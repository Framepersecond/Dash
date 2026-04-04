package dash.web;

public class LoginPage {

    public static String render() {
        return render(null);
    }

    public static String render(String errorCode) {
        String errorBox = "";
        if ("pending_approval".equalsIgnoreCase(errorCode)) {
            errorBox = "<div class='mx-8 mt-6 rounded-lg border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-sm text-amber-200'>"
                    + "Your SSO login is pending MAIN_ADMIN approval.</div>";
        } else if ("sso_expired".equalsIgnoreCase(errorCode)) {
            errorBox = "<div class='mx-8 mt-6 rounded-lg border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200'>"
                    + "SSO request expired. Please start login again from NeoDash.</div>";
        } else if ("sso_invalid".equalsIgnoreCase(errorCode)) {
            errorBox = "<div class='mx-8 mt-6 rounded-lg border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200'>"
                    + "Invalid SSO signature.</div>";
        }

        String content =
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border shadow-2xl shadow-black/40 overflow-hidden\">"
                        +
                        errorBox +
                        "<div class=\"px-8 pt-8 pb-5 border-b border-white/5\">"
                        + "<div class=\"flex items-center gap-2 mb-2\">"
                        + "<span class=\"material-symbols-outlined text-primary text-[26px]\">dashboard</span>"
                        + "<h1 class=\"text-2xl font-bold text-white\">Dash Panel Login</h1>"
                        + "</div>"
                        + "<p class=\"text-sm text-slate-400\">Melde dich an, um dein Server-Dashboard zu verwalten.</p>"
                        + "</div>"
                        + "<form action=\"/action\" method=\"post\" class=\"p-8 space-y-4\">"
                        + "<input type=\"hidden\" name=\"action\" value=\"login\">"
                        + "<div>"
                        + "<label class=\"block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2\">Username</label>"
                        + "<input type=\"text\" name=\"username\" required class=\"w-full rounded-xl bg-slate-900/80 border border-slate-700 px-4 py-3 text-white placeholder-slate-500 focus:border-primary focus:ring-2 focus:ring-primary/40 outline-none\" placeholder=\"admin\">"
                        + "</div>"
                        + "<div>"
                        + "<label class=\"block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2\">Password</label>"
                        + "<input type=\"password\" name=\"password\" required class=\"w-full rounded-xl bg-slate-900/80 border border-slate-700 px-4 py-3 text-white placeholder-slate-500 focus:border-primary focus:ring-2 focus:ring-primary/40 outline-none\" placeholder=\"********\">"
                        + "</div>"
                        + "<button type=\"submit\" class=\"w-full rounded-xl bg-primary text-background-dark font-semibold py-3 hover:bg-white transition-all shadow-glow-primary\">Login</button>"
                        + "</form>"
                        + "</div>";

        return HtmlTemplate.authPage("Login", content);
    }
}

