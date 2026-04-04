package dash.web;

import org.bukkit.Bukkit;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;

public final class ServerSettingsPage {

    public enum ServerType {
        BUKKIT,
        SPIGOT,
        PAPER,
        PURPUR
    }

    private ServerSettingsPage() {
    }

    public static ServerType detectServerType() {
        String name = Bukkit.getName() == null ? "" : Bukkit.getName().toLowerCase(Locale.ROOT);
        String version = Bukkit.getVersion() == null ? "" : Bukkit.getVersion().toLowerCase(Locale.ROOT);
        String combined = name + " " + version;

        if (combined.contains("purpur")) {
            return ServerType.PURPUR;
        }
        if (combined.contains("paper")) {
            return ServerType.PAPER;
        }
        if (combined.contains("spigot")) {
            return ServerType.SPIGOT;
        }
        return ServerType.BUKKIT;
    }

    public static boolean supportsPaperExtras(ServerType type) {
        return type == ServerType.PAPER || type == ServerType.PURPUR;
    }

    public static boolean supportsSparkUi(ServerType type) {
        return supportsPaperExtras(type);
    }

    public static String renderConfigOverview(ServerType type) {
        List<String> files = getConfigFilesForType(type);

        StringBuilder rows = new StringBuilder();
        for (String fileName : files) {
            FileSummary summary = readSummary(fileName);
            rows.append("<div class=\"flex items-center justify-between p-2 rounded bg-white/5\">")
                    .append("<div><p class=\"text-sm text-white font-mono\">").append(escapeHtml(fileName)).append("</p>")
                    .append("<p class=\"text-xs text-slate-500\">").append(escapeHtml(summary.details())).append("</p></div>")
                    .append("<span class=\"text-xs ").append(summary.ok() ? "text-emerald-400\"" : "text-slate-500\"")
                    .append(">")
                    .append(summary.ok() ? "Loaded" : "Not found")
                    .append("</span></div>");
        }

        return "<div class=\"rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden\">"
                + "<div class=\"flex items-center gap-3 px-4 py-3 border-b border-white/5\">"
                + "<span class=\"material-symbols-outlined text-primary\">dns</span>"
                + "<h2 class=\"text-sm font-bold text-white uppercase tracking-wider\">Server Config Files (" + type.name() + ")</h2>"
                + "</div>"
                + "<div class=\"p-3 flex flex-col gap-2\">"
                + rows
                + "</div></div>";
    }

    public static List<String> getConfigFilesForType(ServerType type) {
        List<String> files = new ArrayList<>();
        files.add("server.properties");
        files.add("bukkit.yml");
        files.add("spigot.yml");

        if (supportsPaperExtras(type)) {
            files.add("paper-global.yml");
            files.add("paper.yml");
        }
        if (type == ServerType.PURPUR) {
            files.add("purpur.yml");
        }
        return files;
    }

    public static Properties loadServerPropertiesSafe() {
        File file = new File(Bukkit.getWorldContainer(), "server.properties");
        Properties props = new Properties();
        if (!file.exists() || !file.isFile()) {
            return props;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        } catch (Exception ignored) {
            // Fail soft so the settings page can still render on any server distribution.
        }
        return props;
    }

    public static String readServerPropertySafe(String key, String defaultValue) {
        if (key == null || key.isBlank()) {
            return defaultValue;
        }
        try {
            String value = loadServerPropertiesSafe().getProperty(key);
            return value == null ? defaultValue : value;
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static FileSummary readSummary(String fileName) {
        try {
            File file = new File(Bukkit.getWorldContainer(), fileName);
            if (!file.exists() || !file.isFile()) {
                return new FileSummary(false, "File missing");
            }
            long size = Files.size(file.toPath());
            long lines = 0L;
            try (Stream<String> stream = Files.lines(file.toPath())) {
                lines = stream.count();
            } catch (Exception ignored) {
                // If line counting fails, keep size-only summary.
            }
            if (lines > 0) {
                return new FileSummary(true, size + " bytes, " + lines + " lines");
            }
            return new FileSummary(true, size + " bytes");
        } catch (Exception ex) {
            return new FileSummary(false, "Read failed: " + ex.getClass().getSimpleName());
        }
    }

    private record FileSummary(boolean ok, String details) {
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

