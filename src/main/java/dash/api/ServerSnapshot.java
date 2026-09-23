package dash.api;

/**
 * Identity and headline state of the server.
 *
 * <p>Deliberately excludes anything that would help an attacker: no bind
 * address, no file-system paths, no world seed, no operator list.
 *
 * @param platform         {@code "bukkit"}, {@code "fabric"} or {@code "neoforge"}
 * @param softwareName     server software, e.g. {@code "Paper"} or {@code "Fabric"}
 * @param softwareVersion  version string of that software
 * @param minecraftVersion Minecraft version, e.g. {@code "1.21.8"}
 * @param dashVersion      version of the Dash plugin/mod serving this data
 * @param onlinePlayers    players connected right now
 * @param maxPlayers       configured player slots
 * @param uptimeMillis     milliseconds since Dash started tracking this server
 * @param whitelistEnabled whether the whitelist is active
 */
public record ServerSnapshot(
        String platform,
        String softwareName,
        String softwareVersion,
        String minecraftVersion,
        String dashVersion,
        int onlinePlayers,
        int maxPlayers,
        long uptimeMillis,
        boolean whitelistEnabled) {
}
