package dash;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

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
    }

    public boolean isRegistered() {
        return config.contains("admin.hash") && config.contains("admin.salt");
    }

    public boolean register(String username, String password) {
        if (isRegistered())
            return false;

        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltStr = Base64.getEncoder().encodeToString(salt);
        String hash = hash(password, salt);

        config.set("admin.username", username);
        config.set("admin.hash", hash);
        config.set("admin.salt", saltStr);
        return save();
    }

    public boolean registerWithCode(String code, String username, String password) {
        if (isRegistered())
            return false;

        RegistrationManager regManager = Dash.getRegistrationManager();
        RegistrationManager.RegistrationCode regCode = regManager.validateAndConsume(code);

        if (regCode == null) {
            return false;
        }

        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltStr = Base64.getEncoder().encodeToString(salt);
        String hash = hash(password, salt);

        config.set("admin.username", username);
        config.set("admin.hash", hash);
        config.set("admin.salt", saltStr);
        config.set("admin.linkedPlayer", regCode.playerName());
        config.set("admin.linkedUuid", regCode.playerUuid());

        boolean saved = save();
        if (saved) {
            WebActionLogger.logRegistration(username, regCode.playerName());
        }
        return saved;
    }

    public boolean check(String username, String password) {
        if (!isRegistered())
            return false;

        String storedUser = config.getString("admin.username");
        if (!storedUser.equals(username))
            return false;

        String saltStr = config.getString("admin.salt");
        String storedHash = config.getString("admin.hash");

        byte[] salt = Base64.getDecoder().decode(saltStr);
        String computedHash = hash(password, salt);

        return computedHash.equals(storedHash);
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
}
