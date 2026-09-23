package dash.api;

/**
 * One performance sample.
 *
 * @param timestampEpochMillis when the sample was taken
 * @param tps                  ticks per second, clamped to at most 20.0
 * @param mspt                 milliseconds per tick
 * @param heapUsedBytes        JVM heap in use
 * @param heapMaxBytes         JVM heap ceiling
 * @param cpuLoad              process CPU load in {@code [0.0, 1.0]}, or -1.0 when unavailable
 * @param loadedChunks         chunks loaded across all worlds
 * @param entityCount          entities across all worlds
 */
public record PerformanceSnapshot(
        long timestampEpochMillis,
        double tps,
        double mspt,
        long heapUsedBytes,
        long heapMaxBytes,
        double cpuLoad,
        int loadedChunks,
        int entityCount) {
}
