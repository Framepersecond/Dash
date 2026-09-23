package dash.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordHasher {
    private static final String ALGORITHM_ID = "pbkdf2-sha256";
    private static final String JCA_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 600_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_BYTES = 16;

    private PasswordHasher() {
    }

    public static String hash(String password) {
        if (password == null) {
            throw new IllegalArgumentException("password is required");
        }
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return ALGORITHM_ID + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    public static String hash(String password, byte[] salt) {
        if (password == null || salt == null || salt.length < SALT_BYTES) {
            throw new IllegalArgumentException("password and a 16-byte salt are required");
        }
        return ALGORITHM_ID + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    public static boolean verify(String password, String storedHash) {
        if (password == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        if (storedHash.startsWith(ALGORITHM_ID + "$")) {
            String[] parts = storedHash.split("\\$", -1);
            if (parts.length != 4) {
                return false;
            }
            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                return iterations > 0 && salt.length >= SALT_BYTES
                        && MessageDigest.isEqual(expected, derive(password, salt, iterations));
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return verifyLegacyEmbedded(password, storedHash);
    }

    public static boolean verify(String password, byte[] salt, String storedHash) {
        if (password == null || salt == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        if (storedHash.startsWith(ALGORITHM_ID + "$")) {
            String[] parts = storedHash.split("\\$", -1);
            if (parts.length != 3) {
                return false;
            }
            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] expected = Base64.getDecoder().decode(parts[2]);
                return iterations > 0
                        && MessageDigest.isEqual(expected, derive(password, salt, iterations));
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            byte[] expected = Base64.getDecoder().decode(storedHash);
            return MessageDigest.isEqual(expected, digest.digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean needsUpgrade(String storedHash) {
        if (storedHash == null || !storedHash.startsWith(ALGORITHM_ID + "$")) {
            return true;
        }
        String[] parts = storedHash.split("\\$", -1);
        try {
            return parts.length < 2 || Integer.parseInt(parts[1]) < ITERATIONS;
        } catch (NumberFormatException ignored) {
            return true;
        }
    }

    private static boolean verifyLegacyEmbedded(String password, String storedHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] expected;
            if (storedHash.startsWith("$") && storedHash.indexOf('$', 1) > 1) {
                int separator = storedHash.indexOf('$', 1);
                byte[] salt = Base64.getDecoder().decode(storedHash.substring(1, separator));
                expected = Base64.getDecoder().decode(storedHash.substring(separator + 1));
                digest.update(salt);
            } else {
                expected = Base64.getDecoder().decode(storedHash);
            }
            return MessageDigest.isEqual(expected, digest.digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec keySpec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(JCA_ALGORITHM).generateSecret(keySpec).getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException("PBKDF2-HMAC-SHA256 is unavailable", ex);
        } finally {
            keySpec.clearPassword();
        }
    }
}

