package dash.web;

public class SetupPage {

    public static String render(String codePrefill, boolean setupRequired, String message) {
        String headline = setupRequired ? "Dash Initial Setup" : "Dash Invite Registration";
        String note = setupRequired
                ? "Enter your in-game setup code to create the first administrator account."
                : "Enter your invite code to create a new dashboard account.";

        String safeCode = codePrefill == null ? "" : codePrefill.replace("\"", "");

        String msgBox = (message != null && !message.isBlank())
                ? "<div class='mx-8 mt-6 p-3 rounded-lg bg-sky-500/10 border border-sky-500/20 text-sky-200 text-sm'>"
                        + escapeHtml(message) + "</div>"
                : "";

        String content =
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border shadow-2xl shadow-black/40 overflow-hidden\">"
                        +
                        "<div class=\"px-8 pt-8 pb-5 border-b border-white/5\">"
                        + "<div class=\"flex items-center gap-2 mb-2\">"
                        + "<span class=\"material-symbols-outlined text-primary text-[26px]\">verified_user</span>"
                        + "<h1 class=\"text-2xl font-bold text-white\">" + headline + "</h1>"
                        + "</div>"
                        + "<p class=\"text-sm text-slate-400\">" + note + "</p>"
                        + "</div>"
                        + msgBox
                        + "<form action=\"/action\" method=\"post\" class=\"p-8 space-y-4\">"
                        + "<input type=\"hidden\" name=\"action\" value=\"register_code\">"
                        + "<div>"
                        + "<label class=\"block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2\">Registration Code</label>"
                        + "<input type=\"text\" name=\"code\" required value=\"" + safeCode
                        + "\" class=\"w-full rounded-xl bg-slate-900/80 border border-slate-700 px-4 py-3 text-center uppercase tracking-[0.25em] font-mono text-white focus:border-primary focus:ring-2 focus:ring-primary/40 outline-none\" placeholder=\"XXXXXXXX\">"
                        + "</div>"
                        + "<div>"
                        + "<label class=\"block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2\">Username</label>"
                        + "<input type=\"text\" name=\"username\" required class=\"w-full rounded-xl bg-slate-900/80 border border-slate-700 px-4 py-3 text-white placeholder-slate-500 focus:border-primary focus:ring-2 focus:ring-primary/40 outline-none\" placeholder=\"new-admin\">"
                        + "</div>"
                        + "<div>"
                        + "<label class=\"block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2\">Password</label>"
                        + "<input type=\"password\" name=\"password\" required class=\"w-full rounded-xl bg-slate-900/80 border border-slate-700 px-4 py-3 text-white placeholder-slate-500 focus:border-primary focus:ring-2 focus:ring-primary/40 outline-none\" placeholder=\"********\">"
                        + "</div>"
                        + (!setupRequired
                                ? "<div>"
                                        + "<label class=\"block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2\">Owner 2FA Code (30s)</label>"
                                        + "<input type=\"text\" name=\"owner_2fa_code\" required maxlength=\"6\" class=\"w-full rounded-xl bg-slate-900/80 border border-slate-700 px-4 py-3 text-center tracking-[0.2em] font-mono text-white placeholder-slate-500 focus:border-primary focus:ring-2 focus:ring-primary/40 outline-none\" placeholder=\"123456\">"
                                        + "<p class=\"text-[11px] text-slate-500 mt-1\">Ask the MAIN_ADMIN for the current 30-second code.</p>"
                                        + "</div>"
                                : "")
                        + "<button type=\"submit\" class=\"w-full rounded-xl bg-primary text-background-dark font-semibold py-3 hover:bg-white transition-all shadow-glow-primary\">Create Account</button>"
                        + "</form>"
                        + "<div class=\"px-8 pb-6 text-center\">"
                        + "<a href=\"/\" class=\"text-xs text-primary hover:text-white transition-colors\">Back to Login</a>"
                        + "</div>"
                        + "</div>";

        return HtmlTemplate.authPage("Setup", content);
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}

