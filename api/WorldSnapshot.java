package dash.api;

/**
 * A loaded world.
 *
 * <p>The world seed is intentionally omitted: it would let anyone holding a
 * read key locate every structure on the server.
 *
 * @param name         world name
 * @param environment  e.g. {@code "NORMAL"}, {@code "NETHER"}, {@code "THE_END"}
 * @param loadedChunks chunks currently loaded in this world
 * @param entityCount  entities currently in this world
 * @param playerCount  players currently in this world
 * @param difficulty   configured difficulty, e.g. {@code "HARD"}
 */
public record WorldSnapshot(
        String name,
        String environment,
        int loadedChunks,
        int entityCount,
        int playerCount,
        String difficulty) {
}
