package dash.web;

import java.io.IOException;
import java.io.InputStream;

/**
 * Serves the Dash Motion Engine (script + style layer) straight out of the jar.
 *
 * <p>Shipping the engine as a bundled asset rather than a CDN script means the
 * admin panel animates correctly on an air-gapped or LAN-only server, which is
 * the common deployment for a Minecraft host. The previous motion layer
 * depended on GSAP from jsdelivr and silently degraded to <em>no animation at
 * all</em> whenever that request failed.
 *
 * <p>The filename carries the version, so the response can be cached
 * indefinitely; bump {@link #SCRIPT_PATH}/{@link #STYLE_PATH} and the resource
 * names together when the engine changes.
 */
public final class BundledMotion {

    public static final String SCRIPT_PATH = "/assets/dash-motion-5.js";
    public static final String STYLE_PATH = "/assets/dash-motion-5.css";

    private static final byte[] SCRIPT = load("web/dash-motion-5.js");
    private static final byte[] STYLE = load("web/dash-motion-5.css");

    private BundledMotion() {
    }

    public static byte[] script() {
        return SCRIPT;
    }

    public static byte[] style() {
        return STYLE;
    }

    /** Markup for {@code <head>}; the stylesheet must come after the inline styles. */
    public static String headTags() {
        return "<link rel=\"stylesheet\" href=\"" + STYLE_PATH + "\">\n"
                + "<script src=\"" + SCRIPT_PATH + "\" defer></script>\n";
    }

    private static byte[] load(String resource) {
        try (InputStream input = BundledMotion.class.getClassLoader().getResourceAsStream(resource)) {
            return input == null ? new byte[0] : input.readAllBytes();
        } catch (IOException ignored) {
            return new byte[0];
        }
    }
}
