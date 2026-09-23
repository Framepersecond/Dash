package dash.security;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LoginRateLimiter {
    private static final int MAX_TRACKED_KEYS = 4096;

    private final int maxFailures;
    private final long windowMillis;
    private final long lockMillis;
    private final Map<String, AttemptState> attempts = new ConcurrentHashMap<>();

    public LoginRateLimiter(int maxFailures, Duration window, Duration lockDuration) {
        if (maxFailures < 1 || window == null || window.isNegative() || window.isZero()
                || lockDuration == null || lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalArgumentException("positive limits are required");
        }
        this.maxFailures = maxFailures;
        this.windowMillis = window.toMillis();
        this.lockMillis = lockDuration.toMillis();
    }

    public boolean isAllowed(String key) {
        long now = System.currentTimeMillis();
        AttemptState state = attempts.get(normalize(key));
        if (state == null) {
            return true;
        }
        if (state.lockedUntil() > now) {
            return false;
        }
        if (now - state.windowStartedAt() > windowMillis) {
            attempts.remove(normalize(key), state);
        }
        return true;
    }

    public void recordFailure(String key) {
        long now = System.currentTimeMillis();
        String normalized = normalize(key);
        attempts.compute(normalized, (ignored, state) -> {
            if (state == null || now - state.windowStartedAt() > windowMillis) {
                return new AttemptState(1, now, 0L, now);
            }
            int failures = state.failures() + 1;
            long lockedUntil = failures >= maxFailures ? now + lockMillis : state.lockedUntil();
            return new AttemptState(failures, state.windowStartedAt(), lockedUntil, now);
        });
        if (attempts.size() > MAX_TRACKED_KEYS) {
            attempts.entrySet().removeIf(entry -> now - entry.getValue().lastAttemptAt() > windowMillis + lockMillis);
        }
    }

    public void recordSuccess(String key) {
        attempts.remove(normalize(key));
    }

    public long retryAfterSeconds(String key) {
        AttemptState state = attempts.get(normalize(key));
        if (state == null) {
            return 0L;
        }
        return Math.max(0L, (state.lockedUntil() - System.currentTimeMillis() + 999L) / 1000L);
    }

    private String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private record AttemptState(int failures, long windowStartedAt, long lockedUntil, long lastAttemptAt) {
    }
}

