package dash.bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class BridgeSecurity {

    private BridgeSecurity() {
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static boolean equalsConstantTime(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }

    public static boolean equalsConstantTime(byte[] left, byte[] right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left, right);
    }

    public static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String trimmed = authorizationHeader.trim();
        if (!trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = trimmed.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    public static boolean bearerMatchesSecret(String authorizationHeader, String sharedSecret) {
        String providedToken = extractBearerToken(authorizationHeader);
        if (providedToken == null || sharedSecret == null || sharedSecret.isBlank()) {
            return false;
        }
        return equalsConstantTime(
                providedToken.getBytes(StandardCharsets.UTF_8),
                sharedSecret.trim().getBytes(StandardCharsets.UTF_8));
    }

    public static String normalizeHex(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

