package dash;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GithubUpdater {

    private static final String PUBLIC_REPO = "Framepersecond/Dash";
    private static final long RELEASE_CACHE_TTL_MS = 300_000L;

    private final JavaPlugin plugin;
    private final String releasesLatestApi;
    private final boolean enabled;
    private volatile String lastKnownLatestTag;
    private volatile String cachedLatestReleaseJson;
    private volatile long cachedLatestReleaseFetchedAt;
    private volatile long rateLimitBackoffUntilMs;
    private volatile boolean warnedRateLimited;

    public GithubUpdater(JavaPlugin plugin) {
        this.plugin = plugin;
        this.releasesLatestApi = "https://api.github.com/repos/" + PUBLIC_REPO + "/releases/latest";
        this.enabled = true;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isUpdateAvailable() {
        if (!enabled) {
            return false;
        }

        String latestTag = getLatestVersion();
        if (latestTag == null || latestTag.isBlank()) {
            return false;
        }

        String latestVersion = normalizeVersion(latestTag);
        String currentVersion = normalizeVersion(plugin.getDescription().getVersion());
        return compareVersions(latestVersion, currentVersion) > 0;
    }

    public String getCurrentVersion() {
        return plugin.getDescription() != null ? plugin.getDescription().getVersion() : "unknown";
    }

    public String getLatestVersion() {
        if (!enabled) {
            return getCurrentVersion();
        }
        String latestTag = fetchLatestTag();
        if (latestTag != null && !latestTag.isBlank()) {
            lastKnownLatestTag = latestTag;
            return latestTag;
        }
        return lastKnownLatestTag != null ? lastKnownLatestTag : getCurrentVersion();
    }

    private static final String STAGED_UPDATE_FILENAME = "Dash-update.jar";
    private static final String FINAL_UPDATE_FILENAME = "Dash.jar";

    public boolean isUpdatePrepared() {
        File staged = resolveStagedUpdateFile();
        return staged != null && staged.exists();
    }

    public boolean downloadUpdate() {
        if (!enabled) {
            return false;
        }

        ReleaseAsset asset = fetchLatestJarAsset();
        if (asset == null || asset.downloadUrl().isBlank()) {
            plugin.getLogger().warning("[Updater] No release JAR asset found.");
            return false;
        }

        File output = resolveStagedUpdateFile();
        if (output == null) {
            plugin.getLogger().warning("[Updater] Failed to resolve staged update file path.");
            return false;
        }

        // Download to a temp file first, then rename, so a partial download never leaves a corrupt staged file
        File tempOutput = new File(output.getParentFile(), STAGED_UPDATE_FILENAME + ".tmp");
        try {
            downloadAssetToFile(asset.downloadUrl(), tempOutput);
            if (output.exists() && !output.delete()) {
                plugin.getLogger().warning("[Updater] Could not remove previous staged update — overwriting.");
            }
            if (!tempOutput.renameTo(output)) {
                plugin.getLogger().warning("[Updater] Failed to finalise staged update file.");
                tempOutput.delete();
                return false;
            }
            plugin.getLogger().info("[Updater] Update downloaded successfully and staged as " + STAGED_UPDATE_FILENAME
                    + ". It will be applied automatically when the server shuts down.");
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("[Updater] Failed to download update: " + ex.getMessage());
            tempOutput.delete();
            return false;
        }
    }

    /**
     * Called from {@code onDisable}: replaces the currently running jar with the
     * staged update so the new version loads on the next server start.
     *
     * @return {@code true} if the update was applied, {@code false} otherwise
     */
    public boolean applyUpdateOnShutdown() {
        File staged = resolveStagedUpdateFile();
        if (staged == null || !staged.exists()) {
            return false;
        }

        File pluginsFolder = staged.getParentFile();

        try {
            // Resolve the currently running jar so we can remove it
            File currentJar = new File(
                    plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());

            // Delete the currently running jar.
            // On Linux the JVM keeps an open fd so the file disappears from the directory
            // immediately but disk space is freed once the process exits — safe to do here.
            if (currentJar.exists() && !currentJar.getName().equalsIgnoreCase(FINAL_UPDATE_FILENAME)) {
                if (!currentJar.delete()) {
                    plugin.getLogger().warning("[Updater] Could not delete running jar '"
                            + currentJar.getName() + "'. Manual cleanup may be needed.");
                }
            }

            // If Dash.jar already exists (e.g. from a previously applied update), remove it
            File finalJar = new File(pluginsFolder, FINAL_UPDATE_FILENAME);
            if (finalJar.exists() && !finalJar.delete()) {
                plugin.getLogger().warning("[Updater] Could not remove existing " + FINAL_UPDATE_FILENAME
                        + ". Update rename may fail.");
            }

            if (staged.renameTo(finalJar)) {
                plugin.getLogger().info("[Updater] Update applied! '"
                        + FINAL_UPDATE_FILENAME + "' will be loaded on the next server start.");
                return true;
            } else {
                plugin.getLogger().warning("[Updater] Failed to rename staged update to '"
                        + FINAL_UPDATE_FILENAME + "'. The file is still at: " + staged.getAbsolutePath());
                return false;
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[Updater] Failed to apply update on shutdown: " + ex.getMessage());
            return false;
        }
    }

    private File resolveStagedUpdateFile() {
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        if (pluginsFolder == null) {
            pluginsFolder = plugin.getServer().getUpdateFolderFile().getParentFile();
        }
        return pluginsFolder != null ? new File(pluginsFolder, STAGED_UPDATE_FILENAME) : null;
    }

    private String fetchLatestTag() {
        try {
            String json = fetchLatestReleaseJson();
            Matcher tagMatcher = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            if (tagMatcher.find()) {
                return tagMatcher.group(1);
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("[Updater] Failed to check latest release: " + ex.getMessage());
        }
        return null;
    }

    private ReleaseAsset fetchLatestJarAsset() {
        try {
            String json = fetchLatestReleaseJson();
            String assetsArray = extractJsonArray(json, "assets");
            if (assetsArray == null || assetsArray.isBlank()) {
                return null;
            }

            for (String assetObject : splitTopLevelObjects(assetsArray)) {
                String name = extractJsonStringField(assetObject, "name");
                if (name == null || !name.toLowerCase().endsWith(".jar")) {
                    continue;
                }

                String browserDownloadUrl = extractJsonStringField(assetObject, "browser_download_url");
                if (browserDownloadUrl != null && !browserDownloadUrl.isBlank()) {
                    return new ReleaseAsset(name, browserDownloadUrl);
                }
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("[Updater] Failed to resolve update asset: " + ex.getMessage());
        }
        return null;
    }

    private String fetchLatestReleaseJson() throws IOException {
        long now = System.currentTimeMillis();

        String cached = cachedLatestReleaseJson;
        if (cached != null && (now - cachedLatestReleaseFetchedAt) < RELEASE_CACHE_TTL_MS) {
            return cached;
        }

        if (now < rateLimitBackoffUntilMs) {
            if (cached != null) {
                return cached;
            }
            throw new IOException("GitHub API rate limit active until epoch-second " + (rateLimitBackoffUntilMs / 1000));
        }

        try {
            String json = fetchJson(releasesLatestApi);
            cachedLatestReleaseJson = json;
            cachedLatestReleaseFetchedAt = now;
            rateLimitBackoffUntilMs = 0L;
            warnedRateLimited = false;
            return json;
        } catch (HttpStatusException ex) {
            markRateLimitBackoff(ex, now);
            throw ex;
        }
    }

    private String extractJsonArray(String json, String fieldName) {
        String marker = "\"" + fieldName + "\"";
        int markerIndex = json.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }

        int arrayStart = json.indexOf('[', markerIndex);
        if (arrayStart < 0) {
            return null;
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = arrayStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '[') {
                depth++;
                continue;
            }
            if (c == ']') {
                depth--;
                if (depth == 0) {
                    return json.substring(arrayStart, i + 1);
                }
            }
        }
        return null;
    }

    private java.util.List<String> splitTopLevelObjects(String jsonArray) {
        java.util.List<String> objects = new java.util.ArrayList<>();
        if (jsonArray == null || jsonArray.length() < 2) {
            return objects;
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        int objectStart = -1;

        for (int i = 0; i < jsonArray.length(); i++) {
            char c = jsonArray.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == '{') {
                if (depth == 0) {
                    objectStart = i;
                }
                depth++;
                continue;
            }

            if (c == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    objects.add(jsonArray.substring(objectStart, i + 1));
                    objectStart = -1;
                }
            }
        }

        return objects;
    }

    private String extractJsonStringField(String jsonObject, String fieldName) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(fieldName) + "\\\"\\s*:\\s*\\\"((?:\\\\\\\"|\\\\\\\\|[^\\\"])*)\\\"");
        Matcher matcher = pattern.matcher(jsonObject);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1)
                .replace("\\\\", "\\")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    private String fetchJson(String endpoint) throws IOException {
        return fetchJsonInternal(endpoint);
    }

    private String fetchJsonInternal(String endpoint) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "Dash-Updater");

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            String body = readResponseBody(connection);
            throw new HttpStatusException(
                    buildHttpErrorMessage("GitHub API", endpoint, code, body, connection),
                    code,
                    connection.getHeaderField("X-RateLimit-Remaining"),
                    connection.getHeaderField("X-RateLimit-Reset"));
        }

        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private void downloadAssetToFile(String assetApiUrl, File targetFile) throws IOException {
        downloadAssetToFileInternal(assetApiUrl, targetFile);
    }

    private void downloadAssetToFileInternal(String assetApiUrl, File targetFile) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(assetApiUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/octet-stream");
        connection.setRequestProperty("User-Agent", "Dash-Updater");

        int code = connection.getResponseCode();
        if (code < 200 || code >= 400) {
            String body = readResponseBody(connection);
            throw new HttpStatusException(
                    buildHttpErrorMessage("Asset download", assetApiUrl, code, body, connection),
                    code,
                    connection.getHeaderField("X-RateLimit-Remaining"),
                    connection.getHeaderField("X-RateLimit-Reset"));
        }

        try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(targetFile)) {
            in.transferTo(out);
        } finally {
            connection.disconnect();
        }
    }

    private String normalizeVersion(String version) {
        if (version == null) {
            return "0";
        }
        String normalized = version.trim().toLowerCase();
        if (normalized.startsWith("v")) {
            normalized = normalized.substring(1);
        }
        int dash = normalized.indexOf('-');
        if (dash >= 0) {
            normalized = normalized.substring(0, dash);
        }
        return normalized;
    }

    private int compareVersions(String a, String b) {
        String[] aParts = a.split("\\.");
        String[] bParts = b.split("\\.");
        int max = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < max; i++) {
            int av = parseIntPart(aParts, i);
            int bv = parseIntPart(bParts, i);
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    private int parseIntPart(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        String clean = parts[index].replaceAll("[^0-9]", "");
        if (clean.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(clean);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private boolean isRateLimited(HttpStatusException ex) {
        if (ex == null || ex.statusCode != 403) {
            return false;
        }
        if ("0".equals(ex.rateLimitRemaining)) {
            return true;
        }
        String msg = ex.getMessage();
        return msg != null && msg.toLowerCase().contains("rate limit");
    }

    private void markRateLimitBackoff(HttpStatusException ex, long nowMs) {
        if (!isRateLimited(ex)) {
            return;
        }

        long fallbackReset = nowMs + 60_000L;
        long resetMs = fallbackReset;
        if (ex.rateLimitReset != null && !ex.rateLimitReset.isBlank()) {
            try {
                resetMs = Long.parseLong(ex.rateLimitReset.trim()) * 1000L;
            } catch (NumberFormatException ignored) {
                resetMs = fallbackReset;
            }
        }

        rateLimitBackoffUntilMs = Math.max(rateLimitBackoffUntilMs, resetMs);
        if (!warnedRateLimited) {
            warnedRateLimited = true;
            long seconds = Math.max(1L, (rateLimitBackoffUntilMs - nowMs) / 1000L);
            plugin.getLogger().warning("[Updater] GitHub rate limit reached. Pausing update checks for ~" + seconds
                    + "s (until epoch-second " + (rateLimitBackoffUntilMs / 1000L) + ").");
        }
    }

    private String readResponseBody(HttpURLConnection connection) {
        try (InputStream err = connection.getErrorStream()) {
            if (err == null) {
                return "";
            }
            return new String(err.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            return "";
        }
    }

    private String buildHttpErrorMessage(String prefix, String endpoint, int statusCode, String responseBody,
            HttpURLConnection connection) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append(" HTTP ").append(statusCode).append(" on ").append(endpoint);

        String authHeader = connection.getHeaderField("WWW-Authenticate");
        if (authHeader != null && !authHeader.isBlank()) {
            sb.append(" | WWW-Authenticate: ").append(authHeader);
        }

        String remaining = connection.getHeaderField("X-RateLimit-Remaining");
        String reset = connection.getHeaderField("X-RateLimit-Reset");
        if (remaining != null && !remaining.isBlank()) {
            sb.append(" | X-RateLimit-Remaining: ").append(remaining);
        }
        if (reset != null && !reset.isBlank()) {
            sb.append(" | X-RateLimit-Reset: ").append(reset);
        }

        if (responseBody != null && !responseBody.isBlank()) {
            sb.append(" | Response: ").append(responseBody);
        }

        return sb.toString();
    }


    private static final class HttpStatusException extends IOException {
        private final int statusCode;
        private final String rateLimitRemaining;
        private final String rateLimitReset;

        private HttpStatusException(String message, int statusCode, String rateLimitRemaining, String rateLimitReset) {
            super(message);
            this.statusCode = statusCode;
            this.rateLimitRemaining = rateLimitRemaining;
            this.rateLimitReset = rateLimitReset;
        }
    }

    private record ReleaseAsset(String name, String downloadUrl) {
    }
}

