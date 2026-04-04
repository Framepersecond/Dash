package dash.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dash.AdminWebServer;
import dash.WebActionLogger;
import dash.WebAuth;
import dash.bridge.BridgeSecurity;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class SsoAuthHandler implements HttpHandler {

    private static final long MAX_AGE_SECONDS = 300L;
    private static final ConcurrentHashMap<String, Long> USED_SIGNATURES = new ConcurrentHashMap<>();

    private final JavaPlugin plugin;
    private final WebAuth auth;
    private final AdminWebServer webServer;

    public SsoAuthHandler(JavaPlugin plugin, WebAuth auth, AdminWebServer webServer) {
        this.plugin = plugin;
        this.auth = auth;
        this.webServer = webServer;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        if (!plugin.getConfig().getBoolean("bridge.enabled", true)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String username = query.get("user");
        String timestampRaw = query.get("timestamp");
        String signature = query.get("signature");

        // Missing SSO params means this is not a signed SSO attempt.
        if (signature == null || username == null || timestampRaw == null) {
            redirect(exchange, "/login");
            return;
        }

        if (username.isBlank()) {
            redirect(exchange, "/login?error=sso_invalid");
            return;
        }
        String normalizedUser = username.trim();

        long incomingTimestamp;
        try {
            incomingTimestamp = Long.parseLong(timestampRaw);
        } catch (NumberFormatException ex) {
            redirect(exchange, "/login?error=sso_invalid");
            return;
        }

        long now = Instant.now().getEpochSecond();
        long deltaSeconds = now - incomingTimestamp;
        if (Math.abs(deltaSeconds) > MAX_AGE_SECONDS) {
            redirect(exchange, "/login?error=sso_expired");
            return;
        }

        String bridgeSecret = plugin.getConfig().getString("bridge.secret", "").trim();
        if (bridgeSecret.isBlank()) {
            redirect(exchange, "/login?error=sso_invalid");
            return;
        }

        String localHmacInput = normalizedUser.toLowerCase(Locale.ROOT) + ":" + timestampRaw;
        String expected = hmacSha256Hex(localHmacInput, bridgeSecret);
        String normalizedSignature = BridgeSecurity.normalizeHex(signature);
        if (expected == null
                || normalizedSignature.length() != expected.length()
                || !BridgeSecurity.equalsConstantTime(expected, normalizedSignature)) {
            WebActionLogger.log("SSO_REJECTED", "user=" + normalizedUser + " reason=signature_mismatch");
            redirect(exchange, "/login?error=sso_invalid");
            return;
        }

        if (isReplay(normalizedSignature, System.currentTimeMillis())) {
            WebActionLogger.log("SSO_REJECTED", "user=" + normalizedUser + " reason=replay_detected");
            redirect(exchange, "/login?error=sso_invalid");
            return;
        }

        WebAuth.BridgeSsoResult result = auth.getOrCreateBridgeUserForSso(normalizedUser);
        String sessionUser = result.username() == null ? normalizedUser : result.username();
        if (!result.approved() || !isApprovedBridgeUser(sessionUser)) {
            redirect(exchange, "/waiting-room?user=" + encode(normalizedUser));
            return;
        }

        webServer.createAuthenticatedSession(exchange, sessionUser);
        redirect(exchange, "/");
    }

    private boolean isReplay(String signature, long now) {
        cleanupReplayCache(now);
        Long existing = USED_SIGNATURES.putIfAbsent(signature, now);
        if (existing == null) {
            return false;
        }
        return (now - existing) <= (MAX_AGE_SECONDS * 1000L);
    }

    private void cleanupReplayCache(long now) {
        USED_SIGNATURES.entrySet().removeIf(entry -> (now - entry.getValue()) > (MAX_AGE_SECONDS * 1000L));
    }

    private boolean isApprovedBridgeUser(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        WebAuth.UserInfo info = auth.getUsers().get(username);
        return info != null && info.bridgeUser() && info.bridgeApproved();
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

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }

        String[] parts = rawQuery.split("&");
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                values.put(key, value);
            }
        }
        return values;
    }

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}

