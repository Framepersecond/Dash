package dash.api;

import java.util.UUID;

/**
 * A player as Dash sees them.
 *
 * <p><strong>No IP address, no session token and no login history is exposed
 * here, by design.</strong> Those are personal data that a read API handed to
 * third-party plugins has no business carrying.
 *
 * @param uuid                 the player's unique id
 * @param name                 last known name
 * @param online               whether they are connected right now
 * @param world                world name when online, otherwise {@code null}
 * @param gameMode             e.g. {@code "SURVIVAL"}; {@code null} when offline
 * @param level                experience level when online, otherwise 0
 * @param health               current health when online, otherwise 0.0
 * @param maxHealth            maximum health when online, otherwise 0.0
 * @param operator             whether the player has operator status
 * @param firstSeenEpochMillis first login Dash recorded, or 0 when unknown
 * @param lastSeenEpochMillis  last login/logout Dash recorded, or 0 when unknown
 * @param playtimeMillis       total tracked playtime, or 0 when unknown
 */
public record PlayerSnapshot(
        UUID uuid,
        String name,
        boolean online,
        String world,
        String gameMode,
        int level,
        double health,
        double maxHealth,
        boolean operator,
        long firstSeenEpochMillis,
        long lastSeenEpochMillis,
        long playtimeMillis) {
}
