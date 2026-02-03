package dash.web;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class PluginsPage {

    public static String render() {
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
                    "<div class=\"group p-4 rounded-xl bg-white/5 hover:bg-white/10 transition-colors border border-transparent hover:border-white/10\">\n")
                    .append("<div class=\"flex items-start justify-between\">\n")
                    .append("<div class=\"flex items-center gap-3\">\n")
                    .append("<div class=\"h-10 w-10 rounded-xl ")
                    .append(enabled ? "bg-emerald-500/20" : "bg-rose-500/20")
                    .append(" flex items-center justify-center\">\n")
                    .append("<span class=\"material-symbols-outlined text-[20px] ")
                    .append(enabled ? "text-emerald-400" : "text-rose-400").append("\">extension</span>\n")
                    .append("</div>\n")
                    .append("<div>\n")
                    .append("<div class=\"flex items-center gap-2\">\n")
                    .append("<p class=\"text-white font-semibold\">").append(name).append("</p>\n")
                    .append("<span class=\"px-2 py-0.5 rounded-full bg-slate-700 text-slate-300 text-xs font-mono\">v")
                    .append(version).append("</span>\n")
                    .append("</div>\n")
                    .append("<p class=\"text-slate-500 text-sm\">")
                    .append(authors.isEmpty() ? "Unknown author" : "by " + authors).append("</p>\n")
                    .append("</div>\n")
                    .append("</div>\n")
                    .append("<div class=\"flex items-center gap-2\">\n")
                    .append("<form action='/action' method='post' class='inline'>\n")
                    .append("<input type='hidden' name='action' value='")
                    .append(enabled ? "plugin_disable" : "plugin_enable").append("'>\n")
                    .append("<input type='hidden' name='plugin' value='").append(name).append("'>\n")
                    .append("<button class=\"px-3 py-1 rounded-lg text-xs font-semibold ")
                    .append(enabled ? "bg-emerald-500/20 text-emerald-400 hover:bg-rose-500/20 hover:text-rose-400"
                            : "bg-slate-600 text-slate-300 hover:bg-emerald-500/20 hover:text-emerald-400")
                    .append(" transition-colors\">")
                    .append(enabled ? "Enabled" : "Disabled")
                    .append("</button>\n")
                    .append("</form>\n")
                    .append("</div>\n")
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

        String content = HtmlTemplate.statsHeader() +
                "<main class=\"flex-1 p-6 overflow-auto\">\n" +
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
                "<input type=\"text\" id=\"plugin-search\" placeholder=\"Search plugins...\" class=\"bg-slate-800 border border-slate-600 rounded-lg px-4 py-2 text-sm text-white placeholder-slate-500 focus:border-primary outline-none w-48\">\n"
                +
                "<label class=\"cursor-pointer flex items-center gap-2 px-3 py-1.5 rounded-lg bg-primary/20 text-primary hover:bg-primary hover:text-black transition-colors text-sm font-medium\">\n"
                +
                "<input type=\"file\" id=\"plugin-upload\" accept=\".jar\" class=\"hidden\">\n" +
                "<span class=\"material-symbols-outlined text-[18px]\">add</span>\n" +
                "<span>Install Plugin</span>\n" +
                "</label>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div id=\"plugins-grid\" class=\"p-4 grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4\">\n" +
                pluginsHtml.toString() +
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
                "document.getElementById('plugin-upload').addEventListener('change', e => {\n" +
                "  const file = e.target.files[0];\n" +
                "  if (!file) return;\n" +
                "  if (!file.name.endsWith('.jar')) { alert('Only .jar files allowed'); return; }\n" +
                "  const formData = new FormData();\n" +
                "  formData.append('file', file);\n" +
                "  fetch('/api/upload/plugin', {method:'POST', body: formData}).then(r => r.json()).then(d => {\n" +
                "    if (d.success) { alert('Plugin uploaded! Restart server to load.'); location.reload(); }\n" +
                "    else alert('Error: ' + d.error);\n" +
                "  });\n" +
                "});\n" +
                "</script>\n";

        return HtmlTemplate.page("Plugins", "/plugins", content);
    }
}
