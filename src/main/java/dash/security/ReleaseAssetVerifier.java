package dash.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public final class ReleaseAssetVerifier {
    private ReleaseAssetVerifier() {
    }

    public static void verifySha256(Path artifact, String githubDigest) throws IOException {
        if (artifact == null || !Files.isRegularFile(artifact)) {
            throw new IOException("Downloaded release artifact is missing.");
        }
        String expected = githubDigest == null ? "" : githubDigest.trim().toLowerCase(Locale.ROOT);
        if (!expected.matches("sha256:[a-f0-9]{64}")) {
            throw new IOException("GitHub release asset has no valid SHA-256 digest.");
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception ex) {
            throw new IOException("SHA-256 is unavailable.", ex);
        }
        try (InputStream input = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        String expectedHex = expected.substring("sha256:".length());
        if (!MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                expectedHex.getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("Downloaded release artifact failed SHA-256 verification.");
        }
    }
}

