package dash.security;

import com.sun.net.httpserver.HttpExchange;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Support for hosting the panel under a sub-path of a reverse proxy
 * (for example {@code https://example.org/admin/}).
 *
 * <p>Every link, form target and asset reference the panel emits is rooted at
 * {@code /}. Served straight from the mod that is correct, but behind a proxy
 * that mounts the panel below a prefix the browser resolves {@code /assets/…}
 * against the proxy root and the request never reaches us. Rather than making
 * several hundred hand-written URLs relative — which would break as soon as a
 * page moves to a different depth — the prefix is applied once, to the finished
 * response.
 *
 * <p>The prefix comes from the {@code base-path} config key, or, when that is
 * blank, from the {@code X-Forwarded-Prefix} header that nginx and Traefik set
 * when they strip a path prefix. Incoming request paths are expected to arrive
 * without the prefix, which is what a standard {@code proxy_pass
 * http://127.0.0.1:8080/} (note the trailing slash) and Traefik's
 * {@code StripPrefix} middleware both do.
 */
public final class BasePath {

    /** Attributes whose value is a URL the browser resolves against the document root. */
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "(\\s(?:href|src|action|formaction|poster|data-url|data-href|data-action|data-open-href)\\s*=\\s*)([\"'])/(?!/)",
            Pattern.CASE_INSENSITIVE);

    /** Inline-script navigation targets, which the runtime shim below cannot intercept. */
    private static final Pattern SCRIPT_NAVIGATION = Pattern.compile(
            "((?:window\\.)?location(?:\\.href)?\\s*=\\s*|(?:window\\.)?location\\.(?:assign|replace)\\(\\s*"
                    + "|window\\.open\\(\\s*|\\.action\\s*=\\s*)([\"'])/(?!/)");

    /** Allowed shape of a prefix: one or more plain path segments. */
    private static final Pattern VALID = Pattern.compile("(?:/[A-Za-z0-9._~-]+)+");

    private static volatile String remembered = "";

    private BasePath() {
    }

    /**
     * Normalises a configured or forwarded prefix to either {@code ""} or
     * {@code "/segment[/segment…]"}. Anything containing a traversal, a query
     * or characters that do not belong in a path prefix is rejected outright so
     * a hostile {@code X-Forwarded-Prefix} cannot rewrite our URLs to a foreign
     * origin.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty() || "/".equals(value)) {
            return "";
        }
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.contains("..") || !VALID.matcher(value).matches()) {
            return "";
        }
        return value;
    }

    /** The prefix that applies to one request: config first, then the proxy's hint. */
    public static String forRequest(HttpExchange exchange, String configured) {
        String base = normalize(configured);
        if (base.isEmpty()) {
            base = remembered;
        }
        if (!base.isEmpty() || exchange == null) {
            return base;
        }
        return normalize(exchange.getRequestHeaders().getFirst("X-Forwarded-Prefix"));
    }

    /**
     * Records the configured prefix so handlers that are constructed without a
     * reference to the config (SSO, the AI endpoint) can resolve it too. The
     * panel is a single instance per process, so one slot is enough.
     */
    public static void remember(String raw) {
        remembered = normalize(raw);
    }

    /** Prefixes a root-relative URL; absolute and protocol-relative URLs are left alone. */
    public static String apply(String url, String prefix) {
        if (url == null || prefix == null || prefix.isEmpty()) {
            return url;
        }
        if (!url.startsWith("/") || url.startsWith("//") || url.startsWith(prefix + "/") || url.equals(prefix)) {
            return url;
        }
        return prefix + url;
    }

    /** Rewrites a {@code Location} header so redirects stay inside the mounted sub-path. */
    public static String rewriteLocation(String location, String prefix) {
        return apply(location, prefix);
    }

    /**
     * Rewrites a finished HTML document: root-relative attribute URLs gain the
     * prefix, and a small shim is installed so scripts that build request URLs
     * at runtime ({@code fetch}, {@code XMLHttpRequest}, {@code EventSource})
     * reach the right place too.
     */
    public static String rewriteHtml(String html, String prefix) {
        if (html == null || prefix == null || prefix.isEmpty()) {
            return html;
        }
        String replacement = "$1$2" + Matcher.quoteReplacement(prefix) + "/";
        String rewritten = ATTRIBUTE.matcher(html).replaceAll(replacement);
        rewritten = SCRIPT_NAVIGATION.matcher(rewritten).replaceAll(replacement);
        return injectRuntimeShim(rewritten, prefix);
    }

    /** True when a response body should be treated as HTML for rewriting purposes. */
    public static boolean isHtml(String contentType) {
        return contentType == null || contentType.toLowerCase(Locale.ROOT).contains("text/html");
    }

    private static String injectRuntimeShim(String html, String prefix) {
        int head = html.indexOf("<head>");
        if (head < 0) {
            return html;
        }
        int insertAt = head + "<head>".length();
        return html.substring(0, insertAt) + runtimeShim(prefix) + html.substring(insertAt);
    }

    /**
     * Client-side counterpart of {@link #apply(String, String)}. Patching the
     * three network entry points covers every dynamically built URL without
     * touching the scripts themselves, and each patch is idempotent so a URL
     * that the server already rewrote is not prefixed twice.
     */
    static String runtimeShim(String prefix) {
        String literal = jsString(prefix);
        return "\n<script>(function(){var p=" + literal + ";"
                + "function fix(u){if(typeof u!=='string')return u;"
                + "if(u.charAt(0)!=='/'||u.charAt(1)==='/')return u;"
                + "if(u===p||u.indexOf(p+'/')===0)return u;return p+u;}"
                + "window.dashBasePath=p;window.dashUrl=fix;"
                + "var f=window.fetch;if(f)window.fetch=function(i,o){"
                + "return f.call(this,typeof i==='string'?fix(i):i,o);};"
                + "if(window.XMLHttpRequest){var x=XMLHttpRequest.prototype.open;"
                + "XMLHttpRequest.prototype.open=function(){var a=[].slice.call(arguments);"
                + "a[1]=fix(a[1]);return x.apply(this,a);};}"
                + "if(window.EventSource){var E=window.EventSource,S=function(u,c){return new E(fix(u),c);};"
                + "S.prototype=E.prototype;S.CONNECTING=E.CONNECTING;S.OPEN=E.OPEN;S.CLOSED=E.CLOSED;"
                + "window.EventSource=S;}"
                + "})();</script>\n";
    }

    private static String jsString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
