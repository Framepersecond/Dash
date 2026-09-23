package dash.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The public, <strong>read-only</strong> Dash API.
 *
 * <p>This is the contract other plugins and mods compile against. It is
 * deliberately platform-neutral: the same interface is implemented on Bukkit
 * (Dash), Fabric (FabricDash) and NeoForge (ForgeDash), so an integration
 * written once works on all three.
 *
 * <h2>Read-only guarantee</h2>
 * Every method is a pure observation. Implementations must never mutate server
 * state, never execute commands, and never expose credentials, secrets, world
 * seeds or player IP addresses. Returned collections are immutable snapshots;
 * mutating them throws, and they do not change under the caller afterwards.
 *
 * <h2>Obtaining an instance</h2>
 * <pre>{@code
 * // Works on every platform:
 * DashReadApi api = DashApiProvider.get().orElse(null);
 * if (api == null) {
 *     // Dash is absent or still starting -- degrade gracefully.
 *     return;
 * }
 *
 * // On Bukkit the service is additionally registered with the ServicesManager:
 * RegisteredServiceProvider<DashReadApi> rsp =
 *         Bukkit.getServicesManager().getRegistration(DashReadApi.class);
 * }</pre>
 *
 * <h2>Threading</h2>
 * Implementations are safe to call from any thread. Values that can only be
 * read on the server thread are served from a short-lived cache rather than by
 * blocking the caller, so a call never stalls the main loop.
 */
public interface DashReadApi {

    /** Major version of this API surface. Incremented only on breaking changes. */
    String API_VERSION = "1";

    /** @return {@link #API_VERSION}, so callers can guard against future majors. */
    String apiVersion();

    /** @return identity, software and player-count information about the server. */
    ServerSnapshot server();

    /** @return the most recent performance sample. */
    PerformanceSnapshot performance();

    /**
     * @param maxPoints upper bound on returned samples; values below 1 yield an
     *                  empty list, values above the retained history return all
     *                  of it.
     * @return performance samples, oldest first.
     */
    List<PerformanceSnapshot> performanceHistory(int maxPoints);

    /** @return every currently online player. Never contains IP addresses. */
    List<PlayerSnapshot> players();

    /** @return the player with this unique id, online or known offline. */
    Optional<PlayerSnapshot> player(UUID uuid);

    /** @return the player with this exact name, case-insensitive. */
    Optional<PlayerSnapshot> playerByName(String name);

    /** @return every loaded world. Never contains world seeds. */
    List<WorldSnapshot> worlds();

    /**
     * @return the plugins (Bukkit) or mods (Fabric/NeoForge) loaded alongside
     *         Dash, so integrations can detect their own presence and version.
     */
    List<ExtensionSnapshot> extensions();
}
