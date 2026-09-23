package dash.api;

import java.util.Set;

/**
 * A stored API key.
 *
 * <p>Only the <em>hash</em> of the secret half is kept. The plaintext token is
 * returned once, at creation, and is unrecoverable afterwards -- so a leaked
 * copy of the key file does not hand an attacker working credentials.
 *
 * @param id            public identifier, also the lookup key; not secret
 * @param secretHashHex SHA-256 of the secret half, lowercase hex
 * @param label         operator-supplied description, for the audit trail
 * @param scopes        exactly what this key may read
 * @param createdAtEpochMillis when the key was issued
 * @param expiresAtEpochMillis when it stops working; {@code 0} means never
 * @param revoked       whether it was withdrawn by an operator
 * @param lastUsedAtEpochMillis last successful authentication; {@code 0} if unused
 */
public record ApiKey(
        String id,
        String secretHashHex,
        String label,
        Set<ApiScope> scopes,
        long createdAtEpochMillis,
        long expiresAtEpochMillis,
        boolean revoked,
        long lastUsedAtEpochMillis) {

    public ApiKey {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /** @return true when this key is past its expiry. {@code 0} never expires. */
    public boolean isExpired(long nowEpochMillis) {
        return expiresAtEpochMillis > 0 && nowEpochMillis >= expiresAtEpochMillis;
    }

    /** @return true when the key may currently authenticate at all. */
    public boolean isUsable(long nowEpochMillis) {
        return !revoked && !isExpired(nowEpochMillis);
    }

    public boolean hasScope(ApiScope scope) {
        return scopes.contains(scope);
    }

    public ApiKey withLastUsed(long nowEpochMillis) {
        return new ApiKey(id, secretHashHex, label, scopes,
                createdAtEpochMillis, expiresAtEpochMillis, revoked, nowEpochMillis);
    }

    public ApiKey revokedCopy() {
        return new ApiKey(id, secretHashHex, label, scopes,
                createdAtEpochMillis, expiresAtEpochMillis, true, lastUsedAtEpochMillis);
    }
}
