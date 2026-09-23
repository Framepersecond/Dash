package dash.api;

/**
 * The handful of configuration values the API layer needs.
 *
 * <p>Dash reads them from a Bukkit {@code FileConfiguration}, FabricDash and
 * ForgeDash from their own {@code FabricConfig}. Narrowing that to this
 * interface keeps every other class in {@code dash.api} byte-identical across
 * the three builds.
 */
public interface ApiSettings {

    /**
     * Master switch. Defaults to {@code false}: the API is opt-in, so an
     * upgrade never silently opens a new listener on an existing server.
     */
    boolean enabled();

    /** Requests allowed per minute, per key and per address. */
    int rateLimitPerMinute();

    /** Whether the server is reachable over HTTPS, for response hardening. */
    boolean https();
}
