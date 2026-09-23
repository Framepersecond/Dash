package dash.ai;

import dash.security.FilePermissions;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

public final class AiSecretVault {
    private static final byte[] AAD = "Dash-AI-Vault-v1".getBytes(StandardCharsets.US_ASCII);
    private final Path dataDir;
    private final Path keyFile;
    private final Path secretFile;
    private final Map<String, String> environment;

    public AiSecretVault(Path dataDir) {
        this(dataDir, System.getenv());
    }

    AiSecretVault(Path dataDir, Map<String, String> environment) {
        this.dataDir = dataDir.toAbsolutePath().normalize();
        this.keyFile = this.dataDir.resolve("ai-agent.key").normalize();
        this.secretFile = this.dataDir.resolve("ai-secrets.dat").normalize();
        this.environment = environment == null ? Map.of() : Map.copyOf(environment);
        if (!keyFile.startsWith(this.dataDir) || !secretFile.startsWith(this.dataDir)) {
            throw new IllegalArgumentException("AI vault escaped its data directory.");
        }
    }

    public synchronized String load() {
        String external = clean(environment.get("GOOGLE_API_KEY"));
        if (external.isBlank()) external = clean(environment.get("GEMINI_API_KEY"));
        if (!external.isBlank()) return external;
        try {
            if (!regularNoLink(keyFile) || !regularNoLink(secretFile)) return "";
            byte[] key = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
            String[] payload = Files.readString(secretFile, StandardCharsets.US_ASCII).trim().split(":", 2);
            if (key.length != 32 || payload.length != 2) return "";
            byte[] nonce = Base64.getDecoder().decode(payload[0]);
            byte[] encrypted = Base64.getDecoder().decode(payload[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    public synchronized void save(String rawKey) {
        String apiKey = clean(rawKey);
        if (apiKey.length() < 20 || apiKey.length() > 512 || apiKey.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Google API key format is invalid.");
        }
        try {
            Files.createDirectories(dataDir);
            byte[] key;
            if (regularNoLink(keyFile)) {
                key = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
            } else {
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(256);
                SecretKey generated = generator.generateKey();
                key = generated.getEncoded();
                Files.writeString(keyFile, Base64.getEncoder().encodeToString(key), StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                FilePermissions.ownerReadWrite(keyFile);
            }
            if (key.length != 32) throw new IllegalStateException("AI vault key is invalid.");
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
            String payload = Base64.getEncoder().encodeToString(nonce) + ":"
                    + Base64.getEncoder().encodeToString(encrypted);
            Files.writeString(secretFile, payload, StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            FilePermissions.ownerReadWrite(secretFile);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Google API key could not be stored securely.", ex);
        }
    }

    public synchronized void removeStoredSecret() {
        try { Files.deleteIfExists(secretFile); } catch (Exception ignored) { }
    }

    public boolean externallyManaged() {
        return !clean(environment.get("GOOGLE_API_KEY")).isBlank()
                || !clean(environment.get("GEMINI_API_KEY")).isBlank();
    }

    public String fingerprint() {
        String key = load();
        if (key.isBlank()) return "";
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
            return hash.substring(0, 12);
        } catch (Exception ignored) {
            return "configured";
        }
    }

    private static boolean regularNoLink(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
