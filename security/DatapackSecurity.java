package dash.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class DatapackSecurity {
    public static final int MAX_ARCHIVE_BYTES = 64 * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_ENTRY_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 10_000;

    private DatapackSecurity() {
    }

    public static String sanitizeUploadName(String suppliedName) {
        String name = suppliedName == null ? "" : suppliedName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            name = "datapack";
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            name += ".zip";
        }
        return name.length() > 200 ? name.substring(0, 196) + ".zip" : name;
    }

    public static void validateArchive(byte[] data) throws IOException {
        if (data == null || data.length == 0 || data.length > MAX_ARCHIVE_BYTES) {
            throw new IOException("Datapack archive size is invalid.");
        }

        int entries = 0;
        long expanded = 0L;
        boolean hasPackMetadata = false;
        byte[] buffer = new byte[16 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ENTRIES) {
                    throw new IOException("Datapack contains too many entries.");
                }
                validateEntryName(entry.getName());
                if (entry.isDirectory()) {
                    continue;
                }

                long entryBytes = 0L;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    entryBytes += read;
                    expanded += read;
                    if (entryBytes > MAX_ENTRY_BYTES || expanded > MAX_EXPANDED_BYTES) {
                        throw new IOException("Datapack expands beyond the allowed limit.");
                    }
                }
                String normalized = entry.getName().replace('\\', '/');
                if ("pack.mcmeta".equals(normalized) || normalized.endsWith("/pack.mcmeta")) {
                    hasPackMetadata = true;
                }
            }
        }
        if (entries == 0 || !hasPackMetadata) {
            throw new IOException("Datapack archive does not contain pack.mcmeta.");
        }
    }

    public static Path resolveInstalled(Path root, String suppliedName) throws IOException {
        if (!isSafeInstalledName(suppliedName)) {
            return null;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Files.createDirectories(normalizedRoot);
        Path direct = normalizedRoot.resolve(suppliedName).normalize();
        Path resolved = safeExisting(normalizedRoot, direct);
        if (resolved != null) {
            return resolved;
        }
        if (!suppliedName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return safeExisting(normalizedRoot, normalizedRoot.resolve(suppliedName + ".zip").normalize());
        }
        return null;
    }

    public static void writeAtomically(Path root, String fileName, byte[] data) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Files.createDirectories(normalizedRoot);
        Path target = normalizedRoot.resolve(fileName).normalize();
        if (!target.startsWith(normalizedRoot) || target.getParent() == null || !target.getParent().equals(normalizedRoot)) {
            throw new IOException("Invalid datapack destination.");
        }
        Path temporary = Files.createTempFile(normalizedRoot, ".dash-datapack-", ".tmp");
        try {
            Files.write(temporary, data);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void deleteNoFollow(Path target) throws IOException {
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Path safeExisting(Path root, Path candidate) throws IOException {
        if (!candidate.startsWith(root) || !Files.exists(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(candidate)) {
            return null;
        }
        Path realRoot = root.toRealPath();
        Path realCandidate = candidate.toRealPath();
        return realCandidate.startsWith(realRoot) ? candidate : null;
    }

    private static boolean isSafeInstalledName(String name) {
        if (name == null || name.isBlank() || name.length() > 200 || ".".equals(name) || "..".equals(name)) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '/' || c == '\\' || c == '"') {
                return false;
            }
        }
        return true;
    }

    private static void validateEntryName(String name) throws IOException {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.startsWith("/") || name.startsWith("\\")) {
            throw new IOException("Datapack contains an invalid entry name.");
        }
        Path normalized = Path.of(name.replace('\\', '/')).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IOException("Datapack contains a traversal entry.");
        }
    }
}
