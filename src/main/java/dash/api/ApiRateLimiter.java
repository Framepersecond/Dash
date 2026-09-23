package dash.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fixed-window request limiter, applied per API key and per client address.
 *
 * <p>Two independent buckets matter here: the per-key limit stops one
 * integration from hammering the server, and the per-address limit stops an
 * unauthenticated flood from turning key verification -- which hashes -- into a
 * cheap CPU amplifier.
 */
public final class ApiRateLimiter {

    private static final int MAX_TRACKED = 4096;
    private static final long WINDOW_MILLIS = 60_000L;

    private final int maxPerWindow;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public ApiRateLimiter(int maxPerWindow) {
        this.maxPerWindow = Math.max(1, maxPerWindow);
    }

    /** @return true when this request fits inside the current window. */
    public boolean tryAcquire(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Window updated = windows.compute(key, (ignored, window) -> {
            if (window == null || now - window.startedAt >= WINDOW_MILLIS) {
                return new Window(now, 1);
            }
            return new Window(window.startedAt, window.count + 1);
        });
        if (windows.size() > MAX_TRACKED) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_MILLIS);
        }
        return updated.count <= maxPerWindow;
    }

    /** @return seconds until the caller's window resets, for {@code Retry-After}. */
    public long retryAfterSeconds(String key) {
        Window window = windows.get(key);
        if (window == null) {
            return 1L;
        }
        long remaining = WINDOW_MILLIS - (System.currentTimeMillis() - window.startedAt);
        return Math.max(1L, (remaining + 999L) / 1000L);
    }

    private record Window(long startedAt, int count) {
    }
}
