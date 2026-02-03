package dash.web;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public class InventoryPage {

    public static String render(String playerName) {
        Player player = Bukkit.getPlayerExact(playerName);

        if (player == null) {
            return HtmlTemplate.page("Player Not Found", "/players",
                    HtmlTemplate.statsHeader() +
                            "<main class='flex-1 p-6 flex items-center justify-center'>" +
                            "<div class='text-center'>" +
                            "<span class='material-symbols-outlined text-6xl text-slate-600 mb-4'>person_off</span>" +
                            "<h2 class='text-xl font-bold text-white mb-2'>Player Not Found</h2>" +
                            "<p class='text-slate-400 mb-4'>Player '" + playerName + "' is not online.</p>" +
                            "<a href='/players' class='px-4 py-2 rounded-lg bg-primary text-black font-semibold hover:bg-white transition-colors'>Back to Players</a>"
                            +
                            "</div></main>");
        }

        PlayerInventory inv = player.getInventory();

        StringBuilder inventoryHtml = new StringBuilder();

        inventoryHtml.append("<div class='mb-6'>\n");
        inventoryHtml
                .append("<h3 class='text-sm font-medium text-slate-400 mb-2 uppercase tracking-wider'>Armor</h3>\n");
        inventoryHtml.append("<div class='flex gap-2'>\n");
        ItemStack[] armor = inv.getArmorContents();
        String[] armorNames = { "Boots", "Leggings", "Chestplate", "Helmet" };
        for (int i = armor.length - 1; i >= 0; i--) {
            inventoryHtml.append(renderSlot(armor[i], armorNames[i]));
        }
        inventoryHtml.append(renderSlot(inv.getItemInOffHand(), "Offhand"));
        inventoryHtml.append("</div>\n");
        inventoryHtml.append("</div>\n");

        inventoryHtml.append("<div class='mb-6'>\n");
        inventoryHtml.append(
                "<h3 class='text-sm font-medium text-slate-400 mb-2 uppercase tracking-wider'>Inventory</h3>\n");
        inventoryHtml.append("<div class='grid grid-cols-9 gap-1'>\n");
        for (int i = 9; i < 36; i++) {
            inventoryHtml.append(renderSlot(inv.getItem(i), null));
        }
        inventoryHtml.append("</div>\n");
        inventoryHtml.append("</div>\n");

        inventoryHtml.append("<div>\n");
        inventoryHtml
                .append("<h3 class='text-sm font-medium text-slate-400 mb-2 uppercase tracking-wider'>Hotbar</h3>\n");
        inventoryHtml.append("<div class='grid grid-cols-9 gap-1'>\n");
        for (int i = 0; i < 9; i++) {
            inventoryHtml.append(renderSlot(inv.getItem(i), "Slot " + (i + 1)));
        }
        inventoryHtml.append("</div>\n");
        inventoryHtml.append("</div>\n");

        String content = HtmlTemplate.statsHeader() +
                "<main class='flex-1 p-6 overflow-auto'>\n" +
                "<div class='max-w-2xl mx-auto'>\n" +
                "<div class='rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden'>\n"
                +
                "<div class='flex items-center justify-between px-6 py-4 border-b border-white/5'>\n" +
                "<div class='flex items-center gap-3'>\n" +
                "<a href='/players/" + playerName
                + "/profile' class='h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-white hover:bg-white/10 transition-all'>\n"
                +
                "<span class='material-symbols-outlined'>arrow_back</span>\n" +
                "</a>\n" +
                "<div class='h-10 w-10 rounded-full bg-sky-500/20 flex items-center justify-center text-sky-400 font-bold'>"
                + Character.toUpperCase(playerName.charAt(0)) + "</div>\n" +
                "<div>\n" +
                "<h2 class='text-lg font-bold text-white'>" + playerName + "'s Inventory</h2>\n" +
                "<p class='text-slate-500 text-sm'>" + player.getWorld().getName() + "</p>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class='flex gap-2'>\n" +
                "<a href='/players/" + playerName
                + "/enderchest' class='px-3 py-1 rounded-lg bg-purple-500/20 text-purple-400 hover:bg-purple-500/30 transition-colors text-sm font-medium'>Ender Chest</a>\n"
                +
                "</div>\n" +
                "</div>\n" +
                "<div class='p-6'>\n" +
                inventoryHtml.toString() +
                "</div>\n" +
                "<div class='p-6 border-t border-white/5'>\n" +
                "<h3 class='text-sm font-medium text-slate-400 mb-3 uppercase tracking-wider'>Add Item</h3>\n"
                +
                "<form action='/action' method='post' class='flex gap-2 items-end'>\n" +
                "<input type='hidden' name='action' value='give_item'>\n" +
                "<input type='hidden' name='player' value='" + playerName + "'>\n" +
                "<div class='flex-1'>\n" +
                "<label class='text-xs text-slate-500 block mb-1'>Material</label>\n" +
                "<input type='text' name='material' placeholder='diamond_sword' required class='w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white placeholder-slate-500 focus:border-primary outline-none'>\n"
                +
                "</div>\n" +
                "<div class='w-20'>\n" +
                "<label class='text-xs text-slate-500 block mb-1'>Amount</label>\n" +
                "<input type='number' name='amount' value='1' min='1' max='64' class='w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white focus:border-primary outline-none'>\n"
                +
                "</div>\n" +
                "<button class='px-4 py-2 rounded-lg bg-emerald-500 text-white font-semibold hover:bg-emerald-600 transition-colors text-sm'>Give</button>\n"
                +
                "</form>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "</main>\n" +
                HtmlTemplate.statsScript();

        return HtmlTemplate.page(playerName + "'s Inventory", "/players", content);
    }

    public static String renderEnderChest(String playerName) {
        Player player = Bukkit.getPlayerExact(playerName);

        if (player == null) {
            return HtmlTemplate.page("Player Not Found", "/players",
                    HtmlTemplate.statsHeader() +
                            "<main class='flex-1 p-6 flex items-center justify-center'>" +
                            "<div class='text-center'>" +
                            "<span class='material-symbols-outlined text-6xl text-slate-600 mb-4'>person_off</span>" +
                            "<h2 class='text-xl font-bold text-white mb-2'>Player Not Found</h2>" +
                            "<p class='text-slate-400 mb-4'>Player '" + playerName + "' is not online.</p>" +
                            "<a href='/players' class='px-4 py-2 rounded-lg bg-primary text-black font-semibold hover:bg-white transition-colors'>Back to Players</a>"
                            +
                            "</div></main>");
        }

        StringBuilder enderChestHtml = new StringBuilder();
        enderChestHtml.append("<div class='grid grid-cols-9 gap-1'>\n");
        for (int i = 0; i < 27; i++) {
            ItemStack item = player.getEnderChest().getItem(i);
            enderChestHtml.append(renderSlot(item, null));
        }
        enderChestHtml.append("</div>\n");

        String content = HtmlTemplate.statsHeader() +
                "<main class='flex-1 p-6 overflow-auto'>\n" +
                "<div class='max-w-2xl mx-auto'>\n" +
                "<div class='rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden'>\n"
                +
                "<div class='flex items-center justify-between px-6 py-4 border-b border-white/5'>\n" +
                "<div class='flex items-center gap-3'>\n" +
                "<a href='/players/" + playerName
                + "/profile' class='h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-white hover:bg-white/10 transition-all'>\n"
                +
                "<span class='material-symbols-outlined'>arrow_back</span>\n" +
                "</a>\n" +
                "<div class='h-10 w-10 rounded-full bg-purple-500/20 flex items-center justify-center text-purple-400'>\n"
                +
                "<span class='material-symbols-outlined'>package_2</span>\n" +
                "</div>\n" +
                "<div>\n" +
                "<h2 class='text-lg font-bold text-white'>" + playerName + "'s Ender Chest</h2>\n" +
                "<p class='text-slate-500 text-sm'>27 slots</p>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class='flex gap-2'>\n" +
                "<a href='/players/" + playerName
                + "/inventory' class='px-3 py-1 rounded-lg bg-sky-500/20 text-sky-400 hover:bg-sky-500/30 transition-colors text-sm font-medium'>Inventory</a>\n"
                +
                "</div>\n" +
                "</div>\n" +
                "<div class='p-6'>\n" +
                enderChestHtml.toString() +
                "</div>\n" +
                "<div class='p-6 border-t border-white/5'>\n" +
                "<h3 class='text-sm font-medium text-slate-400 mb-3 uppercase tracking-wider'>Add Item</h3>\n"
                +
                "<form action='/action' method='post' class='flex gap-2 items-end'>\n" +
                "<input type='hidden' name='action' value='give_enderchest'>\n" +
                "<input type='hidden' name='player' value='" + playerName + "'>\n" +
                "<div class='flex-1'>\n" +
                "<label class='text-xs text-slate-500 block mb-1'>Material</label>\n" +
                "<input type='text' name='material' placeholder='diamond' required class='w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white placeholder-slate-500 focus:border-purple-400 outline-none'>\n"
                +
                "</div>\n" +
                "<div class='w-20'>\n" +
                "<label class='text-xs text-slate-500 block mb-1'>Amount</label>\n" +
                "<input type='number' name='amount' value='1' min='1' max='64' class='w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white focus:border-purple-400 outline-none'>\n"
                +
                "</div>\n" +
                "<button class='px-4 py-2 rounded-lg bg-purple-500 text-white font-semibold hover:bg-purple-600 transition-colors text-sm'>Give</button>\n"
                +
                "</form>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "</main>\n" +
                HtmlTemplate.statsScript();

        return HtmlTemplate.page(playerName + "'s Ender Chest", "/players", content);
    }

    private static String renderSlot(ItemStack item, String tooltip) {
        if (item == null || item.getType() == Material.AIR) {
            return "<div class='w-12 h-12 rounded-lg bg-slate-800/60 border border-slate-700/50 flex items-center justify-center'"
                    +
                    (tooltip != null ? " title='" + tooltip + "'" : "") + "></div>\n";
        }

        String itemName = formatMaterialName(item.getType().name());
        int amount = item.getAmount();
        String materialKey = item.getType().getKey().getKey();

        String displayTooltip = tooltip != null ? tooltip + ": " + itemName : itemName;
        if (amount > 1)
            displayTooltip += " x" + amount;

        String iconUrl = "https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.1/assets/minecraft/textures/item/"
                + materialKey + ".png";
        String blockUrl = "https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.1/assets/minecraft/textures/block/"
                + materialKey + ".png";
        String color = getItemColor(item.getType());

        return "<div class='w-12 h-12 rounded-lg " + color
                + " border border-slate-600/50 flex items-center justify-center relative group cursor-pointer hover:scale-110 hover:z-10 transition-transform' title='"
                + displayTooltip + "'>\n" +
                "<img src='" + iconUrl + "' alt='" + itemName + "' class='w-8 h-8 pixelated' onerror=\"this.src='"
                + blockUrl
                + "';this.onerror=function(){this.style.display='none';this.nextElementSibling.style.display='flex';}\">\n"
                +
                "<span class='text-[9px] font-bold text-white text-center hidden items-center justify-center absolute inset-0 leading-tight px-0.5'>"
                + itemName.substring(0, Math.min(itemName.length(), 8)) + "</span>\n" +
                (amount > 1
                        ? "<span class='absolute bottom-0 right-0.5 text-xs font-bold text-white drop-shadow-[0_1px_2px_rgba(0,0,0,1)]'>"
                                + amount + "</span>\n"
                        : "")
                +
                "</div>\n";
    }

    private static String formatMaterialName(String name) {
        String[] parts = name.toLowerCase().split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (result.length() > 0)
                result.append(" ");
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }

    private static String getItemColor(Material material) {
        String name = material.name().toLowerCase();
        if (name.contains("netherite"))
            return "bg-gradient-to-br from-slate-700 to-slate-900";
        if (name.contains("diamond"))
            return "bg-gradient-to-br from-cyan-500/40 to-cyan-700/40";
        if (name.contains("gold"))
            return "bg-gradient-to-br from-yellow-500/40 to-amber-600/40";
        if (name.contains("iron"))
            return "bg-gradient-to-br from-slate-400/40 to-slate-500/40";
        if (name.contains("emerald"))
            return "bg-gradient-to-br from-emerald-500/40 to-emerald-700/40";
        if (name.contains("redstone"))
            return "bg-gradient-to-br from-rose-500/40 to-rose-700/40";
        if (name.contains("lapis"))
            return "bg-gradient-to-br from-blue-500/40 to-blue-700/40";
        if (name.contains("coal"))
            return "bg-slate-800";
        if (name.contains("leather"))
            return "bg-gradient-to-br from-amber-700/40 to-amber-800/40";
        if (name.contains("enchanted") || name.contains("book"))
            return "bg-gradient-to-br from-purple-500/40 to-purple-700/40";
        if (material.isEdible())
            return "bg-gradient-to-br from-orange-500/30 to-orange-600/30";
        return "bg-slate-700/50";
    }
}
