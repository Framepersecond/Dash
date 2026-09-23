package dash.api;

import java.util.Optional;

/**
 * Platform-neutral access point for {@link DashReadApi}.
 *
 * <p>Bukkit has a {@code ServicesManager}, Fabric and NeoForge do not. This
 * holder gives integrations one lookup that behaves identically everywhere:
 *
 * <pre>{@code
 * DashApiProvider.get().ifPresent(api -> {
 *     ServerSnapshot server = api.server();
 * });
 * }</pre>
 *
 * <p>{@link #get()} returns empty while Dash is absent, still starting, or
 * already shut down, so callers must always handle that case rather than
 * assuming the API is there.
 */
public final class DashApiProvider {

    private static volatile DashReadApi instance;

    private DashApiProvider() {
    }

    /** @return the live API, or empty when Dash is not currently serving one. */
    public static Optional<DashReadApi> get() {
        return Optional.ofNullable(instance);
    }

    /** @return true when an API instance is currently available. */
    public static boolean isAvailable() {
        return instance != null;
    }

    /**
     * Publishes the implementation. Called by Dash during startup.
     *
     * @apiNote Internal wiring. Third-party code should only ever call
     *          {@link #get()}; this method exists because the implementation
     *          lives in a different package.
     */
    public static void install(DashReadApi api) {
        if (api == null) {
            throw new IllegalArgumentException("api must not be null");
        }
        instance = api;
    }

    /**
     * Withdraws the implementation on shutdown, so integrations stop seeing a
     * stale API bound to a server that no longer exists.
     *
     * @apiNote Internal wiring, as with {@link #install(DashReadApi)}.
     */
    public static void uninstall() {
        instance = null;
    }
}
