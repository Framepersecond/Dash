package dash;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import dash.web.*;
import dash.bridge.ConsoleCatcher;
import dash.bridge.BridgeSecurity;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class AdminWebServer {

    private static final String SESSION_COOKIE_NAME = "dash_auth";
    private static final long SESSION_TTL_MS = 3600000L;
    private static final long SSO_SIGNATURE_MAX_AGE_SECONDS = 300L;

    private final JavaPlugin plugin;
    private final WebAuth auth;
    private HttpServer server;
    private final int port;
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> usedSsoSignatures = new ConcurrentHashMap<>();

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
    }

    public void start() {
        ConsoleLogAppender.register();

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);

            server.createContext("/", new PageHandler());
            server.createContext("/login", new PageHandler());
            server.createContext("/console", new PageHandler());
            server.createContext("/players", new PageHandler());
            server.createContext("/files", new PageHandler());
            server.createContext("/files/edit", new PageHandler());
            server.createContext("/plugins", new PageHandler());
            server.createContext("/users", new PageHandler());
            server.createContext("/permissions", new PageHandler());
            server.createContext("/settings", new PageHandler());
            server.createContext("/setup", new PageHandler());
            server.createContext("/waiting-room", new PageHandler());
            server.createContext("/audit", new PageHandler());
            server.createContext("/plugin-settings", new PageHandler());
            server.createContext("/scheduled-tasks", new PageHandler());

            server.createContext("/api/console", new ConsoleApiHandler());
            server.createContext("/api/health", new HealthApiHandler());
            server.createContext("/api/server/state", new HealthApiHandler());
            server.createContext("/api/ping", new HealthApiHandler());
            server.createContext("/api/logout", new LogoutApiHandler());
            server.createContext("/api/stats", new StatsApiHandler());
            server.createContext("/api/stats/history", new StatsHistoryHandler());
            server.createContext("/api/files/save", new FileSaveHandler());
            server.createContext("/api/backups/download", new BackupDownloadHandler());
            server.createContext("/api/upload/icon", new IconUploadHandler());
            server.createContext("/api/upload/datapack", new DatapackUploadHandler());
            server.createContext("/api/upload/file", new FileUploadHandler());
            server.createContext("/api/upload/plugin", new PluginUploadHandler());
            server.createContext("/api/players/profile", new PlayerProfileHandler());
            server.createContext("/api/bridge/console", new BridgeConsoleHandler());
            server.createContext("/api/webhook/approve", new WebhookApproveHandler());
            server.createContext("/sso", new SsoAuthHandler(plugin, auth, this));

            server.createContext("/action", new ActionHandler());

            server.setExecutor(null);
            server.start();
            plugin.getLogger().info("Web admin started on port " + port);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to start web server: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null)
            server.stop(0);
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
        String token = UUID.randomUUID().toString();
        boolean bridgeBound = bridgeSecretSnapshot != null && !bridgeSecretSnapshot.isBlank();
        sessions.put(token, new SessionInfo(username, System.currentTimeMillis() + SESSION_TTL_MS, bridgeBound,
                bridgeSecretSnapshot));
        t.getResponseHeaders().add(
                "Set-Cookie",
                SESSION_COOKIE_NAME + "=" + token + "; Path=/; HttpOnly; SameSite=Lax");
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

    private void clearSession(HttpExchange t) {
        String token = getSessionToken(t);
        if (token != null) {
            sessions.remove(token);
        }
        t.getResponseHeaders().add(
                "Set-Cookie",
                SESSION_COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
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
            if ("user".equals(key) || "timestamp".equals(key) || "signature".equals(key) || "token".equals(key)) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append("&");
            }
            out.append(part);
        }
        return out.toString();
    }

    private String getClientIp(HttpExchange t) {
        return t.getRemoteAddress().getAddress().getHostAddress();
    }

    private boolean ensurePermission(HttpExchange t, String permission, boolean jsonResponse) throws IOException {
        if (!isAuthenticated(t)) {
            t.sendResponseHeaders(403, 0);
            t.close();
            return false;
        }

        String username = getSessionUser(t);
        if (username == null || !auth.userHasPermission(username, permission)) {
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

        String username = getSessionUser(t);
        if (username != null) {
            for (String permission : permissions) {
                if (permission != null && !permission.isBlank() && auth.userHasPermission(username, permission)) {
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

    private class PageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String path = t.getRequestURI().getPath();
            String query = t.getRequestURI().getQuery();

            if (bootstrapBridgeSessionFromSignedQuery(t, path)) {
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
            HtmlTemplate.setUiPermissions(auth.getEffectivePermissions(sessionUser));
            WebAuth.UserInfo sessionUserInfo = auth.getUsers().get(sessionUser);
            boolean sessionIsBridgeUser = sessionUserInfo != null && sessionUserInfo.bridgeUser();
            String bridgeMasterUrl = plugin.getConfig().getString("bridge.master_url", "");
            HtmlTemplate.setBridgeContext(sessionIsBridgeUser, bridgeMasterUrl);

            String html;
            try {
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
                    html = PlayersPage.render();
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
                    html = SettingsPage.render(sessionUser, auth.isMainAdmin(sessionUser), msg);
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
                        html = PlayersPage.render();
                    }
                } else {
                    html = DashboardPage.render();
                }
            } finally {
                HtmlTemplate.clearUiPermissions();
                HtmlTemplate.clearBridgeContext();
            }

            sendResponse(t, html);
        }
    }

    private void serveRegistration(HttpExchange t) throws IOException {
        String html = "<!DOCTYPE html><html class=\"dark\" lang=\"en\"><head><meta charset=\"utf-8\"/><title>Dash Setup</title>"
                +
                "<script src=\"https://cdn.tailwindcss.com\"></script></head><body class=\"bg-slate-900 text-white flex items-center justify-center h-screen\">"
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

                // Keep legacy keys for the local dashboard and add camelCase keys for NeoDash.
                String json = String.format(
                        "{\"tps\": %.2f, \"ram_used\": %d, \"ram_max\": %d, \"ramUsed\": %d, \"ramMax\": %d, \"cpu_percent\": %.2f, \"cpuPercent\": %.2f, \"cpuUsage\": %.2f, \"uptime\": \"%s\"}",
                        currentTps, usedMem, maxMem, usedMem, maxMem, cpuPercent, cpuPercent, cpuPercent, uptimeStr);

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

            String json = "{\"status\":\"online\",\"uptime\":\"" + jsonEscape(formatUptime()) + "\"}";
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
                String json = collector != null ? collector.getHistoryJson() : "[]";
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, json);
            } catch (Exception e) {
                sendResponse(t, "[]");
            }
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
                String body = new String(t.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
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

            String auditMsg = allow ? "allowed user=" + result.message() : "denied token=" + token;
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

            if ("POST".equals(t.getRequestMethod())) {
                String body = new String(t.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> params = parseFormData(body);

                String filePath = params.get("path");
                String content = params.get("content");

                if (filePath == null || content == null) {
                    t.getResponseHeaders().add("Content-Type", "application/json");
                    sendResponse(t, "{\"success\": false, \"error\": \"Missing parameters\"}");
                    return;
                }

                File serverDir = Bukkit.getWorldContainer();
                File file = new File(serverDir, filePath);

                try {
                    if (!file.getCanonicalPath().startsWith(serverDir.getCanonicalPath())) {
                        t.getResponseHeaders().add("Content-Type", "application/json");
                        sendResponse(t, "{\"success\": false, \"error\": \"Access denied\"}");
                        return;
                    }
                    if (isProtectedLockFile(file)) {
                        t.getResponseHeaders().add("Content-Type", "application/json");
                        sendResponse(t, "{\"success\": false, \"error\": \"Protected lock file cannot be edited\"}");
                        return;
                    }

                    if (file.exists()) {
                        File backup = new File(file.getPath() + ".bak");
                        Files.copy(file.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }

                    Files.writeString(file.toPath(), content);
                    WebActionLogger.logFileEdit(filePath, getClientIp(t));
                    t.getResponseHeaders().add("Content-Type", "application/json");
                    sendResponse(t, "{\"success\": true}");
                } catch (Exception e) {
                    t.getResponseHeaders().add("Content-Type", "application/json");
                    sendResponse(t, "{\"success\": false, \"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
                }
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

            String body = new String(t.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(body);
            String action = params.get("action");
            String referer = t.getRequestHeaders().getFirst("Referer");
            String redirectTo = referer != null ? referer : "/";
            String clientIp = getClientIp(t);

            if ("register_code".equals(action)) {
                String code = params.get("code");
                String username = params.get("username");
                String password = params.get("password");
                String owner2fa = params.get("owner_2fa_code");

                if (code != null && username != null && password != null) {
                    if (auth.isSetupRequired()) {
                        boolean success = auth.registerFirstAdminWithCode(code, username, password);
                        if (success) {
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
                String username = params.get("username");
                if (auth.check(username, params.get("password"))) {
                    setSession(t, username);
                    WebActionLogger.logLogin(username, clientIp);
                    redirect(t, "/");
                } else {
                    WebActionLogger.log("LOGIN_FAILED", "User '" + username + "' failed from " + clientIp);
                    sendResponse(t, "<h1>Login Failed</h1><a href='/'>Try Again</a>");
                }
                return;
            }

            if (!isAuthenticated(t)) {
                redirect(t, "/");
                return;
            }

            if ("logout".equals(action)) {
                clearSession(t);
                WebActionLogger.logLogout(clientIp);
                redirect(t, "/");
                return;
            }

            String requiredPermission = requiredPermissionForAction(action);
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

                // General settings
                String webPortStr = params.get("web_port");
                String panelUrlVal = params.get("panel_url");
                String maxBackupsStr = params.get("max_backups");

                if (webPortStr != null) {
                    try {
                        int newPort = Integer.parseInt(webPortStr.trim());
                        if (newPort >= 1 && newPort <= 65535) {
                            plugin.getConfig().set("port", newPort);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (panelUrlVal != null) {
                    plugin.getConfig().set("panel-url", panelUrlVal.trim());
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

                // NeoBridge settings
                boolean bridgeEnabled = params.containsKey("bridge_enabled");
                String bridgeSecret = params.get("bridge_secret");
                String bridgeMasterUrl = params.get("bridge_master_url");
                plugin.getConfig().set("bridge.enabled", bridgeEnabled);
                plugin.getConfig().set("bridge.secret", bridgeSecret == null ? "" : bridgeSecret.trim());
                plugin.getConfig().set("bridge.master_url", bridgeMasterUrl == null ? "" : bridgeMasterUrl.trim());

                // Discord webhooks: discover all posted rows by wh_url_<index>
                java.util.List<Integer> webhookIndexes = params.keySet().stream()
                        .filter(k -> k.startsWith("wh_url_"))
                        .map(k -> k.substring("wh_url_".length()))
                        .map(idx -> {
                            try {
                                return Integer.parseInt(idx);
                            } catch (NumberFormatException ex) {
                                return null;
                            }
                        })
                        .filter(java.util.Objects::nonNull)
                        .sorted()
                        .toList();

                java.util.List<DiscordWebhookManager.WebhookEntry> webhookEntries = new java.util.ArrayList<>();
                for (Integer idx : webhookIndexes) {
                    String whUrl = params.get("wh_url_" + idx);
                    if (whUrl == null || whUrl.isBlank()) {
                        continue;
                    }

                    java.util.List<String> events = new java.util.ArrayList<>();
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
                } else {
                    plugin.saveConfig();
                }

                WebActionLogger.logSettingChange("plugin_settings", "updated", clientIp);
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
                    redirect(t, "/settings?msg=" + encodeForQuery("Only MAIN_ADMIN can regenerate owner 2FA."));
                    return;
                }
                String secret = auth.regenerateOwner2faSecret(getSessionUser(t));
                redirect(t, "/settings?msg=" + encodeForQuery(secret == null
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
                    if (!target.getCanonicalPath().startsWith(serverDir.getCanonicalPath())) {
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
                redirect(t, "/files");
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
                    if (!source.getCanonicalPath().startsWith(serverDir.getCanonicalPath())) {
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
                    if (!target.getCanonicalPath().startsWith(serverDir.getCanonicalPath())) {
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
                    if (!target.getCanonicalPath().startsWith(pluginsDir.getCanonicalPath())) {
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

            Bukkit.getScheduler().runTask(plugin, () -> {
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
                    case "backup_create":
                        dash.data.BackupManager bm = Dash.getBackupManager();
                        if (bm != null) {
                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                bm.createBackup();
                                WebActionLogger.logBackup("CREATE", "Manual backup from " + clientIp);
                            });
                        }
                        break;
                    case "backup_delete":
                        dash.data.BackupManager bmd = Dash.getBackupManager();
                        String backupName = params.get("name");
                        if (bmd != null && backupName != null) {
                            bmd.deleteBackup(backupName);
                            WebActionLogger.logBackup("DELETE", backupName + " from " + clientIp);
                        }
                        break;
                    case "backup_schedule":
                        dash.data.BackupManager bms = Dash.getBackupManager();
                        String hours = params.get("hours");
                        if (bms != null && hours != null) {
                            bms.startSchedule(Integer.parseInt(hours));
                            WebActionLogger.logBackup("SCHEDULE", hours + " hours from " + clientIp);
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
                        Plugin spark = Bukkit.getPluginManager().getPlugin("spark");
                        if (spark != null && spark.isEnabled()) {
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
                                if (simDist != null && !simDist.isBlank()) {
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
            });

            redirect(t, redirectTo);
        }
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
        return switch (action) {
            case "command" -> "dash.web.console.command";
            case "restart", "stop" -> "dash.web.server.control";
            case "kick" -> "dash.web.players.kick";
            case "ban" -> "dash.web.players.ban";
            case "freeze", "tp_to_coords", "tp_player_to_player" -> "dash.web.players.moderate";
            case "gamerule", "set_motd", "set_distance" -> null;
            case "whitelist_add", "whitelist_remove", "whitelist_toggle" -> "dash.web.whitelist.manage";
            case "plugin_enable", "plugin_disable" -> "dash.web.plugins.manage";
            case "plugin_delete" -> "dash.web.plugins.manage";
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
            case "save_plugin_settings" -> "dash.web.pluginsettings.write";
            case "task_add", "task_toggle", "task_delete" -> "dash.web.tasks.write";
            default -> null;
        };
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

    private void redirect(HttpExchange t, String location) throws IOException {
        t.getResponseHeaders().set("Location", location);
        t.sendResponseHeaders(302, -1);
        t.close();
    }

    private void sendResponse(HttpExchange t, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = t.getResponseBody()) {
            os.write(bytes, 0, bytes.length);
            os.flush();
        }
    }

    private void sendResponseWithStatus(HttpExchange t, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = t.getResponseBody()) {
            os.write(bytes, 0, bytes.length);
            os.flush();
        }
    }

    private Map<String, String> parseFormData(String formData) {
        Map<String, String> map = new HashMap<>();
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
                byte[] body = t.getRequestBody().readAllBytes();
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
                byte[] body = t.getRequestBody().readAllBytes();
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
                byte[] body = t.getRequestBody().readAllBytes();
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

                        if (targetFile.getCanonicalPath().startsWith(serverDir.getCanonicalPath())) {
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
            if (!ensurePermission(t, "dash.web.plugins.write", true)) {
                return;
            }
            if (!"POST".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, 0);
                t.close();
                return;
            }

            try {
                byte[] body = t.getRequestBody().readAllBytes();
                String contentType = t.getRequestHeaders().getFirst("Content-Type");

                if (contentType != null && contentType.contains("multipart/form-data")) {
                    String boundary = contentType.split("boundary=")[1];
                    byte[] fileData = extractFileFromMultipart(body, boundary);
                    String fileName = extractFileNameFromMultipart(body, boundary);

                    if (fileData != null && fileName != null && fileName.endsWith(".jar")) {
                        File pluginsDir = new File(Bukkit.getWorldContainer(), "plugins");
                        fileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
                        File targetFile = new File(pluginsDir, fileName);

                        Files.write(targetFile.toPath(), fileData);
                        WebActionLogger.logUpload("PLUGIN", fileName, getClientIp(t));
                        t.getResponseHeaders().add("Content-Type", "application/json");
                        sendResponse(t, "{\"success\": true, \"message\": \"Plugin uploaded. Restart to load.\"}");
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
