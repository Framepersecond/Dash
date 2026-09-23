package dash.web;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import dash.Dash;
import dash.data.PlayerDataManager;
import dash.WebAuth;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class PlayersPage {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    public static String render(String query, WebAuth auth) {
        String tab = getQueryParam(query, "tab");
        if (tab == null || tab.isEmpty()) {
            tab = "online";
        }
        
        String pageStr = getQueryParam(query, "page");
        int page = 1;
        if (pageStr != null && !pageStr.isEmpty()) {
            try {
                page = Integer.parseInt(pageStr);
                if (page < 1) page = 1;
            } catch (NumberFormatException ignored) {}
        }
        
        String q = getQueryParam(query, "q");
        if (q == null) q = "";
        
        boolean canModerate = HtmlTemplate.can("dash.web.players.moderate");
        boolean canKick = HtmlTemplate.can("dash.web.players.kick");
        boolean canBan = HtmlTemplate.can("dash.web.players.ban");
        boolean canInventoryWrite = HtmlTemplate.can("dash.web.players.inventory.write");
        boolean canWhitelist = HtmlTemplate.can("dash.web.whitelist.manage");

        PlayerDataManager pdm = Dash.getPlayerDataManager();

        // 1. Fetch Online Players list
        List<Player> onlinePlayers = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (q.isEmpty() || p.getName().toLowerCase().contains(q.toLowerCase()) || p.getUniqueId().toString().contains(q)) {
                onlinePlayers.add(p);
            }
        }

        // 2. Fetch Offline Players from database
        List<PlayerDataManager.PlayerInfo> offlinePlayers = new ArrayList<>();
        int totalOffline = 0;
        int limit = 25;
        int offset = (page - 1) * limit;

        if (pdm != null) {
            List<PlayerDataManager.PlayerInfo> dbPlayers = pdm.searchPlayers(q, 300, 0);
            for (PlayerDataManager.PlayerInfo dbP : dbPlayers) {
                if (Bukkit.getPlayerExact(dbP.name()) == null) {
                    offlinePlayers.add(dbP);
                }
            }
            
            totalOffline = offlinePlayers.size();
            int toIndex = Math.min(offset + limit, totalOffline);
            if (offset < totalOffline) {
                offlinePlayers = offlinePlayers.subList(offset, toIndex);
            } else {
                offlinePlayers = List.of();
            }
        }

        int totalPages = (int) Math.ceil((double) totalOffline / (double) limit);
        if (totalPages < 1) totalPages = 1;

        // Render Online Rows
        StringBuilder onlineRows = new StringBuilder();
        for (Player p : onlinePlayers) {
            Location loc = p.getLocation();
            String world = loc.getWorld() != null ? loc.getWorld().getName() : "Unknown";
            String coords = String.format("%.0f, %.0f, %.0f", loc.getX(), loc.getY(), loc.getZ());
            int ping = p.getPing();
            String uuid = p.getUniqueId().toString();
            String linkedUser = linkedDashUser(auth, p.getName(), uuid);
            String ip = p.getAddress() != null ? p.getAddress().getAddress().getHostAddress() : "Unknown";

            onlineRows.append("<tr class=\"border-b border-white/5 hover:bg-white/5 transition-colors\">\n")
                    .append("<td class=\"px-4 py-3 whitespace-nowrap\">\n")
                    .append("<div class=\"flex items-center gap-3\">\n")
                    .append("<button type='button' class='skin-preview h-10 w-10 rounded-lg overflow-hidden bg-sky-500/20 flex items-center justify-center text-sky-300 font-bold' data-skin-uuid='")
                    .append(uuid).append("' data-skin-name='").append(escapeHtml(p.getName())).append("' aria-label='Preview skin for ")
                    .append(escapeHtml(p.getName())).append("'><img src='https://crafatar.com/avatars/").append(uuid)
                    .append("?size=64&overlay' alt='' loading='lazy' class='h-full w-full image-render-pixel' onerror=\"this.hidden=true;this.nextElementSibling.hidden=false\"><span hidden>")
                    .append(p.getName().isEmpty() ? '?' : Character.toUpperCase(p.getName().charAt(0))).append("</span></button>\n")
                    .append("<div>\n")
                    .append("<p class=\"text-white font-medium\">").append(p.getName()).append("</p>\n")
                    .append(linkedUser.isEmpty() ? "" : "<a href='/users' class='inline-flex mt-1 px-2 py-1 rounded-lg border border-cyan-400/25 bg-cyan-500/10 text-cyan-200 text-[10px] font-bold' title='Manage linked Dash user'>Dash linked: " + escapeHtml(linkedUser) + "</a>\n")
                    .append("<p class=\"text-slate-500 text-xs font-mono\">").append(uuid.substring(0, 8))
                    .append("...</p>\n")
                    .append("</div>\n")
                    .append("</div>\n")
                    .append("</td>\n")
                    .append("<td class=\"px-4 py-3 whitespace-nowrap\"><span class=\"")
                    .append(ping < 100 ? "text-emerald-400" : ping < 200 ? "text-amber-400" : "text-rose-400")
                    .append("\">").append(ping).append("ms</span></td>\n")
                    .append("<td class=\"px-4 py-3 text-slate-400 font-mono text-sm whitespace-nowrap\">").append(ip).append("</td>\n")
                    .append("<td class=\"px-4 py-3 whitespace-nowrap\">\n")
                    .append("<span class=\"text-slate-300\">").append(world).append("</span><br>\n")
                    .append("<span class=\"text-slate-500 text-xs font-mono\">").append(coords).append("</span>\n")
                    .append("</td>\n")
                    .append("<td class=\"px-4 py-3 whitespace-nowrap\">\n")
                    .append("<div class=\"flex items-center gap-2\">\n")
                    .append("<a href='/players/").append(p.getName())
                    .append("/profile' title='View Profile' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-purple-400 hover:bg-purple-500/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">person</span></a>\n")
                    .append("<a href='/players/").append(p.getName())
                    .append("/inventory' title='View Inventory' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-amber-400 hover:bg-amber-500/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">inventory_2</span></a>\n");

            if (canModerate) {
                onlineRows.append("<a href='/players/").append(p.getName())
                        .append("/teleport' title='Teleport' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-primary hover:bg-primary/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">my_location</span></a>\n");
            }

            if (canKick) {
                onlineRows.append("<form action='/action' method='post' class='inline' onsubmit=\"return confirm('Kick ")
                        .append(p.getName())
                        .append("?');\"><input type='hidden' name='action' value='kick'><input type='hidden' name='player' value='")
                        .append(p.getName()).append("'>")
                        .append("<button title='Kick' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-white hover:bg-white/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">logout</span></button></form>\n");
            }

            if (canBan) {
                onlineRows.append("<form action='/action' method='post' class='inline' onsubmit=\"return confirm('Ban ")
                        .append(p.getName())
                        .append("?');\"><input type='hidden' name='action' value='ban'><input type='hidden' name='player' value='")
                        .append(p.getName()).append("'>")
                        .append("<button title='Ban' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-rose-400 hover:bg-rose-500/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">block</span></button></form>\n");
            }

            if (canModerate) {
                onlineRows.append("<form action='/action' method='post' class='inline'><input type='hidden' name='action' value='freeze'><input type='hidden' name='player' value='")
                        .append(p.getName()).append("'>")
                        .append("<button title='Freeze' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-blue-400 hover:bg-blue-500/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">ac_unit</span></button></form>\n");
            }

            if (canInventoryWrite) {
                onlineRows.append("<a href='/players/").append(p.getName())
                        .append("/enderchest' title='Ender Chest' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-cyan-300 hover:bg-cyan-500/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">inventory</span></a>\n");
            }

            onlineRows.append("</div>\n")
                    .append("</td>\n")
                    .append("</tr>\n");
        }

        if (onlineRows.length() == 0) {
            onlineRows.append(
                    "<tr><td colspan='5' class='px-4 py-8 text-center text-slate-500 whitespace-nowrap'>No players online</td></tr>\n");
        }

        // Render Offline Rows
        StringBuilder offlineRows = new StringBuilder();
        for (PlayerDataManager.PlayerInfo dbP : offlinePlayers) {
            String uuid = dbP.uuid();
            String name = dbP.name();
            String linkedUser = linkedDashUser(auth, name, uuid);
            String lastSeen = DATE_FORMAT.format(new Date(dbP.lastJoin()));
            String playtime = dbP.getFormattedPlaytime();

            boolean isWhitelisted = false;
            try {
                isWhitelisted = Bukkit.getOfflinePlayer(UUID.fromString(uuid)).isWhitelisted();
            } catch (Exception ignored) {}

            offlineRows.append("<tr class=\"border-b border-white/5 hover:bg-white/5 transition-colors\">\n")
                    .append("<td class=\"px-4 py-3 whitespace-nowrap\">\n")
                    .append("<div class=\"flex items-center gap-3\">\n")
                    .append("<button type='button' class='skin-preview h-10 w-10 rounded-lg overflow-hidden bg-slate-500/20 flex items-center justify-center text-slate-300 font-bold' data-skin-uuid='")
                    .append(uuid).append("' data-skin-name='").append(escapeHtml(name)).append("' aria-label='Preview skin for ")
                    .append(escapeHtml(name)).append("'><img src='https://crafatar.com/avatars/").append(uuid)
                    .append("?size=64&overlay' alt='' loading='lazy' class='h-full w-full image-render-pixel' onerror=\"this.hidden=true;this.nextElementSibling.hidden=false\"><span hidden>")
                    .append(name.isEmpty() ? '?' : Character.toUpperCase(name.charAt(0))).append("</span></button>\n")
                    .append("<div>\n")
                    .append("<p class=\"text-slate-300 font-medium\">").append(name).append("</p>\n")
                    .append(linkedUser.isEmpty() ? "" : "<a href='/users' class='inline-flex mt-1 px-2 py-1 rounded-lg border border-cyan-400/25 bg-cyan-500/10 text-cyan-200 text-[10px] font-bold' title='Manage linked Dash user'>Dash linked: " + escapeHtml(linkedUser) + "</a>\n")
                    .append("<p class=\"text-slate-500 text-xs font-mono\">").append(uuid.substring(0, 8))
                    .append("...</p>\n")
                    .append("</div>\n")
                    .append("</div>\n")
                    .append("</td>\n")
                    .append("<td class=\"px-4 py-3 whitespace-nowrap text-slate-400 text-sm\">").append(lastSeen).append("</td>\n")
                    .append("<td class=\"px-4 py-3 whitespace-nowrap text-slate-400 font-mono text-sm\">").append(playtime).append("</td>\n")
                    .append("<td class=\"px-4 py-3 whitespace-nowrap\">\n")
                    .append("<div class=\"flex items-center gap-2\">\n")
                    .append("<a href='/players/").append(name)
                    .append("/profile' title='View Profile' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-purple-400 hover:bg-purple-500/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">person</span></a>\n");

            if (canWhitelist) {
                if (isWhitelisted) {
                    offlineRows.append("<form action='/action' method='post' class='inline'>")
                            .append("<input type='hidden' name='action' value='whitelist_remove'>")
                            .append("<input type='hidden' name='player' value='").append(name).append("'>")
                            .append("<button title='Remove from Whitelist' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-rose-400 hover:bg-rose-500/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">person_remove</span></button></form>\n");
                } else {
                    offlineRows.append("<form action='/action' method='post' class='inline'>")
                            .append("<input type='hidden' name='action' value='whitelist_add'>")
                            .append("<input type='hidden' name='player' value='").append(name).append("'>")
                            .append("<button title='Add to Whitelist' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-emerald-400 hover:bg-emerald-500/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">person_add</span></button></form>\n");
                }
            }

            if (canBan) {
                offlineRows.append("<form action='/action' method='post' class='inline' onsubmit=\"return confirm('Ban ")
                        .append(name)
                        .append("?');\"><input type='hidden' name='action' value='ban'><input type='hidden' name='player' value='")
                        .append(name).append("'>")
                        .append("<button title='Ban' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-rose-400 hover:bg-rose-500/10 transition-all\"><span class=\"material-symbols-outlined text-[18px]\">block</span></button></form>\n");
            }

            offlineRows.append("</div>\n")
                    .append("</td>\n")
                    .append("</tr>\n");
        }

        if (offlineRows.length() == 0) {
            offlineRows.append(
                    "<tr><td colspan='4' class='px-4 py-8 text-center text-slate-500 whitespace-nowrap'>No offline players found</td></tr>\n");
        }

        // Active tab styling
        boolean isOnlineTab = "online".equals(tab);
        String onlineBtnClass = isOnlineTab ? "border-primary text-primary" : "border-transparent text-slate-400 hover:text-white";
        String offlineBtnClass = !isOnlineTab ? "border-primary text-primary" : "border-transparent text-slate-400 hover:text-white";

        String onlineStyle = isOnlineTab ? "" : "display: none;";
        String offlineStyle = !isOnlineTab ? "" : "display: none;";

        // Render Pagination buttons
        StringBuilder paginationHtml = new StringBuilder();
        if (!isOnlineTab && totalPages > 1) {
            paginationHtml.append("<div class=\"flex items-center justify-between px-6 py-4 border-t border-white/5\">\n")
                    .append("<div class=\"text-slate-500 text-xs\">Showing ").append(offset + 1).append("-").append(Math.min(offset + limit, totalOffline)).append(" of ").append(totalOffline).append(" players</div>\n")
                    .append("<div class=\"flex items-center gap-1.5\">\n");

            if (page > 1) {
                paginationHtml.append("<a href=\"/players?tab=offline&page=\"").append(page - 1).append("&q=").append(urlEncode(q)).append("\" class=\"px-3 py-1.5 rounded-lg border border-slate-700 bg-slate-800 text-slate-300 hover:bg-slate-700 transition-colors text-xs font-semibold\">Previous</a>\n");
            } else {
                paginationHtml.append("<button disabled class=\"px-3 py-1.5 rounded-lg border border-slate-800 bg-slate-900/40 text-slate-600 text-xs font-semibold cursor-not-allowed\">Previous</button>\n");
            }

            paginationHtml.append("<span class=\"px-3 py-1.5 text-slate-400 text-xs font-mono\">Page ").append(page).append(" of ").append(totalPages).append("</span>\n");

            if (page < totalPages) {
                paginationHtml.append("<a href=\"/players?tab=offline&page=").append(page + 1).append("&q=").append(urlEncode(q)).append("\" class=\"px-3 py-1.5 rounded-lg border border-slate-700 bg-slate-800 text-slate-300 hover:bg-slate-700 transition-colors text-xs font-semibold\">Next</a>\n");
            } else {
                paginationHtml.append("<button disabled class=\"px-3 py-1.5 rounded-lg border border-slate-800 bg-slate-900/40 text-slate-600 text-xs font-semibold cursor-not-allowed\">Next</button>\n");
            }

            paginationHtml.append("</div>\n</div>\n");
        }

        String content = HtmlTemplate.statsHeader() +
                "<style>.image-render-pixel{image-rendering:pixelated}.player-view{overflow:visible}.player-table{display:block!important;width:100%;min-width:0!important}.player-table thead{display:none}.player-table tbody{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,410px),1fr));gap:12px;padding:12px}.player-table tbody tr{display:flex;align-items:center;flex-wrap:wrap;min-width:0;border:1px solid rgba(100,116,139,.22)!important;border-radius:20px;background:rgba(2,6,23,.24);padding:8px}.player-table tbody td{padding:8px!important;min-width:0}.player-table tbody td:first-child{flex:1 1 210px}.player-table tbody td:last-child{margin-left:auto}.skin-preview{flex:0 0 auto;transition:transform .22s ease,box-shadow .22s ease}.skin-preview:hover{transform:translateY(-2px) scale(1.04);box-shadow:0 8px 22px rgba(34,211,238,.18)}.skin-modal{position:fixed;inset:0;z-index:120;display:grid;place-items:center;background:rgba(2,6,23,.78);backdrop-filter:blur(10px);padding:20px}.skin-modal[hidden]{display:none}.skin-stage{position:relative;width:min(360px,100%);min-height:460px;border:1px solid rgba(100,116,139,.35);border-radius:24px;background:radial-gradient(circle at 50% 30%,rgba(34,211,238,.12),transparent 55%),#0f172a;padding:22px;text-align:center}.skin-body{height:350px;max-width:100%;margin:auto;image-rendering:pixelated;filter:drop-shadow(0 20px 26px rgba(0,0,0,.5));animation:skinTurn 6s linear infinite}.skin-close{position:absolute;right:14px;top:14px;width:34px;height:34px;border-radius:50%;background:rgba(15,23,42,.9);color:#cbd5e1}@keyframes skinTurn{0%{transform:perspective(700px) rotateY(-24deg)}50%{transform:perspective(700px) rotateY(24deg)}100%{transform:perspective(700px) rotateY(-24deg)}}@media(max-width:640px){.player-table tbody{grid-template-columns:1fr;padding:8px}.player-table tbody tr{align-items:flex-start}.player-table tbody td{flex:1 1 50%}.player-table tbody td:first-child,.player-table tbody td:last-child{flex-basis:100%;width:100%;margin:0}.player-table tbody td:last-child>div{flex-wrap:wrap}}@media(prefers-reduced-motion:reduce){.skin-body{animation:none}}</style>" +
                "<style>.skin-canvas{display:block;width:min(300px,100%);height:360px;margin-inline:auto;cursor:grab;touch-action:none}.skin-canvas:active{cursor:grabbing}.skin-fallback{height:330px}.skin-error{margin-top:.5rem}</style>" +
                "<main class=\"p-4 sm:p-6 flex-1 w-full\">\n" +
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden\">\n" +
                
                // Header card details
                "<div class=\"flex flex-col sm:flex-row sm:items-center justify-between px-6 py-4 border-b border-white/5 gap-3\">\n" +
                "<div class=\"flex items-center gap-3\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">group</span>\n" +
                "<h2 class=\"text-lg font-display font-semibold text-white tracking-tight\">Player Management</h2>\n" +
                "</div>\n" +
                "<div class=\"flex items-center gap-2\">\n" +
                "<input type=\"text\" id=\"player-search\" value=\"" + escapeHtml(q) + "\" placeholder=\"Search players...\" class=\"bg-slate-950/40 border border-glass-border rounded-full px-5 py-2.5 text-xs text-white placeholder-slate-500 focus:border-primary/50 outline-none w-full sm:w-64 transition-all focus:bg-slate-950/80\">\n" +
                "</div>\n" +
                "</div>\n" +

                // Tab selectors
                "<div class=\"flex border-b border-white/5 px-6 py-4 bg-white/[0.005] gap-3\">\n" +
                "<div class=\"inline-flex p-1 bg-slate-950/40 rounded-full border border-glass-border\">\n" +
                "<button onclick=\"window.dashNavigate('/players?tab=online')\" class=\"px-5 py-2 rounded-full text-xs font-bold transition-all " + (isOnlineTab ? "bg-primary text-black shadow-lg scale-[1.02]" : "text-slate-400 hover:text-white hover:bg-white/5") + "\">\n" +
                "Online Players <span class=\"ml-1 px-1.5 py-0.5 rounded-full bg-slate-950/50 text-white font-mono text-[10px]\">" + Bukkit.getOnlinePlayers().size() + "</span>\n" +
                "</button>\n" +
                "<button onclick=\"window.dashNavigate('/players?tab=offline" + (q.isEmpty() ? "" : "&q=" + urlEncode(q)) + "')\" class=\"px-5 py-2 rounded-full text-xs font-bold transition-all " + (!isOnlineTab ? "bg-primary text-black shadow-lg scale-[1.02]" : "text-slate-400 hover:text-white hover:bg-white/5") + "\">\n" +
                "Offline Players\n" +
                "</button>\n" +
                "</div>\n" +
                "</div>\n" +

                // Online Table view
                "<div id=\"view-online\" class=\"w-full player-view\" style=\"" + onlineStyle + "\">\n" +
                "<table class=\"player-table w-full text-left\">\n" +
                "<thead class=\"bg-white/5\">\n" +
                "<tr class=\"text-left font-display font-semibold text-[13px] text-slate-300\">\n" +
                "<th class=\"px-4 py-3\">Player</th>\n" +
                "<th class=\"px-4 py-3\">Ping</th>\n" +
                "<th class=\"px-4 py-3\">IP</th>\n" +
                "<th class=\"px-4 py-3\">Location</th>\n" +
                "<th class=\"px-4 py-3\">Actions</th>\n" +
                "</tr>\n" +
                "</thead>\n" +
                "<tbody>\n" +
                onlineRows.toString() +
                "</tbody>\n" +
                "</table>\n" +
                "</div>\n" +

                // Offline Table view
                "<div id=\"view-offline\" class=\"w-full player-view\" style=\"" + offlineStyle + "\">\n" +
                "<table class=\"player-table w-full text-left\">\n" +
                "<thead class=\"bg-white/5\">\n" +
                "<tr class=\"text-left font-display font-semibold text-[13px] text-slate-300\">\n" +
                "<th class=\"px-4 py-3\">Player</th>\n" +
                "<th class=\"px-4 py-3\">Last Seen</th>\n" +
                "<th class=\"px-4 py-3\">Playtime</th>\n" +
                "<th class=\"px-4 py-3\">Actions</th>\n" +
                "</tr>\n" +
                "</thead>\n" +
                "<tbody>\n" +
                offlineRows.toString() +
                "</tbody>\n" +
                "</table>\n" +
                "</div>\n" +

                paginationHtml.toString() +
                "</div>\n" +
                "<div id='skin-modal' class='skin-modal' hidden role='dialog' aria-modal='true' aria-labelledby='skin-modal-title'><div class='skin-stage'><button type='button' class='skin-close' aria-label='Close skin preview'><span class='material-symbols-outlined'>close</span></button><h3 id='skin-modal-title' class='text-lg font-bold text-white'></h3><p class='mt-1 text-xs text-slate-500'>Drag to rotate · Scroll to zoom</p><canvas id='skin-canvas' class='skin-canvas mt-3' width='300' height='360' aria-label='Interactive 3D Minecraft skin'></canvas><img class='skin-body skin-fallback mt-4' alt='Minecraft skin preview' hidden><p class='skin-error text-xs text-amber-300' hidden>3D rendering is unavailable; showing the skin preview instead.</p></div></div>" +
                "</main>\n" +
                HtmlTemplate.statsScript() +
                "<script>\n" +
                "// Combined search box integration\n" +
                "document.getElementById('player-search').addEventListener('keypress', function(e) {\n" +
                "  if (e.key === 'Enter') {\n" +
                "    const searchVal = e.target.value.trim();\n" +
                "    const activeTab = '" + tab + "';\n" +
                "    if (activeTab === 'online') {\n" +
                "      // Filter online list instantly\n" +
                "      const filter = searchVal.toLowerCase();\n" +
                "      document.querySelectorAll('#view-online tbody tr').forEach(row => {\n" +
                "        const name = row.querySelector('td')?.textContent.toLowerCase() || '';\n" +
                "        row.style.display = name.includes(filter) ? '' : 'none';\n" +
                "      });\n" +
                "    } else {\n" +
                "      // Query server for offline results\n" +
                "      window.dashNavigate('/players?tab=offline&q=' + encodeURIComponent(searchVal));\n" +
                "    }\n" +
                "  }\n" +
                "});\n" +
                "// Make sure search box can filter online instantly while typing as well\n" +
                "document.getElementById('player-search').addEventListener('input', function(e) {\n" +
                "  const activeTab = '" + tab + "';\n" +
                "  if (activeTab === 'online') {\n" +
                "    const filter = e.target.value.toLowerCase();\n" +
                "    document.querySelectorAll('#view-online tbody tr').forEach(row => {\n" +
                "      const name = row.querySelector('td')?.textContent.toLowerCase() || '';\n" +
                "      row.style.display = name.includes(filter) ? '' : 'none';\n" +
                "    });\n" +
                "  }\n" +
                "});\n" +
                "const skinModal=document.getElementById('skin-modal'),skinBody=skinModal.querySelector('.skin-body'),skinCanvas=document.getElementById('skin-canvas'),skinError=skinModal.querySelector('.skin-error'),skinTitle=document.getElementById('skin-modal-title');let skinViewer=null,skinControls=null;\n" +
                "document.querySelectorAll('.skin-preview').forEach(button=>button.addEventListener('click',()=>{const uuid=encodeURIComponent(button.dataset.skinUuid);skinTitle.textContent=button.dataset.skinName;skinModal.hidden=false;document.body.style.overflow='hidden';skinCanvas.hidden=false;skinBody.hidden=true;skinError.hidden=true;try{if(!window.skinview3d)throw new Error('renderer unavailable');if(skinViewer)skinViewer.dispose();skinViewer=new skinview3d.SkinViewer({canvas:skinCanvas,width:300,height:360,skin:'https://crafatar.com/skins/'+uuid});skinViewer.zoom=0.82;skinViewer.autoRotate=true;skinViewer.autoRotateSpeed=.7;skinViewer.animation=new skinview3d.IdleAnimation();skinControls=skinViewer.controls;skinControls.enableRotate=true;skinControls.enablePan=false;skinControls.enableZoom=true;}catch(error){console.error('Dash 3D skin renderer failed',error);skinCanvas.hidden=true;skinBody.src='https://crafatar.com/renders/body/'+uuid+'?scale=8&overlay';skinBody.hidden=false;skinError.hidden=false;}skinModal.querySelector('.skin-close').focus();}));\n" +
                "function closeSkin(){skinModal.hidden=true;if(skinViewer){skinViewer.dispose();skinViewer=null;}skinControls=null;skinBody.removeAttribute('src');document.body.style.overflow='';}\n" +
                "skinModal.querySelector('.skin-close').addEventListener('click',closeSkin);skinModal.addEventListener('click',e=>{if(e.target===skinModal)closeSkin()});document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!skinModal.hidden)closeSkin()});\n" +
                "</script>\n";

        return HtmlTemplate.page("Players", "/players", content);
    }

    private static String linkedDashUser(WebAuth auth, String playerName, String playerUuid) {
        if (auth == null) return "";
        WebAuth.UserInfo user = auth.findLinkedUser(playerName, playerUuid);
        return user == null ? "" : user.username() + " · " + user.role();
    }

    private static String getQueryParam(String query, String key) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String[] parts = query.split("&");
        for (String part : parts) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && key.equals(pair[0])) {
                try {
                    return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String urlEncode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static String escapeHtml(String text) {
        if (text == null)
            return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
