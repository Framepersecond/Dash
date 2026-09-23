package dash.ai;

import java.util.regex.Pattern;

public final class AiRedactor {
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(api[_-]?key|token|secret|password|authorization|cookie|session)(\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]{12,}");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])");
    private static final Pattern GOOGLE_KEY = Pattern.compile("\\bAIza[A-Za-z0-9_-]{20,}\\b");

    private AiRedactor() {
    }

    public static String redact(String input, int maxChars) {
        String value = input == null ? "" : input;
        value = SECRET_ASSIGNMENT.matcher(value).replaceAll("$1$2[REDACTED]");
        value = BEARER.matcher(value).replaceAll("Bearer [REDACTED]");
        value = GOOGLE_KEY.matcher(value).replaceAll("[REDACTED_GOOGLE_KEY]");
        value = EMAIL.matcher(value).replaceAll("[REDACTED_EMAIL]");
        value = IPV4.matcher(value).replaceAll("[REDACTED_IP]");
        int limit = Math.max(0, maxChars);
        return value.length() <= limit ? value : value.substring(0, limit) + "\n[TRUNCATED]";
    }
}
