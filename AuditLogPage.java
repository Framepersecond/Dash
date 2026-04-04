package dash.web;

import dash.Dash;
import dash.data.AuditDataManager;
import dash.data.AuditDataManager.AuditEntry;

import java.util.List;

/**
 * Renders the Audit Log page – an HTML table of the last 100 audit actions
 * from the SQLite-backed AuditDataManager.
 */
public class AuditLogPage {

    public static String render(String searchQuery) {
        AuditDataManager auditMgr = Dash.getAuditDataManager();
        List<AuditEntry> entries;
        int totalCount = 0;

        if (auditMgr != null) {
            if (searchQuery != null && !searchQuery.isBlank()) {
                entries = auditMgr.searchLogs(searchQuery.trim(), 100);
            } else {
                entries = auditMgr.getRecentLogs(100);
            }
            totalCount = auditMgr.countLogs();
        } else {
            entries = List.of();
        }

        StringBuilder tableRows = new StringBuilder();
        for (AuditEntry entry : entries) {
            String actionColor = actionBadgeColor(entry.action());
            tableRows.append("<tr class=\"border-b border-white/5 hover:bg-white/5 transition-colors\">\n")
                    .append("<td class=\"px-4 py-3 text-xs text-slate-400 font-mono whitespace-nowrap\">").append(entry.getFormattedTime()).append("</td>\n")
                    .append("<td class=\"px-4 py-3 text-sm text-white font-medium whitespace-nowrap\">").append(escapeHtml(entry.username())).append("</td>\n")
                    .append("<td class=\"px-4 py-3 whitespace-nowrap\"><span class=\"px-2 py-0.5 rounded-full text-xs font-semibold ").append(actionColor).append("\">")
                    .append(escapeHtml(entry.action())).append("</span></td>\n")
                    .append("<td class=\"px-4 py-3 text-sm text-slate-300 whitespace-nowrap\" title=\"").append(escapeHtml(entry.details())).append("\">")
                    .append(escapeHtml(entry.details())).append("</td>\n")
                    .append("<td class=\"px-4 py-3 text-xs text-slate-500 font-mono whitespace-nowrap\">").append(escapeHtml(entry.ipAddress())).append("</td>\n")
                    .append("</tr>\n");
        }

        if (entries.isEmpty()) {
            tableRows.append("<tr><td colspan=\"5\" class=\"px-4 py-8 text-center text-slate-500 whitespace-nowrap\">No audit entries found</td></tr>\n");
        }

        String content = HtmlTemplate.statsHeader()
                + "<main class=\"p-4 sm:p-6 flex-1 w-full\">\n"
                + "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden\">\n"

                // Header
                + "<div class=\"flex items-center justify-between px-6 py-4 border-b border-white/5\">\n"
                + "<div class=\"flex items-center gap-3\">\n"
                + "<span class=\"material-symbols-outlined text-primary\">receipt_long</span>\n"
                + "<h2 class=\"text-lg font-bold text-white\">Audit Log</h2>\n"
                + "<span class=\"px-2 py-0.5 rounded-full bg-primary/20 text-primary text-xs font-mono\">"
                + totalCount + " total</span>\n"
                + "</div>\n"
                + "<form method=\"get\" action=\"/audit\" class=\"flex flex-col sm:flex-row items-stretch sm:items-center gap-2\">\n"
                + "<input type=\"text\" name=\"q\" value=\"" + escapeHtml(searchQuery != null ? searchQuery : "")
                + "\" placeholder=\"Search actions, users...\" class=\"bg-slate-800 border border-slate-600 rounded-lg px-4 py-2 text-sm text-white placeholder-slate-500 focus:border-primary outline-none w-full sm:w-64\">\n"
                + "<button type=\"submit\" class=\"px-3 py-2 rounded-lg bg-primary/20 text-primary hover:bg-primary hover:text-black transition-colors\">\n"
                + "<span class=\"material-symbols-outlined text-[18px]\">search</span>\n"
                + "</button>\n"
                + "</form>\n"
                + "</div>\n"

                // Table
                + "<div class=\"w-full overflow-x-auto pb-24\">\n"
                + "<table class=\"w-full min-w-[800px] text-left\">\n"
                + "<thead>\n"
                + "<tr class=\"border-b border-white/10\">\n"
                + "<th class=\"px-4 py-3 text-xs font-semibold text-slate-400 uppercase tracking-wider whitespace-nowrap\">Time</th>\n"
                + "<th class=\"px-4 py-3 text-xs font-semibold text-slate-400 uppercase tracking-wider whitespace-nowrap\">User</th>\n"
                + "<th class=\"px-4 py-3 text-xs font-semibold text-slate-400 uppercase tracking-wider whitespace-nowrap\">Action</th>\n"
                + "<th class=\"px-4 py-3 text-xs font-semibold text-slate-400 uppercase tracking-wider whitespace-nowrap\">Details</th>\n"
                + "<th class=\"px-4 py-3 text-xs font-semibold text-slate-400 uppercase tracking-wider whitespace-nowrap\">IP</th>\n"
                + "</tr>\n"
                + "</thead>\n"
                + "<tbody>\n"
                + tableRows.toString()
                + "</tbody>\n"
                + "</table>\n"
                + "</div>\n"

                + "</div>\n"
                + "</main>\n"
                + HtmlTemplate.statsScript();

        return HtmlTemplate.page("Audit Log", "/audit", content);
    }

    private static String actionBadgeColor(String action) {
        if (action == null) return "bg-slate-600 text-slate-300";
        String a = action.toUpperCase();
        if (a.contains("LOGIN") || a.contains("REGISTER")) return "bg-emerald-500/20 text-emerald-400";
        if (a.contains("DENIED") || a.contains("FAILED") || a.contains("BAN")) return "bg-rose-500/20 text-rose-400";
        if (a.contains("COMMAND") || a.contains("RESTART") || a.contains("STOP")) return "bg-amber-500/20 text-amber-400";
        if (a.contains("SETTING") || a.contains("PLUGIN") || a.contains("GAMERULE")) return "bg-purple-500/20 text-purple-400";
        if (a.contains("FILE") || a.contains("UPLOAD") || a.contains("BACKUP")) return "bg-sky-500/20 text-sky-400";
        return "bg-slate-600/50 text-slate-300";
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "…" : text;
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}

