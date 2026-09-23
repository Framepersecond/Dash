package dash.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

public final class FilePermissions {
    private FilePermissions() { }

    public static void ownerReadWrite(Path file) {
        if (file == null || !Files.exists(file)) return;
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) { }
    }
}
