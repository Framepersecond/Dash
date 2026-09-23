package dash.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dash.Dash;
import dash.security.SafeHttpDownloads;
import org.bukkit.Bukkit;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Stream;

public final class PluginBrowserPage {

    private static final long SCAN_THROTTLE_MS = 5 * 60 * 1000L;
    private static volatile long lastScanStartedAt;

    private PluginBrowserPage() {
    }

    public static String render(String message) {
        Path root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path dataDir = Dash.getInstance().getDataFolder().toPath().toAbsolutePath().normalize();
        recordStartupScan(root, dataDir, "plugins", "bukkit");
        List<JarInfo> jars = mergeScanState(listJars(root.resolve("plugins")), dataDir.resolve("plugin-browser-scan.json"));

        String banner = message == null || message.isBlank()
                ? ""
                : "<div class='rounded-xl border border-cyan-500/30 bg-cyan-500/10 px-4 py-3 text-sm text-cyan-100'>"
                        + escape(message) + "</div>";

        StringBuilder installed = new StringBuilder();
        for (JarInfo jar : jars) {
            String stateClass = jar.updateAvailable()
                    ? "bg-amber-500/15 text-amber-300"
                    : "Current".equalsIgnoreCase(jar.status())
                            ? "bg-emerald-500/15 text-emerald-300"
                            : "bg-slate-800 text-slate-300";
            installed.append("<div class='rounded-xl border border-slate-800 bg-slate-950/35 p-3' data-installed-jar='")
                    .append(escapeAttr(jar.name())).append("'>")
                    .append("<div class='flex items-center justify-between gap-3'>")
                    .append("<div class='min-w-0'><p class='truncate text-sm font-semibold text-slate-100'>")
                    .append(escape(jar.name())).append("</p><p class='text-[11px] text-slate-500'>")
                    .append(escape(jar.size())).append("</p></div>")
                    .append("<span data-scan-pending='").append("Queued".equalsIgnoreCase(jar.status()))
                    .append("' class='rounded-full px-2 py-1 text-[11px] font-bold browser-scan-state ")
                    .append(stateClass).append("'>").append(escape(jar.status())).append("</span>")
                    .append("</div>")
                    .append(jar.scanSummary().isBlank() ? "" : "<p class='mt-2 text-[11px] text-slate-400'>" + escape(jar.scanSummary()) + "</p>")
                    .append("</div>");
        }
        if (installed.isEmpty()) {
            installed.append("<div class='rounded-xl border border-slate-800 bg-slate-950/35 p-4 text-center text-sm text-slate-500'>No plugin jars found.</div>");
        }

        String content = HtmlTemplate.statsHeader()
                + "<main class='flex-1 min-w-0 p-4 sm:p-6'>"
                + "<div class='max-w-7xl mx-auto space-y-5'>"
                + "<nav class='grid grid-cols-2 gap-1 rounded-xl border border-glass-border bg-slate-950/35 p-1' aria-label='Plugin workspace'><a href='/plugins' class='rounded-lg px-4 py-2 text-center text-xs font-bold text-slate-400 hover:bg-white/5 hover:text-white'>Installed</a><a href='/plugin-browser' aria-current='page' class='rounded-lg bg-cyan-500/15 px-4 py-2 text-center text-xs font-bold text-cyan-200'>Discover compatible</a></nav>"
                + "<section class='rounded-2xl bg-glass-surface border border-glass-border p-5'>"
                + "<div class='flex flex-col lg:flex-row lg:items-center lg:justify-between gap-4'>"
                + "<div><div class='inline-flex items-center gap-2 rounded-full border border-primary/25 bg-primary/10 px-3 py-1 text-xs font-bold text-primary mb-3'>"
                + "<span class='material-symbols-outlined text-[16px]'>travel_explore</span>Modrinth Browser</div>"
                + "<h1 class='text-2xl sm:text-3xl font-bold text-white'>Plugin Browser</h1>"
                + "<p class='text-sm text-slate-400 mt-1'>Search trusted Bukkit, Paper, Spigot and Purpur plugins, install jars, and compare installed files.</p></div>"
                + "<div class='grid grid-cols-2 gap-3 text-sm'>"
                + metric("Loader", "Bukkit/Paper")
                + metric("Installed Jars", String.valueOf(jars.size()))
                + "</div></div></section>"
                + banner
                + "<section class='grid grid-cols-1 xl:grid-cols-[1.25fr_.75fr] gap-4'>"
                + "<article class='rounded-2xl bg-glass-surface border border-glass-border p-5'>"
                + "<div class='flex flex-col sm:flex-row sm:items-end gap-3 mb-4'>"
                + field("Search", "<input id='browser-query' type='text' placeholder='WorldGuard, LuckPerms, BlueMap...'>")
                + field("Sort", "<select id='browser-sort'><option value='relevance'>Relevance</option><option value='downloads'>Downloads</option><option value='follows'>Follows</option><option value='updated'>Recently updated</option></select>")
                + "<button id='browser-search' type='button' class='inline-flex items-center justify-center gap-2 rounded-xl bg-primary px-4 py-2.5 text-sm font-bold text-black hover:brightness-110'>"
                + "<span class='material-symbols-outlined text-[18px]'>search</span>Search</button>"
                + "</div><div id='browser-results' class='grid grid-cols-1 lg:grid-cols-2 gap-3'></div>"
                + "<div id='browser-empty' class='rounded-xl border border-slate-800 bg-slate-950/35 p-6 text-center text-sm text-slate-500'>Start with a plugin name or keyword.</div>"
                + "</article>"
                + "<aside class='space-y-4'>"
                + "<article class='rounded-2xl bg-glass-surface border border-glass-border p-5'>"
                + "<div class='flex items-center justify-between gap-3 mb-4'><h2 class='text-lg font-bold text-white'>Startup Update Scan</h2>"
                + "<span class='material-symbols-outlined text-primary'>radar</span></div>"
                + "<p class='text-xs text-slate-400 mb-3'>Dash stores installed plugin data at startup and refreshes update hints when this page opens.</p>"
                + "<div class='space-y-2'>" + installed + "</div></article>"
                + "<article class='rounded-2xl bg-glass-surface border border-glass-border p-5'>"
                + "<h2 class='text-lg font-bold text-white mb-3'>Direct Jar Install</h2>"
                + "<form method='post' action='/action' class='space-y-3'>"
                + "<input type='hidden' name='action' value='plugin_browser_install'><input type='hidden' name='return_to' value='/plugin-browser'>"
                + field("Download URL", "<input name='download_url' type='text' required placeholder='https://.../plugin.jar'>")
                + field("File Name", "<input name='file_name' type='text' required placeholder='plugin.jar'>")
                + field("SHA-256", "<input name='sha256' type='text' required minlength='64' maxlength='64' pattern='[A-Fa-f0-9]{64}' placeholder='64 hexadecimal characters'>")
                + "<button class='inline-flex w-full items-center justify-center gap-2 rounded-xl bg-emerald-500 px-4 py-2.5 text-sm font-bold text-black hover:bg-emerald-400'>"
                + "<span class='material-symbols-outlined text-[18px]'>download</span>Install Jar</button></form></article></aside></section>"
                + "</div></main>"
                + HtmlTemplate.statsScript()
                + script("plugin", "plugins", "[[\"all_project_types:plugin\"],[\"categories:bukkit\",\"categories:spigot\",\"categories:paper\",\"categories:purpur\"]]", "[\"bukkit\",\"spigot\",\"paper\",\"purpur\"]");

        return HtmlTemplate.page("Plugin Browser", "/plugin-browser", content);
    }

    public static String installFromUrl(Path root, String folderName, String rawUrl, String rawFileName,
            String rawSha256, String rawSha512) throws Exception {
        if (rawUrl == null || rawUrl.isBlank() || rawFileName == null || rawFileName.isBlank()) {
            return "Missing download URL or file name.";
        }
        String fileName = rawFileName.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return "Only .jar files can be installed.";
        }
        String sha512 = rawSha512 == null ? "" : rawSha512.trim();
        String sha256 = rawSha256 == null ? "" : rawSha256.trim();
        String algorithm;
        String expected;
        if (sha512.matches("(?i)[a-f0-9]{128}")) {
            algorithm = "SHA-512";
            expected = sha512;
        } else if (sha256.matches("(?i)[a-f0-9]{64}")) {
            algorithm = "SHA-256";
            expected = sha256;
        } else {
            return "A valid SHA-256 digest is required for direct JAR installs.";
        }
        Path targetDir = root.resolve(folderName).toAbsolutePath().normalize();
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(fileName).normalize();
        if (!target.startsWith(targetDir) || Files.isSymbolicLink(targetDir) || Files.isSymbolicLink(target)) {
            return "Invalid target file.";
        }
        Path staged = Files.createTempFile(targetDir, ".dash-browser-install-", ".jar");
        try {
            SafeHttpDownloads.downloadPublicHttpsJar(rawUrl, staged);
            String actual = hashFile(staged, algorithm);
            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                    expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
                return algorithm + " verification failed; the existing JAR was not changed.";
            }
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
        return "Installed " + fileName + ". Restart the server to load it.";
    }

    public static String searchJson(String query, String sort, String facetsJson, String loadersJson) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isBlank()) {
            return "{\"success\":true,\"hits\":[]}";
        }
        String safeSort = switch (sort == null ? "" : sort.toLowerCase(Locale.ROOT)) {
            case "downloads", "follows", "updated" -> sort.toLowerCase(Locale.ROOT);
            default -> "relevance";
        };
        String gameVersion = Bukkit.getMinecraftVersion();
        String versionedFacets = addGameVersionFacet(facetsJson, gameVersion);
        String base = "https://api.modrinth.com/v2/search?limit=18&query=" + encodeUrl(trimmed)
                + "&index=" + encodeUrl(safeSort);
        try {
            JsonArray hits = searchHits(base + "&facets=" + encodeUrl(versionedFacets));
            return browserHitsJson(hits, loadersJson, gameVersion);
        } catch (Exception ex) {
            return "{\"success\":false,\"error\":\"" + json(firstNonBlank(ex.getMessage(), "Search failed."))
                    + "\",\"hits\":[]}";
        }
    }

    private static JsonArray searchHits(String url) throws Exception {
        JsonObject root = fetchJsonObject(url);
        if (root == null || !root.has("hits") || !root.get("hits").isJsonArray()) {
            return new JsonArray();
        }
        return root.getAsJsonArray("hits");
    }

    private static String browserHitsJson(JsonArray hits, String loadersJson, String gameVersion) {
        StringBuilder out = new StringBuilder("{\"success\":true,\"hits\":[");
        int added = 0;
        for (JsonElement element : hits) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject hit = element.getAsJsonObject();
            String slug = firstNonBlank(jsonString(hit, "slug"), jsonString(hit, "project_id"));
            ResolvedInstall install = resolveInstallFile(slug, loadersJson, gameVersion);
            if (added++ > 0) {
                out.append(',');
            }
            out.append("{\"title\":\"").append(json(firstNonBlank(jsonString(hit, "title"), slug)))
                    .append("\",\"slug\":\"").append(json(slug))
                    .append("\",\"description\":\"").append(json(jsonString(hit, "description")))
                    .append("\",\"icon_url\":\"").append(json(jsonString(hit, "icon_url")))
                    .append("\",\"downloads\":").append(jsonLong(hit, "downloads"))
                    .append(",\"download_url\":\"").append(json(install.url()))
                    .append("\",\"file_name\":\"").append(json(install.fileName()))
                    .append("\",\"sha512\":\"").append(json(install.sha512()))
                    .append("\",\"version_note\":\"").append(json(install.note()))
                    .append("\"}");
        }
        return out.append("]}").toString();
    }

    private static ResolvedInstall resolveInstallFile(String project, String loadersJson, String gameVersion) {
        if (project == null || project.isBlank()) {
            return new ResolvedInstall("", "", "No compatible jar found.");
        }
        try {
            JsonArray versions = fetchJsonArray("https://api.modrinth.com/v2/project/" + encodeUrl(project)
                    + "/version?loaders=" + encodeUrl(loadersJson)
                    + "&game_versions=" + encodeUrl("[\"" + gameVersion + "\"]"));
            if (versions == null) {
                return new ResolvedInstall("", "", "No compatible jar found.");
            }
            for (JsonElement versionElement : versions) {
                if (!versionElement.isJsonObject()) {
                    continue;
                }
                JsonObject version = versionElement.getAsJsonObject();
                JsonObject file = firstJarFile(version.has("files") && version.get("files").isJsonArray()
                        ? version.getAsJsonArray("files")
                        : new JsonArray());
                if (file != null) {
                    String versionName = displayVersion(jsonString(version, "version_number"), jsonString(version, "id"));
                    String sha512 = file.has("hashes") && file.get("hashes").isJsonObject()
                            ? jsonString(file.getAsJsonObject("hashes"), "sha512") : "";
                    return new ResolvedInstall(jsonString(file, "url"), jsonString(file, "filename"),
                            "Compatible build " + versionName, sha512);
                }
            }
        } catch (Exception ignored) {
            return new ResolvedInstall("", "", "Version lookup unavailable.");
        }
        return new ResolvedInstall("", "", "No compatible jar found.");
    }

    private static String addGameVersionFacet(String facetsJson, String gameVersion) {
        try {
            JsonArray facets = JsonParser.parseString(facetsJson).getAsJsonArray();
            JsonArray version = new JsonArray();
            version.add("versions:" + gameVersion);
            facets.add(version);
            return facets.toString();
        } catch (Exception ignored) {
            return "[[\"versions:" + json(gameVersion) + "\"]]";
        }
    }

    private static JsonObject firstJarFile(JsonArray files) {
        JsonObject fallback = null;
        for (JsonElement fileElement : files) {
            if (!fileElement.isJsonObject()) {
                continue;
            }
            JsonObject file = fileElement.getAsJsonObject();
            String name = jsonString(file, "filename").toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jar")) {
                continue;
            }
            if (fallback == null) {
                fallback = file;
            }
            try {
                if (file.has("primary") && file.get("primary").getAsBoolean()) {
                    return file;
                }
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    public static void recordStartupScan(Path root, Path dataDir, String folderName, String loader) {
        try {
            Files.createDirectories(dataDir);
            Path scanFile = dataDir.resolve("plugin-browser-scan.json");
            List<JarInfo> jars = listJars(root.resolve(folderName));
            long now = System.currentTimeMillis();
            if (Files.isRegularFile(scanFile) && now - lastScanStartedAt < SCAN_THROTTLE_MS) {
                return;
            }
            writeScanReport(scanFile, loader, jars, false, "Hash scan queued.");
            lastScanStartedAt = now;
            Thread scanner = new Thread(() -> runUpdateScan(root, dataDir, folderName, loader),
                    "dash-plugin-browser-scan");
            scanner.setDaemon(true);
            scanner.start();
        } catch (Exception ignored) {
        }
    }

    private static void runUpdateScan(Path root, Path dataDir, String folderName, String loader) {
        try {
            Path scanFile = dataDir.resolve("plugin-browser-scan.json");
            List<Path> jarPaths = listJarPaths(root.resolve(folderName));
            List<JarInfo> report = new ArrayList<>();
            for (Path jar : jarPaths) {
                report.add(scanJarUpdate(jar, loader));
            }
            writeScanReport(scanFile, loader, report, true, "Hash scan complete.");
        } catch (Exception ignored) {
        }
    }

    private static JarInfo scanJarUpdate(Path jar, String loader) {
        JarInfo fallback = new JarInfo(jar.getFileName().toString(), size(jar));
        try {
            String sha1 = hashFile(jar);
            JsonObject current = fetchJsonObject("https://api.modrinth.com/v2/version_file/"
                    + encodeUrl(sha1) + "?algorithm=sha1");
            if (current == null) {
                return fallback.withScan(sha1, "Local only", "", "", "", false,
                        "Not recognized by Modrinth's hash database.");
            }
            String projectId = jsonString(current, "project_id");
            String projectTitle = resolveProjectTitle(projectId);
            String currentVersionId = jsonString(current, "id");
            String currentVersion = displayVersion(jsonString(current, "version_number"), currentVersionId);
            JsonObject latest = fetchLatestFromHash(sha1, loader);
            if (latest == null) {
                return fallback.withScan(sha1, "Current", projectTitle, currentVersion, "", false,
                        projectTitle.isBlank() ? "Recognized by Modrinth." : projectTitle + " is recognized by Modrinth.");
            }
            String latestVersionId = jsonString(latest, "id");
            String latestVersion = displayVersion(jsonString(latest, "version_number"), latestVersionId);
            boolean updateAvailable = !latestVersionId.isBlank() && !latestVersionId.equals(currentVersionId);
            String summary = updateAvailable
                    ? firstNonBlank(projectTitle, fallback.name()) + ": " + currentVersion + " -> " + latestVersion
                    : firstNonBlank(projectTitle, fallback.name()) + " is current.";
            return fallback.withScan(sha1, updateAvailable ? "Update" : "Current", projectTitle,
                    currentVersion, latestVersion, updateAvailable, summary);
        } catch (Exception ex) {
            return fallback.withScan("", "Saved", "", "", "", false,
                    "Saved locally; online update check failed.");
        }
    }

    private static List<JarInfo> listJars(Path dir) {
        List<JarInfo> jars = new ArrayList<>();
        for (Path path : listJarPaths(dir)) {
            jars.add(new JarInfo(path.getFileName().toString(), size(path)));
        }
        return jars;
    }

    private static List<Path> listJarPaths(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .limit(80)
                    .forEach(paths::add);
        } catch (Exception ignored) {
        }
        return paths;
    }

    private static List<JarInfo> mergeScanState(List<JarInfo> jars, Path scanFile) {
        if (!Files.isRegularFile(scanFile)) {
            return jars;
        }
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(scanFile), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("jars") || !root.get("jars").isJsonArray()) {
                return jars;
            }
            List<JarInfo> scanned = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("jars")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                scanned.add(new JarInfo(
                        jsonString(object, "name"),
                        jsonString(object, "size"),
                        jsonString(object, "sha1"),
                        firstNonBlank(jsonString(object, "status"), "Queued"),
                        jsonString(object, "project"),
                        jsonString(object, "currentVersion"),
                        jsonString(object, "latestVersion"),
                        jsonString(object, "summary"),
                        object.has("updateAvailable") && object.get("updateAvailable").getAsBoolean()));
            }
            List<JarInfo> merged = new ArrayList<>();
            for (JarInfo jar : jars) {
                merged.add(scanned.stream()
                        .filter(item -> item.name().equals(jar.name()))
                        .findFirst()
                        .orElse(jar));
            }
            return merged;
        } catch (Exception ignored) {
            return jars;
        }
    }

    private static void writeScanReport(Path scanFile, String loader, List<JarInfo> jars, boolean complete, String status) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("{\"timestamp\":").append(Instant.now().toEpochMilli())
                .append(",\"loader\":\"").append(json(loader)).append("\"")
                .append(",\"complete\":").append(complete)
                .append(",\"status\":\"").append(json(status)).append("\",\"jars\":[");
        for (int i = 0; i < jars.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            JarInfo jar = jars.get(i);
            out.append("{\"name\":\"").append(json(jar.name()))
                    .append("\",\"size\":\"").append(json(jar.size()))
                    .append("\",\"sha1\":\"").append(json(jar.sha1()))
                    .append("\",\"status\":\"").append(json(jar.status()))
                    .append("\",\"project\":\"").append(json(jar.project()))
                    .append("\",\"currentVersion\":\"").append(json(jar.currentVersion()))
                    .append("\",\"latestVersion\":\"").append(json(jar.latestVersion()))
                    .append("\",\"summary\":\"").append(json(jar.scanSummary()))
                    .append("\",\"updateAvailable\":").append(jar.updateAvailable()).append('}');
        }
        out.append("]}");
        Files.writeString(scanFile, out.toString(), StandardCharsets.UTF_8);
    }

    private static JsonObject fetchLatestFromHash(String sha1, String loader) throws Exception {
        JsonObject body = new JsonObject();
        JsonArray loaders = new JsonArray();
        for (String accepted : acceptedLoaders(loader)) {
            loaders.add(accepted);
        }
        body.add("loaders", loaders);
        return postJsonObject("https://api.modrinth.com/v2/version_file/" + encodeUrl(sha1)
                + "/update?algorithm=sha1", body.toString());
    }

    private static String[] acceptedLoaders(String loader) {
        return switch (loader == null ? "" : loader.toLowerCase(Locale.ROOT)) {
            case "bukkit" -> new String[]{"bukkit", "spigot", "paper", "purpur"};
            case "forge" -> new String[]{"forge", "neoforge"};
            case "fabric" -> new String[]{"fabric"};
            default -> new String[]{loader};
        };
    }

    private static String resolveProjectTitle(String projectId) {
        try {
            JsonObject project = fetchJsonObject("https://api.modrinth.com/v2/project/" + encodeUrl(projectId));
            return project == null ? "" : firstNonBlank(jsonString(project, "title"), jsonString(project, "slug"));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static JsonObject fetchJsonObject(String rawUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(rawUrl).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(9000);
        connection.setRequestProperty("User-Agent", "Dash-PluginBrowser/4.3");
        int status = connection.getResponseCode();
        if (status == 404) {
            return null;
        }
        if (status < 200 || status >= 300) {
            return null;
        }
        try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        }
    }

    private static JsonArray fetchJsonArray(String rawUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(rawUrl).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(9000);
        connection.setRequestProperty("User-Agent", "Dash-PluginBrowser/4.3");
        int status = connection.getResponseCode();
        if (status == 404) {
            return null;
        }
        if (status < 200 || status >= 300) {
            return null;
        }
        try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
        }
    }

    private static JsonObject postJsonObject(String rawUrl, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(rawUrl).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(9000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("User-Agent", "Dash-PluginBrowser/4.3");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        if (status == 404 || status == 204) {
            return null;
        }
        if (status < 200 || status >= 300) {
            return null;
        }
        try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        }
    }

    private static String hashFile(Path file) throws Exception {
        return hashFile(file, "SHA-1");
    }

    private static String hashFile(Path file, String algorithm) throws Exception {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            byte[] bytes = digest.digest();
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(algorithm + " unavailable", ex);
        }
    }

    private static String encodeUrl(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String jsonString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static long jsonLong(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return object.get(key).getAsLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String displayVersion(String version, String fallbackId) {
        if (version != null && !version.isBlank()) {
            return version;
        }
        if (fallbackId == null || fallbackId.isBlank()) {
            return "Unknown";
        }
        return fallbackId.length() > 8 ? fallbackId.substring(0, 8) : fallbackId;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String script(String itemLabel, String folderLabel, String facetsJson, String loadersJson) {
        return "<script>(function(){"
                + "const q=document.getElementById('browser-query'),sort=document.getElementById('browser-sort'),btn=document.getElementById('browser-search'),results=document.getElementById('browser-results'),empty=document.getElementById('browser-empty');"
                + "function esc(v){return String(v||'').replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]));}"
                + "function installForm(url,name,sha512){return '<form method=\"post\" action=\"/action\" class=\"mt-3\"><input type=\"hidden\" name=\"action\" value=\"plugin_browser_install\"><input type=\"hidden\" name=\"return_to\" value=\"/plugin-browser\"><input type=\"hidden\" name=\"download_url\" value=\"'+esc(url)+'\"><input type=\"hidden\" name=\"file_name\" value=\"'+esc(name)+'\"><input type=\"hidden\" name=\"sha512\" value=\"'+esc(sha512)+'\"><button class=\"inline-flex w-full items-center justify-center gap-2 rounded-xl bg-emerald-500 px-3 py-2 text-xs font-bold text-black hover:bg-emerald-400\"><span class=\"material-symbols-outlined text-[16px]\">download</span>Install '+esc(name)+'</button></form>';}"
                + "async function search(){const query=(q.value||'').trim();if(!query)return;results.innerHTML='';empty.textContent='Searching...';empty.style.display='block';try{const r=await fetch('/api/plugin-browser/search?query='+encodeURIComponent(query)+'&sort='+encodeURIComponent(sort.value),{headers:{'Accept':'application/json'}});const data=await r.json();const hits=data.hits||[];if(!data.success){empty.textContent=data.error||'Search failed.';return;}empty.style.display=hits.length?'none':'block';empty.textContent=hits.length?'':'No results.';for(const h of hits){const card=document.createElement('article');card.className='rounded-2xl border border-slate-800 bg-slate-950/35 p-4';const action=h.download_url&&h.sha512?installForm(h.download_url,h.file_name,h.sha512):'<div class=\"mt-3 text-xs text-slate-500\">'+esc(h.version_note||'No compatible '+itemLabel+' jar found.')+'</div>';card.innerHTML='<div class=\"flex items-start gap-3\"><img src=\"'+esc(h.icon_url||'')+'\" class=\"h-10 w-10 rounded-xl bg-slate-800\" alt=\"\"><div class=\"min-w-0\"><h3 class=\"font-bold text-white truncate\">'+esc(h.title)+'</h3><p class=\"text-xs text-slate-500\">'+esc((h.downloads||0).toLocaleString())+' downloads</p></div></div><p class=\"mt-3 text-sm text-slate-400 line-clamp-3\">'+esc(h.description)+'</p><div class=\"mt-2 text-[11px] text-cyan-300\">'+esc(h.version_note||'')+'</div>'+action;results.appendChild(card);}}catch(e){empty.textContent='Search failed. Verify the URL and digest, then use direct JAR install.';}}"
                + "function scanInstalled(){for(const badge of document.querySelectorAll('.browser-scan-state[data-scan-pending=\"true\"]')){badge.textContent='Queued';}}"
                + "btn.addEventListener('click',search);q.addEventListener('keydown',e=>{if(e.key==='Enter')search();});scanInstalled();"
                + "})();</script>";
    }

    private static String field(String label, String control) {
        return "<label class='flex-1 min-w-[180px]'><span class='mb-1 block text-[11px] uppercase tracking-wider text-slate-500'>"
                + escape(label) + "</span>" + control + "</label>";
    }

    private static String metric(String label, String value) {
        return "<div class='rounded-xl border border-slate-800 bg-slate-950/35 p-3'><p class='text-[11px] uppercase tracking-wider text-slate-500'>"
                + escape(label) + "</p><p class='mt-1 text-sm font-semibold text-slate-100'>" + escape(value) + "</p></div>";
    }

    private static String size(Path path) {
        try {
            long bytes = Files.size(path);
            if (bytes < 1024L * 1024L) {
                return Math.max(1L, bytes / 1024L) + " KB";
            }
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0d / 1024.0d);
        } catch (Exception ignored) {
            return "Unknown size";
        }
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escapeAttr(String text) {
        return escape(text);
    }

    private static String json(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r")
                .replace("\n", "\\n").replace("\t", "\\t");
    }

    private record ResolvedInstall(String url, String fileName, String note, String sha512) {
        private ResolvedInstall(String url, String fileName, String note) {
            this(url, fileName, note, "");
        }
    }

    private record JarInfo(String name, String size, String sha1, String status, String project,
            String currentVersion, String latestVersion, String scanSummary, boolean updateAvailable) {

        JarInfo(String name, String size) {
            this(name, size, "", "Queued", "", "", "", "", false);
        }

        JarInfo withScan(String sha1, String status, String project, String currentVersion,
                String latestVersion, boolean updateAvailable, String scanSummary) {
            return new JarInfo(name, size, sha1, status, project, currentVersion, latestVersion,
                    scanSummary, updateAvailable);
        }
    }
}
