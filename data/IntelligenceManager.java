package dash.data;

import dash.security.FilePermissions;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
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
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Persistent intelligence, recovery and collaboration workflows shared by every Dash variant. */
public final class IntelligenceManager {
    private static final long MAX_LOG_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_STATE_FILE_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_STATE_ARCHIVE_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_BACKUP_ENTRY_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_STATE_FILES = 2_000;
    private static final int MAX_SCAN_FILES = 100_000;
    private static final Pattern SAFE_ARTIFACT = Pattern.compile("[A-Za-z0-9._+ -]{1,180}\\.jar", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLUGIN_NAME = Pattern.compile("(?im)^\\s*name\\s*:\\s*['\"]?([^'\"#\\r\\n]+)");
    private static final Pattern PLUGIN_DEPENDS = Pattern.compile("(?im)^\\s*(depend|softdepend|loadbefore)\\s*:\\s*\\[([^]]*)]");
    private static final Pattern JSON_ID = Pattern.compile("\"(?:id|modid)\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOML_MOD_ID = Pattern.compile("(?im)^\\s*modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern LOG_PLAYER = Pattern.compile("(?i)\\b([A-Za-z0-9_]{3,16})\\b");

    private final Path dataDir;
    private final Path serverRoot;
    private final Path databaseFile;
    private final Path snapshotsDir;
    private final Path quarantineDir;
    private final Path shadowDir;
    private final String platform;

    public IntelligenceManager(Path dataDir, Path serverRoot, String platform) {
        this.dataDir = requirePath(dataDir, "Intelligence data directory");
        this.serverRoot = requirePath(serverRoot, "Server root");
        this.databaseFile = this.dataDir.resolve("intelligence.db").normalize();
        this.snapshotsDir = this.dataDir.resolve("state-time-machine").normalize();
        this.quarantineDir = this.dataDir.resolve("safe-mode-quarantine").normalize();
        this.shadowDir = this.dataDir.resolve("shadow-labs").normalize();
        this.platform = clean(platform, 64, "Unknown");
        if (!databaseFile.startsWith(this.dataDir) || !snapshotsDir.startsWith(this.dataDir)
                || !quarantineDir.startsWith(this.dataDir) || !shadowDir.startsWith(this.dataDir)) {
            throw new IllegalArgumentException("Intelligence storage escaped its data directory.");
        }
        initialize();
    }

    private void initialize() {
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(snapshotsDir);
            Files.createDirectories(quarantineDir);
            Files.createDirectories(shadowDir);
            Class.forName("org.sqlite.JDBC");
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS intel_shadow(id TEXT PRIMARY KEY,status TEXT NOT NULL,summary TEXT NOT NULL,log TEXT NOT NULL,actor TEXT NOT NULL,created_at INTEGER NOT NULL,finished_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS intel_snapshots(id TEXT PRIMARY KEY,label TEXT NOT NULL,archive_path TEXT NOT NULL,manifest TEXT NOT NULL,actor TEXT NOT NULL,created_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS intel_performance(id INTEGER PRIMARY KEY AUTOINCREMENT,sampled_at INTEGER NOT NULL,tps REAL NOT NULL,mspt REAL NOT NULL,memory_mb INTEGER NOT NULL,players INTEGER NOT NULL,label TEXT NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS intel_safe_mode(id TEXT PRIMARY KEY,artifact TEXT NOT NULL,original_path TEXT NOT NULL,quarantine_path TEXT NOT NULL,actor TEXT NOT NULL,created_at INTEGER NOT NULL,restored_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS intel_guardrails(id TEXT PRIMARY KEY,action_pattern TEXT NOT NULL,max_players INTEGER NOT NULL,require_backup INTEGER NOT NULL,quiet_start INTEGER NOT NULL,quiet_end INTEGER NOT NULL,require_reason INTEGER NOT NULL,dual_control INTEGER NOT NULL,enabled INTEGER NOT NULL,created_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS intel_support(id TEXT PRIMARY KEY,type TEXT NOT NULL,player TEXT NOT NULL,subject TEXT NOT NULL,message TEXT NOT NULL,status TEXT NOT NULL,owner TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS intel_support_replies(id TEXT PRIMARY KEY,case_id TEXT NOT NULL,author TEXT NOT NULL,message TEXT NOT NULL,public_reply INTEGER NOT NULL,created_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS intel_temp_grants(id TEXT PRIMARY KEY,username TEXT NOT NULL,permission TEXT NOT NULL,granted_by TEXT NOT NULL,expires_at INTEGER NOT NULL,revoked_at INTEGER NOT NULL,created_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS intel_approvals(id TEXT PRIMARY KEY,action TEXT NOT NULL,payload_hash TEXT NOT NULL,requested_by TEXT NOT NULL,status TEXT NOT NULL,approved_by TEXT NOT NULL,expires_at INTEGER NOT NULL,created_at INTEGER NOT NULL,used_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS intel_retention(id INTEGER PRIMARY KEY CHECK(id=1),log_days INTEGER NOT NULL,backup_days INTEGER NOT NULL,crash_days INTEGER NOT NULL,keep_min_backups INTEGER NOT NULL,updated_by TEXT NOT NULL,updated_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS intel_service(id INTEGER PRIMARY KEY AUTOINCREMENT,sampled_at INTEGER NOT NULL,online INTEGER NOT NULL,tps REAL NOT NULL,mspt REAL NOT NULL,backup_age_hours REAL NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS intel_war_rooms(id TEXT PRIMARY KEY,title TEXT NOT NULL,severity TEXT NOT NULL,status TEXT NOT NULL,commander TEXT NOT NULL,summary TEXT NOT NULL,public_message TEXT NOT NULL,created_at INTEGER NOT NULL,closed_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS intel_war_updates(id TEXT PRIMARY KEY,room_id TEXT NOT NULL,author TEXT NOT NULL,message TEXT NOT NULL,kind TEXT NOT NULL,created_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS intel_status(component TEXT PRIMARY KEY,status TEXT NOT NULL,message TEXT NOT NULL,updated_by TEXT NOT NULL,updated_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_intel_support_status ON intel_support(status,updated_at)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_intel_grants_user ON intel_temp_grants(username,expires_at)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_intel_approvals_action ON intel_approvals(action,status,expires_at)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_intel_service_time ON intel_service(sampled_at)");
                statement.executeUpdate("INSERT OR IGNORE INTO intel_retention(id,log_days,backup_days,crash_days,keep_min_backups,updated_by,updated_at) VALUES(1,14,30,30,3,'system',0)");
                statement.executeUpdate("INSERT OR IGNORE INTO intel_status(component,status,message,updated_by,updated_at) VALUES('Minecraft Server','operational','All systems operational.','system',0)");
            }
            FilePermissions.ownerReadWrite(databaseFile);
        } catch (Exception ex) {
            throw new IllegalStateException("Intelligence storage could not be initialized: " + ex.getMessage(), ex);
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

    // Shadow Boot Lab

    public String startShadowBootLab(Executor executor, String actor) {
        if (executor == null) return "Shadow Boot Lab executor is unavailable.";
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO intel_shadow(id,status,summary,log,actor,created_at,finished_at) VALUES(?,?,?,?,?,?,0)")) {
            ps.setString(1, id);
            ps.setString(2, "running");
            ps.setString(3, "Preparing isolated startup workspace.");
            ps.setString(4, "");
            ps.setString(5, clean(actor, 64, "web"));
            ps.setLong(6, now);
            ps.executeUpdate();
        } catch (SQLException ex) {
            return "Shadow Boot Lab could not be queued.";
        }
        executor.execute(() -> executeShadowLab(id));
        return "Shadow Boot Lab started: " + id.substring(0, 8) + ".";
    }

    public List<ShadowLab> shadowLabs(int limit) {
        List<ShadowLab> rows = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "SELECT id,status,summary,log,actor,created_at,finished_at FROM intel_shadow ORDER BY created_at DESC LIMIT ?")) {
            ps.setInt(1, bounded(limit, 1, 50));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new ShadowLab(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7)));
            }
        } catch (SQLException ignored) {
        }
        return List.copyOf(rows);
    }

    private void executeShadowLab(String id) {
        Path workspace = shadowDir.resolve(id).normalize();
        String status = "failed";
        String summary = "No compatible server launcher was found.";
        StringBuilder output = new StringBuilder();
        Process process = null;
        try {
            if (!workspace.startsWith(shadowDir)) throw new IOException("Unsafe Shadow Lab path.");
            Path launcher = discoverServerLauncher().orElseThrow(() -> new IOException("No server launcher JAR found."));
            Files.createDirectories(workspace);
            Path labJar = workspace.resolve("server.jar");
            Files.copy(launcher, labJar, StandardCopyOption.REPLACE_EXISTING);
            copyShadowInputs(workspace);
            int labPort;
            try (ServerSocket socket = new ServerSocket(0)) {
                labPort = socket.getLocalPort();
            }
            Properties properties = new Properties();
            Path originalProperties = serverRoot.resolve("server.properties");
            if (Files.isRegularFile(originalProperties, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(originalProperties)) {
                try (InputStream input = Files.newInputStream(originalProperties)) { properties.load(input); }
            }
            properties.setProperty("server-ip", "127.0.0.1");
            properties.setProperty("server-port", Integer.toString(labPort));
            properties.setProperty("query.port", Integer.toString(labPort));
            properties.setProperty("enable-query", "false");
            properties.setProperty("enable-rcon", "false");
            properties.setProperty("online-mode", "false");
            properties.setProperty("level-name", "shadow-world");
            properties.setProperty("max-players", "1");
            properties.setProperty("view-distance", "2");
            properties.setProperty("simulation-distance", "2");
            try (OutputStream target = Files.newOutputStream(workspace.resolve("server.properties"))) {
                properties.store(target, "Dash Shadow Boot Lab");
            }
            Files.writeString(workspace.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);

            Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
            ProcessBuilder builder = new ProcessBuilder(java.toString(), "-Xms256M", "-Xmx768M",
                    "-Ddash.shadowLab=true", "-jar", "server.jar", "--nogui");
            builder.directory(workspace.toFile());
            builder.redirectErrorStream(true);
            process = builder.start();
            Process running = process;
            AtomicBoolean ready = new AtomicBoolean(false);
            Thread reader = Thread.startVirtualThread(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        synchronized (output) {
                            if (output.length() < 64_000) output.append(line).append('\n');
                        }
                        String lower = line.toLowerCase(Locale.ROOT);
                        if ((lower.contains("done (") && lower.contains("for help"))
                                || lower.contains("for help, type")) ready.set(true);
                    }
                } catch (IOException ignored) {
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (process.isAlive() && !ready.get() && System.nanoTime() < deadline) Thread.sleep(200L);
            if (ready.get()) {
                try (BufferedWriter stdin = process.outputWriter(StandardCharsets.UTF_8)) {
                    stdin.write("stop\n");
                    stdin.flush();
                }
                if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroyForcibly();
                status = "passed";
                summary = "Isolated server reached ready state on loopback and shut down cleanly.";
            } else {
                if (process.isAlive()) process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                summary = "Isolated server did not reach ready state within 90 seconds.";
            }
            reader.join(2_000L);
        } catch (Exception ex) {
            summary = "Shadow startup failed safely: " + clean(ex.getMessage(), 500, "unknown error");
            if (process != null && process.isAlive()) process.destroyForcibly();
        } finally {
            finishShadowLab(id, status, summary, output.toString());
            deleteTree(workspace);
        }
    }

    private Optional<Path> discoverServerLauncher() {
        List<Path> jars = new ArrayList<>();
        try (Stream<Path> stream = Files.list(serverRoot)) {
            stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .forEach(jars::add);
        } catch (IOException ignored) {
        }
        jars.sort(Comparator.comparingInt(this::launcherScore).reversed());
        return jars.stream().filter(path -> launcherScore(path) > 0).findFirst();
    }

    private int launcherScore(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.contains("dash") || name.contains("installer")) return -10;
        if (name.equals("server.jar")) return 100;
        if (name.contains("paper") || name.contains("purpur")) return 90;
        if (name.contains("fabric-server") || name.contains("fabric-loader")) return 80;
        if (name.contains("neoforge") || name.contains("forge")) return 75;
        if (name.contains("minecraft_server") || name.contains("minecraft-server")) return 70;
        return 0;
    }

    private void copyShadowInputs(Path workspace) throws IOException {
        long[] budget = {1536L * 1024L * 1024L};
        int[] files = {0};
        for (String folder : List.of("plugins", "mods", "config", "libraries", ".fabric")) {
            Path source = serverRoot.resolve(folder).normalize();
            if (!source.startsWith(serverRoot) || !Files.isDirectory(source) || Files.isSymbolicLink(source)) continue;
            copyTreeBounded(source, workspace.resolve(folder), budget, files);
        }
        for (String file : List.of("bukkit.yml", "spigot.yml", "paper.yml", "paper-global.yml")) {
            Path source = serverRoot.resolve(file);
            if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(source)
                    && Files.size(source) <= MAX_STATE_FILE_BYTES) {
                Files.copy(source, workspace.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void copyTreeBounded(Path source, Path target, long[] budget, int[] files) throws IOException {
        try (Stream<Path> stream = Files.walk(source, 8)) {
            for (Path path : stream.sorted().toList()) {
                if (Files.isSymbolicLink(path)) continue;
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target.normalize())) throw new IOException("Shadow copy escaped workspace.");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    long size = Files.size(path);
                    if (++files[0] > 50_000 || size > budget[0]) continue;
                    budget[0] -= size;
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void finishShadowLab(String id, String status, String summary, String log) {
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE intel_shadow SET status=?,summary=?,log=?,finished_at=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setString(2, clean(summary, 1000, ""));
            ps.setString(3, clean(log, 64_000, ""));
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, id);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    // Root Cause Explorer

    public RootCauseReport rootCauseReport() {
        List<String> evidence = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        String severity = "info";
        String summary = "No strong failure signature was detected in the current log window.";
        List<String> lines = recentLogLines(2_000);
        Map<String, String> signatures = new LinkedHashMap<>();
        signatures.put("outofmemoryerror", "The JVM exhausted available memory.");
        signatures.put("watchdog", "The server watchdog detected a stalled tick.");
        signatures.put("noclassdeffounderror", "A plugin or mod dependency is missing or incompatible.");
        signatures.put("mixin apply failed", "A mod mixin could not be applied to this game version.");
        signatures.put("failed to load plugin", "A plugin failed during loader initialization.");
        signatures.put("address already in use", "The configured network port is already occupied.");
        signatures.put("no space left on device", "The server storage volume is full.");
        signatures.put("corrupt", "A file or world resource appears corrupted.");
        signatures.put("exception", "An unhandled exception appears in the recent server log.");
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            String lower = line.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> signature : signatures.entrySet()) {
                if (lower.contains(signature.getKey())) {
                    if (evidence.size() < 12) evidence.add(clean(line, 500, ""));
                    if ("info".equals(severity)) {
                        severity = signature.getKey().equals("exception") ? "warning" : "critical";
                        summary = signature.getValue();
                    }
                }
            }
        }
        List<Path> changed = recentlyChangedStateFiles(TimeUnit.HOURS.toMillis(24), 12);
        for (Path path : changed) evidence.add("Changed recently: " + relative(path));
        if (summary.contains("memory")) recommendations.add("Increase the memory ceiling only after checking plugin resource attribution.");
        if (summary.contains("dependency") || summary.contains("mixin")) recommendations.add("Review the Dependency & Impact Map and Supply Chain Center before restart.");
        if (summary.contains("port")) recommendations.add("Choose a free server port or stop the conflicting process.");
        if (summary.contains("storage")) recommendations.add("Open Storage Intelligence and apply the retention preview.");
        if (changed.stream().anyMatch(path -> path.getFileName().toString().endsWith(".jar"))) {
            recommendations.add("Run Shadow Boot Lab or Guided Safe Mode for the recently changed artifact.");
        }
        if (recommendations.isEmpty()) recommendations.add("Capture a State Time Machine snapshot before further diagnosis.");
        return new RootCauseReport(severity, summary, List.copyOf(evidence.stream().limit(20).toList()),
                List.copyOf(recommendations));
    }

    // State Time Machine

    public synchronized String captureState(String label, String actor) {
        try {
            StateSnapshot snapshot = captureStateInternal(clean(label, 100, "Manual snapshot"), actor);
            return "State snapshot captured: " + snapshot.id().substring(0, 8) + ".";
        } catch (Exception ex) {
            return "State snapshot failed safely: " + clean(ex.getMessage(), 500, "unknown error");
        }
    }

    private StateSnapshot captureStateInternal(String label, String actor) throws Exception {
        String id = UUID.randomUUID().toString();
        Path archive = snapshotsDir.resolve(id + ".zip").normalize();
        if (!archive.startsWith(snapshotsDir)) throw new IOException("Unsafe snapshot path.");
        Map<String, FileFingerprint> files = trackedStateFiles();
        Map<String, FileFingerprint> archived = new LinkedHashMap<>();
        String manifest;
        long written = 0L;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW))) {
            for (Map.Entry<String, FileFingerprint> item : files.entrySet()) {
                Path source = serverRoot.resolve(item.getKey()).normalize();
                if (!source.startsWith(serverRoot) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(source)) continue;
                long size = Files.size(source);
                if (size > stateFileLimit(source) || written > MAX_STATE_ARCHIVE_BYTES - size) continue;
                zip.putNextEntry(new ZipEntry("files/" + item.getKey()));
                try (InputStream input = Files.newInputStream(source)) { input.transferTo(zip); }
                zip.closeEntry();
                written += size;
                archived.put(item.getKey(), item.getValue());
            }
            manifest = encodeManifest(archived);
            zip.putNextEntry(new ZipEntry("manifest.txt"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (Exception ex) {
            Files.deleteIfExists(archive);
            throw ex;
        }
        long now = System.currentTimeMillis();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO intel_snapshots(id,label,archive_path,manifest,actor,created_at) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, label);
            ps.setString(3, archive.getFileName().toString());
            ps.setString(4, manifest);
            ps.setString(5, clean(actor, 64, "web"));
            ps.setLong(6, now);
            ps.executeUpdate();
        }
        return new StateSnapshot(id, label, archive.getFileName().toString(), archived.size(), written,
                clean(actor, 64, "web"), now);
    }

    public List<StateSnapshot> stateSnapshots(int limit) {
        List<StateSnapshot> rows = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "SELECT id,label,archive_path,manifest,actor,created_at FROM intel_snapshots ORDER BY created_at DESC LIMIT ?")) {
            ps.setInt(1, bounded(limit, 1, 100));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, FileFingerprint> manifest = decodeManifest(rs.getString(4));
                    long bytes = manifest.values().stream().mapToLong(FileFingerprint::size).sum();
                    rows.add(new StateSnapshot(rs.getString(1), rs.getString(2), rs.getString(3), manifest.size(),
                            bytes, rs.getString(5), rs.getLong(6)));
                }
            }
        } catch (SQLException ignored) {
        }
        return List.copyOf(rows);
    }

    public StateDiff diffState(String id) {
        Optional<SnapshotRow> row = snapshotRow(id);
        if (row.isEmpty()) return new StateDiff(false, 0, 0, 0, List.of("Snapshot not found."));
        Map<String, FileFingerprint> before = decodeManifest(row.get().manifest());
        Map<String, FileFingerprint> current = trackedStateFiles();
        List<String> details = new ArrayList<>();
        int changed = 0;
        int added = 0;
        int missing = 0;
        for (Map.Entry<String, FileFingerprint> item : current.entrySet()) {
            FileFingerprint old = before.get(item.getKey());
            if (old == null) { added++; details.add("Added: " + item.getKey()); }
            else if (!old.sha256().equals(item.getValue().sha256())) { changed++; details.add("Changed: " + item.getKey()); }
        }
        for (String path : before.keySet()) if (!current.containsKey(path)) { missing++; details.add("Missing: " + path); }
        return new StateDiff(true, changed, added, missing, List.copyOf(details.stream().limit(100).toList()));
    }

    public synchronized String restoreState(String id, String actor) {
        Optional<SnapshotRow> selected = snapshotRow(id);
        if (selected.isEmpty()) return "State snapshot not found.";
        Path archive = snapshotsDir.resolve(selected.get().archive()).normalize();
        if (!archive.startsWith(snapshotsDir) || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(archive)) return "State snapshot archive is unavailable.";
        Path restoreRoot = dataDir.resolve("restore-staging").resolve(UUID.randomUUID().toString()).normalize();
        Path incoming = restoreRoot.resolve("incoming").normalize();
        Path rollback = restoreRoot.resolve("rollback").normalize();
        List<String> movedOriginals = new ArrayList<>();
        List<String> createdTargets = new ArrayList<>();
        try {
            Path allowedRestoreRoot = dataDir.resolve("restore-staging").normalize();
            if (!restoreRoot.startsWith(allowedRestoreRoot) || !incoming.startsWith(restoreRoot)
                    || !rollback.startsWith(restoreRoot)) throw new IOException("Unsafe restore staging path.");
            Files.createDirectories(incoming);
            Files.createDirectories(rollback);
            Map<String, FileFingerprint> expected = decodeManifest(selected.get().manifest());
            Set<String> extracted = new LinkedHashSet<>();
            long bytes = 0L;
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith("files/")) continue;
                    String relative = entry.getName().substring("files/".length());
                    validateArchiveEntryName(relative);
                    FileFingerprint fingerprint = expected.get(relative);
                    if (fingerprint == null || !extracted.add(relative)) {
                        throw new IOException("Snapshot contains an unexpected or duplicate state entry.");
                    }
                    Path staged = incoming.resolve(relative).normalize();
                    if (!staged.startsWith(incoming)) throw new IOException("Snapshot entry escaped staging.");
                    long size = entry.getSize();
                    long entryLimit = relative.toLowerCase(Locale.ROOT).endsWith(".jar")
                            ? 64L * 1024L * 1024L : MAX_STATE_FILE_BYTES;
                    if (size < 0 || size > entryLimit || bytes > MAX_STATE_ARCHIVE_BYTES - size) {
                        throw new IOException("Snapshot entry exceeds restore limits.");
                    }
                    Files.createDirectories(staged.getParent());
                    CRC32 crc = new CRC32();
                    long copied;
                    try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(
                            staged, StandardOpenOption.CREATE_NEW)) {
                        copied = copyBounded(input, output, entryLimit, crc);
                    }
                    if (copied != size || (entry.getCrc() >= 0L && entry.getCrc() != crc.getValue())
                            || !hashFile(staged).equals(fingerprint.sha256())) {
                        throw new IOException("Snapshot entry failed integrity verification.");
                    }
                    bytes += copied;
                }
            }
            if (!extracted.equals(expected.keySet())) {
                throw new IOException("Snapshot archive is incomplete.");
            }

            for (String relative : expected.keySet()) {
                Path target = safeServerPath(relative);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        && (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target))) {
                    throw new IOException("A restore target is not a regular managed file.");
                }
            }

            StateSnapshot safety = captureStateInternal("Automatic safety snapshot before restore", actor);
            Map<String, FileFingerprint> current = trackedStateFiles();
            List<String> pathsToRemove = current.keySet().stream()
                    .filter(path -> !expected.containsKey(path)).sorted().toList();
            for (String relative : pathsToRemove) {
                moveLiveFileToRollback(relative, rollback, movedOriginals);
            }
            for (String relative : expected.keySet()) {
                Path target = safeServerPath(relative);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    moveLiveFileToRollback(relative, rollback, movedOriginals);
                }
                Path staged = incoming.resolve(relative).normalize();
                Files.createDirectories(target.getParent());
                moveAtomic(staged, target);
                createdTargets.add(relative);
            }
            return "State Time Machine restored " + expected.size() + " verified files and removed "
                    + pathsToRemove.size() + " later additions. Safety snapshot "
                    + safety.id().substring(0, 8) + " remains available.";
        } catch (Exception ex) {
            rollbackStateRestore(createdTargets, movedOriginals, rollback);
            return "State restore failed safely; the live state was rolled back where changes had begun.";
        } finally {
            deleteTreeInside(restoreRoot, dataDir.resolve("restore-staging"));
        }
    }

    private void moveLiveFileToRollback(String relative, Path rollback, List<String> movedOriginals)
            throws IOException {
        Path source = safeServerPath(relative);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw new IOException("Managed restore source is unavailable.");
        }
        Path target = rollback.resolve(relative).normalize();
        if (!target.startsWith(rollback)) throw new IOException("Rollback path escaped staging.");
        Files.createDirectories(target.getParent());
        moveAtomic(source, target);
        movedOriginals.add(relative);
    }

    private void rollbackStateRestore(List<String> createdTargets, List<String> movedOriginals, Path rollback) {
        for (String relative : createdTargets.reversed()) {
            try { Files.deleteIfExists(safeServerPath(relative)); } catch (IOException ignored) { }
        }
        for (String relative : movedOriginals.reversed()) {
            try {
                Path source = rollback.resolve(relative).normalize();
                Path target = safeServerPath(relative);
                if (!source.startsWith(rollback) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(source)) continue;
                Files.createDirectories(target.getParent());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.delete(target);
                moveAtomic(source, target);
            } catch (IOException ignored) { }
        }
    }

    private Optional<SnapshotRow> snapshotRow(String id) {
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "SELECT id,archive_path,manifest FROM intel_snapshots WHERE id=?")) {
            ps.setString(1, clean(id, 80, ""));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(new SnapshotRow(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException ignored) {
        }
        return Optional.empty();
    }

    // Performance Regression Radar

    public synchronized String recordPerformance(double tps, double mspt, long memoryMb, int players, String label) {
        double safeTps = Math.max(0.0d, Math.min(20.0d, tps));
        double safeMspt = Math.max(0.0d, Math.min(10_000.0d, mspt));
        String safeLabel = normalizeChoice(label, Set.of("baseline", "sample"), "");
        if (safeLabel.isBlank()) return "Unsupported performance sample type.";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO intel_performance(sampled_at,tps,mspt,memory_mb,players,label) VALUES(?,?,?,?,?,?)")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setDouble(2, safeTps);
            ps.setDouble(3, safeMspt);
            ps.setLong(4, Math.max(0L, memoryMb));
            ps.setInt(5, Math.max(0, players));
            ps.setString(6, safeLabel);
            ps.executeUpdate();
            trimTable("intel_performance", "id", 720);
            return "Performance " + safeLabel + " recorded.";
        } catch (SQLException ex) {
            return "Performance sample could not be stored.";
        }
    }

    public PerformanceRegression performanceRegression() {
        List<PerformanceSample> baseline = performanceSamples("baseline", 24);
        List<PerformanceSample> current = performanceSamples("sample", 24);
        if (baseline.isEmpty() || current.isEmpty()) {
            return new PerformanceRegression("learning", 0.0d, 0.0d, 0L, 0,
                    "Capture a baseline and at least one current sample.");
        }
        double baselineTps = averageTps(baseline);
        double currentTps = averageTps(current);
        double baselineMspt = averageMspt(baseline);
        double currentMspt = averageMspt(current);
        long baselineMemory = averageMemory(baseline);
        long currentMemory = averageMemory(current);
        double tpsDelta = currentTps - baselineTps;
        double msptDelta = currentMspt - baselineMspt;
        long memoryDelta = currentMemory - baselineMemory;
        String status = (tpsDelta < -1.0d || msptDelta > 8.0d || memoryDelta > 512L) ? "regression" : "stable";
        String message = "stable".equals(status)
                ? "Current performance remains within the recorded baseline envelope."
                : "A measurable regression was detected; review recent state changes before rollout.";
        return new PerformanceRegression(status, tpsDelta, msptDelta, memoryDelta, current.size(), message);
    }

    private List<PerformanceSample> performanceSamples(String label, int limit) {
        List<PerformanceSample> rows = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "SELECT sampled_at,tps,mspt,memory_mb,players,label FROM intel_performance WHERE label=? ORDER BY sampled_at DESC LIMIT ?")) {
            ps.setString(1, label);
            ps.setInt(2, bounded(limit, 1, 100));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new PerformanceSample(rs.getLong(1), rs.getDouble(2), rs.getDouble(3),
                        rs.getLong(4), rs.getInt(5), rs.getString(6)));
            }
        } catch (SQLException ignored) {
        }
        return rows;
    }

    // Plugin Resource Attribution and Dependency & Impact Map

    public List<String> artifactNames() {
        return artifactPaths().stream()
                .map(path -> path.getFileName().toString())
                .toList();
    }

    public List<ResourceAttribution> resourceAttribution() {
        List<String> log = recentLogLines(3_000);
        List<ResourceAttribution> rows = new ArrayList<>();
        for (Path artifact : artifactPaths()) {
            String fileName = artifact.getFileName().toString();
            String stem = fileName.replaceFirst("(?i)\\.jar$", "")
                    .replaceFirst("[-_](?:v)?\\d.*$", "").toLowerCase(Locale.ROOT);
            int logSignals = 0;
            for (String line : log) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains(stem) && (lower.contains("warn") || lower.contains("error")
                        || lower.contains("exception") || lower.contains("took "))) logSignals++;
            }
            long bytes = 0L;
            int classes = 0;
            int nativeEntries = 0;
            try (JarFile jar = new JarFile(artifact.toFile(), false)) {
                bytes = Files.size(artifact);
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".class")) classes++;
                    if (name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib")) nativeEntries++;
                }
            } catch (Exception ignored) {
            }
            int score = Math.min(100, logSignals * 12 + Math.min(30, classes / 500) + nativeEntries * 8);
            String confidence = logSignals > 4 ? "high" : logSignals > 0 ? "medium" : "inventory";
            List<String> evidence = new ArrayList<>();
            evidence.add(classes + " loaded class files in artifact");
            evidence.add(formatBytes(bytes) + " artifact size");
            if (logSignals > 0) evidence.add(logSignals + " recent warning/error references");
            if (nativeEntries > 0) evidence.add(nativeEntries + " bundled native libraries");
            rows.add(new ResourceAttribution(fileName, artifact.getParent().getFileName().toString(), score,
                    confidence, bytes, classes, logSignals, List.copyOf(evidence)));
        }
        rows.sort(Comparator.comparingInt(ResourceAttribution::score).reversed()
                .thenComparing(ResourceAttribution::artifact));
        return List.copyOf(rows);
    }

    public DependencyGraph dependencyGraph() {
        List<DependencyNode> nodes = new ArrayList<>();
        List<DependencyEdge> edges = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Path artifact : artifactPaths()) {
            ArtifactDescriptor descriptor = descriptor(artifact);
            String id = descriptor.id().isBlank()
                    ? artifact.getFileName().toString().replaceFirst("(?i)\\.jar$", "")
                    : descriptor.id();
            ids.add(id.toLowerCase(Locale.ROOT));
            nodes.add(new DependencyNode(id, artifact.getFileName().toString(), descriptor.loader(),
                    descriptor.dependencies().size()));
            for (String dependency : descriptor.dependencies()) {
                edges.add(new DependencyEdge(id, dependency, "requires"));
            }
            for (String dependency : descriptor.optionalDependencies()) {
                edges.add(new DependencyEdge(id, dependency, "optional"));
            }
        }
        List<String> missing = edges.stream()
                .filter(edge -> "requires".equals(edge.kind()))
                .map(DependencyEdge::target)
                .filter(target -> !ids.contains(target.toLowerCase(Locale.ROOT)))
                .distinct().sorted().toList();
        return new DependencyGraph(List.copyOf(nodes), List.copyOf(edges), missing);
    }

    private ArtifactDescriptor descriptor(Path artifact) {
        try (JarFile jar = new JarFile(artifact.toFile(), false)) {
            JarEntry plugin = jar.getJarEntry("plugin.yml");
            if (plugin != null) {
                String text = readTextBounded(jar.getInputStream(plugin), 512_000L);
                Matcher name = PLUGIN_NAME.matcher(text);
                String id = name.find() ? clean(name.group(1), 100, "") : "";
                Set<String> required = new LinkedHashSet<>();
                Set<String> optional = new LinkedHashSet<>();
                Matcher deps = PLUGIN_DEPENDS.matcher(text);
                while (deps.find()) {
                    Set<String> target = "depend".equalsIgnoreCase(deps.group(1)) ? required : optional;
                    for (String value : deps.group(2).split(",")) {
                        String dep = value.replace("'", "").replace("\"", "").trim();
                        if (!dep.isBlank()) target.add(dep);
                    }
                }
                return new ArtifactDescriptor(id, "bukkit", List.copyOf(required), List.copyOf(optional));
            }
            JarEntry fabric = jar.getJarEntry("fabric.mod.json");
            if (fabric != null) {
                String text = readTextBounded(jar.getInputStream(fabric), 512_000L);
                Matcher idMatcher = JSON_ID.matcher(text);
                String id = idMatcher.find() ? idMatcher.group(1) : "";
                Set<String> required = jsonObjectKeys(text, "depends");
                Set<String> optional = jsonObjectKeys(text, "recommends");
                required.removeAll(Set.of("minecraft", "java", "fabricloader", "fabric-api"));
                return new ArtifactDescriptor(id, "fabric", List.copyOf(required), List.copyOf(optional));
            }
            JarEntry neo = jar.getJarEntry("META-INF/neoforge.mods.toml");
            if (neo == null) neo = jar.getJarEntry("META-INF/mods.toml");
            if (neo != null) {
                String text = readTextBounded(jar.getInputStream(neo), 512_000L);
                Matcher matcher = TOML_MOD_ID.matcher(text);
                List<String> ids = new ArrayList<>();
                while (matcher.find()) ids.add(matcher.group(1));
                String id = ids.isEmpty() ? "" : ids.get(0);
                Set<String> dependencies = new LinkedHashSet<>(ids.stream().skip(1).toList());
                dependencies.removeAll(Set.of("minecraft", "neoforge", "forge", "java"));
                return new ArtifactDescriptor(id, "neoforge", List.copyOf(dependencies), List.of());
            }
        } catch (Exception ignored) {
        }
        return new ArtifactDescriptor("", "unknown", List.of(), List.of());
    }

    private Set<String> jsonObjectKeys(String json, String property) {
        Set<String> result = new LinkedHashSet<>();
        Matcher start = Pattern.compile("\\\"" + Pattern.quote(property) + "\\\"\\s*:\\s*\\{").matcher(json);
        if (!start.find()) return result;
        int depth = 1;
        int end = start.end();
        boolean quoted = false;
        boolean escaped = false;
        for (; end < json.length() && depth > 0; end++) {
            char c = json.charAt(end);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') quoted = !quoted;
            if (!quoted && c == '{') depth++;
            if (!quoted && c == '}') depth--;
        }
        if (depth != 0) return result;
        String body = json.substring(start.end(), end - 1);
        Matcher key = Pattern.compile("\"([^\"]+)\"\\s*:").matcher(body);
        while (key.find()) result.add(key.group(1));
        return result;
    }

    // Guided Safe Mode

    public synchronized String quarantineArtifact(String artifactName, String actor) {
        String safeName = safeArtifactName(artifactName);
        if (safeName.isBlank()) return "Choose a valid plugin or mod JAR.";
        String lower = safeName.toLowerCase(Locale.ROOT);
        if (lower.contains("dash") || lower.contains("fabric-loader") || lower.contains("neoforge")) {
            return "The dashboard and loader artifacts cannot be quarantined from their own interface.";
        }
        Path source = artifactPaths().stream()
                .filter(path -> path.getFileName().toString().equalsIgnoreCase(safeName))
                .findFirst().orElse(null);
        if (source == null) return "Artifact was not found in plugins or mods.";
        String id = UUID.randomUUID().toString();
        Path target = quarantineDir.resolve(id).resolve(source.getParent().getFileName()).resolve(safeName).normalize();
        if (!target.startsWith(quarantineDir)) return "Safe Mode path was rejected.";
        try {
            Files.createDirectories(target.getParent());
            moveAtomic(source, target);
            try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO intel_safe_mode(id,artifact,original_path,quarantine_path,actor,created_at,restored_at) VALUES(?,?,?,?,?,?,0)")) {
                ps.setString(1, id);
                ps.setString(2, safeName);
                ps.setString(3, relative(source));
                ps.setString(4, dataDir.relativize(target).toString().replace('\\', '/'));
                ps.setString(5, clean(actor, 64, "web"));
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException ex) {
                moveAtomic(target, source);
                throw ex;
            }
            return "Guided Safe Mode quarantined " + safeName + "; restart the server to test without it.";
        } catch (Exception ex) {
            return "Safe Mode failed safely: " + clean(ex.getMessage(), 400, "unknown error");
        }
    }

    public synchronized String restoreQuarantinedArtifact(String id) {
        SafeModeItem item = safeModeItems(100).stream().filter(row -> row.id().equals(clean(id, 80, ""))).findFirst().orElse(null);
        if (item == null || item.restoredAt() > 0) return "Active quarantine entry not found.";
        try {
            Path source = dataDir.resolve(item.quarantinePath()).normalize();
            Path target = safeServerPath(item.originalPath());
            if (!source.startsWith(quarantineDir) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(source)) return "Quarantined artifact is unavailable.";
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return "Restore target already exists; resolve it manually first.";
            Files.createDirectories(target.getParent());
            moveAtomic(source, target);
            try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                    "UPDATE intel_safe_mode SET restored_at=? WHERE id=? AND restored_at=0")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, item.id());
                ps.executeUpdate();
            }
            return "Quarantined artifact restored. Restart the server to load it.";
        } catch (Exception ex) {
            return "Artifact restore failed safely: " + clean(ex.getMessage(), 400, "unknown error");
        }
    }

    public List<SafeModeItem> safeModeItems(int limit) {
        List<SafeModeItem> rows = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "SELECT id,artifact,original_path,quarantine_path,actor,created_at,restored_at FROM intel_safe_mode ORDER BY created_at DESC LIMIT ?")) {
            ps.setInt(1, bounded(limit, 1, 200));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new SafeModeItem(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7)));
            }
        } catch (SQLException ignored) {
        }
        return List.copyOf(rows);
    }

    // Backup Content Explorer

    public List<String> backupCandidates() {
        Set<String> names = new LinkedHashSet<>();
        for (Path directory : backupDirectories()) {
            if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) continue;
            try (Stream<Path> stream = Files.list(directory)) {
                stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !Files.isSymbolicLink(path))
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".zip"))
                        .sorted().forEach(names::add);
            } catch (IOException ignored) {
            }
        }
        return List.copyOf(names);
    }

    public BackupView browseBackup(String backupName, String search, int limit) {
        Path backup = locateBackup(backupName).orElse(null);
        if (backup == null) return new BackupView("", 0L, 0, List.of(), "Backup not found.");
        String query = clean(search, 160, "").toLowerCase(Locale.ROOT);
        List<BackupEntry> rows = new ArrayList<>();
        int total = 0;
        long totalBytes = 0L;
        try (ZipFile zip = new ZipFile(backup.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements() && total < MAX_SCAN_FILES) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                validateArchiveEntryName(entry.getName());
                total++;
                totalBytes += Math.max(0L, entry.getSize());
                if ((query.isBlank() || entry.getName().toLowerCase(Locale.ROOT).contains(query))
                        && rows.size() < bounded(limit, 1, 500)) {
                    Path live = safeServerPath(entry.getName());
                    String state = !Files.exists(live, LinkOption.NOFOLLOW_LINKS) ? "missing-live"
                            : Files.isRegularFile(live, LinkOption.NOFOLLOW_LINKS) && entry.getSize() == Files.size(live)
                            ? "same-size" : "changed";
                    rows.add(new BackupEntry(entry.getName(), Math.max(0L, entry.getSize()), entry.getTime(), state));
                }
            }
            String message = entries.hasMoreElements()
                    ? "Preview limited to " + MAX_SCAN_FILES + " files for dashboard responsiveness."
                    : "Ready";
            return new BackupView(backup.getFileName().toString(), totalBytes, total, List.copyOf(rows), message);
        } catch (Exception ex) {
            return new BackupView(backup.getFileName().toString(), totalBytes, total, List.copyOf(rows),
                    "Backup inspection failed safely: " + clean(ex.getMessage(), 300, "invalid archive"));
        }
    }

    public synchronized String restoreBackupEntry(String backupName, String entryName, String actor) {
        Path backup = locateBackup(backupName).orElse(null);
        if (backup == null) return "Backup not found.";
        try (ZipFile zip = new ZipFile(backup.toFile())) {
            validateArchiveEntryName(entryName);
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.isDirectory()) return "Backup entry not found.";
            if (entry.getSize() < 0 || entry.getSize() > MAX_BACKUP_ENTRY_BYTES) return "Backup entry exceeds restore limits.";
            Path target = safeServerPath(entry.getName());
            Path safety = dataDir.resolve("restore-safety").resolve(UUID.randomUUID().toString())
                    .resolve(entry.getName()).normalize();
            if (!safety.startsWith(dataDir.resolve("restore-safety").normalize())) throw new IOException("Unsafe safety path.");
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
                Files.createDirectories(safety.getParent());
                Files.copy(target, safety, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".dash-restore-", ".tmp");
            CRC32 crc = new CRC32();
            long copied;
            try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(temporary)) {
                copied = copyBounded(input, output, MAX_BACKUP_ENTRY_BYTES, crc);
            }
            if (entry.getSize() != copied || (entry.getCrc() >= 0 && entry.getCrc() != crc.getValue())) {
                Files.deleteIfExists(temporary);
                return "Backup entry failed size or checksum verification.";
            }
            atomicReplace(temporary, target);
            return "Restored " + entry.getName() + " after preserving the live file in restore-safety.";
        } catch (Exception ex) {
            return "Selective restore failed safely: " + clean(ex.getMessage(), 400, "invalid archive");
        }
    }

    // Adaptive Maintenance Window

    public MaintenanceWindow maintenanceWindow() {
        Path database = locatePlayerDatabase().orElse(null);
        if (database == null) return new MaintenanceWindow("Unknown", 3, 0, 0.0d,
                "Collect player sessions before calculating a maintenance window.");
        int[][] joins = new int[7][24];
        int samples = 0;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT join_time FROM sessions WHERE join_time > "
                        + (System.currentTimeMillis() - TimeUnit.DAYS.toMillis(60)))) {
            while (rs.next()) {
                LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(rs.getLong(1)), ZoneId.systemDefault());
                joins[time.getDayOfWeek().getValue() - 1][time.getHour()]++;
                samples++;
            }
        } catch (SQLException ex) {
            return new MaintenanceWindow("Unknown", 3, 0, 0.0d, "Session history could not be read safely.");
        }
        int bestDay = 0;
        int bestHour = 3;
        int best = Integer.MAX_VALUE;
        for (int day = 0; day < 7; day++) {
            for (int hour = 0; hour < 24; hour++) {
                if (joins[day][hour] < best) { best = joins[day][hour]; bestDay = day; bestHour = hour; }
            }
        }
        double confidence = Math.min(1.0d, samples / 200.0d);
        String message = samples < 20 ? "Low confidence: more session history is needed."
                : "This hour has the lowest observed join activity in the last 60 days.";
        return new MaintenanceWindow(DayOfWeek.of(bestDay + 1).toString(), bestHour, Math.max(0, best), confidence, message);
    }

    // Action Guardrails and Dual-Control Actions

    public synchronized String saveGuardrail(String actionPattern, int maxPlayers, boolean requireBackup,
            int quietStart, int quietEnd, boolean requireReason, boolean dualControl) {
        String pattern = clean(actionPattern, 120, "");
        if (pattern.isBlank() || !pattern.matches("[A-Za-z0-9_*.-]+")) return "Guardrail action pattern is invalid.";
        if (maxPlayers < -1 || maxPlayers > 100_000 || quietStart < -1 || quietStart > 23
                || quietEnd < -1 || quietEnd > 23) return "Guardrail thresholds are invalid.";
        if ((quietStart == -1) != (quietEnd == -1)) {
            return "Guardrail maintenance hours require both a start and end, or neither.";
        }
        String id = UUID.randomUUID().toString();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO intel_guardrails(id,action_pattern,max_players,require_backup,quiet_start,quiet_end,require_reason,dual_control,enabled,created_at) VALUES(?,?,?,?,?,?,?,?,1,?)")) {
            ps.setString(1, id);
            ps.setString(2, pattern.toLowerCase(Locale.ROOT));
            ps.setInt(3, maxPlayers);
            ps.setInt(4, requireBackup ? 1 : 0);
            ps.setInt(5, quietStart);
            ps.setInt(6, quietEnd);
            ps.setInt(7, requireReason ? 1 : 0);
            ps.setInt(8, dualControl ? 1 : 0);
            ps.setLong(9, System.currentTimeMillis());
            ps.executeUpdate();
            return "Action guardrail saved.";
        } catch (SQLException ex) {
            return "Action guardrail could not be saved.";
        }
    }

    public synchronized String deleteGuardrail(String id) {
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM intel_guardrails WHERE id=?")) {
            ps.setString(1, clean(id, 80, ""));
            return ps.executeUpdate() == 1 ? "Action guardrail deleted." : "Action guardrail not found.";
        } catch (SQLException ex) {
            return "Action guardrail could not be deleted.";
        }
    }

    public List<Guardrail> guardrails() {
        List<Guardrail> rows = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT id,action_pattern,max_players,require_backup,quiet_start,quiet_end,require_reason,dual_control,enabled,created_at FROM intel_guardrails ORDER BY created_at DESC")) {
            while (rs.next()) rows.add(new Guardrail(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4) == 1,
                    rs.getInt(5), rs.getInt(6), rs.getInt(7) == 1, rs.getInt(8) == 1, rs.getInt(9) == 1, rs.getLong(10)));
        } catch (SQLException ignored) {
        }
        return List.copyOf(rows);
    }

    public boolean hasMatchingGuardrail(String action) {
        String safeAction = clean(action, 120, "").toLowerCase(Locale.ROOT);
        return !safeAction.isBlank() && guardrails().stream().filter(Guardrail::enabled)
                .anyMatch(rule -> wildcardMatches(rule.actionPattern(), safeAction));
    }

    public GuardDecision authorizeAction(String action, String actor, String reason, int onlinePlayers,
            Map<String, String> payload) {
        String safeAction = clean(action, 120, "").toLowerCase(Locale.ROOT);
        if (safeAction.isBlank() || safeAction.startsWith("intel_approval_")) return GuardDecision.allow();
        List<Guardrail> matches = guardrails().stream().filter(Guardrail::enabled)
                .filter(rule -> wildcardMatches(rule.actionPattern(), safeAction)).toList();
        if (matches.isEmpty()) return GuardDecision.allow();
        for (Guardrail rule : matches) {
            if (rule.maxPlayers() >= 0 && onlinePlayers > rule.maxPlayers()) {
                return new GuardDecision(false, "Blocked by guardrail: " + onlinePlayers
                        + " players are online; maximum is " + rule.maxPlayers() + ".", "");
            }
            if (rule.requireBackup() && latestBackupAgeHours() > 24.0d) {
                return new GuardDecision(false, "Blocked by guardrail: create a verified backup newer than 24 hours.", "");
            }
            if (rule.requireReason() && clean(reason, 500, "").isBlank()) {
                return new GuardDecision(false, "Blocked by guardrail: an operator reason is required.", "");
            }
            if (rule.quietStart() >= 0 && rule.quietEnd() >= 0
                    && !hourInsideWindow(LocalDateTime.now().getHour(), rule.quietStart(), rule.quietEnd())) {
                return new GuardDecision(false, "Blocked by guardrail: action is outside the configured maintenance hours.", "");
            }
            if (rule.dualControl()) {
                String payloadHash = payloadHash(payload);
                if (consumeApproval(safeAction, payloadHash, actor)) continue;
                String approvalId = ensureApprovalRequest(safeAction, payloadHash, actor);
                return new GuardDecision(false, "Dual control required. Approval request "
                        + approvalId.substring(0, 8) + " is awaiting another operator.", approvalId);
            }
        }
        return GuardDecision.allow();
    }

    // Player Journey Replay, Cross-Server Identity and Player Experience Score

    public PlayerIdentity playerIdentity(String nameOrUuid) {
        String target = clean(nameOrUuid, 64, "");
        if (target.isBlank()) return PlayerIdentity.empty("");
        Path database = locatePlayerDatabase().orElse(null);
        if (database == null) return PlayerIdentity.empty(target);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT p.uuid,p.name,p.first_join,p.last_join,p.total_playtime,(SELECT COUNT(*) FROM sessions s WHERE s.uuid=p.uuid) FROM players p WHERE lower(p.name)=lower(?) OR p.uuid=? LIMIT 1")) {
            ps.setString(1, target);
            ps.setString(2, target);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new PlayerIdentity(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getLong(4), rs.getLong(5), rs.getInt(6), platform);
            }
        } catch (SQLException ignored) {
        }
        return PlayerIdentity.empty(target);
    }

    public List<PlayerIdentity> searchPlayerIdentities(String query, int limit) {
        String target = clean(query, 64, "");
        Path database = locatePlayerDatabase().orElse(null);
        if (database == null) return List.of();
        List<PlayerIdentity> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT p.uuid,p.name,p.first_join,p.last_join,p.total_playtime,(SELECT COUNT(*) FROM sessions s WHERE s.uuid=p.uuid) FROM players p WHERE lower(p.name) LIKE lower(?) ESCAPE '\\' ORDER BY p.last_join DESC LIMIT ?")) {
            ps.setString(1, "%" + target.replace("%", "\\%").replace("_", "\\_") + "%");
            ps.setInt(2, bounded(limit, 1, 100));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new PlayerIdentity(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getLong(4), rs.getLong(5), rs.getInt(6), platform));
            }
        } catch (SQLException ignored) {
        }
        return List.copyOf(rows);
    }

    public PlayerJourney playerJourney(String playerName, int limit) {
        PlayerIdentity identity = playerIdentity(playerName);
        if (identity.uuid().isBlank()) return new PlayerJourney(identity, List.of(), "Player was not found in local history.");
        List<JourneyEvent> events = new ArrayList<>();
        Path database = locatePlayerDatabase().orElse(null);
        if (database != null) {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT join_time,leave_time,ip_address FROM sessions WHERE uuid=? ORDER BY join_time DESC LIMIT ?")) {
                    ps.setString(1, identity.uuid());
                    ps.setInt(2, bounded(limit, 1, 200));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long leave = rs.getLong(2);
                            String detail = leave > 0 ? "Session duration " + formatDuration(leave - rs.getLong(1))
                                    : "Session did not record a clean leave";
                            events.add(new JourneyEvent(rs.getLong(1), "session", "Joined server", detail));
                            if (leave > 0) events.add(new JourneyEvent(leave, "session", "Left server", "Session closed cleanly"));
                        }
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT created_at,admin_name,note FROM notes WHERE uuid=? ORDER BY created_at DESC LIMIT ?")) {
                    ps.setString(1, identity.uuid());
                    ps.setInt(2, bounded(limit, 1, 100));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) events.add(new JourneyEvent(rs.getLong(1), "staff", "Staff note by "
                                + rs.getString(2), clean(rs.getString(3), 500, "")));
                    }
                }
            } catch (SQLException ignored) {
            }
        }
        appendGuardianJourney(events, identity.name(), limit);
        for (SupportCase support : supportCases("", identity.name(), 100)) {
            events.add(new JourneyEvent(support.createdAt(), "support", support.type() + ": " + support.subject(), support.status()));
        }
        events.sort(Comparator.comparingLong(JourneyEvent::timestamp).reversed());
        return new PlayerJourney(identity, List.copyOf(events.stream().limit(bounded(limit, 1, 300)).toList()),
                "Combined sessions, staff notes, Guardian activity and support history.");
    }

    private void appendGuardianJourney(List<JourneyEvent> events, String player, int limit) {
        Path database = locateGuardianDatabase().orElse(null);
        if (database == null || player.isBlank()) return;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            for (String table : List.of("guardian_block_log", "guardian_container_log")) {
                String type = table.contains("block") ? "block" : "container";
                try (PreparedStatement ps = connection.prepareStatement("SELECT timestamp,action,world,x,y,z FROM "
                        + table + " WHERE lower(player_name)=lower(?) ORDER BY timestamp DESC LIMIT ?")) {
                    ps.setString(1, player);
                    ps.setInt(2, bounded(limit, 1, 200));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) events.add(new JourneyEvent(rs.getLong(1), "guardian",
                                type + " action " + rs.getInt(2), rs.getString(3) + " @ " + rs.getInt(4)
                                        + "," + rs.getInt(5) + "," + rs.getInt(6)));
                    }
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException ignored) {
        }
    }

    public ExperienceScore playerExperience(String playerName) {
        PlayerIdentity identity = playerIdentity(playerName);
        if (identity.uuid().isBlank()) return new ExperienceScore(0, "unknown", 0, 0, 0L, List.of("Player not found."));
        int sessions = 0;
        int abrupt = 0;
        int shortSessions = 0;
        long totalDuration = 0L;
        Path database = locatePlayerDatabase().orElse(null);
        if (database != null) {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                    PreparedStatement ps = connection.prepareStatement(
                            "SELECT join_time,leave_time FROM sessions WHERE uuid=? ORDER BY join_time DESC LIMIT 100")) {
                ps.setString(1, identity.uuid());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sessions++;
                        long join = rs.getLong(1);
                        long leave = rs.getLong(2);
                        if (leave <= 0L) abrupt++;
                        else {
                            long duration = Math.max(0L, leave - join);
                            totalDuration += duration;
                            if (duration < TimeUnit.MINUTES.toMillis(2)) shortSessions++;
                        }
                    }
                }
            } catch (SQLException ignored) {
            }
        }
        int logErrors = 0;
        for (String line : recentLogLines(2_000)) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains(identity.name().toLowerCase(Locale.ROOT))
                    && (lower.contains("timeout") || lower.contains("disconnect") || lower.contains("error"))) logErrors++;
        }
        int score = Math.max(0, 100 - abrupt * 8 - shortSessions * 4 - logErrors * 5);
        String status = score >= 85 ? "excellent" : score >= 65 ? "watch" : "degraded";
        List<String> signals = new ArrayList<>();
        signals.add(abrupt + " sessions without a clean leave record");
        signals.add(shortSessions + " sessions shorter than two minutes");
        signals.add(logErrors + " recent player-related error signals");
        long average = sessions - abrupt <= 0 ? 0L : totalDuration / Math.max(1, sessions - abrupt);
        return new ExperienceScore(score, status, sessions, abrupt, average, List.copyOf(signals));
    }

    // Support & Appeals Inbox

    public synchronized String createSupportCase(String type, String player, String subject, String message,
            String owner) {
        String safeSubject = clean(subject, 160, "");
        String safeMessage = clean(message, 5_000, "");
        if (safeSubject.isBlank() || safeMessage.isBlank()) return "Support subject and message are required.";
        String safeType = normalizeChoice(type, Set.of("support", "appeal", "bug", "billing", "other"), "");
        if (safeType.isBlank()) return "Unsupported support case type.";
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO intel_support(id,type,player,subject,message,status,owner,created_at,updated_at) VALUES(?,?,?,?,?,'open',?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, safeType);
            ps.setString(3, clean(player, 64, "Anonymous"));
            ps.setString(4, safeSubject);
            ps.setString(5, safeMessage);
            ps.setString(6, clean(owner, 64, "unassigned"));
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.executeUpdate();
            return "Support case created: " + id.substring(0, 8) + ".";
        } catch (SQLException ex) {
            return "Support case could not be saved.";
        }
    }

    public synchronized String updateSupportCase(String id, String status, String owner) {
        String safeStatus = normalizeChoice(status, Set.of("open", "investigating", "waiting", "resolved", "rejected"), "");
        if (safeStatus.isBlank()) return "Unsupported support status.";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE intel_support SET status=?,owner=?,updated_at=? WHERE id=?")) {
            ps.setString(1, safeStatus);
            ps.setString(2, clean(owner, 64, "unassigned"));
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, clean(id, 80, ""));
            return ps.executeUpdate() == 1 ? "Support case updated." : "Support case not found.";
        } catch (SQLException ex) {
            return "Support case could not be updated.";
        }
    }

    public synchronized String addSupportReply(String caseId, String author, String message, boolean publicReply) {
        String safe = clean(message, 5_000, "");
        if (safe.isBlank()) return "Reply message is required.";
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO intel_support_replies(id,case_id,author,message,public_reply,created_at) VALUES(?,?,?,?,?,?)");
                    PreparedStatement update = connection.prepareStatement(
                            "UPDATE intel_support SET updated_at=? WHERE id=?")) {
                long now = System.currentTimeMillis();
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, clean(caseId, 80, ""));
                insert.setString(3, clean(author, 64, "web"));
                insert.setString(4, safe);
                insert.setInt(5, publicReply ? 1 : 0);
                insert.setLong(6, now);
                insert.executeUpdate();
                update.setLong(1, now);
                update.setString(2, clean(caseId, 80, ""));
                if (update.executeUpdate() != 1) throw new SQLException("Case not found.");
                connection.commit();
                return "Support reply added.";
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            return "Support reply could not be saved.";
        }
    }

    public List<SupportCase> supportCases(String status, String player, int limit) {
        String safeStatus = clean(status, 32, "").toLowerCase(Locale.ROOT);
        String safePlayer = clean(player, 64, "").toLowerCase(Locale.ROOT);
        List<SupportCase> rows = new ArrayList<>();
        String sql = "SELECT id,type,player,subject,message,status,owner,created_at,updated_at FROM intel_support WHERE (?='' OR status=?) AND (?='' OR lower(player)=?) ORDER BY CASE status WHEN 'open' THEN 0 WHEN 'investigating' THEN 1 ELSE 2 END,updated_at DESC LIMIT ?";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, safeStatus); ps.setString(2, safeStatus);
            ps.setString(3, safePlayer); ps.setString(4, safePlayer);
            ps.setInt(5, bounded(limit, 1, 300));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new SupportCase(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getLong(9),
                        supportReplies(connection, rs.getString(1))));
            }
        } catch (SQLException ignored) {
        }
        return List.copyOf(rows);
    }

    private List<SupportReply> supportReplies(Connection connection, String caseId) throws SQLException {
        List<SupportReply> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id,author,message,public_reply,created_at FROM intel_support_replies WHERE case_id=? ORDER BY created_at")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new SupportReply(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4) == 1, rs.getLong(5)));
            }
        }
        return List.copyOf(rows);
    }

    // Just-in-Time Staff Access

    public synchronized String grantTemporaryAccess(String username, String permission, int minutes, String actor) {
        String user = clean(username, 64, "");
        String grant = clean(permission, 160, "").toLowerCase(Locale.ROOT);
        if (user.isBlank() || grant.isBlank() || !grant.matches("[a-z0-9.*_-]+(?:\\.[a-z0-9.*_-]+)*")) {
            return "Temporary access user or permission is invalid.";
        }
        if (minutes < 1 || minutes > 1_440) return "Temporary access duration must be 1-1440 minutes.";
        long now = System.currentTimeMillis();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO intel_temp_grants(id,username,permission,granted_by,expires_at,revoked_at,created_at) VALUES(?,?,?,?,?,0,?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, user);
            ps.setString(3, grant);
            ps.setString(4, clean(actor, 64, "web"));
            ps.setLong(5, now + TimeUnit.MINUTES.toMillis(minutes));
            ps.setLong(6, now);
            ps.executeUpdate();
            return "Just-in-time access granted until " + Instant.ofEpochMilli(now + TimeUnit.MINUTES.toMillis(minutes)) + ".";
        } catch (SQLException ex) {
            return "Temporary access could not be granted.";
        }
    }

    public synchronized String revokeTemporaryAccess(String id, String actor) {
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE intel_temp_grants SET revoked_at=? WHERE id=? AND revoked_at=0")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, clean(id, 80, ""));
            return ps.executeUpdate() == 1 ? "Temporary access revoked by " + clean(actor, 64, "web") + "."
                    : "Active temporary access was not found.";
        } catch (SQLException ex) {
            return "Temporary access could not be revoked.";
        }
    }

    public boolean hasTemporaryGrant(String username, String requested) {
        String user = clean(username, 64, "");
        String target = clean(requested, 160, "").toLowerCase(Locale.ROOT);
        if (user.isBlank() || target.isBlank()) return false;
        long now = System.currentTimeMillis();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "SELECT permission FROM intel_temp_grants WHERE lower(username)=lower(?) AND revoked_at=0 AND expires_at>?")) {
            ps.setString(1, user);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) if (permissionMatches(rs.getString(1), target)) return true;
            }
        } catch (SQLException ignored) {
        }
        return false;
    }

    public List<TemporaryGrant> temporaryGrants(boolean activeOnly) {
        List<TemporaryGrant> rows = new ArrayList<>();
        String sql = "SELECT id,username,permission,granted_by,expires_at,revoked_at,created_at FROM intel_temp_grants"
                + (activeOnly ? " WHERE revoked_at=0 AND expires_at>" + System.currentTimeMillis() : "")
                + " ORDER BY created_at DESC LIMIT 200";
        try (Connection connection = connect(); Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) rows.add(new TemporaryGrant(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)));
        } catch (SQLException ignored) {
        }
        return List.copyOf(rows);
    }

    // Dual-control approval lifecycle

    public synchronized String decideApproval(String id, String approver, boolean approve) {
        Approval row = approvals(200).stream().filter(item -> item.id().equals(clean(id, 80, ""))).findFirst().orElse(null);
        if (row == null || !"pending".equals(row.status()) || row.expiresAt() <= System.currentTimeMillis()) {
            return "Pending approval request not found or expired.";
        }
        if (row.requestedBy().equalsIgnoreCase(clean(approver, 64, ""))) return "A different operator must decide this request.";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE intel_approvals SET status=?,approved_by=? WHERE id=? AND status='pending'")) {
            ps.setString(1, approve ? "approved" : "rejected");
            ps.setString(2, clean(approver, 64, "web"));
            ps.setString(3, row.id());
            return ps.executeUpdate() == 1 ? "Approval request " + (approve ? "approved." : "rejected.")
                    : "Approval request changed concurrently.";
        } catch (SQLException ex) {
            return "Approval request could not be updated.";
        }
    }

    public List<Approval> approvals(int limit) {
        List<Approval> rows = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "SELECT id,action,payload_hash,requested_by,status,approved_by,expires_at,created_at,used_at FROM intel_approvals ORDER BY created_at DESC LIMIT ?")) {
            ps.setInt(1, bounded(limit, 1, 500));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new Approval(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getLong(8), rs.getLong(9)));
            }
        } catch (SQLException ignored) {
        }
        return List.copyOf(rows);
    }

    private String ensureApprovalRequest(String action, String payloadHash, String actor) {
        long now = System.currentTimeMillis();
        for (Approval row : approvals(500)) {
            if (row.action().equals(action) && row.payloadHash().equals(payloadHash)
                    && row.requestedBy().equalsIgnoreCase(clean(actor, 64, ""))
                    && "pending".equals(row.status()) && row.expiresAt() > now) return row.id();
        }
        String id = UUID.randomUUID().toString();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO intel_approvals(id,action,payload_hash,requested_by,status,approved_by,expires_at,created_at,used_at) VALUES(?,?,?,?,'pending','',?,?,0)")) {
            ps.setString(1, id);
            ps.setString(2, action);
            ps.setString(3, payloadHash);
            ps.setString(4, clean(actor, 64, "web"));
            ps.setLong(5, now + TimeUnit.MINUTES.toMillis(30));
            ps.setLong(6, now);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
        return id;
    }

    private synchronized boolean consumeApproval(String action, String payloadHash, String actor) {
        long now = System.currentTimeMillis();
        try (Connection connection = connect(); PreparedStatement find = connection.prepareStatement(
                "SELECT id FROM intel_approvals WHERE action=? AND payload_hash=? AND lower(requested_by)=lower(?) AND status='approved' AND used_at=0 AND expires_at>? ORDER BY created_at DESC LIMIT 1")) {
            find.setString(1, action);
            find.setString(2, payloadHash);
            find.setString(3, clean(actor, 64, ""));
            find.setLong(4, now);
            try (ResultSet rs = find.executeQuery()) {
                if (!rs.next()) return false;
                String id = rs.getString(1);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE intel_approvals SET used_at=?,status='used' WHERE id=? AND used_at=0")) {
                    update.setLong(1, now);
                    update.setString(2, id);
                    return update.executeUpdate() == 1;
                }
            }
        } catch (SQLException ignored) {
            return false;
        }
    }

    // Supply Chain Center

    public List<SupplyArtifact> supplyChain() {
        Map<String, Integer> hashes = new HashMap<>();
        List<SupplyArtifact> rows = new ArrayList<>();
        for (Path artifact : artifactPaths()) {
            String sha = "unavailable";
            long bytes = 0L;
            int entries = 0;
            int signedEntries = 0;
            int nativeEntries = 0;
            int scripts = 0;
            boolean readable = true;
            List<String> warnings = new ArrayList<>();
            try {
                sha = hashFile(artifact);
                bytes = Files.size(artifact);
                try (JarFile jar = new JarFile(artifact.toFile(), true)) {
                    var iterator = jar.entries();
                    byte[] buffer = new byte[16 * 1024];
                    while (iterator.hasMoreElements()) {
                        JarEntry entry = iterator.nextElement();
                        if (entry.isDirectory()) continue;
                        entries++;
                        String lower = entry.getName().toLowerCase(Locale.ROOT);
                        if (lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib")) nativeEntries++;
                        if (lower.endsWith(".bat") || lower.endsWith(".cmd") || lower.endsWith(".ps1")
                                || lower.endsWith(".sh") || lower.endsWith(".exe")) scripts++;
                        try (InputStream input = jar.getInputStream(entry)) {
                            while (input.read(buffer) >= 0) { }
                        }
                        Certificate[] certificates = entry.getCertificates();
                        if (certificates != null && certificates.length > 0) signedEntries++;
                    }
                }
            } catch (Exception ex) {
                readable = false;
                warnings.add("Artifact could not be fully verified: " + clean(ex.getMessage(), 200, "invalid JAR"));
            }
            hashes.merge(sha, 1, Integer::sum);
            if (signedEntries == 0) warnings.add("No signed content was detected.");
            if (nativeEntries > 0) warnings.add(nativeEntries + " native binaries require additional trust review.");
            if (scripts > 0) warnings.add(scripts + " executable script entries were found.");
            ArtifactDescriptor descriptor = descriptor(artifact);
            String trust = !readable ? "blocked" : scripts > 0 ? "review" : signedEntries > 0 ? "signed" : "unsigned";
            rows.add(new SupplyArtifact(artifact.getFileName().toString(), descriptor.id(), descriptor.loader(), sha,
                    bytes, entries, signedEntries, nativeEntries, scripts, trust, List.copyOf(warnings)));
        }
        List<SupplyArtifact> enriched = new ArrayList<>();
        for (SupplyArtifact row : rows) {
            List<String> warnings = new ArrayList<>(row.warnings());
            if (hashes.getOrDefault(row.sha256(), 0) > 1) warnings.add("Identical artifact content is installed more than once.");
            enriched.add(new SupplyArtifact(row.fileName(), row.identity(), row.loader(), row.sha256(), row.bytes(),
                    row.entries(), row.signedEntries(), row.nativeEntries(), row.scriptEntries(), row.trust(),
                    List.copyOf(warnings)));
        }
        enriched.sort(Comparator.comparing(SupplyArtifact::trust).thenComparing(SupplyArtifact::fileName));
        return List.copyOf(enriched);
    }

    // Retention Center

    public RetentionPolicy retentionPolicy() {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT log_days,backup_days,crash_days,keep_min_backups,updated_by,updated_at FROM intel_retention WHERE id=1")) {
            if (rs.next()) return new RetentionPolicy(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                    rs.getString(5), rs.getLong(6));
        } catch (SQLException ignored) {
        }
        return new RetentionPolicy(14, 30, 30, 3, "system", 0L);
    }

    public synchronized String saveRetentionPolicy(int logDays, int backupDays, int crashDays,
            int keepMinBackups, String actor) {
        if (logDays < 1 || logDays > 3650 || backupDays < 1 || backupDays > 3650
                || crashDays < 1 || crashDays > 3650 || keepMinBackups < 1 || keepMinBackups > 100) {
            return "Retention values are outside the supported range.";
        }
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE intel_retention SET log_days=?,backup_days=?,crash_days=?,keep_min_backups=?,updated_by=?,updated_at=? WHERE id=1")) {
            ps.setInt(1, logDays); ps.setInt(2, backupDays); ps.setInt(3, crashDays); ps.setInt(4, keepMinBackups);
            ps.setString(5, clean(actor, 64, "web")); ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
            return "Retention policy saved.";
        } catch (SQLException ex) {
            return "Retention policy could not be saved.";
        }
    }

    public RetentionPreview retentionPreview() {
        RetentionPolicy policy = retentionPolicy();
        List<Path> candidates = retentionCandidates(policy);
        long bytes = candidates.stream().mapToLong(path -> {
            try { return Files.size(path); } catch (IOException ignored) { return 0L; }
        }).sum();
        List<String> sample = candidates.stream().limit(30).map(this::relativeOrData).toList();
        return new RetentionPreview(candidates.size(), bytes, sample);
    }

    public synchronized String applyRetention(String actor) {
        RetentionPolicy policy = retentionPolicy();
        List<Path> candidates = retentionCandidates(policy);
        int deleted = 0;
        long bytes = 0L;
        for (Path path : candidates) {
            try {
                if (!isAllowedRetentionPath(path)) continue;
                long size = Files.size(path);
                if (Files.deleteIfExists(path)) { deleted++; bytes += size; }
            } catch (IOException ignored) {
            }
        }
        return "Retention applied by " + clean(actor, 64, "web") + ": " + deleted + " files and "
                + formatBytes(bytes) + " removed.";
    }

    private List<Path> retentionCandidates(RetentionPolicy policy) {
        List<Path> result = new ArrayList<>();
        collectOldFiles(serverRoot.resolve("logs"), policy.logDays(), result, path -> {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            return name.endsWith(".log") || name.endsWith(".log.gz") || name.endsWith(".gz");
        });
        collectOldFiles(serverRoot.resolve("crash-reports"), policy.crashDays(), result,
                path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"));
        List<Path> backups = new ArrayList<>();
        for (Path directory : backupDirectories()) collectOldFiles(directory, policy.backupDays(), backups,
                path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"));
        backups.sort(Comparator.comparingLong(this::lastModifiedSafe).reversed());
        if (backups.size() > policy.keepMinBackups()) result.addAll(backups.subList(policy.keepMinBackups(), backups.size()));
        return result.stream().distinct().sorted().toList();
    }

    private void collectOldFiles(Path directory, int days, List<Path> target, java.util.function.Predicate<Path> filter) {
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) return;
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        try (Stream<Path> stream = Files.walk(directory, 3)) {
            stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(filter)
                    .filter(path -> lastModifiedSafe(path) < cutoff)
                    .limit(10_000).forEach(target::add);
        } catch (IOException ignored) {
        }
    }

    // Config Schema Assistant

    public List<String> configCandidates() {
        return configCandidatePaths().stream().map(this::relative).sorted().limit(500).toList();
    }

    public ConfigDocument inspectConfig(String rawPath) {
        try {
            Path file = safeServerPath(rawPath);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                    || Files.size(file) > MAX_STATE_FILE_BYTES || !isConfigFile(file)) {
                return new ConfigDocument("", "unsupported", false, List.of(), List.of("Configuration file is unavailable."));
            }
            String relative = relative(file);
            String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
            List<ConfigField> fields = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            boolean editable = true;
            if (lower.endsWith(".properties")) {
                Properties properties = new Properties();
                try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
                properties.stringPropertyNames().stream().sorted().limit(500).forEach(key -> {
                    String value = properties.getProperty(key, "");
                    fields.add(new ConfigField(key, value, inferType(value), inferConstraint(key, value), true));
                });
            } else if (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".toml")) {
                boolean toml = lower.endsWith(".toml");
                List<ParsedScalar> scalars = parseRootScalars(
                        Files.readAllLines(file, StandardCharsets.UTF_8), toml);
                Map<String, Long> counts = new HashMap<>();
                for (ParsedScalar scalar : scalars) counts.merge(scalar.key(), 1L, Long::sum);
                for (ParsedScalar scalar : scalars) {
                    if (fields.size() >= 500) break;
                    boolean unique = counts.getOrDefault(scalar.key(), 0L) == 1L;
                    fields.add(new ConfigField(scalar.key(), scalar.value(), inferType(scalar.value()),
                            unique ? inferConstraint(scalar.key(), scalar.value()) : "duplicate key is read-only",
                            unique));
                }
                warnings.add("Only unique root scalar keys are editable; nested structures, tables and lists remain read-only.");
                if (counts.values().stream().anyMatch(count -> count > 1L)) {
                    warnings.add("Duplicate root keys are shown read-only to avoid updating an ambiguous value.");
                }
            } else {
                editable = false;
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher scalar = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\"(?:\\\\.|[^\"])*\"|true|false|null|-?\\d+(?:\\.\\d+)?)").matcher(text);
                while (scalar.find() && fields.size() < 500) {
                    String value = scalar.group(2);
                    fields.add(new ConfigField(scalar.group(1), value, inferType(value), "read-only JSON scalar", false));
                }
                warnings.add("JSON is inspected structurally but edited only through the file editor to preserve nesting.");
            }
            return new ConfigDocument(relative, lower.substring(lower.lastIndexOf('.') + 1), editable,
                    List.copyOf(fields), List.copyOf(warnings));
        } catch (Exception ex) {
            return new ConfigDocument("", "invalid", false, List.of(), List.of("Inspection failed safely."));
        }
    }

    public synchronized String updateConfigScalar(String rawPath, String key, String value, String actor) {
        String safeKey = clean(key, 200, "");
        String safeValue = clean(value, 4_000, "");
        if (safeKey.isBlank() || !safeKey.matches("[A-Za-z0-9_.-]+")) return "Configuration key is invalid.";
        if (value == null || value.indexOf('\0') >= 0 || value.contains("\r") || value.contains("\n")) {
            return "Configuration values must be a single safe scalar line.";
        }
        try {
            Path file = safeServerPath(rawPath);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                    || Files.size(file) > MAX_STATE_FILE_BYTES || !isConfigFile(file)) return "Configuration file is unavailable.";
            String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".json")) return "Nested JSON updates remain read-only; use the guarded file editor.";
            ConfigDocument document = inspectConfig(relative(file));
            List<ConfigField> matches = document.fields().stream().filter(field -> field.key().equals(safeKey)).toList();
            if (matches.size() != 1 || !matches.get(0).editable()) {
                return matches.isEmpty() ? "Configuration scalar key not found."
                        : "Configuration key is ambiguous or read-only.";
            }
            String validation = validateScalarUpdate(safeKey, safeValue, matches.get(0).type(), lower);
            if (!validation.isBlank()) return validation;

            String originalHash = hashFile(file);
            Path temporary = Files.createTempFile(file.getParent(), ".dash-config-", ".tmp");
            try {
                if (lower.endsWith(".properties")) {
                    Properties properties = new Properties();
                    try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
                    if (!properties.containsKey(safeKey)) return "Configuration key not found.";
                    properties.setProperty(safeKey, safeValue);
                    try (OutputStream output = Files.newOutputStream(temporary)) {
                        properties.store(output, "Updated by Dash Config Schema Assistant");
                    }
                } else {
                    boolean toml = lower.endsWith(".toml");
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    List<ParsedScalar> scalars = parseRootScalars(lines, toml).stream()
                            .filter(scalar -> scalar.key().equals(safeKey)).toList();
                    if (scalars.size() != 1) return "Configuration scalar key is missing or ambiguous.";
                    int lineIndex = scalars.get(0).lineIndex();
                    char separator = toml ? '=' : ':';
                    List<String> updated = new ArrayList<>(lines);
                    updated.set(lineIndex, safeKey + (separator == ':' ? ": " : " = ") + safeValue);
                    Files.write(temporary, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                }
                captureStateInternal("Automatic snapshot before config assistant update", actor);
                if (!hashFile(file).equals(originalHash)) {
                    return "Configuration changed concurrently; review and retry the update.";
                }
                atomicReplace(temporary, file);
                return "Configuration scalar updated after a Time Machine safety snapshot.";
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (Exception ex) {
            return "Configuration update failed safely without changing the live file.";
        }
    }

    private List<ParsedScalar> parseRootScalars(List<String> lines, boolean toml) {
        List<ParsedScalar> result = new ArrayList<>();
        boolean insideTomlTable = false;
        char separator = toml ? '=' : ':';
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue;
            if (toml && trimmed.startsWith("[")) { insideTomlTable = true; continue; }
            if (insideTomlTable || Character.isWhitespace(line.charAt(0)) || trimmed.startsWith("-")) continue;
            int split = line.indexOf(separator);
            if (split <= 0) continue;
            String scalarKey = line.substring(0, split).trim();
            String scalarValue = line.substring(split + 1).trim();
            if (!scalarKey.matches("[A-Za-z0-9_.-]+") || !isEditableScalarLiteral(scalarValue)) continue;
            result.add(new ParsedScalar(scalarKey, scalarValue, index));
        }
        return List.copyOf(result);
    }

    private boolean isEditableScalarLiteral(String value) {
        if (value == null || value.isBlank()) return false;
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) return true;
        return !trimmed.startsWith("[") && !trimmed.startsWith("{") && !trimmed.startsWith("|")
                && !trimmed.startsWith(">") && !trimmed.startsWith("&") && !trimmed.startsWith("*")
                && !trimmed.startsWith("!");
    }

    private String validateScalarUpdate(String key, String value, String type, String fileName) {
        if (value.isBlank()) return "Use an explicit quoted empty string instead of an empty scalar.";
        String unquoted = stripMatchingQuotes(value.trim());
        if ("boolean".equals(type) && !Set.of("true", "false").contains(unquoted.toLowerCase(Locale.ROOT))) {
            return "This configuration value must remain true or false.";
        }
        if ("integer".equals(type) && !unquoted.matches("-?\\d+")) {
            return "This configuration value must remain an integer.";
        }
        if ("decimal".equals(type) && !unquoted.matches("-?(?:\\d+\\.\\d+|\\d+)")) {
            return "This configuration value must remain numeric.";
        }
        if ("color".equals(type) && !unquoted.matches("#[0-9a-fA-F]{6,8}")) {
            return "This configuration value must remain a six- or eight-digit color.";
        }
        String lowerKey = key.toLowerCase(Locale.ROOT);
        if (lowerKey.contains("port")) {
            try {
                int port = Integer.parseInt(unquoted);
                if (port < 1 || port > 65_535) return "Port values must be between 1 and 65535.";
            } catch (NumberFormatException ex) {
                return "Port values must be integers between 1 and 65535.";
            }
        }
        if (lowerKey.contains("distance")) {
            try {
                if (Double.parseDouble(unquoted) < 0.0d) return "Distance values cannot be negative.";
            } catch (NumberFormatException ex) {
                return "Distance values must remain numeric.";
            }
        }
        boolean quoted = (value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"));
        if (!fileName.endsWith(".properties") && !quoted
                && (value.startsWith("!") || value.startsWith("&") || value.startsWith("*")
                        || value.startsWith("[") || value.startsWith("{") || value.contains(" #"))) {
            return "Quote structured or comment-like text before saving it as a scalar.";
        }
        return "";
    }

    private String stripMatchingQuotes(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    // Universal Search

    public List<SearchHit> universalSearch(String query, int limit) {
        String q = clean(query, 160, "").toLowerCase(Locale.ROOT);
        if (q.isBlank()) return List.of();
        List<SearchHit> hits = new ArrayList<>();
        for (Path artifact : artifactPaths()) {
            if (artifact.getFileName().toString().toLowerCase(Locale.ROOT).contains(q)) {
                hits.add(new SearchHit("artifact", artifact.getFileName().toString(), relative(artifact), platform, 95));
            }
        }
        for (String config : configCandidates()) {
            if (config.toLowerCase(Locale.ROOT).contains(q)) hits.add(new SearchHit("configuration", config, config, platform, 85));
        }
        for (String line : recentLogLines(2_000)) {
            if (line.toLowerCase(Locale.ROOT).contains(q)) hits.add(new SearchHit("log", clean(line, 240, ""), "logs/latest.log", platform, 70));
            if (hits.size() >= 500) break;
        }
        for (SupportCase support : supportCases("", "", 300)) {
            if ((support.subject() + " " + support.message() + " " + support.player()).toLowerCase(Locale.ROOT).contains(q)) {
                hits.add(new SearchHit("support", support.subject(), support.id(), platform, 90));
            }
        }
        for (WarRoom room : warRooms(100)) {
            if ((room.title() + " " + room.summary() + " " + room.publicMessage()).toLowerCase(Locale.ROOT).contains(q)) {
                hits.add(new SearchHit("war-room", room.title(), room.id(), platform, 90));
            }
        }
        for (PlayerIdentity player : searchPlayerIdentities(q, 50)) {
            hits.add(new SearchHit("player", player.name(), player.uuid(), platform, 100));
        }
        hits.sort(Comparator.comparingInt(SearchHit::score).reversed().thenComparing(SearchHit::title));
        return List.copyOf(hits.stream().limit(bounded(limit, 1, 300)).toList());
    }

    // World Health Center and Storage Intelligence

    public WorldHealth worldHealth() {
        List<WorldFinding> findings = new ArrayList<>();
        int worlds = 0;
        int regions = 0;
        int invalidRegions = 0;
        long bytes = 0L;
        try (Stream<Path> stream = Files.list(serverRoot)) {
            for (Path world : stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> Files.exists(path.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS)
                            || Files.isDirectory(path.resolve("region"))).toList()) {
                worlds++;
                for (String folder : List.of("region", "entities", "poi", "DIM-1/region", "DIM1/region")) {
                    Path regionDir = world.resolve(folder).normalize();
                    if (!regionDir.startsWith(world) || !Files.isDirectory(regionDir) || Files.isSymbolicLink(regionDir)) continue;
                    try (Stream<Path> files = Files.list(regionDir)) {
                        for (Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                                .filter(path -> path.getFileName().toString().endsWith(".mca")).limit(20_000).toList()) {
                            regions++;
                            long size = Files.size(file);
                            bytes += size;
                            String problem = validateRegionFile(file);
                            if (!problem.isBlank()) { invalidRegions++; findings.add(new WorldFinding(relative(file), "critical", problem)); }
                            else if (size > 128L * 1024L * 1024L) findings.add(new WorldFinding(relative(file), "warning", "Region exceeds 128 MiB."));
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
        String status = invalidRegions > 0 ? "degraded" : findings.isEmpty() ? "healthy" : "watch";
        return new WorldHealth(status, worlds, regions, invalidRegions, bytes,
                List.copyOf(findings.stream().limit(100).toList()));
    }

    private String validateRegionFile(Path file) {
        try {
            long length = Files.size(file);
            if (length < 8_192L) return "Region header is truncated.";
            byte[] header = new byte[4_096];
            try (InputStream input = Files.newInputStream(file)) {
                int offset = 0;
                while (offset < header.length) {
                    int read = input.read(header, offset, header.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
                if (offset != header.length) return "Region location table is truncated.";
            }
            ByteBuffer locations = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
            for (int i = 0; i < 1_024; i++) {
                int value = locations.getInt();
                int sector = (value >>> 8) & 0xFFFFFF;
                int count = value & 0xFF;
                if (sector == 0 && count == 0) continue;
                if (sector < 2 || count == 0 || ((long) sector + count) * 4_096L > length) {
                    return "Region chunk location points outside the file.";
                }
            }
        } catch (IOException ex) {
            return "Region file could not be read.";
        }
        return "";
    }

    public StorageReport storageIntelligence() {
        List<StorageBucket> buckets = new ArrayList<>();
        try (Stream<Path> stream = Files.list(serverRoot)) {
            for (Path path : stream.filter(item -> !Files.isSymbolicLink(item)).limit(300).toList()) {
                long bytes = directorySizeBounded(path, 40_000);
                String category = classifyStorage(path.getFileName().toString());
                boolean cleanup = Set.of("logs", "crash-reports", "cache", "downloads").contains(path.getFileName().toString().toLowerCase(Locale.ROOT));
                buckets.add(new StorageBucket(path.getFileName().toString(), category, bytes, cleanup));
            }
        } catch (IOException ignored) {
        }
        buckets.sort(Comparator.comparingLong(StorageBucket::bytes).reversed());
        long total = buckets.stream().mapToLong(StorageBucket::bytes).sum();
        long reclaimable = retentionPreview().bytes();
        long usable = 0L;
        try { usable = Files.getFileStore(serverRoot).getUsableSpace(); } catch (IOException ignored) { }
        return new StorageReport(total, usable, reclaimable, List.copyOf(buckets.stream().limit(80).toList()));
    }

    private String classifyStorage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("world")) return "world";
        if (lower.equals("plugins") || lower.equals("mods") || lower.equals("config")) return "runtime";
        if (lower.contains("backup")) return "backup";
        if (lower.equals("logs") || lower.equals("crash-reports")) return "diagnostic";
        return "other";
    }

    // Service Level Dashboard

    public synchronized String recordServiceSample(boolean online, double tps, double mspt) {
        long now = System.currentTimeMillis();
        try (Connection connection = connect()) {
            try (PreparedStatement recent = connection.prepareStatement(
                    "SELECT sampled_at FROM intel_service ORDER BY sampled_at DESC LIMIT 1")) {
                try (ResultSet rs = recent.executeQuery()) {
                    if (rs.next() && now - rs.getLong(1) < TimeUnit.MINUTES.toMillis(5)) return "Service sample is current.";
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO intel_service(sampled_at,online,tps,mspt,backup_age_hours) VALUES(?,?,?,?,?)")) {
                ps.setLong(1, now); ps.setInt(2, online ? 1 : 0);
                ps.setDouble(3, Math.max(0.0d, Math.min(20.0d, tps)));
                ps.setDouble(4, Math.max(0.0d, Math.min(10_000.0d, mspt)));
                ps.setDouble(5, latestBackupAgeHours());
                ps.executeUpdate();
            }
            trimTable("intel_service", "id", 2_016);
            return "Service sample recorded.";
        } catch (SQLException ex) {
            return "Service sample could not be stored.";
        }
    }

    public ServiceLevel serviceLevel() {
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30);
        int samples = 0;
        int online = 0;
        double tps = 0.0d;
        double mspt = 0.0d;
        double backupAge = 0.0d;
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "SELECT online,tps,mspt,backup_age_hours FROM intel_service WHERE sampled_at>=?")) {
            ps.setLong(1, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    samples++; online += rs.getInt(1); tps += rs.getDouble(2); mspt += rs.getDouble(3); backupAge += rs.getDouble(4);
                }
            }
        } catch (SQLException ignored) {
        }
        if (samples == 0) return new ServiceLevel(0, 0.0d, 0.0d, 0.0d, 0.0d, "learning");
        double availability = online * 100.0d / samples;
        double avgTps = tps / samples;
        double avgMspt = mspt / samples;
        double avgBackup = backupAge / samples;
        String status = availability >= 99.0d && avgTps >= 18.0d && avgBackup <= 24.0d ? "meeting" : "at-risk";
        return new ServiceLevel(samples, availability, avgTps, avgMspt, avgBackup, status);
    }

    // Operator War Room and Public Status Page

    public synchronized String createWarRoom(String title, String severity, String summary, String commander) {
        String safeTitle = clean(title, 160, "");
        String safeSummary = clean(summary, 4_000, "");
        if (safeTitle.isBlank() || safeSummary.isBlank()) return "War room title and summary are required.";
        String safeSeverity = normalizeChoice(severity, Set.of("info", "warning", "critical"), "");
        if (safeSeverity.isBlank()) return "Unsupported war room severity.";
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO intel_war_rooms(id,title,severity,status,commander,summary,public_message,created_at,closed_at) VALUES(?,?,?,'open',?,?,'',?,0)")) {
            ps.setString(1, id); ps.setString(2, safeTitle);
            ps.setString(3, safeSeverity);
            ps.setString(4, clean(commander, 64, "web")); ps.setString(5, safeSummary); ps.setLong(6, now);
            ps.executeUpdate();
            return "Operator War Room opened: " + id.substring(0, 8) + ".";
        } catch (SQLException ex) {
            return "War room could not be opened.";
        }
    }

    public synchronized String addWarRoomUpdate(String roomId, String author, String message, String kind,
            boolean publish) {
        String safe = clean(message, 4_000, "");
        if (safe.isBlank()) return "War room update is required.";
        String safeKind = normalizeChoice(kind, Set.of("update", "decision", "action", "resolution"), "");
        if (safeKind.isBlank()) return "Unsupported war room update kind.";
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO intel_war_updates(id,room_id,author,message,kind,created_at) VALUES(?,?,?,?,?,?)");
                    PreparedStatement publishUpdate = connection.prepareStatement(
                            "UPDATE intel_war_rooms SET public_message=CASE WHEN ?=1 THEN ? ELSE public_message END WHERE id=? AND status='open'")) {
                insert.setString(1, UUID.randomUUID().toString()); insert.setString(2, clean(roomId, 80, ""));
                insert.setString(3, clean(author, 64, "web")); insert.setString(4, safe); insert.setString(5, safeKind);
                insert.setLong(6, System.currentTimeMillis()); insert.executeUpdate();
                publishUpdate.setInt(1, publish ? 1 : 0); publishUpdate.setString(2, safe);
                publishUpdate.setString(3, clean(roomId, 80, ""));
                if (publishUpdate.executeUpdate() != 1) throw new SQLException("War room not found.");
                connection.commit();
                return "War room update added" + (publish ? " and published." : ".");
            } catch (SQLException ex) { connection.rollback(); throw ex; }
        } catch (SQLException ex) {
            return "War room update could not be saved.";
        }
    }

    public synchronized String closeWarRoom(String id, String actor, String resolution) {
        String safeResolution = clean(resolution, 4_000, "");
        if (safeResolution.isBlank()) return "A public incident resolution is required.";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE intel_war_rooms SET status='closed',closed_at=?,public_message=? WHERE id=? AND status='open'")) {
            ps.setLong(1, System.currentTimeMillis()); ps.setString(2, safeResolution); ps.setString(3, clean(id, 80, ""));
            return ps.executeUpdate() == 1 ? "War room closed and public resolution updated." : "Open war room not found.";
        } catch (SQLException ex) {
            return "War room could not be closed.";
        }
    }

    public List<WarRoom> warRooms(int limit) {
        List<WarRoom> rows = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "SELECT id,title,severity,status,commander,summary,public_message,created_at,closed_at FROM intel_war_rooms ORDER BY CASE status WHEN 'open' THEN 0 ELSE 1 END,created_at DESC LIMIT ?")) {
            ps.setInt(1, bounded(limit, 1, 200));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new WarRoom(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getLong(9),
                        warRoomUpdates(connection, rs.getString(1))));
            }
        } catch (SQLException ignored) {
        }
        return List.copyOf(rows);
    }

    private List<WarRoomUpdate> warRoomUpdates(Connection connection, String roomId) throws SQLException {
        List<WarRoomUpdate> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id,author,message,kind,created_at FROM intel_war_updates WHERE room_id=? ORDER BY created_at DESC LIMIT 100")) {
            ps.setString(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new WarRoomUpdate(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)));
            }
        }
        return List.copyOf(rows);
    }

    public synchronized String updateStatusComponent(String component, String status, String message, String actor) {
        String safeComponent = clean(component, 100, "");
        String safeStatus = normalizeChoice(status, Set.of("operational", "degraded", "partial-outage", "major-outage", "maintenance"), "");
        if (safeComponent.isBlank() || safeStatus.isBlank()) return "Status component or state is invalid.";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO intel_status(component,status,message,updated_by,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(component) DO UPDATE SET status=excluded.status,message=excluded.message,updated_by=excluded.updated_by,updated_at=excluded.updated_at")) {
            ps.setString(1, safeComponent); ps.setString(2, safeStatus); ps.setString(3, clean(message, 500, ""));
            ps.setString(4, clean(actor, 64, "web")); ps.setLong(5, System.currentTimeMillis()); ps.executeUpdate();
            return "Public status component updated.";
        } catch (SQLException ex) {
            return "Public status could not be updated.";
        }
    }

    public PublicStatus publicStatus() {
        List<StatusComponent> components = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT component,status,message,updated_at FROM intel_status ORDER BY component")) {
            while (rs.next()) components.add(new StatusComponent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
        } catch (SQLException ignored) {
        }
        List<PublicIncident> incidents = warRooms(50).stream()
                .filter(room -> !room.publicMessage().isBlank())
                .map(room -> new PublicIncident(room.title(), room.severity(), room.status(), room.publicMessage(),
                        room.createdAt(), room.closedAt())).toList();
        String componentOverall = components.stream().map(StatusComponent::status)
                .max(Comparator.comparingInt(this::statusWeight)).orElse("operational");
        String incidentOverall = incidents.stream().filter(incident -> !"closed".equals(incident.status()))
                .map(incident -> switch (incident.severity()) {
                    case "critical" -> "major-outage";
                    case "warning" -> "degraded";
                    default -> "maintenance";
                })
                .max(Comparator.comparingInt(this::statusWeight)).orElse("operational");
        String overall = statusWeight(componentOverall) >= statusWeight(incidentOverall)
                ? componentOverall : incidentOverall;
        return new PublicStatus(overall, List.copyOf(components), List.copyOf(incidents), System.currentTimeMillis());
    }

    // Shared bounded filesystem and persistence helpers

    private Map<String, FileFingerprint> trackedStateFiles() {
        Map<String, FileFingerprint> rows = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(serverRoot, 7)) {
            for (Path path : stream.filter(item -> Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS))
                    .filter(item -> !Files.isSymbolicLink(item))
                    .filter(this::isStateCandidate)
                    .sorted().limit(MAX_STATE_FILES).toList()) {
                try { rows.put(relative(path), new FileFingerprint(hashFile(path), Files.size(path), lastModifiedSafe(path))); }
                catch (IOException ignored) { }
            }
        } catch (IOException ignored) {
        }
        return rows;
    }

    private boolean isStateCandidate(Path path) {
        if (path.startsWith(dataDir)) return false;
        String rel = relative(path).toLowerCase(Locale.ROOT);
        if (rel.startsWith("logs/") || rel.startsWith("backups/") || rel.startsWith("crash-reports/")
                || rel.contains("/cache/") || rel.contains("/libraries/") || rel.contains("/versions/")
                || rel.contains("/region/") || rel.contains("/entities/") || rel.contains("/playerdata/")) return false;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        long size;
        try { size = Files.size(path); } catch (IOException ex) { return false; }
        if (name.endsWith(".jar")) return (rel.startsWith("plugins/") || rel.startsWith("mods/"))
                && size <= 64L * 1024L * 1024L;
        return isConfigFile(path) && size <= MAX_STATE_FILE_BYTES;
    }

    private boolean isConfigFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml")
                || name.endsWith(".toml") || name.endsWith(".json") || name.endsWith(".conf")
                || name.endsWith(".cfg") || name.endsWith(".txt");
    }

    private long stateFileLimit(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                ? 64L * 1024L * 1024L : MAX_STATE_FILE_BYTES;
    }

    private String encodeManifest(Map<String, FileFingerprint> rows) {
        StringBuilder out = new StringBuilder();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        for (Map.Entry<String, FileFingerprint> item : rows.entrySet()) {
            out.append(encoder.encodeToString(item.getKey().getBytes(StandardCharsets.UTF_8))).append('|')
                    .append(item.getValue().sha256()).append('|').append(item.getValue().size()).append('|')
                    .append(item.getValue().modifiedAt()).append('\n');
        }
        return out.toString();
    }

    private Map<String, FileFingerprint> decodeManifest(String manifest) {
        Map<String, FileFingerprint> rows = new LinkedHashMap<>();
        if (manifest == null) return rows;
        for (String line : manifest.split("\\R")) {
            String[] parts = line.split("\\|", -1);
            if (parts.length != 4) continue;
            try {
                String path = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
                rows.put(path, new FileFingerprint(parts[1], Long.parseLong(parts[2]), Long.parseLong(parts[3])));
            } catch (Exception ignored) { }
        }
        return rows;
    }

    private List<Path> artifactPaths() {
        List<Path> rows = new ArrayList<>();
        for (String folder : List.of("plugins", "mods")) {
            Path directory = serverRoot.resolve(folder).normalize();
            if (!directory.startsWith(serverRoot) || !Files.isDirectory(directory) || Files.isSymbolicLink(directory)) continue;
            try (Stream<Path> stream = Files.list(directory)) {
                stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !Files.isSymbolicLink(path))
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .limit(1_000).forEach(rows::add);
            } catch (IOException ignored) { }
        }
        rows.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)));
        return List.copyOf(rows);
    }

    private List<Path> backupDirectories() {
        return List.of(
                dataDir.resolve("backups").toAbsolutePath().normalize(),
                serverRoot.resolve("backups").toAbsolutePath().normalize(),
                serverRoot.resolve("plugins").resolve("Dash").resolve("backups").toAbsolutePath().normalize(),
                serverRoot.resolve("config").resolve("fabricdash").resolve("backups").toAbsolutePath().normalize(),
                serverRoot.resolve("config").resolve("forgedash").resolve("backups").toAbsolutePath().normalize(),
                serverRoot.resolve(".neodash").resolve("backups").toAbsolutePath().normalize());
    }

    private Optional<Path> locateBackup(String rawName) {
        String name = safeArchiveName(rawName);
        if (name.isBlank()) return Optional.empty();
        for (Path directory : backupDirectories()) {
            Path candidate = directory.resolve(name).normalize();
            if (candidate.startsWith(directory) && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private Optional<Path> locatePlayerDatabase() {
        return firstRegularFile(List.of(
                dataDir.resolve("playerdata.db"),
                serverRoot.resolve("plugins/Dash/playerdata.db"),
                serverRoot.resolve("config/fabricdash/playerdata.db"),
                serverRoot.resolve("config/forgedash/playerdata.db"),
                serverRoot.resolve(".neodash/playerdata.db")));
    }

    private Optional<Path> locateGuardianDatabase() {
        return firstRegularFile(List.of(
                dataDir.resolve("guardian.db"),
                serverRoot.resolve("plugins/Dash/guardian.db"),
                serverRoot.resolve("config/fabricdash/guardian.db"),
                serverRoot.resolve("config/forgedash/guardian.db"),
                serverRoot.resolve(".neodash/guardian.db")));
    }

    private Optional<Path> firstRegularFile(List<Path> candidates) {
        for (Path raw : candidates) {
            Path path = raw.toAbsolutePath().normalize();
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) return Optional.of(path);
        }
        return Optional.empty();
    }

    private List<String> recentLogLines(int limit) {
        Path log = serverRoot.resolve("logs/latest.log").normalize();
        if (!log.startsWith(serverRoot) || !Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(log)) return List.of();
        try {
            long size = Files.size(log);
            long start = Math.max(0L, size - MAX_LOG_BYTES);
            byte[] bytes = new byte[(int) (size - start)];
            try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(log.toFile(), "r")) {
                input.seek(start);
                input.readFully(bytes);
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            List<String> lines = text.lines().toList();
            int from = Math.max(0, lines.size() - bounded(limit, 1, 10_000));
            return List.copyOf(lines.subList(from, lines.size()));
        } catch (IOException ex) {
            return List.of();
        }
    }

    private List<Path> recentlyChangedStateFiles(long window, int limit) {
        long cutoff = System.currentTimeMillis() - window;
        List<Path> rows = new ArrayList<>();
        for (Path artifact : artifactPaths()) if (lastModifiedSafe(artifact) >= cutoff) rows.add(artifact);
        for (Path path : coreConfigCandidatePaths()) if (lastModifiedSafe(path) >= cutoff) rows.add(path);
        rows.sort(Comparator.comparingLong(this::lastModifiedSafe).reversed());
        return rows.stream().limit(bounded(limit, 1, 100)).toList();
    }

    private List<Path> configCandidatePaths() {
        LinkedHashSet<Path> rows = new LinkedHashSet<>(coreConfigCandidatePaths());
        collectConfigCandidates(serverRoot.resolve("plugins"), 3, rows, 800);
        return List.copyOf(rows);
    }

    private List<Path> coreConfigCandidatePaths() {
        LinkedHashSet<Path> rows = new LinkedHashSet<>();
        collectConfigCandidates(serverRoot, 1, rows, 100);
        collectConfigCandidates(serverRoot.resolve("config"), 7, rows, 600);
        return List.copyOf(rows);
    }

    private void collectConfigCandidates(Path root, int depth, Set<Path> target, int limit) {
        Path normalized = root.toAbsolutePath().normalize();
        if (!normalized.startsWith(serverRoot) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized) || target.size() >= limit) return;
        try (Stream<Path> stream = Files.walk(normalized, depth)) {
            var iterator = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(this::isConfigFile)
                    .iterator();
            while (iterator.hasNext() && target.size() < limit) target.add(iterator.next());
        } catch (IOException | SecurityException ignored) {
        }
    }

    private Path safeServerPath(String raw) throws IOException {
        String value = raw == null ? "" : raw.trim().replace('\\', '/');
        if (value.isBlank() || value.startsWith("/") || value.contains(":") || value.indexOf('\0') >= 0) {
            throw new IOException("Unsafe server path.");
        }
        for (String part : value.split("/", -1)) if (part.equals(".") || part.equals("..")) throw new IOException("Unsafe server path.");
        Path resolved = serverRoot.resolve(value).normalize();
        if (!resolved.startsWith(serverRoot)) throw new IOException("Server path escaped its root.");
        Path parent = resolved.getParent();
        if (parent != null) {
            Path cursor = serverRoot;
            for (Path part : serverRoot.relativize(parent)) {
                cursor = cursor.resolve(part);
                if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                    throw new IOException("Server path crosses a symbolic link.");
                }
            }
        }
        return resolved;
    }

    private void validateArchiveEntryName(String raw) throws IOException {
        if (raw == null || raw.isBlank() || raw.startsWith("/") || raw.startsWith("\\")
                || raw.contains("\\") || raw.contains(":") || raw.indexOf('\0') >= 0) throw new IOException("Unsafe archive entry.");
        for (String part : raw.split("/", -1)) if (part.equals(".") || part.equals("..")) throw new IOException("Unsafe archive entry.");
        Path normalized;
        try { normalized = Path.of(raw).normalize(); } catch (RuntimeException ex) { throw new IOException("Unsafe archive entry.", ex); }
        if (normalized.isAbsolute() || normalized.startsWith("..")) throw new IOException("Unsafe archive entry.");
    }

    private boolean isAllowedRetentionPath(Path raw) {
        Path path = raw.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return false;
        for (Path root : List.of(serverRoot.resolve("logs").normalize(), serverRoot.resolve("crash-reports").normalize())) {
            if (path.startsWith(root)) return true;
        }
        for (Path root : backupDirectories()) if (path.startsWith(root)) return true;
        return false;
    }

    private double latestBackupAgeHours() {
        long newest = 0L;
        for (Path directory : backupDirectories()) {
            if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) continue;
            try (Stream<Path> stream = Files.list(directory)) {
                newest = Math.max(newest, stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                        .mapToLong(this::lastModifiedSafe).max().orElse(0L));
            } catch (IOException ignored) { }
        }
        return newest <= 0L ? Double.POSITIVE_INFINITY
                : Math.max(0.0d, (System.currentTimeMillis() - newest) / (double) TimeUnit.HOURS.toMillis(1));
    }

    private long directorySizeBounded(Path root, int maxFiles) {
        if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) {
            try { return Files.size(root); } catch (IOException ignored) { return 0L; }
        }
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) return 0L;
        long[] size = {0L};
        int[] count = {0};
        try (Stream<Path> stream = Files.walk(root, 12)) {
            var iterator = stream.iterator();
            while (iterator.hasNext() && count[0] < maxFiles) {
                Path path = iterator.next();
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                    size[0] += Files.size(path); count[0]++;
                }
            }
        } catch (IOException ignored) { }
        return size[0];
    }

    private void trimTable(String table, String idColumn, int keep) {
        if (!Set.of("intel_performance", "intel_service").contains(table) || !"id".equals(idColumn)) return;
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + table + " WHERE " + idColumn + " NOT IN (SELECT " + idColumn
                    + " FROM " + table + " ORDER BY " + idColumn + " DESC LIMIT " + bounded(keep, 1, 10_000) + ")");
        } catch (SQLException ignored) { }
    }

    private static long copyBounded(InputStream input, OutputStream output, long maxBytes) throws IOException {
        return copyBounded(input, output, maxBytes, null);
    }

    private static long copyBounded(InputStream input, OutputStream output, long maxBytes, CRC32 crc) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            if (total > maxBytes - read) throw new IOException("Stream exceeds size limit.");
            output.write(buffer, 0, read);
            if (crc != null) crc.update(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private static String readTextBounded(InputStream input, long maxBytes) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        copyBounded(input, output, maxBytes);
        return output.toString(StandardCharsets.UTF_8);
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static void moveAtomic(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target); }
    }

    private void deleteTree(Path root) {
        if (root == null || !root.toAbsolutePath().normalize().startsWith(shadowDir)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    private void deleteTreeInside(Path root, Path allowedParent) {
        if (root == null || allowedParent == null) return;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedParent = allowedParent.toAbsolutePath().normalize();
        if (normalizedRoot.equals(normalizedParent) || !normalizedRoot.startsWith(normalizedParent)) return;
        try (Stream<Path> stream = Files.walk(normalizedRoot)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    private String relative(Path path) {
        try { return serverRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/'); }
        catch (Exception ignored) { return path.getFileName() == null ? "" : path.getFileName().toString(); }
    }

    private String relativeOrData(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(serverRoot)) return relative(normalized);
        if (normalized.startsWith(dataDir)) return "data/" + dataDir.relativize(normalized).toString().replace('\\', '/');
        return normalized.getFileName().toString();
    }

    private long lastModifiedSafe(Path path) {
        try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(); }
        catch (IOException ignored) { return 0L; }
    }

    private static String hashFile(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (java.security.GeneralSecurityException ex) {
            throw new IOException("SHA-256 unavailable.", ex);
        }
    }

    private static String payloadHash(Map<String, String> payload) {
        StringBuilder normalized = new StringBuilder();
        if (payload != null) payload.entrySet().stream()
                .filter(entry -> !Set.of("csrf", "reason").contains(entry.getKey().toLowerCase(Locale.ROOT)))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> normalized.append(entry.getKey()).append('=').append(entry.getValue()).append('&'));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(normalized.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) { return "unavailable"; }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value));
        return out.toString();
    }

    private static boolean wildcardMatches(String wildcard, String value) {
        StringBuilder regex = new StringBuilder("^");
        for (char c : wildcard.toCharArray()) {
            if (c == '*') regex.append(".*");
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return value.matches(regex.append('$').toString());
    }

    private static boolean permissionMatches(String grant, String target) {
        String value = grant == null ? "" : grant.trim().toLowerCase(Locale.ROOT);
        return value.equals("*") || value.equals(target)
                || (value.endsWith(".*") && target.startsWith(value.substring(0, value.length() - 1)));
    }

    private static boolean hourInsideWindow(int hour, int start, int end) {
        if (start == end) return true;
        return start < end ? hour >= start && hour < end : hour >= start || hour < end;
    }

    private String inferType(String value) {
        String normalized = value == null ? "" : value.trim().replace("\"", "").replace("'", "");
        if (normalized.equalsIgnoreCase("true") || normalized.equalsIgnoreCase("false")) return "boolean";
        if (normalized.matches("-?\\d+")) return "integer";
        if (normalized.matches("-?\\d+\\.\\d+")) return "decimal";
        if (normalized.matches("#[0-9a-fA-F]{6,8}")) return "color";
        if (normalized.matches("\\d{1,5}")) return "number";
        return "text";
    }

    private String inferConstraint(String key, String value) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.contains("port")) return "1-65535";
        if (lower.contains("distance")) return "positive server distance";
        if (lower.contains("percent") || lower.contains("ratio")) return "bounded numeric value";
        if ("boolean".equals(inferType(value))) return "true or false";
        return "preserve the documented plugin value format";
    }

    private static double averageTps(List<PerformanceSample> rows) {
        return rows.stream().mapToDouble(PerformanceSample::tps).average().orElse(0.0d);
    }

    private static double averageMspt(List<PerformanceSample> rows) {
        return rows.stream().mapToDouble(PerformanceSample::mspt).average().orElse(0.0d);
    }

    private static long averageMemory(List<PerformanceSample> rows) {
        return (long) rows.stream().mapToLong(PerformanceSample::memoryMb).average().orElse(0.0d);
    }

    private int statusWeight(String status) {
        return switch (status) {
            case "major-outage" -> 5;
            case "partial-outage" -> 4;
            case "degraded" -> 3;
            case "maintenance" -> 2;
            default -> 1;
        };
    }

    private static Path requirePath(Path path, String label) {
        if (path == null) throw new IllegalArgumentException(label + " is required.");
        return path.toAbsolutePath().normalize();
    }

    private static String clean(String value, int max, String fallback) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) return fallback == null ? "" : fallback;
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private static String normalizeChoice(String value, Set<String> allowed, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
    }

    private static int bounded(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static String safeArtifactName(String value) {
        String name = value == null ? "" : value.trim();
        return SAFE_ARTIFACT.matcher(name).matches() && !name.contains("..") ? name : "";
    }

    private static String safeArchiveName(String value) {
        String name = value == null ? "" : value.trim();
        if (!name.matches("[A-Za-z0-9._+ -]{1,180}\\.zip") || name.contains("..")) return "";
        return name;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int unit = -1;
        do { value /= 1024.0d; unit++; } while (value >= 1024.0d && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String formatDuration(long millis) {
        long minutes = Math.max(0L, TimeUnit.MILLISECONDS.toMinutes(millis));
        return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    // Public immutable views

    private record FileFingerprint(String sha256, long size, long modifiedAt) { }
    private record SnapshotRow(String id, String archive, String manifest) { }
    private record ParsedScalar(String key, String value, int lineIndex) { }
    private record ArtifactDescriptor(String id, String loader, List<String> dependencies,
            List<String> optionalDependencies) { }

    public record ShadowLab(String id, String status, String summary, String log, String actor,
            long createdAt, long finishedAt) { }
    public record RootCauseReport(String severity, String summary, List<String> evidence,
            List<String> recommendations) { }
    public record StateSnapshot(String id, String label, String archive, int files, long bytes,
            String actor, long createdAt) { }
    public record StateDiff(boolean found, int changed, int added, int missing, List<String> details) { }
    public record PerformanceSample(long timestamp, double tps, double mspt, long memoryMb, int players,
            String label) { }
    public record PerformanceRegression(String status, double tpsDelta, double msptDelta, long memoryDeltaMb,
            int samples, String message) { }
    public record ResourceAttribution(String artifact, String folder, int score, String confidence, long bytes,
            int classes, int logSignals, List<String> evidence) { }
    public record DependencyNode(String id, String artifact, String loader, int dependencyCount) { }
    public record DependencyEdge(String source, String target, String kind) { }
    public record DependencyGraph(List<DependencyNode> nodes, List<DependencyEdge> edges,
            List<String> missingRequired) { }
    public record SafeModeItem(String id, String artifact, String originalPath, String quarantinePath,
            String actor, long createdAt, long restoredAt) { }
    public record BackupEntry(String path, long bytes, long modifiedAt, String liveState) { }
    public record BackupView(String backup, long bytes, int totalEntries, List<BackupEntry> entries,
            String message) { }
    public record MaintenanceWindow(String day, int hour, int observedJoins, double confidence, String message) { }
    public record Guardrail(String id, String actionPattern, int maxPlayers, boolean requireBackup,
            int quietStart, int quietEnd, boolean requireReason, boolean dualControl, boolean enabled,
            long createdAt) { }
    public record GuardDecision(boolean allowed, String message, String approvalId) {
        public static GuardDecision allow() { return new GuardDecision(true, "Allowed", ""); }
    }
    public record PlayerIdentity(String uuid, String name, long firstJoin, long lastJoin, long totalPlaytime,
            int sessions, String source) {
        public static PlayerIdentity empty(String value) { return new PlayerIdentity("", value, 0L, 0L, 0L, 0, ""); }
    }
    public record JourneyEvent(long timestamp, String category, String title, String detail) { }
    public record PlayerJourney(PlayerIdentity identity, List<JourneyEvent> events, String message) { }
    public record ExperienceScore(int score, String status, int sessions, int abruptSessions,
            long averageSessionMillis, List<String> signals) { }
    public record SupportReply(String id, String author, String message, boolean publicReply, long createdAt) { }
    public record SupportCase(String id, String type, String player, String subject, String message, String status,
            String owner, long createdAt, long updatedAt, List<SupportReply> replies) { }
    public record TemporaryGrant(String id, String username, String permission, String grantedBy,
            long expiresAt, long revokedAt, long createdAt) { }
    public record Approval(String id, String action, String payloadHash, String requestedBy, String status,
            String approvedBy, long expiresAt, long createdAt, long usedAt) { }
    public record SupplyArtifact(String fileName, String identity, String loader, String sha256, long bytes,
            int entries, int signedEntries, int nativeEntries, int scriptEntries, String trust,
            List<String> warnings) { }
    public record RetentionPolicy(int logDays, int backupDays, int crashDays, int keepMinBackups,
            String updatedBy, long updatedAt) { }
    public record RetentionPreview(int files, long bytes, List<String> sample) { }
    public record ConfigField(String key, String value, String type, String constraint, boolean editable) { }
    public record ConfigDocument(String path, String format, boolean editable, List<ConfigField> fields,
            List<String> warnings) { }
    public record SearchHit(String type, String title, String reference, String server, int score) { }
    public record WorldFinding(String path, String severity, String message) { }
    public record WorldHealth(String status, int worlds, int regionFiles, int invalidRegions, long bytes,
            List<WorldFinding> findings) { }
    public record StorageBucket(String name, String category, long bytes, boolean cleanupCandidate) { }
    public record StorageReport(long managedBytes, long usableBytes, long reclaimableBytes,
            List<StorageBucket> buckets) { }
    public record ServiceLevel(int samples, double availabilityPercent, double averageTps, double averageMspt,
            double averageBackupAgeHours, String status) { }
    public record WarRoomUpdate(String id, String author, String message, String kind, long createdAt) { }
    public record WarRoom(String id, String title, String severity, String status, String commander, String summary,
            String publicMessage, long createdAt, long closedAt, List<WarRoomUpdate> updates) { }
    public record StatusComponent(String component, String status, String message, long updatedAt) { }
    public record PublicIncident(String title, String severity, String status, String message,
            long startedAt, long resolvedAt) { }
    public record PublicStatus(String overall, List<StatusComponent> components,
            List<PublicIncident> incidents, long generatedAt) { }
}
