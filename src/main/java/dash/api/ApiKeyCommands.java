package dash.api;

import dash.security.FilePermissions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * Platform-neutral key administration, shared by the Bukkit, Fabric and
 * NeoForge command handlers.
 *
 * <p>Each method returns lines of plain text for the caller to print, so the
 * three command implementations differ only in how they parse arguments and
 * emit messages.
 *
 * <h2>Why the token goes to a file</h2>
 * A newly minted key is written to an owner-only file and the operator is told
 * the path, rather than the secret being echoed to chat or console. Console
 * output lands in {@code latest.log}, which routinely gets posted in support
 * threads -- exactly how credentials leak. This mirrors the existing
 * {@code setup.token} handoff the panel already uses.
 */
public final class ApiKeyCommands {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private ApiKeyCommands() {
    }

    /**
     * Issues a key and writes the token to {@code api-key-<id>.txt}.
     *
     * @param scopeSpec comma-separated scope ids, or {@code "*"} for all reads
     * @param days      lifetime in days, or 0 for a key that does not expire
     */
    public static List<String> create(ApiKeyStore store, Path outputDirectory,
                                      String label, String scopeSpec, int days) {
        Set<ApiScope> scopes = ApiScope.parse(scopeSpec == null || scopeSpec.isBlank() ? "*" : scopeSpec);
        if (scopes.isEmpty()) {
            return List.of("No valid scopes given. Available: " + availableScopes());
        }
        try {
            ApiKeyStore.IssuedKey issued = store.issue(label, scopes, Math.max(0, days));
            Path target = outputDirectory.resolve("api-key-" + issued.key().id() + ".txt");
            Files.createDirectories(outputDirectory);
            Files.writeString(target,
                    "Dash read-only API key\n"
                            + "Label:   " + issued.key().label() + "\n"
                            + "Id:      " + issued.key().id() + "\n"
                            + "Scopes:  " + ApiScope.join(issued.key().scopes()) + "\n"
                            + "Expires: " + expiry(issued.key()) + "\n\n"
                            + issued.token() + "\n\n"
                            + "Send it as:  Authorization: Bearer <token>\n"
                            + "This file is the only copy. Store the token somewhere safe, then delete it.\n",
                    StandardCharsets.UTF_8);
            FilePermissions.ownerReadWrite(target);
            return List.of(
                    "Created read-only API key " + issued.key().id()
                            + " (" + ApiScope.join(issued.key().scopes()) + ")",
                    "Token written to " + target.toAbsolutePath(),
                    "It is shown only there. Copy it, then delete the file.");
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return List.of("Could not create key: " + rejected.getMessage());
        } catch (IOException failed) {
            return List.of("Could not write the key file: " + failed.getMessage());
        }
    }

    public static List<String> list(ApiKeyStore store) {
        List<ApiKey> keys = store.list();
        if (keys.isEmpty()) {
            return List.of("No API keys exist yet.");
        }
        long now = System.currentTimeMillis();
        List<String> lines = new java.util.ArrayList<>();
        lines.add("API keys (" + keys.size() + "):");
        for (ApiKey key : keys) {
            String state = key.revoked() ? "revoked"
                    : key.isExpired(now) ? "expired" : "active";
            lines.add("  " + key.id() + "  [" + state + "]  " + key.label()
                    + "  scopes=" + ApiScope.join(key.scopes())
                    + "  created=" + STAMP.format(Instant.ofEpochMilli(key.createdAtEpochMillis()))
                    + "  expires=" + expiry(key)
                    + "  lastUsed=" + (key.lastUsedAtEpochMillis() == 0 ? "never"
                            : STAMP.format(Instant.ofEpochMilli(key.lastUsedAtEpochMillis()))));
        }
        return List.copyOf(lines);
    }

    public static List<String> revoke(ApiKeyStore store, String keyId) {
        if (keyId == null || keyId.isBlank()) {
            return List.of("Usage: api revoke <key-id>");
        }
        try {
            return store.revoke(keyId.trim())
                    ? List.of("Revoked API key " + keyId.trim() + ".")
                    : List.of("No active key with id " + keyId.trim() + ".");
        } catch (IOException failed) {
            return List.of("Could not update the key store: " + failed.getMessage());
        }
    }

    public static String availableScopes() {
        StringBuilder sb = new StringBuilder();
        for (ApiScope scope : ApiScope.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(scope.id());
        }
        return sb + ", or * for all";
    }

    public static List<String> usage() {
        return List.of(
                "Usage:",
                "  /dash api create <label> [scopes] [days]  - issue a key (scopes default to *)",
                "  /dash api list                            - show every key",
                "  /dash api revoke <key-id>                 - withdraw a key",
                "Scopes: " + availableScopes(),
                "The API stays off until api.enabled is true in config.yml.");
    }

    private static String expiry(ApiKey key) {
        return key.expiresAtEpochMillis() == 0
                ? "never"
                : STAMP.format(Instant.ofEpochMilli(key.expiresAtEpochMillis()));
    }
}
