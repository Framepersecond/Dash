package dash;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import dash.api.ApiKeyStore;
import dash.api.ApiSettings;
import dash.api.DashApiProvider;
import dash.api.ReadApiHandler;
import dash.web.*;
import dash.ai.AiAgentManager;
import com.google.gson.JsonObject;
import dash.data.GuardianDataManager;
import dash.data.IntelligenceManager;
import dash.data.OperationsManager;
import dash.guardian.GuardianActionService;
import dash.integration.CoreProtectBridge;
import dash.bridge.ConsoleCatcher;
import dash.bridge.BridgeSecurity;
import dash.security.BasePath;
import dash.security.HttpSecurity;
import dash.security.LoginRateLimiter;
import dash.security.DiscordWebhookPolicy;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.Duration;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class AdminWebServer {

    private static final String SESSION_COOKIE_NAME = "dash_auth";
    private static final String BETA_FEATURES_CONFIG_KEY = "beta.enabled";
    private static final long SESSION_TTL_MS = 3600000L;
    private static final long SSO_SIGNATURE_MAX_AGE_SECONDS = 300L;

    private final JavaPlugin plugin;
    private final WebAuth auth;
    private final OperationsManager operationsManager;
    private final IntelligenceManager intelligenceManager;
    private final AiAgentManager aiAgentManager;
    private HttpServer server;
    private ExecutorService httpExecutor;
    private final int port;
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> usedSsoSignatures = new ConcurrentHashMap<>();
    private final LoginRateLimiter loginRateLimiter = new LoginRateLimiter(
            5, Duration.ofMinutes(15), Duration.ofMinutes(15));

    private static class SessionInfo {
        final String username;
        final long expiresAt;
        final boolean bridgeBound;
        final String bridgeSecretSnapshot;

        SessionInfo(String username, long expiresAt, boolean bridgeBound, String bridgeSecretSnapshot) {
            this.username = username;
            this.expiresAt = expiresAt;
            this.bridgeBound = bridgeBound;
            this.bridgeSecretSnapshot = bridgeSecretSnapshot == null ? "" : bridgeSecretSnapshot.trim();
        }
    }

    public AdminWebServer(JavaPlugin plugin, int port) {
        this.plugin = plugin;
        this.port = port;
        this.auth = new WebAuth(plugin);
        this.operationsManager = new OperationsManager(
                plugin.getDataFolder().toPath(),
                Bukkit.getWorldContainer().toPath(),
                "Paper");
        this.intelligenceManager = new IntelligenceManager(
                plugin.getDataFolder().toPath().resolve("intelligence"),
                Bukkit.getWorldContainer().toPath(),
                "Paper");
        this.aiAgentManager = new AiAgentManager(plugin.getDataFolder().toPath().resolve("ai"));
    }

    public void start() {
        ConsoleLogAppender.register();
        ensureUpdaterConfigStructure();
        PluginBrowserPage.recordStartupScan(
                Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize(),
                plugin.getDataFolder().toPath().toAbsolutePath().normalize(),
                "plugins",
                "bukkit");

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);

            server.createContext(BundledStyles.PATH, this::serveBundledStyles);
            server.createContext(BundledMotion.SCRIPT_PATH, this::serveBundledMotionScript);
            server.createContext(BundledMotion.STYLE_PATH, this::serveBundledMotionStyle);
            registerReadApi();
            server.createContext("/", new PageHandler());
            server.createContext("/login", new PageHandler());
            server.createContext("/console", new PageHandler());
            server.createContext("/players", new PageHandler());
            server.createContext("/files", new PageHandler());
            server.createContext("/files/edit", new PageHandler());
            server.createContext("/plugins", new PageHandler());
            server.createContext("/plugin-browser", new PageHandler());
            server.createContext("/intelligence", new PageHandler());
            server.createContext("/status", new PageHandler());
            server.createContext("/maintenance", new PageHandler());
            server.createContext("/doctor", new PageHandler());
            server.createContext("/ai", new PageHandler());
            server.createContext("/staff", new PageHandler());
            server.createContext("/report", new PageHandler());
            server.createContext("/notifications", new PageHandler());
            server.createContext("/graphs", new PageHandler());
            server.createContext("/guardian", new PageHandler());
            server.createContext("/users", new PageHandler());
            server.createContext("/permissions", new PageHandler());
            server.createContext("/settings", new PageHandler());
            server.createContext("/setup", new PageHandler());
            server.createContext("/waiting-room", new PageHandler());
            server.createContext("/audit", new PageHandler());
            server.createContext("/plugin-settings", new PageHandler());
            server.createContext("/scheduled-tasks", new PageHandler());
            server.createContext("/updates", new PageHandler());

            server.createContext("/api/console", new ConsoleApiHandler());
            server.createContext("/api/health", new HealthApiHandler());
            server.createContext("/api/server/state", new HealthApiHandler());
            server.createContext("/api/ping", new HealthApiHandler());
            server.createContext("/api/logout", new LogoutApiHandler());
            server.createContext("/api/stats", new StatsApiHandler());
            server.createContext("/api/stats/history", new StatsHistoryHandler());
            server.createContext("/api/guardian", new GuardianApiHandler());
            server.createContext("/api/settings", new SettingsApiHandler());
            server.createContext("/api/plugin-browser/search", new PluginBrowserSearchHandler());
            server.createContext("/api/files/save", new FileSaveHandler());
            server.createContext("/api/files/download", new FileDownloadHandler());
            server.createContext("/api/backups/download", new BackupDownloadHandler());
            server.createContext("/api/upload/icon", new IconUploadHandler());
            server.createContext("/api/upload/datapack", new DatapackUploadHandler());
            server.createContext("/api/upload/file", new FileUploadHandler());
            server.createContext("/api/upload/plugin", new PluginUploadHandler());
            server.createContext("/api/update/download", new UpdateDownloadHandler());
            server.createContext("/api/update/restart", new UpdateRestartHandler());
            server.createContext("/api/players", new BridgePlayersHandler());
            server.createContext("/api/players/profile", new PlayerProfileHandler());
            server.createContext("/api/bridge/console", new BridgeConsoleHandler());
            server.createContext("/api/bridge/rotate-secret", new BridgeSecretRotationHandler());
            server.createContext("/api/webhook/approve", new WebhookApproveHandler());
            server.createContext("/api/ui-language", new UiLanguageHandler());
            server.createContext("/api/ai", new AiApiHandler());
            server.createContext("/sso", new SsoAuthHandler(plugin, auth, this));

            server.createContext("/action", new ActionHandler());

            httpExecutor = Executors.newFixedThreadPool(8, runnable -> {
                Thread thread = new Thread(runnable, "dash-http");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(httpExecutor);
            server.start();
            plugin.getLogger().info("Web admin started on port " + port);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to start web server: " + e.getMessage());
        }
    }

    public void stop() {
        aiAgentManager.close();
        if (server != null)
            server.stop(0);
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
    }

    private boolean isAuthenticated(HttpExchange t) {
        return resolveSession(t) != null;
    }

    private String getSessionUser(HttpExchange t) {
        SessionInfo session = resolveSession(t);
        return session == null ? null : session.username;
    }

    private void setSession(HttpExchange t, String username) {
        WebAuth.UserInfo userInfo = auth.getUsers().get(username);
        String bridgeSecret = (userInfo != null && userInfo.bridgeUser())
                ? plugin.getConfig().getString("bridge.secret", "")
                : null;
        setSession(t, username, bridgeSecret);
    }

    private void setSession(HttpExchange t, String username, String bridgeSecretSnapshot) {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        String token = HttpSecurity.newSessionToken();
        boolean bridgeBound = bridgeSecretSnapshot != null && !bridgeSecretSnapshot.isBlank();
        sessions.put(token, new SessionInfo(username, now + SESSION_TTL_MS, bridgeBound,
                bridgeSecretSnapshot));
        t.getResponseHeaders().add(
                "Set-Cookie",
                SESSION_COOKIE_NAME + "=" + token + "; Path=/; Max-Age=3600; HttpOnly; SameSite=Lax"
                        + HttpSecurity.secureCookieSuffix(t, configuredHttps()));
    }

    public void createAuthenticatedSession(HttpExchange t, String username) {
        setSession(t, username);
    }

    private SessionInfo resolveSession(HttpExchange t) {
        String token = getSessionToken(t);
        if (token == null) {
            return null;
        }

        SessionInfo session = sessions.get(token);
        if (session == null) {
            return null;
        }

        if (session.expiresAt <= System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }

        if (session.bridgeBound && !bridgeSecretStillValid(session.bridgeSecretSnapshot)) {
            sessions.remove(token);
            return null;
        }

        return session;
    }

    private boolean bridgeSecretStillValid(String snapshot) {
        String current = plugin.getConfig().getString("bridge.secret", "");
        if (current == null || current.isBlank() || snapshot == null || snapshot.isBlank()) {
            return false;
        }
        return BridgeSecurity.equalsConstantTime(snapshot.trim().getBytes(StandardCharsets.UTF_8),
                current.trim().getBytes(StandardCharsets.UTF_8));
    }

    private String getSessionToken(HttpExchange t) {
        String cookie = t.getRequestHeaders().getFirst("Cookie");
        if (cookie == null || cookie.isBlank()) {
            return null;
        }
        String[] parts = cookie.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith(SESSION_COOKIE_NAME + "=")) {
                String value = trimmed.substring((SESSION_COOKIE_NAME + "=").length()).trim();
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    /**
     * Drops every session belonging to a user. Permission lookups already fail
     * closed once the account is gone, but without this the deleted user keeps
     * an authenticated session — and the panel around it — until the token's
     * own TTL runs out.
     */
    private void invalidateSessionsFor(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        sessions.values().removeIf(session -> username.equalsIgnoreCase(session.username));
    }

    private void clearSession(HttpExchange t) {
        String token = getSessionToken(t);
        if (token != null) {
            sessions.remove(token);
        }
        t.getResponseHeaders().add(
                "Set-Cookie",
                SESSION_COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
                        + HttpSecurity.secureCookieSuffix(t, configuredHttps()));
    }

    private boolean bootstrapBridgeSessionFromSignedQuery(HttpExchange t, String path) throws IOException {
        if (isAuthenticated(t)) {
            return false;
        }

        if (!plugin.getConfig().getBoolean("bridge.enabled", true)) {
            return false;
        }

        String rawQuery = t.getRequestURI().getRawQuery();
        String user = getQueryParam(rawQuery, "user");
        String timestampRaw = getQueryParam(rawQuery, "timestamp");
        String signature = getQueryParam(rawQuery, "signature");

        // Only treat this as an SSO bootstrap attempt when all signed parameters are present.
        if (signature == null || user == null || timestampRaw == null) {
            return false;
        }

        if (user.isBlank()) {
            redirect(t, "/login?error=sso_invalid");
            return true;
        }
        String normalizedUser = user.trim();

        String bridgeSecret = plugin.getConfig().getString("bridge.secret", "").trim();
        if (bridgeSecret.isBlank()) {
            redirect(t, "/login?error=sso_invalid");
            return true;
        }

        long incomingTimestamp;
        try {
            incomingTimestamp = Long.parseLong(timestampRaw);
        } catch (NumberFormatException ex) {
            redirect(t, "/login?error=sso_invalid");
            return true;
        }

        long now = Instant.now().getEpochSecond();
        long timeDelta = now - incomingTimestamp;
        if (Math.abs(timeDelta) > SSO_SIGNATURE_MAX_AGE_SECONDS) {
            redirect(t, "/login?error=sso_expired");
            return true;
        }

        String localHmacInput = normalizedUser.toLowerCase(Locale.ROOT) + ":" + timestampRaw;
        String expected = hmacSha256Hex(localHmacInput, bridgeSecret);
        String provided = BridgeSecurity.normalizeHex(signature);
        if (expected == null
                || provided.length() != expected.length()
                || !BridgeSecurity.equalsConstantTime(expected, provided)
                || isReplaySignature(provided, System.currentTimeMillis())) {
            redirect(t, "/login?error=sso_invalid");
            return true;
        }

        WebAuth.BridgeSsoResult result = auth.getOrCreateBridgeUserForSso(normalizedUser);
        String sessionUser = result.username() == null ? normalizedUser : result.username();
        WebAuth.UserInfo bridgeUserInfo = auth.getUsers().get(sessionUser);
        boolean pendingBridgeUser = bridgeUserInfo != null && bridgeUserInfo.bridgeUser() && !bridgeUserInfo.bridgeApproved();
        if (!result.approved() || pendingBridgeUser || !isApprovedBridgeUser(sessionUser)) {
            // Only send users to waiting-room after we can see the pending bridge entry in persisted auth data.
            if (bridgeUserInfo == null || !bridgeUserInfo.bridgeUser()) {
                redirect(t, "/login?error=sso_invalid");
                return true;
            }
            redirect(t, "/waiting-room?user=" + encodeForQuery(normalizedUser));
            return true;
        }

        persistNeoDashReturnUrlsFromQuery(rawQuery);
        setSession(t, sessionUser, bridgeSecret);
        String cleanedQuery = stripAuthBootstrapParams(rawQuery);
        String redirectPath = path;
        if (cleanedQuery != null && !cleanedQuery.isBlank()) {
            redirectPath = path + "?" + cleanedQuery;
        }
        if ("/login".equals(path) && (cleanedQuery == null || cleanedQuery.isBlank())) {
            redirectPath = "/";
        }
        redirect(t, redirectPath);
        return true;
    }

    private String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isReplaySignature(String signature, long now) {
        cleanupUsedSignatures(now);
        Long existing = usedSsoSignatures.putIfAbsent(signature, now);
        return existing != null && (now - existing) <= (SSO_SIGNATURE_MAX_AGE_SECONDS * 1000L);
    }

    private void cleanupUsedSignatures(long now) {
        usedSsoSignatures.entrySet().removeIf(e -> (now - e.getValue()) > (SSO_SIGNATURE_MAX_AGE_SECONDS * 1000L));
    }

    private boolean isApprovedBridgeUser(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        WebAuth.UserInfo info = auth.getUsers().get(username);
        return info != null && info.bridgeUser() && info.bridgeApproved();
    }

    private String stripAuthBootstrapParams(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        String[] parts = rawQuery.split("&");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String[] pair = part.split("=", 2);
            String key = pair.length > 0 ? pair[0] : "";
            if ("user".equals(key) || "timestamp".equals(key) || "signature".equals(key) || "token".equals(key)
                    || "master_url".equals(key) || "restart_url".equals(key)) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append("&");
            }
            out.append(part);
        }
        return out.toString();
    }

    private void persistNeoDashReturnUrlsFromQuery(String rawQuery) {
        String masterUrl = getQueryParam(rawQuery, "master_url");
        String restartUrl = getQueryParam(rawQuery, "restart_url");
        boolean changed = false;
        if (isSafePanelUrl(masterUrl)) {
            plugin.getConfig().set("bridge.master_url", masterUrl.trim());
            changed = true;
        }
        if (isSafePanelUrl(restartUrl)) {
            plugin.getConfig().set("bridge.restart_url", restartUrl.trim());
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
    }

    private boolean isSafePanelUrl(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private String resolveActionReturnTarget(String requested, String referer) {
        String direct = safeLocalReturnTarget(requested);
        if (direct != null) {
            return direct;
        }
        if (referer != null && !referer.isBlank()) {
            try {
                java.net.URI uri = java.net.URI.create(referer.trim());
                String path = uri.getRawPath();
                String candidate = (path == null || path.isBlank() ? "/" : path)
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                String local = safeLocalReturnTarget(candidate);
                if (local != null) {
                    return local;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return "/";
    }

    private String safeLocalReturnTarget(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.startsWith("/") && !candidate.startsWith("//")
                && !candidate.contains("\\") && !candidate.contains("\r") && !candidate.contains("\n")) {
            return candidate;
        }
        return null;
    }

    private String withActionMessage(String returnTarget, String message) {
        String target = safeLocalReturnTarget(returnTarget);
        if (target == null) {
            target = "/";
        }
        int queryIndex = target.indexOf('?');
        String path = queryIndex < 0 ? target : target.substring(0, queryIndex);
        return path + "?msg=" + encodeForQuery(message == null ? "" : message);
    }

    private String getClientIp(HttpExchange t) {
        return t.getRemoteAddress().getAddress().getHostAddress();
    }

    private boolean ensurePermission(HttpExchange t, String permission, boolean jsonResponse) throws IOException {
        String feature = FeatureFlags.featureForPath(t.getRequestURI().getPath());
        if (feature != null && !FeatureFlags.enabled(feature)) {
            sendResponseWithStatus(t, 404, jsonResponse ? "{\"success\":false,\"error\":\"Feature disabled\"}" : "Feature disabled");
            return false;
        }
        if (!isAuthenticated(t)) {
            t.sendResponseHeaders(403, 0);
            t.close();
            return false;
        }
        if (!ensureSameOriginMutation(t, jsonResponse)) {
            return false;
        }

        String username = getSessionUser(t);
        if (username == null || !userHasWebPermission(username, permission)) {
            WebActionLogger.log("ACCESS_DENIED",
                    "user=" + (username == null ? "anonymous" : username) + " ip=" + getClientIp(t)
                            + " path=" + t.getRequestURI().getPath() + " required=" + permission);
            t.getResponseHeaders().add("Content-Type", jsonResponse ? "application/json" : "text/html");
            if (jsonResponse) {
                sendResponseWithStatus(t, 403, "{\"success\": false, \"error\": \"Forbidden\"}");
            } else {
                sendResponseWithStatus(t, 403,
                        "<html><body style='background:#0f172a;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh'><div><h1>403 Forbidden</h1><p>Missing permission: "
                                + permission + "</p></div></body></html>");
            }
            return false;
        }

        return true;
    }

    private boolean ensureAnyPermission(HttpExchange t, boolean jsonResponse, String... permissions) throws IOException {
        if (!isAuthenticated(t)) {
            t.sendResponseHeaders(403, 0);
            t.close();
            return false;
        }
        if (!ensureSameOriginMutation(t, jsonResponse)) {
            return false;
        }

        String username = getSessionUser(t);
        if (username != null) {
            for (String permission : permissions) {
                if (permission != null && !permission.isBlank() && userHasWebPermission(username, permission)) {
                    return true;
                }
            }
        }

        String required = String.join(" OR ", Arrays.stream(permissions)
                .filter(p -> p != null && !p.isBlank())
                .toList());
        WebActionLogger.log("ACCESS_DENIED",
                "user=" + (username == null ? "anonymous" : username) + " ip=" + getClientIp(t)
                        + " path=" + t.getRequestURI().getPath() + " requiredAny=" + required);
        t.getResponseHeaders().add("Content-Type", jsonResponse ? "application/json" : "text/html");
        if (jsonResponse) {
            sendResponseWithStatus(t, 403, "{\"success\": false, \"error\": \"Forbidden\"}");
        } else {
            sendResponseWithStatus(t, 403,
                    "<html><body style='background:#0f172a;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh'><div><h1>403 Forbidden</h1><p>Missing permission: "
                            + required + "</p></div></body></html>");
        }
        return false;
    }

    private boolean ensureBridgeBearer(HttpExchange t) throws IOException {
        if (!plugin.getConfig().getBoolean("bridge.enabled", true)) {
            sendResponseWithStatus(t, 404, "Bridge disabled");
            return false;
        }

        String authHeader = t.getRequestHeaders().getFirst("Authorization");
        String secret = plugin.getConfig().getString("bridge.secret", "");
        if (!BridgeSecurity.bearerMatchesSecret(authHeader, secret)) {
            sendResponseWithStatus(t, 401, "Unauthorized");
            return false;
        }
        return true;
    }

    private boolean isBridgeBearerAuthorized(HttpExchange t) {
        if (!plugin.getConfig().getBoolean("bridge.enabled", true)) {
            return false;
        }
        String authHeader = t.getRequestHeaders().getFirst("Authorization");
        String secret = plugin.getConfig().getString("bridge.secret", "");
        return BridgeSecurity.bearerMatchesSecret(authHeader, secret);
    }

    private class PageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            HttpSecurity.ensureContentType(t.getResponseHeaders(), HttpSecurity.HTML_UTF8);
            String path = t.getRequestURI().getPath();
            String query = t.getRequestURI().getQuery();

            if (bootstrapBridgeSessionFromSignedQuery(t, path)) {
                return;
            }

            if (path.equals("/status")) {
                if (!FeatureFlags.enabled("intelligence")) {
                    sendResponseWithStatus(t, 404, "Feature disabled");
                    return;
                }
                sendResponse(t, IntelligencePage.renderPublicStatus(
                        intelligenceManager.publicStatus(), "Paper server", 0L, List.of()));
                return;
            }

            if (path.equals("/report")) {
                if (!FeatureFlags.enabled("tickets")) {
                    sendResponseWithStatus(t, 404, "Reports are disabled");
                    return;
                }
                String token = getQueryParam(query, "token");
                String message = null;
                if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                    Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 24L * 1024L), StandardCharsets.UTF_8));
                    token = params.get("token");
                    message = PublicReportLinks.submit(plugin.getDataFolder().toPath(), token,
                            params.get("title"), params.get("body"), params.get("category"),
                            params.get("target_player"), params.get("reporter"), params.get("contact"), params.get("website"));
                    if (message.startsWith("Report submitted")) {
                        notifyIngameTicket(params.get("reporter"), params.get("target_player"));
                    }
                    WebActionLogger.log("PUBLIC_REPORT_SUBMIT", "result=" + message.replaceAll("[^A-Za-z ]", ""));
                } else if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                    sendResponseWithStatus(t, 405, "Method not allowed");
                    return;
                }
                sendResponse(t, PublicReportLinks.render(plugin.getDataFolder().toPath(), token, message));
                return;
            }

            if (path.equals("/setup")) {
                String code = null;
                String msg = null;
                if (query != null && query.startsWith("code=")) {
                    code = URLDecoder.decode(query.substring(5), "UTF-8");
                }
                msg = getQueryParam(query, "msg");
                sendResponse(t, SetupPage.render(code, auth.isSetupRequired(), msg));
                return;
            }

            if (path.equals("/waiting-room")) {
                String pendingUser = getQueryParam(query, "user");
                if (pendingUser != null && !pendingUser.isBlank()) {
                    // Ensure pending bridge entries exist once a verified SSO user lands in waiting room.
                    auth.getOrCreateBridgeUserForSso(pendingUser.trim());
                }
                sendResponse(t, WaitingRoomPage.render(pendingUser));
                return;
            }

            if (auth.isSetupRequired()) {
                sendResponse(t, SetupPage.render(null, true, null));
                return;
            }

            if (path.equals("/login")) {
                serveLogin(t);
                return;
            }

            if (!isAuthenticated(t)) {
                serveLogin(t);
                return;
            }

            String sessionUser = getSessionUser(t);
            String html;
            // Set inside the try: these are thread locals on a pooled request
            // thread, so anything that throws before the finally is reached
            // would leave one request's permissions visible to the next.
            try {
                HtmlTemplate.setUiPermissions(effectiveUiPermissions(sessionUser));
                HtmlTemplate.setUiUser(sessionUser);
                WebAuth.UserInfo sessionUserInfo = auth.getUsers().get(sessionUser);
                boolean sessionIsBridgeUser = sessionUserInfo != null && sessionUserInfo.bridgeUser();
                String bridgeMasterUrl = plugin.getConfig().getString("bridge.master_url", "");
                HtmlTemplate.setBridgeContext(sessionIsBridgeUser, bridgeMasterUrl);
                HtmlTemplate.setUiLanguage(auth.getUserLanguage(sessionUser));

                String requestedFeature = FeatureFlags.featureForPath(path);
                if (requestedFeature != null && !FeatureFlags.enabled(requestedFeature)) {
                    redirect(t, "/settings?msg=" + encodeForQuery(
                            "The " + requestedFeature + " feature is disabled in Settings."));
                    return;
                }
                if (path.equals("/")) {
                    if (!ensurePermission(t, "dash.web.stats.read", false))
                        return;
                    html = DashboardPage.render();
                } else if (path.equals("/console")) {
                    if (!ensurePermission(t, "dash.web.console.read", false))
                        return;
                    html = dash.web.ConsolePage.render();
                } else if (path.equals("/players")) {
                    if (!ensurePermission(t, "dash.web.players.read", false))
                        return;
                    html = PlayersPage.render(query, auth);
                } else if (path.startsWith("/players/") && path.endsWith("/inventory")) {
                    if (!ensurePermission(t, "dash.web.players.read", false))
                        return;
                    String playerName = path.replace("/players/", "").replace("/inventory", "");
                    html = InventoryPage.render(playerName);
                } else if (path.startsWith("/players/") && path.endsWith("/enderchest")) {
                    if (!ensurePermission(t, "dash.web.players.read", false))
                        return;
                    String playerName = path.replace("/players/", "").replace("/enderchest", "");
                    html = InventoryPage.renderEnderChest(playerName);
                } else if (path.equals("/plugins")) {
                    if (!ensurePermission(t, "dash.web.plugins.read", false))
                        return;
                    html = PluginsPage.render();
                } else if (path.equals("/plugin-browser")) {
                    if (!ensurePermission(t, "dash.web.plugins.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    html = PluginBrowserPage.render(msg);
                } else if (path.equals("/operations")) {
                    redirect(t, "/intelligence");
                    return;
                } else if (path.equals("/intelligence")) {
                    if (!ensurePermission(t, "dash.web.intelligence.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    try {
                        html = IntelligencePage.render(
                                intelligenceManager,
                                msg,
                                query,
                                userHasWebPermission(sessionUser, "dash.web.intelligence.write"),
                                userHasWebPermission(sessionUser, "dash.web.users.manage"),
                                sessionUser,
                                0L,
                                List.of(),
                                intelligenceRuntimeMetrics(),
                                null,
                                null);
                    } catch (RuntimeException | LinkageError ex) {
                        String incident = java.util.UUID.randomUUID().toString().substring(0, 8);
                        plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                "Intelligence page render failed (incident " + incident + ")", ex);
                        html = IntelligencePage.renderUnavailable(incident);
                    }
                } else if (path.equals("/ai")) {
                    if (!ensurePermission(t, "dash.web.ai.read", false))
                        return;
                    WebAuth.UserInfo aiUser = auth.getUsers().get(sessionUser);
                    String aiRole = aiUser == null ? "USER" : aiUser.role();
                    html = AiPage.render(aiAgentManager, sessionUser, aiRole,
                            auth.isMainAdmin(sessionUser),
                            userHasWebPermission(sessionUser, "dash.web.ai.audit"),
                            getQueryParam(query, "conversation"), getQueryParam(query, "msg"),
                            "1".equals(getQueryParam(query, "setup")));
                } else if (path.equals("/maintenance")) {
                    if (!ensurePermission(t, "dash.web.settings.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    html = MaintenancePage.render(msg);
                } else if (path.equals("/doctor")) {
                    redirect(t, "/ai");
                    return;
                } else if (path.equals("/staff")) {
                    if (!ensurePermission(t, "dash.web.stats.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    html = StaffPage.render(msg, query);
                } else if (path.equals("/notifications")) {
                    if (!ensurePermission(t, "dash.web.pluginsettings.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    html = NotificationSettingsPage.render(msg);
                } else if (path.equals("/graphs")) {
                    if (!ensurePermission(t, "dash.web.stats.read", false))
                        return;
                    html = GraphsPage.render();
                } else if (path.equals("/guardian")) {
                    if (!ensurePermission(t, "dash.web.guardian.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    html = GuardianPage.render(msg);
                } else if (path.equals("/users")) {
                    if (!ensurePermission(t, "dash.web.users.manage", false))
                        return;
                    auth.reload();
                    String inviteCode = getQueryParam(query, "code");
                    String message = getQueryParam(query, "msg");
                    html = UsersPage.render(auth.getUsers(), auth.getRoleNames(), auth.getRoleValues(), sessionUser,
                            auth.isMainAdmin(sessionUser), inviteCode, message,
                            Dash.getRegistrationApprovalManager() == null ? List.of()
                                    : Dash.getRegistrationApprovalManager().listPending(),
                            auth.getPendingBridgeUsers());
                } else if (path.equals("/permissions")) {
                    if (!ensurePermission(t, "dash.web.users.manage", false))
                        return;
                    auth.reload();
                    String selectedRole = getQueryParam(query, "role");
                    String message = getQueryParam(query, "msg");
                    html = PermissionsPage.render(auth.getRolesWithPermissions(), auth.getRoleValues(), selectedRole,
                            message, auth.isMainAdmin(sessionUser), auth.getActorRoleValue(sessionUser));
                } else if (path.equals("/settings")) {
                    if (!ensurePermission(t, "dash.web.settings.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    try {
                        html = SettingsPage.render(sessionUser, auth.isMainAdmin(sessionUser), msg);
                    } catch (Exception ex) {
                        plugin.getLogger().warning("Failed to render settings page: " + ex.getMessage());
                        html = HtmlTemplate.page("Settings", "/settings",
                                "<main class=\"p-4 sm:p-6 flex-1 w-full\"><div class=\"rounded-2xl border border-rose-500/30 bg-rose-500/10 p-4 text-rose-200\">"
                                        + "Settings are temporarily unavailable on this server type. Check missing config files and try again."
                                        + "</div></main>");
                    }
                } else if (path.equals("/audit")) {
                    if (!ensurePermission(t, "dash.web.audit.read", false))
                        return;
                    String searchQ = getQueryParam(query, "q");
                    html = AuditLogPage.render(searchQ);
                } else if (path.equals("/plugin-settings")) {
                    if (!ensurePermission(t, "dash.web.pluginsettings.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    html = PluginSettingsPage.render(msg);
                } else if (path.equals("/scheduled-tasks")) {
                    if (!ensurePermission(t, "dash.web.tasks.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    html = ScheduledTasksPage.render(msg);
                } else if (path.equals("/updates")) {
                    if (!ensurePermission(t, "dash.web.settings.read", false))
                        return;
                    GithubUpdater updater = Dash.getGithubUpdater();
                    String currentVersion = plugin.getDescription() != null ? plugin.getDescription().getVersion() : "unknown";
                    String latestVersion = currentVersion;
                    boolean updateAvailable = false;
                    boolean updatePrepared = false;
                    boolean updaterEnabled = updater != null && updater.isEnabled();
                    if (updaterEnabled) {
                        latestVersion = updater.getLatestVersion();
                        updateAvailable = updater.isUpdateAvailable();
                        updatePrepared = updater.isUpdatePrepared();
                    }
                    html = UpdatesPage.render(currentVersion, latestVersion, updateAvailable, updatePrepared,
                            updaterEnabled);
                } else if (path.equals("/files")) {
                    if (!ensurePermission(t, "dash.web.files.read", false))
                        return;
                    String filePath = query != null && query.startsWith("path=")
                            ? URLDecoder.decode(query.substring(5), "UTF-8")
                            : "";
                    html = FilesPage.render(filePath);
                } else if (path.equals("/files/edit")) {
                    if (!ensurePermission(t, "dash.web.files.read", false))
                        return;
                    String filePath = query != null && query.startsWith("path=")
                            ? URLDecoder.decode(query.substring(5), "UTF-8")
                            : "";
                    html = FilesPage.renderEditor(filePath);
                } else if (path.startsWith("/players/") && path.endsWith("/profile")) {
                    if (!ensurePermission(t, "dash.web.players.read", false))
                        return;
                    String playerName = path.replace("/players/", "").replace("/profile", "");
                    html = PlayerProfilePage.render(playerName);
                } else if (path.startsWith("/players/") && path.endsWith("/teleport")) {
                    if (!ensurePermission(t, "dash.web.players.moderate", false))
                        return;
                    String playerName = path.replace("/players/", "").replace("/teleport", "");
                    html = TeleportPage.render(playerName);
                } else if (path.startsWith("/players/") && !path.contains("/")) {
                    if (!ensurePermission(t, "dash.web.players.read", false))
                        return;
                    String playerName = path.replace("/players/", "");
                    if (!playerName.isEmpty()) {
                        html = PlayerProfilePage.render(playerName);
                    } else {
                        html = PlayersPage.render(query, auth);
                    }
                } else {
                    html = DashboardPage.render();
                }
            } finally {
                HtmlTemplate.clearUiPermissions();
                HtmlTemplate.clearBridgeContext();
                HtmlTemplate.clearUiLanguage();
                HtmlTemplate.clearUiUser();
            }

            sendResponse(t, html);
        }
    }

    private void serveRegistration(HttpExchange t) throws IOException {
        String html = "<!DOCTYPE html><html class=\"dark\" lang=\"en\"><head><meta charset=\"utf-8\"/><title>Dash Setup</title>"
                +
                "<link rel=\"stylesheet\" href=\"/assets/dash-4.3.css\"></head><body class=\"bg-slate-900 text-white flex items-center justify-center h-screen\">"
                +
                "<div class=\"bg-slate-800 p-8 rounded-xl shadow-2xl w-96 border border-slate-700\">" +
                "<h2 class=\"text-2xl font-bold mb-6 text-center text-sky-400\">Dash Admin Setup</h2>" +
                "<p class=\"text-sm text-slate-400 mb-4 text-center\">Enter the registration code from /dash register</p>"
                +
                "<form action=\"/action\" method=\"post\" class=\"flex flex-col gap-4\">" +
                "<input type=\"hidden\" name=\"action\" value=\"register\">" +
                "<div><label class=\"text-sm text-slate-400\">Registration Code</label><input type=\"text\" name=\"code\" required placeholder=\"XXXXXXXX\" class=\"w-full bg-slate-900 border border-slate-600 rounded p-2 focus:border-sky-500 outline-none uppercase tracking-widest text-center font-mono\"></div>"
                +
                "<div><label class=\"text-sm text-slate-400\">Username</label><input type=\"text\" name=\"username\" required class=\"w-full bg-slate-900 border border-slate-600 rounded p-2 focus:border-sky-500 outline-none\"></div>"
                +
                "<div><label class=\"text-sm text-slate-400\">Password</label><input type=\"password\" name=\"password\" required class=\"w-full bg-slate-900 border border-slate-600 rounded p-2 focus:border-sky-500 outline-none\"></div>"
                +
                "<button type=\"submit\" class=\"bg-sky-500 hover:bg-sky-600 text-white font-bold py-2 rounded transition\">Complete Setup</button>"
                +
                "</form></div></body></html>";
        sendResponse(t, html);
    }

    private void serveLogin(HttpExchange t) throws IOException {
        String error = getQueryParam(t.getRequestURI().getRawQuery(), "error");
        sendResponse(t, LoginPage.render(error));
    }

    private class ConsoleApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.console.read", true)) {
                return;
            }
            List<String> logs = ConsoleLogAppender.getLogs();
            String response = String.join("\n", logs);
            sendResponse(t, response);
        }
    }

    private class StatsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                    t.sendResponseHeaders(405, -1);
                    t.close();
                    return;
                }

                // During first-run setup, allow minimal telemetry for wrapper panels.
                if (!isSetupTelemetryBypass(t)) {
                    // Keep standalone panel behavior (session+permission), but also allow bridge bearer auth.
                    String authHeader = t.getRequestHeaders().getFirst("Authorization");
                    String bridgeSecret = plugin.getConfig().getString("bridge.secret", "");
                    boolean bridgeAuthorized = BridgeSecurity.bearerMatchesSecret(authHeader, bridgeSecret);
                    if (!bridgeAuthorized && !ensurePermission(t, "dash.web.stats.read", true)) {
                        return;
                    }
                }

                double currentTps = 20.0;
                try {
                    double[] tps = Bukkit.getTPS();
                    currentTps = (tps != null && tps.length > 0) ? tps[0] : 20.0;
                    if (currentTps > 20.0)
                        currentTps = 20.0;
                } catch (Throwable ignored) {
                }

                long maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024;
                long totalMem = Runtime.getRuntime().totalMemory() / 1024 / 1024;
                long freeMem = Runtime.getRuntime().freeMemory() / 1024 / 1024;
                long usedMem = totalMem - freeMem;

                double cpuPercent = 0.0;
                try {
                    java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory
                            .getOperatingSystemMXBean();
                    if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                        double load = ((com.sun.management.OperatingSystemMXBean) osBean).getSystemCpuLoad();
                        cpuPercent = (load < 0.0) ? 0.0 : (load * 100.0);
                    }
                } catch (Exception e) {
                    // Ignore fallback to 0.0
                }

                String uptimeStr = formatUptime();
                String dashVersion = plugin.getDescription() != null ? plugin.getDescription().getVersion() : "unknown";

                StatsCollector collector = Dash.getStatsCollector();
                StatsCollector.StatsSample latestSample = collector != null ? collector.getLatest() : null;
                double mspt = latestSample != null ? latestSample.mspt : (currentTps > 0 ? 1000.0 / currentTps : 50.0);
                int overworldChunks = latestSample != null ? latestSample.overworldChunks : 0;
                int netherChunks = latestSample != null ? latestSample.netherChunks : 0;
                int endChunks = latestSample != null ? latestSample.endChunks : 0;

                // Keep legacy keys for the local dashboard and add camelCase keys for NeoDash.
                String json = String.format(
                        "{\"tps\": %.2f, \"ram_used\": %d, \"ram_max\": %d, \"ramUsed\": %d, \"ramMax\": %d, \"cpu_percent\": %.2f, \"cpuPercent\": %.2f, \"cpuUsage\": %.2f, \"uptime\": \"%s\", \"dashVersion\": \"%s\", \"overworld_chunks\": %d, \"nether_chunks\": %d, \"end_chunks\": %d, \"mspt\": %.2f}",
                        currentTps, usedMem, maxMem, usedMem, maxMem, cpuPercent, cpuPercent, cpuPercent,
                        jsonEscape(uptimeStr), jsonEscape(dashVersion),
                        overworldChunks, netherChunks, endChunks, mspt);

                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, json);
            } catch (Exception e) {
                sendResponse(t, "{\"error\": \"Internal Error\"}");
            }
        }
    }

    private class HealthApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            if (!isSetupTelemetryBypass(t)) {
                String authHeader = t.getRequestHeaders().getFirst("Authorization");
                String secret = plugin.getConfig().getString("bridge.secret", "");
                if (!BridgeSecurity.bearerMatchesSecret(authHeader, secret)) {
                    sendResponseWithStatus(t, 401, "{\"error\":\"Unauthorized\"}");
                    return;
                }
            }

            String dashVersion = plugin.getDescription() != null ? plugin.getDescription().getVersion() : "unknown";
            String json = "{\"status\":\"online\",\"uptime\":\"" + jsonEscape(formatUptime())
                    + "\",\"dashVersion\":\"" + jsonEscape(dashVersion) + "\"}";
            t.getResponseHeaders().add("Content-Type", "application/json");
            sendResponse(t, json);
        }
    }

    private class LogoutApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod()) && !"GET".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            clearSession(t);
            t.getResponseHeaders().add("Content-Type", "application/json");
            sendResponse(t, "{\"success\":true}");
        }
    }

    private class StatsHistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.stats.read", true)) {
                return;
            }
            try {
                StatsCollector collector = Dash.getStatsCollector();
                String range = getQueryParam(t.getRequestURI().getRawQuery(), "range");
                long span = graphRangeMillis(range);
                String json = collector != null
                        ? collector.getHistoryJson(System.currentTimeMillis() - span, 1_200)
                        : "[]";
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, json);
            } catch (Exception e) {
                sendResponse(t, "[]");
            }
        }
    }

    private long graphRangeMillis(String range) {
        if ("7d".equals(range)) return TimeUnit.DAYS.toMillis(7);
        if ("24h".equals(range)) return TimeUnit.HOURS.toMillis(24);
        if ("6h".equals(range)) return TimeUnit.HOURS.toMillis(6);
        return TimeUnit.HOURS.toMillis(1);
    }

    private class GuardianApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String path = t.getRequestURI().getPath();
            t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            boolean bridgeAuthorized = isBridgeBearerAuthorized(t);
            if (path.startsWith("/api/guardian/export/")) {
                if (!bridgeAuthorized && !ensurePermission(t, "dash.web.guardian.export", true)) return;
                handleGuardianExport(t, path);
                return;
            }
            if (path.equals("/api/guardian/coreprotect/import")) {
                if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                    sendResponseWithStatus(t, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}");
                    return;
                }
                if (!bridgeAuthorized && !ensurePermission(t, "dash.web.guardian.import", true)) return;
                handleCoreProtectImport(t);
                return;
            }
            if (path.equals("/api/guardian/rollback") || path.equals("/api/guardian/restore")) {
                if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                    sendResponseWithStatus(t, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}");
                    return;
                }
                if (bridgeAuthorized) {
                    // NeoDash has already checked the human user's Guardian action permission.
                } else if (path.endsWith("/restore")) {
                    if (!ensureAnyPermission(t, true, "dash.web.guardian.restore", "dash.web.guardian.rollback",
                            "dash.web.guardian.manage")) return;
                } else if (!ensureAnyPermission(t, true, "dash.web.guardian.rollback", "dash.web.guardian.manage")) {
                    return;
                }
                handleGuardianAction(t, path.endsWith("/restore") ? "restore" : "rollback");
                return;
            }
            if (path.equals("/api/guardian/purge")) {
                if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                    sendResponseWithStatus(t, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}");
                    return;
                }
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.purge", "dash.web.guardian.manage")) return;
                handleGuardianPurge(t);
                return;
            }
            if (path.equals("/api/guardian/cases") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.cases", "dash.web.guardian.manage")) return;
                handleGuardianCaseCreate(t);
                return;
            }
            if (path.equals("/api/guardian/cases/update") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.cases", "dash.web.guardian.manage")) return;
                handleGuardianCaseUpdate(t);
                return;
            }
            if (path.equals("/api/guardian/cases/evidence") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.cases", "dash.web.guardian.manage")) return;
                handleGuardianEvidenceAdd(t);
                return;
            }
            if (path.equals("/api/guardian/player-notes") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.notes", "dash.web.guardian.manage")) return;
                handleGuardianPlayerNoteSave(t);
                return;
            }
            if (path.equals("/api/guardian/player-notes/delete") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.notes", "dash.web.guardian.manage")) return;
                handleGuardianPlayerNoteDelete(t);
                return;
            }
            if (path.equals("/api/guardian/saved-filters") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.filters", "dash.web.guardian.manage")) return;
                handleGuardianFilterSave(t);
                return;
            }
            if (path.equals("/api/guardian/saved-filters/delete") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.filters", "dash.web.guardian.manage")) return;
                handleGuardianFilterDelete(t);
                return;
            }
            if (path.equals("/api/guardian/protected-regions") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized && !ensurePermission(t, "dash.web.guardian.manage", true)) return;
                handleGuardianProtectedRegionSave(t);
                return;
            }
            if (path.equals("/api/guardian/protected-regions/delete") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized && !ensurePermission(t, "dash.web.guardian.manage", true)) return;
                handleGuardianProtectedRegionDelete(t);
                return;
            }
            if (path.equals("/api/guardian/alert-rules") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized && !ensurePermission(t, "dash.web.guardian.manage", true)) return;
                handleGuardianAlertRuleSave(t);
                return;
            }
            if (path.equals("/api/guardian/alert-rules/delete") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized && !ensurePermission(t, "dash.web.guardian.manage", true)) return;
                handleGuardianAlertRuleDelete(t);
                return;
            }
            if (path.equals("/api/guardian/alert-rules/evaluate") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.cases", "dash.web.guardian.manage")) return;
                handleGuardianAlertRuleEvaluate(t);
                return;
            }
            if (path.equals("/api/guardian/retention") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.purge", "dash.web.guardian.manage")) return;
                handleGuardianRetentionSave(t);
                return;
            }
            if (path.equals("/api/guardian/retention/apply") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.purge", "dash.web.guardian.manage")) return;
                handleGuardianRetentionApply(t);
                return;
            }
            if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                sendResponseWithStatus(t, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}");
                return;
            }
            if (!bridgeAuthorized && !ensurePermission(t, "dash.web.guardian.read", true)) return;

            GuardianDataManager guardian = Dash.getGuardianDataManager();
            if (guardian == null) {
                sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
                return;
            }

            String query = t.getRequestURI().getRawQuery();
            if (path.equals("/api/guardian") || path.equals("/api/guardian/stats")) {
                sendResponse(t, guardianStatsJson(guardian.getServerStats()));
            } else if (path.equals("/api/guardian/status")) {
                sendResponse(t, guardianStatusJson(guardian.getStatus()));
            } else if (path.equals("/api/guardian/logs/blocks")) {
                sendResponse(t, guardianBlockLogsJson(guardian.searchBlockLogs(
                        getQueryParam(query, "player"),
                        getQueryParam(query, "world"),
                        sinceFromQuery(query),
                        null,
                        GuardianDataManager.parseBlockAction(getQueryParam(query, "action")),
                        parseInt(getQueryParam(query, "page"), 1),
                        parseInt(getQueryParam(query, "limit"), 50))));
            } else if (path.equals("/api/guardian/logs/containers")) {
                sendResponse(t, guardianContainerLogsJson(guardian.searchContainerLogs(
                        getQueryParam(query, "player"),
                        getQueryParam(query, "world"),
                        sinceFromQuery(query),
                        null,
                        GuardianDataManager.parseContainerAction(getQueryParam(query, "action")),
                        parseInt(getQueryParam(query, "page"), 1),
                        parseInt(getQueryParam(query, "limit"), 50))));
            } else if (path.equals("/api/guardian/lookup") || path.equals("/api/guardian/near")) {
                sendResponse(t, guardianLookupJson(guardian, query, path.equals("/api/guardian/near")));
            } else if (path.equals("/api/guardian/has-placed") || path.equals("/api/guardian/has-removed")) {
                sendResponse(t, guardianHasActionJson(guardian, query, path.endsWith("has-placed")));
            } else if (path.equals("/api/guardian/worlds")) {
                sendResponse(t, stringArrayJson(guardian.getDistinctWorlds()));
            } else if (path.equals("/api/guardian/timeline")) {
                long to = System.currentTimeMillis() / 1000L;
                long from = sinceFromQuery(query, 24);
                sendResponse(t, guardianTimelineJson(guardian.getTimelineStats(from, to)));
            } else if (path.equals("/api/guardian/timeline/events") || path.equals("/api/guardian/timeline/player")) {
                sendResponse(t, guardianTimelineEventsJson(guardian.searchTimeline(
                        getQueryParam(query, "q"),
                        getQueryParam(query, "player"),
                        getQueryParam(query, "world"),
                        sinceFromQuery(query),
                        null,
                        parseInt(getQueryParam(query, "limit"), 100))));
            } else if (path.equals("/api/guardian/cases")) {
                sendResponse(t, guardianCasesJson(guardian.listCases(
                        getQueryParam(query, "status"),
                        getQueryParam(query, "player"),
                        parseInt(getQueryParam(query, "limit"), 30))));
            } else if (path.equals("/api/guardian/cases/evidence")) {
                sendResponse(t, guardianEvidenceJson(guardian.listCaseEvidence(
                        parseLong(getQueryParam(query, "caseId"), 0L))));
            } else if (path.equals("/api/guardian/cases/bundle")) {
                sendResponse(t, guardianCaseBundleJson(guardian, parseLong(getQueryParam(query, "caseId"), 0L)));
            } else if (path.equals("/api/guardian/player-notes")) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.notes", "dash.web.guardian.manage")) return;
                sendResponse(t, guardianPlayerNotesJson(guardian.listPlayerNotes(
                        getQueryParam(query, "q"),
                        getQueryParam(query, "severity"),
                        parseInt(getQueryParam(query, "limit"), 20))));
            } else if (path.equals("/api/guardian/saved-filters")) {
                sendResponse(t, guardianFiltersJson(guardian.listSavedFilters()));
            } else if (path.equals("/api/guardian/incidents")) {
                long from = sinceFromQuery(query, 24);
                sendResponse(t, guardianIncidentsJson(guardian.listIncidents(from, parseInt(getQueryParam(query, "limit"), 20))));
            } else if (path.equals("/api/guardian/scores")) {
                long from = sinceFromQuery(query, 24);
                sendResponse(t, guardianScoresJson(guardian.listSuspicionScores(from, parseInt(getQueryParam(query, "limit"), 20))));
            } else if (path.equals("/api/guardian/preview-diff")) {
                sendResponse(t, guardianPreviewDiffJson(guardianPreviewDiff(guardian, query)));
            } else if (path.equals("/api/guardian/replay")) {
                sendResponse(t, guardianTimelineEventsJson(guardian.searchTimelineReplay(
                        getQueryParam(query, "q"),
                        getQueryParam(query, "player"),
                        getQueryParam(query, "world"),
                        sinceFromQuery(query),
                        null,
                        parseInt(getQueryParam(query, "limit"), 80))));
            } else if (path.equals("/api/guardian/container-restore-plan")) {
                sendResponse(t, guardianItemAmountsJson(guardian.containerRestorePlan(
                        getQueryParam(query, "player"),
                        getQueryParam(query, "world"),
                        sinceFromQuery(query),
                        parseOptionalInt(getQueryParam(query, "x")),
                        parseOptionalInt(getQueryParam(query, "y")),
                        parseOptionalInt(getQueryParam(query, "z")),
                        parseOptionalInt(getQueryParam(query, "radius")),
                        parseInt(getQueryParam(query, "limit"), 500))));
            } else if (path.equals("/api/guardian/protected-regions")) {
                sendResponse(t, guardianProtectedRegionsJson(guardian.listProtectedRegions()));
            } else if (path.equals("/api/guardian/protected-regions/hits")) {
                long from = sinceFromQuery(query, 24);
                sendResponse(t, guardianProtectedRegionHitsJson(
                        guardian.listProtectedRegionHits(from, parseInt(getQueryParam(query, "limit"), 20))));
            } else if (path.equals("/api/guardian/alert-rules")) {
                sendResponse(t, guardianAlertRulesJson(guardian.listAlertRules()));
            } else if (path.equals("/api/guardian/alert-rules/hits")) {
                sendResponse(t, guardianAlertHitsJson(guardian.evaluateAlertRules(false, actorLabel(t))));
            } else if (path.equals("/api/guardian/retention")) {
                sendResponse(t, guardianRetentionJson(guardian.getRetentionPolicy()));
            } else if (path.equals("/api/guardian/inbox")) {
                long from = sinceFromQuery(query, 24);
                sendResponse(t, guardianInboxJson(guardian.getInbox(from)));
            } else if (path.equals("/api/guardian/activity")) {
                sendResponse(t, guardianActivityJson());
            } else if (path.equals("/api/guardian/heatmap")) {
                long from = sinceFromQuery(query, 24);
                sendResponse(t, guardianHeatmapJson(guardian.getHeatmapData(from, parseInt(getQueryParam(query, "limit"), 50))));
            } else if (path.equals("/api/guardian/suspicious")) {
                long from = sinceFromQuery(query, 24);
                sendResponse(t, guardianSuspiciousJson(guardian.getSuspiciousPlayers(from)));
            } else if (path.equals("/api/guardian/peak-hours")) {
                long from = sinceFromQuery(query, 168);
                sendResponse(t, intIntMapJson(guardian.getPeakHoursData(from)));
            } else if (path.equals("/api/guardian/top-players")) {
                long from = sinceFromQuery(query, 168);
                sendResponse(t, guardianPlayerActivityJson(
                        guardian.getTopPlayersData(from, parseInt(getQueryParam(query, "limit"), 10))));
            } else if (path.equals("/api/guardian/block-types")) {
                long from = sinceFromQuery(query, 168);
                sendResponse(t, stringIntMapJson(guardian.getBlockTypesData(from, getQueryParam(query, "action"),
                        parseInt(getQueryParam(query, "limit"), 20))));
            } else if (path.equals("/api/guardian/custom")) {
                long from = sinceFromQuery(query, 168);
                sendResponse(t, guardianCustomStatsJson(guardian, from, getQueryParam(query, "action"),
                        parseInt(getQueryParam(query, "limit"), 10)));
            } else if (path.equals("/api/guardian/coreprotect/status")) {
                CoreProtectBridge.Status status = new CoreProtectBridge(plugin).status();
                sendResponse(t, "{\"available\":" + status.available()
                        + ",\"apiVersion\":" + status.apiVersion()
                        + ",\"message\":\"" + jsonEscape(status.message()) + "\"}");
            } else {
                sendResponseWithStatus(t, 404, "{\"success\":false,\"error\":\"not_found\"}");
            }
        }
    }

    private void handleCoreProtectImport(HttpExchange t) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        String body = new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8);
        Map<String, String> params = parseFormData(body);
        int hours = Math.max(1, Math.min(parseInt(params.get("hours"), 24), 2160));
        int limit = Math.max(1, Math.min(parseInt(params.get("limit"), 2000), 10000));
        CoreProtectBridge.ImportResult result = new CoreProtectBridge(plugin)
                .importRecent(guardian, hours * 3600, limit, params.get("player"));
        WebActionLogger.log("GUARDIAN_COREPROTECT_IMPORT",
                "user=" + getSessionUser(t) + " hours=" + hours + " limit=" + limit
                        + " blocks=" + result.blocksImported() + " containers=" + result.containersImported());
        sendResponse(t, "{\"success\":" + result.success()
                + ",\"blocksImported\":" + result.blocksImported()
                + ",\"containersImported\":" + result.containersImported()
                + ",\"message\":\"" + jsonEscape(result.message()) + "\"}");
    }

    private void handleGuardianAction(HttpExchange t, String mode) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        String body = new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8);
        Map<String, String> params = parseFormData(body);
        GuardianActionService.ActionRequest request = guardianActionRequest(params);
        GuardianActionService.ActionResult result = "restore".equals(mode)
                ? new GuardianActionService(plugin).restore(guardian, request)
                : new GuardianActionService(plugin).rollback(guardian, request);
        WebActionLogger.log("GUARDIAN_" + mode.toUpperCase(Locale.ROOT),
                "user=" + getSessionUser(t) + " preview=" + request.preview() + " player=" + request.player()
                        + " world=" + request.world() + " changedBlocks=" + result.changedBlocks()
                        + " changedContainers=" + result.changedContainers());
        sendResponse(t, guardianActionResultJson(result));
    }

    private void handleGuardianPurge(HttpExchange t) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        String body = new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8);
        Map<String, String> params = parseFormData(body);
        int hours = Math.max(1, Math.min(parseInt(params.get("hours"), 720), 24000));
        long cutoff = (System.currentTimeMillis() / 1000L) - (hours * 3600L);
        GuardianDataManager.PurgeResult result = guardian.purgeOlderThan(cutoff, params.get("world"),
                csvList(params.get("include")));
        WebActionLogger.log("GUARDIAN_PURGE",
                "user=" + getSessionUser(t) + " hours=" + hours + " world=" + params.get("world")
                        + " blocks=" + result.blockRows() + " containers=" + result.containerRows());
        sendResponse(t, "{\"success\":true,\"blockRows\":" + result.blockRows()
                + ",\"containerRows\":" + result.containerRows()
                + ",\"message\":\"Purged " + (result.blockRows() + result.containerRows()) + " Guardian rows.\"}");
    }

    private void handleGuardianCaseCreate(HttpExchange t) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        GuardianDataManager.CaseRecord created = guardian.createCase(
                params.get("title"),
                params.get("priority"),
                params.get("player"),
                params.get("world"),
                parseOptionalInt(params.get("x")),
                parseOptionalInt(params.get("y")),
                parseOptionalInt(params.get("z")),
                params.get("notes"),
                actorLabel(t));
        if (created == null) {
            sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"case_create_failed\"}");
            return;
        }
        WebActionLogger.log("GUARDIAN_CASE_CREATE",
                "user=" + actorLabel(t) + " case=" + created.id() + " player=" + created.playerName());
        sendResponse(t, "{\"success\":true,\"case\":" + guardianCaseJson(created) + "}");
    }

    private void handleGuardianCaseUpdate(HttpExchange t) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        long caseId = parseLong(params.get("caseId"), 0L);
        boolean updated = guardian.updateCase(caseId, params.get("status"), params.get("priority"),
                params.get("notes"), actorLabel(t));
        if (!updated) {
            sendResponseWithStatus(t, 404, "{\"success\":false,\"error\":\"case_not_found\"}");
            return;
        }
        GuardianDataManager.CaseRecord record = guardian.getCase(caseId);
        WebActionLogger.log("GUARDIAN_CASE_UPDATE",
                "user=" + actorLabel(t) + " case=" + caseId + " status=" + params.get("status"));
        sendResponse(t, "{\"success\":true,\"case\":" + guardianCaseJson(record) + "}");
    }

    private void handleGuardianEvidenceAdd(HttpExchange t) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        long caseId = parseLong(params.get("caseId"), 0L);
        long eventId = parseLong(params.get("eventId"), 0L);
        boolean added = guardian.addCaseEvidence(caseId, params.get("eventType"), eventId, params.get("label"),
                actorLabel(t));
        if (!added) {
            sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"evidence_add_failed\"}");
            return;
        }
        WebActionLogger.log("GUARDIAN_EVIDENCE_ADD",
                "user=" + actorLabel(t) + " case=" + caseId + " type=" + params.get("eventType")
                        + " event=" + eventId);
        sendResponse(t, "{\"success\":true,\"evidence\":" + guardianEvidenceJson(guardian.listCaseEvidence(caseId)) + "}");
    }

    private void handleGuardianPlayerNoteSave(HttpExchange t) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        GuardianDataManager.PlayerNoteRecord note = guardian.upsertPlayerNote(
                params.get("player"),
                params.get("severity"),
                params.get("notes"),
                actorLabel(t));
        if (note == null) {
            sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"player_note_save_failed\"}");
            return;
        }
        WebActionLogger.log("GUARDIAN_PLAYER_NOTE_SAVE",
                "user=" + actorLabel(t) + " player=" + note.playerName() + " severity=" + note.severity());
        sendResponse(t, "{\"success\":true,\"note\":" + guardianPlayerNoteJson(note)
                + ",\"notes\":" + guardianPlayerNotesJson(guardian.listPlayerNotes(null, null, 20)) + "}");
    }

    private void handleGuardianPlayerNoteDelete(HttpExchange t) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        boolean deleted = guardian.deletePlayerNote(params.get("player"));
        WebActionLogger.log("GUARDIAN_PLAYER_NOTE_DELETE",
                "user=" + actorLabel(t) + " player=" + params.get("player"));
        sendResponse(t, "{\"success\":" + deleted
                + ",\"notes\":" + guardianPlayerNotesJson(guardian.listPlayerNotes(null, null, 20)) + "}");
    }

    private void handleGuardianFilterSave(HttpExchange t) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        boolean saved = guardian.saveFilter(params.get("name"), params.get("query"), actorLabel(t));
        if (!saved) {
            sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"filter_save_failed\"}");
            return;
        }
        WebActionLogger.log("GUARDIAN_FILTER_SAVE", "user=" + actorLabel(t) + " name=" + params.get("name"));
        sendResponse(t, "{\"success\":true,\"filters\":" + guardianFiltersJson(guardian.listSavedFilters()) + "}");
    }

    private void handleGuardianFilterDelete(HttpExchange t) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        boolean deleted = guardian.deleteFilter(parseLong(params.get("id"), 0L));
        sendResponse(t, "{\"success\":" + deleted + ",\"filters\":"
                + guardianFiltersJson(guardian.listSavedFilters()) + "}");
    }

    private void handleGuardianProtectedRegionSave(HttpExchange t) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        long idValue = parseLong(params.get("id"), 0L);
        GuardianDataManager.ProtectedRegionRecord region = guardian.upsertProtectedRegion(
                idValue > 0 ? idValue : null,
                params.get("name"),
                params.get("world"),
                parseOptionalInt(params.get("x1")),
                parseOptionalInt(params.get("y1")),
                parseOptionalInt(params.get("z1")),
                parseOptionalInt(params.get("x2")),
                parseOptionalInt(params.get("y2")),
                parseOptionalInt(params.get("z2")),
                params.get("severity"),
                actorLabel(t));
        if (region == null) {
            sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"region_save_failed\"}");
            return;
        }
        WebActionLogger.log("GUARDIAN_REGION_SAVE", "user=" + actorLabel(t) + " region=" + region.name());
        sendResponse(t, "{\"success\":true,\"region\":" + guardianProtectedRegionJson(region)
                + ",\"regions\":" + guardianProtectedRegionsJson(guardian.listProtectedRegions()) + "}");
    }

    private void handleGuardianProtectedRegionDelete(HttpExchange t) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        long id = parseLong(params.get("id"), 0L);
        boolean deleted = guardian.deleteProtectedRegion(id);
        WebActionLogger.log("GUARDIAN_REGION_DELETE", "user=" + actorLabel(t) + " id=" + id);
        sendResponse(t, "{\"success\":" + deleted
                + ",\"regions\":" + guardianProtectedRegionsJson(guardian.listProtectedRegions()) + "}");
    }

    private void handleGuardianAlertRuleSave(HttpExchange t) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        long idValue = parseLong(params.get("id"), 0L);
        GuardianDataManager.AlertRuleRecord rule = guardian.upsertAlertRule(
                idValue > 0 ? idValue : null,
                params.get("name"),
                isChecked(params, "enabled"),
                parseInt(params.get("windowSeconds"), parseInt(params.get("window_seconds"), 600)),
                parseInt(params.get("minActions"), parseInt(params.get("min_actions"), 25)),
                params.get("action"),
                params.get("material"),
                isChecked(params, "autoCase") || isChecked(params, "auto_case"),
                params.get("priority"),
                actorLabel(t));
        if (rule == null) {
            sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"alert_rule_save_failed\"}");
            return;
        }
        WebActionLogger.log("GUARDIAN_ALERT_RULE_SAVE", "user=" + actorLabel(t) + " rule=" + rule.name());
        sendResponse(t, "{\"success\":true,\"rule\":" + guardianAlertRuleJson(rule)
                + ",\"rules\":" + guardianAlertRulesJson(guardian.listAlertRules()) + "}");
    }

    private void handleGuardianAlertRuleDelete(HttpExchange t) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        long id = parseLong(params.get("id"), 0L);
        boolean deleted = guardian.deleteAlertRule(id);
        WebActionLogger.log("GUARDIAN_ALERT_RULE_DELETE", "user=" + actorLabel(t) + " id=" + id);
        sendResponse(t, "{\"success\":" + deleted
                + ",\"rules\":" + guardianAlertRulesJson(guardian.listAlertRules()) + "}");
    }

    private void handleGuardianAlertRuleEvaluate(HttpExchange t) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        boolean autoCase = isChecked(params, "autoCase") || isChecked(params, "auto_case");
        List<GuardianDataManager.AlertHitRecord> hits = guardian.evaluateAlertRules(autoCase, actorLabel(t));
        WebActionLogger.log("GUARDIAN_ALERT_RULE_EVALUATE",
                "user=" + actorLabel(t) + " hits=" + hits.size() + " autoCase=" + autoCase);
        sendResponse(t, "{\"success\":true,\"hits\":" + guardianAlertHitsJson(hits)
                + ",\"cases\":" + guardianCasesJson(guardian.listCases("OPEN", null, 10)) + "}");
    }

    private void handleGuardianRetentionSave(HttpExchange t) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        GuardianDataManager.RetentionPolicyRecord policy = guardian.saveRetentionPolicy(
                parseInt(params.get("logDays"), parseInt(params.get("log_days"), 90)),
                isChecked(params, "keepCases") || isChecked(params, "keep_cases"),
                actorLabel(t));
        WebActionLogger.log("GUARDIAN_RETENTION_SAVE",
                "user=" + actorLabel(t) + " days=" + policy.logDays());
        sendResponse(t, "{\"success\":true,\"policy\":" + guardianRetentionJson(policy) + "}");
    }

    private void handleGuardianRetentionApply(HttpExchange t) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        GuardianDataManager.PurgeResult result = guardian.applyRetentionPolicy();
        WebActionLogger.log("GUARDIAN_RETENTION_APPLY",
                "user=" + actorLabel(t) + " blocks=" + result.blockRows() + " containers=" + result.containerRows());
        sendResponse(t, "{\"success\":true,\"blockRows\":" + result.blockRows()
                + ",\"containerRows\":" + result.containerRows()
                + ",\"message\":\"Retention purged " + (result.blockRows() + result.containerRows()) + " rows.\"}");
    }

    private void handleGuardianExport(HttpExchange t, String path) throws IOException {
        GuardianDataManager guardian = Dash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "Guardian unavailable");
            return;
        }
        String query = t.getRequestURI().getRawQuery();
        if (path.endsWith("/blocks")) {
            List<GuardianDataManager.BlockLogEntry> rows = guardian.searchBlockLogs(
                    getQueryParam(query, "player"),
                    getQueryParam(query, "world"),
                    sinceFromQuery(query),
                    null,
                    GuardianDataManager.parseBlockAction(getQueryParam(query, "action")),
                    1,
                    100000);
            t.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
            t.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"guardian-blocks.csv\"");
            sendResponse(t, guardianBlocksCsv(rows));
        } else if (path.endsWith("/containers")) {
            List<GuardianDataManager.ContainerLogEntry> rows = guardian.searchContainerLogs(
                    getQueryParam(query, "player"),
                    getQueryParam(query, "world"),
                    sinceFromQuery(query),
                    null,
                    GuardianDataManager.parseContainerAction(getQueryParam(query, "action")),
                    1,
                    100000);
            t.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
            t.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"guardian-containers.csv\"");
            sendResponse(t, guardianContainersCsv(rows));
        } else {
            sendResponseWithStatus(t, 404, "Not found");
        }
    }

    private class UpdateDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!ensurePermission(t, "dash.web.settings.write", true)) {
                return;
            }

            GithubUpdater updater = Dash.getGithubUpdater();
            if (updater == null || !updater.isEnabled()) {
                sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"Updater disabled\"}");
                return;
            }

            boolean downloaded = updater.downloadUpdate();
            if (downloaded) {
                sendResponseWithStatus(t, 200,
                        "{\"success\":true,\"message\":\"Update downloaded and staged as Dash-update.jar. It will be applied automatically when the server stops.\"}");
            } else {
                sendResponseWithStatus(t, 500, "{\"success\":false,\"error\":\"Update download failed\"}");
            }
        }
    }

    private class UpdateRestartHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!ensurePermission(t, "dash.web.server.control", true)) {
                return;
            }

            Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 40L);
            sendResponseWithStatus(t, 200, "{\"success\":true,\"message\":\"Server shutdown scheduled — update will be applied automatically.\"}");
        }
    }

    private class BridgeConsoleHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensureBridgeBearer(t)) {
                return;
            }

            if ("GET".equalsIgnoreCase(t.getRequestMethod())) {
                List<String> logs = ConsoleCatcher.getRecentLogs();
                String response = String.join("\n", logs);
                t.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                sendResponse(t, response);
                return;
            }

            if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                String body = new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8);
                String cmd = extractBridgeCommand(body, t.getRequestHeaders().getFirst("Content-Type"));
                if (cmd == null || cmd.isBlank()) {
                    t.getResponseHeaders().add("Content-Type", "application/json");
                    sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"Missing command\"}");
                    return;
                }

                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
                WebActionLogger.log("BRIDGE_COMMAND", "cmd=" + cmd + " ip=" + getClientIp(t));
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\":true}");
                return;
            }

            t.sendResponseHeaders(405, -1);
            t.close();
        }
    }

    private class WebhookApproveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            String token = getQueryParam(t.getRequestURI().getRawQuery(), "token");
            String action = getQueryParam(t.getRequestURI().getRawQuery(), "action");
            boolean allow = "allow".equalsIgnoreCase(action);
            boolean deny = "deny".equalsIgnoreCase(action);
            if (!allow && !deny) {
                sendResponseWithStatus(t, 400, "Invalid action");
                return;
            }

            WebAuth.AuthResult result = auth.approveBridgeByToken(token, allow, "MODERATOR");
            if (!result.success()) {
                sendResponseWithStatus(t, 400, "Request failed: " + result.message());
                return;
            }

            String auditMsg = allow ? "allowed user=" + result.message() : "denied pending bridge user";
            WebActionLogger.log("BRIDGE_WEBHOOK_APPROVAL", auditMsg + " from " + getClientIp(t));
            sendResponseWithStatus(t, 200, "Success");
        }
    }

    private class FileSaveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.files.write", true)) {
                return;
            }

            if (!"POST".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            String body = new String(readRequestBodyStrict(t, 4L * 1024L * 1024L), StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(body);
            String filePath = params.get("path");
            String content = params.get("content");
            t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");

            if (filePath == null || content == null) {
                sendResponse(t, "{\"success\":false,\"error\":\"Missing parameters\"}");
                return;
            }
            if (content.getBytes(StandardCharsets.UTF_8).length > 1024L * 1024L) {
                sendResponse(t, "{\"success\":false,\"error\":\"Content exceeds the 1 MiB editor limit\"}");
                return;
            }

            File serverDir = Bukkit.getWorldContainer().getCanonicalFile();
            File file = new File(serverDir, filePath).getCanonicalFile();

            try {
                Path rootPath = serverDir.toPath();
                Path fileTarget = file.toPath();
                if (!fileTarget.startsWith(rootPath)) {
                    sendResponse(t, "{\"success\":false,\"error\":\"Access denied\"}");
                    return;
                }
                if (isProtectedLockFile(file) || !FilesPage.isEditableFile(file.getName())) {
                    sendResponse(t, "{\"success\":false,\"error\":\"This file type cannot be edited\"}");
                    return;
                }
                if (Files.exists(fileTarget) && (!Files.isRegularFile(fileTarget) || Files.size(fileTarget) > 1024L * 1024L)) {
                    sendResponse(t, "{\"success\":false,\"error\":\"File is not editable in the web editor\"}");
                    return;
                }
                Path parent = fileTarget.getParent();
                if (parent == null || !Files.isDirectory(parent)) {
                    sendResponse(t, "{\"success\":false,\"error\":\"Parent folder does not exist\"}");
                    return;
                }

                if (Files.exists(fileTarget)) {
                    Files.copy(fileTarget, Path.of(fileTarget + ".bak"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }

                Path temp = Files.createTempFile(parent, "." + file.getName() + "-", ".dash-save");
                try {
                    Files.writeString(temp, content, StandardCharsets.UTF_8);
                    try {
                        Files.move(temp, fileTarget,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                        Files.move(temp, fileTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temp);
                }

                WebActionLogger.logFileEdit(filePath, getClientIp(t));
                sendResponse(t, "{\"success\":true}");
            } catch (Exception e) {
                sendResponse(t, "{\"success\":false,\"error\":\"" + jsonEscape(
                        e.getMessage() == null ? "Save failed" : e.getMessage()) + "\"}");
            }
        }
    }

    private class FileDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.files.read", true)) {
                return;
            }
            if (!"GET".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            String relativePath = getQueryParam(t.getRequestURI().getQuery(), "path");
            if (relativePath == null || relativePath.isBlank()) {
                t.sendResponseHeaders(400, -1);
                t.close();
                return;
            }

            File serverDir = Bukkit.getWorldContainer().getCanonicalFile();
            File file = new File(serverDir, relativePath).getCanonicalFile();
            if (!file.toPath().startsWith(serverDir.toPath())) {
                t.sendResponseHeaders(403, -1);
                t.close();
                return;
            }
            if (!file.exists()) {
                t.sendResponseHeaders(404, -1);
                t.close();
                return;
            }
            if (file.isDirectory()) {
                streamDirectoryZip(t, file, serverDir);
                return;
            }
            if (!file.isFile()) {
                t.sendResponseHeaders(404, -1);
                t.close();
                return;
            }

            String fileName = file.getName();
            String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            String contentType = Files.probeContentType(file.toPath());
            t.getResponseHeaders().set("Content-Type",
                    contentType == null ? "application/octet-stream" : contentType);
            t.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + fileName.replace("\"", "_")
                            + "\"; filename*=UTF-8''" + encodedName);
            t.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            t.sendResponseHeaders(200, file.length());
            try (OutputStream output = t.getResponseBody();
                    InputStream input = Files.newInputStream(file.toPath())) {
                input.transferTo(output);
            }
        }

        private void streamDirectoryZip(HttpExchange exchange, File directory, File serverRoot) throws IOException {
            String downloadName = directory.getName().replace("\"", "_") + ".zip";
            String encodedName = URLEncoder.encode(downloadName, StandardCharsets.UTF_8).replace("+", "%20");
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + downloadName + "\"; filename*=UTF-8''" + encodedName);
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, 0);

            java.nio.file.Path root = directory.toPath();
            java.nio.file.Path allowedRoot = serverRoot.toPath();
            try (OutputStream output = exchange.getResponseBody();
                    java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(output);
                    java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root)) {
                java.util.Iterator<java.nio.file.Path> iterator = paths.sorted().iterator();
                while (iterator.hasNext()) {
                    java.nio.file.Path path = iterator.next();
                    if (path.equals(root) || Files.isSymbolicLink(path)) continue;
                    java.nio.file.Path canonical = path.toFile().getCanonicalFile().toPath();
                    if (!canonical.startsWith(allowedRoot)) continue;
                    String entryName = root.relativize(path).toString().replace('\\', '/');
                    if (Files.isDirectory(path)) entryName += "/";
                    zip.putNextEntry(new java.util.zip.ZipEntry(entryName));
                    if (Files.isRegularFile(path)) Files.copy(path, zip);
                    zip.closeEntry();
                }
                zip.finish();
            }
        }
    }

    private class OperationsEvidenceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!ensureAnyPermission(t, true, "dash.web.audit.read", "dash.web.settings.read")) {
                return;
            }
            t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            t.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"dash-security-evidence.json\"");
            t.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            sendResponse(t, operationsManager.securityEvidenceJson(getSessionUser(t)));
        }
    }

    private class BridgeSecretRotationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                sendResponseWithStatus(t, 405, "{\"success\":false,\"error\":\"Method not allowed\"}");
                return;
            }
            if (!isBridgeBearerAuthorized(t)) {
                sendResponseWithStatus(t, 403, "{\"success\":false,\"error\":\"Forbidden\"}");
                return;
            }
            byte[] body = readRequestBodyOrReject(t, 4096L);
            if (body == null) return;
            String newSecret = parseFormData(new String(body, StandardCharsets.UTF_8))
                    .getOrDefault("new_secret", "").trim();
            if (!newSecret.matches("[A-Za-z0-9_-]{32,256}")) {
                sendResponseWithStatus(t, 400,
                        "{\"success\":false,\"error\":\"Secret must contain 32-256 URL-safe characters\"}");
                return;
            }
            String oldSecret = plugin.getConfig().getString("bridge.secret", "");
            try {
                plugin.getConfig().set("bridge.secret", newSecret);
                plugin.saveConfig();
                sessions.entrySet().removeIf(entry -> entry.getValue().bridgeBound);
                WebActionLogger.log("BRIDGE_SECRET_ROTATE",
                        "newFingerprint=" + operationsManager.secretFingerprint(newSecret) + " from " + getClientIp(t));
                sendResponse(t, "{\"success\":true}");
            } catch (Exception ex) {
                plugin.getConfig().set("bridge.secret", oldSecret);
                try { plugin.saveConfig(); } catch (Exception ignored) { }
                sendResponseWithStatus(t, 500, "{\"success\":false,\"error\":\"Secret save failed\"}");
            }
        }
    }

    private class ActionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, 0);
                t.close();
                return;
            }

            byte[] requestBody = readRequestBodyOrReject(t, 1024L * 1024L);
            if (requestBody == null) {
                return;
            }
            String body = new String(requestBody, StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(body);
            String action = params.get("action");
            String referer = t.getRequestHeaders().getFirst("Referer");
            String redirectTo = resolveActionReturnTarget(params.get("return_to"), referer);
            String clientIp = getClientIp(t);

            if ("register_code".equals(action)) {
                String code = params.get("code");
                String username = params.get("username");
                String password = params.get("password");
                String passwordConfirm = params.get("password_confirm");
                String owner2fa = params.get("owner_2fa_code");

                if (code != null && username != null && password != null) {
                    if (!username.trim().matches("[A-Za-z0-9_.-]{3,32}")) {
                        redirect(t, "/setup?msg=" + encodeForQuery(
                                "Username must contain 3-32 letters, numbers, dots, underscores or hyphens. The code was not consumed."));
                        return;
                    }
                    if (password.length() < 12 || password.length() > 256) {
                        redirect(t, "/setup?msg=" + encodeForQuery(
                                "Password must contain 12-256 characters. The registration code is still valid."));
                        return;
                    }
                    if (!password.equals(passwordConfirm)) {
                        redirect(t, "/setup?msg=" + encodeForQuery(
                                "Passwords do not match. The registration code is still valid."));
                        return;
                    }
                    if (auth.isSetupRequired()) {
                        boolean success = auth.registerFirstAdminWithCode(code, username, password);
                        if (success) {
                            FeatureFlags.applySetupPreset(params.get("setup_profile"), isChecked(params, "beta_opt_in"));
                            setSession(t, username);
                            redirect(t, "/");
                            return;
                        }
                    } else {
                        if (!auth.verifyOwner2faCode(owner2fa)) {
                            redirect(t, "/setup?msg=" + encodeForQuery("Invalid owner 2FA code."));
                            return;
                        }

                        RegistrationManager.RegistrationCode regCode = Dash.getRegistrationManager().validateAndConsume(code);
                        if (regCode == null) {
                            redirect(t, "/setup?msg=" + encodeForQuery("Invalid or expired registration code."));
                            return;
                        }
                        RegistrationApprovalManager approvalManager = Dash.getRegistrationApprovalManager();
                        if (approvalManager == null) {
                            redirect(t, "/setup?msg=" + encodeForQuery("Approval manager unavailable."));
                            return;
                        }
                        String reqId = approvalManager.createPending(regCode, username, password, clientIp);
                        WebActionLogger.log("REGISTRATION_PENDING",
                                "request=" + reqId + " user=" + username + " role="
                                        + (regCode.role() == null ? "MODERATOR" : regCode.role()) + " from " + clientIp);
                        redirect(t, "/setup?msg=" + encodeForQuery("Registration request submitted. Waiting for MAIN_ADMIN web approval."));
                        return;
                    }
                }
                sendResponse(t,
                        "<!DOCTYPE html><html><body style='background:#0f172a;color:white;display:flex;justify-content:center;align-items:center;height:100vh;font-family:sans-serif'><div style='text-align:center'><h1 style='color:#f43f5e'>Registration Failed</h1><p>Invalid, expired or already-used code.</p><a href='/setup' style='color:#0ea5e9'>Try Again</a></div></body></html>");
                return;
            }

            if ("login".equals(action)) {
                String username = params.getOrDefault("username", "").trim();
                String loginKey = clientIp + "|" + username;
                if (!loginRateLimiter.isAllowed(loginKey)) {
                    t.getResponseHeaders().set("Retry-After",
                            Long.toString(loginRateLimiter.retryAfterSeconds(loginKey)));
                    sendResponseWithStatus(t, 429,
                            "<h1>Login temporarily unavailable</h1><p>Try again later.</p>");
                    return;
                }
                if (auth.check(username, params.get("password"))) {
                    loginRateLimiter.recordSuccess(loginKey);
                    setSession(t, username);
                    WebActionLogger.logLogin(username, clientIp);
                    redirect(t, "/");
                } else {
                    loginRateLimiter.recordFailure(loginKey);
                    WebActionLogger.log("LOGIN_FAILED", "User '" + username + "' failed from " + clientIp);
                    sendResponse(t, "<h1>Login Failed</h1><a href='/'>Try Again</a>");
                }
                return;
            }

            if (!isAuthenticated(t)) {
                redirect(t, "/");
                return;
            }
            if (!ensureSameOriginMutation(t, false)) {
                return;
            }

            String actionFeature = FeatureFlags.featureForAction(action);
            if (actionFeature != null && !FeatureFlags.enabled(actionFeature)) {
                redirect(t, "/settings?msg=" + encodeForQuery(
                        "The " + actionFeature + " feature is disabled in Settings."));
                return;
            }

            if ("logout".equals(action)) {
                clearSession(t);
                WebActionLogger.logLogout(clientIp);
                redirect(t, "/");
                return;
            }

            String requiredPermission = requiredPermissionForAction(action);
            if (requiredPermission == null && !usesSeparateActionAuthorization(action)) {
                sendResponseWithStatus(t, 400, "<html><body>Unknown or unauthorized action.</body></html>");
                return;
            }
            if (requiredPermission != null && !ensurePermission(t, requiredPermission, false)) {
                return;
            }

            if ("gamerule".equals(action)) {
                String rulePermission = requiredPermissionForGamerule(params.get("rule"));
                if (rulePermission == null) {
                    sendResponseWithStatus(t, 400, "<html><body>Invalid gamerule.</body></html>");
                    return;
                }
                if (!ensureAnyPermission(t, false, rulePermission, "dash.web.settings.write")) {
                    return;
                }
            }

            if ("set_motd".equals(action)) {
                if (!ensureAnyPermission(t, false, "dash.web.settings.motd.write", "dash.web.settings.write")) {
                    return;
                }
            }

            if ("set_distance".equals(action)) {
                boolean wantsView = params.containsKey("view") && params.get("view") != null
                        && !params.get("view").isBlank();
                boolean wantsSim = params.containsKey("sim") && params.get("sim") != null
                        && !params.get("sim").isBlank();
                boolean supportsSimulationDistance = ServerSettingsPage
                        .supportsPaperExtras(ServerSettingsPage.detectServerType());
                if (wantsSim && !supportsSimulationDistance) {
                    params.remove("sim");
                    wantsSim = false;
                }
                if (wantsView
                        && !ensureAnyPermission(t, false, "dash.web.settings.distance.view", "dash.web.settings.write")) {
                    return;
                }
                if (wantsSim && !ensureAnyPermission(t, false, "dash.web.settings.distance.simulation",
                        "dash.web.settings.write")) {
                    return;
                }
                if (!wantsView && !wantsSim) {
                    sendResponseWithStatus(t, 400, "<html><body>Missing distance values.</body></html>");
                    return;
                }
            }

            if ("save_beta_settings".equals(action)) {
                boolean enabled = isChecked(params, "beta_enabled");
                plugin.getConfig().set(BETA_FEATURES_CONFIG_KEY, enabled);
                plugin.saveConfig();
                WebActionLogger.logSettingChange(BETA_FEATURES_CONFIG_KEY, String.valueOf(enabled), clientIp);
                redirect(t, "/settings?msg=" + encodeForQuery(enabled ? "Beta features enabled." : "Beta features disabled."));
                return;
            }

            if ("save_feature_settings".equals(action)) {
                java.util.Map<String, Boolean> features = new java.util.LinkedHashMap<>();
                for (String id : FeatureFlags.IDS) features.put(id, isChecked(params, "feature_" + id));
                boolean beta = isChecked(params, "beta_enabled");
                FeatureFlags.save(beta, features);
                WebActionLogger.log("FEATURE_SETTINGS_SAVE", "beta=" + beta + " by " + getSessionUser(t));
                redirect(t, "/settings?msg=" + encodeForQuery("Feature availability updated."));
                return;
            }

            if (isBetaFeatureAction(action) && !ensureBetaFeatureEnabled(t)) {
                return;
            }

            if (action != null && action.startsWith("intel_jit_")
                    && !ensurePermission(t, "dash.web.users.manage", false)) {
                return;
            }

            if (shouldApplyGuardrails(action)) {
                IntelligenceManager.GuardDecision decision = intelligenceManager.authorizeAction(
                        action,
                        getSessionUser(t),
                        params.get("reason"),
                        Bukkit.getOnlinePlayers().size(),
                        params);
                if (!decision.allowed()) {
                    WebActionLogger.log("ACTION_GUARDRAIL",
                            "action=" + action + " actor=" + getSessionUser(t) + " result=" + decision.message());
                    if (sendGuardrailChallengeIfNeeded(t, decision, params)) {
                        return;
                    }
                    if (action.startsWith("intel_")) {
                        redirect(t, intelligenceRedirect(action, decision.message()));
                    } else {
                        redirect(t, withActionMessage(redirectTo, decision.message()));
                    }
                    return;
                }
            }

            if (action != null && action.startsWith("intel_")) {
                handleIntelligenceAction(t, params, getSessionUser(t), clientIp);
                return;
            }

            if (action != null && action.startsWith("operations_")) {
                redirect(t, "/intelligence?msg=" + encodeForQuery("Operations was removed in Dash 4.4."));
                return;
            }

            if ("invite_generate".equals(action)) {
                String invitePlayer = params.get("player");
                String inviteRole = params.get("role");
                String invitePermsRaw = params.get("permissions");
                List<String> invitePerms = invitePermsRaw == null || invitePermsRaw.isBlank() ? List.of()
                        : Arrays.stream(invitePermsRaw.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
                String targetName = invitePlayer == null || invitePlayer.isBlank() ? "Unbound" : invitePlayer;
                String targetUuid = "UNBOUND";
                if (!"Unbound".equals(targetName)) {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
                    if (target.getUniqueId() != null) {
                        targetUuid = target.getUniqueId().toString();
                    }
                }
                String generatedCode = Dash.getRegistrationManager().generateCode(targetUuid, targetName, inviteRole,
                        invitePerms);
                if (!"Unbound".equals(targetName)) {
                    Player onlineTarget = Bukkit.getPlayerExact(targetName);
                    if (onlineTarget != null) {
                        String setupUrl = SetupNotifier.buildSetupUrl(plugin, onlineTarget, generatedCode);
                        onlineTarget.sendMessage(Component.empty());
                        onlineTarget.sendMessage(Component.text("[Dash] ", NamedTextColor.AQUA)
                                .append(Component.text("Du wurdest von " + getSessionUser(t)
                                        + " in das Dash-Panel eingeladen! Dein Rang: " + inviteRole
                                        + ". Klicke hier, um dich zu registrieren.", NamedTextColor.YELLOW)
                                        .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(setupUrl))));
                        onlineTarget.sendMessage(Component.text("Setup URL: " + setupUrl, NamedTextColor.GRAY));
                    }
                }
                WebActionLogger.log("INVITE_CODE_GENERATED",
                        "role=" + inviteRole + " target=" + targetName + " by " + getSessionUser(t)
                                + " from " + clientIp);
                redirect(t, "/users?code=" + generatedCode);
                return;
            }

            if ("role_permissions_save".equals(action)) {
                String role = params.get("role");
                List<String> add = splitCsv(params.get("add_permissions"));
                List<String> remove = splitCsv(params.get("remove_permissions"));
                WebAuth.AuthResult result = auth.updateRolePermissionsSafe(getSessionUser(t), role, add, remove);
                String message = result.success()
                        ? "Permissions updated"
                        : humanizeRolePermissionError(result.message());
                redirect(t,
                        "/permissions?role=" + encodeForQuery(role == null ? "" : role)
                                + "&msg=" + encodeForQuery(message));
                return;
            }

            if ("role_create".equals(action)) {
                String roleName = params.get("role_name");
                String presetRole = params.get("preset");
                WebAuth.AuthResult result = auth.createRoleSafe(getSessionUser(t), roleName, presetRole);
                if (result.success()) {
                    String createdRole = result.message();
                    redirect(t, "/permissions?role=" + encodeForQuery(createdRole));
                    return;
                }
                redirect(t,
                        "/permissions?msg=" + encodeForQuery(humanizeRoleCreationError(result.message())));
                return;
            }

            if ("role_set_value".equals(action)) {
                String role = params.get("role");
                String value = params.get("value");
                int parsedValue;
                try {
                    parsedValue = Integer.parseInt(value == null ? "" : value.trim());
                } catch (NumberFormatException ex) {
                    redirect(t, "/permissions?role=" + encodeForQuery(role == null ? "" : role)
                            + "&msg=" + encodeForQuery("Invalid role value."));
                    return;
                }

                WebAuth.AuthResult result = auth.setRoleValueSafe(getSessionUser(t), role, parsedValue);
                String message = result.success() ? "Role value updated" : humanizeRolePermissionError(result.message());
                redirect(t,
                        "/permissions?role=" + encodeForQuery(role == null ? "" : role)
                                + "&msg=" + encodeForQuery(message));
                return;
            }

            if ("role_delete".equals(action)) {
                String role = params.get("role");
                WebAuth.AuthResult result = auth.deleteRoleSafe(getSessionUser(t), role);
                String message = result.success() ? "Role deleted" : humanizeRolePermissionError(result.message());
                if (result.success()) {
                    redirect(t, "/permissions?msg=" + encodeForQuery(message));
                } else {
                    redirect(t,
                            "/permissions?role=" + encodeForQuery(role == null ? "" : role)
                                    + "&msg=" + encodeForQuery(message));
                }
                return;
            }

            if ("user_set_role".equals(action)) {
                String roleUser = params.get("username");
                String newRole = params.get("role");
                if (roleUser != null && newRole != null) {
                    WebAuth.AuthResult result = auth.setUserRoleSafe(getSessionUser(t), roleUser, newRole);
                    if (result.success()) {
                        WebActionLogger.log("ROLE_CHANGE",
                                "user=" + roleUser + " role=" + newRole + " by " + getSessionUser(t)
                                        + " from " + clientIp);
                        redirect(t, "/users?msg=Role%20updated");
                        return;
                    }
                    WebActionLogger.log("ROLE_CHANGE",
                            "BLOCKED user=" + roleUser + " role=" + newRole + " by " + getSessionUser(t)
                                    + " from " + clientIp + " reason=" + result.message());
                    redirect(t, "/users?msg=" + encodeForQuery(humanizeRolePermissionError(result.message())));
                    return;
                }
                redirect(t, "/users?msg=Invalid%20role%20request");
                return;
            }

            if ("user_make_main_admin".equals(action)) {
                String targetUser = params.get("username");
                if (targetUser != null) {
                    WebAuth.AuthResult result = auth.transferMainAdmin(getSessionUser(t), targetUser);
                    if (result.success()) {
                        redirect(t, "/users?msg=Main%20admin%20transferred");
                        return;
                    }
                    redirect(t, "/users?msg=Blocked:%20" + result.message());
                    return;
                }
                redirect(t, "/users?msg=Invalid%20main%20admin%20request");
                return;
            }

            if ("user_delete".equals(action)) {
                String targetUser = params.get("username");
                if (targetUser != null) {
                    WebAuth.AuthResult result = auth.deleteUserSafe(getSessionUser(t), targetUser);
                    if (result.success()) {
                        invalidateSessionsFor(targetUser);
                        WebActionLogger.log("USER_DELETE", "user=" + getSessionUser(t) + " deleted=" + targetUser
                                + " ip=" + getClientIp(t));
                        redirect(t, "/users?msg=User%20deleted");
                        return;
                    }
                    redirect(t, "/users?msg=Blocked:%20" + result.message());
                    return;
                }
                redirect(t, "/users?msg=Invalid%20delete%20request");
                return;
            }

            // --- Feature 1: Save Plugin Settings ---
            if ("save_plugin_settings".equals(action)) {
                if (!ensurePermission(t, "dash.web.pluginsettings.write", false)) return;

                String error = applyPluginSettings(params, clientIp);
                if (error != null) {
                    redirect(t, "/plugin-settings?msg=" + encodeForQuery(error));
                    return;
                }
                redirect(t, "/plugin-settings?msg=Settings%20saved%20successfully");
                return;
            }

            // --- Feature 5: Scheduled Task actions ---
            if ("task_add".equals(action)) {
                if (!ensurePermission(t, "dash.web.tasks.write", false)) return;
                String taskType = params.get("task_type");
                String payload = params.get("payload");
                String intervalStr = params.get("interval");
                if (taskType != null && payload != null && intervalStr != null) {
                    try {
                        int interval = Integer.parseInt(intervalStr.trim());
                        if (interval >= 1 && interval <= 10080) {
                            dash.data.ScheduledTaskManager mgr = Dash.getScheduledTaskManager();
                            if (mgr != null) {
                                int id = mgr.addTask(taskType, interval, payload, true);
                                WebActionLogger.log("TASK_ADD", "id=" + id + " type=" + taskType + " interval=" + interval + "min by " + getSessionUser(t) + " from " + clientIp);
                                redirect(t, "/scheduled-tasks?msg=Task%20created%20successfully");
                                return;
                            }
                        }
                    } catch (NumberFormatException ignored) {}
                }
                redirect(t, "/scheduled-tasks?msg=Error:%20Invalid%20task%20parameters");
                return;
            }

            if ("task_toggle".equals(action)) {
                if (!ensurePermission(t, "dash.web.tasks.write", false)) return;
                String taskIdStr = params.get("task_id");
                String enabledStr = params.get("enabled");
                if (taskIdStr != null && enabledStr != null) {
                    try {
                        int taskId = Integer.parseInt(taskIdStr.trim());
                        boolean enabled = Boolean.parseBoolean(enabledStr);
                        dash.data.ScheduledTaskManager mgr = Dash.getScheduledTaskManager();
                        if (mgr != null) {
                            mgr.setEnabled(taskId, enabled);
                            WebActionLogger.log("TASK_TOGGLE", "id=" + taskId + " enabled=" + enabled + " by " + getSessionUser(t) + " from " + clientIp);
                        }
                    } catch (NumberFormatException ignored) {}
                }
                redirect(t, "/scheduled-tasks");
                return;
            }

            if ("task_delete".equals(action)) {
                if (!ensurePermission(t, "dash.web.tasks.write", false)) return;
                String taskIdStr = params.get("task_id");
                if (taskIdStr != null) {
                    try {
                        int taskId = Integer.parseInt(taskIdStr.trim());
                        dash.data.ScheduledTaskManager mgr = Dash.getScheduledTaskManager();
                        if (mgr != null) {
                            mgr.deleteTask(taskId);
                            WebActionLogger.log("TASK_DELETE", "id=" + taskId + " by " + getSessionUser(t) + " from " + clientIp);
                        }
                    } catch (NumberFormatException ignored) {}
                }
                redirect(t, "/scheduled-tasks?msg=Task%20deleted");
                return;
            }

            if ("registration_approve".equals(action)) {
                if (!auth.isMainAdmin(getSessionUser(t))) {
                    redirect(t, "/users?msg=" + encodeForQuery("Only MAIN_ADMIN can approve registrations."));
                    return;
                }
                String reqId = params.get("request_id");
                RegistrationApprovalManager approvalManager = Dash.getRegistrationApprovalManager();
                RegistrationApprovalManager.PendingRegistration pending = approvalManager == null ? null
                        : approvalManager.consume(reqId);
                if (pending == null) {
                    redirect(t, "/users?msg=" + encodeForQuery("Pending request not found or expired."));
                    return;
                }
                boolean ok = auth.registerApprovedPending(pending);
                WebActionLogger.log("REGISTRATION_APPROVAL",
                        "request=" + reqId + " user=" + pending.username() + " by " + getSessionUser(t)
                                + " success=" + ok + " from " + clientIp);
                redirect(t, "/users?msg=" + encodeForQuery(ok ? "Registration approved." : "Registration failed."));
                return;
            }

            if ("registration_deny".equals(action)) {
                if (!auth.isMainAdmin(getSessionUser(t))) {
                    redirect(t, "/users?msg=" + encodeForQuery("Only MAIN_ADMIN can deny registrations."));
                    return;
                }
                String reqId = params.get("request_id");
                RegistrationApprovalManager approvalManager = Dash.getRegistrationApprovalManager();
                boolean removed = approvalManager != null && approvalManager.deny(reqId);
                WebActionLogger.log("REGISTRATION_DENIED",
                        "request=" + reqId + " by " + getSessionUser(t) + " removed=" + removed + " from " + clientIp);
                redirect(t, "/users?msg=" + encodeForQuery(removed ? "Registration denied." : "Request not found."));
                return;
            }

            if ("bridge_user_allow".equals(action)) {
                if (!auth.isMainAdmin(getSessionUser(t))) {
                    redirect(t, "/users?msg=" + encodeForQuery("Only MAIN_ADMIN can approve bridge users."));
                    return;
                }
                String username = params.get("username");
                String role = params.get("role");
                List<String> extra = splitCsv(params.get("permissions"));
                WebAuth.AuthResult result = auth.approveBridgeUserSafe(getSessionUser(t), username, role, extra);
                WebActionLogger.log("BRIDGE_USER_ALLOW",
                        "user=" + username + " role=" + role + " by " + getSessionUser(t)
                                + " success=" + result.success() + " reason=" + result.message()
                                + " from " + clientIp);
                redirect(t, "/users?msg=" + encodeForQuery(result.success() ? "Bridge user approved." : result.message()));
                return;
            }

            if ("bridge_user_deny".equals(action)) {
                if (!auth.isMainAdmin(getSessionUser(t))) {
                    redirect(t, "/users?msg=" + encodeForQuery("Only MAIN_ADMIN can deny bridge users."));
                    return;
                }
                String username = params.get("username");
                WebAuth.AuthResult result = auth.denyBridgeUserSafe(getSessionUser(t), username);
                WebActionLogger.log("BRIDGE_USER_DENY",
                        "user=" + username + " by " + getSessionUser(t)
                                + " success=" + result.success() + " reason=" + result.message()
                                + " from " + clientIp);
                redirect(t, "/users?msg=" + encodeForQuery(result.success() ? "Bridge user denied." : result.message()));
                return;
            }

            if ("owner_2fa_regen".equals(action)) {
                if (!auth.isMainAdmin(getSessionUser(t))) {
                    redirect(t, "/users?msg=" + encodeForQuery("Only MAIN_ADMIN can regenerate owner 2FA."));
                    return;
                }
                String secret = auth.regenerateOwner2faSecret(getSessionUser(t));
                redirect(t, "/users?msg=" + encodeForQuery(secret == null
                        ? "Failed to regenerate owner 2FA secret."
                        : "Owner 2FA secret regenerated."));
                return;
            }

            if ("file_delete".equals(action)) {
                String relPath = params.get("path");
                if (relPath == null || relPath.isBlank()) {
                    redirect(t, "/files?msg=" + encodeForQuery("Missing file path."));
                    return;
                }
                File serverDir = Bukkit.getWorldContainer();
                File target = new File(serverDir, relPath);
                try {
                    if (!target.getCanonicalFile().toPath().startsWith(serverDir.getCanonicalFile().toPath())) {
                        redirect(t, "/files?msg=" + encodeForQuery("Access denied."));
                        return;
                    }
                    if (isProtectedLockFile(target)) {
                        redirect(t, "/files?msg=" + encodeForQuery("Protected lock file cannot be deleted."));
                        return;
                    }
                    boolean deleted = target.isDirectory() ? deleteRecursively(target) : target.delete();
                    WebActionLogger.log("FILE_DELETE",
                            "path=" + relPath + " by " + getSessionUser(t) + " success=" + deleted + " from " + clientIp);
                } catch (Exception ignored) {
                }
                String parentPath = relPath.contains("/") ? relPath.substring(0, relPath.lastIndexOf('/')) : "";
                redirect(t, "/files?path=" + encodeForQuery(parentPath));
                return;
            }

            if ("file_rename".equals(action)) {
                String relPath = params.get("path");
                String newName = params.get("new_name");
                if (relPath == null || relPath.isBlank() || newName == null || newName.isBlank()) {
                    redirect(t, "/files?msg=" + encodeForQuery("Missing rename parameters."));
                    return;
                }

                newName = newName.trim();
                if (newName.contains("/") || newName.contains("\\") || newName.contains("..") || ".".equals(newName)
                        || newName.isBlank()) {
                    redirect(t, "/files?msg=" + encodeForQuery("Invalid target name."));
                    return;
                }

                File serverDir = Bukkit.getWorldContainer();
                File source = new File(serverDir, relPath);
                try {
                    if (!source.getCanonicalFile().toPath().startsWith(serverDir.getCanonicalFile().toPath())) {
                        redirect(t, "/files?msg=" + encodeForQuery("Access denied."));
                        return;
                    }
                    if (!source.exists()) {
                        redirect(t, "/files?msg=" + encodeForQuery("Source not found."));
                        return;
                    }
                    if (isProtectedLockFile(source)) {
                        redirect(t, "/files?msg=" + encodeForQuery("Protected lock file cannot be renamed."));
                        return;
                    }

                    File parent = source.getParentFile();
                    if (parent == null) {
                        redirect(t, "/files?msg=" + encodeForQuery("Cannot rename this path."));
                        return;
                    }

                    File target = new File(parent, newName);
                    if (!target.getCanonicalFile().toPath().startsWith(serverDir.getCanonicalFile().toPath())) {
                        redirect(t, "/files?msg=" + encodeForQuery("Access denied."));
                        return;
                    }
                    if (isProtectedLockFile(target)) {
                        redirect(t, "/files?msg=" + encodeForQuery("Protected lock file name is not allowed."));
                        return;
                    }
                    if (target.exists()) {
                        redirect(t, "/files?msg=" + encodeForQuery("Target already exists."));
                        return;
                    }

                    boolean renamed = source.renameTo(target);
                    WebActionLogger.log("FILE_RENAME",
                            "path=" + relPath + " to=" + newName + " by " + getSessionUser(t)
                                    + " success=" + renamed + " from " + clientIp);

                    String parentPath = relPath.contains("/") ? relPath.substring(0, relPath.lastIndexOf('/')) : "";
                    String msg = renamed ? "Renamed successfully." : "Rename failed.";
                    redirect(t, "/files?path=" + encodeForQuery(parentPath) + "&msg=" + encodeForQuery(msg));
                    return;
                } catch (Exception ignored) {
                    redirect(t, "/files?msg=" + encodeForQuery("Rename failed."));
                    return;
                }
            }

            if ("plugin_delete".equals(action)) {
                String pluginName = params.get("plugin");
                String pluginFile = params.get("plugin_file");
                if (pluginFile == null || pluginFile.isBlank() || !pluginFile.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    redirect(t, "/plugins?msg=" + encodeForQuery("Invalid plugin file."));
                    return;
                }
                Plugin p = pluginName == null ? null : Bukkit.getPluginManager().getPlugin(pluginName);
                if (p != null && p.equals(plugin)) {
                    redirect(t, "/plugins?msg=" + encodeForQuery("Dash cannot delete itself while running."));
                    return;
                }
                if (p != null && p.isEnabled()) {
                    Bukkit.getPluginManager().disablePlugin(p);
                }
                File pluginsDir = new File(Bukkit.getWorldContainer(), "plugins");
                File target = new File(pluginsDir, pluginFile.replaceAll("[^a-zA-Z0-9._-]", "_"));
                try {
                    if (!target.getCanonicalFile().toPath().startsWith(pluginsDir.getCanonicalFile().toPath())) {
                        redirect(t, "/plugins?msg=" + encodeForQuery("Access denied."));
                        return;
                    }
                } catch (IOException ignored) {
                }
                boolean deleted = target.exists() && target.delete();
                WebActionLogger.log("PLUGIN_DELETE",
                        "plugin=" + pluginName + " file=" + pluginFile + " by " + getSessionUser(t)
                                + " success=" + deleted + " from " + clientIp);
                redirect(t, "/plugins");
                return;
            }

            if ("plugin_browser_install".equals(action)) {
                try {
                    if (plugin.getConfig().getBoolean("backups.pre_update", true) && Dash.getBackupManager() != null) {
                        Dash.getBackupManager().createBackup();
                    }
                    String msg = PluginBrowserPage.installFromUrl(
                            Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize(),
                            "plugins",
                            params.get("download_url"),
                            params.get("file_name"),
                            params.get("sha256"),
                            params.get("sha512"));
                    WebActionLogger.log("PLUGIN_BROWSER_INSTALL",
                            "file=" + params.get("file_name") + " by " + getSessionUser(t) + " from " + clientIp);
                    redirect(t, "/plugin-browser?msg=" + encodeForQuery(msg));
                } catch (Exception ex) {
                    redirect(t, "/plugin-browser?msg=" + encodeForQuery("Install failed: " + ex.getMessage()));
                }
                return;
            }

            if ("test_notification".equals(action)) {
                DiscordWebhookManager manager = Dash.getDiscordWebhookManager();
                int targets = manager == null ? 0 : manager.dispatchTest(
                        "Dash test notification from " + getSessionUser(t) + ". Delivery is working.");
                WebActionLogger.log("NOTIFICATION_TEST", "targets=" + targets + " by " + getSessionUser(t) + " from " + clientIp);
                String msg = targets == 0 ? "No Discord destination is configured."
                        : "Test notification queued for " + targets + " Discord destination" + (targets == 1 ? "." : "s.");
                redirect(t, "/notifications?msg=" + encodeForQuery(msg));
                return;
            }

            if ("save_notification_settings".equals(action)) {
                saveNotificationSettings(params);
                WebActionLogger.log("NOTIFICATION_SETTINGS_SAVE", "by " + getSessionUser(t) + " from " + clientIp);
                redirect(t, "/notifications?msg=" + encodeForQuery("Notification and cloud backup settings saved."));
                return;
            }

            if ("doctor_delete_crash".equals(action) || "doctor_mark_reviewed".equals(action)) {
                String msg = DashDoctorPage.resolveCrashAction(
                        Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize(),
                        params.get("file"),
                        "doctor_delete_crash".equals(action));
                WebActionLogger.log("DASH_DOCTOR_ACTION", action + " file=" + params.get("file") + " from " + clientIp);
                redirect(t, "/maintenance?msg=" + encodeForQuery(msg));
                return;
            }

            if ("staff_ticket_create".equals(action) || "staff_note_create".equals(action)) {
                String msg = "staff_ticket_create".equals(action)
                        ? StaffPage.createDetailed(plugin.getDataFolder().toPath(), params.get("title"), params.get("body"),
                                params.get("priority"), params.get("target_player"), getSessionUser(t), params.get("category"))
                        : StaffPage.create(plugin.getDataFolder().toPath(), "note", params.get("title"), params.get("body"),
                                params.get("priority"), params.get("target_player"), getSessionUser(t));
                if ("staff_ticket_create".equals(action) && msg.toLowerCase(Locale.ROOT).contains("created")) {
                    notifyIngameTicket(getSessionUser(t), params.get("target_player"));
                }
                WebActionLogger.log("STAFF_WORKFLOW_CREATE", action + " by " + getSessionUser(t) + " from " + clientIp);
                redirect(t, "/staff?msg=" + encodeForQuery(msg));
                return;
            }

            if ("staff_report_link_create".equals(action)) {
                PublicReportLinks.CreatedLink link = PublicReportLinks.create(plugin.getDataFolder().toPath(),
                        getSessionUser(t), parseInt(params.get("lifetime_minutes"), 1440),
                        params.get("target_player"), params.get("category"));
                WebActionLogger.log("PUBLIC_REPORT_LINK_CREATE", "by=" + getSessionUser(t) + " from=" + clientIp);
                redirect(t, "/staff?view=links" + (link.success()
                        ? "&report_token=" + encodeForQuery(link.token())
                        : "&msg=" + encodeForQuery("Report link could not be created.")));
                return;
            }

            if ("staff_ticket_status".equals(action)) {
                String msg = StaffPage.updateStatus(plugin.getDataFolder().toPath(), params.get("ticket_id"), params.get("status"));
                WebActionLogger.log("STAFF_WORKFLOW_STATUS", "ticket=" + params.get("ticket_id") + " from " + clientIp);
                redirect(t, "/staff?msg=" + encodeForQuery(msg));
                return;
            }

            if ("staff_ticket_reply".equals(action)) {
                String msg = StaffPage.appendReply(plugin.getDataFolder().toPath(), params.get("ticket_id"),
                        getSessionUser(t), params.get("reply"));
                WebActionLogger.log("STAFF_WORKFLOW_REPLY", "ticket=" + params.get("ticket_id") + " from " + clientIp);
                redirect(t, "/staff?msg=" + encodeForQuery(msg));
                return;
            }

            if ("staff_ticket_delete".equals(action)) {
                String msg = StaffPage.delete(plugin.getDataFolder().toPath(), params.get("ticket_id"));
                WebActionLogger.log("STAFF_WORKFLOW_DELETE", "ticket=" + params.get("ticket_id") + " from " + clientIp);
                redirect(t, "/staff?msg=" + encodeForQuery(msg));
                return;
            }

            if ("backup_create".equals(action)) {
                dash.data.BackupManager manager = Dash.getBackupManager();
                if (manager == null) {
                    redirect(t, withActionMessage(redirectTo, "Backup service is unavailable."));
                    return;
                }
                try {
                    dash.data.BackupManager.BackupResult result = manager.createBackupAsync().get(15, TimeUnit.MINUTES);
                    if (result.success()) {
                        WebActionLogger.logBackup("CREATE", result.fileName() + " from " + clientIp);
                        redirect(t, withActionMessage(redirectTo,
                                "Verified backup created: " + result.fileName() + "."));
                    } else {
                        redirect(t, withActionMessage(redirectTo,
                                "Backup failed: " + (result.error() == null ? "Unknown error" : result.error())));
                    }
                } catch (java.util.concurrent.TimeoutException ex) {
                    redirect(t, withActionMessage(redirectTo,
                            "Backup is still running. Refresh this page to see it when verification finishes."));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    redirect(t, withActionMessage(redirectTo, "Backup was interrupted. Please retry."));
                } catch (Exception ex) {
                    redirect(t, withActionMessage(redirectTo, "Backup failed safely."));
                }
                return;
            }

            if ("backup_delete".equals(action)) {
                dash.data.BackupManager manager = Dash.getBackupManager();
                String backupName = params.get("name");
                boolean deleted = manager != null && backupName != null && manager.deleteBackup(backupName);
                if (deleted) {
                    WebActionLogger.logBackup("DELETE", backupName + " from " + clientIp);
                }
                redirect(t, withActionMessage(redirectTo,
                        deleted ? "Backup deleted." : "Backup was not found or could not be deleted."));
                return;
            }

            if ("restart".equals(action) && redirectToNeoDashRestartIfAvailable(t)) {
                return;
            }

            CompletableFuture<Void> actionCompletion = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    switch (action) {
                    case "command":
                        String cmd = params.get("cmd");
                        if (cmd != null && !cmd.isEmpty()) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                            WebActionLogger.logCommand(cmd, clientIp);
                        }
                        break;
                    case "restart":
                        WebActionLogger.log("RESTART", "Server restart initiated from " + clientIp);
                        Bukkit.spigot().restart();
                        break;
                    case "stop":
                        WebActionLogger.log("STOP", "Server stop initiated from " + clientIp);
                        Bukkit.shutdown();
                        break;
                    case "kick":
                        Player kickTarget = Bukkit.getPlayerExact(params.get("player"));
                        if (kickTarget != null) {
                            kickTarget.kick(Component.text("Kicked by admin"));
                            WebActionLogger.logPlayerAction("KICK", params.get("player"), clientIp);
                        }
                        break;
                    case "ban":
                        String banName = params.get("player");
                        Bukkit.getBanList(BanList.Type.NAME).addBan(banName, "Banned by admin", null, "WebConsole");
                        Player banTarget = Bukkit.getPlayerExact(banName);
                        if (banTarget != null)
                            banTarget.kick(Component.text("Banned by admin"));
                        WebActionLogger.logPlayerAction("BAN", banName, clientIp);
                        break;
                    case "freeze":
                        Player freezeTarget = Bukkit.getPlayerExact(params.get("player"));
                        if (freezeTarget != null) {
                            UUID uuid = freezeTarget.getUniqueId();
                            if (FreezeManager.toggleFreeze(uuid)) {
                                freezeTarget.sendMessage(
                                        net.kyori.adventure.text.Component.text("§cYou have been frozen by an admin."));
                                WebActionLogger.logPlayerAction("FREEZE", params.get("player"), clientIp);
                            } else {
                                freezeTarget.sendMessage(net.kyori.adventure.text.Component
                                        .text("§aYou have been unfrozen by an admin."));
                                WebActionLogger.logPlayerAction("UNFREEZE", params.get("player"), clientIp);
                            }
                        }
                        break;
                    case "tp_to_coords":
                        String coordsPlayer = params.get("player");
                        String xStr = params.get("x");
                        String yStr = params.get("y");
                        String zStr = params.get("z");
                        if (coordsPlayer != null && xStr != null && yStr != null && zStr != null) {
                            Player coordsTarget = Bukkit.getPlayerExact(coordsPlayer);
                            if (coordsTarget != null) {
                                try {
                                    double x = Double.parseDouble(xStr);
                                    double y = Double.parseDouble(yStr);
                                    double z = Double.parseDouble(zStr);
                                    coordsTarget.teleport(new Location(coordsTarget.getWorld(), x, y, z));
                                    WebActionLogger.logPlayerAction("TELEPORT_COORDS",
                                            coordsPlayer + " to " + x + "," + y + "," + z, clientIp);
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        break;
                    case "tp_player_to_player":
                        String targetName = params.get("target");
                        String destName = params.get("destination");
                        if (targetName != null && destName != null) {
                            Player targetPlayer = Bukkit.getPlayerExact(targetName);
                            Player destPlayer = Bukkit.getPlayerExact(destName);
                            if (targetPlayer != null && destPlayer != null) {
                                targetPlayer.teleport(destPlayer.getLocation());
                                WebActionLogger.logPlayerAction("TELEPORT_TO_PLAYER", targetName + " to " + destName,
                                        clientIp);
                            }
                        }
                        break;
                    case "gamerule":
                        String rule = params.get("rule");
                        String value = params.get("value");
                        if (rule != null && value != null) {
                            boolean boolValue = Boolean.parseBoolean(value);
                            for (World world : Bukkit.getWorlds()) {
                                switch (rule) {
                                    case "keepInventory":
                                        world.setGameRule(GameRule.KEEP_INVENTORY, boolValue);
                                        break;
                                    case "doMobSpawning":
                                        world.setGameRule(GameRule.DO_MOB_SPAWNING, boolValue);
                                        break;
                                    case "doDaylightCycle":
                                        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, boolValue);
                                        break;
                                    case "doWeatherCycle":
                                        world.setGameRule(GameRule.DO_WEATHER_CYCLE, boolValue);
                                        break;
                                    case "mobGriefing":
                                        world.setGameRule(GameRule.MOB_GRIEFING, boolValue);
                                        break;
                                    case "doFireTick":
                                        world.setGameRule(GameRule.DO_FIRE_TICK, boolValue);
                                        break;
                                    case "naturalRegeneration":
                                        world.setGameRule(GameRule.NATURAL_REGENERATION, boolValue);
                                        break;
                                }
                            }
                            WebActionLogger.logSettingChange("gamerule." + rule, value, clientIp);
                        }
                        break;
                    case "whitelist_add":
                        String addPlayer = params.get("player");
                        if (addPlayer != null) {
                            OfflinePlayer op = Bukkit.getOfflinePlayer(addPlayer);
                            op.setWhitelisted(true);
                            WebActionLogger.logPlayerAction("WHITELIST_ADD", addPlayer, clientIp);
                        }
                        break;
                    case "whitelist_remove":
                        String removePlayer = params.get("player");
                        if (removePlayer != null) {
                            OfflinePlayer op = Bukkit.getOfflinePlayer(removePlayer);
                            op.setWhitelisted(false);
                            WebActionLogger.logPlayerAction("WHITELIST_REMOVE", removePlayer, clientIp);
                        }
                        break;
                    case "whitelist_toggle":
                        boolean newState = !Bukkit.hasWhitelist();
                        Bukkit.setWhitelist(newState);
                        WebActionLogger.logSettingChange("whitelist", String.valueOf(newState), clientIp);
                        break;
                    case "plugin_enable":
                        String enablePlugin = params.get("plugin");
                        if (enablePlugin != null) {
                            Plugin p = Bukkit.getPluginManager().getPlugin(enablePlugin);
                            if (p != null) {
                                Bukkit.getPluginManager().enablePlugin(p);
                                WebActionLogger.logPluginAction("ENABLE", enablePlugin, clientIp);
                            }
                        }
                        break;
                    case "plugin_disable":
                        String disablePlugin = params.get("plugin");
                        if (disablePlugin != null) {
                            Plugin p = Bukkit.getPluginManager().getPlugin(disablePlugin);
                            if (p != null) {
                                Bukkit.getPluginManager().disablePlugin(p);
                                WebActionLogger.logPluginAction("DISABLE", disablePlugin, clientIp);
                            }
                        }
                        break;
                    case "chat":
                        String chatMsg = params.get("message");
                        String chatAs = params.get("as");
                        if (chatMsg != null && !chatMsg.isEmpty()) {
                            String prefix = "as".equals(chatAs) ? "[ADMIN]" : "[SERVER]";
                            Bukkit.broadcast(Component.text(prefix + " " + chatMsg));
                            WebActionLogger.log("BROADCAST", prefix + " " + chatMsg + " from " + clientIp);
                        }
                        break;
                    case "mute":
                        WebActionLogger.logPlayerAction("MUTE", params.get("player"), clientIp);
                        break;
                    case "backup_schedule":
                        dash.data.BackupManager bms = Dash.getBackupManager();
                        String hours = params.get("hours");
                        if (bms != null && hours != null) {
                            try {
                                int interval = Integer.parseInt(hours);
                                if (!Set.of(0, 1, 6, 12, 24).contains(interval)) {
                                    break;
                                }
                                if (interval == 0) bms.stopSchedule();
                                else bms.startSchedule(interval);
                                WebActionLogger.logBackup("SCHEDULE", interval + " hours from " + clientIp);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        break;
                    case "datapack_toggle":
                        String dpName = params.get("name");
                        boolean dpEnable = Boolean.parseBoolean(params.get("enable"));
                        if (dpName != null) {
                            dash.data.DatapackManager.toggleDatapack(dpName, dpEnable);
                            WebActionLogger.log("DATAPACK_TOGGLE", dpName + " = " + dpEnable + " from " + clientIp);
                        }
                        break;
                    case "datapack_delete":
                        String dpDelName = params.get("name");
                        if (dpDelName != null) {
                            dash.data.DatapackManager.deleteDatapack(dpDelName);
                            WebActionLogger.log("DATAPACK_DELETE", dpDelName + " from " + clientIp);
                        }
                        break;
                    case "set_motd":
                        String motd = params.get("motd");
                        if (motd != null) {
                            try {
                                File serverProps = new File(Bukkit.getWorldContainer(), "server.properties");
                                if (serverProps.exists()) {
                                    java.util.Properties props = new java.util.Properties();
                                    try (FileInputStream fis = new FileInputStream(serverProps)) {
                                        props.load(fis);
                                    }
                                    props.setProperty("motd", motd);
                                    try (FileOutputStream fos = new FileOutputStream(serverProps)) {
                                        props.store(fos, null);
                                    }
                                    WebActionLogger.logSettingChange("motd", motd, clientIp);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        break;
                    case "spark_profile":
                        if (isSparkRuntimeAvailable()) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "spark profiler start --timeout 60");
                            WebActionLogger.log("SPARK_PROFILE", "Started from " + clientIp);
                        }
                        break;
                    case "add_note":
                        String noteUuid = params.get("uuid");
                        String noteText = params.get("note");
                        if (noteUuid != null && noteText != null) {
                            dash.data.PlayerDataManager pdm = Dash.getPlayerDataManager();
                            if (pdm != null) {
                                pdm.addNote(noteUuid, "WebAdmin", noteText);
                                WebActionLogger.log("ADD_NOTE", "Player " + noteUuid + " from " + clientIp);
                            }
                        }
                        break;
                    case "delete_note":
                        String noteIdStr = params.get("id");
                        if (noteIdStr != null) {
                            dash.data.PlayerDataManager pdm2 = Dash.getPlayerDataManager();
                            if (pdm2 != null) {
                                pdm2.deleteNote(Integer.parseInt(noteIdStr));
                                WebActionLogger.log("DELETE_NOTE", "Note " + noteIdStr + " from " + clientIp);
                            }
                        }
                        break;
                    case "set_distance":
                        String viewDist = params.get("view");
                        String simDist = params.get("sim");
                        boolean supportsSimulationDistance = ServerSettingsPage
                                .supportsPaperExtras(ServerSettingsPage.detectServerType());
                        if ((viewDist != null && !viewDist.isBlank()) || (simDist != null && !simDist.isBlank())) {
                            try {
                                File propsFile = new File(Bukkit.getWorldContainer(), "server.properties");
                                java.util.Properties props = new java.util.Properties();
                                try (java.io.FileInputStream fis = new java.io.FileInputStream(propsFile)) {
                                    props.load(fis);
                                }
                                if (viewDist != null && !viewDist.isBlank()) {
                                    props.setProperty("view-distance", viewDist);
                                }
                                if (supportsSimulationDistance && simDist != null && !simDist.isBlank()) {
                                    props.setProperty("simulation-distance", simDist);
                                }
                                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(propsFile)) {
                                    props.store(fos, null);
                                }
                                WebActionLogger.logSettingChange("distances",
                                        "view=" + (viewDist == null ? "-" : viewDist) + ",sim="
                                                + (simDist == null ? "-" : simDist),
                                        clientIp);
                            } catch (Exception ignored) {
                            }
                        }
                        break;
                    case "give_item":
                        String giveItemPlayer = params.get("player");
                        String giveItemMaterial = params.get("material");
                        String giveItemAmountStr = params.get("amount");
                        if (giveItemPlayer != null && giveItemMaterial != null) {
                            Player target = Bukkit.getPlayerExact(giveItemPlayer);
                            if (target != null) {
                                try {
                                    Material mat = Material.matchMaterial(giveItemMaterial.toUpperCase());
                                    int amount = giveItemAmountStr != null ? Integer.parseInt(giveItemAmountStr) : 1;
                                    amount = Math.max(1, Math.min(amount, 64));
                                    if (mat != null && mat.isItem()) {
                                        ItemStack item = new ItemStack(mat, amount);
                                        target.getInventory().addItem(item);
                                        WebActionLogger.log("GIVE_ITEM", amount + "x " + mat.name() + " to "
                                                + giveItemPlayer + " from " + clientIp);
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        break;
                    case "give_enderchest":
                        String giveECPlayer = params.get("player");
                        String giveECMaterial = params.get("material");
                        String giveECAmountStr = params.get("amount");
                        if (giveECPlayer != null && giveECMaterial != null) {
                            Player target = Bukkit.getPlayerExact(giveECPlayer);
                            if (target != null) {
                                try {
                                    Material mat = Material.matchMaterial(giveECMaterial.toUpperCase());
                                    int amount = giveECAmountStr != null ? Integer.parseInt(giveECAmountStr) : 1;
                                    amount = Math.max(1, Math.min(amount, 64));
                                    if (mat != null && mat.isItem()) {
                                        ItemStack item = new ItemStack(mat, amount);
                                        target.getEnderChest().addItem(item);
                                        WebActionLogger.log("GIVE_ENDERCHEST", amount + "x " + mat.name() + " to "
                                                + giveECPlayer + " from " + clientIp);
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        break;
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("Web action '" + action + "' failed: " + ex.getMessage());
                    actionCompletion.completeExceptionally(ex);
                } finally {
                    if (!actionCompletion.isDone()) {
                        actionCompletion.complete(null);
                    }
                }
            });

            try {
                actionCompletion.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                sendResponseWithStatus(t, 503, "Server action was interrupted. Please retry.");
                return;
            } catch (Exception ex) {
                plugin.getLogger().warning("Web action '" + action + "' did not complete in time.");
                sendResponseWithStatus(t, 500, "Server action failed or timed out.");
                return;
            }
            redirect(t, redirectTo);
        }
    }

    private void ensureUpdaterConfigStructure() {
        try {
            boolean changed = false;
            if (plugin.getConfig().contains("github-repo")) {
                plugin.getConfig().set("github-repo", null);
                changed = true;
            }
            if (!(plugin.getConfig().get("updater") instanceof ConfigurationSection)) {
                plugin.getConfig().set("updater", null);
                changed = true;
            }

            String repo = plugin.getConfig().getString("updater.github-repo", "");
            if (!"Framepersecond/Dash".equalsIgnoreCase(repo == null ? "" : repo.trim())) {
                plugin.getConfig().set("updater.github-repo", "Framepersecond/Dash");
                changed = true;
            }

            if (changed) {
                plugin.saveConfig();
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to normalize updater config structure: " + ex.getMessage());
        }
    }

    private String applyPluginSettings(Map<String, String> params, String clientIp) {
        try {
            String webPortStr = params.get("web_port");
            String serverIpVal = params.get("server_ip");
            String panelUrlVal = params.get("panel_url");
            String reportUrlVal = params.get("report_url");
            boolean sslEnabled = params.containsKey("ssl_enabled");
            String maxBackupsStr = params.get("max_backups");

            if (sslEnabled
                    && (serverIpVal == null || serverIpVal.trim().isBlank()
                    || panelUrlVal == null || panelUrlVal.trim().isBlank()
                    || reportUrlVal == null || reportUrlVal.trim().isBlank())) {
                return "Error: Server IP, Panel URL and Public Report URL are required when SSL is enabled";
            }

            if (webPortStr != null) {
                try {
                    int newPort = Integer.parseInt(webPortStr.trim());
                    if (newPort >= 1 && newPort <= 65535) {
                        plugin.getConfig().set("port", newPort);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            if (serverIpVal != null) {
                plugin.getConfig().set("server-ip", serverIpVal.trim());
            }
            plugin.getConfig().set("ssl-enabled", sslEnabled);
            if (panelUrlVal != null) {
                plugin.getConfig().set("panel-url", panelUrlVal.trim());
            }
            if (reportUrlVal != null) {
                plugin.getConfig().set("report-url", reportUrlVal.trim());
            }
            if (maxBackupsStr != null) {
                try {
                    int val = Integer.parseInt(maxBackupsStr.trim());
                    if (val >= 1 && val <= 100) {
                        plugin.getConfig().set("backups.max-backups", val);
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            boolean bridgeEnabled = params.containsKey("bridge_enabled");
            String bridgeSecret = params.get("bridge_secret");
            String bridgeMasterUrl = params.get("bridge_master_url");
            String normalizedBridgeSecret = bridgeSecret == null ? "" : bridgeSecret.trim();
            if (bridgeEnabled && !normalizedBridgeSecret.isBlank()
                    && (normalizedBridgeSecret.length() < 32 || "your-super-secret-key".equals(normalizedBridgeSecret))) {
                return "Error: Bridge secret must contain at least 32 characters";
            }
            if (bridgeEnabled && normalizedBridgeSecret.isBlank()) {
                normalizedBridgeSecret = plugin.getConfig().getString("bridge.secret", "").trim();
                if (normalizedBridgeSecret.isBlank()) normalizedBridgeSecret = BridgeSecurity.generateSecret();
            }
            plugin.getConfig().set("bridge.enabled", bridgeEnabled);
            plugin.getConfig().set("bridge.secret", normalizedBridgeSecret);
            plugin.getConfig().set("bridge-secret", normalizedBridgeSecret);
            plugin.getConfig().set("bridge.master_url", bridgeMasterUrl == null ? "" : bridgeMasterUrl.trim());

            ensureUpdaterConfigStructure();
            plugin.saveConfig();

            List<Integer> webhookIndexes = params.keySet().stream()
                    .filter(k -> k.startsWith("wh_url_"))
                    .map(k -> k.substring("wh_url_".length()))
                    .map(idx -> {
                        try {
                            return Integer.parseInt(idx);
                        } catch (NumberFormatException ex) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();

            List<DiscordWebhookManager.WebhookEntry> webhookEntries = new ArrayList<>();
            for (Integer idx : webhookIndexes) {
                String whUrl = params.get("wh_url_" + idx);
                if (whUrl == null || whUrl.isBlank()) {
                    continue;
                }
                if (!DiscordWebhookPolicy.isAllowed(whUrl)) {
                    return "Error: Only Discord HTTPS webhook URLs are allowed";
                }
                List<String> events = new ArrayList<>();
                for (String evt : DiscordWebhookManager.ALL_EVENTS) {
                    if (params.containsKey("wh_evt_" + idx + "_" + evt)) {
                        events.add(evt);
                    }
                }
                webhookEntries.add(new DiscordWebhookManager.WebhookEntry(whUrl.trim(), events));
            }

            DiscordWebhookManager whMgr = Dash.getDiscordWebhookManager();
            if (whMgr != null) {
                whMgr.saveWebhooks(webhookEntries);
            }

            WebActionLogger.logSettingChange("plugin_settings", "updated", clientIp);
            return null;
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to save plugin settings: " + ex.getMessage());
            return "Error: failed to save settings";
        }
    }

    private class SettingsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod()) && !"PUT".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!ensurePermission(t, "dash.web.pluginsettings.write", true)) {
                return;
            }

            byte[] requestBody = readRequestBodyOrReject(t, 1024L * 1024L);
            if (requestBody == null) {
                return;
            }
            String body = new String(requestBody, StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(body);
            String error = applyPluginSettings(params, getClientIp(t));

            t.getResponseHeaders().add("Content-Type", "application/json");
            if (error != null) {
                sendResponseWithStatus(t, 500, "{\"success\":false,\"error\":\"" + jsonEscape(error) + "\"}");
                return;
            }
            sendResponseWithStatus(t, 200, "{\"success\":true}");
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean isSparkRuntimeAvailable() {
        Plugin spark = Bukkit.getPluginManager().getPlugin("spark");
        if (spark != null && spark.isEnabled()) {
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
        if (!Files.isRegularFile(latestLog)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(latestLog, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - 160);
            for (int i = start; i < lines.size(); i++) {
                String lower = lines.get(i) == null ? "" : lines.get(i).toLowerCase(Locale.ROOT);
                if (lower.contains("bundles the spark profiler") || lower.contains("[spark]")) {
                    return true;
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private boolean userHasWebPermission(String username, String permission) {
        if (username == null || username.isBlank() || permission == null || permission.isBlank()) return false;
        if (auth.userHasPermission(username, permission)
                || intelligenceManager.hasTemporaryGrant(username, permission)) return true;
        if ("dash.web.intelligence.read".equals(permission)) {
            return auth.userHasPermission(username, "dash.web.stats.read")
                    || auth.userHasPermission(username, "dash.web.settings.read");
        }
        if ("dash.web.intelligence.write".equals(permission)) {
            return auth.userHasPermission(username, "dash.web.settings.write");
        }
        if ("dash.web.ai.read".equals(permission)) {
            return auth.userHasPermission(username, "dash.web.stats.read")
                    || auth.userHasPermission(username, "dash.web.settings.read");
        }
        if ("dash.web.ai.use".equals(permission)) {
            return auth.userHasPermission(username, "dash.web.stats.read");
        }
        return false;
    }

    private Set<String> effectiveUiPermissions(String username) {
        Set<String> permissions = new LinkedHashSet<>(auth.getEffectivePermissions(username));
        if (auth.userHasPermission(username, "dash.web.stats.read")
                || auth.userHasPermission(username, "dash.web.settings.read")) {
            permissions.add("dash.web.intelligence.read");
        }
        if (auth.userHasPermission(username, "dash.web.settings.write")) {
            permissions.add("dash.web.intelligence.write");
        }
        if (auth.userHasPermission(username, "dash.web.stats.read")
                || auth.userHasPermission(username, "dash.web.settings.read")) {
            permissions.add("dash.web.ai.read");
        }
        if (auth.userHasPermission(username, "dash.web.stats.read")) {
            permissions.add("dash.web.ai.use");
        }
        intelligenceManager.temporaryGrants(true).stream()
                .filter(grant -> grant.username().equalsIgnoreCase(username))
                .map(IntelligenceManager.TemporaryGrant::permission)
                .forEach(permissions::add);
        return Set.copyOf(permissions);
    }

    private IntelligencePage.RuntimeMetrics intelligenceRuntimeMetrics() {
        StatsCollector collector = Dash.getStatsCollector();
        StatsCollector.StatsSample latest = collector == null ? null : collector.getLatest();
        return new IntelligencePage.RuntimeMetrics(
                server != null,
                latest == null ? 20.0d : latest.tps,
                latest == null ? 0.0d : latest.mspt,
                latest == null ? 0L : latest.ramUsed,
                Bukkit.getOnlinePlayers().size());
    }

    private boolean redirectToNeoDashRestartIfAvailable(HttpExchange t) throws IOException {
        SessionInfo session = resolveSession(t);
        if (session == null) {
            return false;
        }
        String restartUrl = plugin.getConfig().getString("bridge.restart_url", "").trim();
        String lower = restartUrl.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        redirect(t, restartUrl);
        return true;
    }

    private boolean betaFeaturesEnabled() {
        return plugin.getConfig().getBoolean(BETA_FEATURES_CONFIG_KEY, false);
    }

    private boolean ensureBetaFeatureEnabled(HttpExchange t) throws IOException {
        if (betaFeaturesEnabled()) {
            return true;
        }
        redirect(t, "/settings?msg=" + encodeForQuery("Enable Beta Features in Settings to use this section."));
        return false;
    }

    private boolean isBetaFeatureAction(String action) {
        if (action == null) {
            return false;
        }
        return switch (action) {
            case "spark_profile", "doctor_delete_crash", "doctor_mark_reviewed" -> true;
            default -> false;
        };
    }

    private boolean isSetupTelemetryBypass(HttpExchange t) {
        if (!auth.isSetupRequired() || !"GET".equalsIgnoreCase(t.getRequestMethod())) {
            return false;
        }
        String path = t.getRequestURI().getPath();
        return "/api/stats".equals(path) || "/api/ping".equals(path);
    }

    private String requiredPermissionForAction(String action) {
        if (action == null) {
            return null;
        }
        if (action.startsWith("intel_")) {
            return "dash.web.intelligence.write";
        }
        return switch (action) {
            case "command" -> "dash.web.console.command";
            case "restart", "stop" -> "dash.web.server.control";
            case "kick" -> "dash.web.players.kick";
            case "ban" -> "dash.web.players.ban";
            case "freeze", "tp_to_coords", "tp_player_to_player" -> "dash.web.players.moderate";
            case "gamerule", "set_motd", "set_distance" -> null;
            case "whitelist_add", "whitelist_remove", "whitelist_toggle" -> "dash.web.whitelist.manage";
            case "plugin_enable", "plugin_disable" -> "dash.web.plugins.manage";
            case "plugin_delete", "plugin_browser_install" -> "dash.web.plugins.manage";
            case "chat" -> "dash.web.chat.send";
            case "mute" -> "dash.web.players.moderate";
            case "backup_create" -> "dash.web.backups.create";
            case "backup_delete" -> "dash.web.backups.delete";
            case "backup_schedule" -> "dash.web.backups.schedule";
            case "datapack_toggle", "datapack_delete" -> "dash.web.datapacks.write";
            case "spark_profile" -> "dash.web.tools.spark";
            case "add_note", "delete_note" -> "dash.web.players.notes";
            case "give_item", "give_enderchest" -> "dash.web.players.inventory.write";
            case "invite_generate", "user_set_role", "user_make_main_admin", "user_delete", "role_create",
                    "role_permissions_save", "role_set_value", "role_delete", "bridge_user_allow",
                    "bridge_user_deny" -> "dash.web.users.manage";
            case "registration_approve", "registration_deny" -> "dash.web.users.manage";
            case "file_delete", "file_rename" -> "dash.web.files.write";
            case "owner_2fa_regen" -> "dash.web.settings.write";
            case "save_beta_settings" -> "dash.web.settings.write";
            case "save_feature_settings" -> "dash.web.settings.write";
            case "save_plugin_settings" -> "dash.web.pluginsettings.write";
            case "save_notification_settings", "test_notification" -> "dash.web.pluginsettings.write";
            case "doctor_delete_crash", "doctor_mark_reviewed" -> "dash.web.settings.write";
            case "staff_ticket_create" -> null;
            case "staff_note_create", "staff_ticket_status", "staff_ticket_reply", "staff_ticket_delete", "staff_report_link_create" -> "dash.web.players.moderate";
            case "task_add", "task_toggle", "task_delete" -> "dash.web.tasks.write";
            case "operations_plan_create", "operations_plan_prepare", "operations_plan_status",
                    "operations_incident_create", "operations_incident_close",
                    "operations_handover_create", "operations_handover_ack",
                    "operations_drift_baseline", "operations_restore_drill",
                    "operations_alert_ack", "operations_capacity_sample",
                    "operations_recipe_create", "operations_recipe_toggle" -> "dash.web.settings.write";
            default -> null;
        };
    }

    private boolean shouldApplyGuardrails(String action) {
        return action != null && !action.isBlank()
                && !action.startsWith("intel_guardrail_")
                && !action.startsWith("intel_approval_");
    }

    private boolean sendGuardrailChallengeIfNeeded(HttpExchange exchange,
                                                    IntelligenceManager.GuardDecision decision,
                                                    Map<String, String> params) throws IOException {
        String reason = params.get("reason");
        if (reason != null && !reason.isBlank()) {
            return false;
        }
        String message = decision.message() == null ? "" : decision.message();
        if (!message.toLowerCase(Locale.ROOT).contains("reason is required")) {
            return false;
        }

        exchange.getResponseHeaders().set("X-Dash-Reason-Required", "1");
        String requestedWith = exchange.getRequestHeaders().getFirst("X-Requested-With");
        if (requestedWith != null && requestedWith.toLowerCase(Locale.ROOT).endsWith("-mutation")) {
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            sendResponseWithStatus(exchange, 428, message);
        } else {
            sendResponseWithStatus(exchange, 428, GuardrailChallengePage.render(message, params));
        }
        return true;
    }

    private void handleIntelligenceAction(HttpExchange t, Map<String, String> params, String actor, String clientIp)
            throws IOException {
        String action = params.getOrDefault("action", "");
        String message;
        try {
            switch (action) {
                case "intel_shadow_start" -> message = intelligenceManager.startShadowBootLab(
                        command -> CompletableFuture.runAsync(command), actor);
                case "intel_state_capture" -> message = intelligenceManager.captureState(params.get("label"), actor);
                case "intel_state_restore" -> message = intelligenceManager.restoreState(params.get("snapshot_id"), actor);
                case "intel_performance_sample" -> {
                    IntelligencePage.RuntimeMetrics live = intelligenceRuntimeMetrics();
                    message = intelligenceManager.recordPerformance(live.tps(), live.mspt(), live.memoryMb(),
                            live.players(), params.get("label"));
                }
                case "intel_safe_quarantine" -> message = intelligenceManager.quarantineArtifact(params.get("artifact"), actor);
                case "intel_safe_restore" -> message = intelligenceManager.restoreQuarantinedArtifact(params.get("quarantine_id"));
                case "intel_backup_restore" -> message = intelligenceManager.restoreBackupEntry(
                        params.get("backup"), params.get("entry"), actor);
                case "intel_guardrail_save" -> message = intelligenceManager.saveGuardrail(
                        params.get("action_pattern"),
                        parseInt(params.get("max_players"), -1),
                        isChecked(params, "require_backup"),
                        parseInt(params.get("quiet_start"), -1),
                        parseInt(params.get("quiet_end"), -1),
                        isChecked(params, "require_reason"),
                        isChecked(params, "dual_control"));
                case "intel_guardrail_delete" -> message = intelligenceManager.deleteGuardrail(params.get("guardrail_id"));
                case "intel_support_create" -> message = intelligenceManager.createSupportCase(
                        params.get("type"), params.get("player"), params.get("subject"), params.get("message"), actor);
                case "intel_support_update" -> message = intelligenceManager.updateSupportCase(
                        params.get("case_id"), params.get("status"), params.get("owner"));
                case "intel_support_reply" -> message = intelligenceManager.addSupportReply(
                        params.get("case_id"), actor, params.get("message"), isChecked(params, "public_reply"));
                case "intel_jit_grant" -> message = intelligenceManager.grantTemporaryAccess(
                        params.get("username"), params.get("permission"), parseInt(params.get("minutes"), 0), actor);
                case "intel_jit_revoke" -> message = intelligenceManager.revokeTemporaryAccess(params.get("grant_id"), actor);
                case "intel_approval_decide" -> {
                    String decision = params.getOrDefault("decision", "");
                    message = Set.of("approve", "reject").contains(decision)
                            ? intelligenceManager.decideApproval(params.get("approval_id"), actor,
                                    "approve".equals(decision))
                            : "Unsupported approval decision.";
                }
                case "intel_retention_save" -> message = intelligenceManager.saveRetentionPolicy(
                        parseInt(params.get("log_days"), 0),
                        parseInt(params.get("backup_days"), 0),
                        parseInt(params.get("crash_days"), 0),
                        parseInt(params.get("keep_min_backups"), 0), actor);
                case "intel_retention_apply" -> message = intelligenceManager.applyRetention(actor);
                case "intel_config_update" -> message = intelligenceManager.updateConfigScalar(
                        params.get("path"), params.get("key"), params.get("value"), actor);
                case "intel_service_sample" -> {
                    IntelligencePage.RuntimeMetrics live = intelligenceRuntimeMetrics();
                    message = intelligenceManager.recordServiceSample(live.online(), live.tps(), live.mspt());
                }
                case "intel_war_create" -> message = intelligenceManager.createWarRoom(
                        params.get("title"), params.get("severity"), params.get("summary"), actor);
                case "intel_war_update" -> message = intelligenceManager.addWarRoomUpdate(
                        params.get("room_id"), actor, params.get("message"), params.get("kind"),
                        isChecked(params, "publish"));
                case "intel_war_close" -> message = intelligenceManager.closeWarRoom(
                        params.get("room_id"), actor, params.get("resolution"));
                case "intel_status_update" -> message = intelligenceManager.updateStatusComponent(
                        params.get("component"), params.get("status"), params.get("message"), actor);
                default -> message = "Unsupported intelligence action.";
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Intelligence action failed safely: " + ex.getMessage());
            message = "Intelligence action failed safely: "
                    + (ex.getMessage() == null ? "internal error" : ex.getMessage());
        }
        WebActionLogger.log("INTELLIGENCE", "action=" + action + " actor=" + actor + " ip=" + clientIp);
        redirect(t, intelligenceRedirect(action, message));
    }

    private String intelligenceRedirect(String action, String message) {
        return "/intelligence?tab=" + intelligenceTabForAction(action) + "&msg="
                + encodeForQuery(message == null ? "" : message);
    }

    private String intelligenceTabForAction(String action) {
        if (action == null) return "lab";
        if (action.startsWith("intel_state_") || action.startsWith("intel_performance_")) return "change";
        if (action.startsWith("intel_support_")) return "players";
        if (action.startsWith("intel_guardrail_") || action.startsWith("intel_jit_")
                || action.startsWith("intel_approval_") || action.startsWith("intel_retention_")
                || action.startsWith("intel_config_")) return "policy";
        if (action.startsWith("intel_service_")) return "reliability";
        if (action.startsWith("intel_war_") || action.startsWith("intel_status_")) return "response";
        return "lab";
    }

    private void handleOperationsAction(HttpExchange t, Map<String, String> params, String actor, String clientIp)
            throws IOException {
        String action = params.getOrDefault("action", "");
        String tab = "overview";
        String message;
        try {
            switch (action) {
                case "operations_plan_create" -> {
                    tab = "planner";
                    message = operationsManager.createPlan(params.get("title"), params.get("change_type"),
                            params.get("scheduled_at"), params.get("details"), actor);
                }
                case "operations_plan_prepare" -> {
                    tab = "planner";
                    dash.data.BackupManager backupManager = Dash.getBackupManager();
                    if (backupManager == null) {
                        message = "Backup service is unavailable; preflight was not started.";
                    } else {
                        dash.data.BackupManager.BackupResult result = backupManager.createBackupAsync()
                                .get(15, TimeUnit.MINUTES);
                        message = result.success()
                                ? operationsManager.preparePlan(params.get("plan_id"), result.fileName())
                                : "Verified backup failed: " + (result.error() == null ? "unknown error" : result.error());
                    }
                }
                case "operations_plan_status" -> {
                    tab = "planner";
                    message = operationsManager.updatePlanStatus(params.get("plan_id"), params.get("status"));
                }
                case "operations_incident_create" -> {
                    tab = "incidents";
                    message = operationsManager.createIncident(params.get("title"), params.get("severity"),
                            params.get("summary"), actor);
                }
                case "operations_incident_close" -> {
                    tab = "incidents";
                    message = operationsManager.closeIncident(params.get("incident_id"), params.get("resolution"), actor);
                }
                case "operations_handover_create" ->
                        message = operationsManager.createHandover(params.get("summary"), actor);
                case "operations_handover_ack" ->
                        message = operationsManager.acknowledgeHandover(params.get("handover_id"), actor);
                case "operations_drift_baseline" -> {
                    tab = "recovery";
                    message = operationsManager.saveDriftBaseline();
                }
                case "operations_restore_drill" -> {
                    tab = "recovery";
                    message = operationsManager.runRestoreDrill(params.get("backup"));
                }
                case "operations_alert_ack" ->
                        message = operationsManager.acknowledgeAlert(params.get("signature"));
                case "operations_capacity_sample" -> {
                    tab = "recovery";
                    message = operationsManager.recordCapacity(true);
                }
                case "operations_recipe_create" -> {
                    tab = "automation";
                    message = activateOperationsRecipe(params);
                }
                case "operations_recipe_toggle" -> {
                    tab = "automation";
                    message = toggleOperationsRecipe(params);
                }
                default -> message = "Unsupported operations action.";
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Operations action failed: " + ex.getMessage());
            message = "Operation failed safely: " + (ex.getMessage() == null ? "internal error" : ex.getMessage());
        }
        WebActionLogger.log("OPERATIONS", "action=" + action + " actor=" + actor + " ip=" + clientIp);
        redirect(t, "/operations?tab=" + tab + "&msg=" + encodeForQuery(message));
    }

    private String activateOperationsRecipe(Map<String, String> params) {
        String recipe = params.getOrDefault("recipe", "").trim();
        int interval;
        try {
            interval = Integer.parseInt(params.getOrDefault("interval", "0"));
        } catch (NumberFormatException ex) {
            return "Interval must be a number between 1 and 10080 minutes.";
        }
        if (interval < 1 || interval > 10080) {
            return "Interval must be between 1 and 10080 minutes.";
        }
        String payload = params.getOrDefault("payload", "").trim();
        int taskId = -1;
        if ("daily_backup".equals(recipe)) {
            if (interval % 60 != 0) {
                return "Backup recipes require a whole-hour interval.";
            }
            dash.data.BackupManager manager = Dash.getBackupManager();
            if (manager == null) return "Backup service is unavailable.";
            manager.startSchedule(interval / 60);
        } else {
            dash.data.ScheduledTaskManager manager = Dash.getScheduledTaskManager();
            if (manager == null) return "Scheduled task service is unavailable.";
            String taskType = dash.data.ScheduledTaskManager.TYPE_COMMAND;
            String taskPayload;
            switch (recipe) {
                case "nightly_restart" -> taskPayload = "restart";
                case "hourly_save" -> taskPayload = "save-all";
                case "maintenance_notice" -> {
                    taskType = dash.data.ScheduledTaskManager.TYPE_BROADCAST;
                    taskPayload = payload.isBlank() ? "Scheduled maintenance begins soon." : payload;
                }
                default -> { return "Unsupported automation recipe."; }
            }
            taskId = manager.addTask(taskType, interval, taskPayload, true);
            if (taskId < 1) return "Scheduled task could not be created.";
            payload = taskPayload;
        }
        return operationsManager.recordAutomation(recipe, interval, payload, taskId, true);
    }

    private String toggleOperationsRecipe(Map<String, String> params) {
        OperationsManager.Automation automation = operationsManager.findAutomation(params.get("automation_id"))
                .orElse(null);
        if (automation == null) return "Automation recipe not found.";
        boolean enabled = Boolean.parseBoolean(params.getOrDefault("enabled", "false"));
        if ("daily_backup".equals(automation.recipe())) {
            dash.data.BackupManager manager = Dash.getBackupManager();
            if (manager == null) return "Backup service is unavailable.";
            if (enabled) manager.startSchedule(Math.max(1, automation.intervalMinutes() / 60));
            else manager.stopSchedule();
        } else {
            dash.data.ScheduledTaskManager manager = Dash.getScheduledTaskManager();
            if (manager == null || automation.taskId() < 1) return "Scheduled task is unavailable.";
            manager.setEnabled(automation.taskId(), enabled);
        }
        return operationsManager.setAutomationEnabled(automation.id(), enabled);
    }

    private boolean usesSeparateActionAuthorization(String action) {
        return "gamerule".equals(action)
                || "set_motd".equals(action)
                || "set_distance".equals(action)
                || "staff_ticket_create".equals(action);
    }

    private void saveNotificationSettings(Map<String, String> params) {
        if ("ingame".equals(params.get("settings_scope"))) {
            plugin.getConfig().set("notifications.ingame.enabled", params.containsKey("notifications.ingame.enabled"));
            plugin.getConfig().set("notifications.ingame.tickets", params.containsKey("notifications.ingame.tickets"));
            plugin.getConfig().set("notifications.ingame.security", params.containsKey("notifications.ingame.security"));
            plugin.getConfig().set("notifications.ingame.users",
                    splitCsv(params.getOrDefault("notifications.ingame.users", "")));
            plugin.saveConfig();
            return;
        }
        List<String> booleans = List.of(
                "notifications.panel.enabled",
                "notifications.discord.enabled",
                "notifications.email.enabled",
                "notifications.mobile.enabled",
                "notifications.events.startup_log",
                "notifications.events.restart",
                "notifications.events.plugin_updates",
                "cloud-backups.enabled",
                "cloud-backups.encrypt",
                "cloud-backups.pre_restart",
                "cloud-backups.pre_update",
                "backups.pre_restart",
                "backups.pre_update");
        for (String key : booleans) {
            plugin.getConfig().set(key, params.containsKey(key));
        }
        List<String> strings = List.of(
                "discord.webhook_url",
                "notifications.email.to",
                "notifications.email.smtp_host",
                "notifications.mobile.webhook_url",
                "notifications.messages.restart",
                "notifications.messages.backup",
                "notifications.messages.crash",
                "cloud-backups.provider",
                "cloud-backups.bucket",
                "cloud-backups.path",
                "cloud-backups.retention_days");
        for (String key : strings) {
            plugin.getConfig().set(key, params.getOrDefault(key, ""));
        }
        plugin.saveConfig();
    }

    private void notifyIngameTicket(String author, String target) {
        if (!plugin.getConfig().getBoolean("notifications.ingame.enabled", true)
                || !plugin.getConfig().getBoolean("notifications.ingame.tickets", true)) return;
        List<String> recipients = plugin.getConfig().getStringList("notifications.ingame.users");
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!recipients.isEmpty()
                    && recipients.stream().noneMatch(name -> name.equalsIgnoreCase(online.getName()))) continue;
            if (recipients.isEmpty() && !online.isOp() && !online.hasPermission("dash.ticket.notify")) continue;
            online.sendMessage(Component.text("[Dash] ", NamedTextColor.AQUA)
                    .append(Component.text("New ticket from " + (author == null ? "web" : author)
                            + (target == null || target.isBlank() ? "." : " about " + target + "."),
                            NamedTextColor.YELLOW)));
        }
    }

    private String requiredPermissionForGamerule(String rule) {
        if (rule == null || rule.isBlank()) {
            return null;
        }
        return switch (rule) {
            case "keepInventory" -> "dash.web.settings.gamerule.keep_inventory";
            case "doMobSpawning" -> "dash.web.settings.gamerule.mob_spawning";
            case "doDaylightCycle" -> "dash.web.settings.gamerule.daylight_cycle";
            case "doWeatherCycle" -> "dash.web.settings.gamerule.weather_cycle";
            case "mobGriefing" -> "dash.web.settings.gamerule.mob_griefing";
            case "doFireTick" -> "dash.web.settings.gamerule.fire_tick";
            case "naturalRegeneration" -> "dash.web.settings.gamerule.natural_regeneration";
            default -> null;
        };
    }

    private String getQueryParam(String query, String key) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String[] parts = query.split("&");
        for (String part : parts) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && key.equals(pair[0])) {
                try {
                    return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private String humanizeRolePermissionError(String key) {
        if (key == null || key.isBlank()) {
            return "Permission update was blocked.";
        }
        return switch (key) {
            case "only_main_admin_can_edit_admin_role" -> "Only the MAIN_ADMIN account can edit ADMIN role permissions.";
            case "main_admin_role_hidden" -> "MAIN_ADMIN is a system role and cannot be edited.";
            case "invalid_role" -> "Invalid role selected.";
            case "role_not_found" -> "The selected role was not found.";
            case "cannot_manage_same_or_higher_role" -> "You can only manage roles below your own level.";
            case "cannot_set_role_value_same_or_higher_than_self" -> "Role value must stay below your own level.";
            case "cannot_manage_same_or_higher_user" -> "You can only manage users below your own level.";
            case "cannot_assign_same_or_higher_role" -> "You cannot assign a role at or above your own level.";
            case "cannot_create_same_or_higher_role" -> "You cannot create a role at or above your own level.";
            case "invalid_role_value" -> "Role value must be between 0 and 1000000.";
            case "system_role_protected" -> "System roles cannot be deleted.";
            case "role_in_use" -> "Role is assigned to one or more users and cannot be deleted.";
            case "save_failed" -> "Failed to save role permissions.";
            default -> "Permission update was blocked.";
        };
    }

    private String humanizeRoleCreationError(String key) {
        if (key == null || key.isBlank()) {
            return "Role creation was blocked.";
        }
        return switch (key) {
            case "invalid_role_name" -> "Role name cannot be empty and must be at most 64 characters.";
            case "reserved_role" -> "MAIN_ADMIN is reserved and cannot be created.";
            case "role_exists" -> "That role already exists.";
            case "preset_not_found" -> "Selected preset role was not found.";
            case "admin_preset_requires_main_admin" -> "Only MAIN_ADMIN can create roles from the ADMIN preset.";
            case "cannot_create_same_or_higher_role" -> "You cannot create a role at or above your own level.";
            case "save_failed" -> "Failed to save role.";
            default -> "Role creation was blocked.";
        };
    }

    private String encodeForQuery(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * Mounts the read-only HTTP API.
     *
     * <p>The context is always registered so that a disabled API answers a
     * clean 404 instead of falling through to the panel's catch-all handler
     * and leaking a redirect to the login page. {@link ReadApiHandler} checks
     * {@link ApiSettings#enabled()} on every request, so toggling the config
     * takes effect without a restart.
     */
    private void registerReadApi() {
        try {
            ApiSettings settings = new ApiSettings() {
                @Override
                public boolean enabled() {
                    return plugin.getConfig().getBoolean("api.enabled", false);
                }

                @Override
                public int rateLimitPerMinute() {
                    return plugin.getConfig().getInt("api.rate-limit-per-minute", 120);
                }

                @Override
                public boolean https() {
                    return configuredHttps();
                }
            };
            ApiKeyStore store = readApiKeyStore();
            server.createContext(ReadApiHandler.BASE_PATH, new ReadApiHandler(
                    () -> DashApiProvider.get().orElse(null), store, settings, WebActionLogger::log));
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[Dash] Read API could not be mounted: " + ex.getMessage());
        }
    }

    /** Lazily opens the key store; shared by the API handler and the settings page. */
    public ApiKeyStore readApiKeyStore() {
        return Dash.getApiKeyStore();
    }

    private boolean configuredHttps() {
        return plugin.getConfig().getBoolean("ssl-enabled", plugin.getConfig().getBoolean("ssl.enabled", false));
    }

    private String configuredPublicPanelUrl() {
        return plugin.getConfig().getString("panel-url", "");
    }

    /** Sub-path the panel is mounted under by a reverse proxy, e.g. {@code /admin}. */
    private String configuredBasePath() {
        String value = plugin.getConfig().getString("base-path", "");
        BasePath.remember(value);
        return value;
    }

    /** Resolves the sub-path prefix for one request; empty when served at the root. */
    private String basePathFor(HttpExchange t) {
        return BasePath.forRequest(t, configuredBasePath());
    }

    private boolean ensureSameOriginMutation(HttpExchange t, boolean jsonResponse) throws IOException {
        if (HttpSecurity.isSameOriginMutation(t, configuredPublicPanelUrl())) {
            return true;
        }
        WebActionLogger.log("CSRF_BLOCKED", "ip=" + getClientIp(t) + " path=" + t.getRequestURI().getPath());
        t.getResponseHeaders().set("Content-Type", jsonResponse ? "application/json" : "text/html; charset=utf-8");
        sendResponseWithStatus(t, 403,
                jsonResponse ? "{\"success\":false,\"error\":\"Cross-site request blocked\"}"
                        : "<html><body><h1>403 Forbidden</h1><p>Cross-site request blocked.</p></body></html>");
        return false;
    }

    private byte[] readRequestBodyOrReject(HttpExchange t, long maxBytes) throws IOException {
        try {
            return HttpSecurity.readRequestBody(t, maxBytes);
        } catch (HttpSecurity.RequestBodyTooLargeException ex) {
            sendResponseWithStatus(t, 413, "Request body too large.");
            return null;
        }
    }

    private byte[] readRequestBodyStrict(HttpExchange t, long maxBytes) throws IOException {
        try {
            return HttpSecurity.readRequestBody(t, maxBytes);
        } catch (HttpSecurity.RequestBodyTooLargeException ex) {
            sendResponseWithStatus(t, 413, "Request body too large.");
            throw new IOException("Request rejected after exceeding the body limit.");
        }
    }

    private void redirect(HttpExchange t, String location) throws IOException {
        HttpSecurity.applyResponseHeaders(t, configuredHttps());
        t.getResponseHeaders().set("Location", BasePath.rewriteLocation(location, basePathFor(t)));
        t.sendResponseHeaders(302, -1);
        t.close();
    }

    private void sendResponse(HttpExchange t, String response) throws IOException {
        HttpSecurity.applyResponseHeaders(t, configuredHttps());
        String basePath = basePathFor(t);
        if (!basePath.isEmpty() && BasePath.isHtml(t.getResponseHeaders().getFirst("Content-Type"))) {
            response = BasePath.rewriteHtml(response, basePath);
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        HttpSecurity.ensureContentType(t.getResponseHeaders(), HttpSecurity.HTML_UTF8);
        t.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = t.getResponseBody()) {
            os.write(bytes, 0, bytes.length);
            os.flush();
        }
    }

    private void serveBundledMotionScript(HttpExchange t) throws IOException {
        serveBundledAsset(t, BundledMotion.script(), "text/javascript; charset=utf-8", "Motion engine unavailable");
    }

    private void serveBundledMotionStyle(HttpExchange t) throws IOException {
        serveBundledAsset(t, BundledMotion.style(), "text/css; charset=utf-8", "Motion stylesheet unavailable");
    }

    /** Shared GET/HEAD responder for immutable, version-named assets baked into the jar. */
    private void serveBundledAsset(HttpExchange t, byte[] body, String contentType, String missingMessage)
            throws IOException {
        boolean head = "HEAD".equalsIgnoreCase(t.getRequestMethod());
        if (!head && !"GET".equalsIgnoreCase(t.getRequestMethod())) {
            t.getResponseHeaders().set("Allow", "GET, HEAD");
            sendResponseWithStatus(t, 405, "Method not allowed");
            return;
        }
        if (body.length == 0) {
            sendResponseWithStatus(t, 500, missingMessage);
            return;
        }
        HttpSecurity.applyResponseHeaders(t, configuredHttps());
        t.getResponseHeaders().set("Content-Type", contentType);
        t.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
        if (head) {
            t.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            t.sendResponseHeaders(200, -1);
            t.close();
            return;
        }
        t.sendResponseHeaders(200, body.length);
        try (OutputStream output = t.getResponseBody()) {
            output.write(body);
        }
    }

    private void serveBundledStyles(HttpExchange t) throws IOException {
        boolean head = "HEAD".equalsIgnoreCase(t.getRequestMethod());
        if (!head && !"GET".equalsIgnoreCase(t.getRequestMethod())) {
            t.getResponseHeaders().set("Allow", "GET, HEAD");
            sendResponseWithStatus(t, 405, "Method not allowed");
            return;
        }
        byte[] css = BundledStyles.css();
        if (css.length == 0) {
            sendResponseWithStatus(t, 500, "Dashboard stylesheet unavailable");
            return;
        }
        HttpSecurity.applyResponseHeaders(t, configuredHttps());
        t.getResponseHeaders().set("Content-Type", "text/css; charset=utf-8");
        t.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
        if (head) {
            t.getResponseHeaders().set("Content-Length", Integer.toString(css.length));
            t.sendResponseHeaders(200, -1);
            t.close();
            return;
        }
        t.sendResponseHeaders(200, css.length);
        try (OutputStream output = t.getResponseBody()) {
            output.write(css);
        }
    }

    private void sendResponseWithStatus(HttpExchange t, int status, String response) throws IOException {
        HttpSecurity.applyResponseHeaders(t, configuredHttps());
        String basePath = basePathFor(t);
        if (!basePath.isEmpty() && BasePath.isHtml(t.getResponseHeaders().getFirst("Content-Type"))) {
            response = BasePath.rewriteHtml(response, basePath);
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        HttpSecurity.ensureContentType(t.getResponseHeaders(), HttpSecurity.HTML_UTF8);
        t.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = t.getResponseBody()) {
            os.write(bytes, 0, bytes.length);
            os.flush();
        }
    }

    private class AiApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            if (!isAuthenticated(t)) {
                sendResponseWithStatus(t, 401, jsonError("unauthorized"));
                return;
            }
            String path = t.getRequestURI().getPath();
            String method = t.getRequestMethod();
            String user = getSessionUser(t);
            if ("GET".equalsIgnoreCase(method)) {
                if (!userHasWebPermission(user, "dash.web.ai.read")) {
                    sendResponseWithStatus(t, 403, jsonError("forbidden"));
                    return;
                }
                if (path.endsWith("/status")) {
                    AiAgentManager.ConfigStatus status = aiAgentManager.status(user);
                    JsonObject body = new JsonObject();
                    body.addProperty("enabled", status.enabled());
                    body.addProperty("agenticEnabled", status.agenticEnabled());
                    body.addProperty("model", status.model());
                    body.addProperty("configured", status.keyConfigured());
                    body.addProperty("ownerAccepted", status.ownerAccepted());
                    body.addProperty("userAccepted", status.userAccepted());
                    body.addProperty("thinkingLevel", status.thinkingLevel());
                    body.addProperty("maxOutputTokens", status.maxOutputTokens());
                    body.addProperty("thinkingSummaries", status.thinkingSummaries());
                    body.addProperty("toolChoice", status.toolChoice());
                    body.addProperty("retryCount", status.retryCount());
                    body.addProperty("maxProviderCalls", status.maxProviderCalls());
                    body.addProperty("requestsPerMinute", status.requestsPerMinute());
                    body.addProperty("requestsPerDay", status.requestsPerDay());
                    body.addProperty("inputTokensPerMinute", status.inputTokensPerMinute());
                    sendResponse(t, body.toString()); return;
                }
                if (path.endsWith("/conversations")) {
                    sendResponse(t, new com.google.gson.Gson().toJson(aiAgentManager.conversations(user, 100))); return;
                }
                if (path.endsWith("/messages")) {
                    if (!userHasWebPermission(user, "dash.web.ai.use")) {
                        sendResponseWithStatus(t, 403, jsonError("forbidden")); return;
                    }
                    Map<String, String> query = parseFormData(t.getRequestURI().getRawQuery() == null ? "" : t.getRequestURI().getRawQuery());
                    sendResponse(t, new com.google.gson.Gson().toJson(aiAgentManager.messages(user, query.get("conversation"), 300))); return;
                }
                if (path.endsWith("/audit")) {
                    if (!userHasWebPermission(user, "dash.web.ai.audit")) {
                        sendResponseWithStatus(t, 403, jsonError("forbidden")); return;
                    }
                    sendResponse(t, new com.google.gson.Gson().toJson(aiAgentManager.proposals(user, true, 300))); return;
                }
                if (path.endsWith("/proposals")) {
                    if (!userHasWebPermission(user, "dash.web.ai.agentic")) {
                        sendResponseWithStatus(t, 403, jsonError("forbidden")); return;
                    }
                    sendResponse(t, new com.google.gson.Gson().toJson(aiAgentManager.proposals(user, false, 100))); return;
                }
                sendResponseWithStatus(t, 404, jsonError("not_found")); return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                sendResponseWithStatus(t, 405, jsonError("method_not_allowed"));
                return;
            }
            if (!ensureSameOriginMutation(t, true)) return;
            byte[] raw = readRequestBodyOrReject(t, 128 * 1024L);
            if (raw == null) return;
            Map<String, String> params = parseFormData(new String(raw, StandardCharsets.UTF_8));
            try {
                if (path.endsWith("/consent")) {
                    handleAiConsent(t, user, params);
                } else if (path.endsWith("/config")) {
                    handleAiConfig(t, user, params);
                } else if (path.endsWith("/chat")) {
                    handleAiChat(t, user, params);
                } else if (path.endsWith("/cancel")) {
                    if (!userHasWebPermission(user, "dash.web.ai.use")) {
                        sendResponseWithStatus(t, 403, jsonError("forbidden")); return;
                    }
                    JsonObject body = new JsonObject(); body.addProperty("cancelled", aiAgentManager.cancel(user));
                    sendResponse(t, body.toString());
                } else if (path.endsWith("/proposal")) {
                    handleAiProposal(t, user, params);
                } else if (path.endsWith("/conversation/delete")) {
                    if (!userHasWebPermission(user, "dash.web.ai.use")) {
                        sendResponseWithStatus(t, 403, jsonError("forbidden")); return;
                    }
                    JsonObject body = new JsonObject();
                    body.addProperty("deleted", aiAgentManager.deleteConversation(user, params.get("conversation")));
                    sendResponse(t, body.toString());
                } else {
                    sendResponseWithStatus(t, 404, jsonError("not_found"));
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Dash AI request failed: " + dash.ai.AiRedactor.redact(ex.getMessage(), 240));
                sendResponseWithStatus(t, 400, jsonError(dash.ai.AiRedactor.redact(ex.getMessage(), 240)));
            }
        }
    }

    private void handleAiConsent(HttpExchange t, String user, Map<String, String> params) throws IOException {
        if (!userHasWebPermission(user, "dash.web.ai.read")) {
            sendResponseWithStatus(t, 403, jsonError("forbidden")); return;
        }
        String operation = params.getOrDefault("operation", "accept");
        boolean owner = "owner".equalsIgnoreCase(params.get("scope"))
                || "true".equalsIgnoreCase(params.get("owner"));
        if (owner && !auth.isMainAdmin(user)) {
            sendResponseWithStatus(t, 403, jsonError("Only the Main Admin can accept owner terms.")); return;
        }
        if ("revoke".equalsIgnoreCase(operation)) {
            aiAgentManager.revokeConsent(owner ? "__OWNER__" : user);
            WebActionLogger.log("AI_CONSENT_REVOKED", "user=" + user + " scope=" + (owner ? "owner" : "user"));
        } else {
            if (!"true".equalsIgnoreCase(params.get("agree"))) {
                sendResponseWithStatus(t, 400, jsonError("All current terms must be accepted.")); return;
            }
            WebAuth.UserInfo info = auth.getUsers().get(user);
            aiAgentManager.acceptTerms(user, info == null ? "USER" : info.role(), owner);
            WebActionLogger.log("AI_CONSENT_ACCEPTED", "user=" + user + " scope=" + (owner ? "owner" : "user")
                    + " version=1.0 digest=" + aiAgentManager.termsDigest());
        }
        redirect(t, "/ai?setup=" + (owner ? "1" : "0") + "&msg=" + encodeForQuery("AI terms updated."));
    }

    private void handleAiConfig(HttpExchange t, String user, Map<String, String> params) throws IOException {
        if (!auth.isMainAdmin(user)) {
            sendResponseWithStatus(t, 403, jsonError("Only the Main Admin can configure Dash AI.")); return;
        }
        String operation = params.getOrDefault("operation", "save");
        AiAgentManager.ConfigStatus current = aiAgentManager.status(user);
        boolean remove = "remove_key".equals(operation) || "true".equalsIgnoreCase(params.get("remove_key"));
        boolean enabled = !remove && "true".equalsIgnoreCase(params.get("enabled"));
        boolean agentic = enabled && "true".equalsIgnoreCase(params.get("agentic_enabled"));
        String model = params.getOrDefault("model", current.model());
        String message = aiAgentManager.configure(enabled, agentic, model, params.get("api_key"), remove,
                params.get("thinking_level"), parseInt(params.get("max_output_tokens"), 4096), params.get("seed"),
                params.get("stop_sequences"), params.get("thinking_summaries"), params.get("tool_choice"),
                parseInt(params.get("retry_count"), 0), parseInt(params.get("max_provider_calls"), 2),
                parseInt(params.get("requests_per_minute"), 3), parseInt(params.get("requests_per_day"), 15),
                parseInt(params.get("input_tokens_per_minute"), 150000));
        if ("test".equals(operation) && !message.toLowerCase(Locale.ROOT).contains("before enabling")) {
            message = aiAgentManager.testConnection();
        }
        WebActionLogger.log("AI_CONFIGURATION", "user=" + user + " operation=" + operation + " enabled=" + enabled
                + " agentic=" + agentic + " model=" + model);
        redirect(t, "/ai?setup=1&msg=" + encodeForQuery(message));
    }

    private void handleAiChat(HttpExchange t, String user, Map<String, String> params) throws IOException {
        if (!userHasWebPermission(user, "dash.web.ai.use")) {
            sendResponseWithStatus(t, 403, jsonError("forbidden")); return;
        }
        boolean agentic = "agentic".equalsIgnoreCase(params.get("mode"))
                || "true".equalsIgnoreCase(params.get("agentic"));
        if (agentic && !userHasWebPermission(user, "dash.web.ai.agentic")) {
            sendResponseWithStatus(t, 403, jsonError("Agentic permission is required.")); return;
        }
        String prompt = params.getOrDefault("message", params.getOrDefault("prompt", ""));
        if (prompt.isBlank()) {
            sendResponseWithStatus(t, 400, jsonError("Message is required.")); return;
        }
        Set<String> permissions = effectiveUiPermissions(user);
        String context = buildAiContext(params.getOrDefault("context", ""), user);
        HttpSecurity.applyResponseHeaders(t, configuredHttps());
        t.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        t.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
        t.getResponseHeaders().set("X-Accel-Buffering", "no");
        t.sendResponseHeaders(200, 0);
        OutputStream output = t.getResponseBody();
        try {
            writeSse(output, "status", jsonMessage("Dash AI is working."));
            var future = aiAgentManager.submitChat(user,
                    params.getOrDefault("conversation", params.get("conversation_id")), prompt,
                    context, agentic, permissions, this::executeAiReadTool);
            AiAgentManager.ChatResult result;
            int elapsed = 0;
            while (true) {
                try {
                    result = future.get(10, TimeUnit.SECONDS);
                    break;
                } catch (java.util.concurrent.TimeoutException waiting) {
                    elapsed += 10;
                    if (elapsed >= 120) throw waiting;
                    writeSse(output, "status", jsonMessage("Dash AI is still working (" + elapsed + "s)."));
                }
            }
            JsonObject body = new JsonObject();
            body.addProperty("conversation_id", result.conversationId());
            body.addProperty("response", result.response());
            body.add("proposalIds", new com.google.gson.Gson().toJsonTree(result.proposalIds()));
            writeSse(output, "result", body.toString());
        } catch (java.util.concurrent.TimeoutException ex) {
            aiAgentManager.cancel(user);
            try { writeSse(output, "error", jsonMessage("The AI request timed out.")); } catch (Exception ignored) { }
        } catch (Exception ex) {
            try { writeSse(output, "error", jsonMessage(dash.ai.AiRedactor.redact(
                    ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage(), 300))); } catch (Exception ignored) { }
        } finally {
            output.close();
        }
    }

    private void handleAiProposal(HttpExchange t, String user, Map<String, String> params) throws IOException {
        if (!userHasWebPermission(user, "dash.web.ai.agentic")) {
            sendResponseWithStatus(t, 403, jsonError("forbidden")); return;
        }
        String operation = params.getOrDefault("operation", params.getOrDefault("decision", "reject"));
        String id = params.getOrDefault("proposal", params.getOrDefault("proposal_id", ""));
        if ("reject".equals(operation)) {
            boolean rejected = aiAgentManager.rejectProposal(id, user);
            WebActionLogger.log("AI_PROPOSAL_REJECTED", "user=" + user + " proposal=" + id);
            redirect(t, "/ai?msg=" + encodeForQuery(rejected ? "Proposal rejected." : "Proposal is unavailable."));
            return;
        }
        String reason = params.getOrDefault("reason", "").trim();
        if (reason.length() < 3) {
            sendResponseWithStatus(t, 400, jsonError("An approval reason is required.")); return;
        }
        AiAgentManager.Proposal candidate = aiAgentManager.proposals(user, false, 100).stream()
                .filter(value -> value.id().equals(id)).findFirst().orElse(null);
        if (candidate == null || !"pending".equals(candidate.status())) {
            sendResponseWithStatus(t, 409, jsonError("Proposal is unavailable or expired.")); return;
        }
        String required = aiAgentManager.requiredPermission(candidate.tool());
        if (!userHasWebPermission(user, required)) {
            sendResponseWithStatus(t, 403, jsonError("The underlying Dash permission is required.")); return;
        }
        if ("high".equals(candidate.risk())
                && !("APPROVE " + candidate.tool()).equals(params.getOrDefault("confirmation", "").trim())) {
            sendResponseWithStatus(t, 400, jsonError("Typed confirmation does not match.")); return;
        }
        AiAgentManager.Proposal approved = aiAgentManager.approveProposal(id, user);
        if (approved == null) {
            sendResponseWithStatus(t, 409, jsonError("Proposal expired or was already handled.")); return;
        }
        AiExecutionResult result = executeAiMutation(approved, user, reason);
        aiAgentManager.markExecuted(id, result.success(), result.message());
        WebActionLogger.log("AI_PROPOSAL_EXECUTED", "user=" + user + " proposal=" + id + " tool="
                + approved.tool() + " args_hash=" + approved.argsHash() + " success=" + result.success());
        redirect(t, "/ai?msg=" + encodeForQuery(result.message()));
    }

    private String buildAiContext(String requested, String user) {
        Set<String> selected = new LinkedHashSet<>(splitCsv(requested));
        StringBuilder context = new StringBuilder();
        if (selected.contains("health") && userHasWebPermission(user, "dash.web.stats.read"))
            context.append("\nHEALTH\n").append(executeAiReadTool("get_server_overview", new JsonObject(), user));
        if (selected.contains("players") && userHasWebPermission(user, "dash.web.players.read"))
            context.append("\nPLAYERS\n").append(executeAiReadTool("get_players", new JsonObject(), user));
        if (selected.contains("logs") && userHasWebPermission(user, "dash.web.console.read")) {
            JsonObject args = new JsonObject(); args.addProperty("lines", 120);
            context.append("\nLOGS\n").append(executeAiReadTool("get_recent_logs", args, user));
        }
        if (selected.contains("plugins") && userHasWebPermission(user, "dash.web.plugins.read"))
            context.append("\nPLUGINS\n").append(executeAiReadTool("get_plugins", new JsonObject(), user));
        return dash.ai.AiRedactor.redact(context.toString(), 24_000);
    }

    private String executeAiReadTool(String tool, JsonObject args, String user) {
        String required = aiAgentManager.requiredPermission(tool);
        if (!userHasWebPermission(user, required)) return "Permission denied.";
        try {
            return switch (tool) {
                case "get_server_overview" -> {
                    StatsCollector.StatsSample latest = Dash.getStatsCollector() == null ? null : Dash.getStatsCollector().getLatest();
                    yield "online=true, players=" + Bukkit.getOnlinePlayers().size() + ", max_players=" + Bukkit.getMaxPlayers()
                            + ", tps=" + (latest == null ? "unknown" : latest.tps) + ", mspt="
                            + (latest == null ? "unknown" : latest.mspt) + ", ram_used="
                            + (latest == null ? "unknown" : latest.ramUsed);
                }
                case "get_recent_logs" -> boundedRecentLog(args.has("lines") ? args.get("lines").getAsInt() : 120);
                case "get_players" -> callServerThread(() -> Bukkit.getOnlinePlayers().stream()
                        .map(player -> player.getName() + " world=" + player.getWorld().getName() + " mode=" + player.getGameMode()
                                + " health=" + String.format(Locale.ROOT, "%.1f", player.getHealth()))
                        .limit(100).reduce((a, b) -> a + "\n" + b).orElse("No players are online."));
                case "get_plugins" -> callServerThread(() -> Arrays.stream(Bukkit.getPluginManager().getPlugins())
                        .map(value -> value.getName() + " " + value.getDescription().getVersion() + " enabled=" + value.isEnabled())
                        .limit(250).reduce((a, b) -> a + "\n" + b).orElse("No plugins found."));
                case "get_backups" -> Dash.getBackupManager().listBackups().stream().limit(30)
                        .map(value -> value.name() + " bytes=" + value.size() + " created=" + value.timestamp())
                        .reduce((a, b) -> a + "\n" + b).orElse("No managed backups found.");
                case "read_config" -> {
                    var document = intelligenceManager.inspectConfig(args.has("path") ? args.get("path").getAsString() : "");
                    yield "path=" + document.path() + " type=" + document.format() + " editable=" + document.editable() + "\n"
                            + document.fields().stream().limit(150).map(field -> field.key() + "=" + field.value())
                                    .reduce((a, b) -> a + "\n" + b).orElse("No supported scalar fields.");
                }
                case "get_guardian_summary" -> {
                    int[] counts = Dash.getGuardianDataManager().countLogsSince(System.currentTimeMillis() / 1000L - 86400L);
                    yield "last_24h_blocks=" + counts[0] + ", last_24h_containers=" + counts[1] + ", open_cases="
                            + Dash.getGuardianDataManager().listCases("OPEN", null, 50).size();
                }
                case "get_intelligence_summary" -> {
                    var root = intelligenceManager.rootCauseReport(); var service = intelligenceManager.serviceLevel();
                    yield "root_cause_severity=" + root.severity() + ", summary=" + root.summary() + ", service_status="
                            + service.status() + ", availability=" + service.availabilityPercent();
                }
                default -> "Unsupported read tool.";
            };
        } catch (Exception ex) {
            return "Tool failed safely: " + dash.ai.AiRedactor.redact(ex.getMessage(), 240);
        }
    }

    private AiExecutionResult executeAiMutation(AiAgentManager.Proposal proposal, String user, String reason) {
        JsonObject args = proposal.arguments();
        try {
            if (Set.of("restart_server", "quarantine_plugin", "restore_plugin", "apply_config_change",
                    "guardian_rollback", "guardian_restore").contains(proposal.tool())) {
                dash.data.BackupManager.BackupResult backup = Dash.getBackupManager().createBackupAsync().get(15, TimeUnit.MINUTES);
                if (!backup.success()) return new AiExecutionResult(false, "Restore point creation failed; action was not run.");
            }
            return switch (proposal.tool()) {
                case "create_backup" -> {
                    var backup = Dash.getBackupManager().createBackupAsync().get(15, TimeUnit.MINUTES);
                    yield new AiExecutionResult(backup.success(), backup.success() ? "Backup created: " + backup.fileName() : "Backup failed safely.");
                }
                case "restart_server" -> {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.spigot().restart(), 20L);
                    yield new AiExecutionResult(true, "Restart approved and scheduled with a restore point.");
                }
                case "kick_player" -> serverPlayerAction(args, user, reason, "kick");
                case "ban_player" -> serverPlayerAction(args, user, reason, "ban");
                case "unban_player" -> serverPlayerAction(args, user, reason, "unban");
                case "whitelist_player" -> serverPlayerAction(args, user, reason, "whitelist");
                case "quarantine_plugin" -> resultFromMessage(intelligenceManager.quarantineArtifact(textArg(args, "artifact"), user));
                case "restore_plugin" -> resultFromMessage(intelligenceManager.restoreQuarantinedArtifact(textArg(args, "quarantine_id")));
                case "apply_config_change" -> resultFromMessage(intelligenceManager.updateConfigScalar(textArg(args, "path"),
                        textArg(args, "key"), textArg(args, "value"), user));
                case "guardian_rollback", "guardian_restore" -> executeAiGuardian(proposal.tool(), args);
                default -> new AiExecutionResult(false, "Unsupported action.");
            };
        } catch (Exception ex) {
            return new AiExecutionResult(false, "Action failed safely: " + dash.ai.AiRedactor.redact(ex.getMessage(), 240));
        }
    }

    private AiExecutionResult serverPlayerAction(JsonObject args, String user, String reason, String kind) throws Exception {
        String playerName = textArg(args, "player");
        if (playerName.isBlank() || playerName.length() > 32) return new AiExecutionResult(false, "A valid player is required.");
        return callServerThread(() -> {
            Player online = Bukkit.getPlayerExact(playerName);
            switch (kind) {
                case "kick" -> { if (online == null) return new AiExecutionResult(false, "Player is not online."); online.kick(Component.text(reason)); }
                case "ban" -> { Bukkit.getBanList(BanList.Type.NAME).addBan(playerName, reason, null, user); if (online != null) online.kick(Component.text(reason)); }
                case "unban" -> Bukkit.getBanList(BanList.Type.NAME).pardon(playerName);
                case "whitelist" -> Bukkit.getOfflinePlayer(playerName).setWhitelisted(!"remove".equalsIgnoreCase(textArg(args, "operation")));
                default -> { return new AiExecutionResult(false, "Unsupported player action."); }
            }
            return new AiExecutionResult(true, "Approved player action completed.");
        });
    }

    private AiExecutionResult executeAiGuardian(String tool, JsonObject args) {
        long hours = args.has("hours") ? Math.max(1, Math.min(720, args.get("hours").getAsLong())) : 24L;
        GuardianActionService service = new GuardianActionService(plugin);
        GuardianActionService.ActionRequest preview = new GuardianActionService.ActionRequest(textArg(args, "player"), "",
                System.currentTimeMillis() / 1000L - hours * 3600L, null, null, null, 0, "", List.of(), List.of(), 1000,
                true, true, true);
        GuardianActionService.ActionResult checked = "guardian_restore".equals(tool)
                ? service.restore(Dash.getGuardianDataManager(), preview) : service.rollback(Dash.getGuardianDataManager(), preview);
        if (!checked.success()) return new AiExecutionResult(false, checked.message());
        GuardianActionService.ActionRequest apply = new GuardianActionService.ActionRequest(preview.player(), preview.world(),
                preview.fromTime(), preview.x(), preview.y(), preview.z(), preview.radius(), preview.action(), preview.include(),
                preview.exclude(), preview.limit(), false, true, true);
        GuardianActionService.ActionResult result = "guardian_restore".equals(tool)
                ? service.restore(Dash.getGuardianDataManager(), apply) : service.rollback(Dash.getGuardianDataManager(), apply);
        return new AiExecutionResult(result.success(), result.message());
    }

    private String boundedRecentLog(int requestedLines) throws IOException {
        Path file = Bukkit.getWorldContainer().toPath().resolve("logs").resolve("latest.log").normalize();
        if (!Files.isRegularFile(file)) return "No current server log is available.";
        long size = Files.size(file); long start = Math.max(0, size - 1024 * 1024L);
        byte[] bytes;
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            input.seek(start); bytes = new byte[(int) (size - start)]; input.readFully(bytes);
        }
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R");
        int count = Math.max(1, Math.min(300, requestedLines));
        return dash.ai.AiRedactor.redact(String.join("\n", Arrays.copyOfRange(lines, Math.max(0, lines.length - count), lines.length)), 24_000);
    }

    private <T> T callServerThread(java.util.concurrent.Callable<T> callable) throws Exception {
        if (Bukkit.isPrimaryThread()) return callable.call();
        return Bukkit.getScheduler().callSyncMethod(plugin, callable).get(5, TimeUnit.SECONDS);
    }

    private AiExecutionResult resultFromMessage(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        boolean success = !(lower.contains("failed") || lower.contains("could not") || lower.contains("unavailable")
                || lower.contains("invalid") || lower.contains("not found") || lower.contains("read-only"));
        return new AiExecutionResult(success, message == null ? "Action completed without a result." : message);
    }

    private String textArg(JsonObject args, String key) {
        try { return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString().trim() : ""; }
        catch (Exception ignored) { return ""; }
    }

    private String jsonError(String message) {
        JsonObject body = new JsonObject(); body.addProperty("success", false); body.addProperty("error", message); return body.toString();
    }

    private String jsonMessage(String message) {
        JsonObject body = new JsonObject(); body.addProperty("message", message); return body.toString();
    }

    private void writeSse(OutputStream output, String event, String json) throws IOException {
        JsonObject payload;
        try { payload = com.google.gson.JsonParser.parseString(json).getAsJsonObject(); }
        catch (Exception ignored) { payload = new JsonObject(); payload.addProperty("message", json); }
        payload.addProperty("type", event);
        output.write(("event: " + event + "\ndata: " + payload.toString().replace("\n", "\\n") + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private record AiExecutionResult(boolean success, String message) { }

    private class PluginBrowserSearchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                sendResponseWithStatus(t, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}");
                return;
            }
            if (!isAuthenticated(t)) {
                sendResponseWithStatus(t, 401, "{\"success\":false,\"error\":\"unauthorized\"}");
                return;
            }
            String query = getQueryParam(t.getRequestURI().getQuery(), "query");
            String sort = getQueryParam(t.getRequestURI().getQuery(), "sort");
            sendResponse(t, PluginBrowserPage.searchJson(query, sort,
                    "[[\"all_project_types:plugin\"],[\"categories:bukkit\",\"categories:spigot\",\"categories:paper\",\"categories:purpur\"]]",
                    "[\"bukkit\",\"spigot\",\"paper\",\"purpur\"]"));
        }
    }

    private class UiLanguageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!isAuthenticated(t)) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponseWithStatus(t, 401, "{\"success\":false,\"error\":\"unauthorized\"}");
                return;
            }
            String user = getSessionUser(t);
            if (user == null || user.isBlank()) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponseWithStatus(t, 401, "{\"success\":false,\"error\":\"no_session\"}");
                return;
            }
            String body = new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(body);
            String code = dash.web.I18n.normalize(params.get("language"));
            boolean ok = auth.setUserLanguage(user, code);
            t.getResponseHeaders().add("Content-Type", "application/json");
            if (ok) {
                WebActionLogger.log("UI_LANGUAGE_SET", "user=" + user + " lang=" + code);
                sendResponse(t, "{\"success\":true,\"language\":\"" + code + "\"}");
            } else {
                sendResponseWithStatus(t, 500, "{\"success\":false,\"error\":\"save_failed\"}");
            }
        }
    }

    private Map<String, String> parseFormData(String formData) {
        Map<String, String> map = new HashMap<>();
        if (formData == null || formData.isBlank()) return map;
        String[] pairs = formData.split("&");
        for (String pair : pairs) {
            String[] keyVal = pair.split("=", 2);
            if (keyVal.length == 2) {
                try {
                    String key = URLDecoder.decode(keyVal[0], "UTF-8");
                    String val = URLDecoder.decode(keyVal[1], "UTF-8");
                    map.put(key, val);
                } catch (Exception ignored) {
                }
            } else if (keyVal.length == 1) {
                try {
                    String key = URLDecoder.decode(keyVal[0], "UTF-8");
                    map.put(key, "");
                } catch (Exception ignored) {
                }
            }
        }
        return map;
    }

    private String extractBridgeCommand(String body, String contentType) {
        if (body == null || body.isBlank()) {
            return null;
        }

        String normalizedContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (normalizedContentType.contains("application/json") || body.trim().startsWith("{")) {
            String fromJson = extractJsonStringField(body, "command");
            if (fromJson == null || fromJson.isBlank()) {
                fromJson = extractJsonStringField(body, "cmd");
            }
            if (fromJson != null && !fromJson.isBlank()) {
                return fromJson.trim();
            }
        }

        Map<String, String> params = parseFormData(body);
        String cmd = params.get("command");
        if (cmd == null || cmd.isBlank()) {
            cmd = params.get("cmd");
        }
        return cmd == null ? null : cmd.trim();
    }

    private String formatUptime() {
        try {
            long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
            long uptimeSeconds = uptimeMillis / 1000;
            long days = uptimeSeconds / 86400;
            long hours = (uptimeSeconds % 86400) / 3600;
            long minutes = (uptimeSeconds % 3600) / 60;
            if (days > 0) {
                return String.format("%dd %dh %dm", days, hours, minutes);
            }
            return String.format("%dh %dm", hours, minutes);
        } catch (Exception ignored) {
            return "Unknown";
        }
    }

    private int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Integer parseOptionalInt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<String> csvList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private GuardianActionService.ActionRequest guardianActionRequest(Map<String, String> params) {
        int hours = Math.max(1, Math.min(parseInt(params.get("hours"), 24), 2160));
        String scope = params.getOrDefault("scope", "both");
        return new GuardianActionService.ActionRequest(
                blankToNull(params.get("player")),
                blankToNull(params.get("world")),
                (System.currentTimeMillis() / 1000L) - (hours * 3600L),
                parseOptionalInt(params.get("x")),
                parseOptionalInt(params.get("y")),
                parseOptionalInt(params.get("z")),
                parseOptionalInt(params.get("radius")),
                blankToNull(params.get("action")),
                csvList(params.get("include")),
                csvList(params.get("exclude")),
                Math.max(1, Math.min(parseInt(params.get("limit"), 1000), 10000)),
                "true".equalsIgnoreCase(params.get("preview")) || "on".equalsIgnoreCase(params.get("preview")),
                !"containers".equalsIgnoreCase(scope),
                !"blocks".equalsIgnoreCase(scope));
    }

    private GuardianDataManager.ActionPreviewDiff guardianPreviewDiff(GuardianDataManager guardian, String query) {
        int hours = Math.max(1, Math.min(parseInt(getQueryParam(query, "hours"), 24), 2160));
        String scope = getQueryParam(query, "scope");
        return guardian.buildActionPreviewDiff(
                getQueryParam(query, "player"),
                getQueryParam(query, "world"),
                (System.currentTimeMillis() / 1000L) - (hours * 3600L),
                getQueryParam(query, "action"),
                parseOptionalInt(getQueryParam(query, "x")),
                parseOptionalInt(getQueryParam(query, "y")),
                parseOptionalInt(getQueryParam(query, "z")),
                parseOptionalInt(getQueryParam(query, "radius")),
                csvList(getQueryParam(query, "include")),
                csvList(getQueryParam(query, "exclude")),
                Math.max(1, Math.min(parseInt(getQueryParam(query, "limit"), 1000), 10000)),
                !"containers".equalsIgnoreCase(scope),
                !"blocks".equalsIgnoreCase(scope));
    }

    private String guardianLookupJson(GuardianDataManager guardian, String query, boolean near) {
        int radius = Math.max(0, Math.min(parseInt(getQueryParam(query, "radius"), near ? 5 : 0), 10000));
        Integer x = parseOptionalInt(getQueryParam(query, "x"));
        Integer y = parseOptionalInt(getQueryParam(query, "y"));
        Integer z = parseOptionalInt(getQueryParam(query, "z"));
        String action = getQueryParam(query, "action");
        Long since = sinceFromQuery(query);
        int limit = Math.max(1, Math.min(parseInt(getQueryParam(query, "limit"), 100), 10000));
        List<String> include = csvList(getQueryParam(query, "include"));
        List<String> exclude = csvList(getQueryParam(query, "exclude"));
        List<GuardianDataManager.BlockLogEntry> blocks = guardian.searchBlockLogsAdvanced(
                getQueryParam(query, "player"), getQueryParam(query, "world"), since, null,
                GuardianDataManager.parseBlockAction(action), x, y, z, radius, include, exclude, 1, limit, false);
        List<GuardianDataManager.ContainerLogEntry> containers = guardian.searchContainerLogsAdvanced(
                getQueryParam(query, "player"), getQueryParam(query, "world"), since, null,
                GuardianDataManager.parseContainerAction(action), x, y, z, radius, include, exclude, 1, limit, false);
        GuardianDataManager.QueryCount count = guardian.countAdvanced(getQueryParam(query, "player"),
                getQueryParam(query, "world"), since, null, action, x, y, z, radius, include, exclude);
        return "{\"success\":true,\"count\":{\"blocks\":" + count.blocks() + ",\"containers\":"
                + count.containers() + "},\"blocks\":" + guardianBlockLogsJson(blocks)
                + ",\"containers\":" + guardianContainerLogsJson(containers) + "}";
    }

    private String guardianHasActionJson(GuardianDataManager guardian, String query, boolean placed) {
        Integer x = parseOptionalInt(getQueryParam(query, "x"));
        Integer y = parseOptionalInt(getQueryParam(query, "y"));
        Integer z = parseOptionalInt(getQueryParam(query, "z"));
        String world = getQueryParam(query, "world");
        if (x == null || y == null || z == null || world == null || world.isBlank()) {
            return "{\"success\":false,\"error\":\"world_x_y_z_required\"}";
        }
        long since = sinceFromQuery(query, 24);
        boolean found = guardian.hasBlockAction(getQueryParam(query, "player"), world, x, y, z,
                placed ? GuardianDataManager.ACTION_PLACE : GuardianDataManager.ACTION_BREAK, since,
                Math.max(0, parseInt(getQueryParam(query, "offset"), 0)));
        return "{\"success\":true,\"found\":" + found + "}";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isChecked(Map<String, String> params, String name) {
        if (params == null || name == null) {
            return false;
        }
        String value = params.get(name);
        return value != null && ("on".equalsIgnoreCase(value)
                || "true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "yes".equalsIgnoreCase(value));
    }

    private String actorLabel(HttpExchange t) {
        String user = getSessionUser(t);
        return user == null || user.isBlank() ? "bridge" : user;
    }

    private Long sinceFromQuery(String query) {
        String hoursRaw = getQueryParam(query, "hours");
        if (hoursRaw == null || hoursRaw.isBlank()) return null;
        return sinceFromQuery(query, parseInt(hoursRaw, 24));
    }

    private long sinceFromQuery(String query, int fallbackHours) {
        String hoursRaw = getQueryParam(query, "hours");
        int hours = Math.max(1, Math.min(parseInt(hoursRaw, fallbackHours), 2160));
        return (System.currentTimeMillis() / 1000L) - (hours * 3600L);
    }

    private String guardianStatsJson(GuardianDataManager.ServerStats stats) {
        StringBuilder top = new StringBuilder("[");
        for (int i = 0; i < stats.topPlayers.size(); i++) {
            GuardianDataManager.PlayerActivity p = stats.topPlayers.get(i);
            if (i > 0) top.append(',');
            top.append("{\"player\":\"").append(jsonEscape(p.playerName())).append("\",")
                    .append("\"totalActions\":").append(p.totalActions()).append(',')
                    .append("\"blocksBroken\":").append(p.blocksBroken()).append(',')
                    .append("\"blocksPlaced\":").append(p.blocksPlaced()).append('}');
        }
        top.append(']');
        return "{"
                + "\"totalBlocksBroken\":" + stats.totalBlocksBroken + ','
                + "\"totalBlocksPlaced\":" + stats.totalBlocksPlaced + ','
                + "\"totalItemsRemoved\":" + stats.totalItemsRemoved + ','
                + "\"totalItemsAdded\":" + stats.totalItemsAdded + ','
                + "\"uniquePlayers\":" + stats.uniquePlayers + ','
                + "\"topPlayers\":" + top
                + "}";
    }

    private String guardianStatusJson(GuardianDataManager.GuardianStatus status) {
        return "{\"success\":true,\"available\":" + status.available()
                + ",\"databasePath\":\"" + jsonEscape(status.databasePath()) + "\""
                + ",\"databaseBytes\":" + status.databaseBytes()
                + ",\"blockRows\":" + status.blockRows()
                + ",\"containerRows\":" + status.containerRows()
                + ",\"coreProtectRequired\":false}";
    }

    private String guardianActionResultJson(GuardianActionService.ActionResult result) {
        return "{\"success\":" + result.success()
                + ",\"mode\":\"" + jsonEscape(result.mode()) + "\""
                + ",\"preview\":" + result.preview()
                + ",\"matchedBlocks\":" + result.matchedBlocks()
                + ",\"matchedContainers\":" + result.matchedContainers()
                + ",\"changedBlocks\":" + result.changedBlocks()
                + ",\"changedContainers\":" + result.changedContainers()
                + ",\"skipped\":" + result.skipped()
                + ",\"message\":\"" + jsonEscape(result.message()) + "\"}";
    }

    private String guardianBlockLogsJson(List<GuardianDataManager.BlockLogEntry> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.BlockLogEntry row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(row.id())
                    .append(",\"source\":\"").append(jsonEscape(row.source())).append('"')
                    .append(",\"timestamp\":").append(row.timestamp())
                    .append(",\"time\":\"").append(jsonEscape(row.formattedTime())).append('"')
                    .append(",\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"action\":\"").append(row.actionLabel()).append('"')
                    .append(",\"world\":\"").append(jsonEscape(row.world())).append('"')
                    .append(",\"x\":").append(row.x())
                    .append(",\"y\":").append(row.y())
                    .append(",\"z\":").append(row.z())
                    .append(",\"block\":\"").append(jsonEscape(row.blockType())).append('"')
                    .append(",\"oldBlock\":\"").append(jsonEscape(row.oldBlockType())).append("\"}");
        }
        return json.append(']').toString();
    }

    private String guardianContainerLogsJson(List<GuardianDataManager.ContainerLogEntry> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.ContainerLogEntry row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(row.id())
                    .append(",\"source\":\"").append(jsonEscape(row.source())).append('"')
                    .append(",\"timestamp\":").append(row.timestamp())
                    .append(",\"time\":\"").append(jsonEscape(row.formattedTime())).append('"')
                    .append(",\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"action\":\"").append(row.actionLabel()).append('"')
                    .append(",\"world\":\"").append(jsonEscape(row.world())).append('"')
                    .append(",\"x\":").append(row.x())
                    .append(",\"y\":").append(row.y())
                    .append(",\"z\":").append(row.z())
                    .append(",\"item\":\"").append(jsonEscape(row.itemMaterial())).append('"')
                    .append(",\"amount\":").append(row.itemAmount()).append('}');
        }
        return json.append(']').toString();
    }

    private String guardianTimelineJson(List<GuardianDataManager.TimelineEntry> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.TimelineEntry row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"timeSlot\":\"").append(jsonEscape(row.timeSlot())).append('"')
                    .append(",\"blockCount\":").append(row.blockCount())
                    .append(",\"containerCount\":").append(row.containerCount()).append('}');
        }
        return json.append(']').toString();
    }

    private String guardianTimelineEventsJson(List<GuardianDataManager.UnifiedTimelineEntry> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.UnifiedTimelineEntry row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"type\":\"").append(jsonEscape(row.eventType())).append('"')
                    .append(",\"id\":").append(row.id())
                    .append(",\"source\":\"").append(jsonEscape(row.source())).append('"')
                    .append(",\"timestamp\":").append(row.timestamp())
                    .append(",\"time\":\"").append(jsonEscape(row.formattedTime())).append('"')
                    .append(",\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"action\":\"").append(jsonEscape(row.action())).append('"')
                    .append(",\"world\":\"").append(jsonEscape(row.world())).append('"')
                    .append(",\"x\":").append(row.x())
                    .append(",\"y\":").append(row.y())
                    .append(",\"z\":").append(row.z())
                    .append(",\"target\":\"").append(jsonEscape(row.target())).append('"')
                    .append(",\"amount\":").append(row.amount())
                    .append('}');
        }
        return json.append(']').toString();
    }

    private String guardianCasesJson(List<GuardianDataManager.CaseRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) json.append(',');
            json.append(guardianCaseJson(rows.get(i)));
        }
        return json.append(']').toString();
    }

    private String guardianCaseJson(GuardianDataManager.CaseRecord row) {
        if (row == null) {
            return "null";
        }
        return "{\"id\":" + row.id()
                + ",\"title\":\"" + jsonEscape(row.title()) + "\""
                + ",\"status\":\"" + jsonEscape(row.status()) + "\""
                + ",\"priority\":\"" + jsonEscape(row.priority()) + "\""
                + ",\"player\":\"" + jsonEscape(row.playerName()) + "\""
                + ",\"world\":\"" + jsonEscape(row.world()) + "\""
                + ",\"x\":" + nullableNumber(row.x())
                + ",\"y\":" + nullableNumber(row.y())
                + ",\"z\":" + nullableNumber(row.z())
                + ",\"notes\":\"" + jsonEscape(row.notes()) + "\""
                + ",\"createdBy\":\"" + jsonEscape(row.createdBy()) + "\""
                + ",\"createdAt\":" + row.createdAt()
                + ",\"updatedAt\":" + row.updatedAt()
                + ",\"createdTime\":\"" + jsonEscape(row.formattedCreatedAt()) + "\""
                + ",\"updatedTime\":\"" + jsonEscape(row.formattedUpdatedAt()) + "\""
                + ",\"locked\":" + row.locked()
                + "}";
    }

    private String guardianEvidenceJson(List<GuardianDataManager.EvidenceRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.EvidenceRecord row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(row.id())
                    .append(",\"caseId\":").append(row.caseId())
                    .append(",\"eventType\":\"").append(jsonEscape(row.eventType())).append('"')
                    .append(",\"eventId\":").append(row.eventId())
                    .append(",\"label\":\"").append(jsonEscape(row.label())).append('"')
                    .append(",\"addedBy\":\"").append(jsonEscape(row.addedBy())).append('"')
                    .append(",\"createdAt\":").append(row.createdAt())
                    .append(",\"createdTime\":\"").append(jsonEscape(row.formattedCreatedAt())).append("\"}");
        }
        return json.append(']').toString();
    }

    private String guardianFiltersJson(List<GuardianDataManager.SavedFilterRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.SavedFilterRecord row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(row.id())
                    .append(",\"name\":\"").append(jsonEscape(row.name())).append('"')
                    .append(",\"query\":\"").append(jsonEscape(row.query())).append('"')
                    .append(",\"createdBy\":\"").append(jsonEscape(row.createdBy())).append('"')
                    .append(",\"createdAt\":").append(row.createdAt())
                    .append(",\"createdTime\":\"").append(jsonEscape(row.formattedCreatedAt())).append("\"}");
        }
        return json.append(']').toString();
    }

    private String guardianPlayerNotesJson(List<GuardianDataManager.PlayerNoteRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) json.append(',');
            json.append(guardianPlayerNoteJson(rows.get(i)));
        }
        return json.append(']').toString();
    }

    private String guardianPlayerNoteJson(GuardianDataManager.PlayerNoteRecord row) {
        if (row == null) {
            return "null";
        }
        return "{\"player\":\"" + jsonEscape(row.playerName()) + "\""
                + ",\"severity\":\"" + jsonEscape(row.severity()) + "\""
                + ",\"notes\":\"" + jsonEscape(row.notes()) + "\""
                + ",\"createdBy\":\"" + jsonEscape(row.createdBy()) + "\""
                + ",\"createdAt\":" + row.createdAt()
                + ",\"updatedAt\":" + row.updatedAt()
                + ",\"createdTime\":\"" + jsonEscape(row.formattedCreatedAt()) + "\""
                + ",\"updatedTime\":\"" + jsonEscape(row.formattedUpdatedAt()) + "\""
                + "}";
    }

    private String guardianIncidentsJson(List<GuardianDataManager.IncidentRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.IncidentRecord row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"world\":\"").append(jsonEscape(row.world())).append('"')
                    .append(",\"chunkX\":").append(row.chunkX())
                    .append(",\"chunkZ\":").append(row.chunkZ())
                    .append(",\"firstAt\":").append(row.firstAt())
                    .append(",\"lastAt\":").append(row.lastAt())
                    .append(",\"firstTime\":\"").append(jsonEscape(row.formattedFirstAt())).append('"')
                    .append(",\"lastTime\":\"").append(jsonEscape(row.formattedLastAt())).append('"')
                    .append(",\"totalActions\":").append(row.totalActions())
                    .append(",\"blockActions\":").append(row.blockActions())
                    .append(",\"containerActions\":").append(row.containerActions())
                    .append(",\"score\":").append(row.score())
                    .append('}');
        }
        return json.append(']').toString();
    }

    private String guardianScoresJson(List<GuardianDataManager.SuspicionScoreRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.SuspicionScoreRecord row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"score\":").append(row.score())
                    .append(",\"severity\":\"").append(jsonEscape(row.severity())).append('"')
                    .append(",\"totalActions\":").append(row.totalActions())
                    .append(",\"blockBreaks\":").append(row.blockBreaks())
                    .append(",\"containerRemoves\":").append(row.containerRemoves())
                    .append(",\"rareHits\":").append(row.rareHits())
                    .append(",\"dangerHits\":").append(row.dangerHits())
                    .append(",\"firstAt\":").append(row.firstAt())
                    .append(",\"lastAt\":").append(row.lastAt())
                    .append(",\"firstTime\":\"").append(jsonEscape(row.formattedFirstAt())).append('"')
                    .append(",\"lastTime\":\"").append(jsonEscape(row.formattedLastAt())).append("\"}");
        }
        return json.append(']').toString();
    }

    private String guardianItemAmountsJson(List<GuardianDataManager.ItemAmountRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.ItemAmountRecord row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"item\":\"").append(jsonEscape(row.item())).append('"')
                    .append(",\"amount\":").append(row.amount()).append('}');
        }
        return json.append(']').toString();
    }

    private String guardianPreviewDiffJson(GuardianDataManager.ActionPreviewDiff diff) {
        return "{\"success\":true"
                + ",\"blockRows\":" + diff.blockRows()
                + ",\"containerRows\":" + diff.containerRows()
                + ",\"blockBreaks\":" + diff.blockBreaks()
                + ",\"blockPlaces\":" + diff.blockPlaces()
                + ",\"containerRemovedItems\":" + diff.containerRemovedItems()
                + ",\"containerAddedItems\":" + diff.containerAddedItems()
                + ",\"topTargets\":" + guardianItemAmountsJson(diff.topTargets())
                + "}";
    }

    private String guardianProtectedRegionsJson(List<GuardianDataManager.ProtectedRegionRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) json.append(',');
            json.append(guardianProtectedRegionJson(rows.get(i)));
        }
        return json.append(']').toString();
    }

    private String guardianProtectedRegionJson(GuardianDataManager.ProtectedRegionRecord row) {
        if (row == null) return "null";
        return "{\"id\":" + row.id()
                + ",\"name\":\"" + jsonEscape(row.name()) + "\""
                + ",\"world\":\"" + jsonEscape(row.world()) + "\""
                + ",\"minX\":" + row.minX()
                + ",\"minY\":" + row.minY()
                + ",\"minZ\":" + row.minZ()
                + ",\"maxX\":" + row.maxX()
                + ",\"maxY\":" + row.maxY()
                + ",\"maxZ\":" + row.maxZ()
                + ",\"severity\":\"" + jsonEscape(row.severity()) + "\""
                + ",\"createdBy\":\"" + jsonEscape(row.createdBy()) + "\""
                + ",\"createdAt\":" + row.createdAt()
                + ",\"updatedAt\":" + row.updatedAt()
                + ",\"createdTime\":\"" + jsonEscape(row.formattedCreatedAt()) + "\""
                + ",\"updatedTime\":\"" + jsonEscape(row.formattedUpdatedAt()) + "\""
                + "}";
    }

    private String guardianProtectedRegionHitsJson(List<GuardianDataManager.ProtectedRegionHitRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.ProtectedRegionHitRecord row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"region\":\"").append(jsonEscape(row.regionName())).append('"')
                    .append(",\"severity\":\"").append(jsonEscape(row.severity())).append('"')
                    .append(",\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"world\":\"").append(jsonEscape(row.world())).append('"')
                    .append(",\"totalActions\":").append(row.totalActions())
                    .append(",\"lastAt\":").append(row.lastAt())
                    .append(",\"lastTime\":\"").append(jsonEscape(row.formattedLastAt())).append("\"}");
        }
        return json.append(']').toString();
    }

    private String guardianAlertRulesJson(List<GuardianDataManager.AlertRuleRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) json.append(',');
            json.append(guardianAlertRuleJson(rows.get(i)));
        }
        return json.append(']').toString();
    }

    private String guardianAlertRuleJson(GuardianDataManager.AlertRuleRecord row) {
        if (row == null) return "null";
        return "{\"id\":" + row.id()
                + ",\"name\":\"" + jsonEscape(row.name()) + "\""
                + ",\"enabled\":" + row.enabled()
                + ",\"windowSeconds\":" + row.windowSeconds()
                + ",\"minActions\":" + row.minActions()
                + ",\"action\":\"" + jsonEscape(row.action()) + "\""
                + ",\"material\":\"" + jsonEscape(row.material()) + "\""
                + ",\"autoCase\":" + row.autoCase()
                + ",\"priority\":\"" + jsonEscape(row.priority()) + "\""
                + ",\"createdBy\":\"" + jsonEscape(row.createdBy()) + "\""
                + ",\"createdAt\":" + row.createdAt()
                + ",\"updatedAt\":" + row.updatedAt()
                + ",\"createdTime\":\"" + jsonEscape(row.formattedCreatedAt()) + "\""
                + ",\"updatedTime\":\"" + jsonEscape(row.formattedUpdatedAt()) + "\""
                + "}";
    }

    private String guardianAlertHitsJson(List<GuardianDataManager.AlertHitRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.AlertHitRecord row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"ruleId\":").append(row.ruleId())
                    .append(",\"rule\":\"").append(jsonEscape(row.ruleName())).append('"')
                    .append(",\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"count\":").append(row.count())
                    .append(",\"firstAt\":").append(row.firstAt())
                    .append(",\"lastAt\":").append(row.lastAt())
                    .append(",\"firstTime\":\"").append(jsonEscape(row.formattedFirstAt())).append('"')
                    .append(",\"lastTime\":\"").append(jsonEscape(row.formattedLastAt())).append('"')
                    .append(",\"priority\":\"").append(jsonEscape(row.priority())).append('"')
                    .append(",\"autoCase\":").append(row.autoCase())
                    .append('}');
        }
        return json.append(']').toString();
    }

    private String guardianRetentionJson(GuardianDataManager.RetentionPolicyRecord row) {
        return "{\"logDays\":" + row.logDays()
                + ",\"keepCases\":" + row.keepCases()
                + ",\"updatedBy\":\"" + jsonEscape(row.updatedBy()) + "\""
                + ",\"updatedAt\":" + row.updatedAt()
                + ",\"updatedTime\":\"" + jsonEscape(row.formattedUpdatedAt()) + "\""
                + "}";
    }

    private String guardianInboxJson(GuardianDataManager.GuardianInboxRecord inbox) {
        return "{\"openCases\":" + guardianCasesJson(inbox.openCases())
                + ",\"alerts\":" + guardianAlertHitsJson(inbox.alerts())
                + ",\"alertNotes\":" + guardianPlayerNotesJson(inbox.alertNotes())
                + ",\"incidents\":" + guardianIncidentsJson(inbox.incidents())
                + "}";
    }

    private String guardianCaseBundleJson(GuardianDataManager guardian, long caseId) {
        GuardianDataManager.CaseRecord record = guardian.getCase(caseId);
        if (record == null) {
            return "{\"success\":false,\"error\":\"case_not_found\"}";
        }
        return "{\"success\":true,\"case\":" + guardianCaseJson(record)
                + ",\"evidence\":" + guardianEvidenceJson(guardian.listCaseEvidence(caseId))
                + ",\"replay\":" + guardianTimelineEventsJson(guardian.searchTimelineReplay(
                        null, record.playerName(), record.world(), null, null, 80))
                + "}";
    }

    private String guardianActivityJson() {
        var audit = Dash.getAuditDataManager();
        if (audit == null) {
            return "[]";
        }
        StringBuilder json = new StringBuilder("[");
        List<dash.data.AuditDataManager.AuditEntry> rows = audit.searchLogs("GUARDIAN", 30);
        for (int i = 0; i < rows.size(); i++) {
            dash.data.AuditDataManager.AuditEntry row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(row.id())
                    .append(",\"timestamp\":").append(row.timestamp())
                    .append(",\"time\":\"").append(jsonEscape(row.getFormattedTime())).append('"')
                    .append(",\"user\":\"").append(jsonEscape(row.username())).append('"')
                    .append(",\"action\":\"").append(jsonEscape(row.action())).append('"')
                    .append(",\"details\":\"").append(jsonEscape(row.details())).append('"')
                    .append(",\"ip\":\"").append(jsonEscape(row.ipAddress())).append("\"}");
        }
        return json.append(']').toString();
    }

    private String nullableNumber(Integer value) {
        return value == null ? "null" : value.toString();
    }

    private String guardianHeatmapJson(List<GuardianDataManager.HeatmapEntry> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.HeatmapEntry row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"world\":\"").append(jsonEscape(row.world())).append('"')
                    .append(",\"chunkX\":").append(row.chunkX())
                    .append(",\"chunkZ\":").append(row.chunkZ())
                    .append(",\"count\":").append(row.count()).append('}');
        }
        return json.append(']').toString();
    }

    private String guardianSuspiciousJson(List<GuardianDataManager.SuspiciousEntry> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.SuspiciousEntry row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"totalBroken\":").append(row.totalBroken())
                    .append(",\"diamonds\":").append(row.diamonds())
                    .append(",\"debris\":").append(row.debris()).append('}');
        }
        return json.append(']').toString();
    }

    private String guardianPlayerActivityJson(List<GuardianDataManager.PlayerActivity> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.PlayerActivity row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"totalActions\":").append(row.totalActions())
                    .append(",\"blocksBroken\":").append(row.blocksBroken())
                    .append(",\"blocksPlaced\":").append(row.blocksPlaced()).append('}');
        }
        return json.append(']').toString();
    }

    private String guardianCustomStatsJson(GuardianDataManager guardian, long since, String action, int limit) {
        return "{\"since\":" + since
                + ",\"topPlayers\":" + guardianPlayerActivityJson(guardian.getTopPlayersData(since, limit))
                + ",\"peakHours\":" + intIntMapJson(guardian.getPeakHoursData(since))
                + ",\"blockTypes\":" + stringIntMapJson(guardian.getBlockTypesData(since, action, limit))
                + ",\"suspicious\":" + guardianSuspiciousJson(guardian.getSuspiciousPlayers(since))
                + "}";
    }

    private String stringArrayJson(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(jsonEscape(values.get(i))).append('"');
        }
        return json.append(']').toString();
    }

    private String stringIntMapJson(Map<String, Integer> values) {
        StringBuilder json = new StringBuilder("{");
        int idx = 0;
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            if (idx++ > 0) json.append(',');
            json.append('"').append(jsonEscape(entry.getKey())).append("\":").append(entry.getValue());
        }
        return json.append('}').toString();
    }

    private String intIntMapJson(Map<Integer, Integer> values) {
        StringBuilder json = new StringBuilder("{");
        int idx = 0;
        for (Map.Entry<Integer, Integer> entry : values.entrySet()) {
            if (idx++ > 0) json.append(',');
            json.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        return json.append('}').toString();
    }

    private String guardianBlocksCsv(List<GuardianDataManager.BlockLogEntry> rows) {
        StringBuilder csv = new StringBuilder("timestamp,time,source,player,action,block,old_block,world,x,y,z\n");
        for (GuardianDataManager.BlockLogEntry row : rows) {
            csv.append(row.timestamp()).append(',')
                    .append(csv(row.formattedTime())).append(',')
                    .append(csv(row.source())).append(',')
                    .append(csv(row.playerName())).append(',')
                    .append(row.actionLabel()).append(',')
                    .append(csv(row.blockType())).append(',')
                    .append(csv(row.oldBlockType())).append(',')
                    .append(csv(row.world())).append(',')
                    .append(row.x()).append(',').append(row.y()).append(',').append(row.z()).append('\n');
        }
        return csv.toString();
    }

    private String guardianContainersCsv(List<GuardianDataManager.ContainerLogEntry> rows) {
        StringBuilder csv = new StringBuilder("timestamp,time,source,player,action,item,amount,world,x,y,z\n");
        for (GuardianDataManager.ContainerLogEntry row : rows) {
            csv.append(row.timestamp()).append(',')
                    .append(csv(row.formattedTime())).append(',')
                    .append(csv(row.source())).append(',')
                    .append(csv(row.playerName())).append(',')
                    .append(row.actionLabel()).append(',')
                    .append(csv(row.itemMaterial())).append(',')
                    .append(row.itemAmount()).append(',')
                    .append(csv(row.world())).append(',')
                    .append(row.x()).append(',').append(row.y()).append(',').append(row.z()).append('\n');
        }
        return csv.toString();
    }

    private String csv(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private String extractJsonStringField(String json, String fieldName) {
        Pattern p = Pattern.compile("\\\"" + Pattern.quote(fieldName)
                + "\\\"\\s*:\\s*\\\"((?:\\\\\\\"|\\\\\\\\|[^\\\"])*)\\\"");
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return null;
        }
        return m.group(1)
                .replace("\\\\", "\\")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    private class BackupDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.backups.read", true)) {
                return;
            }

            String query = t.getRequestURI().getQuery();
            String name = null;
            if (query != null && query.startsWith("name=")) {
                name = URLDecoder.decode(query.substring(5), "UTF-8");
            }

            dash.data.BackupManager bm = Dash.getBackupManager();
            if (bm == null || name == null) {
                t.sendResponseHeaders(404, 0);
                t.close();
                return;
            }

            File file = bm.getBackupFile(name);
            if (file == null) {
                t.sendResponseHeaders(404, 0);
                t.close();
                return;
            }

            WebActionLogger.logBackup("DOWNLOAD", name + " from " + getClientIp(t));

            t.getResponseHeaders().set("Content-Type", "application/zip");
            t.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");
            t.sendResponseHeaders(200, file.length());

            try (OutputStream os = t.getResponseBody(); FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
            }
        }
    }

    private class IconUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensureAnyPermission(t, true, "dash.web.settings.icon.write", "dash.web.settings.write")) {
                return;
            }

            if (!"POST".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, 0);
                t.close();
                return;
            }

            try {
                byte[] body = readRequestBodyOrReject(t, 2L * 1024L * 1024L);
                if (body == null) return;
                String contentType = t.getRequestHeaders().getFirst("Content-Type");

                if (contentType != null && contentType.contains("multipart/form-data")) {
                    String boundary = contentType.split("boundary=")[1];
                    byte[] fileData = extractFileFromMultipart(body, boundary);

                    if (fileData != null) {
                        File iconFile = new File(Bukkit.getWorldContainer(), "server-icon.png");
                        Files.write(iconFile.toPath(), fileData);
                        WebActionLogger.logUpload("ICON", "server-icon.png", getClientIp(t));

                        t.getResponseHeaders().add("Content-Type", "application/json");
                        sendResponse(t, "{\"success\": true}");
                        return;
                    }
                }

                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\": false, \"error\": \"Invalid upload\"}");
            } catch (Exception e) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\": false, \"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        }
    }

    private class DatapackUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.datapacks.write", true)) {
                return;
            }

            if (!"POST".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, 0);
                t.close();
                return;
            }

            try {
                byte[] body = readRequestBodyOrReject(t, 64L * 1024L * 1024L);
                if (body == null) return;
                String contentType = t.getRequestHeaders().getFirst("Content-Type");

                if (contentType != null && contentType.contains("multipart/form-data")) {
                    String boundary = contentType.split("boundary=")[1];
                    byte[] fileData = extractFileFromMultipart(body, boundary);
                    String fileName = extractFileNameFromMultipart(body, boundary);

                    if (fileData != null && fileName != null) {
                        boolean success = dash.data.DatapackManager.uploadDatapack(fileName, fileData);
                        if (success) {
                            WebActionLogger.logUpload("DATAPACK", fileName, getClientIp(t));
                        }
                        t.getResponseHeaders().add("Content-Type", "application/json");
                        sendResponse(t, "{\"success\": " + success + "}");
                        return;
                    }
                }

                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\": false, \"error\": \"Invalid upload\"}");
            } catch (Exception e) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\": false, \"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        }
    }

    private class BridgePlayersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                sendResponseWithStatus(t, 405, "Method not allowed");
                return;
            }
            if (!ensureBridgeBearer(t)) return;
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            Set<String> onlineNames = new HashSet<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!first) json.append(',');
                first = false;
                String uuid = player.getUniqueId().toString();
                onlineNames.add(player.getName().toLowerCase(Locale.ROOT));
                WebAuth.UserInfo linked = auth.findLinkedUser(player.getName(), uuid);
                String linkedUser = linked == null ? "" : linked.username() + " · " + linked.role();
                String world = player.getWorld() == null ? "Unknown" : player.getWorld().getName();
                json.append("{\"name\":\"").append(jsonEscape(player.getName()))
                        .append("\",\"uuid\":\"").append(jsonEscape(uuid))
                        .append("\",\"world\":\"").append(jsonEscape(world))
                        .append("\",\"ping\":\"").append(player.getPing()).append(" ms")
                        .append("\",\"linkedUser\":\"").append(jsonEscape(linkedUser)).append("\",\"online\":true}");
            }
            for (WebAuth.UserInfo user : auth.getUsers().values()) {
                String linkedPlayer = user.linkedPlayer();
                if (linkedPlayer == null || linkedPlayer.isBlank() || "N/A".equalsIgnoreCase(linkedPlayer)
                        || onlineNames.contains(linkedPlayer.toLowerCase(Locale.ROOT))) continue;
                if (!first) json.append(',');
                first = false;
                json.append("{\"name\":\"").append(jsonEscape(linkedPlayer))
                        .append("\",\"uuid\":\"\",\"world\":\"Offline\",\"ping\":\"-\",\"linkedUser\":\"")
                        .append(jsonEscape(user.username() + " · " + user.role())).append("\",\"online\":false}");
            }
            json.append(']');
            t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            sendResponse(t, json.toString());
        }
    }

    private class PlayerProfileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.players.read", true)) {
                return;
            }

            String query = t.getRequestURI().getQuery();
            String name = null;
            if (query != null && query.startsWith("name=")) {
                name = URLDecoder.decode(query.substring(5), "UTF-8");
            }

            if (name == null) {
                t.sendResponseHeaders(400, 0);
                t.close();
                return;
            }

            dash.data.PlayerDataManager pdm = Dash.getPlayerDataManager();
            if (pdm == null) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"error\": \"Player data not available\"}");
                return;
            }

            dash.data.PlayerDataManager.PlayerInfo info = pdm.getPlayerInfo(name);
            if (info == null) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"error\": \"Player not found\"}");
                return;
            }

            StringBuilder json = new StringBuilder();
            json.append("{\"uuid\":\"").append(info.uuid()).append("\",");
            json.append("\"name\":\"").append(info.name()).append("\",");
            json.append("\"firstJoin\":").append(info.firstJoin()).append(",");
            json.append("\"lastJoin\":").append(info.lastJoin()).append(",");
            json.append("\"playtime\":\"").append(info.getFormattedPlaytime()).append("\",");
            json.append("\"sessions\":[");

            var sessions = pdm.getPlayerSessions(info.uuid(), 10);
            for (int i = 0; i < sessions.size(); i++) {
                var s = sessions.get(i);
                if (i > 0)
                    json.append(",");
                json.append("{\"join\":").append(s.joinTime()).append(",\"duration\":\"").append(s.getDuration())
                        .append("\"}");
            }
            json.append("],\"notes\":[");

            var notes = pdm.getPlayerNotes(info.uuid());
            for (int i = 0; i < notes.size(); i++) {
                var n = notes.get(i);
                if (i > 0)
                    json.append(",");
                json.append("{\"id\":").append(n.id()).append(",\"admin\":\"").append(n.adminName())
                        .append("\",\"note\":\"").append(n.note().replace("\"", "\\\"")).append("\",\"time\":")
                        .append(n.createdAt()).append("}");
            }
            json.append("]}");

            t.getResponseHeaders().add("Content-Type", "application/json");
            sendResponse(t, json.toString());
        }
    }

    private byte[] extractFileFromMultipart(byte[] body, String boundary) {
        try {
            String bodyStr = new String(body, StandardCharsets.ISO_8859_1);
            String delimiter = "--" + boundary;
            int start = bodyStr.indexOf(delimiter);
            if (start == -1)
                return null;

            int headerEnd = bodyStr.indexOf("\r\n\r\n", start);
            if (headerEnd == -1)
                return null;

            int contentStart = headerEnd + 4;
            int contentEnd = bodyStr.indexOf("\r\n" + delimiter, contentStart);
            if (contentEnd == -1)
                return null;

            return Arrays.copyOfRange(body, contentStart, contentEnd);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractFileNameFromMultipart(byte[] body, String boundary) {
        try {
            String bodyStr = new String(body, StandardCharsets.UTF_8);
            int fnIndex = bodyStr.indexOf("filename=\"");
            if (fnIndex == -1)
                return "upload.zip";

            int fnStart = fnIndex + 10;
            int fnEnd = bodyStr.indexOf("\"", fnStart);
            if (fnEnd == -1)
                return "upload.zip";

            return bodyStr.substring(fnStart, fnEnd);
        } catch (Exception e) {
            return "upload.zip";
        }
    }

    private class FileUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.files.write", true)) {
                return;
            }
            if (!"POST".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, 0);
                t.close();
                return;
            }

            try {
                byte[] body = readRequestBodyOrReject(t, 64L * 1024L * 1024L);
                if (body == null) return;
                String contentType = t.getRequestHeaders().getFirst("Content-Type");

                if (contentType != null && contentType.contains("multipart/form-data")) {
                    String boundary = contentType.split("boundary=")[1];
                    byte[] fileData = extractFileFromMultipart(body, boundary);
                    String fileName = extractFileNameFromMultipart(body, boundary);
                    String path = extractFormField(body, boundary, "path");
                    String fullPath = extractFormField(body, boundary, "fullpath");

                    if (fileData != null && fileName != null) {
                        File serverDir = Bukkit.getWorldContainer();
                        File targetFile;

                        if (fullPath != null && !fullPath.isEmpty()) {
                            fullPath = fullPath.replaceAll("\\.\\.", "").replaceAll("//+", "/");
                            targetFile = new File(serverDir, fullPath);
                        } else {
                            fileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
                            File targetDir = path != null && !path.isEmpty() ? new File(serverDir, path) : serverDir;
                            targetFile = new File(targetDir, fileName);
                        }

                        if (targetFile.getCanonicalFile().toPath().startsWith(serverDir.getCanonicalFile().toPath())) {
                            if (isProtectedLockFile(targetFile)) {
                                t.getResponseHeaders().add("Content-Type", "application/json");
                                sendResponse(t, "{\"success\": false, \"error\": \"Protected lock file upload blocked\"}");
                                return;
                            }
                            File parentDir = targetFile.getParentFile();
                            if (parentDir != null && !parentDir.exists()) {
                                parentDir.mkdirs();
                            }
                            Files.write(targetFile.toPath(), fileData);
                            WebActionLogger.logUpload("FILE", targetFile.getName(), getClientIp(t));
                            t.getResponseHeaders().add("Content-Type", "application/json");
                            sendResponse(t, "{\"success\": true}");
                            return;
                        }
                    }
                }
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\": false, \"error\": \"Invalid upload\"}");
            } catch (Exception e) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\": false, \"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        }
    }

    private class PluginUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensureAnyPermission(t, true, "dash.web.plugins.manage", "dash.web.plugins.write")) {
                return;
            }
            if (!"POST".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, 0);
                t.close();
                return;
            }

            try {
                byte[] body = readRequestBodyOrReject(t, 64L * 1024L * 1024L);
                if (body == null) return;
                String contentType = t.getRequestHeaders().getFirst("Content-Type");

                if (contentType != null && contentType.contains("multipart/form-data")) {
                    String boundary = contentType.split("boundary=")[1];
                    byte[] fileData = extractFileFromMultipart(body, boundary);
                    String fileName = extractFileNameFromMultipart(body, boundary);

                    if (fileData != null && fileName != null
                            && fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        File pluginsDir = new File(Bukkit.getWorldContainer(), "plugins");
                        fileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
                        dash.security.JarUploadSecurity.validateArchive(
                                fileData, Set.of("plugin.yml", "paper-plugin.yml"));
                        Path targetFile = dash.security.JarUploadSecurity.writeAtomically(
                                pluginsDir.toPath(), fileName, fileData);
                        WebActionLogger.logUpload("PLUGIN", fileName, getClientIp(t));
                        t.getResponseHeaders().add("Content-Type", "application/json");
                        sendResponse(t, "{\"success\": true, \"message\": \"Plugin uploaded to "
                                + jsonEscape(targetFile.getFileName().toString()) + ". Restart to load.\"}");
                        return;
                    }
                }
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\": false, \"error\": \"Only .jar files allowed\"}");
            } catch (Exception e) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\": false, \"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        }
    }

    private String extractFormField(byte[] body, String boundary, String fieldName) {
        try {
            String bodyStr = new String(body, StandardCharsets.UTF_8);
            String search = "name=\"" + fieldName + "\"";
            int fieldIdx = bodyStr.indexOf(search);
            if (fieldIdx == -1)
                return null;

            int valueStart = bodyStr.indexOf("\r\n\r\n", fieldIdx) + 4;
            int valueEnd = bodyStr.indexOf("\r\n--", valueStart);
            if (valueEnd == -1)
                return null;

            return bodyStr.substring(valueStart, valueEnd).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isProtectedLockFile(File file) {
        if (file == null) {
            return false;
        }
        String name = file.getName().toLowerCase(Locale.ROOT);
        return "session.lock".equals(name)
                || name.endsWith(".lock")
                || name.endsWith(".lck")
                || name.endsWith(".pid");
    }

    private boolean deleteRecursively(File target) {
        if (target == null || !target.exists()) {
            return false;
        }
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (isProtectedLockFile(child)) {
                        continue;
                    }
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return target.delete();
    }
}
