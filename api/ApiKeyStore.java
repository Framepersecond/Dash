package dash.api;

import dash.bridge.BridgeSecurity;
import dash.security.FilePermissions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Issues, stores and verifies read-only API keys.
 *
 * <h2>Token shape</h2>
 * <pre>dash_ak_&lt;id&gt;.&lt;secret&gt;</pre>
 * The id half is public and indexes the store; the secret half is 256 bits from
 * {@link SecureRandom}. Splitting the two means verification is an O(1) lookup
 * followed by a single constant-time comparison, instead of comparing against
 * every stored key in turn.
 *
 * <h2>What is persisted</h2>
 * Only {@code SHA-256(secret)}. The plaintext is handed back exactly once, by
 * {@link #issue}, and cannot be recovered from the file. The file itself is
 * written with owner-only permissions and replaced atomically.
 */
public final class ApiKeyStore {

    /** Prefix that makes a leaked key recognisable in logs and secret scanners. */
    public static final String TOKEN_PREFIX = "dash_ak_";

    private static final String HEADER = "# Dash read-only API keys (v1). "
            + "Secrets are stored as SHA-256 hashes and cannot be recovered.";
    private static final int ID_BYTES = 9;
    private static final int SECRET_BYTES = 32;
    private static final int MAX_KEYS = 256;
    private static final int MAX_LABEL_LENGTH = 96;

    private final Path file;
    private final SecureRandom random = new SecureRandom();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, ApiKey> keysById = new LinkedHashMap<>();

    public ApiKeyStore(Path file) {
        this.file = file;
        load();
    }

    /** The plaintext token, available only at the moment of issue. */
    public record IssuedKey(ApiKey key, String token) {
    }

    /**
     * Creates a new key.
     *
     * @param label   free-text description; trimmed and length-capped
     * @param scopes  what the key may read; an empty set yields an unusable key,
     *                so callers should reject that before calling
     * @param ttlDays days until expiry, or {@code 0} for a non-expiring key
     * @return the stored key plus the one-time plaintext token
     */
    public IssuedKey issue(String label, Set<ApiScope> scopes, int ttlDays) throws IOException {
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("at least one scope is required");
        }
        lock.writeLock().lock();
        try {
            if (keysById.size() >= MAX_KEYS) {
                throw new IllegalStateException("API key limit reached (" + MAX_KEYS + ")");
            }
            String id = randomToken(ID_BYTES);
            while (keysById.containsKey(id)) {
                id = randomToken(ID_BYTES);
            }
            String secret = randomToken(SECRET_BYTES);
            long now = System.currentTimeMillis();
            long expiresAt = ttlDays > 0 ? now + (long) ttlDays * 86_400_000L : 0L;

            ApiKey key = new ApiKey(id, BridgeSecurity.sha256Hex(secret), sanitizeLabel(label),
                    scopes, now, expiresAt, false, 0L);
            keysById.put(id, key);
            persist();
            return new IssuedKey(key, TOKEN_PREFIX + id + "." + secret);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Verifies a bearer token.
     *
     * <p>Returns empty for every failure mode -- malformed token, unknown id,
     * wrong secret, revoked, expired -- so a caller cannot distinguish them and
     * use the API as an oracle for which key ids exist.
     *
     * @param authorizationHeader raw {@code Authorization} header value
     */
    public Optional<ApiKey> authenticate(String authorizationHeader) {
        String token = BridgeSecurity.extractBearerToken(authorizationHeader);
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        String body = token.substring(TOKEN_PREFIX.length());
        int separator = body.indexOf('.');
        if (separator <= 0 || separator >= body.length() - 1) {
            return Optional.empty();
        }
        String id = body.substring(0, separator);
        String secret = body.substring(separator + 1);

        ApiKey candidate;
        lock.readLock().lock();
        try {
            candidate = keysById.get(id);
        } finally {
            lock.readLock().unlock();
        }
        if (candidate == null) {
            // Still hash, so an unknown id costs the same as a known one.
            BridgeSecurity.sha256Hex(secret);
            return Optional.empty();
        }

        String providedHash = BridgeSecurity.sha256Hex(secret);
        if (!BridgeSecurity.equalsConstantTime(providedHash, candidate.secretHashHex())) {
            return Optional.empty();
        }
        if (!candidate.isUsable(System.currentTimeMillis())) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    /** Records a successful use. Failures here never block the request. */
    public void touch(String keyId) {
        lock.writeLock().lock();
        try {
            ApiKey existing = keysById.get(keyId);
            if (existing == null) {
                return;
            }
            keysById.put(keyId, existing.withLastUsed(System.currentTimeMillis()));
            persist();
        } catch (IOException ignored) {
            // Usage tracking is best-effort; never fail a read because of it.
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean revoke(String keyId) throws IOException {
        lock.writeLock().lock();
        try {
            ApiKey existing = keysById.get(keyId);
            if (existing == null || existing.revoked()) {
                return false;
            }
            keysById.put(keyId, existing.revokedCopy());
            persist();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean delete(String keyId) throws IOException {
        lock.writeLock().lock();
        try {
            if (keysById.remove(keyId) == null) {
                return false;
            }
            persist();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** @return every stored key, newest first. Contains hashes, never secrets. */
    public List<ApiKey> list() {
        lock.readLock().lock();
        try {
            List<ApiKey> all = new ArrayList<>(keysById.values());
            all.sort((a, b) -> Long.compare(b.createdAtEpochMillis(), a.createdAtEpochMillis()));
            return List.copyOf(all);
        } finally {
            lock.readLock().unlock();
        }
    }

    public int activeCount() {
        long now = System.currentTimeMillis();
        lock.readLock().lock();
        try {
            return (int) keysById.values().stream().filter(key -> key.isUsable(now)).count();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    //
    // A tab-separated line per key rather than JSON: every field except the
    // label is base64url, hex, an enum id or a number, so the format has no
    // parser edge cases to get wrong. The label is escaped.
    // ------------------------------------------------------------------

    private void load() {
        lock.writeLock().lock();
        try {
            keysById.clear();
            if (!Files.isRegularFile(file)) {
                return;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 8) {
                    continue;
                }
                try {
                    keysById.put(parts[0], new ApiKey(
                            parts[0],
                            BridgeSecurity.normalizeHex(parts[1]),
                            unescape(parts[7]),
                            ApiScope.parse(parts[2]),
                            Long.parseLong(parts[3]),
                            Long.parseLong(parts[4]),
                            Boolean.parseBoolean(parts[5]),
                            Long.parseLong(parts[6])));
                } catch (RuntimeException ignored) {
                    // Skip an unreadable row rather than refusing to start.
                }
            }
        } catch (IOException ignored) {
            // An unreadable store means no keys, which fails closed.
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void persist() throws IOException {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (ApiKey key : keysById.values()) {
            sb.append(key.id()).append('\t')
              .append(key.secretHashHex()).append('\t')
              .append(ApiScope.join(key.scopes())).append('\t')
              .append(key.createdAtEpochMillis()).append('\t')
              .append(key.expiresAtEpochMillis()).append('\t')
              .append(key.revoked()).append('\t')
              .append(key.lastUsedAtEpochMillis()).append('\t')
              .append(escape(key.label())).append('\n');
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, sb.toString(), StandardCharsets.UTF_8);
        FilePermissions.ownerReadWrite(temp);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        FilePermissions.ownerReadWrite(file);
    }

    private String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static String sanitizeLabel(String label) {
        if (label == null || label.isBlank()) {
            return "unnamed";
        }
        String cleaned = label.trim().replaceAll("[\\p{Cntrl}]", " ");
        if (cleaned.length() > MAX_LABEL_LENGTH) {
            cleaned = cleaned.substring(0, MAX_LABEL_LENGTH);
        }
        return cleaned.isBlank() ? "unnamed" : cleaned;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unescape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                sb.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case '\\' -> sb.append('\\');
                default -> sb.append(next);
            }
        }
        return sb.toString();
    }

    /** @return a short, non-secret identifier safe to write into audit logs. */
    public static String auditName(ApiKey key) {
        return key == null ? "unknown" : key.id().toLowerCase(Locale.ROOT);
    }
}
