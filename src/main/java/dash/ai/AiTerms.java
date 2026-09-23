package dash.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

public final class AiTerms {
    public static final String VERSION = "1.0";
    public static final String TITLE = "Dash AI and Agentic Actions Terms v" + VERSION;
    public static final List<String> CLAUSES = List.of(
            "You confirm that you are authorized to operate this server and are at least 18 years old.",
            "Selected prompts and sanitized server context are processed by Google under the applicable Gemini API terms.",
            "You will not submit secrets or unnecessary personal, confidential or unlawful data.",
            "The API-key owner is responsible for quota, billing, restrictions and key security.",
            "AI output can be incomplete or incorrect and must be reviewed before use.",
            "Approved actions can change gameplay, availability, configuration and stored data.",
            "Previews and restore points reduce risk but cannot guarantee recovery.",
            "Dash AI may not be used for harassment, surveillance abuse, unauthorized access or policy evasion.",
            "Local chats are retained for 30 days; administrative action audits follow Dash audit retention.",
            "Disabling AI stops provider calls and agent jobs; consent can be revoked and local chats deleted.");
    public static final String DIGEST = digest(TITLE + "\n" + String.join("\n", CLAUSES));

    private AiTerms() {
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash AI terms.", ex);
        }
    }
}
