package dash;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reads Discord webhook configurations from config.yml and dispatches
 * JSON payloads asynchronously to webhooks subscribed to a given event.
 *
 * Config structure:
 * <pre>
 * discord-webhooks:
 *   - url: "https://discord.com/api/webhooks/..."
 *     events:
 *       - audit
 *       - chat
 *       - server_start_stop
 *       - console_warnings
 * </pre>
 */
public class DiscordWebhookManager {

    private final JavaPlugin plugin;
    private final List<WebhookEntry> webhooks = new CopyOnWriteArrayList<>();

    public static final String EVENT_AUDIT = "audit";
    public static final String EVENT_CHAT = "chat";
    public static final String EVENT_SERVER_START_STOP = "server_start_stop";
    public static final String EVENT_CONSOLE_WARNINGS = "console_warnings";

    public static final String[] ALL_EVENTS = {
            EVENT_AUDIT, EVENT_CHAT, EVENT_SERVER_START_STOP, EVENT_CONSOLE_WARNINGS
    };

    public DiscordWebhookManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Re-reads webhook list from config.yml. */
    public void reload() {
        webhooks.clear();
        List<?> raw = plugin.getConfig().getList("discord-webhooks");
        if (raw == null) return;

        for (Object obj : raw) {
            if (obj instanceof Map<?, ?> map) {
                String url = String.valueOf(map.get("url")).trim();
                if (url.isEmpty() || url.equals("null")) continue;
                List<String> events = new ArrayList<>();
                Object evtObj = map.get("events");
                if (evtObj instanceof List<?> evtList) {
                    for (Object e : evtList) {
                        events.add(String.valueOf(e).trim().toLowerCase());
                    }
                }
                webhooks.add(new WebhookEntry(url, events));
            }
        }
    }

    /** Returns the current webhook list (read-only snapshot). */
    public List<WebhookEntry> getWebhooks() {
        return Collections.unmodifiableList(webhooks);
    }

    /**
     * Saves the given list of webhooks to config.yml and reloads in-memory state.
     */
    public void saveWebhooks(List<WebhookEntry> entries) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (WebhookEntry entry : entries) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("url", entry.url());
            map.put("events", new ArrayList<>(entry.events()));
            serialized.add(map);
        }
        plugin.getConfig().set("discord-webhooks", serialized);
        plugin.saveConfig();
        reload();
    }

    /**
     * Send a message to all webhooks subscribed to the given event.
     * Runs asynchronously on the Bukkit scheduler.
     */
    public void dispatch(String event, String message) {
        if (webhooks.isEmpty()) {
            return;
        }
        String normalizedEvent = event == null ? "" : event.toLowerCase(Locale.ROOT);
        Runnable task = () -> {
            for (WebhookEntry entry : webhooks) {
                if (entry.events().contains(normalizedEvent)) {
                    sendPayload(entry.url(), message);
                }
            }
        };
        runDispatch(task);
    }

    /**
     * Send a rich embed to all webhooks subscribed to the given event.
     */
    public void dispatchEmbed(String event, String title, String description, int color) {
        if (webhooks.isEmpty()) {
            return;
        }
        String normalizedEvent = event == null ? "" : event.toLowerCase(Locale.ROOT);
        String payload = "{\"embeds\":[{\"title\":\"" + escapeJson(title)
                + "\",\"description\":\"" + escapeJson(description)
                + "\",\"color\":" + color + "}]}";
        Runnable task = () -> {
            for (WebhookEntry entry : webhooks) {
                if (entry.events().contains(normalizedEvent)) {
                    sendRawPayload(entry.url(), payload);
                }
            }
        };
        runDispatch(task);
    }


    private void runDispatch(Runnable task) {
        // During shutdown Paper rejects scheduler registrations for disabled plugins.
        // Fall back to direct execution so stop/warning webhooks do not crash the plugin.
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            task.run();
        }
    }

    private void sendPayload(String webhookUrl, String message) {
        String payload = "{\"content\":\"" + escapeJson(message) + "\"}";
        sendRawPayload(webhookUrl, payload);
    }

    private void sendRawPayload(String webhookUrl, String jsonPayload) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                plugin.getLogger().warning("[Webhook] Non-2xx response (" + status + ") from: " + webhookUrl);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[Webhook] Failed to send: " + ex.getMessage());
        }
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    public record WebhookEntry(String url, List<String> events) {
        public boolean subscribedTo(String event) {
            return events.contains(event.toLowerCase());
        }
    }
}

