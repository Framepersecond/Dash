package dash.web;

public class HtmlTemplate {

    private static final String[][] NAV_ITEMS = {
            { "home", "Dashboard", "/" },
            { "terminal", "Console", "/console" },
            { "group", "Players", "/players" },
            { "folder", "Files", "/files" },
            { "extension", "Plugins", "/plugins" },
            { "settings", "Settings", "/settings" }
    };

    public static String head(String title) {
        return "<!DOCTYPE html>\n" +
                "<html class=\"dark\" lang=\"en\"><head>\n" +
                "<meta charset=\"utf-8\"/>\n" +
                "<meta content=\"width=device-width, initial-scale=1.0\" name=\"viewport\"/>\n" +
                "<title>" + title + " - Dash Admin</title>\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:opsz,wght,FILL,GRAD@20..48,100..700,0..1,-50..200&display=swap\" rel=\"stylesheet\"/>\n"
                +
                "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap\" rel=\"stylesheet\"/>\n"
                +
                "<script src=\"https://cdn.tailwindcss.com?plugins=forms,container-queries\"></script>\n" +
                "<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n" +
                "<script>\n" +
                "tailwind.config = {\n" +
                "  darkMode: 'class',\n" +
                "  theme: {\n" +
                "    extend: {\n" +
                "      colors: {\n" +
                "        'primary': '#0dccf2',\n" +
                "        'background-dark': '#0f172a',\n" +
                "        'glass-border': 'rgba(255, 255, 255, 0.08)',\n" +
                "        'glass-surface': 'rgba(255, 255, 255, 0.03)',\n" +
                "        'glass-highlight': 'rgba(255, 255, 255, 0.08)',\n" +
                "      },\n" +
                "      fontFamily: {\n" +
                "        'display': ['Inter', 'sans-serif'],\n" +
                "        'mono': ['JetBrains Mono', 'monospace'],\n" +
                "      },\n" +
                "      boxShadow: {\n" +
                "        'glow-primary': '0 0 20px -5px rgba(13, 204, 242, 0.4)',\n" +
                "        'glow-danger': '0 0 20px -5px rgba(244, 63, 94, 0.4)',\n" +
                "      },\n" +
                "      backgroundImage: {\n" +
                "        'deep-space': 'radial-gradient(circle at 50% 0%, #1e293b 0%, #0f172a 40%, #020617 100%)',\n" +
                "      }\n" +
                "    },\n" +
                "  },\n" +
                "}\n" +
                "</script>\n" +
                "<style>\n" +
                ".console-scrollbar::-webkit-scrollbar { width: 6px; }\n" +
                ".console-scrollbar::-webkit-scrollbar-track { background: transparent; }\n" +
                ".console-scrollbar::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.1); border-radius: 9999px; }\n"
                +
                ".console-scrollbar::-webkit-scrollbar-thumb:hover { background: rgba(255,255,255,0.2); }\n" +
                ".pixelated { image-rendering: pixelated; image-rendering: crisp-edges; }\n" +
                "</style>\n" +
                "</head>\n";
    }

    public static String bodyStart(String currentPath) {
        StringBuilder nav = new StringBuilder();
        for (String[] item : NAV_ITEMS) {
            String icon = item[0];
            String label = item[1];
            String path = item[2];
            boolean active = path.equals(currentPath);

            String activeClass = active
                    ? "bg-primary/20 text-primary border-primary/30"
                    : "text-slate-400 border-transparent hover:bg-white/5 hover:text-white";

            nav.append("<a href=\"").append(path)
                    .append("\" class=\"flex items-center gap-3 px-4 py-3 rounded-xl border ").append(activeClass)
                    .append(" transition-all\">\n")
                    .append("<span class=\"material-symbols-outlined text-[20px]\">").append(icon).append("</span>\n")
                    .append("<span class=\"text-sm font-medium\">").append(label).append("</span>\n")
                    .append("</a>\n");
        }

        return "<body class=\"bg-deep-space text-slate-200 font-display min-h-screen h-screen flex overflow-hidden selection:bg-primary/30 selection:text-white\">\n"
                +
                "<nav class=\"w-64 flex-shrink-0 flex flex-col bg-glass-surface backdrop-blur-xl border-r border-glass-border p-4 h-full\">\n"
                +
                "<div class=\"flex items-center gap-2 px-4 py-4 mb-4\">\n" +
                "<span class=\"material-symbols-outlined text-primary text-[28px]\">dashboard</span>\n" +
                "<span class=\"text-lg font-bold text-white\">Dash</span>\n" +
                "</div>\n" +
                "<div class=\"flex flex-col gap-1\">\n" +
                nav.toString() +
                "</div>\n" +
                "<div class=\"mt-auto pt-4 border-t border-white/5\">\n" +
                "<form action='/action' method='post'>\n" +
                "<input type='hidden' name='action' value='logout'>\n" +
                "<button class=\"w-full flex items-center gap-3 px-4 py-3 rounded-xl text-slate-400 hover:bg-rose-500/10 hover:text-rose-400 transition-all\">\n"
                +
                "<span class=\"material-symbols-outlined text-[20px]\">logout</span>\n" +
                "<span class=\"text-sm font-medium\">Logout</span>\n" +
                "</button>\n" +
                "</form>\n" +
                "</div>\n" +
                "</nav>\n" +
                "<div class=\"flex-1 flex flex-col overflow-hidden\">\n";
    }

    public static String bodyEnd() {
        return "</div>\n</body></html>";
    }

    public static String page(String title, String currentPath, String content) {
        return head(title) + bodyStart(currentPath) + content + bodyEnd();
    }

    public static String statsHeader() {
        return "<header class=\"w-full px-6 py-4 flex-shrink-0\">\n" +
                "<div class=\"grid grid-cols-1 md:grid-cols-4 gap-4\">\n" +
                "<div class=\"group flex items-center justify-between p-4 rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border hover:bg-glass-highlight transition-all duration-300\">\n"
                +
                "<div class=\"flex flex-col gap-1\">\n" +
                "<span class=\"text-xs font-medium text-slate-400 uppercase tracking-wider\">Server Uptime</span>\n" +
                "<span id=\"uptime-val\" class=\"text-xl font-bold text-white tracking-tight\">--</span>\n" +
                "</div>\n" +
                "<div class=\"h-10 w-10 rounded-full bg-emerald-500/10 flex items-center justify-center text-emerald-400 group-hover:scale-110 transition-transform\">\n"
                +
                "<span class=\"material-symbols-outlined text-[20px]\">dns</span>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"group flex items-center justify-between p-4 rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border hover:bg-glass-highlight transition-all duration-300\">\n"
                +
                "<div class=\"flex flex-col gap-1\">\n" +
                "<span class=\"text-xs font-medium text-slate-400 uppercase tracking-wider\">TPS</span>\n" +
                "<span id=\"tps-val\" class=\"text-xl font-bold text-white tracking-tight\">--</span>\n" +
                "</div>\n" +
                "<div class=\"h-10 w-10 rounded-full bg-primary/10 flex items-center justify-center text-primary group-hover:scale-110 transition-transform\">\n"
                +
                "<span class=\"material-symbols-outlined text-[20px]\">speed</span>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"group flex items-center justify-between p-4 rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border hover:bg-glass-highlight transition-all duration-300\">\n"
                +
                "<div class=\"flex flex-col gap-1\">\n" +
                "<span class=\"text-xs font-medium text-slate-400 uppercase tracking-wider\">RAM Usage</span>\n" +
                "<span id=\"ram-val\" class=\"text-xl font-bold text-white tracking-tight\">--</span>\n" +
                "</div>\n" +
                "<div class=\"h-10 w-10 rounded-full bg-amber-500/10 flex items-center justify-center text-amber-400 group-hover:scale-110 transition-transform\">\n"
                +
                "<span class=\"material-symbols-outlined text-[20px]\">memory</span>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"flex items-center justify-end gap-3 pl-4\">\n" +
                "<form action='/action' method='post' style='display:inline'><input type='hidden' name='action' value='restart'>\n"
                +
                "<button class=\"flex items-center gap-2 px-6 py-3 rounded-full bg-primary/10 border border-primary/20 text-primary hover:bg-primary hover:text-black hover:shadow-glow-primary transition-all duration-300 group\">\n"
                +
                "<span class=\"material-symbols-outlined text-[20px] group-hover:animate-spin\">refresh</span>\n" +
                "<span class=\"text-sm font-semibold\">Restart</span>\n" +
                "</button></form>\n" +
                "<form action='/action' method='post' style='display:inline' onsubmit=\"return confirm('STOP SERVER?');\">\n"
                +
                "<input type='hidden' name='action' value='stop'>\n" +
                "<button class=\"flex items-center gap-2 px-6 py-3 rounded-full bg-rose-500/10 border border-rose-500/20 text-rose-400 hover:bg-rose-600 hover:text-white hover:shadow-glow-danger transition-all duration-300\">\n"
                +
                "<span class=\"material-symbols-outlined text-[20px]\">power_settings_new</span>\n" +
                "<span class=\"text-sm font-semibold\">Stop</span>\n" +
                "</button></form>\n" +
                "</div>\n" +
                "</div>\n" +
                "</header>\n";
    }

    public static String statsScript() {
        return "<script>\n" +
                "function pollStats() {\n" +
                "  fetch('/api/stats').then(r => r.json()).then(d => {\n" +
                "    if(d.error) return;\n" +
                "    document.getElementById('tps-val').innerText = d.tps.toFixed(1);\n" +
                "    document.getElementById('ram-val').innerText = d.ram_used + ' / ' + d.ram_max + ' MB';\n" +
                "    document.getElementById('uptime-val').innerText = d.uptime;\n" +
                "  }).catch(e=>{});\n" +
                "}\n" +
                "setInterval(pollStats, 2000);\n" +
                "window.onload = function() { pollStats(); };\n" +
                "</script>\n";
    }
}
