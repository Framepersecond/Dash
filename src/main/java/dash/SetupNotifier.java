package dash;

import dash.security.DiscordWebhookPolicy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.kyori.adventure.audience.Audience;

public class SetupNotifier implements Listener {

    private final JavaPlugin plugin;
    private final WebAuth auth;
    private final RegistrationManager registrationManager;


    public SetupNotifier(JavaPlugin plugin, WebAuth auth, RegistrationManager registrationManager) {
        this.plugin = plugin;
        this.auth = auth;
        this.registrationManager = registrationManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!auth.isSetupRequired() || !player.isOp()) {
            return;
        }

        String code = registrationManager.generateCode(player.getUniqueId().toString(), player.getName(), "ADMIN", List.of());
        String setupUrl = buildSetupUrl(player, code);
        String baseUrl = buildSetupUrl(player, null);

        Component message = Component.text()
                .append(Component.text("[Dash] ", NamedTextColor.AQUA))
                .append(Component.text("Panel setup is still required. Click here to open setup.", NamedTextColor.YELLOW, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.openUrl(setupUrl))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to authenticate server!"))))
                .build();

        // Delay by one tick so Spigot has fully initialized the player's chat connection.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            Audience playerAudience = ((Dash) plugin).adventure().player(player);
            playerAudience.sendMessage(Component.empty());
            playerAudience.sendMessage(message);
            playerAudience.sendMessage(Component.text("Setup URL: " + setupUrl, NamedTextColor.GRAY));
            playerAudience.sendMessage(Component.text("Code expires in 5 minutes.", NamedTextColor.RED));
            playerAudience.sendMessage(Component.empty());
            playerAudience.sendMessage(Component.text("If clicking the link doesn't work:", NamedTextColor.GRAY));
            playerAudience.sendMessage(Component.text("1. Go to: ", NamedTextColor.GRAY)
                    .append(Component.text(baseUrl, NamedTextColor.WHITE)));
            playerAudience.sendMessage(Component.text("2. Enter code: ", NamedTextColor.GRAY)
                    .append(Component.text(code, NamedTextColor.GREEN, TextDecoration.BOLD)));
            playerAudience.sendMessage(Component.empty());
        }, 1L);

        WebActionLogger.log("SETUP_CODE_GENERATED", "Player " + player.getName() + " received setup link");
    }

    public void sendDiscordSetupNotificationIfConfigured() {
        String webhookUrl = plugin.getConfig().getString("setup-discord-webhook-url", "").trim();
        if (webhookUrl.isEmpty() || !DiscordWebhookPolicy.isAllowed(webhookUrl)) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String setupUrl = buildSetupUrl(null);
                String payload = "{\"content\":\"Dash setup required on server **" + escapeJson(Bukkit.getServer().getName())
                        + "**. Open: " + escapeJson(setupUrl) + "\"}";

                HttpURLConnection connection = (HttpURLConnection) DiscordWebhookPolicy.requireAllowed(webhookUrl).toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }

                int status = connection.getResponseCode();
                WebActionLogger.log("SETUP_DISCORD_NOTIFY", "Webhook status=" + status);
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to send setup webhook: " + ex.getMessage());
            }
        });
    }

    private String buildSetupUrl(String code) {
        return buildSetupUrl(null, code);
    }

    private String buildSetupUrl(Player player, String code) {
        return buildSetupUrl(plugin, player, code);
    }

    public static String buildSetupUrl(JavaPlugin plugin, Player player, String code) {
        String fallback = plugin.getConfig().getString("panel-url", "").trim();
        boolean sslEnabled = plugin.getConfig().getBoolean("ssl-enabled",
                plugin.getConfig().getBoolean("ssl.enabled", false));
        String host = plugin.getConfig().getString("server-ip", "").trim();

        if (host.isBlank() && player != null) {
            try {
                InetSocketAddress virtualHost = player.getVirtualHost();
                if (virtualHost != null && virtualHost.getHostString() != null && !virtualHost.getHostString().isBlank()) {
                    host = virtualHost.getHostString();
                }
            } catch (Throwable ignored) {
            }
        }

        if (host.isBlank()) {
            host = Bukkit.getIp();
        }
        if (host == null || host.isBlank()) {
            host = "localhost";
        }

        String base = "http://" + normalizeHost(host) + ":" + Dash.getWebPort() + "/setup";
        if (!fallback.isEmpty()) {
            String publicBase = normalizePublicBaseUrl(fallback, sslEnabled);
            base = publicBase.endsWith("/") ? publicBase + "setup" : publicBase + "/setup";
        }

        if (code == null || code.isBlank()) {
            return base;
        }

        return base + (base.contains("?") ? "&" : "?") + "code=" + code;
    }

    public static String buildReportUrl(JavaPlugin plugin, Player player, String token) {
        boolean sslEnabled = plugin.getConfig().getBoolean("ssl-enabled",
                plugin.getConfig().getBoolean("ssl.enabled", false));
        String explicit = plugin.getConfig().getString("report-url", "").trim();
        String base;
        if (!explicit.isBlank()) {
            base = normalizePublicBaseUrl(explicit, true);
        } else if (sslEnabled) {
            return "";
        } else {
            String host = plugin.getConfig().getString("server-ip", "").trim();
            if (host.isBlank() && player != null) {
                try {
                    InetSocketAddress virtualHost = player.getVirtualHost();
                    if (virtualHost != null) host = virtualHost.getHostString();
                } catch (Throwable ignored) {
                }
            }
            if (host.isBlank()) host = Bukkit.getIp();
            if (host == null || host.isBlank()) host = "localhost";
            host = normalizeHost(host);
            base = "http://" + addPortWhenRequired(host, Dash.getWebPort(), false);
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/report?token=" + token;
    }

    private static String addPortWhenRequired(String host, int port, boolean sslEnabled) {
        if (sslEnabled || host.matches(".*:\\d+$") || host.startsWith("[") && host.contains("]:")) return host;
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]:" + port : host + ":" + port;
    }

    private static String normalizeHost(String raw) {
        if (raw == null) {
            return "localhost";
        }
        String host = raw.trim();
        if (host.regionMatches(true, 0, "http://", 0, 7)) {
            host = host.substring(7);
        } else if (host.regionMatches(true, 0, "https://", 0, 8)) {
            host = host.substring(8);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        return host.isBlank() ? "localhost" : host;
    }

    private static String normalizePublicBaseUrl(String raw, boolean sslEnabled) {
        String url = raw == null ? "" : raw.trim();
        if (url.isBlank()) {
            return "";
        }
        if (sslEnabled && url.regionMatches(true, 0, "http://", 0, 7)) {
            url = "https://" + url.substring(7);
        } else if (!url.regionMatches(true, 0, "http://", 0, 7)
                && !url.regionMatches(true, 0, "https://", 0, 8)) {
            url = (sslEnabled ? "https://" : "http://") + url;
        }
        while (url.endsWith("/") && url.length() > "https://".length()) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
