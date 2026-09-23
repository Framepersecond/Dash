package dash.api;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The permissions an API key can carry.
 *
 * <p>Every scope is read-only; there is deliberately no write scope, and no
 * way to express one. Keys are least-privilege by default: a key is created
 * with an explicit scope set, and anything not listed is denied.
 */
public enum ApiScope {

    SERVER("server:read"),
    PERFORMANCE("performance:read"),
    PLAYERS("players:read"),
    WORLDS("worlds:read"),
    EXTENSIONS("extensions:read");

    private final String id;

    ApiScope(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static ApiScope fromId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ApiScope scope : values()) {
            if (scope.id.equals(normalized)) {
                return scope;
            }
        }
        return null;
    }

    /**
     * Parses a comma- or space-separated scope list.
     *
     * <p>Unknown entries are dropped rather than silently widening access. The
     * literal {@code "*"} expands to every read scope -- it can never grant
     * more than reading, because no other kind of scope exists.
     */
    public static Set<ApiScope> parse(String raw) {
        Set<ApiScope> parsed = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        for (String part : raw.split("[,\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            if ("*".equals(part.trim())) {
                parsed.addAll(java.util.Arrays.asList(values()));
                continue;
            }
            ApiScope scope = fromId(part);
            if (scope != null) {
                parsed.add(scope);
            }
        }
        return Set.copyOf(parsed);
    }

    public static String join(Set<ApiScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ApiScope scope : values()) {
            if (!scopes.contains(scope)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(scope.id);
        }
        return sb.toString();
    }
}
