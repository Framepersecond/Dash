package dash.web;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import dash.Dash;
import dash.data.PlayerDataManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class PlayerProfilePage {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    public static String render(String playerName) {
        PlayerDataManager pdm = Dash.getPlayerDataManager();

        if (pdm == null) {
            return HtmlTemplate.page("Error", "/players",
                    HtmlTemplate.statsHeader() +
                            "<main class='flex-1 p-6 flex items-center justify-center'>" +
                            "<div class='text-center'><p class='text-white'>Player data system not initialized</p></div></main>");
        }

        PlayerDataManager.PlayerInfo info = pdm.getPlayerInfo(playerName);

        if (info == null) {
            return HtmlTemplate.page("Player Not Found", "/players",
                    HtmlTemplate.statsHeader() +
                            "<main class='flex-1 p-6 flex items-center justify-center'>" +
                            "<div class='text-center'>" +
                            "<span class='material-symbols-outlined text-6xl text-slate-600 mb-4'>person_off</span>" +
                            "<h2 class='text-xl font-bold text-white mb-2'>Player Not Found</h2>" +
                            "<p class='text-slate-400 mb-4'>No data found for '" + escapeHtml(playerName) + "'</p>" +
                            "<a href='/players' class='px-4 py-2 rounded-lg bg-primary text-black font-semibold hover:bg-white transition-colors'>Back to Players</a>"
                            +
                            "</div></main>");
        }

        Player onlinePlayer = Bukkit.getPlayerExact(info.name());
        boolean isOnline = onlinePlayer != null;

        List<PlayerDataManager.SessionInfo> sessions = pdm.getPlayerSessions(info.uuid(), 20);
        List<PlayerDataManager.NoteInfo> notes = pdm.getPlayerNotes(info.uuid());

        StringBuilder sessionsHtml = new StringBuilder();
        for (PlayerDataManager.SessionInfo session : sessions) {
            String joinDate = DATE_FORMAT.format(new Date(session.joinTime()));
            String duration = session.getDuration();
            String ip = session.ipAddress() != null ? maskIp(session.ipAddress()) : "Unknown";

            sessionsHtml.append("<div class=\"flex items-center justify-between p-3 rounded-lg bg-white/5\">\n")
                    .append("<div class=\"flex items-center gap-3\">\n")
                    .append("<span class=\"material-symbols-outlined text-slate-500\">login</span>\n")
                    .append("<div><p class=\"text-white text-sm\">").append(joinDate).append("</p>\n")
                    .append("<p class=\"text-slate-500 text-xs\">").append(ip).append("</p></div></div>\n")
                    .append("<span class=\"text-slate-400 text-sm font-mono\">").append(duration).append("</span>\n")
                    .append("</div>\n");
        }
        if (sessions.isEmpty()) {
            sessionsHtml.append("<p class=\"text-slate-500 text-center py-4\">No session history</p>\n");
        }

        StringBuilder notesHtml = new StringBuilder();
        for (PlayerDataManager.NoteInfo note : notes) {
            String noteDate = DATE_FORMAT.format(new Date(note.createdAt()));
            notesHtml.append("<div class=\"p-3 rounded-lg bg-white/5 group\">\n")
                    .append("<div class=\"flex items-center justify-between mb-2\">\n")
                    .append("<span class=\"text-xs text-slate-500\">").append(note.adminName()).append(" • ")
                    .append(noteDate).append("</span>\n")
                    .append("<form action='/action' method='post' class='inline opacity-0 group-hover:opacity-100 transition-opacity'>\n")
                    .append("<input type='hidden' name='action' value='delete_note'>")
                    .append("<input type='hidden' name='id' value='").append(note.id()).append("'>")
                    .append("<button class=\"text-slate-400 hover:text-rose-400\"><span class=\"material-symbols-outlined text-[14px]\">delete</span></button>")
                    .append("</form></div>\n")
                    .append("<p class=\"text-white text-sm\">").append(escapeHtml(note.note())).append("</p>\n")
                    .append("</div>\n");
        }

        String content = HtmlTemplate.statsHeader() +
                "<main class='flex-1 p-6 overflow-auto'>\n" +
                "<div class='max-w-4xl mx-auto'>\n" +

                "<div class='rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border p-6 mb-6'>\n" +
                "<div class='flex items-center gap-6'>\n" +
                "<a href='/players' class='h-10 w-10 rounded-lg flex items-center justify-center text-slate-400 hover:text-white hover:bg-white/10 transition-all'>\n"
                +
                "<span class='material-symbols-outlined'>arrow_back</span></a>\n" +
                "<div class='h-20 w-20 rounded-full " + (isOnline ? "bg-emerald-500/20" : "bg-slate-700")
                + " flex items-center justify-center text-3xl font-bold "
                + (isOnline ? "text-emerald-400" : "text-slate-400") + "'>\n" +
                Character.toUpperCase(info.name().charAt(0)) + "</div>\n" +
                "<div class='flex-1'>\n" +
                "<div class='flex items-center gap-3'>\n" +
                "<h1 class='text-2xl font-bold text-white'>" + escapeHtml(info.name()) + "</h1>\n" +
                "<span class='px-2 py-0.5 rounded-full text-xs font-medium "
                + (isOnline ? "bg-emerald-500/20 text-emerald-400" : "bg-slate-600 text-slate-300") + "'>"
                + (isOnline ? "Online" : "Offline") + "</span>\n" +
                "</div>\n" +
                "<p class='text-slate-500 text-sm font-mono'>" + info.uuid() + "</p>\n" +
                "</div>\n" +
                "<div class='text-right'>\n" +
                "<p class='text-slate-400 text-sm'>Total Playtime</p>\n" +
                "<p class='text-2xl font-bold text-primary'>" + info.getFormattedPlaytime() + "</p>\n" +
                "</div>\n" +
                "</div>\n" +

                "<div class='grid grid-cols-3 gap-4 mt-6'>\n" +
                "<div class='p-4 rounded-xl bg-white/5 text-center'>\n" +
                "<p class='text-slate-400 text-xs uppercase tracking-wider mb-1'>First Seen</p>\n" +
                "<p class='text-white font-medium'>" + DATE_FORMAT.format(new Date(info.firstJoin())) + "</p>\n" +
                "</div>\n" +
                "<div class='p-4 rounded-xl bg-white/5 text-center'>\n" +
                "<p class='text-slate-400 text-xs uppercase tracking-wider mb-1'>Last Seen</p>\n" +
                "<p class='text-white font-medium'>" + DATE_FORMAT.format(new Date(info.lastJoin())) + "</p>\n" +
                "</div>\n" +
                "<div class='p-4 rounded-xl bg-white/5 text-center'>\n" +
                "<p class='text-slate-400 text-xs uppercase tracking-wider mb-1'>Sessions</p>\n" +
                "<p class='text-white font-medium'>" + sessions.size() + "</p>\n" +
                "</div>\n" +
                "</div>\n" +

                (isOnline ? "<div class='flex gap-2 mt-6'>\n" +
                        "<a href='/players/" + info.name()
                        + "/inventory' class='px-4 py-2 rounded-lg bg-sky-500/20 text-sky-400 hover:bg-sky-500 hover:text-white transition-colors text-sm font-medium'>View Inventory</a>\n"
                        +
                        "<a href='/players/" + info.name()
                        + "/enderchest' class='px-4 py-2 rounded-lg bg-purple-500/20 text-purple-400 hover:bg-purple-500 hover:text-white transition-colors text-sm font-medium'>Ender Chest</a>\n"
                        +
                        "<form action='/action' method='post' class='inline' onsubmit=\"return confirm('Kick player?');\">\n"
                        +
                        "<input type='hidden' name='action' value='kick'><input type='hidden' name='player' value='"
                        + info.name() + "'>\n" +
                        "<button class='px-4 py-2 rounded-lg bg-amber-500/20 text-amber-400 hover:bg-amber-500 hover:text-black transition-colors text-sm font-medium'>Kick</button></form>\n"
                        +
                        "<form action='/action' method='post' class='inline' onsubmit=\"return confirm('Ban player?');\">\n"
                        +
                        "<input type='hidden' name='action' value='ban'><input type='hidden' name='player' value='"
                        + info.name() + "'>\n" +
                        "<button class='px-4 py-2 rounded-lg bg-rose-500/20 text-rose-400 hover:bg-rose-500 hover:text-white transition-colors text-sm font-medium'>Ban</button></form>\n"
                        +
                        "</div>\n" : "")
                +
                "</div>\n" +

                "<div class='grid grid-cols-1 lg:grid-cols-2 gap-6'>\n" +

                "<div class='rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden'>\n"
                +
                "<div class='flex items-center gap-3 px-6 py-4 border-b border-white/5'>\n" +
                "<span class='material-symbols-outlined text-primary'>history</span>\n" +
                "<h2 class='text-sm font-bold text-white uppercase tracking-wider'>Session History</h2>\n" +
                "</div>\n" +
                "<div class='p-4 flex flex-col gap-2 max-h-96 overflow-y-auto console-scrollbar'>\n" +
                sessionsHtml.toString() +
                "</div></div>\n" +

                "<div class='rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden'>\n"
                +
                "<div class='flex items-center gap-3 px-6 py-4 border-b border-white/5'>\n" +
                "<span class='material-symbols-outlined text-primary'>note_alt</span>\n" +
                "<h2 class='text-sm font-bold text-white uppercase tracking-wider'>Admin Notes</h2>\n" +
                "</div>\n" +
                "<div class='p-4'>\n" +
                "<form action='/action' method='post' class='flex gap-2 mb-4'>\n" +
                "<input type='hidden' name='action' value='add_note'>\n" +
                "<input type='hidden' name='uuid' value='" + info.uuid() + "'>\n" +
                "<input type='text' name='note' placeholder='Add a note...' class='flex-1 bg-slate-800 border border-slate-600 rounded px-3 py-2 text-sm text-white placeholder-slate-500 focus:border-primary outline-none'>\n"
                +
                "<button class='px-4 py-2 rounded bg-primary text-black text-sm font-semibold'>Add</button>\n" +
                "</form>\n" +
                "<div class='flex flex-col gap-2 max-h-72 overflow-y-auto console-scrollbar'>\n" +
                notesHtml.toString() +
                (notes.isEmpty() ? "<p class='text-slate-500 text-center py-4'>No notes</p>\n" : "") +
                "</div></div></div>\n" +

                "</div>\n" +
                "</div>\n" +
                "</main>\n" +
                HtmlTemplate.statsScript();

        return HtmlTemplate.page(info.name() + " - Profile", "/players", content);
    }

    private static String maskIp(String ip) {
        if (ip == null)
            return "Unknown";
        String[] parts = ip.split("\\.");
        if (parts.length == 4) {
            return parts[0] + "." + parts[1] + ".xxx.xxx";
        }
        return ip;
    }

    private static String escapeHtml(String text) {
        if (text == null)
            return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
