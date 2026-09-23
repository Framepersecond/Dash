package dash.web;

import dash.Dash;
import dash.StatsCollector;
import dash.data.BackupManager;
import dash.data.PlayerDataManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

public final class MaintenancePage {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final long FRESH_CRASH_WINDOW_MS = 14L * 24L * 60L * 60L * 1000L;

    private MaintenancePage() {
    }

    public static String render(String message) {
        Path root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        BackupManager backupManager = Dash.getBackupManager();
        List<BackupManager.BackupInfo> backups = backupManager == null ? List.of() : backupManager.listBackups();
        List<StatsCollector.StatsSample> stats = Dash.getStatsCollector() == null
                ? List.of()
                : Dash.getStatsCollector().getHistory();
        StatsCollector.StatsSample latest = Dash.getStatsCollector() == null
                ? null
                : Dash.getStatsCollector().getLatest();
        List<PlayerDataManager.PlayerInfo> players = Dash.getPlayerDataManager() == null
                ? List.of()
                : Dash.getPlayerDataManager().getAllPlayers(500, 0);
        CrashReport crash = latestCrash(root);
        String pluginLabel = "Plugins";

        String banner = message == null || message.isBlank()
                ? ""
                : "<div class='rounded-xl border border-cyan-500/30 bg-cyan-500/10 px-4 py-3 text-sm text-cyan-100'>"
                        + escape(message) + "</div>";

        String content = HtmlTemplate.statsHeader()
                + styles()
                + "<main class='maintenance-main flex-1 min-w-0 p-4 sm:p-6'>"
                + "<div class='max-w-7xl mx-auto space-y-5'>"
                + header(pluginLabel, root)
                + banner
                + "<section class='grid grid-cols-1 xl:grid-cols-[1.05fr_.95fr] gap-4'>"
                + smartAlerts(root, backups, latest, crash)
                + pluginManager(pluginLabel)
                + "</section>"
                + "<section class='grid grid-cols-1 lg:grid-cols-2 xl:grid-cols-3 gap-4'>"
                + backupsPanel(backups, backupManager)
                + crashPanel(crash)
                + playerPanel(players)
                + performancePanel(stats, latest)
                + localToolsPanel(pluginLabel)
                + notificationPanel()
                + "</section>"
                + "</div>"
                + "</main>"
                + script();
        return HtmlTemplate.page("Maintenance", "/maintenance", content);
    }

    private static String header(String pluginLabel, Path root) {
        return "<section class='rounded-2xl bg-glass-surface border border-glass-border p-5 shadow-xl dash-maintenance-hero'>"
                + "<div class='flex flex-col lg:flex-row lg:items-center lg:justify-between gap-4'>"
                + "<div>"
                + "<div class='inline-flex items-center gap-2 rounded-full border border-primary/25 bg-primary/10 px-3 py-1 text-xs font-bold text-primary mb-3'>"
                + "<span class='material-symbols-outlined text-[16px]'>stethoscope</span>Local Diagnostics</div>"
                + "<h1 class='text-2xl sm:text-3xl font-bold text-white'>Local Maintenance</h1>"
                + "<p class='text-sm text-slate-400 mt-1'>Backups, " + pluginLabel.toLowerCase(Locale.ROOT)
                + ", crashes, players, profiler, and alerts for this running Bukkit/Paper server.</p>"
                + "</div>"
                + "<div class='rounded-xl bg-slate-950/35 border border-slate-800 px-4 py-3 min-w-0'>"
                + "<p class='text-[11px] uppercase tracking-wider text-slate-500'>Server Root</p>"
                + "<p class='text-xs text-slate-300 font-mono break-all mt-1'>" + escape(root.toString()) + "</p>"
                + "</div>"
                + "</div>"
                + "</section>";
    }

    private static String smartAlerts(Path root,
            List<BackupManager.BackupInfo> backups,
            StatsCollector.StatsSample latest,
            CrashReport crash) {
        List<String> alerts = new ArrayList<>();
        if (backups.isEmpty()) {
            alerts.add("No verified backup exists yet.");
        }
        if (crash != null) {
            alerts.add("Latest crash report: " + crash.fileName());
        }
        if (latest != null && latest.tps > 0.0d && latest.tps < 18.0d) {
            alerts.add("TPS is below target: " + formatOne(latest.tps));
        }
        if (latest != null && latest.ramMax > 0 && latest.ramUsed * 100.0d / latest.ramMax >= 85.0d) {
            alerts.add("Memory pressure is above 85%.");
        }
        if (!Files.isDirectory(root.resolve("plugins"))) {
            alerts.add("Plugins folder is missing.");
        }
        String rows;
        if (alerts.isEmpty()) {
            rows = alertRow("No active local alerts", "Current backups, crashes, TPS, RAM, and plugin folder checks look calm.", "check_circle", "emerald");
        } else {
            StringBuilder out = new StringBuilder();
            for (String alert : alerts) {
                out.append(alertRow("Maintenance warning", alert, "warning", "amber"));
            }
            rows = out.toString();
        }
        return panel("Smart Alerts", "notifications_active",
                "<div class='space-y-2'>" + rows + "</div>");
    }

    private static String pluginManager(String pluginLabel) {
        boolean canManage = HtmlTemplate.can("dash.web.plugins.manage");
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        int enabled = 0;
        for (Plugin plugin : plugins) {
            if (plugin.isEnabled()) {
                enabled++;
            }
        }
        StringBuilder rows = new StringBuilder();
        int shown = 0;
        for (Plugin plugin : plugins) {
            if (shown >= 8) {
                break;
            }
            rows.append("<div class='flex items-center justify-between gap-3 rounded-xl bg-slate-950/35 border border-slate-800 px-3 py-2'>")
                    .append("<div class='min-w-0'><p class='text-sm font-semibold text-slate-100 truncate'>")
                    .append(escape(plugin.getName()))
                    .append("</p><p class='text-[11px] text-slate-500'>v")
                    .append(escape(plugin.getDescription().getVersion()))
                    .append("</p></div>")
                    .append("<span class='px-2 py-1 rounded-full text-[11px] font-bold ")
                    .append(plugin.isEnabled() ? "bg-emerald-500/15 text-emerald-300" : "bg-rose-500/15 text-rose-300")
                    .append("'>")
                    .append(plugin.isEnabled() ? "Enabled" : "Disabled")
                    .append("</span></div>");
            shown++;
        }
        if (rows.isEmpty()) {
            rows.append(emptyRow("No plugins detected."));
        }
        String upload = canManage
                ? "<label class='cursor-pointer inline-flex w-full justify-center items-center gap-2 rounded-xl bg-primary px-4 py-2.5 text-sm font-bold text-black hover:brightness-110'>"
                        + "<input type='file' id='maintenance-plugin-upload' accept='.jar' class='hidden'>"
                        + "<span class='material-symbols-outlined text-[18px]'>upload_file</span>Upload Plugin Jar</label>"
                : "<div class='rounded-xl border border-slate-800 bg-slate-950/35 px-3 py-3 text-xs text-slate-500 text-center'>Plugin management permission required.</div>";
        return panel(pluginLabel + " Manager", "extension",
                "<div class='grid grid-cols-2 gap-3 mb-4'>"
                        + metric("Installed", String.valueOf(plugins.length))
                        + metric("Enabled", enabled + "/" + plugins.length)
                        + metric("Platform", "Bukkit/Paper")
                        + metric("Updates", "Local scan")
                        + "</div>"
                        + upload
                        + "<div class='mt-3 rounded-xl border border-slate-800 bg-slate-950/25 p-3'>"
                        + "<p class='text-xs uppercase tracking-wider text-slate-500 mb-2'>Installed " + pluginLabel + "</p>"
                        + "<div class='space-y-2'>" + rows + "</div>"
                        + "</div>");
    }

    private static String backupsPanel(List<BackupManager.BackupInfo> backups, BackupManager manager) {
        boolean canCreate = HtmlTemplate.can("dash.web.backups.create");
        boolean canDelete = HtmlTemplate.can("dash.web.backups.delete");
        boolean canRead = HtmlTemplate.can("dash.web.backups.read");
        boolean canSchedule = HtmlTemplate.can("dash.web.backups.schedule");
        StringBuilder rows = new StringBuilder();
        int shown = 0;
        for (BackupManager.BackupInfo backup : backups) {
            if (shown >= 6) {
                break;
            }
            rows.append("<div class='rounded-xl bg-slate-950/35 border border-slate-800 p-3'>")
                    .append("<div class='flex items-start justify-between gap-3'>")
                    .append("<div class='min-w-0'><p class='text-xs font-mono text-slate-200 truncate'>")
                    .append(escape(backup.name()))
                    .append("</p><p class='text-[11px] text-slate-500'>")
                    .append(escape(backup.getFormattedDate()))
                    .append(" - ")
                    .append(escape(backup.getFormattedSize()))
                    .append("</p></div><div class='flex gap-2'>")
                    .append(canRead ? "<a class='px-2 py-1 rounded-lg bg-primary/20 text-primary text-xs font-bold' href='/api/backups/download?name="
                            + escape(backup.name()) + "'>Download</a>" : "")
                    .append(canDelete ? "<form method='post' action='/action'><input type='hidden' name='action' value='backup_delete'><input type='hidden' name='name' value='"
                            + escape(backup.name()) + "'><input type='hidden' name='return_to' value='/maintenance?msg=Backup%20deleted.'><button class='px-2 py-1 rounded-lg bg-rose-500/20 text-rose-300 text-xs font-bold'>Delete</button></form>" : "")
                    .append("</div></div></div>");
            shown++;
        }
        if (rows.isEmpty()) {
            rows.append(emptyRow("No backups yet."));
        }
        int schedule = manager == null ? 0 : manager.getScheduleHours();
        return panel("Advanced Backups", "cloud_upload",
                "<div class='grid grid-cols-2 gap-3 mb-4'>"
                        + metric("Restore Points", String.valueOf(backups.size()))
                        + metric("Schedule", schedule <= 0 ? "Off" : schedule + "h")
                        + metric("Verification", "Zip checked")
                        + metric("Rollback", "Offline safe")
                        + "</div>"
                        + "<form method='post' action='/action' class='space-y-3'>"
                        + "<input type='hidden' name='action' value='backup_create'>"
                        + "<input type='hidden' name='return_to' value='/maintenance?msg=Backup%20creation%20queued.'>"
                        + "<button " + (canCreate ? "" : "disabled")
                        + " class='inline-flex w-full justify-center items-center gap-2 rounded-xl bg-emerald-500 px-4 py-2.5 text-sm font-bold text-black "
                        + (canCreate ? "hover:bg-emerald-400" : "opacity-50 cursor-not-allowed") + "'>"
                        + "<span class='material-symbols-outlined text-[18px]'>verified</span>Create Restore Point</button></form>"
                        + "<form method='post' action='/action' class='grid grid-cols-[1fr_auto] gap-2 mt-3'>"
                        + "<input type='hidden' name='action' value='backup_schedule'>"
                        + "<input type='hidden' name='return_to' value='/maintenance?msg=Backup%20schedule%20updated.'>"
                        + "<select name='hours' " + (canSchedule ? "" : "disabled") + ">"
                        + scheduleOption(0, schedule, "Manual only")
                        + scheduleOption(1, schedule, "Every hour")
                        + scheduleOption(6, schedule, "Every 6 hours")
                        + scheduleOption(12, schedule, "Every 12 hours")
                        + scheduleOption(24, schedule, "Daily")
                        + "</select><button " + (canSchedule ? "" : "disabled")
                        + " class='rounded-xl border border-primary/30 bg-primary/10 px-3 text-xs font-bold text-primary'>Save</button></form>"
                        + "<div class='mt-4 space-y-2'>" + rows + "</div>");
    }

    private static String crashPanel(CrashReport crash) {
        if (crash == null) {
            return panel("Crash Diagnostics", "medical_services",
                    alertRow("No crash reports detected", "Local diagnostics are watching crash-reports and latest.log.", "check_circle", "emerald"));
        }
        return panel("Crash Diagnostics", "medical_services",
                "<div class='space-y-3'>"
                        + metric("Latest", crash.fileName())
                        + metric("Detected", crash.modified())
                        + metric("Likely Cause", crash.cause())
                        + "<div class='rounded-xl border border-slate-800 bg-slate-950/35 p-3'>"
                        + "<p class='text-xs uppercase tracking-wider text-slate-500 mb-1'>Suggested Fix</p>"
                        + "<p class='text-sm text-slate-300'>" + escape(crash.fix()) + "</p></div>"
                        + "</div>");
    }

    private static String playerPanel(List<PlayerDataManager.PlayerInfo> players) {
        long now = System.currentTimeMillis();
        long week = now - 7L * 24L * 60L * 60L * 1000L;
        int newPlayers = 0;
        long totalPlaytime = 0L;
        List<PlayerDataManager.PlayerInfo> sorted = new ArrayList<>(players);
        for (PlayerDataManager.PlayerInfo player : sorted) {
            if (player.firstJoin() >= week) {
                newPlayers++;
            }
            totalPlaytime += Math.max(0L, player.totalPlaytime());
        }
        sorted.sort(Comparator.comparingLong(PlayerDataManager.PlayerInfo::totalPlaytime).reversed());
        long avgPlaytime = players.isEmpty() ? 0L : totalPlaytime / players.size();
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < Math.min(5, sorted.size()); i++) {
            PlayerDataManager.PlayerInfo player = sorted.get(i);
            rows.append("<div class='flex items-center justify-between gap-3 rounded-lg bg-slate-950/35 border border-slate-800 px-3 py-2'>")
                    .append("<span class='text-sm font-semibold text-slate-200 truncate'>")
                    .append(escape(player.name()))
                    .append("</span><span class='text-xs text-cyan-300 whitespace-nowrap'>")
                    .append(escape(player.getFormattedPlaytime()))
                    .append("</span></div>");
        }
        if (rows.isEmpty()) {
            rows.append(emptyRow("Player history starts filling after players join."));
        }
        return panel("Player Intelligence", "monitoring",
                "<div class='grid grid-cols-2 gap-3 mb-4'>"
                        + metric("Known Players", String.valueOf(players.size()))
                        + metric("New 7d", String.valueOf(newPlayers))
                        + metric("Returning", String.valueOf(Math.max(0, players.size() - newPlayers)))
                        + metric("Avg Playtime", duration(avgPlaytime))
                        + "</div>"
                        + "<div class='dash-mini-chart mb-4'>"
                        + bar("Growth", percent(newPlayers, Math.max(1, players.size())))
                        + bar("Retention", percent(Math.max(0, players.size() - newPlayers), Math.max(1, players.size())))
                        + "</div>"
                        + "<div class='space-y-2'>" + rows + "</div>");
    }

    private static String performancePanel(List<StatsCollector.StatsSample> stats, StatsCollector.StatsSample latest) {
        double avgTps = average(stats.stream().map(sample -> sample.tps).toList());
        double minTps = stats.stream().mapToDouble(sample -> sample.tps).filter(value -> value > 0.0d).min().orElse(0.0d);
        double maxMspt = stats.stream().mapToDouble(sample -> sample.mspt).max().orElse(0.0d);
        double ramPct = latest != null && latest.ramMax > 0 ? latest.ramUsed * 100.0d / latest.ramMax : 0.0d;
        int chunks = latest == null ? 0 : latest.overworldChunks + latest.netherChunks + latest.endChunks;
        return panel("Performance Profiler", "speed",
                "<div class='grid grid-cols-2 gap-3 mb-4'>"
                        + metric("Avg TPS", stats.isEmpty() ? "Learning" : formatOne(avgTps))
                        + metric("Min TPS", minTps > 0.0d ? formatOne(minTps) : "Learning")
                        + metric("Peak MSPT", maxMspt > 0.0d ? formatOne(maxMspt) : "Learning")
                        + metric("Loaded Chunks", String.valueOf(chunks))
                        + "</div>"
                        + "<div class='dash-mini-chart'>"
                        + bar("TPS", (int) Math.round(Math.min(100.0d, (avgTps <= 0.0d ? 20.0d : avgTps) * 5.0d)))
                        + bar("CPU", latest == null ? 0 : (int) Math.round(latest.cpuUsage))
                        + bar("RAM", (int) Math.round(ramPct))
                        + "</div>"
                        + profilerWarning(latest, minTps, maxMspt, ramPct));
    }

    private static String localToolsPanel(String pluginLabel) {
        boolean spark = isSparkAvailable();
        return panel("Local Tools", "construction",
                "<div class='grid grid-cols-2 gap-3 mb-4'>"
                        + metric("Spark", spark ? "Installed" : "Missing")
                        + metric(pluginLabel, "Local")
                        + metric("Server Type", ServerSettingsPage.detectServerType().name())
                        + metric("Safe Updates", "Backup first")
                        + "</div>"
                        + "<form method='post' action='/action'>"
                        + "<input type='hidden' name='action' value='spark_profile'>"
                        + "<input type='hidden' name='return_to' value='/maintenance?msg=Spark%20profiling%20request%20submitted.'>"
                        + "<button " + (spark && HtmlTemplate.can("dash.web.tools.spark") ? "" : "disabled")
                        + " class='inline-flex w-full justify-center items-center gap-2 rounded-xl border border-cyan-500/30 bg-cyan-500/10 px-4 py-2.5 text-sm font-bold text-cyan-200 "
                        + (spark && HtmlTemplate.can("dash.web.tools.spark") ? "hover:bg-cyan-500 hover:text-black" : "opacity-50 cursor-not-allowed")
                        + "'><span class='material-symbols-outlined text-[18px]'>query_stats</span>Start Spark Profile</button></form>");
    }

    private static boolean isSparkAvailable() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("spark");
        if (plugin != null && plugin.isEnabled()) {
            return true;
        }
        try {
            Class.forName("me.lucko.spark.api.Spark");
            return true;
        } catch (Throwable ignored) {
        }
        try {
            Class.forName("me.lucko.spark.common.SparkPlatform");
            return true;
        } catch (Throwable ignored) {
        }
        Path latestLog = Bukkit.getWorldContainer().toPath().resolve("logs").resolve("latest.log");
        for (String line : tail(latestLog, 120)) {
            String lower = line == null ? "" : line.toLowerCase(Locale.ROOT);
            if (lower.contains("bundles the spark profiler") || lower.contains("[spark]")) {
                return true;
            }
        }
        return false;
    }

    private static String notificationPanel() {
        int discordTargets = Dash.getDiscordWebhookManager() == null
                ? 0 : Dash.getDiscordWebhookManager().getWebhooks().size();
        return panel("Notification Targets", "campaign",
                "<div class='grid grid-cols-2 gap-3'>"
                        + metric("Discord", discordTargets + " configured")
                        + metric("Panel", "Smart alerts")
                        + metric("Audit", "Active")
                        + metric("Console", "Rate limited")
                        + "</div>"
                        + "<a href='/notifications' class='mt-4 inline-flex w-full justify-center items-center gap-2 rounded-xl border border-slate-700 bg-slate-950/40 px-4 py-2.5 text-sm font-bold text-slate-300 hover:text-primary hover:border-primary'>"
                        + "<span class='material-symbols-outlined text-[18px]'>tune</span>Open Notification Settings</a>");
    }

    private static CrashReport latestCrash(Path root) {
        Path crashDir = root.resolve("crash-reports");
        if (!Files.isDirectory(crashDir)) {
            return null;
        }
        Optional<Path> latest;
        try (Stream<Path> stream = Files.list(crashDir)) {
            latest = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .max(Comparator.comparingLong(MaintenancePage::modifiedMillis));
        } catch (IOException ignored) {
            latest = Optional.empty();
        }
        if (latest.isEmpty()) {
            return null;
        }
        if (System.currentTimeMillis() - modifiedMillis(latest.get()) > FRESH_CRASH_WINDOW_MS) {
            return null;
        }
        List<String> lines = firstLines(latest.get(), 240);
        String cause = "Unknown crash";
        String fix = "Review the crash report, then remove the last changed plugin or dependency and restart.";
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.startsWith("Description:")) {
                cause = trimmed.substring("Description:".length()).trim();
            }
            if (trimmed.startsWith("Caused by:")) {
                cause = trimmed.substring("Caused by:".length()).trim();
                break;
            }
        }
        String lower = String.join("\n", lines).toLowerCase(Locale.ROOT);
        if (lower.contains("unsupportedclassversionerror") || lower.contains("classversionerror")) {
            fix = "Run the Java version required by this server/plugin version.";
        } else if (lower.contains("nosuchmethoderror") || lower.contains("noclassdeffounderror")) {
            fix = "Update the named dependency or install the missing plugin library.";
        } else if (lower.contains("outofmemoryerror")) {
            fix = "Increase memory or inspect plugins/world activity for memory pressure.";
        }
        return new CrashReport(latest.get().getFileName().toString(), modified(latest.get()), cause, fix);
    }

    private static List<String> firstLines(Path path, int maxLines) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null && lines.size() < maxLines) {
                lines.add(line);
            }
        } catch (IOException ignored) {
        }
        return lines;
    }

    private static List<String> tail(Path path, int maxLines) {
        if (path == null || !Files.isRegularFile(path)) {
            return List.of();
        }
        int safeLimit = Math.max(1, maxLines);
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (lines.size() > safeLimit) {
                    lines.remove(0);
                }
            }
        } catch (IOException ignored) {
        }
        return lines;
    }

    private static String panel(String title, String icon, String body) {
        return "<article class='rounded-2xl bg-glass-surface border border-glass-border p-5 shadow-xl dash-panel'>"
                + "<div class='flex items-center justify-between gap-3 mb-4'>"
                + "<h2 class='text-lg font-bold text-white'>" + escape(title) + "</h2>"
                + "<span class='material-symbols-outlined text-primary'>" + escape(icon) + "</span>"
                + "</div>" + body + "</article>";
    }

    private static String metric(String label, String value) {
        return "<div class='rounded-xl bg-slate-950/35 border border-slate-800 p-3 min-w-0'>"
                + "<p class='text-[11px] uppercase tracking-wider text-slate-500'>" + escape(label) + "</p>"
                + "<p class='text-sm font-semibold text-slate-100 mt-1 truncate'>" + escape(value) + "</p>"
                + "</div>";
    }

    private static String alertRow(String title, String details, String icon, String color) {
        String cls = "emerald".equals(color)
                ? "border-emerald-500/25 bg-emerald-500/10 text-emerald-300"
                : "border-amber-500/25 bg-amber-500/10 text-amber-300";
        return "<div class='rounded-xl border " + cls + " p-3'>"
                + "<div class='flex items-center justify-between gap-3'>"
                + "<span class='text-sm font-semibold text-slate-100'>" + escape(title) + "</span>"
                + "<span class='material-symbols-outlined text-[18px]'>" + escape(icon) + "</span>"
                + "</div><p class='text-xs text-slate-400 mt-1'>" + escape(details) + "</p></div>";
    }

    private static String profilerWarning(StatsCollector.StatsSample latest, double minTps, double maxMspt, double ramPct) {
        if ((minTps > 0.0d && minTps < 18.0d) || maxMspt > 55.0d || ramPct >= 85.0d) {
            return "<div class='rounded-xl border border-amber-500/25 bg-amber-500/10 p-3 mt-4'>"
                    + "<p class='text-sm font-semibold text-slate-100'>Profiler warning</p>"
                    + "<p class='text-xs text-slate-400 mt-1'>Lag or memory pressure was detected in the retained samples.</p></div>";
        }
        return "<div class='rounded-xl border border-emerald-500/20 bg-emerald-500/10 p-3 mt-4'>"
                + "<p class='text-sm font-semibold text-slate-100'>No retained lag warnings</p>"
                + "<p class='text-xs text-slate-400 mt-1'>TPS, MSPT, CPU, and RAM history look calm so far.</p></div>";
    }

    private static String bar(String label, int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        return "<div class='space-y-1'><div class='flex items-center justify-between text-[11px] text-slate-500'>"
                + "<span>" + escape(label) + "</span><span>" + clamped + "%</span></div>"
                + "<div class='h-2 rounded-full bg-slate-950 overflow-hidden border border-slate-800'>"
                + "<div class='dash-bar h-full rounded-full bg-primary' style='--dash-bar:" + clamped + "%'></div>"
                + "</div></div>";
    }

    private static String emptyRow(String text) {
        return "<div class='rounded-lg border border-slate-800 bg-slate-950/35 px-3 py-4 text-center text-xs text-slate-500'>"
                + escape(text) + "</div>";
    }

    private static String scheduleOption(int value, int current, String label) {
        return "<option value='" + value + "'" + (value == current ? " selected" : "") + ">" + escape(label) + "</option>";
    }

    private static double average(List<Double> values) {
        double sum = 0.0d;
        int count = 0;
        for (Double value : values) {
            if (value != null && Double.isFinite(value) && value > 0.0d) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? 0.0d : sum / count;
    }

    private static int percent(int value, int total) {
        return total <= 0 ? 0 : Math.max(0, Math.min(100, (int) Math.round(value * 100.0d / total)));
    }

    private static String duration(long millis) {
        if (millis <= 0L) {
            return "Learning";
        }
        long minutes = Math.max(1L, millis / 60_000L);
        long hours = minutes / 60L;
        return hours <= 0L ? minutes + "m" : hours + "h " + (minutes % 60L) + "m";
    }

    private static long modifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static String modified(Path path) {
        long millis = modifiedMillis(path);
        if (millis <= 0L) {
            return "Unknown";
        }
        return DATE_TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
    }

    private static String formatOne(double value) {
        return new DecimalFormat("0.0").format(value);
    }

    private static String styles() {
        return "<style>"
                + "@keyframes dashPanelIn{from{opacity:0;transform:translate3d(0,10px,0) scale(.992)}to{opacity:1;transform:none}}"
                + ".dash-maintenance-hero,.dash-panel{animation:dashPanelIn .36s cubic-bezier(.16,1,.3,1) both;will-change:transform,opacity}"
                + ".dash-panel:nth-child(2n){animation-delay:35ms}.dash-panel:nth-child(3n){animation-delay:70ms}"
                + ".dash-mini-chart{display:grid;gap:.65rem}"
                + ".dash-bar{width:0;transition:width .7s cubic-bezier(.16,1,.3,1);box-shadow:0 0 18px rgba(34,211,238,.22)}"
                + "main[data-bars-ready='true'] .dash-bar{width:var(--dash-bar)}"
                + "</style>";
    }

    private static String script() {
        return HtmlTemplate.statsScript()
                + "<script>"
                + "if(window.dashPageAbortController){try{window.dashPageAbortController.abort();}catch(_){}}window.dashPageAbortController=new AbortController();var maintenanceSignal=window.dashPageAbortController.signal;"
                + "requestAnimationFrame(function(){var m=document.querySelector('main');if(m)m.setAttribute('data-bars-ready','true');});"
                + "var up=document.getElementById('maintenance-plugin-upload');"
                + "if(up){up.addEventListener('change',function(e){var f=e.target.files&&e.target.files[0];if(!f)return;if(!String(f.name).toLowerCase().endsWith('.jar')){showToast('Only .jar files allowed','error');up.value='';return;}up.disabled=true;var fd=new FormData();fd.append('file',f);fetch('/api/upload/plugin',{method:'POST',body:fd,credentials:'same-origin'}).then(function(r){return r.json();}).then(function(d){if(d.success){showToast('Plugin uploaded. Restart to load.','success');setTimeout(function(){if(window.dashNavigate){window.dashNavigate('/maintenance?msg=Plugin%20uploaded.%20Restart%20to%20load.','replace',undefined,true);}},450);}else{showToast('Error: '+(d.error||'Upload failed'),'error');}}).catch(function(){showToast('Plugin upload failed','error');}).finally(function(){up.disabled=false;up.value='';});},{signal:maintenanceSignal});}"
                + "</script>";
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private record CrashReport(String fileName, String modified, String cause, String fix) {
    }
}
