package dash.security;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class SafeHttpDownloads {
    private static final int MAX_REDIRECTS = 5;
    private static final long MAX_JAR_BYTES = 256L * 1024L * 1024L;

    private SafeHttpDownloads() {
    }

    public static void downloadPublicHttpsJar(String rawUrl, Path target) throws IOException {
        if (target == null || target.getParent() == null) {
            throw new IOException("A target file inside an existing directory is required.");
        }
        URI current = parsePublicHttpsUri(rawUrl);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".dash-download-", ".jar.tmp");
        try {
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                validatePublicDestination(current);
                HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(45_000);
                connection.setRequestProperty("User-Agent", "Dash-SecureDownloader/4.3");
                try {
                    int status = connection.getResponseCode();
                    if (status >= 300 && status < 400) {
                        String location = connection.getHeaderField("Location");
                        if (location == null || location.isBlank() || redirects == MAX_REDIRECTS) {
                            throw new IOException("Download redirect was missing or exceeded the redirect limit.");
                        }
                        current = current.resolve(location);
                        continue;
                    }
                    if (status < 200 || status >= 300) {
                        throw new IOException("Download returned HTTP " + status + ".");
                    }
                    long declaredLength = connection.getContentLengthLong();
                    if (declaredLength > MAX_JAR_BYTES) {
                        throw new IOException("Download exceeds the 256 MiB limit.");
                    }
                    try (InputStream input = connection.getInputStream()) {
                        copyLimited(input, temporary, MAX_JAR_BYTES);
                    }
                    validateJar(temporary);
                    moveAtomically(temporary, target);
                    return;
                } finally {
                    connection.disconnect();
                }
            }
            throw new IOException("Download exceeded the redirect limit.");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static URI parsePublicHttpsUri(String rawUrl) throws IOException {
        try {
            URI uri = URI.create(rawUrl == null ? "" : rawUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IOException("Only public HTTPS URLs on port 443 are supported.");
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid download URL.", ex);
        }
    }

    private static void validatePublicDestination(URI uri) throws IOException {
        URI validated = parsePublicHttpsUri(uri.toString());
        InetAddress[] addresses = InetAddress.getAllByName(validated.getHost());
        if (addresses.length == 0) {
            throw new IOException("Download host did not resolve.");
        }
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new IOException("Download host resolves to a local or reserved network.");
            }
        }
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if (first == 0 || first == 127 || first >= 224) {
                return false;
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return false;
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return false;
            }
        } else if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) {
            return false;
        }
        return true;
    }

    private static void copyLimited(InputStream input, Path target, long maxBytes) throws IOException {
        try (var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Download exceeds the 256 MiB limit.");
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private static void validateJar(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            Enumeration<JarEntry> entries = jar.entries();
            if (!entries.hasMoreElements()) {
                throw new IOException("Downloaded JAR is empty.");
            }
        } catch (java.util.zip.ZipException ex) {
            throw new IOException("Downloaded file is not a valid JAR archive.", ex);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
