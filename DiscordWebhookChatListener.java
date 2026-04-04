package dash;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Sends player chat lines to Discord webhooks subscribed to the "chat" event.
 */
public class DiscordWebhookChatListener implements Listener {

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        DiscordWebhookManager manager = Dash.getDiscordWebhookManager();
        if (manager == null) {
            return;
        }

        String player = event.getPlayer().getName();
        String world = event.getPlayer().getWorld().getName();
        String message = event.getMessage();

        manager.dispatch(
                DiscordWebhookManager.EVENT_CHAT,
                "[" + world + "] <" + player + "> " + message);
    }
}

