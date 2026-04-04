package dash;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class WebAuth {

    private final JavaPlugin plugin;
    private final File userFile;
    private YamlConfiguration config;

    public WebAuth(JavaPlugin plugin) {
        this.plugin = plugin;
        this.userFile = new File(plugin.getDataFolder(), "web-users.yml");
        reload();
    }

    public void reload() {
        if (!userFile.exists()) {
            try {
                userFile.getParentFile().mkdirs();
                userFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        config = YamlConfiguration.loadConfiguration(userFile);
        ensureDefaultRoles();
        ensureMainAdmin();
        normalizeBridgeFlagsOnLoad();
        save();
    }

    public boolean isRegistered() {
        if (config.contains("admin.hash") && config.contains("admin.salt")) {
            return true;
        }
        return config.isConfigurationSection("users") && !config.getConfigurationSection("users").getKeys(false).isEmpty();
    }

    public boolean isSetupRequired() {
        return !hasFullAdminAccount();
    }

    public boolean hasFullAdminAccount() {
        if (config.contains("admin.hash") && config.contains("admin.salt")) {
            return true;
        }
        if (!config.isConfigurationSection("users")) {
            return false;
        }
        for (String username : config.getConfigurationSection("users").getKeys(false)) {
            if (userHasPermission(username, "dash.web.users.manage") && userHasPermission(username, "dash.web.settings.write")) {
                return true;
            }
        }
        return false;
    }

    public boolean register(String username, String password) {
        return registerUser(username, password, null, null, "ADMIN", List.of());
    }

    public boolean registerWithCode(String code, String username, String password) {
        RegistrationManager regManager = Dash.getRegistrationManager();
        RegistrationManager.RegistrationCode regCode = regManager.validateAndConsume(code);

        if (regCode == null) {
            return false;
        }

        String role = regCode.role() != null ? regCode.role() : (isSetupRequired() ? "ADMIN" : "MODERATOR");
        return registerUser(username, password, regCode.playerName(), regCode.playerUuid(), role, regCode.permissions());
    }

    public boolean registerFirstAdminWithCode(String code, String username, String password) {
        if (!isSetupRequired()) {
            return false;
        }

        RegistrationManager regManager = Dash.getRegistrationManager();
        RegistrationManager.RegistrationCode regCode = regManager.validateAndConsume(code);
        if (regCode == null) {
            return false;
        }

        boolean saved = registerUser(username, password, regCode.playerName(), regCode.playerUuid(), "ADMIN", List.of());
        if (saved) {
            config.set("main-admin", username);
            save();
            WebActionLogger.log("SETUP_COMPLETE", "First admin '" + username + "' linked to " + regCode.playerName());
        }
        return saved;
    }

    public boolean registerInvitedUser(String code, String username, String password) {
        RegistrationManager regManager = Dash.getRegistrationManager();
        RegistrationManager.RegistrationCode regCode = regManager.validateAndConsume(code);
        if (regCode == null) {
            return false;
        }

        String role = regCode.role() != null ? regCode.role() : "MODERATOR";
        return registerUser(username, password, regCode.playerName(), regCode.playerUuid(), role, regCode.permissions());
    }

    public boolean registerApprovedPending(RegistrationApprovalManager.PendingRegistration pending) {
        if (pending == null) {
            return false;
        }
        return registerUser(
                pending.username(),
                pending.password(),
                pending.linkedPlayer(),
                pending.linkedUuid(),
                pending.role(),
                pending.permissions());
    }

    public BridgeSsoResult getOrCreateBridgeUserForSso(String username) {
        if (username == null || username.isBlank()) {
            return new BridgeSsoResult(false, false, null, null);
        }

        refreshConfigView();
        String normalizedUsername = username.trim();

        // Local owner/admin accounts must never be treated as bridge-pending users.
        if (isMainAdminInternal(normalizedUsername) || isLegacyAdminInternal(normalizedUsername)) {
            clearBridgeFlags(normalizedUsername);
            return new BridgeSsoResult(false, true, normalizedUsername, null);
        }

        if (!userExists(normalizedUsername)) {
            String approvalToken = UUID.randomUUID().toString();
            boolean created = registerBridgePendingUser(normalizedUsername, approvalToken);
            if (created) {
                // Re-read file-backed config and verify the pending bridge user really exists on disk.
                refreshConfigView();
                String userPath = "users." + normalizedUsername;
                boolean persisted = config.contains(userPath + ".hash")
                        && config.getBoolean(userPath + ".is_bridge_user", false)
                        && !config.getBoolean(userPath + ".bridge_approved", true);
                if (persisted) {
                    return new BridgeSsoResult(true, false, normalizedUsername, approvalToken);
                }
                return new BridgeSsoResult(false, false, normalizedUsername, null);
            }
            return new BridgeSsoResult(created, false, normalizedUsername, approvalToken);
        }

        String userPath = "users." + normalizedUsername;
        boolean isBridgeUser = config.getBoolean(userPath + ".is_bridge_user", false);
        if (!isBridgeUser) {
            return new BridgeSsoResult(false, true, normalizedUsername, null);
        }

        boolean approved = config.getBoolean(userPath + ".bridge_approved", false);
        String token = config.getString(userPath + ".approval_token", "");
        if (!approved && (token == null || token.isBlank())) {
            token = UUID.randomUUID().toString();
            config.set(userPath + ".approval_token", token);
            save();
        }
        return new BridgeSsoResult(false, approved, normalizedUsername, token);
    }

    public AuthResult approveBridgeUserSafe(String actor, String username, String role, List<String> extraPermissions) {
        refreshConfigView();
        if (!isMainAdmin(actor)) {
            return new AuthResult(false, "only_main_admin");
        }
        if (!userExists(username)) {
            return new AuthResult(false, "user_not_found");
        }
        String userPath = "users." + username;
        if (!config.getBoolean(userPath + ".is_bridge_user", false)) {
            return new AuthResult(false, "not_bridge_user");
        }

        String normalizedRole = normalizeRole(role);
        if (!roleExists(normalizedRole)) {
            normalizedRole = "MODERATOR";
        }

        LinkedHashSet<String> mergedPerms = new LinkedHashSet<>(config.getStringList(userPath + ".permissions"));
        if (extraPermissions != null) {
            for (String perm : extraPermissions) {
                if (perm != null && !perm.isBlank()) {
                    mergedPerms.add(perm.trim());
                }
            }
        }

        config.set(userPath + ".role", normalizedRole);
        config.set(userPath + ".permissions", new ArrayList<>(mergedPerms));
        persistBridgeState(username, true, true, null);
        config.set(userPath + ".bridge_approved_at", System.currentTimeMillis());

        if (save()) {
            return new AuthResult(true, "ok");
        }
        return new AuthResult(false, "save_failed");
    }

    public AuthResult denyBridgeUserSafe(String actor, String username) {
        refreshConfigView();
        if (!isMainAdmin(actor)) {
            return new AuthResult(false, "only_main_admin");
        }
        if (!userExists(username)) {
            return new AuthResult(false, "user_not_found");
        }
        String userPath = "users." + username;
        if (!config.getBoolean(userPath + ".is_bridge_user", false)) {
            return new AuthResult(false, "not_bridge_user");
        }
        if (config.getBoolean(userPath + ".bridge_approved", false)) {
            return new AuthResult(false, "already_approved");
        }

        config.set(userPath, null);
        return save() ? new AuthResult(true, "ok") : new AuthResult(false, "save_failed");
    }

    public AuthResult approveBridgeByToken(String token, boolean allow, String defaultRole) {
        refreshConfigView();
        if (token == null || token.isBlank()) {
            return new AuthResult(false, "invalid_token");
        }

        String username = findUserByApprovalToken(token);
        if (username == null) {
            return new AuthResult(false, "invalid_token");
        }

        if (!allow) {
            config.set("users." + username, null);
            return save() ? new AuthResult(true, "ok") : new AuthResult(false, "save_failed");
        }

        String role = normalizeRole(defaultRole);
        if (!roleExists(role)) {
            role = "MODERATOR";
        }

        String userPath = "users." + username;
        persistBridgeState(username, true, true, null);
        config.set(userPath + ".bridge_approved_at", System.currentTimeMillis());
        config.set(userPath + ".role", role);
        return save() ? new AuthResult(true, username) : new AuthResult(false, "save_failed");
    }

    public List<UserInfo> getPendingBridgeUsers() {
        refreshConfigView();
        List<UserInfo> pending = new ArrayList<>();
        if (!config.isConfigurationSection("users")) {
            return pending;
        }

        for (String username : config.getConfigurationSection("users").getKeys(false)) {
            String userPath = "users." + username;
            boolean isBridgeUser = config.getBoolean(userPath + ".is_bridge_user", false);
            boolean bridgeApproved = config.getBoolean(userPath + ".bridge_approved", false);
            if (!isBridgeUser || bridgeApproved) {
                continue;
            }

            String displayRole = isMainAdminInternal(username)
                    ? "MAIN_ADMIN"
                    : normalizeRole(config.getString(userPath + ".role", "MODERATOR"));
            pending.add(new UserInfo(
                    username,
                    displayRole,
                    config.getStringList(userPath + ".permissions"),
                    config.getString(userPath + ".linkedPlayer", ""),
                    isMainAdminInternal(username),
                    true,
                    false,
                    config.getString(userPath + ".approval_token", "")));
        }
        return pending;
    }

    private String findUserByApprovalToken(String token) {
        if (!config.isConfigurationSection("users")) {
            return null;
        }
        for (String username : config.getConfigurationSection("users").getKeys(false)) {
            String configuredToken = config.getString("users." + username + ".approval_token", "");
            if (configuredToken != null && configuredToken.equals(token)) {
                return username;
            }
        }
        return null;
    }

    private boolean registerBridgePendingUser(String username, String approvalToken) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltStr = Base64.getEncoder().encodeToString(salt);
        String randomPassword = UUID.randomUUID().toString() + UUID.randomUUID();
        String hash = hash(randomPassword, salt);

        String userPath = "users." + username;
        config.set(userPath + ".hash", hash);
        config.set(userPath + ".salt", saltStr);
        config.set(userPath + ".role", "MODERATOR");
        config.set(userPath + ".permissions", List.of());
        config.set(userPath + ".linkedPlayer", username);
        config.set(userPath + ".linkedUuid", "BRIDGE");
        config.set(userPath + ".is_bridge_user", true);
        config.set(userPath + ".bridge_approved", false);
        persistBridgeState(username, true, false, approvalToken);
        config.set(userPath + ".bridge_created_at", System.currentTimeMillis());

        boolean saved = save();
        if (saved) {
            WebActionLogger.log("BRIDGE_PENDING_CREATED", "user=" + username);
        }
        return saved;
    }

    private void persistBridgeState(String username, boolean isBridgeUser, boolean approved, String approvalToken) {
        if (username == null || username.isBlank()) {
            return;
        }
        String userPath = "users." + username;
        config.set(userPath + ".is_bridge_user", isBridgeUser);
        config.set(userPath + ".bridge_approved", approved);
        config.set(userPath + ".approval_token", approvalToken == null || approvalToken.isBlank() ? null : approvalToken);
    }

    private void normalizeBridgeFlagsOnLoad() {
        if (!config.isConfigurationSection("users")) {
            return;
        }
        for (String username : config.getConfigurationSection("users").getKeys(false)) {
            String userPath = "users." + username;
            boolean hasBridgeFlag = config.contains(userPath + ".is_bridge_user");
            boolean hasApprovedFlag = config.contains(userPath + ".bridge_approved");
            boolean hasTokenFlag = config.contains(userPath + ".approval_token");

            if (!hasBridgeFlag && (hasApprovedFlag || hasTokenFlag)) {
                config.set(userPath + ".is_bridge_user", true);
            }

            boolean isBridgeUser = config.getBoolean(userPath + ".is_bridge_user", false);
            if (isBridgeUser && !hasApprovedFlag) {
                String token = config.getString(userPath + ".approval_token", "");
                boolean pending = token != null && !token.isBlank();
                config.set(userPath + ".bridge_approved", !pending);
            }
        }
    }

    public String getOrCreateOwner2faSecret() {
        String secret = config.getString("owner-2fa-secret", "");
        if (secret == null || secret.isBlank()) {
            byte[] raw = new byte[20];
            new SecureRandom().nextBytes(raw);
            secret = Base64.getEncoder().encodeToString(raw);
            config.set("owner-2fa-secret", secret);
            save();
        }
        return secret;
    }

    public String regenerateOwner2faSecret(String actor) {
        if (!isMainAdmin(actor)) {
            return null;
        }
        byte[] raw = new byte[20];
        new SecureRandom().nextBytes(raw);
        String secret = Base64.getEncoder().encodeToString(raw);
        config.set("owner-2fa-secret", secret);
        save();
        WebActionLogger.log("OWNER_2FA_REGENERATED", "actor=" + actor);
        return secret;
    }

    public String getCurrentOwner2faCode() {
        String secret = getOrCreateOwner2faSecret();
        return generateTotp(secret, System.currentTimeMillis() / 30000L);
    }

    public int getOwner2faSecondsRemaining() {
        return (int) (30 - ((System.currentTimeMillis() / 1000L) % 30));
    }

    public boolean verifyOwner2faCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String normalized = code.replaceAll("\\s+", "").trim();
        String secret = getOrCreateOwner2faSecret();
        long step = System.currentTimeMillis() / 30000L;
        return normalized.equals(generateTotp(secret, step))
                || normalized.equals(generateTotp(secret, step - 1))
                || normalized.equals(generateTotp(secret, step + 1));
    }

    private boolean registerUser(String username, String password, String linkedPlayer, String linkedUuid, String role,
            List<String> extraPermissions) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return false;
        }
        if (userExists(username)) {
            return false;
        }

        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltStr = Base64.getEncoder().encodeToString(salt);
        String hash = hash(password, salt);

        String userPath = "users." + username;
        config.set(userPath + ".hash", hash);
        config.set(userPath + ".salt", saltStr);
        config.set(userPath + ".role", normalizeRole(role));
        config.set(userPath + ".permissions", extraPermissions == null ? List.of() : List.copyOf(extraPermissions));
        config.set(userPath + ".linkedPlayer", linkedPlayer);
        config.set(userPath + ".linkedUuid", linkedUuid);
        config.set(userPath + ".is_bridge_user", false);
        config.set(userPath + ".bridge_approved", false);
        config.set(userPath + ".approval_token", null);
        if ((config.getString("main-admin", "").isBlank()) && "ADMIN".equals(normalizeRole(role))) {
            config.set("main-admin", username);
        }

        boolean saved = save();
        if (saved) {
            WebActionLogger.logRegistration(username, linkedPlayer == null ? "Unknown" : linkedPlayer);
        }
        return saved;
    }

    public boolean check(String username, String password) {
        if (!isRegistered())
            return false;

        if (config.contains("admin.hash") && config.contains("admin.salt") && config.getString("admin.username") != null) {
            String storedUser = config.getString("admin.username");
            if (storedUser != null && storedUser.equals(username)) {
                String saltStr = config.getString("admin.salt");
                String storedHash = config.getString("admin.hash");
                byte[] salt = Base64.getDecoder().decode(saltStr);
                String computedHash = hash(password, salt);
                return computedHash.equals(storedHash);
            }
        }

        if (!userExists(username)) {
            return false;
        }

        String userPath = "users." + username;
        String saltStr = config.getString(userPath + ".salt");
        String storedHash = config.getString(userPath + ".hash");
        if (saltStr == null || storedHash == null) {
            return false;
        }

        byte[] salt = Base64.getDecoder().decode(saltStr);
        String computedHash = hash(password, salt);
        return computedHash.equals(storedHash);
    }

    public boolean userExists(String username) {
        return config.contains("users." + username + ".hash");
    }

    public boolean userHasPermission(String username, String permission) {
        if (username == null || permission == null) {
            return false;
        }
        if (isMainAdmin(username)) {
            return true;
        }
        if (config.contains("admin.username") && username.equals(config.getString("admin.username"))) {
            return true;
        }

        String userPath = "users." + username;
        if (!config.isConfigurationSection(userPath)) {
            return false;
        }

        List<String> effective = new ArrayList<>();
        String role = normalizeRole(config.getString(userPath + ".role", "MODERATOR"));
        effective.addAll(getRolePermissionsInternal(role));
        effective.addAll(config.getStringList(userPath + ".permissions"));
        return matchesPermission(effective, permission);
    }

    public boolean setUserRole(String username, String role) {
        if (isMainAdmin(username) && !"ADMIN".equals(normalizeRole(role))) {
            return false;
        }
        if (!userExists(username)) {
            return false;
        }
        config.set("users." + username + ".role", normalizeRole(role));
        return save();
    }

    public AuthResult setUserRoleSafe(String actor, String username, String role) {
        refreshConfigView();
        if (!userExists(username)) {
            return new AuthResult(false, "user_not_found");
        }
        if (actor == null || actor.isBlank()) {
            return new AuthResult(false, "actor_not_found");
        }

        String normalized = normalizeRole(role);
        if (!roleExists(normalized)) {
            return new AuthResult(false, "role_not_found");
        }
        if (isMainAdmin(username) && !"ADMIN".equals(normalized)) {
            WebActionLogger.log("ROLE_CHANGE_BLOCKED",
                    "actor=" + actor + " target=" + username + " reason=main_admin_protected");
            return new AuthResult(false, "main_admin_protected");
        }

        if (!isMainAdmin(actor)) {
            int actorValue = getActorRoleValue(actor);
            int targetUserValue = getUserRoleValue(username);
            int targetRoleValue = getRoleValueInternal(normalized);
            if (targetUserValue >= actorValue) {
                return new AuthResult(false, "cannot_manage_same_or_higher_user");
            }
            if (targetRoleValue >= actorValue) {
                return new AuthResult(false, "cannot_assign_same_or_higher_role");
            }
        }

        config.set("users." + username + ".role", normalized);
        return save() ? new AuthResult(true, "ok") : new AuthResult(false, "save_failed");
    }

    public AuthResult createRoleSafe(String actor, String roleName, String presetRole) {
        String normalizedRole = normalizeRoleNameForCreate(roleName);
        if (normalizedRole == null) {
            return new AuthResult(false, "invalid_role_name");
        }
        if ("MAIN_ADMIN".equals(normalizedRole)) {
            return new AuthResult(false, "reserved_role");
        }
        if (roleExists(normalizedRole)) {
            return new AuthResult(false, "role_exists");
        }

        String normalizedPreset = normalizeRole(presetRole);
        if (!roleExists(normalizedPreset)) {
            return new AuthResult(false, "preset_not_found");
        }
        if ("ADMIN".equals(normalizedPreset) && !isMainAdmin(actor)) {
            WebActionLogger.log("ROLE_CREATE_BLOCKED",
                    "actor=" + actor + " role=" + normalizedRole + " reason=admin_preset_requires_main_admin");
            return new AuthResult(false, "admin_preset_requires_main_admin");
        }

        List<String> presetPermissions = new ArrayList<>(getRolePermissionsInternal(normalizedPreset));
        int presetValue = getRoleValueInternal(normalizedPreset);
        int newRoleValue = presetValue;
        if (!isMainAdmin(actor)) {
            int actorValue = getActorRoleValue(actor);
            newRoleValue = Math.min(presetValue, Math.max(0, actorValue - 1));
            if (newRoleValue >= actorValue) {
                return new AuthResult(false, "cannot_create_same_or_higher_role");
            }
        }
        config.set("roles." + normalizedRole + ".permissions", presetPermissions);
        config.set("roles." + normalizedRole + ".value", newRoleValue);
        boolean saved = save();
        if (!saved) {
            return new AuthResult(false, "save_failed");
        }

        WebActionLogger.log("ROLE_CREATED",
                "actor=" + actor + " role=" + normalizedRole + " preset=" + normalizedPreset);
        return new AuthResult(true, normalizedRole);
    }

    public AuthResult setRoleValueSafe(String actor, String role, int value) {
        String normalized = normalizeRole(role);
        if (normalized.isBlank() || "MAIN_ADMIN".equals(normalized)) {
            return new AuthResult(false, "invalid_role");
        }
        if (!roleExists(normalized)) {
            return new AuthResult(false, "role_not_found");
        }
        if (value < 0 || value > 1_000_000) {
            return new AuthResult(false, "invalid_role_value");
        }
        if ("ADMIN".equals(normalized) && !isMainAdmin(actor)) {
            return new AuthResult(false, "only_main_admin_can_edit_admin_role");
        }

        if (!isMainAdmin(actor)) {
            int actorValue = getActorRoleValue(actor);
            int currentRoleValue = getRoleValueInternal(normalized);
            if (currentRoleValue >= actorValue) {
                return new AuthResult(false, "cannot_manage_same_or_higher_role");
            }
            if (value >= actorValue) {
                return new AuthResult(false, "cannot_set_role_value_same_or_higher_than_self");
            }
        }

        config.set("roles." + normalized + ".value", value);
        if (save()) {
            WebActionLogger.log("ROLE_VALUE_UPDATED",
                    "actor=" + actor + " role=" + normalized + " value=" + value);
            return new AuthResult(true, "ok");
        }
        return new AuthResult(false, "save_failed");
    }

    public AuthResult deleteRoleSafe(String actor, String role) {
        String normalized = normalizeRole(role);
        if (normalized.isBlank()) {
            return new AuthResult(false, "invalid_role");
        }
        if (!roleExists(normalized)) {
            return new AuthResult(false, "role_not_found");
        }
        if ("ADMIN".equals(normalized) || "MODERATOR".equals(normalized) || "MAIN_ADMIN".equals(normalized)) {
            return new AuthResult(false, "system_role_protected");
        }

        if (!isMainAdmin(actor)) {
            int actorValue = getActorRoleValue(actor);
            int roleValue = getRoleValueInternal(normalized);
            if (roleValue >= actorValue) {
                return new AuthResult(false, "cannot_manage_same_or_higher_role");
            }
        }

        if (config.isConfigurationSection("users")) {
            for (String username : config.getConfigurationSection("users").getKeys(false)) {
                String userRole = normalizeRole(config.getString("users." + username + ".role", "MODERATOR"));
                if (normalized.equals(userRole)) {
                    return new AuthResult(false, "role_in_use");
                }
            }
        }

        config.set("roles." + normalized, null);
        if (save()) {
            WebActionLogger.log("ROLE_DELETED", "actor=" + actor + " role=" + normalized);
            return new AuthResult(true, "ok");
        }
        return new AuthResult(false, "save_failed");
    }

    public AuthResult transferMainAdmin(String actor, String newMainAdmin) {
        refreshConfigView();
        if (!userExists(newMainAdmin)) {
            return new AuthResult(false, "user_not_found");
        }

        String role = normalizeRole(config.getString("users." + newMainAdmin + ".role", "MODERATOR"));
        if (!"ADMIN".equals(role)) {
            return new AuthResult(false, "target_not_admin");
        }

        config.set("main-admin", newMainAdmin);
        boolean saved = save();
        if (saved) {
            WebActionLogger.log("MAIN_ADMIN_TRANSFER",
                    "actor=" + actor + " newMainAdmin=" + newMainAdmin);
            return new AuthResult(true, "ok");
        }
        return new AuthResult(false, "save_failed");
    }

    public AuthResult deleteUserSafe(String actor, String username) {
        refreshConfigView();
        if (!userExists(username)) {
            return new AuthResult(false, "user_not_found");
        }
        if (isMainAdmin(username)) {
            WebActionLogger.log("USER_DELETE_BLOCKED",
                    "actor=" + actor + " target=" + username + " reason=main_admin_protected");
            return new AuthResult(false, "main_admin_protected");
        }

        config.set("users." + username, null);
        boolean saved = save();
        if (saved) {
            WebActionLogger.log("USER_DELETED", "actor=" + actor + " target=" + username);
            return new AuthResult(true, "ok");
        }
        return new AuthResult(false, "save_failed");
    }

    public String getMainAdmin() {
        return config.getString("main-admin", "");
    }

    public boolean isMainAdmin(String username) {
        return isMainAdminInternal(username);
    }

    public Map<String, UserInfo> getUsers() {
        refreshConfigView();
        Map<String, UserInfo> users = new LinkedHashMap<>();
        if (!config.isConfigurationSection("users")) {
            return users;
        }

        for (String username : config.getConfigurationSection("users").getKeys(false)) {
            String userPath = "users." + username;
            boolean isMain = isMainAdminInternal(username);
            String displayRole = isMain
                    ? "MAIN_ADMIN"
                    : normalizeRole(config.getString(userPath + ".role", "MODERATOR"));
            users.put(username, new UserInfo(
                    username,
                    displayRole,
                    config.getStringList(userPath + ".permissions"),
                    config.getString(userPath + ".linkedPlayer", ""),
                    isMain,
                    isMain ? false : config.getBoolean(userPath + ".is_bridge_user", false),
                    config.getBoolean(userPath + ".bridge_approved", false),
                    config.getString(userPath + ".approval_token", "")));
        }

        if (config.contains("admin.username")) {
            String adminUsername = config.getString("admin.username");
            users.putIfAbsent(adminUsername,
                    new UserInfo(adminUsername, "ADMIN", List.of(), config.getString("admin.linkedPlayer", ""),
                            isMainAdmin(adminUsername), false, true, ""));
        }

        return users;
    }

    public Set<String> getRoleNames() {
        if (!config.isConfigurationSection("roles")) {
            return Set.of("ADMIN", "MODERATOR");
        }
        Set<String> roles = new LinkedHashSet<>(config.getConfigurationSection("roles").getKeys(false));
        roles.remove("MAIN_ADMIN");
        return Set.copyOf(roles);
    }

    public Map<String, Integer> getRoleValues() {
        Map<String, Integer> roleValues = new LinkedHashMap<>();
        for (String role : getRoleNames()) {
            String normalized = normalizeRole(role);
            roleValues.put(normalized, getRoleValueInternal(normalized));
        }
        return roleValues;
    }

    public int getActorRoleValue(String username) {
        refreshConfigView();
        if (username == null || username.isBlank()) {
            return 0;
        }
        if (isMainAdmin(username)) {
            return getHighestRoleValue() + 1;
        }
        if (!userExists(username)) {
            return 0;
        }
        String role = normalizeRole(config.getString("users." + username + ".role", "MODERATOR"));
        return getRoleValueInternal(role);
    }

    public int getUserRoleValue(String username) {
        return getActorRoleValue(username);
    }

    public List<String> getRolePermissions(String role) {
        return List.copyOf(getRolePermissionsInternal(normalizeRole(role)));
    }

    public Map<String, List<String>> getRolesWithPermissions() {
        Map<String, List<String>> roles = new LinkedHashMap<>();
        for (String role : getRoleNames()) {
            String normalized = normalizeRole(role);
            roles.put(normalized, List.copyOf(getRolePermissionsInternal(normalized)));
        }
        return roles;
    }

    public Set<String> getEffectivePermissions(String username) {
        if (username == null) {
            return Set.of();
        }
        if (isMainAdmin(username)) {
            return Set.of("dash.web.*", "*");
        }
        if (config.contains("admin.username") && username.equals(config.getString("admin.username"))) {
            return Set.of("dash.web.*", "*");
        }

        String userPath = "users." + username;
        if (!config.isConfigurationSection(userPath)) {
            return Set.of();
        }

        LinkedHashSet<String> effective = new LinkedHashSet<>();
        String role = normalizeRole(config.getString(userPath + ".role", "MODERATOR"));
        effective.addAll(getRolePermissionsInternal(role));
        effective.addAll(config.getStringList(userPath + ".permissions"));
        return Set.copyOf(effective);
    }

    public AuthResult updateRolePermissionsSafe(String actor, String role, List<String> add, List<String> remove) {
        String normalized = normalizeRole(role);
        if (normalized.isBlank()) {
            return new AuthResult(false, "invalid_role");
        }
        if ("MAIN_ADMIN".equals(normalized)) {
            WebActionLogger.log("ROLE_PERMISSIONS_BLOCKED",
                    "actor=" + actor + " role=" + normalized + " reason=main_admin_role_hidden");
            return new AuthResult(false, "main_admin_role_hidden");
        }
        if ("ADMIN".equals(normalized) && !isMainAdmin(actor)) {
            WebActionLogger.log("ROLE_PERMISSIONS_BLOCKED",
                    "actor=" + actor + " role=" + normalized + " reason=only_main_admin_can_edit_admin_role");
            return new AuthResult(false, "only_main_admin_can_edit_admin_role");
        }

        if (!isMainAdmin(actor)) {
            int actorValue = getActorRoleValue(actor);
            int roleValue = getRoleValueInternal(normalized);
            if (roleValue >= actorValue) {
                return new AuthResult(false, "cannot_manage_same_or_higher_role");
            }
        }

        String rolePermPath = "roles." + normalized + ".permissions";
        if (!config.contains(rolePermPath) && !config.isConfigurationSection("roles." + normalized)) {
            return new AuthResult(false, "role_not_found");
        }

        LinkedHashSet<String> permissions = new LinkedHashSet<>(config.getStringList(rolePermPath));
        if (remove != null) {
            for (String perm : remove) {
                if (perm != null && !perm.isBlank()) {
                    permissions.remove(perm.trim());
                }
            }
        }
        if (add != null) {
            for (String perm : add) {
                if (perm != null && !perm.isBlank()) {
                    permissions.add(perm.trim());
                }
            }
        }

        config.set(rolePermPath, new ArrayList<>(permissions));
        boolean saved = save();
        if (!saved) {
            return new AuthResult(false, "save_failed");
        }

        WebActionLogger.log("ROLE_PERMISSIONS_UPDATED",
                "actor=" + actor + " role=" + normalized + " add=" + (add == null ? 0 : add.size())
                        + " remove=" + (remove == null ? 0 : remove.size()));
        return new AuthResult(true, "ok");
    }

    private void ensureDefaultRoles() {
        if (!config.contains("roles.ADMIN.permissions")) {
            config.set("roles.ADMIN.permissions", List.of("dash.web.*"));
        }
        if (!config.contains("roles.ADMIN.value")) {
            config.set("roles.ADMIN.value", 100);
        }
        if (!config.contains("roles.MODERATOR.permissions")) {
            config.set("roles.MODERATOR.permissions",
                    List.of(
                            "dash.web.players.read",
                            "dash.web.players.kick",
                            "dash.web.players.ban",
                            "dash.web.players.moderate",
                            "dash.web.stats.read",
                            "dash.web.console.read"));
        }
        if (!config.contains("roles.MODERATOR.value")) {
            config.set("roles.MODERATOR.value", 50);
        }
    }

    private void ensureMainAdmin() {
        String configured = config.getString("main-admin", "");
        if (!configured.isBlank() && (userExists(configured) || configured.equals(config.getString("admin.username", "")))) {
            return;
        }

        if (config.contains("admin.username")) {
            config.set("main-admin", config.getString("admin.username"));
            return;
        }

        if (!config.isConfigurationSection("users")) {
            return;
        }

        for (String username : config.getConfigurationSection("users").getKeys(false)) {
            String role = normalizeRole(config.getString("users." + username + ".role", "MODERATOR"));
            if ("ADMIN".equals(role)) {
                config.set("main-admin", username);
                return;
            }
        }
    }

    private List<String> getRolePermissionsInternal(String role) {
        return config.getStringList("roles." + normalizeRole(role) + ".permissions");
    }

    private boolean roleExists(String role) {
        String normalized = normalizeRole(role);
        return config.contains("roles." + normalized + ".permissions")
                || config.isConfigurationSection("roles." + normalized);
    }

    private String normalizeRoleNameForCreate(String roleName) {
        if (roleName == null) {
            return null;
        }
        String normalized = roleName.trim()
                .replaceAll("\\s+", "_")
                .replace('.', '_')
                .toUpperCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 64) {
            return null;
        }
        return normalized;
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "MODERATOR";
        }
        return role.toUpperCase(Locale.ROOT);
    }

    private int getRoleValueInternal(String role) {
        String normalized = normalizeRole(role);
        int fallback = switch (normalized) {
            case "ADMIN" -> 100;
            case "MODERATOR" -> 50;
            default -> 0;
        };
        return config.getInt("roles." + normalized + ".value", fallback);
    }

    private int getHighestRoleValue() {
        int highest = 0;
        for (String role : getRoleNames()) {
            highest = Math.max(highest, getRoleValueInternal(role));
        }
        return highest;
    }

    private boolean matchesPermission(List<String> grants, String requested) {
        if (grants.contains("dash.web.*") || grants.contains("*")) {
            return true;
        }
        for (String grant : grants) {
            if (grant.equalsIgnoreCase(requested)) {
                return true;
            }
            if (grant.endsWith(".*")) {
                String prefix = grant.substring(0, grant.length() - 1);
                if (requested.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean save() {
        try {
            config.save(userFile);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private void refreshConfigView() {
        if (!userFile.exists()) {
            return;
        }
        config = YamlConfiguration.loadConfiguration(userFile);
    }

    private boolean isMainAdminInternal(String username) {
        return username != null && username.equalsIgnoreCase(getMainAdmin());
    }

    private boolean isLegacyAdminInternal(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        String legacyAdmin = config.getString("admin.username", "");
        return !legacyAdmin.isBlank() && username.equalsIgnoreCase(legacyAdmin);
    }

    private void clearBridgeFlags(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        String userPath = "users." + username;
        if (!config.isConfigurationSection(userPath)) {
            return;
        }
        if (config.getBoolean(userPath + ".is_bridge_user", false)
                || !config.getBoolean(userPath + ".bridge_approved", true)
                || !config.getString(userPath + ".approval_token", "").isBlank()) {
            config.set(userPath + ".is_bridge_user", false);
            config.set(userPath + ".bridge_approved", true);
            config.set(userPath + ".approval_token", null);
            save();
        }
    }

    private String hash(String password, byte[] salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            byte[] hashedPassword = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashedPassword);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }

    private String generateTotp(String base64Secret, long timestep) {
        try {
            byte[] secret = Base64.getDecoder().decode(base64Secret);
            byte[] data = ByteBuffer.allocate(8).putLong(timestep).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hmac = mac.doFinal(data);
            int offset = hmac[hmac.length - 1] & 0x0F;
            int binary = ((hmac[offset] & 0x7F) << 24)
                    | ((hmac[offset + 1] & 0xFF) << 16)
                    | ((hmac[offset + 2] & 0xFF) << 8)
                    | (hmac[offset + 3] & 0xFF);
            int otp = binary % 1_000_000;
            return String.format("%06d", otp);
        } catch (Exception e) {
            return "000000";
        }
    }

    public record UserInfo(String username, String role, List<String> permissions, String linkedPlayer,
            boolean mainAdmin, boolean bridgeUser, boolean bridgeApproved, String approvalToken) {
    }

    public record BridgeSsoResult(boolean created, boolean approved, String username, String approvalToken) {
    }

    public record AuthResult(boolean success, String message) {
    }
}
