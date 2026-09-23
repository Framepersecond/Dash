package dash.web;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class PluginsPage {

    public static String render() {
        boolean canManagePlugins = HtmlTemplate.can("dash.web.plugins.manage");

        StringBuilder pluginsHtml = new StringBuilder();

        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            boolean enabled = plugin.isEnabled();
            String name = plugin.getName();
            String version = plugin.getDescription().getVersion();
            String authors = String.join(", ", plugin.getDescription().getAuthors());
            String description = plugin.getDescription().getDescription();
            if (description == null)
                description = "No description available";
            if (description.length() > 100)
                description = description.substring(0, 100) + "...";

            pluginsHtml.append(
                    "<div class=\"group relative p-4 rounded-xl bg-white/5 hover:bg-white/10 transition-colors border border-transparent hover:border-white/10 dash-hover-lift\">\n")
                    .append("<div class=\"flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3\">\n")
                    .append("<div class=\"flex items-center gap-3 min-w-0 flex-1\">\n")
                    .append("<div class=\"h-10 w-10 rounded-xl ")
                    .append(enabled ? "bg-emerald-500/20" : "bg-rose-500/20")
                    .append(" flex items-center justify-center\">\n")
                    .append("<span class=\"material-symbols-outlined text-[20px] ")
                    .append(enabled ? "text-emerald-400" : "text-rose-400").append("\">extension</span>\n")
                    .append("</div>\n")
                    .append("<div class=\"min-w-0\">\n")
                    .append("<div class=\"flex flex-wrap items-center gap-2 min-w-0\">\n")
                    .append("<p class=\"text-white font-semibold truncate\">").append(name).append("</p>\n")
                    .append("<span class=\"px-2 py-0.5 rounded-full bg-slate-700 text-slate-300 text-xs font-mono\">v")
                    .append(version).append("</span>\n")
                    .append("</div>\n")
                    .append("<p class=\"text-slate-500 text-sm\">")
                    .append(authors.isEmpty() ? "Unknown author" : "by " + authors).append("</p>\n")
                    .append("</div>\n")
                    .append("</div>\n")
                    .append("<div class=\"relative z-50 flex w-full sm:w-auto shrink-0 items-center justify-end gap-2 flex-wrap pointer-events-auto\">\n");

            if (canManagePlugins) {
                String pluginJar = getPluginJarFileName(plugin);
                pluginsHtml.append("<form action='/action' method='post' class='inline-flex relative z-50 pointer-events-auto' onclick=\"event.stopPropagation();\">\n")
                        .append("<input type='hidden' name='action' value='")
                        .append(enabled ? "plugin_disable" : "plugin_enable").append("'>\n")
                        .append("<input type='hidden' name='plugin' value='").append(name).append("'>\n")
                        .append("<button type=\"submit\" class=\"pointer-events-auto px-3 py-1 rounded-lg text-xs font-semibold ")
                        .append(enabled
                                ? "bg-emerald-500/20 text-emerald-400 hover:bg-rose-500/20 hover:text-rose-400"
                                : "bg-slate-600 text-slate-300 hover:bg-emerald-500/20 hover:text-emerald-400")
                        .append(" transition-colors\">")
                        .append(enabled ? "Enabled" : "Disabled")
                        .append("</button>\n")
                        .append("</form>\n");

                if (pluginJar != null && !pluginJar.isBlank()) {
                    pluginsHtml.append("<form action='/action' method='post' class='inline-flex relative z-50 pointer-events-auto' onclick=\"event.stopPropagation();\" onsubmit=\"return confirm('Delete plugin file? Restart required.');\">\n")
                            .append("<input type='hidden' name='action' value='plugin_delete'>\n")
                            .append("<input type='hidden' name='plugin' value='").append(name).append("'>\n")
                            .append("<input type='hidden' name='plugin_file' value='").append(pluginJar).append("'>\n")
                            .append("<button type=\"submit\" class=\"pointer-events-auto px-3 py-1 rounded-lg text-xs font-semibold bg-rose-500/20 text-rose-300 hover:bg-rose-500/30 transition-colors\">Delete File</button>\n")
                            .append("</form>\n");
                }
            }

            pluginsHtml.append("</div>\n")
                    .append("</div>\n")
                    .append("<p class=\"mt-2 text-slate-400 text-sm\">").append(description).append("</p>\n")
                    .append("</div>\n");
        }

        int enabledCount = 0;
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p.isEnabled())
                enabledCount++;
        }
        int totalCount = Bukkit.getPluginManager().getPlugins().length;

        String uploadScript = canManagePlugins
                ? "document.getElementById('plugin-upload').addEventListener('change', e => {\n" +
                        "  const file = e.target.files[0];\n" +
                        "  if (!file) return;\n" +
                        "  if (!file.name.endsWith('.jar')) { showToast('Only .jar files allowed', 'error'); return; }\n" +
                        "  const formData = new FormData();\n" +
                        "  formData.append('file', file);\n" +
                        "  fetch('/api/upload/plugin', {method:'POST', body: formData}).then(r => r.json()).then(d => {\n"
                        +
                        "    if (d.success) { showToast('Plugin uploaded! Restart server to load.', 'success'); if(window.dashNavigate){window.dashNavigate(location.pathname+location.search,'replace',undefined,true);} }\n"
                        +
                        "    else showToast('Error: ' + d.error, 'error');\n" +
                        "  });\n" +
                        "});\n"
                : "";

        String content = HtmlTemplate.statsHeader() +
                "<main class=\"flex-1 min-w-0 p-6\">\n" +
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden\">\n"
                +
                "<div class=\"flex items-center justify-between px-6 py-4 border-b border-white/5\">\n" +
                "<div class=\"flex items-center gap-3\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">extension</span>\n" +
                "<h2 class=\"text-lg font-bold text-white\">Installed Plugins</h2>\n" +
                "<span class=\"px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-400 text-xs font-mono\">"
                + enabledCount + "/" + totalCount + " enabled</span>\n" +
                "</div>\n" +
                "<div class=\"flex items-center gap-3\">\n" +
                "<a href='/plugin-browser' class=\"inline-flex items-center gap-2 px-3 py-2 rounded-lg border border-cyan-500/25 bg-cyan-500/10 text-cyan-300 hover:bg-cyan-500/20 text-sm font-semibold\"><span class=\"material-symbols-outlined text-[18px]\">travel_explore</span>Browse compatible</a>\n" +
                "<input type=\"text\" id=\"plugin-search\" placeholder=\"Search plugins...\" class=\"bg-slate-800 border border-slate-600 rounded-lg px-4 py-2 text-sm text-white placeholder-slate-500 focus:border-primary outline-none w-48\">\n"
                +
                (canManagePlugins
                        ? "<label class=\"cursor-pointer flex items-center gap-2 px-3 py-1.5 rounded-lg bg-primary/20 text-primary hover:bg-primary hover:text-black transition-colors text-sm font-medium\">\n"
                                +
                                "<input type=\"file\" id=\"plugin-upload\" accept=\".jar\" class=\"hidden\">\n"
                                +
                                "<span class=\"material-symbols-outlined text-[18px]\">add</span>\n" +
                                "<span>Install Plugin</span>\n" +
                                "</label>\n"
                        : "")
                +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"w-full overflow-x-auto pb-32 bg-gray-800 rounded-lg shadow ring-1 ring-gray-700\">\n" +
                "<div id=\"plugins-grid\" class=\"p-4 grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4\">\n" +
                pluginsHtml.toString() +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "</main>\n" +
                HtmlTemplate.statsScript() +
                "<script>\n" +
                "document.getElementById('plugin-search').addEventListener('input', function(e) {\n" +
                "  const search = e.target.value.toLowerCase();\n" +
                "  document.querySelectorAll('#plugins-grid > div').forEach(card => {\n" +
                "    const name = card.textContent.toLowerCase();\n" +
                "    card.style.display = name.includes(search) ? '' : 'none';\n" +
                "  });\n" +
                "});\n" +
                uploadScript +
                "</script>\n";

        return HtmlTemplate.page("Plugins", "/plugins", content);
    }

    private static String getPluginJarFileName(Plugin plugin) {
        try {
            java.net.URL url = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            if (url == null) {
                return null;
            }
            java.io.File f = new java.io.File(url.toURI());
            if (!f.isFile()) {
                return null;
            }
            return f.getName();
        } catch (Exception ignored) {
            return null;
        }
    }
}
