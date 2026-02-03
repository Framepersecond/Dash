package dash.web;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class TeleportPage {

    public static String render(String targetPlayerName) {
        StringBuilder playersHtml = new StringBuilder();

        for (Player p : Bukkit.getOnlinePlayers()) {
            playersHtml.append("<form action='/action' method='post' class='inline'>\n")
                    .append("<input type='hidden' name='action' value='tp_player_to_player'>\n")
                    .append("<input type='hidden' name='target' value='").append(targetPlayerName).append("'>\n")
                    .append("<input type='hidden' name='destination' value='").append(p.getName()).append("'>\n")
                    .append("<button class='flex items-center gap-3 w-full p-3 rounded-lg bg-white/5 hover:bg-white/10 transition-colors text-left'>\n")
                    .append("<div class='h-8 w-8 rounded-full bg-sky-500/20 flex items-center justify-center text-sky-400 font-bold text-sm'>")
                    .append(Character.toUpperCase(p.getName().charAt(0))).append("</div>\n")
                    .append("<span class='text-white'>").append(p.getName()).append("</span>\n")
                    .append("</button></form>\n");
        }

        if (playersHtml.length() == 0) {
            playersHtml.append("<p class='text-slate-500 text-center py-4'>No other players online</p>\n");
        }

        String content = HtmlTemplate.statsHeader() +
                "<main class='flex-1 p-6 overflow-auto'>\n" +
                "<div class='max-w-2xl mx-auto'>\n" +
                "<div class='rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden'>\n"
                +
                "<div class='flex items-center gap-3 px-6 py-4 border-b border-white/5'>\n" +
                "<a href='/players' class='h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-white hover:bg-white/10 transition-all'>\n"
                +
                "<span class='material-symbols-outlined'>arrow_back</span>\n" +
                "</a>\n" +
                "<span class='material-symbols-outlined text-primary'>my_location</span>\n" +
                "<h2 class='text-lg font-bold text-white'>Teleport " + targetPlayerName + "</h2>\n" +
                "</div>\n" +

                "<div class='p-6'>\n" +
                "<h3 class='text-sm font-medium text-slate-400 mb-3 uppercase tracking-wider'>Teleport to Coordinates</h3>\n"
                +
                "<form action='/action' method='post' class='flex gap-2 items-end mb-6'>\n" +
                "<input type='hidden' name='action' value='tp_to_coords'>\n" +
                "<input type='hidden' name='player' value='" + targetPlayerName + "'>\n" +
                "<div class='flex-1'>\n" +
                "<label class='text-xs text-slate-500 block mb-1'>X</label>\n" +
                "<input type='number' name='x' placeholder='0' required class='w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white focus:border-primary outline-none'>\n"
                +
                "</div>\n" +
                "<div class='flex-1'>\n" +
                "<label class='text-xs text-slate-500 block mb-1'>Y</label>\n" +
                "<input type='number' name='y' placeholder='64' required class='w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white focus:border-primary outline-none'>\n"
                +
                "</div>\n" +
                "<div class='flex-1'>\n" +
                "<label class='text-xs text-slate-500 block mb-1'>Z</label>\n" +
                "<input type='number' name='z' placeholder='0' required class='w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white focus:border-primary outline-none'>\n"
                +
                "</div>\n" +
                "<button class='px-4 py-2 rounded-lg bg-primary text-black font-semibold hover:bg-white transition-colors text-sm'>Teleport</button>\n"
                +
                "</form>\n" +

                "<h3 class='text-sm font-medium text-slate-400 mb-3 uppercase tracking-wider'>Teleport to Player</h3>\n"
                +
                "<div class='flex flex-col gap-2 max-h-64 overflow-y-auto console-scrollbar'>\n" +
                playersHtml.toString() +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "</main>\n" +
                HtmlTemplate.statsScript();

        return HtmlTemplate.page("Teleport " + targetPlayerName, "/players", content);
    }
}
