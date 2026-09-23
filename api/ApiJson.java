package dash.api;

/**
 * A minimal JSON writer.
 *
 * <p>The codebase builds JSON by hand rather than pulling in a serialisation
 * framework (see AGENTS.md), and Fabric/NeoForge builds cannot assume Gson is
 * on the classpath. This keeps that convention while making correct escaping
 * the default instead of something each call site has to remember.
 */
public final class ApiJson {

    private final StringBuilder sb = new StringBuilder(256);
    private boolean needsComma;

    public static ApiJson object() {
        ApiJson json = new ApiJson();
        json.sb.append('{');
        return json;
    }

    public ApiJson field(String name, String value) {
        separate();
        key(name);
        if (value == null) {
            sb.append("null");
        } else {
            writeString(value);
        }
        return this;
    }

    public ApiJson field(String name, long value) {
        separate();
        key(name);
        sb.append(value);
        return this;
    }

    public ApiJson field(String name, int value) {
        return field(name, (long) value);
    }

    public ApiJson field(String name, boolean value) {
        separate();
        key(name);
        sb.append(value);
        return this;
    }

    /** Writes a double, rounded to three decimals; non-finite becomes null. */
    public ApiJson field(String name, double value) {
        separate();
        key(name);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            sb.append("null");
        } else {
            sb.append(Math.round(value * 1000.0) / 1000.0);
        }
        return this;
    }

    /** Writes an already-rendered JSON fragment. The caller owns its validity. */
    public ApiJson raw(String name, String jsonFragment) {
        separate();
        key(name);
        sb.append(jsonFragment == null || jsonFragment.isBlank() ? "null" : jsonFragment);
        return this;
    }

    public ApiJson uuid(String name, java.util.UUID value) {
        return field(name, value == null ? null : value.toString());
    }

    public ApiJson strings(String name, java.util.List<String> values) {
        separate();
        key(name);
        sb.append('[');
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeString(values.get(i));
            }
        }
        sb.append(']');
        return this;
    }

    public String end() {
        sb.append('}');
        return sb.toString();
    }

    /** Joins pre-rendered objects into a JSON array. */
    public static String array(java.util.List<String> renderedObjects) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < renderedObjects.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(renderedObjects.get(i));
        }
        return out.append(']').toString();
    }

    private void separate() {
        if (needsComma) {
            sb.append(',');
        }
        needsComma = true;
    }

    private void key(String name) {
        writeString(name);
        sb.append(':');
    }

    private void writeString(String value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    // Escape every control character, plus the line and
                    // paragraph separators that break JSON once it is embedded
                    // in a <script> block.
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
