package dash.web;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class PlayersPage {

    public static String render() {
        StringBuilder tableRows = new StringBuilder();

        for (Player p : Bukkit.getOnlinePlayers()) {
            Location loc = p.getLocation();
            String world = loc.getWorld() != null ? loc.getWorld().getName() : "Unknown";
            String coords = String.format("%.0f, %.0f, %.0f", loc.getX(), loc.getY(), loc.getZ());
            int ping = p.getPing();
            String uuid = p.getUniqueId().toString();
            String ip = p.getAddress() != null ? p.getAddress().getAddress().getHostAddress() : "Unknown";

            tableRows.append("<tr class=\"border-b border-white/5 hover:bg-white/5 transition-colors\">\n")
                    .append("<td class=\"px-4 py-3\">\n")
                    .append("<div class=\"flex items-center gap-3\">\n")
                    .append("<div class=\"h-8 w-8 rounded-full bg-sky-500/20 flex items-center justify-center text-sky-400 font-bold text-sm\">")
                    .append(p.getName().isEmpty() ? '?' : Character.toUpperCase(p.getName().charAt(0)))
                    .append("</div>\n")
                    .append("<div>\n")
                    .append("<p class=\"text-white font-medium\">").append(p.getName()).append("</p>\n")
                    .append("<p class=\"text-slate-500 text-xs font-mono\">").append(uuid.substring(0, 8))
                    .append("...</p>\n")
                    .append("</div>\n")
                    .append("</div>\n")
                    .append("</td>\n")
                    .append("<td class=\"px-4 py-3\"><span class=\"")
                    .append(ping < 100 ? "text-emerald-400" : ping < 200 ? "text-amber-400" : "text-rose-400")
                    .append("\">").append(ping).append("ms</span></td>\n")
                    .append("<td class=\"px-4 py-3 text-slate-400 font-mono text-sm\">").append(ip).append("</td>\n")
                    .append("<td class=\"px-4 py-3\">\n")
                    .append("<span class=\"text-slate-300\">").append(world).append("</span><br>\n")
                    .append("<span class=\"text-slate-500 text-xs font-mono\">").append(coords).append("</span>\n")
                    .append("</td>\n")
                    .append("<td class=\"px-4 py-3\">\n")
                    .append("<div class=\"flex items-center gap-2\">\n")
                    .append("<a href='/players/").append(p.getName())
                    .append("/profile' title='View Profile' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-purple-400 hover:bg-purple-500/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">person</span></a>\n")
                    .append("<a href='/players/").append(p.getName())
                    .append("/teleport' title='Teleport' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-primary hover:bg-primary/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">my_location</span></a>\n")
                    .append("<a href='/players/").append(p.getName())
                    .append("/inventory' title='View Inventory' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-amber-400 hover:bg-amber-500/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">inventory_2</span></a>\n")
                    .append("<form action='/action' method='post' class='inline' onsubmit=\"return confirm('Kick ")
                    .append(p.getName())
                    .append("?');\"><input type='hidden' name='action' value='kick'><input type='hidden' name='player' value='")
                    .append(p.getName()).append("'>")
                    .append("<button title='Kick' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-white hover:bg-white/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">logout</span></button></form>\n")
                    .append("<form action='/action' method='post' class='inline' onsubmit=\"return confirm('Ban ")
                    .append(p.getName())
                    .append("?');\"><input type='hidden' name='action' value='ban'><input type='hidden' name='player' value='")
                    .append(p.getName()).append("'>")
                    .append("<button title='Ban' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-rose-400 hover:bg-rose-500/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">block</span></button></form>\n")
                    .append("<form action='/action' method='post' class='inline'><input type='hidden' name='action' value='freeze'><input type='hidden' name='player' value='")
                    .append(p.getName()).append("'>")
                    .append("<button title='Freeze' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-blue-400 hover:bg-blue-500/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">ac_unit</span></button></form>\n")
                    .append("</div>\n")
                    .append("</td>\n")
                    .append("</tr>\n");
        }

        if (tableRows.length() == 0) {
            tableRows.append(
                    "<tr><td colspan='5' class='px-4 py-8 text-center text-slate-500'>No players online</td></tr>\n");
        }

        String content = HtmlTemplate.statsHeader() +
                "<main class=\"flex-1 p-6 overflow-auto\">\n" +
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden\">\n"
                +
                "<div class=\"flex items-center justify-between px-6 py-4 border-b border-white/5\">\n" +
                "<div class=\"flex items-center gap-3\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">group</span>\n" +
                "<h2 class=\"text-lg font-bold text-white\">Player Management</h2>\n" +
                "<span class=\"px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-400 text-xs font-mono\">"
                + Bukkit.getOnlinePlayers().size() + " online</span>\n" +
                "</div>\n" +
                "<div class=\"flex items-center gap-2\">\n" +
                "<input type=\"text\" id=\"player-search\" placeholder=\"Search players...\" class=\"bg-slate-800 border border-slate-600 rounded-lg px-4 py-2 text-sm text-white placeholder-slate-500 focus:border-primary outline-none w-64\">\n"
                +
                "</div>\n" +
                "</div>\n" +
                "<table class=\"w-full\">\n" +
                "<thead class=\"bg-white/5\">\n" +
                "<tr class=\"text-left text-xs font-medium text-slate-400 uppercase tracking-wider\">\n" +
                "<th class=\"px-4 py-3\">Player</th>\n" +
                "<th class=\"px-4 py-3\">Ping</th>\n" +
                "<th class=\"px-4 py-3\">IP</th>\n" +
                "<th class=\"px-4 py-3\">Location</th>\n" +
                "<th class=\"px-4 py-3\">Actions</th>\n" +
                "</tr>\n" +
                "</thead>\n" +
                "<tbody>\n" +
                tableRows.toString() +
                "</tbody>\n" +
                "</table>\n" +
                "</div>\n" +
                "</main>\n" +
                HtmlTemplate.statsScript() +
                "<script>\n" +
                "document.getElementById('player-search').addEventListener('input', function(e) {\n" +
                "  const search = e.target.value.toLowerCase();\n" +
                "  document.querySelectorAll('tbody tr').forEach(row => {\n" +
                "    const name = row.querySelector('td')?.textContent.toLowerCase() || '';\n" +
                "    row.style.display = name.includes(search) ? '' : 'none';\n" +
                "  });\n" +
                "});\n" +
                "</script>\n";

        return HtmlTemplate.page("Players", "/players", content);
    }

}
