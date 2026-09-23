package dash.data;

import dash.security.FilePermissions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Durable operations workflows shared by the local Dash variants. */
public final class OperationsManager {
    private static final long MAX_STATE_FILE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_DRILL_BYTES = 64L * 1024L * 1024L * 1024L;
    private static final int MAX_DRILL_ENTRIES = 100_000;
    private static final int MAX_TRACKED_CONFIGS = 500;
    private static final Pattern MC_VERSION = Pattern.compile("(?i)(?:minecraft server version|starting minecraft server version)\\s+([0-9][0-9A-Za-z._+-]*)");

    private final Path dataDir;
    private final Path serverRoot;
    private final Path databaseFile;
    private final String platform;

    public OperationsManager(Path dataDir, Path serverRoot, String platform) {
        this.dataDir = requirePath(dataDir, "Operations data directory");
        this.serverRoot = requirePath(serverRoot, "Server root");
        this.databaseFile = this.dataDir.resolve("operations.db").normalize();
        this.platform = clean(platform, 48, "Unknown");
        if (!this.databaseFile.startsWith(this.dataDir)) {
            throw new IllegalArgumentException("Operations database path escaped its data directory.");
        }
        initialize();
    }

    public synchronized Snapshot snapshot() {
        recordCapacity(false);
        return new Snapshot(
                plans(100),
                incidents(100),
                handovers(100),
                drills(50),
                automations(100),
                capacitySamples(180),
                driftReport(),
                compatibilityReport(),
                alertBundles(),
                capacityForecast(),
                backupCandidates());
    }

    public synchronized String createPlan(String title, String changeType, String scheduledAt,
            String details, String actor) {
        String safeTitle = clean(title, 120, "");
        String safeDetails = clean(details, 2000, "");
        String type = normalizeChoice(changeType, Set.of("update", "configuration", "plugin", "restart", "security"), "configuration");
        if (safeTitle.isBlank()) {
            return "Plan title is required.";
        }
        long schedule;
        try {
            schedule = parseSchedule(scheduledAt);
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }
        String runbook = String.join(" -> ", "Preflight", "Verified backup", "Maintenance notice",
                "Apply " + type, "Health check", "Rollback if unhealthy");
        String sql = "INSERT INTO ops_plans(id,title,change_type,scheduled_at,status,details,runbook,created_by,created_at,last_result) VALUES(?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, safeTitle);
            ps.setString(3, type);
            ps.setLong(4, schedule);
            ps.setString(5, "draft");
            ps.setString(6, safeDetails);
            ps.setString(7, runbook);
            ps.setString(8, clean(actor, 64, "web"));
            ps.setLong(9, System.currentTimeMillis());
            ps.setString(10, "Awaiting preflight.");
            ps.executeUpdate();
            return "Maintenance plan created.";
        } catch (SQLException ex) {
            return "Maintenance plan could not be saved.";
        }
    }

    public synchronized String preparePlan(String id, String backupName) {
        Plan plan = findPlan(id).orElse(null);
        if (plan == null) {
            return "Maintenance plan not found.";
        }
        CompatibilityReport compatibility = compatibilityReport();
        DriftReport drift = driftReport();
        String backup = clean(backupName, 180, "");
        boolean backupPresent = !backup.isBlank() && backupCandidates().contains(backup);
        String result = "Preflight: root=" + (Files.isReadable(serverRoot) && Files.isWritable(serverRoot) ? "ready" : "blocked")
                + ", backup=" + (backupPresent ? backup : "missing")
                + ", invalid-jars=" + compatibility.invalidJars()
                + ", drift=" + drift.changedCount() + ".";
        if (!Files.isReadable(serverRoot) || !Files.isWritable(serverRoot) || !backupPresent || compatibility.invalidJars() > 0) {
            updatePlan(plan.id(), "draft", result);
            return "Preflight blocked. " + result;
        }
        updatePlan(plan.id(), "ready", result);
        return "Preflight passed. Plan is ready.";
    }

    public synchronized String updatePlanStatus(String id, String status) {
        String safe = normalizeChoice(status, Set.of("draft", "ready", "running", "completed", "cancelled"), "");
        if (safe.isBlank()) {
            return "Unsupported plan status.";
        }
        Plan plan = findPlan(id).orElse(null);
        if (plan == null) {
            return "Maintenance plan not found.";
        }
        if ("running".equals(safe) && !"ready".equals(plan.status())) {
            return "Run preflight before starting this plan.";
        }
        updatePlan(plan.id(), safe, "Status changed to " + safe + " at " + Instant.now() + ".");
        return "Maintenance plan status updated.";
    }

    public ChangePreview previewChange(String changeType, String rawTarget, String proposedValue) {
        String type = normalizeChoice(changeType, Set.of("update", "configuration", "plugin", "restart", "security"), "configuration");
        String target = clean(rawTarget, 240, "server.properties").replace('\\', '/');
        String value = clean(proposedValue, 500, "");
        if (target.startsWith("/") || target.contains("../") || target.equals("..") || target.contains(":")) {
            return new ChangePreview(false, "blocked", true, List.of(), "Target path is outside the server root.");
        }
        Path resolved = serverRoot.resolve(target).normalize();
        if (!resolved.startsWith(serverRoot)) {
            return new ChangePreview(false, "blocked", true, List.of(), "Target path is outside the server root.");
        }
        boolean restart = !"security".equals(type) || target.endsWith(".jar") || target.contains("server.properties");
        String risk = switch (type) {
            case "plugin", "update" -> "high";
            case "security" -> "critical";
            case "restart" -> "low";
            default -> target.contains("server.properties") ? "medium" : "low";
        };
        List<String> effects = new ArrayList<>();
        effects.add(Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) ? "Existing target will change" : "New target will be created");
        effects.add(restart ? "Server restart required" : "No restart detected");
        effects.add(("high".equals(risk) || "critical".equals(risk)) ? "Verified backup required" : "Backup recommended");
        if (!value.isBlank()) effects.add("Proposed value captured (" + value.length() + " characters)");
        return new ChangePreview(true, risk, restart, List.copyOf(effects),
                "Preview only. No server files were changed.");
    }

    public synchronized String createIncident(String title, String severity, String summary, String owner) {
        String safeTitle = clean(title, 140, "");
        String safeSummary = clean(summary, 4000, "");
        if (safeTitle.isBlank() || safeSummary.isBlank()) {
            return "Incident title and summary are required.";
        }
        String safeSeverity = normalizeChoice(severity, Set.of("info", "warning", "critical"), "warning");
        String sql = "INSERT INTO ops_incidents(id,title,severity,status,summary,owner,created_at,closed_at,report) VALUES(?,?,?,?,?,?,?,?,?)";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, safeTitle);
            ps.setString(3, safeSeverity);
            ps.setString(4, "open");
            ps.setString(5, safeSummary);
            ps.setString(6, clean(owner, 64, "web"));
            ps.setLong(7, System.currentTimeMillis());
            ps.setLong(8, 0L);
            ps.setString(9, "");
            ps.executeUpdate();
            return "Incident mode started.";
        } catch (SQLException ex) {
            return "Incident could not be saved.";
        }
    }

    public synchronized String closeIncident(String id, String resolution, String actor) {
        Incident incident = findIncident(id).orElse(null);
        if (incident == null) {
            return "Incident not found.";
        }
        if (!"open".equals(incident.status())) {
            return "Incident is already closed.";
        }
        long closedAt = System.currentTimeMillis();
        long durationMinutes = Math.max(0L, TimeUnit.MILLISECONDS.toMinutes(closedAt - incident.createdAt()));
        DriftReport drift = driftReport();
        CompatibilityReport compatibility = compatibilityReport();
        String report = "Post-incident report\n"
                + "Incident: " + incident.title() + "\n"
                + "Severity: " + incident.severity() + "\n"
                + "Owner: " + incident.owner() + "\n"
                + "Duration: " + durationMinutes + " minutes\n"
                + "Summary: " + incident.summary() + "\n"
                + "Resolution: " + clean(resolution, 4000, "No resolution note supplied.") + "\n"
                + "Closed by: " + clean(actor, 64, "web") + "\n"
                + "Config drift at close: " + drift.changedCount() + "\n"
                + "Invalid jars at close: " + compatibility.invalidJars() + "\n";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE ops_incidents SET status='closed',closed_at=?,report=? WHERE id=? AND status='open'")) {
            ps.setLong(1, closedAt);
            ps.setString(2, report);
            ps.setString(3, incident.id());
            return ps.executeUpdate() == 1 ? "Incident closed and post-incident report generated." : "Incident was not changed.";
        } catch (SQLException ex) {
            return "Incident could not be closed.";
        }
    }

    public synchronized String createHandover(String summary, String author) {
        String safe = clean(summary, 3000, "");
        if (safe.isBlank()) return "Handover summary is required.";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ops_handovers(id,author,summary,status,created_at,ack_by,ack_at) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, clean(author, 64, "web"));
            ps.setString(3, safe);
            ps.setString(4, "open");
            ps.setLong(5, System.currentTimeMillis());
            ps.setString(6, "");
            ps.setLong(7, 0L);
            ps.executeUpdate();
            return "Shift handover published.";
        } catch (SQLException ex) {
            return "Shift handover could not be saved.";
        }
    }

    public synchronized String acknowledgeHandover(String id, String actor) {
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE ops_handovers SET status='acknowledged',ack_by=?,ack_at=? WHERE id=? AND status='open'")) {
            ps.setString(1, clean(actor, 64, "web"));
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, clean(id, 80, ""));
            return ps.executeUpdate() == 1 ? "Shift handover acknowledged." : "Open handover not found.";
        } catch (SQLException ex) {
            return "Shift handover could not be acknowledged.";
        }
    }

    public synchronized String saveDriftBaseline() {
        Map<String, FileFingerprint> files = trackedConfigs();
        try (Connection connection = connect(); Statement clear = connection.createStatement();
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO ops_baseline(path,sha256,size,modified_at) VALUES(?,?,?,?)")) {
            connection.setAutoCommit(false);
            clear.executeUpdate("DELETE FROM ops_baseline");
            for (Map.Entry<String, FileFingerprint> entry : files.entrySet()) {
                insert.setString(1, entry.getKey());
                insert.setString(2, entry.getValue().sha256());
                insert.setLong(3, entry.getValue().size());
                insert.setLong(4, entry.getValue().modifiedAt());
                insert.addBatch();
            }
            insert.executeBatch();
            connection.commit();
            return "Configuration baseline saved for " + files.size() + " files.";
        } catch (SQLException ex) {
            return "Configuration baseline could not be saved.";
        }
    }

    public DriftReport driftReport() {
        Map<String, FileFingerprint> baseline = baseline();
        Map<String, FileFingerprint> current = trackedConfigs();
        if (baseline.isEmpty()) {
            return new DriftReport(false, 0, 0, 0, List.of("No baseline saved yet."));
        }
        List<String> details = new ArrayList<>();
        int changed = 0;
        int added = 0;
        int missing = 0;
        for (Map.Entry<String, FileFingerprint> entry : current.entrySet()) {
            FileFingerprint before = baseline.get(entry.getKey());
            if (before == null) {
                added++;
                details.add("Added: " + entry.getKey());
            } else if (!before.sha256().equals(entry.getValue().sha256())) {
                changed++;
                details.add("Changed: " + entry.getKey());
            }
        }
        for (String path : baseline.keySet()) {
            if (!current.containsKey(path)) {
                missing++;
                details.add("Missing: " + path);
            }
        }
        return new DriftReport(true, changed, added, missing, List.copyOf(details.stream().limit(80).toList()));
    }

    public synchronized String runRestoreDrill(String backupName) {
        String safeName = safeFileName(backupName);
        if (safeName.isBlank() || !safeName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return "Choose a valid backup archive.";
        }
        Path backup = locateBackup(safeName).orElse(null);
        String status = "failed";
        String details;
        if (backup == null) {
            details = "Backup archive was not found.";
        } else {
            try {
                DrillVerification verification = verifyArchive(backup);
                status = "passed";
                details = "Verified " + verification.entries() + " entries and " + verification.bytes() + " uncompressed bytes without extraction.";
            } catch (Exception ex) {
                details = "Verification failed: " + clean(ex.getMessage(), 500, "invalid archive");
            }
        }
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ops_drills(id,backup_name,status,details,checked_at) VALUES(?,?,?,?,?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, safeName);
            ps.setString(3, status);
            ps.setString(4, details);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ignored) {
            return "Restore drill completed but its result could not be stored.";
        }
        return "passed".equals(status) ? "Restore drill passed. " + details : "Restore drill failed safely. " + details;
    }

    public CompatibilityReport compatibilityReport() {
        List<String> warnings = new ArrayList<>();
        List<Path> jars = new ArrayList<>();
        for (String folder : List.of("plugins", "mods")) {
            Path dir = serverRoot.resolve(folder);
            if (!Files.isDirectory(dir) || Files.isSymbolicLink(dir)) continue;
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .limit(400)
                        .forEach(jars::add);
            } catch (IOException ignored) {
            }
        }
        int invalid = 0;
        Map<String, Integer> identities = new HashMap<>();
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            String identity = name.toLowerCase(Locale.ROOT)
                    .replaceAll("[-_](?:v)?[0-9][0-9a-z._+-]*?(?=\\.jar$)", "")
                    .replace(".jar", "");
            identities.merge(identity, 1, Integer::sum);
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                boolean descriptor = zip.getEntry("plugin.yml") != null
                        || zip.getEntry("fabric.mod.json") != null
                        || zip.getEntry("META-INF/mods.toml") != null
                        || zip.getEntry("META-INF/neoforge.mods.toml") != null;
                if (!descriptor) warnings.add(name + " has no recognized loader descriptor.");
            } catch (IOException ex) {
                invalid++;
                warnings.add(name + " is not a readable JAR.");
            }
        }
        identities.forEach((identity, count) -> {
            if (count > 1 && !identity.isBlank()) warnings.add("Possible duplicate artifact: " + identity + " (" + count + ")");
        });
        String mcVersion = detectMinecraftVersion();
        if (mcVersion.isBlank()) warnings.add("Minecraft version could not be detected from logs.");
        int duplicates = (int) identities.values().stream().filter(count -> count > 1).count();
        return new CompatibilityReport(platform, System.getProperty("java.version", "unknown"), mcVersion,
                jars.size(), invalid, duplicates, List.copyOf(warnings.stream().limit(80).toList()));
    }

    public List<AlertBundle> alertBundles() {
        Path latest = serverRoot.resolve("logs").resolve("latest.log");
        if (!Files.isRegularFile(latest, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(latest)) return List.of();
        List<String> lines;
        try {
            if (Files.size(latest) > 64L * 1024L * 1024L) return List.of();
            lines = Files.readAllLines(latest, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return List.of();
        }
        Map<String, MutableAlert> grouped = new LinkedHashMap<>();
        int start = Math.max(0, lines.size() - 800);
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i) == null ? "" : lines.get(i).trim();
            String lower = line.toLowerCase(Locale.ROOT);
            String category = null;
            String severity = "warning";
            if (lower.contains("crash") || lower.contains("exception")) {
                category = "crash";
                severity = "critical";
            } else if (lower.contains("[error") || lower.contains(" severe ")) {
                category = "errors";
                severity = "critical";
            } else if (lower.contains("unauthorized") || lower.contains("login_failed") || lower.contains("access_denied")) {
                category = "security";
                severity = "critical";
            } else if (lower.contains("can't keep up") || lower.contains("overloaded") || lower.contains("low tps")) {
                category = "performance";
            } else if (lower.contains("[warn") || lower.contains(" warning")) {
                category = "warnings";
            }
            if (category != null) {
                String bundleSeverity = severity;
                grouped.computeIfAbsent(category, key -> new MutableAlert(bundleSeverity)).add(line);
            }
        }
        Set<String> acknowledged = acknowledgedAlerts();
        List<AlertBundle> result = new ArrayList<>();
        grouped.forEach((category, alert) -> {
            String signature = shortHash(category + "|" + alert.count + "|" + alert.lastLine);
            result.add(new AlertBundle(signature, category, alert.severity, alert.count,
                    clean(alert.lastLine, 300, ""), acknowledged.contains(signature)));
        });
        result.sort(Comparator.comparing(AlertBundle::severity).thenComparing(AlertBundle::count).reversed());
        return List.copyOf(result);
    }

    public synchronized String acknowledgeAlert(String signature) {
        String safe = clean(signature, 80, "");
        if (safe.isBlank() || alertBundles().stream().noneMatch(bundle -> bundle.signature().equals(safe))) {
            return "Alert bundle not found.";
        }
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO ops_ack(signature,acknowledged_at) VALUES(?,?)")) {
            ps.setString(1, safe);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
            return "Alert bundle acknowledged.";
        } catch (SQLException ex) {
            return "Alert bundle could not be acknowledged.";
        }
    }

    public synchronized String recordCapacity(boolean force) {
        List<CapacitySample> samples = capacitySamples(1);
        long now = System.currentTimeMillis();
        if (!force && !samples.isEmpty() && now - samples.get(0).timestamp() < TimeUnit.HOURS.toMillis(6)) {
            return "Capacity sample is current.";
        }
        try {
            FileStore store = Files.getFileStore(serverRoot);
            long serverBytes = directorySize(serverRoot, 80_000, Set.of("backups", ".neodash/backups"));
            long backupBytes = backupBytes();
            try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ops_capacity(sampled_at,total_bytes,usable_bytes,server_bytes,backup_bytes) VALUES(?,?,?,?,?)")) {
                ps.setLong(1, now);
                ps.setLong(2, store.getTotalSpace());
                ps.setLong(3, store.getUsableSpace());
                ps.setLong(4, serverBytes);
                ps.setLong(5, backupBytes);
                ps.executeUpdate();
            }
            trimCapacity();
            return "Capacity sample recorded.";
        } catch (Exception ex) {
            return "Capacity sample failed safely.";
        }
    }

    public CapacityForecast capacityForecast() {
        List<CapacitySample> newestFirst = capacitySamples(180);
        if (newestFirst.isEmpty()) return new CapacityForecast(0L, 0L, 0.0d, -1L, "No capacity sample yet.");
        List<CapacitySample> chronological = new ArrayList<>(newestFirst);
        chronological.sort(Comparator.comparingLong(CapacitySample::timestamp));
        CapacitySample latest = chronological.get(chronological.size() - 1);
        if (chronological.size() < 2) {
            return new CapacityForecast(latest.usableBytes(), latest.serverBytes() + latest.backupBytes(), 0.0d, -1L,
                    "Collect another sample later to calculate growth.");
        }
        CapacitySample first = chronological.get(0);
        long elapsed = Math.max(1L, latest.timestamp() - first.timestamp());
        long growth = (latest.serverBytes() + latest.backupBytes()) - (first.serverBytes() + first.backupBytes());
        double perDay = growth * (double) TimeUnit.DAYS.toMillis(1) / elapsed;
        long days = perDay <= 0.0d ? -1L : Math.max(0L, (long) (latest.usableBytes() / perDay));
        String message = perDay <= 0.0d ? "Storage is stable or shrinking." : "Estimated " + days + " days until the current free space is consumed.";
        return new CapacityForecast(latest.usableBytes(), latest.serverBytes() + latest.backupBytes(), perDay, days, message);
    }

    public synchronized String recordAutomation(String recipe, int intervalMinutes, String payload, int taskId,
            boolean enabled) {
        String safeRecipe = normalizeChoice(recipe,
                Set.of("daily_backup", "nightly_restart", "hourly_save", "maintenance_notice"), "");
        if (safeRecipe.isBlank() || intervalMinutes < 1 || intervalMinutes > 10080 || taskId < -1) {
            return "Unsupported automation recipe.";
        }
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ops_automations(id,recipe,interval_minutes,payload,task_id,enabled,created_at) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, safeRecipe);
            ps.setInt(3, intervalMinutes);
            ps.setString(4, clean(payload, 500, ""));
            ps.setInt(5, taskId);
            ps.setInt(6, enabled ? 1 : 0);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
            return "Automation recipe activated.";
        } catch (SQLException ex) {
            return "Automation recipe could not be saved.";
        }
    }

    public synchronized String setAutomationEnabled(String id, boolean enabled) {
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE ops_automations SET enabled=? WHERE id=?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setString(2, clean(id, 80, ""));
            return ps.executeUpdate() == 1 ? "Automation recipe updated." : "Automation recipe not found.";
        } catch (SQLException ex) {
            return "Automation recipe could not be updated.";
        }
    }

    public Optional<Automation> findAutomation(String id) {
        return automations(200).stream().filter(item -> item.id().equals(clean(id, 80, ""))).findFirst();
    }

    public PlayerEvidence playerEvidence(String playerName) {
        String player = clean(playerName, 64, "");
        if (player.isBlank()) return new PlayerEvidence("", 0, 0, 0, 0L);
        int tickets = 0;
        int notes = 0;
        long latest = 0L;
        Path staff = dataDir.resolve("staff-workflow.txt");
        if (Files.isRegularFile(staff, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(staff)) {
            try {
                if (Files.size(staff) <= 8L * 1024L * 1024L) {
                    for (String line : Files.readAllLines(staff, StandardCharsets.UTF_8)) {
                        String[] parts = line.split("\\|", -1);
                        if (parts.length < 9) continue;
                        String target = decode(parts[7]);
                        if (!player.equalsIgnoreCase(target)) continue;
                        if ("note".equalsIgnoreCase(parts[1])) notes++; else tickets++;
                        try { latest = Math.max(latest, Long.parseLong(parts[8])); } catch (NumberFormatException ignored) { }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        int incidentCount = 0;
        for (Incident incident : incidents(500)) {
            if (incident.title().toLowerCase(Locale.ROOT).contains(player.toLowerCase(Locale.ROOT))
                    || incident.summary().toLowerCase(Locale.ROOT).contains(player.toLowerCase(Locale.ROOT))) {
                incidentCount++;
                latest = Math.max(latest, incident.createdAt());
            }
        }
        return new PlayerEvidence(player, tickets, notes, incidentCount, latest);
    }

    public String securityEvidenceJson(String actor) {
        Snapshot snapshot = snapshot();
        StringBuilder json = new StringBuilder("{");
        json.append("\"generatedAt\":").append(System.currentTimeMillis()).append(',');
        json.append("\"generatedBy\":\"").append(json(actor)).append("\",");
        json.append("\"platform\":\"").append(json(platform)).append("\",");
        json.append("\"serverRootFingerprint\":\"").append(json(shortHash(serverRoot.toString()))).append("\",");
        json.append("\"drift\":{")
                .append("\"baseline\":").append(snapshot.drift().baselinePresent()).append(',')
                .append("\"changed\":").append(snapshot.drift().changedCount()).append(',')
                .append("\"added\":").append(snapshot.drift().addedCount()).append(',')
                .append("\"missing\":").append(snapshot.drift().missingCount()).append("},");
        json.append("\"compatibility\":{")
                .append("\"minecraft\":\"").append(json(snapshot.compatibility().minecraftVersion())).append("\",")
                .append("\"java\":\"").append(json(snapshot.compatibility().javaVersion())).append("\",")
                .append("\"jars\":").append(snapshot.compatibility().jarCount()).append(',')
                .append("\"invalidJars\":").append(snapshot.compatibility().invalidJars()).append("},");
        json.append("\"plans\":").append(plansJson(snapshot.plans())).append(',');
        json.append("\"incidents\":").append(incidentsJson(snapshot.incidents())).append(',');
        json.append("\"restoreDrills\":").append(drillsJson(snapshot.drills())).append(',');
        json.append("\"alertBundles\":").append(alertsJson(snapshot.alertBundles()));
        return json.append('}').toString();
    }

    public String secretFingerprint(String secret) {
        String value = secret == null ? "" : secret.trim();
        return value.isBlank() ? "Not configured" : shortHash(value);
    }

    public static boolean permissionMatches(Set<String> granted, String requested) {
        if (requested == null || requested.isBlank() || granted == null) return false;
        String target = requested.trim().toLowerCase(Locale.ROOT);
        for (String raw : granted) {
            String permission = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (permission.equals("*") || permission.equals(target)) return true;
            if (permission.endsWith(".*") && target.startsWith(permission.substring(0, permission.length() - 1))) return true;
        }
        return false;
    }

    public List<String> backupCandidates() {
        Set<String> names = new LinkedHashSet<>();
        for (Path dir : backupDirectories()) {
            if (!Files.isDirectory(dir) || Files.isSymbolicLink(dir)) continue;
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !Files.isSymbolicLink(path))
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".zip"))
                        .sorted()
                        .forEach(names::add);
            } catch (IOException ignored) {
            }
        }
        return List.copyOf(names);
    }

    private void initialize() {
        try {
            Files.createDirectories(dataDir);
            Class.forName("org.sqlite.JDBC");
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ops_plans(id TEXT PRIMARY KEY,title TEXT NOT NULL,change_type TEXT NOT NULL,scheduled_at INTEGER NOT NULL,status TEXT NOT NULL,details TEXT NOT NULL,runbook TEXT NOT NULL,created_by TEXT NOT NULL,created_at INTEGER NOT NULL,last_result TEXT NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ops_incidents(id TEXT PRIMARY KEY,title TEXT NOT NULL,severity TEXT NOT NULL,status TEXT NOT NULL,summary TEXT NOT NULL,owner TEXT NOT NULL,created_at INTEGER NOT NULL,closed_at INTEGER NOT NULL,report TEXT NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ops_handovers(id TEXT PRIMARY KEY,author TEXT NOT NULL,summary TEXT NOT NULL,status TEXT NOT NULL,created_at INTEGER NOT NULL,ack_by TEXT NOT NULL,ack_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ops_drills(id TEXT PRIMARY KEY,backup_name TEXT NOT NULL,status TEXT NOT NULL,details TEXT NOT NULL,checked_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ops_automations(id TEXT PRIMARY KEY,recipe TEXT NOT NULL,interval_minutes INTEGER NOT NULL,payload TEXT NOT NULL,task_id INTEGER NOT NULL,enabled INTEGER NOT NULL,created_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ops_capacity(id INTEGER PRIMARY KEY AUTOINCREMENT,sampled_at INTEGER NOT NULL,total_bytes INTEGER NOT NULL,usable_bytes INTEGER NOT NULL,server_bytes INTEGER NOT NULL,backup_bytes INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ops_baseline(path TEXT PRIMARY KEY,sha256 TEXT NOT NULL,size INTEGER NOT NULL,modified_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ops_ack(signature TEXT PRIMARY KEY,acknowledged_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ops_plans_schedule ON ops_plans(scheduled_at)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ops_incidents_created ON ops_incidents(created_at)");
            }
            FilePermissions.ownerReadWrite(databaseFile);
        } catch (Exception ex) {
            throw new IllegalStateException("Operations storage could not be initialized: " + ex.getMessage(), ex);
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private List<Plan> plans(int limit) {
        List<Plan> rows = new ArrayList<>();
        String sql = "SELECT * FROM ops_plans ORDER BY CASE status WHEN 'running' THEN 0 WHEN 'ready' THEN 1 WHEN 'draft' THEN 2 ELSE 3 END,scheduled_at ASC LIMIT ?";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new Plan(rs.getString("id"), rs.getString("title"), rs.getString("change_type"),
                        rs.getLong("scheduled_at"), rs.getString("status"), rs.getString("details"), rs.getString("runbook"),
                        rs.getString("created_by"), rs.getLong("created_at"), rs.getString("last_result")));
            }
        } catch (SQLException ignored) {
        }
        return List.copyOf(rows);
    }

    private Optional<Plan> findPlan(String id) {
        String target = clean(id, 80, "");
        return plans(500).stream().filter(plan -> plan.id().equals(target)).findFirst();
    }

    private void updatePlan(String id, String status, String result) {
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE ops_plans SET status=?,last_result=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setString(2, clean(result, 1000, ""));
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    private List<Incident> incidents(int limit) {
        List<Incident> rows = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM ops_incidents ORDER BY CASE status WHEN 'open' THEN 0 ELSE 1 END,created_at DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new Incident(rs.getString("id"), rs.getString("title"), rs.getString("severity"),
                        rs.getString("status"), rs.getString("summary"), rs.getString("owner"), rs.getLong("created_at"),
                        rs.getLong("closed_at"), rs.getString("report")));
            }
        } catch (SQLException ignored) {
        }
        return List.copyOf(rows);
    }

    private Optional<Incident> findIncident(String id) {
        String target = clean(id, 80, "");
        return incidents(500).stream().filter(incident -> incident.id().equals(target)).findFirst();
    }

    private List<Handover> handovers(int limit) {
        List<Handover> rows = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM ops_handovers ORDER BY CASE status WHEN 'open' THEN 0 ELSE 1 END,created_at DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new Handover(rs.getString("id"), rs.getString("author"), rs.getString("summary"),
                        rs.getString("status"), rs.getLong("created_at"), rs.getString("ack_by"), rs.getLong("ack_at")));
            }
        } catch (SQLException ignored) {
        }
        return List.copyOf(rows);
    }

    private List<RestoreDrill> drills(int limit) {
        List<RestoreDrill> rows = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM ops_drills ORDER BY checked_at DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new RestoreDrill(rs.getString("id"), rs.getString("backup_name"),
                        rs.getString("status"), rs.getString("details"), rs.getLong("checked_at")));
            }
        } catch (SQLException ignored) {
        }
        return List.copyOf(rows);
    }

    private List<Automation> automations(int limit) {
        List<Automation> rows = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM ops_automations ORDER BY created_at DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new Automation(rs.getString("id"), rs.getString("recipe"),
                        rs.getInt("interval_minutes"), rs.getString("payload"), rs.getInt("task_id"),
                        rs.getInt("enabled") == 1, rs.getLong("created_at")));
            }
        } catch (SQLException ignored) {
        }
        return List.copyOf(rows);
    }

    private List<CapacitySample> capacitySamples(int limit) {
        List<CapacitySample> rows = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "SELECT sampled_at,total_bytes,usable_bytes,server_bytes,backup_bytes FROM ops_capacity ORDER BY sampled_at DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(limit, 1000)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new CapacitySample(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
            }
        } catch (SQLException ignored) {
        }
        return List.copyOf(rows);
    }

    private Map<String, FileFingerprint> baseline() {
        Map<String, FileFingerprint> result = new LinkedHashMap<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT path,sha256,size,modified_at FROM ops_baseline ORDER BY path")) {
            while (rs.next()) result.put(rs.getString(1), new FileFingerprint(rs.getString(2), rs.getLong(3), rs.getLong(4)));
        } catch (SQLException ignored) {
        }
        return result;
    }

    private Map<String, FileFingerprint> trackedConfigs() {
        Map<String, FileFingerprint> result = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(serverRoot, 5)) {
            stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(this::isTrackedConfig)
                    .filter(this::isTrackableStateFile)
                    .sorted()
                    .limit(MAX_TRACKED_CONFIGS)
                    .forEach(path -> {
                        try {
                            result.put(serverRoot.relativize(path).toString().replace('\\', '/'),
                                    new FileFingerprint(hashFile(path), Files.size(path), Files.getLastModifiedTime(path).toMillis()));
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
        return result;
    }

    private boolean isTrackedConfig(Path path) {
        String rel = serverRoot.relativize(path).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (rel.contains("/backups/") || rel.startsWith("backups/") || rel.startsWith("logs/")
                || rel.contains("/cache/") || rel.contains("/world/") || rel.contains("playerdata/")) return false;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".toml")
                || name.endsWith(".properties") || name.endsWith(".json");
    }

    private boolean isTrackableStateFile(Path path) {
        try {
            return Files.size(path) <= MAX_STATE_FILE_BYTES;
        } catch (IOException ignored) {
            return false;
        }
    }

    private Optional<Path> locateBackup(String name) {
        for (Path directory : backupDirectories()) {
            Path normalized = directory.toAbsolutePath().normalize();
            Path candidate = normalized.resolve(name).normalize();
            if (candidate.startsWith(normalized) && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private List<Path> backupDirectories() {
        return List.of(
                dataDir.resolve("backups").toAbsolutePath().normalize(),
                serverRoot.resolve("backups").toAbsolutePath().normalize(),
                serverRoot.resolve("plugins").resolve("Dash").resolve("backups").toAbsolutePath().normalize(),
                serverRoot.resolve("config").resolve("fabricdash").resolve("backups").toAbsolutePath().normalize(),
                serverRoot.resolve(".neodash").resolve("backups").toAbsolutePath().normalize());
    }

    private DrillVerification verifyArchive(Path backup) throws IOException {
        int entries = 0;
        long bytes = 0L;
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(backup.toFile())) {
            var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                String name = entry.getName();
                if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                        || name.contains("\\") || name.contains(":")) {
                    throw new IOException("Unsafe ZIP entry name.");
                }
                for (String segment : name.split("/", -1)) {
                    if (".".equals(segment) || "..".equals(segment)) {
                        throw new IOException("Unsafe ZIP entry name.");
                    }
                }
                Path normalized;
                try {
                    normalized = Path.of(name).normalize();
                } catch (RuntimeException ex) {
                    throw new IOException("Unsafe ZIP entry name.", ex);
                }
                if (normalized.isAbsolute() || normalized.startsWith("..")) {
                    throw new IOException("Unsafe ZIP entry name.");
                }
                String duplicateKey = normalized.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (duplicateKey.isBlank() || !names.add(duplicateKey)) {
                    throw new IOException("Duplicate ZIP entry: " + name);
                }
                if (++entries > MAX_DRILL_ENTRIES) throw new IOException("Archive contains too many entries.");
                long declaredSize = entry.getSize();
                if (declaredSize > MAX_DRILL_BYTES - bytes) {
                    throw new IOException("Archive exceeds the restore-drill size limit.");
                }
                long entryBytes = 0L;
                CRC32 crc = new CRC32();
                if (!entry.isDirectory()) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        byte[] buffer = new byte[32 * 1024];
                        int read;
                        while ((read = input.read(buffer)) >= 0) {
                            if (read == 0) continue;
                            if (entryBytes > MAX_DRILL_BYTES - bytes - read) {
                                throw new IOException("Archive exceeds the restore-drill size limit.");
                            }
                            entryBytes += read;
                            crc.update(buffer, 0, read);
                        }
                    }
                }
                if (declaredSize >= 0L && declaredSize != entryBytes) {
                    throw new IOException("ZIP entry size mismatch: " + name);
                }
                if (entry.getCrc() >= 0L && entry.getCrc() != crc.getValue()) {
                    throw new IOException("ZIP entry checksum mismatch: " + name);
                }
                bytes += entryBytes;
            }
        }
        if (entries == 0) throw new IOException("Archive is empty.");
        return new DrillVerification(entries, bytes);
    }

    private Set<String> acknowledgedAlerts() {
        Set<String> result = new HashSet<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT signature FROM ops_ack")) {
            while (rs.next()) result.add(rs.getString(1));
        } catch (SQLException ignored) {
        }
        return result;
    }

    private String detectMinecraftVersion() {
        Path latest = serverRoot.resolve("logs").resolve("latest.log");
        if (!Files.isRegularFile(latest, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(latest)) return "";
        try {
            if (Files.size(latest) > MAX_STATE_FILE_BYTES) return "";
            List<String> lines = Files.readAllLines(latest, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - 1000);
            for (int i = lines.size() - 1; i >= start; i--) {
                Matcher matcher = MC_VERSION.matcher(lines.get(i));
                if (matcher.find()) return matcher.group(1);
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    private long directorySize(Path root, int maxFiles, Set<String> skipped) throws IOException {
        long[] size = {0L};
        int[] files = {0};
        try (Stream<Path> stream = Files.walk(root, 12)) {
            var iterator = stream.iterator();
            while (iterator.hasNext() && files[0] < maxFiles) {
                Path path = iterator.next();
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String rel = root.relativize(path).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (skipped.stream().anyMatch(skip -> rel.equals(skip) || rel.startsWith(skip + "/") || rel.contains("/" + skip + "/"))) continue;
                size[0] += Files.size(path);
                files[0]++;
            }
        }
        return size[0];
    }

    private long backupBytes() {
        long total = 0L;
        Set<Path> seen = new HashSet<>();
        for (Path dir : backupDirectories()) {
            if (!seen.add(dir) || !Files.isDirectory(dir) || Files.isSymbolicLink(dir)) continue;
            try (Stream<Path> stream = Files.list(dir)) {
                total += stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !Files.isSymbolicLink(path))
                        .mapToLong(path -> {
                            try { return Files.size(path); } catch (IOException ignored) { return 0L; }
                        }).sum();
            } catch (IOException ignored) {
            }
        }
        return total;
    }

    private void trimCapacity() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM ops_capacity WHERE id NOT IN (SELECT id FROM ops_capacity ORDER BY sampled_at DESC LIMIT 180)");
        } catch (SQLException ignored) {
        }
    }

    private long parseSchedule(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) return System.currentTimeMillis();
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Scheduled time is invalid.");
        }
    }

    private static Path requirePath(Path path, String label) {
        if (path == null) throw new IllegalArgumentException(label + " is required.");
        return path.toAbsolutePath().normalize();
    }

    private static String normalizeChoice(String value, Set<String> allowed, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
    }

    private static String clean(String value, int max, String fallback) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) return fallback == null ? "" : fallback;
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private static String safeFileName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return "";
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String hashFile(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (Exception ex) {
            if (ex instanceof IOException io) throw io;
            throw new IOException("SHA-256 unavailable.", ex);
        }
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception ex) {
            return "unavailable";
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }

    private static String decode(String value) {
        try { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (Exception ignored) { return ""; }
    }

    private static String json(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c >= 0x20) out.append(c); }
            }
        }
        return out.toString();
    }

    private static String plansJson(List<Plan> rows) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) out.append(',');
            Plan row = rows.get(i);
            out.append("{\"id\":\"").append(json(row.id())).append("\",\"title\":\"").append(json(row.title()))
                    .append("\",\"type\":\"").append(json(row.changeType())).append("\",\"status\":\"")
                    .append(json(row.status())).append("\",\"scheduledAt\":").append(row.scheduledAt()).append('}');
        }
        return out.append(']').toString();
    }

    private static String incidentsJson(List<Incident> rows) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) out.append(',');
            Incident row = rows.get(i);
            out.append("{\"id\":\"").append(json(row.id())).append("\",\"title\":\"").append(json(row.title()))
                    .append("\",\"severity\":\"").append(json(row.severity())).append("\",\"status\":\"")
                    .append(json(row.status())).append("\",\"createdAt\":").append(row.createdAt()).append('}');
        }
        return out.append(']').toString();
    }

    private static String drillsJson(List<RestoreDrill> rows) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) out.append(',');
            RestoreDrill row = rows.get(i);
            out.append("{\"backup\":\"").append(json(row.backupName())).append("\",\"status\":\"")
                    .append(json(row.status())).append("\",\"checkedAt\":").append(row.checkedAt()).append('}');
        }
        return out.append(']').toString();
    }

    private static String alertsJson(List<AlertBundle> rows) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) out.append(',');
            AlertBundle row = rows.get(i);
            out.append("{\"category\":\"").append(json(row.category())).append("\",\"severity\":\"")
                    .append(json(row.severity())).append("\",\"count\":").append(row.count())
                    .append(",\"acknowledged\":").append(row.acknowledged()).append('}');
        }
        return out.append(']').toString();
    }

    private static final class MutableAlert {
        private final String severity;
        private int count;
        private String lastLine = "";

        private MutableAlert(String severity) { this.severity = severity; }
        private void add(String line) { count++; lastLine = line; }
    }

    private record FileFingerprint(String sha256, long size, long modifiedAt) { }
    private record DrillVerification(int entries, long bytes) { }

    public record Plan(String id, String title, String changeType, long scheduledAt, String status, String details,
            String runbook, String createdBy, long createdAt, String lastResult) { }
    public record Incident(String id, String title, String severity, String status, String summary, String owner,
            long createdAt, long closedAt, String report) { }
    public record Handover(String id, String author, String summary, String status, long createdAt, String acknowledgedBy,
            long acknowledgedAt) { }
    public record RestoreDrill(String id, String backupName, String status, String details, long checkedAt) { }
    public record Automation(String id, String recipe, int intervalMinutes, String payload, int taskId, boolean enabled,
            long createdAt) { }
    public record CapacitySample(long timestamp, long totalBytes, long usableBytes, long serverBytes, long backupBytes) { }
    public record ChangePreview(boolean valid, String risk, boolean restartRequired, List<String> effects, String message) { }
    public record DriftReport(boolean baselinePresent, int changedCount, int addedCount, int missingCount, List<String> details) { }
    public record CompatibilityReport(String platform, String javaVersion, String minecraftVersion, int jarCount,
            int invalidJars, int duplicateArtifacts, List<String> warnings) { }
    public record AlertBundle(String signature, String category, String severity, int count, String latestLine,
            boolean acknowledged) { }
    public record CapacityForecast(long usableBytes, long managedBytes, double growthPerDay, long daysRemaining,
            String message) { }
    public record PlayerEvidence(String playerName, int tickets, int notes, int incidents, long latestActivity) { }
    public record Snapshot(List<Plan> plans, List<Incident> incidents, List<Handover> handovers,
            List<RestoreDrill> drills, List<Automation> automations, List<CapacitySample> capacitySamples,
            DriftReport drift, CompatibilityReport compatibility, List<AlertBundle> alertBundles,
            CapacityForecast capacity, List<String> backups) { }
}
