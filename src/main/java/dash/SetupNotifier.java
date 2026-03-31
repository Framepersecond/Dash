package dash;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
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

        Component message = Component.text("[Dash] ", NamedTextColor.AQUA)
                .append(Component.text("Panel setup is still required. Click here to open setup.", NamedTextColor.YELLOW)
                        .decorate(TextDecoration.BOLD)
                        .clickEvent(ClickEvent.openUrl(setupUrl)));

        player.sendMessage(Component.empty());
        player.sendMessage(message);
        player.sendMessage(Component.text("Setup URL: " + setupUrl, NamedTextColor.GRAY));
        player.sendMessage(Component.text("Code expires in 5 minutes.", NamedTextColor.RED));

        WebActionLogger.log("SETUP_CODE_GENERATED", "Player " + player.getName() + " received setup link");
    }

    public void sendDiscordSetupNotificationIfConfigured() {
        String webhookUrl = plugin.getConfig().getString("setup-discord-webhook-url", "").trim();
        if (webhookUrl.isEmpty()) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String setupUrl = buildSetupUrl(null);
                String payload = "{\"content\":\"Dash setup required on server **" + escapeJson(Bukkit.getServer().getName())
                        + "**. Open: " + escapeJson(setupUrl) + "\"}";

                HttpURLConnection connection = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
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
        String host = "";

        if (player != null) {
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

        String base = "http://" + host + ":" + Dash.getWebPort() + "/setup";
        if (!fallback.isEmpty()) {
            base = fallback.endsWith("/") ? fallback + "setup" : fallback + "/setup";
        }

        if (code == null || code.isBlank()) {
            return base;
        }

        return base + (base.contains("?") ? "&" : "?") + "code=" + code;
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

