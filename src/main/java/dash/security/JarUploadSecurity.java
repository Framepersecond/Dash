package dash.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class JarUploadSecurity {

    private static final long MAX_UPLOAD_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_ENTRY_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_EXPANDED_BYTES = 1024L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 20_000;

    private JarUploadSecurity() {
    }

    public static void validateArchive(byte[] archive, Set<String> requiredDescriptors) throws IOException {
        if (archive == null || archive.length == 0 || archive.length > MAX_UPLOAD_BYTES) {
            throw new IOException("JAR upload is empty or exceeds 64 MiB.");
        }
        Set<String> descriptors = new HashSet<>();
        if (requiredDescriptors != null) {
            for (String descriptor : requiredDescriptors) {
                if (descriptor != null && !descriptor.isBlank()) {
                    descriptors.add(descriptor.replace('\\', '/').toLowerCase(Locale.ROOT));
                }
            }
        }
        if (descriptors.isEmpty()) {
            throw new IOException("No platform descriptor policy was provided.");
        }

        Set<String> seen = new HashSet<>();
        boolean descriptorFound = false;
        int entryCount = 0;
        long expandedBytes = 0L;
        byte[] buffer = new byte[16 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRIES) {
                    throw new IOException("JAR contains too many entries.");
                }
                String name = safeEntryName(entry.getName());
                if (!seen.add(name)) {
                    throw new IOException("JAR contains a duplicate entry.");
                }
                descriptorFound |= descriptors.contains(name.toLowerCase(Locale.ROOT));
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                long entryBytes = 0L;
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    entryBytes += read;
                    expandedBytes += read;
                    if (entryBytes > MAX_ENTRY_BYTES || expandedBytes > MAX_EXPANDED_BYTES) {
                        throw new IOException("JAR expanded content exceeds safety limits.");
                    }
                }
                zip.closeEntry();
            }
        } catch (IllegalArgumentException ex) {
            throw new IOException("JAR contains an invalid entry name.", ex);
        }
        if (entryCount == 0 || !descriptorFound) {
            throw new IOException("JAR does not contain metadata for this server platform.");
        }
    }

    public static Path writeAtomically(Path directory, String fileName, byte[] archive) throws IOException {
        if (directory == null || fileName == null || fileName.isBlank() || archive == null) {
            throw new IOException("Invalid JAR upload target.");
        }
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("JAR upload directory is not a real directory.");
        }
        String safeName = fileName.replace('\\', '/');
        safeName = safeName.substring(safeName.lastIndexOf('/') + 1)
                .replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.isBlank() || !safeName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("Only .jar files are accepted.");
        }
        Path target = root.resolve(safeName).normalize();
        if (!target.startsWith(root) || Files.isSymbolicLink(target)) {
            throw new IOException("Invalid JAR upload target.");
        }

        Path temporary = Files.createTempFile(root, ".dash-upload-", ".tmp");
        try {
            Files.write(temporary, archive, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String safeEntryName(String rawName) throws IOException {
        if (rawName == null || rawName.isBlank() || rawName.indexOf('\\') >= 0) {
            throw new IOException("JAR contains an unsafe entry name.");
        }
        Path path = Path.of(rawName).normalize();
        String normalized = path.toString().replace('\\', '/');
        if (path.isAbsolute() || normalized.isBlank() || normalized.equals("..")
                || normalized.startsWith("../") || rawName.startsWith("/")) {
            throw new IOException("JAR contains an unsafe entry name.");
        }
        return normalized;
    }
}

