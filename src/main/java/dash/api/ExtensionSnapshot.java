package dash.api;

import java.util.List;

/**
 * A plugin (Bukkit) or mod (Fabric/NeoForge) loaded alongside Dash.
 *
 * <p>Useful for integrations that need to detect whether their counterpart is
 * present and which version is running. File paths are not exposed.
 *
 * @param id      stable identifier, e.g. {@code "dash"}
 * @param name    human-readable name
 * @param version version string, or {@code "unknown"}
 * @param enabled whether the extension is currently active
 * @param authors declared authors; empty when the platform does not report any
 */
public record ExtensionSnapshot(
        String id,
        String name,
        String version,
        boolean enabled,
        List<String> authors) {

    /** Defensive copy so the record really is an immutable snapshot. */
    public ExtensionSnapshot {
        authors = authors == null ? List.of() : List.copyOf(authors);
    }
}
