package dash.web;

import java.util.Set;

public class HtmlTemplate {

    private static final String[][] NAV_ITEMS = {
            { "home", "Dashboard", "/", "dash.web.stats.read" },
            { "terminal", "Console", "/console", "dash.web.console.read" },
            { "group", "Players", "/players", "dash.web.players.read" },
            { "folder", "Files", "/files", "dash.web.files.read" },
            { "extension", "Plugins", "/plugins", "dash.web.plugins.read" },
            { "manage_accounts", "Users", "/users", "dash.web.users.manage" },
            { "menu_book", "Permissions", "/permissions", "dash.web.users.manage" },
            { "settings", "Settings", "/settings", "dash.web.settings.read" },
            { "receipt_long", "Audit Log", "/audit", "dash.web.audit.read" },
            { "schedule", "Tasks", "/scheduled-tasks", "dash.web.tasks.read" },
            { "tune", "Plugin Settings", "/plugin-settings", "dash.web.pluginsettings.read" }
    };

    private static final ThreadLocal<Set<String>> UI_PERMISSIONS = ThreadLocal.withInitial(Set::of);
    private static final ThreadLocal<Boolean> UI_BRIDGE_USER = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<String> UI_BRIDGE_MASTER_URL = ThreadLocal.withInitial(() -> "");

    public static void setUiPermissions(Set<String> permissions) {
        UI_PERMISSIONS.set(permissions == null ? Set.of() : permissions);
    }

    public static void clearUiPermissions() {
        UI_PERMISSIONS.remove();
    }

    public static void setBridgeContext(boolean bridgeUser, String bridgeMasterUrl) {
        UI_BRIDGE_USER.set(bridgeUser);
        UI_BRIDGE_MASTER_URL.set(bridgeMasterUrl == null ? "" : bridgeMasterUrl.trim());
    }

    public static void clearBridgeContext() {
        UI_BRIDGE_USER.remove();
        UI_BRIDGE_MASTER_URL.remove();
    }

    public static boolean can(String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        Set<String> grants = UI_PERMISSIONS.get();
        if (grants.contains("*") || grants.contains("dash.web.*")) {
            return true;
        }
        if (grants.contains(permission)) {
            return true;
        }
        for (String grant : grants) {
            if (grant.endsWith(".*")) {
                String prefix = grant.substring(0, grant.length() - 1);
                if (permission.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String head(String title) {
        return "<!DOCTYPE html>\n" +
                "<html class=\"dark\" lang=\"en\"><head>\n" +
                "<meta charset=\"utf-8\"/>\n" +
                "<meta content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0\" name=\"viewport\"/>\n" +
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
                "        'primary': '#22d3ee',\n" +
                "        'accent': '#10b981',\n" +
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
                "* { box-sizing: border-box; }\n" +
                "body { margin: 0; padding: 0; font-family: sans-serif; overflow-x: hidden; }\n" +
                ".app-shell { display: flex; min-height: 100vh; width: 100%; }\n" +
                ".sidebar { flex-shrink: 0; width: 260px; }\n" +
                ".content { flex-grow: 1; min-width: 0; background: #0f172a; }\n" +
                ".page-shell { width: 95%; max-width: 1200px; margin: 0 auto; }\n" +
                ".sidebar-overlay { display: none; }\n" +
                ".console-scrollbar::-webkit-scrollbar { width: 6px; }\n" +
                ".console-scrollbar::-webkit-scrollbar-track { background: transparent; }\n" +
                ".console-scrollbar::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.1); border-radius: 9999px; }\n"
                +
                ".console-scrollbar::-webkit-scrollbar-thumb:hover { background: rgba(255,255,255,0.2); }\n" +
                ".pixelated { image-rendering: pixelated; image-rendering: crisp-edges; }\n" +
                "@media (max-width: 768px) {\n" +
                "  .sidebar { position: fixed; top: 0; left: 0; height: 100vh; z-index: 50; transform: translateX(-100%); transition: transform 0.2s ease; display: flex; }\n"
                +
                "  body.sidebar-open .sidebar { transform: translateX(0); }\n" +
                "  .sidebar-overlay { display: block; position: fixed; inset: 0; background: rgba(2, 6, 23, 0.55); opacity: 0; pointer-events: none; transition: opacity 0.2s ease; z-index: 40; }\n"
                +
                "  body.sidebar-open .sidebar-overlay { opacity: 1; pointer-events: auto; }\n" +
                "  .content { width: 100%; }\n" +
                "}\n"
                +
                "</style>\n" +
                "<script>\n" +
                "(function(){\n" +
                "  let allowUnloadLogout = true;\n" +
                "  document.addEventListener('click', function (e) {\n" +
                "    const a = e.target && e.target.closest ? e.target.closest('a[href]') : null;\n" +
                "    if (!a) return;\n" +
                "    const href = a.getAttribute('href') || '';\n" +
                "    if (!href.startsWith('javascript:') && !href.startsWith('#')) { allowUnloadLogout = false; }\n" +
                "  }, true);\n" +
                "  document.addEventListener('submit', function () { allowUnloadLogout = false; }, true);\n" +
                "  window.addEventListener('beforeunload', function (e) {\n" +
                "    if (e && e.target && e.target.activeElement && e.target.activeElement.tagName === 'A') return;\n" +
                "    if (allowUnloadLogout && navigator.sendBeacon) { navigator.sendBeacon('/api/logout'); }\n" +
                "  });\n" +
                "  document.addEventListener('DOMContentLoaded', function () {\n" +
                "    const menuBtn = document.getElementById('mobile-menu-toggle');\n" +
                "    const overlay = document.getElementById('sidebar-overlay');\n" +
                "    const close = function(){ document.body.classList.remove('sidebar-open'); };\n" +
                "    if (menuBtn) { menuBtn.addEventListener('click', function(){ document.body.classList.toggle('sidebar-open'); }); }\n" +
                "    if (overlay) { overlay.addEventListener('click', close); }\n" +
                "    document.addEventListener('click', function (e) {\n" +
                "      const a = e.target && e.target.closest ? e.target.closest('a[href]') : null;\n" +
                "      if (a) close();\n" +
                "    });\n" +
                "  });\n" +
                "})();\n" +
                "</script>\n" +
                "</head>\n";
    }

    public static String bodyStart(String currentPath) {
        StringBuilder nav = new StringBuilder();
        for (String[] item : NAV_ITEMS) {
            String icon = item[0];
            String label = item[1];
            String path = item[2];
            String requiredPermission = item[3];
            if (!can(requiredPermission)) {
                continue;
            }
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

        boolean showBackToNeoDash = Boolean.TRUE.equals(UI_BRIDGE_USER.get());
        String configuredMasterUrl = UI_BRIDGE_MASTER_URL.get();
        String backHref = (configuredMasterUrl == null || configuredMasterUrl.isBlank()) ? "/" : configuredMasterUrl;
        String backToNeoDashHtml = showBackToNeoDash
                ? "<a href=\"" + escapeHtml(backHref)
                        + "\" class=\"mb-3 flex items-center gap-3 px-4 py-3 rounded-xl border border-emerald-500/30 bg-emerald-500/10 text-emerald-300 hover:bg-emerald-500/20 transition-all\">\n"
                        + "<span class=\"material-symbols-outlined text-[20px]\">arrow_back</span>\n"
                        + "<span class=\"text-sm font-semibold\">Back to NeoDash</span>\n"
                        + "</a>\n"
                : "";

        return "<body class=\"app-shell bg-deep-space text-slate-200 font-display min-h-screen overflow-y-auto selection:bg-primary/30 selection:text-white\">\n"
                +
                "<nav class=\"sidebar dash-sidebar w-64 flex-shrink-0 flex flex-col min-h-screen bg-gray-900 backdrop-blur-xl border-r border-glass-border p-4\">\n"
                +
                "<div class=\"flex items-center gap-2 px-4 py-4 mb-4\">\n" +
                "<span class=\"material-symbols-outlined text-primary text-[28px]\">dashboard</span>\n" +
                "<span class=\"text-lg font-bold text-white\">Dash</span>\n" +
                "</div>\n" +
                "<div class=\"flex flex-col gap-1 flex-1\">\n" +
                nav.toString() +
                "</div>\n" +
                "<div class=\"mt-auto pt-4 border-t border-white/5\">\n" +
                backToNeoDashHtml +
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
                "<div id=\"sidebar-overlay\" class=\"sidebar-overlay\"></div>\n" +
                "<div class=\"flex-1 flex flex-col h-screen overflow-y-auto overflow-x-hidden w-full pb-24\">\n" +
                "<div class=\"md:hidden sticky top-0 z-30 flex items-center gap-3 px-4 py-3 border-b border-white/10 bg-slate-900/95 backdrop-blur\">\n"
                +
                "<button id=\"mobile-menu-toggle\" type=\"button\" class=\"h-9 w-9 rounded-lg border border-white/15 text-slate-200 flex items-center justify-center\">\n"
                +
                "<span class=\"material-symbols-outlined text-[20px]\">menu</span>\n" +
                "</button>\n" +
                "<span class=\"text-sm font-semibold text-slate-100\">Dash</span>\n" +
                "</div>\n";
    }

    public static String bodyEnd() {
        return "</div>\n</body></html>";
    }

    public static String page(String title, String currentPath, String content) {
        return head(title) + bodyStart(currentPath) + content + bodyEnd();
    }

    public static String authPage(String title, String content) {
        return head(title) +
                "<body class=\"bg-deep-space text-slate-200 font-display min-h-screen flex items-center justify-center px-4\">\n" +
                "<div class=\"absolute inset-0 bg-[radial-gradient(circle_at_20%_20%,rgba(14,165,233,0.15),transparent_28%),radial-gradient(circle_at_80%_0%,rgba(56,189,248,0.1),transparent_32%)]\"></div>\n"
                +
                "<div class=\"relative z-10 page-shell w-full max-w-md\">\n" +
                content +
                "</div>\n" +
                "</body></html>";
    }

    public static String statsHeader() {
        String serverActions = "";
        if (can("dash.web.server.control")) {
            serverActions = "<form action='/action' method='post' style='display:inline'><input type='hidden' name='action' value='restart'>\n"
                    +
                    "<button class=\"flex items-center gap-2 px-6 py-3 rounded-full bg-primary/10 border border-primary/20 text-primary hover:bg-primary hover:text-black hover:shadow-glow-primary transition-all duration-300 group\">\n"
                    +
                    "<span class=\"material-symbols-outlined text-[20px] group-hover:animate-spin\">refresh</span>\n"
                    +
                    "<span class=\"text-sm font-semibold\">Restart</span>\n" +
                    "</button></form>\n" +
                    "<form action='/action' method='post' style='display:inline' onsubmit=\"return confirm('STOP SERVER?');\">\n"
                    +
                    "<input type='hidden' name='action' value='stop'>\n" +
                    "<button class=\"flex items-center gap-2 px-6 py-3 rounded-full bg-rose-500/10 border border-rose-500/20 text-rose-400 hover:bg-rose-600 hover:text-white hover:shadow-glow-danger transition-all duration-300\">\n"
                    +
                    "<span class=\"material-symbols-outlined text-[20px]\">power_settings_new</span>\n" +
                    "<span class=\"text-sm font-semibold\">Stop</span>\n" +
                    "</button></form>\n";
        }

        return "<header class=\"w-full px-6 py-4 flex-shrink-0\">\n" +
                "<div class=\"flex flex-wrap gap-4\">\n" +
                "<div class=\"group flex-1 min-w-[220px] flex items-center justify-between p-4 rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border hover:bg-glass-highlight transition-all duration-300\">\n"
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
                "<div class=\"group flex-1 min-w-[220px] flex items-center justify-between p-4 rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border hover:bg-glass-highlight transition-all duration-300\">\n"
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
                "<div class=\"group flex-1 min-w-[220px] flex items-center justify-between p-4 rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border hover:bg-glass-highlight transition-all duration-300\">\n"
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
                "<div class=\"flex-1 min-w-[220px] flex items-center justify-start lg:justify-end gap-3 pl-0 lg:pl-4\">\n" +
                serverActions +
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

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
