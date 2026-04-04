package dash.web;

import dash.Dash;
import dash.data.ScheduledTaskManager;
import dash.data.ScheduledTaskManager.ScheduledTask;

import java.util.List;

/**
 * Renders the Scheduled Tasks management page — list existing tasks, toggle/delete, add new ones.
 */
public class ScheduledTasksPage {

    public static String render(String message) {
        boolean canWrite = HtmlTemplate.can("dash.web.tasks.write");
        ScheduledTaskManager mgr = Dash.getScheduledTaskManager();
        List<ScheduledTask> tasks = mgr != null ? mgr.getAllTasks() : List.of();

        StringBuilder taskRows = new StringBuilder();
        for (ScheduledTask task : tasks) {
            String statusColor = task.enabled()
                    ? "bg-emerald-500/20 text-emerald-400"
                    : "bg-slate-600/50 text-slate-400";
            String statusLabel = task.enabled() ? "Active" : "Paused";
            boolean running = mgr != null && mgr.isRunning(task.id());

            taskRows.append("<tr class=\"border-b border-white/5 hover:bg-white/5 transition-colors\">\n")
                    .append("<td class=\"px-4 py-3 text-sm text-slate-300 font-mono whitespace-nowrap\">#").append(task.id()).append("</td>\n")
                    .append("<td class=\"px-4 py-3 whitespace-nowrap\">\n")
                    .append("<span class=\"px-2 py-0.5 rounded-full text-xs font-semibold ")
                    .append("command".equals(task.taskType()) ? "bg-amber-500/20 text-amber-400" : "bg-sky-500/20 text-sky-400")
                    .append("\">").append(escapeHtml(task.getFormattedType())).append("</span>\n")
                    .append("</td>\n")
                    .append("<td class=\"px-4 py-3 text-sm text-white font-mono max-w-xs truncate whitespace-nowrap\" title=\"")
                    .append(escapeHtml(task.payload())).append("\">")
                    .append(escapeHtml(truncate(task.payload(), 60))).append("</td>\n")
                    .append("<td class=\"px-4 py-3 text-sm text-slate-300 whitespace-nowrap\">").append(task.getFormattedInterval()).append("</td>\n")
                    .append("<td class=\"px-4 py-3 whitespace-nowrap\">\n")
                    .append("<span class=\"px-2 py-0.5 rounded-full text-xs font-semibold ").append(statusColor)
                    .append("\">").append(statusLabel).append("</span>\n")
                    .append(running ? "<span class=\"ml-2 text-[10px] text-emerald-500 animate-pulse\">● running</span>" : "")
                    .append("</td>\n")
                    .append("<td class=\"px-4 py-3 whitespace-nowrap\">\n");

            if (canWrite) {
                // Toggle button
                taskRows.append("<form action=\"/action\" method=\"post\" style=\"display:inline\">\n")
                        .append("<input type=\"hidden\" name=\"action\" value=\"task_toggle\">\n")
                        .append("<input type=\"hidden\" name=\"task_id\" value=\"").append(task.id()).append("\">\n")
                        .append("<input type=\"hidden\" name=\"enabled\" value=\"").append(!task.enabled()).append("\">\n")
                        .append("<button class=\"px-2.5 py-1 rounded-lg text-xs font-medium ")
                        .append(task.enabled()
                                ? "bg-amber-500/20 text-amber-400 hover:bg-amber-500/30"
                                : "bg-emerald-500/20 text-emerald-400 hover:bg-emerald-500/30")
                        .append(" transition-colors whitespace-nowrap\">")
                        .append(task.enabled() ? "Pause" : "Resume")
                        .append("</button></form>\n");

                // Delete button
                taskRows.append("<form action=\"/action\" method=\"post\" style=\"display:inline\" onsubmit=\"return confirm('Delete task #")
                        .append(task.id()).append("?');\">\n")
                        .append("<input type=\"hidden\" name=\"action\" value=\"task_delete\">\n")
                        .append("<input type=\"hidden\" name=\"task_id\" value=\"").append(task.id()).append("\">\n")
                        .append("<button class=\"px-2.5 py-1 rounded-lg text-xs font-medium bg-rose-500/20 text-rose-400 hover:bg-rose-500/30 transition-colors ml-1 whitespace-nowrap\">Delete</button></form>\n");
            } else {
                taskRows.append("<span class=\"text-xs text-slate-500\">Read only</span>\n");
            }

            taskRows.append("</td>\n</tr>\n");
        }

        if (tasks.isEmpty()) {
            taskRows.append("<tr><td colspan=\"6\" class=\"px-4 py-8 text-center text-slate-500 whitespace-nowrap\">No scheduled tasks configured</td></tr>\n");
        }

        String messageHtml = "";
        if (message != null && !message.isEmpty()) {
            boolean isError = message.toLowerCase().contains("error") || message.toLowerCase().contains("fail");
            messageHtml = "<div class=\"mb-4 px-4 py-3 rounded-xl text-sm font-medium "
                    + (isError ? "bg-rose-500/20 text-rose-400 border border-rose-500/20" : "bg-emerald-500/20 text-emerald-400 border border-emerald-500/20")
                    + "\">" + escapeHtml(message) + "</div>\n";
        }

        String addForm = "";
        if (canWrite) {
            addForm = "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden mt-6\">\n"
                    + "<div class=\"flex items-center gap-3 px-6 py-4 border-b border-white/5\">\n"
                    + "<span class=\"material-symbols-outlined text-primary\">add_circle</span>\n"
                    + "<h2 class=\"text-lg font-bold text-white\">Add Scheduled Task</h2>\n"
                    + "</div>\n"
                    + "<form action=\"/action\" method=\"post\" class=\"p-6\">\n"
                    + "<input type=\"hidden\" name=\"action\" value=\"task_add\">\n"
                    + "<div class=\"grid grid-cols-1 md:grid-cols-4 gap-4 items-end\">\n"

                    // Type selector
                    + "<div>\n"
                    + "<label class=\"block text-xs font-medium text-slate-400 uppercase tracking-wider mb-2\">Type</label>\n"
                    + "<select name=\"task_type\" class=\"w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white focus:border-primary outline-none\">\n"
                    + "<option value=\"command\">Console Command</option>\n"
                    + "<option value=\"broadcast\">Broadcast Message</option>\n"
                    + "</select>\n"
                    + "</div>\n"

                    // Payload
                    + "<div class=\"md:col-span-2\">\n"
                    + "<label class=\"block text-xs font-medium text-slate-400 uppercase tracking-wider mb-2\">Payload</label>\n"
                    + "<input type=\"text\" name=\"payload\" required placeholder=\"e.g. say Hello! or save-all\" class=\"w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white font-mono placeholder-slate-500 focus:border-primary outline-none\">\n"
                    + "</div>\n"

                    // Interval
                    + "<div>\n"
                    + "<label class=\"block text-xs font-medium text-slate-400 uppercase tracking-wider mb-2\">Interval (min)</label>\n"
                    + "<input type=\"number\" name=\"interval\" required min=\"1\" max=\"10080\" value=\"60\" class=\"w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white font-mono focus:border-primary outline-none\">\n"
                    + "</div>\n"

                    + "</div>\n"
                    + "<div class=\"flex justify-end mt-4\">\n"
                    + "<button type=\"submit\" class=\"flex items-center gap-2 px-6 py-2.5 rounded-full bg-primary/10 border border-primary/20 text-primary hover:bg-primary hover:text-black hover:shadow-glow-primary transition-all duration-300 font-semibold text-sm\">\n"
                    + "<span class=\"material-symbols-outlined text-[18px]\">add</span>\n"
                    + "<span>Create Task</span>\n"
                    + "</button>\n"
                    + "</div>\n"
                    + "</form>\n"
                    + "</div>\n";
        }

        String content = HtmlTemplate.statsHeader()
                + "<main class=\"p-4 sm:p-6 flex-1 w-full\">\n"
                + messageHtml

                // Task list
                + "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden\">\n"
                + "<div class=\"flex items-center justify-between px-6 py-4 border-b border-white/5\">\n"
                + "<div class=\"flex items-center gap-3\">\n"
                + "<span class=\"material-symbols-outlined text-primary\">schedule</span>\n"
                + "<h2 class=\"text-lg font-bold text-white\">Scheduled Tasks</h2>\n"
                + "<span class=\"px-2 py-0.5 rounded-full bg-primary/20 text-primary text-xs font-mono\">" + tasks.size() + " tasks</span>\n"
                + "</div>\n"
                + "</div>\n"
                + "<div class=\"w-full overflow-x-auto pb-24\">\n"
                + "<table class=\"w-full min-w-[800px] text-left\">\n"
                + "<thead>\n"
                + "<tr class=\"border-b border-white/10\">\n"
                + "<th class=\"px-4 py-3 text-xs font-semibold text-slate-400 uppercase tracking-wider whitespace-nowrap\">ID</th>\n"
                + "<th class=\"px-4 py-3 text-xs font-semibold text-slate-400 uppercase tracking-wider whitespace-nowrap\">Type</th>\n"
                + "<th class=\"px-4 py-3 text-xs font-semibold text-slate-400 uppercase tracking-wider whitespace-nowrap\">Payload</th>\n"
                + "<th class=\"px-4 py-3 text-xs font-semibold text-slate-400 uppercase tracking-wider whitespace-nowrap\">Interval</th>\n"
                + "<th class=\"px-4 py-3 text-xs font-semibold text-slate-400 uppercase tracking-wider whitespace-nowrap\">Status</th>\n"
                + "<th class=\"px-4 py-3 text-xs font-semibold text-slate-400 uppercase tracking-wider whitespace-nowrap\">Actions</th>\n"
                + "</tr>\n"
                + "</thead>\n"
                + "<tbody>\n"
                + taskRows.toString()
                + "</tbody>\n"
                + "</table>\n"
                + "</div>\n"
                + "</div>\n"

                + addForm
                + "</main>\n"
                + HtmlTemplate.statsScript();

        return HtmlTemplate.page("Scheduled Tasks", "/scheduled-tasks", content);
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

