package dash.web;

import java.io.IOException;
import java.io.InputStream;

/** Immutable, precompiled dashboard styles served with a long-lived cache key. */
public final class BundledStyles {
    public static final String PATH = "/assets/dash-4.3.css";
    private static final byte[] CSS = load();

    private BundledStyles() {
    }

    public static byte[] css() {
        return CSS;
    }

    private static byte[] load() {
        try (InputStream input = BundledStyles.class.getClassLoader().getResourceAsStream("web/dash-4.3.css")) {
            return input == null ? new byte[0] : input.readAllBytes();
        } catch (IOException ignored) {
            return new byte[0];
        }
    }
}
