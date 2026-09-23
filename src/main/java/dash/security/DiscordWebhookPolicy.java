package dash.security;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

public final class DiscordWebhookPolicy {
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "discord.com", "discordapp.com", "canary.discord.com", "ptb.discord.com");

    private DiscordWebhookPolicy() {
    }

    public static URI requireAllowed(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !ALLOWED_HOSTS.contains(host)
                    || uri.getPort() != -1 && uri.getPort() != 443
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || !path.startsWith("/api/webhooks/") || path.length() <= "/api/webhooks/".length()) {
                throw new IllegalArgumentException("Only Discord HTTPS webhook URLs are allowed.");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Only Discord HTTPS webhook URLs are allowed.");
        }
    }

    public static boolean isAllowed(String value) {
        try {
            requireAllowed(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
