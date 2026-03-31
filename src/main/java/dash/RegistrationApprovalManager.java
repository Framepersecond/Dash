package dash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps pending registration requests until MAIN_ADMIN confirms in the web UI.
 */
public class RegistrationApprovalManager {

    private static final long EXPIRY_MS = 10 * 60 * 1000L;
    private final Map<String, PendingRegistration> pending = new ConcurrentHashMap<>();

    public String createPending(RegistrationManager.RegistrationCode regCode, String username, String password, String ip) {
        cleanupExpired();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        PendingRegistration req = new PendingRegistration(
                id,
                username,
                password,
                regCode.playerName(),
                regCode.playerUuid(),
                regCode.role() == null ? "MODERATOR" : regCode.role(),
                regCode.permissions() == null ? List.of() : List.copyOf(regCode.permissions()),
                ip,
                System.currentTimeMillis());
        pending.put(id, req);
        return id;
    }

    public PendingRegistration consume(String id) {
        cleanupExpired();
        if (id == null || id.isBlank()) {
            return null;
        }
        return pending.remove(id.trim().toUpperCase());
    }

    public boolean deny(String id) {
        cleanupExpired();
        if (id == null || id.isBlank()) {
            return false;
        }
        return pending.remove(id.trim().toUpperCase()) != null;
    }

    public List<PendingRegistration> listPending() {
        cleanupExpired();
        List<PendingRegistration> list = new ArrayList<>(pending.values());
        list.sort(Comparator.comparingLong(PendingRegistration::createdAt).reversed());
        return list;
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> (now - e.getValue().createdAt()) > EXPIRY_MS);
    }

    public record PendingRegistration(
            String id,
            String username,
            String password,
            String linkedPlayer,
            String linkedUuid,
            String role,
            List<String> permissions,
            String requestedFromIp,
            long createdAt) {
    }
}

