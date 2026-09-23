package dash;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class DashIngameUi implements Listener {
    private static final String TITLE = "Dash Main Admin";
    private static final String CONFIRM_TITLE = "Confirm Dash Restart";
    private final Dash plugin;

    public DashIngameUi(Dash plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (!authorized(player)) {
            player.sendMessage(Component.text("This UI is restricted to the Minecraft account linked to Dash MAIN_ADMIN.",
                    NamedTextColor.RED));
            return;
        }
        Inventory inventory = Bukkit.createInventory(null, 27, Component.text(TITLE));
        fill(inventory);
        inventory.setItem(10, item(Material.LIME_CONCRETE, "Server online",
                "TPS: " + String.format("%.1f", Bukkit.getTPS()[0]), "Uptime management is available in Dash."));
        inventory.setItem(12, item(Material.PLAYER_HEAD, "Online players",
                Bukkit.getOnlinePlayers().size() + " / " + Bukkit.getMaxPlayers(), "Click to refresh this view."));
        inventory.setItem(14, item(Material.WRITABLE_BOOK, "Tickets and reports",
                "Open the private staff inbox in your browser.", "Public reports never gain dashboard access."));
        inventory.setItem(16, item(Material.COMPARATOR, "Open web dashboard",
                "Receive a clickable HTTPS/HTTP panel link in chat."));
        inventory.setItem(20, item(Material.CLOCK, "Refresh", "Reload live server information."));
        inventory.setItem(24, item(Material.REDSTONE_BLOCK, "Restart server",
                "Requires a second explicit confirmation.", "All actions are written to the Dash audit log."));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().title().toString();
        boolean main = title.contains(TITLE);
        boolean confirm = title.contains(CONFIRM_TITLE);
        if (!main && !confirm) return;
        event.setCancelled(true);
        if (!authorized(player)) {
            player.closeInventory();
            player.sendMessage(Component.text("Dash MAIN_ADMIN link verification failed.", NamedTextColor.RED));
            return;
        }
        int slot = event.getRawSlot();
        if (main) {
            if (slot == 12 || slot == 20) open(player);
            if (slot == 14) sendPanelLink(player, "/staff");
            if (slot == 16) sendPanelLink(player, "/");
            if (slot == 24) openRestartConfirmation(player);
            return;
        }
        if (slot == 11) {
            player.closeInventory();
            WebActionLogger.log("INGAME_UI_RESTART", "linkedMainAdmin=" + player.getName());
            Bukkit.broadcast(Component.text("[Dash] Server restart requested by MAIN_ADMIN.", NamedTextColor.YELLOW));
            Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.spigot().restart(), 40L);
        } else if (slot == 15) {
            open(player);
        }
    }

    private void openRestartConfirmation(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, Component.text(CONFIRM_TITLE));
        fill(inventory);
        inventory.setItem(11, item(Material.LIME_CONCRETE, "Confirm restart",
                "Restart the server in two seconds."));
        inventory.setItem(15, item(Material.RED_CONCRETE, "Cancel", "Return without changing the server."));
        player.openInventory(inventory);
    }

    private void sendPanelLink(Player player, String path) {
        String configured = plugin.getConfig().getString("panel-url", "").trim();
        String base;
        if (!configured.isBlank()) {
            base = configured.replaceAll("/+$", "");
        } else {
            String host = plugin.getConfig().getString("server-ip", "").trim();
            if (host.isBlank()) host = Bukkit.getIp();
            if (host == null || host.isBlank()) host = "localhost";
            boolean ssl = plugin.getConfig().getBoolean("ssl-enabled", plugin.getConfig().getBoolean("ssl.enabled", false));
            base = (ssl ? "https://" : "http://") + host.replaceFirst("^https?://", "") + ":" + Dash.getWebPort();
        }
        String url = base + path;
        player.closeInventory();
        player.sendMessage(Component.text("[Dash] Open " + ("/staff".equals(path) ? "Tickets" : "Dashboard"),
                NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(url)));
    }

    private boolean authorized(Player player) {
        try {
            return new WebAuth(plugin).isLinkedMainAdmin(player.getName(), player.getUniqueId().toString());
        } catch (Exception ex) {
            plugin.getLogger().warning("Dash in-game UI authorization failed safely: " + ex.getMessage());
            return false;
        }
    }

    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.WHITE));
        meta.lore(List.of(lore).stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private static void fill(Inventory inventory) {
        ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, pane);
    }
}
