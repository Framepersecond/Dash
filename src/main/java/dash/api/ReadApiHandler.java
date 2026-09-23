package dash.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dash.security.HttpSecurity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Serves the read-only HTTP API under {@code /api/v1/}.
 *
 * <h2>Why this is safe to expose</h2>
 * <ul>
 *   <li><b>Opt-in.</b> Disabled unless {@code api.enabled} is set; a disabled
 *       API answers 404 and never even looks at the Authorization header.</li>
 *   <li><b>Structurally read-only.</b> Only GET and HEAD reach any logic; every
 *       other method is rejected before authentication. The handler holds a
 *       {@link DashReadApi}, which has no mutating operation to call, so there
 *       is no write path to reach even by mistake.</li>
 *   <li><b>Separate credentials.</b> API keys are their own namespace. They are
 *       not panel sessions, they are not the bridge secret, and they grant no
 *       access to any route outside this handler -- so a leaked read key cannot
 *       be replayed against the admin UI.</li>
 *   <li><b>Least privilege.</b> Each endpoint demands a specific
 *       {@link ApiScope}; a key holds only the scopes it was issued with.</li>
 *   <li><b>Rate limited</b> per key and per address, before any hashing.</li>
 *   <li><b>No browser cross-origin access.</b> No CORS headers are emitted, so
 *       a malicious page cannot read the API using a victim's network position.
 *       Responses are {@code no-store}.</li>
 *   <li><b>Nothing sensitive in the payload.</b> No secrets, no tokens, no file
 *       paths, no world seeds, no player IP addresses.</li>
 * </ul>
 */
public final class ReadApiHandler implements HttpHandler {

    public static final String BASE_PATH = "/api/v1";

    private static final String JSON = "application/json; charset=utf-8";
    private static final int MAX_HISTORY_POINTS = 1440;

    private final Supplier<DashReadApi> apiSupplier;
    private final ApiKeyStore keys;
    private final ApiSettings settings;
    private final ApiRateLimiter limiter;
    private final BiConsumer<String, String> auditLogger;

    /** Convenience overload for a fixed implementation, used by the tests. */
    public ReadApiHandler(DashReadApi api, ApiKeyStore keys, ApiSettings settings,
                          BiConsumer<String, String> auditLogger) {
        this(() -> api, keys, settings, auditLogger);
    }

    /**
     * @param apiSupplier resolved per request rather than captured, so the
     *                    handler never holds a stale or not-yet-installed
     *                    implementation and mounting order cannot matter
     * @param auditLogger receives {@code (action, details)} for the audit trail;
     *                    Dash passes {@code WebActionLogger::log}.
     */
    public ReadApiHandler(Supplier<DashReadApi> apiSupplier, ApiKeyStore keys, ApiSettings settings,
                          BiConsumer<String, String> auditLogger) {
        this.apiSupplier = apiSupplier;
        this.keys = keys;
        this.settings = settings;
        this.auditLogger = auditLogger;
        this.limiter = new ApiRateLimiter(settings.rateLimitPerMinute());
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (RuntimeException unexpected) {
            // Never leak a stack trace or class name to an API consumer.
            send(exchange, 500, error("internal_error", "Request could not be completed."));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        if (!settings.enabled()) {
            send(exchange, 404, error("not_found", "The Dash read API is disabled."));
            return;
        }

        // Read-only is enforced here, before anything else happens.
        String method = exchange.getRequestMethod();
        boolean head = "HEAD".equalsIgnoreCase(method);
        if (!head && !"GET".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            send(exchange, 405, error("method_not_allowed", "This API is read-only; use GET."));
            return;
        }

        String client = clientAddress(exchange);
        if (!limiter.tryAcquire("ip:" + client)) {
            tooManyRequests(exchange, "ip:" + client);
            return;
        }

        Optional<ApiKey> authenticated = keys.authenticate(exchange.getRequestHeaders().getFirst("Authorization"));
        if (authenticated.isEmpty()) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"dash\"");
            send(exchange, 401, error("unauthorized", "A valid read-only API key is required."));
            return;
        }
        ApiKey key = authenticated.get();

        if (!limiter.tryAcquire("key:" + key.id())) {
            tooManyRequests(exchange, "key:" + key.id());
            return;
        }

        DashReadApi api = apiSupplier == null ? null : apiSupplier.get();
        if (api == null) {
            // Dash is starting or shutting down; say so rather than erroring.
            send(exchange, 503, error("unavailable", "The server is not ready yet."));
            return;
        }

        String path = normalizePath(exchange.getRequestURI().getPath());
        try {
            dispatch(exchange, api, key, path, head);
        } finally {
            keys.touch(key.id());
        }
    }

    private void dispatch(HttpExchange exchange, DashReadApi api, ApiKey key, String path, boolean head)
            throws IOException {
        switch (path) {
            case "", "/" -> send(exchange, 200, index(api, key), head);

            case "/server" -> {
                if (denied(exchange, key, ApiScope.SERVER)) {
                    return;
                }
                send(exchange, 200, render(api.server()), head);
            }

            case "/performance" -> {
                if (denied(exchange, key, ApiScope.PERFORMANCE)) {
                    return;
                }
                send(exchange, 200, render(api.performance()), head);
            }

            case "/performance/history" -> {
                if (denied(exchange, key, ApiScope.PERFORMANCE)) {
                    return;
                }
                int points = clampedIntParam(exchange, "points", 60, 1, MAX_HISTORY_POINTS);
                List<String> rendered = new ArrayList<>();
                for (PerformanceSnapshot sample : api.performanceHistory(points)) {
                    rendered.add(render(sample));
                }
                send(exchange, 200, ApiJson.object()
                        .field("count", rendered.size())
                        .raw("samples", ApiJson.array(rendered))
                        .end(), head);
            }

            case "/players" -> {
                if (denied(exchange, key, ApiScope.PLAYERS)) {
                    return;
                }
                List<String> rendered = new ArrayList<>();
                for (PlayerSnapshot player : api.players()) {
                    rendered.add(render(player));
                }
                send(exchange, 200, ApiJson.object()
                        .field("count", rendered.size())
                        .raw("players", ApiJson.array(rendered))
                        .end(), head);
            }

            case "/worlds" -> {
                if (denied(exchange, key, ApiScope.WORLDS)) {
                    return;
                }
                List<String> rendered = new ArrayList<>();
                for (WorldSnapshot world : api.worlds()) {
                    rendered.add(render(world));
                }
                send(exchange, 200, ApiJson.object()
                        .field("count", rendered.size())
                        .raw("worlds", ApiJson.array(rendered))
                        .end(), head);
            }

            case "/extensions" -> {
                if (denied(exchange, key, ApiScope.EXTENSIONS)) {
                    return;
                }
                List<String> rendered = new ArrayList<>();
                for (ExtensionSnapshot extension : api.extensions()) {
                    rendered.add(render(extension));
                }
                send(exchange, 200, ApiJson.object()
                        .field("count", rendered.size())
                        .raw("extensions", ApiJson.array(rendered))
                        .end(), head);
            }

            default -> {
                if (path.startsWith("/players/")) {
                    if (denied(exchange, key, ApiScope.PLAYERS)) {
                        return;
                    }
                    servePlayer(exchange, api, path.substring("/players/".length()), head);
                    return;
                }
                send(exchange, 404, error("not_found", "Unknown endpoint."));
            }
        }
    }

    private void servePlayer(HttpExchange exchange, DashReadApi api, String rawIdentifier, boolean head)
            throws IOException {
        String identifier = URLDecoder.decode(rawIdentifier, StandardCharsets.UTF_8).trim();
        if (identifier.isEmpty() || identifier.length() > 64 || identifier.contains("/")) {
            send(exchange, 404, error("not_found", "Unknown player."));
            return;
        }
        Optional<PlayerSnapshot> found;
        try {
            found = api.player(UUID.fromString(identifier));
        } catch (IllegalArgumentException notAUuid) {
            found = api.playerByName(identifier);
        }
        if (found.isEmpty()) {
            send(exchange, 404, error("not_found", "Unknown player."));
            return;
        }
        send(exchange, 200, render(found.get()), head);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private String index(DashReadApi api, ApiKey key) {
        List<String> scopes = new ArrayList<>();
        for (ApiScope scope : ApiScope.values()) {
            if (key.hasScope(scope)) {
                scopes.add(scope.id());
            }
        }
        List<String> endpoints = List.of(
                BASE_PATH + "/server",
                BASE_PATH + "/performance",
                BASE_PATH + "/performance/history",
                BASE_PATH + "/players",
                BASE_PATH + "/players/{uuid|name}",
                BASE_PATH + "/worlds",
                BASE_PATH + "/extensions");
        return ApiJson.object()
                .field("api", "dash-read")
                .field("version", api.apiVersion())
                .field("readOnly", true)
                .strings("scopes", scopes)
                .strings("endpoints", endpoints)
                .end();
    }

    private static String render(ServerSnapshot s) {
        return ApiJson.object()
                .field("platform", s.platform())
                .field("softwareName", s.softwareName())
                .field("softwareVersion", s.softwareVersion())
                .field("minecraftVersion", s.minecraftVersion())
                .field("dashVersion", s.dashVersion())
                .field("onlinePlayers", s.onlinePlayers())
                .field("maxPlayers", s.maxPlayers())
                .field("uptimeMillis", s.uptimeMillis())
                .field("whitelistEnabled", s.whitelistEnabled())
                .end();
    }

    private static String render(PerformanceSnapshot p) {
        return ApiJson.object()
                .field("timestamp", p.timestampEpochMillis())
                .field("tps", p.tps())
                .field("mspt", p.mspt())
                .field("heapUsedBytes", p.heapUsedBytes())
                .field("heapMaxBytes", p.heapMaxBytes())
                .field("cpuLoad", p.cpuLoad())
                .field("loadedChunks", p.loadedChunks())
                .field("entityCount", p.entityCount())
                .end();
    }

    private static String render(PlayerSnapshot p) {
        return ApiJson.object()
                .uuid("uuid", p.uuid())
                .field("name", p.name())
                .field("online", p.online())
                .field("world", p.world())
                .field("gameMode", p.gameMode())
                .field("level", p.level())
                .field("health", p.health())
                .field("maxHealth", p.maxHealth())
                .field("operator", p.operator())
                .field("firstSeen", p.firstSeenEpochMillis())
                .field("lastSeen", p.lastSeenEpochMillis())
                .field("playtimeMillis", p.playtimeMillis())
                .end();
    }

    private static String render(WorldSnapshot w) {
        return ApiJson.object()
                .field("name", w.name())
                .field("environment", w.environment())
                .field("loadedChunks", w.loadedChunks())
                .field("entityCount", w.entityCount())
                .field("playerCount", w.playerCount())
                .field("difficulty", w.difficulty())
                .end();
    }

    private static String render(ExtensionSnapshot e) {
        return ApiJson.object()
                .field("id", e.id())
                .field("name", e.name())
                .field("version", e.version())
                .field("enabled", e.enabled())
                .strings("authors", e.authors())
                .end();
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private boolean denied(HttpExchange exchange, ApiKey key, ApiScope required) throws IOException {
        if (key.hasScope(required)) {
            return false;
        }
        audit("api.read.denied", "key=" + ApiKeyStore.auditName(key) + " scope=" + required.id());
        send(exchange, 403, error("forbidden", "This key lacks the scope " + required.id() + "."));
        return true;
    }

    private void tooManyRequests(HttpExchange exchange, String bucket) throws IOException {
        exchange.getResponseHeaders().set("Retry-After", Long.toString(limiter.retryAfterSeconds(bucket)));
        send(exchange, 429, error("rate_limited", "Too many requests."));
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.startsWith(BASE_PATH) ? path.substring(BASE_PATH.length()) : path;
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static int clampedIntParam(HttpExchange exchange, String name, int fallback, int min, int max) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !name.equals(pair.substring(0, eq))) {
                continue;
            }
            try {
                return Math.max(min, Math.min(max, Integer.parseInt(pair.substring(eq + 1).trim())));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String clientAddress(HttpExchange exchange) {
        // Deliberately the socket address, never a forwarded header: an
        // attacker controls those, and the rate limiter must not be bypassable
        // by spoofing X-Forwarded-For.
        if (exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null) {
            return "unknown";
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private static String error(String code, String message) {
        return ApiJson.object().field("error", code).field("message", message).end();
    }

    private void audit(String action, String details) {
        if (auditLogger != null) {
            try {
                auditLogger.accept(action, details);
            } catch (RuntimeException ignored) {
                // Auditing must never break a response.
            }
        }
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, body, false);
    }

    private void send(HttpExchange exchange, int status, String body, boolean headOnly) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpSecurity.applyResponseHeaders(exchange, settings.https());
        exchange.getResponseHeaders().set("Content-Type", JSON);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (headOnly) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
