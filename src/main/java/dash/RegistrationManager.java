package dash;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RegistrationManager {

    private static final long CODE_EXPIRY_MS = 5 * 60 * 1000;
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 8;

    private final Map<String, RegistrationCode> pendingCodes = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public String generateCode(String playerUuid, String playerName) {
        cleanExpiredCodes();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        String code = sb.toString();

        pendingCodes.put(code, new RegistrationCode(playerUuid, playerName, System.currentTimeMillis()));
        return code;
    }

    public RegistrationCode validateAndConsume(String code) {
        cleanExpiredCodes();

        RegistrationCode regCode = pendingCodes.remove(code.toUpperCase());
        if (regCode == null) {
            return null;
        }

        if (System.currentTimeMillis() - regCode.createdAt() > CODE_EXPIRY_MS) {
            return null;
        }

        return regCode;
    }

    public boolean isValidCode(String code) {
        cleanExpiredCodes();
        RegistrationCode regCode = pendingCodes.get(code.toUpperCase());
        if (regCode == null)
            return false;
        return System.currentTimeMillis() - regCode.createdAt() <= CODE_EXPIRY_MS;
    }

    private void cleanExpiredCodes() {
        long now = System.currentTimeMillis();
        pendingCodes.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > CODE_EXPIRY_MS);
    }

    public record RegistrationCode(String playerUuid, String playerName, long createdAt) {
    }
}
