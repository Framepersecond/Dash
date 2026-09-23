package dash.web;

public class WaitingRoomPage {

    public static String render(String pendingUser) {
        String content = "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border shadow-2xl shadow-black/40 overflow-hidden\">"
                + "<div class=\"px-8 pt-8 pb-5 border-b border-white/5\">"
                + "<div class=\"flex items-center gap-2 mb-2\">"
                + "<span class=\"material-symbols-outlined text-amber-300 text-[26px]\">hourglass_top</span>"
                + "<h1 class=\"text-2xl font-bold text-white\">Freigabe ausstehend</h1>"
                + "</div>"
                + "<p class=\"text-sm text-slate-400\">Identit&auml;t verifiziert. Bitte warte auf Freigabe durch den Admin.</p>"
                + "</div>"
                + "<div class=\"p-8 space-y-4\">"
                + "<a href=\"/login\" class=\"inline-block rounded-xl bg-primary text-background-dark font-semibold px-4 py-2 hover:bg-white transition-all\">Back to Login</a>"
                + "</div>"
                + "</div>";

        return HtmlTemplate.authPage("Waiting Room", content);
    }
}

