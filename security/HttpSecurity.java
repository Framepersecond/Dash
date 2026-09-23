package dash.security;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

public final class HttpSecurity {
    public static final String HTML_UTF8 = "text/html; charset=utf-8";
    private static final SecureRandom RANDOM = new SecureRandom();

    private HttpSecurity() {
    }

    public static String newSessionToken() {
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    public static void applyResponseHeaders(HttpExchange exchange, boolean configuredHttps) {
        if (exchange == null) {
            return;
        }
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        // "no-referrer" makes browsers send `Origin: null` on same-origin form
        // posts and strips the Referer as well, which left the same-origin check
        // below with nothing to verify and blocked plain <form> actions such as
        // Restart/Stop. "same-origin" keeps referrers off cross-origin requests
        // while still identifying our own.
        exchange.getResponseHeaders().set("Referrer-Policy", "same-origin");
        exchange.getResponseHeaders().set("Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        exchange.getResponseHeaders().set("Cross-Origin-Opener-Policy", "same-origin");
        exchange.getResponseHeaders().set("Cross-Origin-Resource-Policy", "same-origin");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "frame-ancestors 'none'; base-uri 'self'; object-src 'none'");
        if (isHttps(exchange, configuredHttps)) {
            exchange.getResponseHeaders().set("Strict-Transport-Security", "max-age=15552000");
        }
    }

    public static void ensureContentType(Headers headers, String fallback) {
        if (headers == null || fallback == null || fallback.isBlank()) {
            return;
        }
        String current = headers.getFirst("Content-Type");
        if (current == null || current.isBlank()) {
            headers.set("Content-Type", fallback);
        }
    }

    public static String secureCookieSuffix(HttpExchange exchange, boolean configuredHttps) {
        return isHttps(exchange, configuredHttps) ? "; Secure" : "";
    }

    public static byte[] readRequestBody(HttpExchange exchange, long maxBytes) throws IOException {
        if (exchange == null || maxBytes < 1) {
            throw new IllegalArgumentException("exchange and a positive limit are required");
        }
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null && !contentLength.isBlank()) {
            try {
                if (Long.parseLong(contentLength.trim()) > maxBytes) {
                    throw new RequestBodyTooLargeException();
                }
            } catch (NumberFormatException ex) {
                throw new IOException("Invalid Content-Length header.", ex);
            }
        }
        try (InputStream input = exchange.getRequestBody();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new RequestBodyTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    public static final class RequestBodyTooLargeException extends IOException {
        public RequestBodyTooLargeException() {
            super("Request body exceeds the configured limit.");
        }
    }

    public static boolean isSameOriginMutation(HttpExchange exchange, String configuredPublicUrl) {
        if (exchange == null || isSafeMethod(exchange.getRequestMethod())) {
            return true;
        }

        String fetchSite = header(exchange, "Sec-Fetch-Site");
        if ("cross-site".equalsIgnoreCase(fetchSite)) {
            return false;
        }
        if ("same-origin".equalsIgnoreCase(fetchSite)) {
            return true;
        }

        String source = header(exchange, "Origin");
        if (source.isBlank() || "null".equalsIgnoreCase(source)) {
            source = header(exchange, "Referer");
        }
        if (source.isBlank() || "null".equalsIgnoreCase(source)) {
            return false;
        }

        URI sourceUri = parseHttpUri(source);
        if (sourceUri == null) {
            return false;
        }
        URI configuredUri = parseHttpUri(configuredPublicUrl);
        if (configuredUri != null && sameEndpoint(sourceUri, configuredUri)) {
            return true;
        }

        return matchesRequestHost(exchange, sourceUri);
    }

    /**
     * Compares the request's own host against the source origin. Behind a
     * reverse proxy the browser-facing host lives in {@code X-Forwarded-Host},
     * while {@code Host} carries whatever the proxy dialled, so both are
     * accepted; the bare hostname is compared too because a proxy that
     * terminates on the default port drops it from the forwarded value.
     */
    private static boolean matchesRequestHost(HttpExchange exchange, URI sourceUri) {
        String sourceAuthority = authority(sourceUri);
        String sourceHost = sourceUri.getHost().toLowerCase(Locale.ROOT);
        for (String candidate : new String[] {
                header(exchange, "X-Forwarded-Host"), header(exchange, "Host") }) {
            if (candidate.isBlank()) {
                continue;
            }
            String host = candidate.split(",", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (host.isEmpty()) {
                continue;
            }
            if (host.equals(sourceAuthority) || host.equals(sourceHost)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHttps(HttpExchange exchange, boolean configuredHttps) {
        if (configuredHttps) {
            return true;
        }
        String forwardedProto = header(exchange, "X-Forwarded-Proto");
        if (!forwardedProto.isBlank()) {
            forwardedProto = forwardedProto.split(",", 2)[0].trim();
        }
        return "https".equalsIgnoreCase(forwardedProto);
    }

    private static boolean isSafeMethod(String method) {
        return "GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    private static URI parseHttpUri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean sameEndpoint(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static String authority(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        int port = uri.getPort();
        return port < 0 ? host : host + ":" + port;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String header(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? "" : value.trim();
    }
}
