package dash.web;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

public class DashboardPage {

    public static String render() {
        boolean canKick = HtmlTemplate.can("dash.web.players.kick");
        boolean canBan = HtmlTemplate.can("dash.web.players.ban");
        boolean canCommand = HtmlTemplate.can("dash.web.console.command");

        StringBuilder playerListHtml = new StringBuilder();
        for (Player p : Bukkit.getOnlinePlayers()) {
            char initial = p.getName().isEmpty() ? '?' : Character.toUpperCase(p.getName().charAt(0));
            playerListHtml.append(
                    "<div class=\"group flex items-center gap-3 p-3 rounded-xl hover:bg-white/5 transition-colors cursor-pointer border border-transparent hover:border-white/5\">\n")
                    .append("<div class=\"relative\">\n")
                    .append("<div class=\"h-10 w-10 rounded-full bg-sky-500/20 flex items-center justify-center text-sky-400 font-bold font-mono text-lg\">")
                    .append(initial).append("</div>\n")
                    .append("<div class=\"absolute -bottom-1 -right-1 h-3.5 w-3.5 bg-[#0f172a] rounded-full flex items-center justify-center\"><div class=\"h-2 w-2 bg-emerald-400 rounded-full\"></div></div>\n")
                    .append("</div>\n")
                    .append("<div class=\"flex-1 min-w-0\">\n")
                    .append("<p class=\"text-white text-sm font-medium truncate\">").append(p.getName())
                    .append("</p>\n")
                    .append("<p class=\"text-slate-500 text-xs truncate\">").append(p.getWorld().getName())
                    .append("</p>\n")
                    .append("</div>\n")
                    .append("<div class=\"flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity\">\n");
            if (canKick) {
                playerListHtml
                        .append("<form action='/action' method='post' onsubmit=\"return confirm('Kick?');\"><input type='hidden' name='action' value='kick'><input type='hidden' name='player' value='")
                        .append(p.getName()).append("'>")
                        .append("<button class=\"h-7 w-7 rounded-lg flex items-center justify-center text-slate-400 hover:text-white hover:bg-white/10\"><span class=\"material-symbols-outlined text-[16px]\">logout</span></button></form>\n");
            }
            if (canBan) {
                playerListHtml
                        .append("<form action='/action' method='post' onsubmit=\"return confirm('Ban?');\"><input type='hidden' name='action' value='ban'><input type='hidden' name='player' value='")
                        .append(p.getName()).append("'>")
                        .append("<button class=\"h-7 w-7 rounded-lg flex items-center justify-center text-slate-400 hover:text-rose-400 hover:bg-rose-500/10\"><span class=\"material-symbols-outlined text-[16px]\">block</span></button></form>\n");
            }
            playerListHtml.append("</div></div>\n");
        }
        if (playerListHtml.length() == 0) {
            int maxPlayers = Bukkit.getMaxPlayers();
            playerListHtml.append("<div class=\"text-slate-500 text-center text-sm py-4\">No players online (0/")
                    .append(maxPlayers).append(")</div>\n");
        }

        int overworldChunks = 0, netherChunks = 0, endChunks = 0;
        for (World world : Bukkit.getWorlds()) {
            int chunks = world.getLoadedChunks().length;
            switch (world.getEnvironment()) {
                case NORMAL:
                    overworldChunks += chunks;
                    break;
                case NETHER:
                    netherChunks += chunks;
                    break;
                case THE_END:
                    endChunks += chunks;
                    break;
            }
        }

        String content = HtmlTemplate.statsHeader() +
                "<main class=\"p-4 sm:p-6 flex-1 w-full pb-24\">\n" +
                "<div class=\"flex flex-col gap-6 w-full\">\n" +
                "<div class=\"flex flex-col xl:flex-row gap-6 w-full\">\n" +
                "<aside class=\"w-full xl:w-80 xl:flex-shrink-0 flex flex-col gap-4 rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border p-4\">\n"
                +
                "<div class=\"flex items-center justify-between px-2 pt-2 pb-4 border-b border-white/5\">\n" +
                "<h3 class=\"text-white text-sm font-bold uppercase tracking-wider\">Online Players</h3>\n" +
                "<span class=\"px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-400 text-xs font-mono\">"
                + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers() + "</span>\n" +
                "</div>\n" +
                "<div class=\"flex-1 overflow-y-auto pr-1 console-scrollbar flex flex-col gap-2\">\n" +
                playerListHtml.toString() +
                "</div>\n" +
                "<div class=\"mt-auto pt-4 border-t border-white/5\">\n" +
                "<p class=\"text-xs font-medium text-slate-400 uppercase tracking-wider mb-2\">Loaded Chunks</p>\n" +
                "<div class=\"grid grid-cols-3 gap-2 text-center\">\n" +
                "<div class=\"p-2 rounded-lg bg-emerald-500/10\"><p class=\"text-emerald-400 font-mono font-bold\">"
                + overworldChunks + "</p><p class=\"text-[10px] text-slate-500\">Overworld</p></div>\n" +
                "<div class=\"p-2 rounded-lg bg-rose-500/10\"><p class=\"text-rose-400 font-mono font-bold\">"
                + netherChunks + "</p><p class=\"text-[10px] text-slate-500\">Nether</p></div>\n" +
                "<div class=\"p-2 rounded-lg bg-purple-500/10\"><p class=\"text-purple-400 font-mono font-bold\">"
                + endChunks + "</p><p class=\"text-[10px] text-slate-500\">End</p></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "</aside>\n" +
                "<section class=\"flex-1 flex flex-col gap-4\">\n" +

                // --- KPI + Pie Charts ---
                "<div class=\"grid grid-cols-1 md:grid-cols-2 gap-3 flex-shrink-0\">\n" +
                "<div class=\"rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border p-3\"><p class=\"text-[11px] text-slate-400 uppercase tracking-wider\">MSPT</p><p id=\"kpi-mspt\" class=\"text-xl font-bold text-indigo-300\">0.0</p></div>\n" +
                "<div class=\"rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border p-3\"><p class=\"text-[11px] text-slate-400 uppercase tracking-wider\">Chunks</p><p id=\"kpi-chunks\" class=\"text-xl font-bold text-purple-300\">0</p></div>\n" +
                "</div>\n" +
                "<div class=\"grid grid-cols-1 md:grid-cols-3 gap-4 flex-shrink-0\">\n" +
                "<div class=\"rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border p-4\"><p class=\"text-xs font-medium text-slate-400 uppercase tracking-wider mb-2\">TPS Health</p><div class=\"h-[170px]\"><canvas id=\"chart-tps\"></canvas></div></div>\n" +
                "<div class=\"rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border p-4\"><p class=\"text-xs font-medium text-slate-400 uppercase tracking-wider mb-2\">RAM Usage</p><div class=\"h-[170px]\"><canvas id=\"chart-ram\"></canvas></div></div>\n" +
                "<div class=\"rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border p-4\"><p class=\"text-xs font-medium text-slate-400 uppercase tracking-wider mb-2\">Chunk Split</p><div class=\"h-[170px]\"><canvas id=\"chart-chunks\"></canvas></div></div>\n" +
                "</div>\n" +

                // --- Console ---
                "<div class=\"flex-1 relative rounded-3xl bg-[#000000]/40 backdrop-blur-xl border border-glass-border flex flex-col shadow-inner\">\n"
                +
                "<div class=\"flex items-center justify-between px-6 py-3 border-b border-white/5 bg-white/5\">\n" +
                "<div class=\"flex items-center gap-2\">\n" +
                "<span class=\"material-symbols-outlined text-primary text-[18px]\">terminal</span>\n" +
                "<span class=\"text-xs font-mono font-medium text-slate-300\">Server Console</span>\n" +
                "</div>\n" +
                "<div class=\"flex gap-1.5\">\n" +
                "<div class=\"h-2.5 w-2.5 rounded-full bg-rose-500/20\"></div>\n" +
                "<div class=\"h-2.5 w-2.5 rounded-full bg-amber-500/20\"></div>\n" +
                "<div class=\"h-2.5 w-2.5 rounded-full bg-emerald-500\"></div>\n" +
                "</div></div>\n" +
                "<div id=\"console\" class=\"flex-1 overflow-y-auto p-6 font-mono text-sm leading-relaxed console-scrollbar whitespace-pre-wrap text-emerald-400\">Loading...</div>\n"
                +
                "</div>\n" +
                (canCommand
                        ? "<div class=\"h-16 flex-shrink-0 relative\">\n" +
                                "<form action='/action' method='post'>\n" +
                                "<input type='hidden' name='action' value='command'>\n" +
                                "<input name=\"cmd\" class=\"w-full h-12 rounded-full bg-glass-surface backdrop-blur-xl border border-glass-border px-8 pr-16 text-white placeholder-slate-500 font-mono focus:outline-none focus:ring-2 focus:ring-primary/50 focus:border-primary/50 transition-all shadow-lg text-sm\" placeholder=\"Enter admin command...\" type=\"text\"/>\n"
                                +
                                "<button class=\"absolute right-3 top-1/2 -translate-y-1/2 h-10 w-10 rounded-full bg-primary flex items-center justify-center text-background-dark hover:bg-white hover:scale-105 transition-all shadow-glow-primary\">\n"
                                +
                                "<span class=\"material-symbols-outlined\">arrow_forward</span>\n" +
                                "</button>\n" +
                                "</form>\n" +
                                "</div>\n"
                        : "<div class=\"h-16 flex-shrink-0 rounded-2xl border border-white/10 bg-white/5 flex items-center px-5 text-sm text-slate-400\">No command permission for this account.</div>\n")
                +
                "</section>\n" +
                "</div>\n" +
                "</div>\n" +
                "</main>\n" +
                HtmlTemplate.statsScript() +
                "<script>\n" +
                "if(window._dashPageTimer){clearInterval(window._dashPageTimer);window._dashPageTimer=null;}\n" +
                "function pollConsole() {\n" +
                "  fetch('/api/console').then(r => r.text()).then(t => {\n" +
                "    let el = document.getElementById('console');\n" +
                "    if(el && el.innerText !== t) { el.innerText = t; el.scrollTop = el.scrollHeight; }\n" +
                "  });\n" +
                "}\n" +
                "pollConsole();\n" +
                "window._dashPageTimer = setInterval(() => { pollConsole(); pollStats(); }, 2000);\n" +
                "</script>\n" +

                // --- Chart.js Pie Visuals + Numeric KPIs ---
                "<script>\n" +
                "if(window._dashPageTimer2){clearInterval(window._dashPageTimer2);window._dashPageTimer2=null;}\n" +
                "var donutOpts = function(showLegend) { if(showLegend===undefined)showLegend=true; return {\n" +
                "  responsive: true,\n" +
                "  maintainAspectRatio: false,\n" +
                "  cutout: '68%',\n" +
                "  animation: { duration: 280 },\n" +
                "  plugins: {\n" +
                "    legend: { display: showLegend, labels: { color: 'rgba(255,255,255,0.62)', boxWidth: 10, boxHeight: 10 } }\n" +
                "  }\n" +
                "};};\n" +
                "var tpsChart = new Chart(document.getElementById('chart-tps'), {\n" +
                "  type:'doughnut',\n" +
                "  data:{ labels:['TPS','Headroom'], datasets:[{ data:[20,0], backgroundColor:['rgba(16,185,129,0.95)','rgba(71,85,105,0.45)'], borderWidth:0 }] },\n" +
                "  options: donutOpts(false)\n" +
                "});\n" +
                "var ramChart = new Chart(document.getElementById('chart-ram'), {\n" +
                "  type:'doughnut',\n" +
                "  data:{ labels:['Used','Free'], datasets:[{ data:[0,1], backgroundColor:['rgba(245,158,11,0.95)','rgba(71,85,105,0.45)'], borderWidth:0 }] },\n" +
                "  options: donutOpts(true)\n" +
                "});\n" +
                "var chunksChart = new Chart(document.getElementById('chart-chunks'), {\n" +
                "  type:'doughnut',\n" +
                "  data:{ labels:['Overworld','Nether','End'], datasets:[{ data:[1,1,1], backgroundColor:['rgba(16,185,129,0.95)','rgba(244,63,94,0.95)','rgba(168,85,247,0.95)'], borderWidth:0 }] },\n" +
                "  options: donutOpts(true)\n" +
                "});\n" +
                "function setText(id, val){ var el = document.getElementById(id); if(el) el.textContent = val; }\n" +
                "function updateDashboardVisuals(){\n" +
                "  Promise.all([\n" +
                "    fetch('/api/stats').then(r => r.json()).catch(() => null),\n" +
                "    fetch('/api/stats/history').then(r => r.json()).catch(() => [])\n" +
                "  ]).then(([stats, history]) => {\n" +
                "    if (stats && !stats.error) {\n" +
                "      var tps = Math.max(0, Math.min(20, Number(stats.tps || 0)));\n" +
                "      var ramUsed = Math.max(0, Number(stats.ram_used || 0));\n" +
                "      var ramMax = Math.max(ramUsed + 1, Number(stats.ram_max || ramUsed + 1));\n" +
                "      tpsChart.data.datasets[0].data = [tps, Math.max(0, 20 - tps)];\n" +
                "      tpsChart.update();\n" +
                "      ramChart.data.datasets[0].data = [ramUsed, Math.max(0, ramMax - ramUsed)];\n" +
                "      ramChart.update();\n" +
                "    }\n" +
                "    if (Array.isArray(history) && history.length > 0) {\n" +
                "      var latest = history[history.length - 1];\n" +
                "      var mspt = Math.max(0, Number(latest.mspt || 0));\n" +
                "      var ow = Math.max(0, Number(latest.ow || 0));\n" +
                "      var nether = Math.max(0, Number(latest.nether || 0));\n" +
                "      var end = Math.max(0, Number(latest.end || 0));\n" +
                "      setText('kpi-mspt', mspt.toFixed(2));\n" +
                "      setText('kpi-chunks', String(ow + nether + end));\n" +
                "      chunksChart.data.datasets[0].data = [ow, nether, end];\n" +
                "      chunksChart.update();\n" +
                "    }\n" +
                "  });\n" +
                "}\n" +
                "updateDashboardVisuals();\n" +
                "window._dashPageTimer2 = setInterval(updateDashboardVisuals, 5000);\n" +
                "</script>\n";

        return HtmlTemplate.page("Dashboard", "/", content);
    }
}
