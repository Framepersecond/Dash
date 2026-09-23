package dash.web;

import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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

public final class DashDoctorPage {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final long FRESH_CRASH_WINDOW_MS = 14L * 24L * 60L * 60L * 1000L;

    private DashDoctorPage() {
    }

    public static String render(String message, boolean canManageCrashes) {
        Path root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Crash crash = latestCrash(root);
        List<String> logLines = tail(root.resolve("logs").resolve("latest.log"), 90);
        String banner = message == null || message.isBlank() ? ""
                : "<div class='rounded-xl border border-cyan-500/30 bg-cyan-500/10 px-4 py-3 text-sm text-cyan-100'>" + escape(message) + "</div>";

        String crashBody = crash == null
                ? "<div class='rounded-2xl border border-emerald-500/25 bg-emerald-500/10 p-6'><h2 class='text-xl font-bold text-white'>No fresh crash report</h2><p class='mt-2 text-sm text-slate-300'>Dash Doctor ignores archived reports older than 14 days so resolved crashes do not keep coming back.</p></div>"
                : "<div class='rounded-2xl border border-rose-500/30 bg-rose-500/10 p-5 space-y-4'>"
                        + "<div class='flex flex-col lg:flex-row lg:items-start lg:justify-between gap-4'><div><h2 class='text-xl font-bold text-white'>" + escape(crash.fileName()) + "</h2>"
                        + "<p class='text-sm text-slate-400 mt-1'>Detected " + escape(crash.modified()) + "</p></div>"
                        + crashActions(crash, canManageCrashes) + "</div>"
                        + metric("Likely Cause", crash.cause()) + metric("Suggested Fix", crash.fix())
                        + "<pre class='max-h-[520px] overflow-y-auto rounded-xl bg-black/70 p-4 text-xs leading-5 text-emerald-300 console-scrollbar'>"
                        + escape(String.join("\n", crash.lines())) + "</pre></div>";

        String content = HtmlTemplate.statsHeader()
                + "<main class='flex-1 min-w-0 p-4 sm:p-6'><div class='max-w-[1800px] mx-auto space-y-5'>"
                + "<section class='rounded-2xl bg-glass-surface border border-glass-border p-5'>"
                + "<div class='inline-flex items-center gap-2 rounded-full border border-primary/25 bg-primary/10 px-3 py-1 text-xs font-bold text-primary mb-3'>"
                + "<span class='material-symbols-outlined text-[16px]'>medical_services</span>Full Page Diagnostics</div>"
                + "<h1 class='text-2xl sm:text-3xl font-bold text-white'>Dash Doctor</h1>"
                + "<p class='text-sm text-slate-400 mt-1'>Crash reports, latest.log patterns, Java mismatch hints, dependency conflicts and repeated failure signals.</p></section>"
                + banner
                + "<section class='space-y-4'>"
                + crashBody
                + "<article class='rounded-2xl bg-glass-surface border border-glass-border p-5'>"
                + "<div class='flex items-center justify-between gap-3 mb-4'><h2 class='text-lg font-bold text-white'>latest.log Window</h2>"
                + "<span class='material-symbols-outlined text-primary'>article</span></div>"
                + logSummary(logLines)
                + diagnosticHints(logLines)
                + "<pre class='mt-4 max-h-[620px] overflow-auto rounded-xl bg-black/70 p-4 text-xs leading-5 text-emerald-300 console-scrollbar'>"
                + escape(String.join("\n", logLines)) + "</pre></article></section></div></main>" + HtmlTemplate.statsScript();
        return HtmlTemplate.page("Dash Doctor", "/doctor", content);
    }

    public static String resolveCrashAction(Path root, String rawFile, boolean delete) {
        try {
            if (rawFile == null || rawFile.isBlank()) {
                return "Crash report not found.";
            }
            String fileName = rawFile == null ? "" : rawFile.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (!fileName.equals(rawFile)) {
                return "Crash report not found.";
            }
            Path crashDir = root.resolve("crash-reports").toAbsolutePath().normalize();
            Path target = crashDir.resolve(fileName).normalize();
            if (!target.startsWith(crashDir) || Files.isSymbolicLink(target)
                    || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                return "Crash report not found.";
            }
            if (delete) {
                Files.deleteIfExists(target);
                return "Crash report deleted.";
            }
            Path reviewed = crashDir.resolve(fileName + ".reviewed").normalize();
            if (Files.exists(reviewed, LinkOption.NOFOLLOW_LINKS)) {
                reviewed = crashDir.resolve(fileName + "." + System.currentTimeMillis() + ".reviewed").normalize();
            }
            Files.move(target, reviewed);
            return "Crash report marked reviewed.";
        } catch (Exception ex) {
            return "Crash action failed safely.";
        }
    }

    private static Crash latestCrash(Path root) {
        Path crashDir = root.resolve("crash-reports");
        if (!Files.isDirectory(crashDir)) {
            return null;
        }
        Optional<Path> latest;
        try (Stream<Path> stream = Files.list(crashDir)) {
            latest = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .max(Comparator.comparingLong(DashDoctorPage::modifiedMillis));
        } catch (Exception ignored) {
            latest = Optional.empty();
        }
        if (latest.isEmpty() || System.currentTimeMillis() - modifiedMillis(latest.get()) > FRESH_CRASH_WINDOW_MS) {
            return null;
        }
        List<String> lines = firstLines(latest.get(), 260);
        String joined = String.join("\n", lines).toLowerCase(Locale.ROOT);
        String cause = lines.stream().map(String::trim)
                .filter(line -> line.startsWith("Caused by:") || line.startsWith("Description:"))
                .findFirst().orElse("Unknown crash");
        String fix = "Remove the last changed plugin, check dependencies, then restart from the configured start script.";
        if (joined.contains("unsupportedclassversionerror") || joined.contains("classversionerror")) {
            fix = "Use the Java version required by this Minecraft/plugin version.";
        } else if (joined.contains("noclassdeffounderror") || joined.contains("nosuchmethoderror")) {
            fix = "Install or update the missing dependency named in the stacktrace.";
        } else if (joined.contains("outofmemoryerror")) {
            fix = "Increase memory and inspect recent plugin/world activity for leaks.";
        }
        return new Crash(latest.get().getFileName().toString(), modified(latest.get()), cause, fix, lines);
    }

    private static String logSummary(List<String> lines) {
        int errors = 0;
        int warnings = 0;
        for (String line : lines) {
            String lower = line == null ? "" : line.toLowerCase(Locale.ROOT);
            if (lower.contains("error") || lower.contains("exception")) {
                errors++;
            }
            if (lower.contains("warn")) {
                warnings++;
            }
        }
        return "<div class='grid grid-cols-2 gap-3'>" + metric("Errors", String.valueOf(errors)) + metric("Warnings", String.valueOf(warnings)) + "</div>";
    }

    private static String diagnosticHints(List<String> lines) {
        String log = String.join("\n", lines).toLowerCase(Locale.ROOT);
        List<String[]> hints = new ArrayList<>();
        if (log.contains("unsupportedclassversionerror") || log.contains("classversionerror")) {
            hints.add(new String[]{"Java mismatch", "Run the Java version required by this Minecraft and extension build."});
        }
        if (log.contains("noclassdeffounderror") || log.contains("classnotfoundexception")
                || log.contains("nosuchmethoderror")) {
            hints.add(new String[]{"Dependency conflict", "Install or update the dependency named near the first exception."});
        }
        if (log.contains("outofmemoryerror") || log.contains("gc overhead limit exceeded")) {
            hints.add(new String[]{"Memory exhaustion", "Review heap allocation and recent extensions or world activity."});
        }
        if (log.contains("address already in use") || log.contains("bindexception")) {
            hints.add(new String[]{"Port conflict", "Another process is already using a configured server or dashboard port."});
        }
        if (log.contains("can't keep up") || log.contains("watchdog") || log.contains("server thread hang")) {
            hints.add(new String[]{"Server lag", "Capture a Spark profile and inspect the busiest server-thread tasks."});
        }
        if (hints.isEmpty()) {
            return "<div class='mt-3 rounded-xl border border-emerald-500/20 bg-emerald-500/10 p-3 text-sm text-emerald-200'>"
                    + "No known critical signature was found in the current log window.</div>";
        }
        StringBuilder rows = new StringBuilder("<div class='mt-3 grid grid-cols-1 lg:grid-cols-2 gap-2'>");
        for (String[] hint : hints) {
            rows.append("<div class='rounded-xl border border-amber-500/25 bg-amber-500/10 p-3'>")
                    .append("<p class='text-sm font-bold text-amber-200'>").append(escape(hint[0])).append("</p>")
                    .append("<p class='mt-1 text-xs text-slate-300'>").append(escape(hint[1])).append("</p></div>");
        }
        return rows.append("</div>").toString();
    }

    private static String doctorForm(String action, String file, String label, String icon) {
        String confirmation = "doctor_delete_crash".equals(action)
                ? " onsubmit=\"return confirm('Delete this crash report permanently?');\""
                : "";
        return "<form method='post' action='/action'" + confirmation + "><input type='hidden' name='action' value='" + action + "'>"
                + "<input type='hidden' name='file' value='" + escape(file) + "'>"
                + "<button class='inline-flex items-center gap-2 rounded-xl border border-slate-700 bg-slate-950/40 px-3 py-2 text-xs font-bold text-slate-200 hover:border-primary hover:text-primary'>"
                + "<span class='material-symbols-outlined text-[16px]'>" + icon + "</span>" + escape(label) + "</button></form>";
    }

    private static String crashActions(Crash crash, boolean canManageCrashes) {
        if (!canManageCrashes) {
            return "<span class='rounded-lg border border-slate-700 px-3 py-2 text-xs text-slate-400'>Read-only access</span>";
        }
        return "<div class='flex gap-2'>"
                + doctorForm("doctor_mark_reviewed", crash.fileName(), "Mark Reviewed", "visibility_off")
                + doctorForm("doctor_delete_crash", crash.fileName(), "Delete", "delete")
                + "</div>";
    }

    private static String metric(String label, String value) {
        return "<div class='rounded-xl border border-slate-800 bg-slate-950/35 p-3'><p class='text-[11px] uppercase tracking-wider text-slate-500'>"
                + escape(label) + "</p><p class='mt-1 text-sm font-semibold text-slate-100 break-words'>" + escape(value) + "</p></div>";
    }

    private static List<String> firstLines(Path path, int max) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null && lines.size() < max) {
                lines.add(line);
            }
        } catch (Exception ignored) {
        }
        return lines;
    }

    private static List<String> tail(Path path, int max) {
        List<String> lines = new ArrayList<>();
        int limit = Math.max(1, max);
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return lines;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() == limit) {
                    lines.remove(0);
                }
                lines.add(line);
            }
        } catch (Exception ignored) {
        }
        return lines;
    }

    private static long modifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String modified(Path path) {
        long millis = modifiedMillis(path);
        return millis <= 0L ? "Unknown" : DATE_TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private record Crash(String fileName, String modified, String cause, String fix, List<String> lines) {
    }
}
