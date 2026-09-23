package dash.web;

public class SetupPage {

    public static String render(String codePrefill, boolean setupRequired, String message) {
        String headline = setupRequired ? "Dash Initial Setup" : "Dash Invite Registration";
        String note = setupRequired
                ? "Create the owner account, choose a starting feature profile and review the optional beta boundary."
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
                        + "<form action=\"/action\" method=\"post\" class=\"p-8 space-y-4\" id=\"dash-setup-form\">"
                        + "<input type=\"hidden\" name=\"action\" value=\"register_code\">"
                        + "<div>"
                        + "<label class=\"block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2\">Registration Code</label>"
                        + "<input type=\"text\" name=\"code\" required value=\"" + safeCode
                        + "\" class=\"w-full rounded-xl bg-slate-900/80 border border-slate-700 px-4 py-3 text-center uppercase tracking-[0.25em] font-mono text-white focus:border-primary focus:ring-2 focus:ring-primary/40 outline-none\" placeholder=\"XXXXXXXX\">"
                        + "</div>"
                        + "<div>"
                        + "<label class=\"block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2\">Username</label>"
                        + "<input type=\"text\" name=\"username\" required minlength=\"3\" maxlength=\"32\" pattern=\"[A-Za-z0-9_.-]+\" title=\"3-32 letters, numbers, dots, underscores or hyphens\" class=\"w-full rounded-xl bg-slate-900/80 border border-slate-700 px-4 py-3 text-white placeholder-slate-500 focus:border-primary focus:ring-2 focus:ring-primary/40 outline-none\" placeholder=\"new-admin\">"
                        + "</div>"
                        + "<div>"
                        + "<label class=\"block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2\">Password</label>"
                        + "<input type=\"password\" name=\"password\" required minlength=\"12\" maxlength=\"256\" autocomplete=\"new-password\" aria-describedby=\"password-help\" class=\"w-full rounded-xl bg-slate-900/80 border border-slate-700 px-4 py-3 text-white placeholder-slate-500 focus:border-primary focus:ring-2 focus:ring-primary/40 outline-none\" placeholder=\"At least 12 characters\">"
                        + "<p id=\"password-help\" class=\"mt-1.5 text-[11px] text-slate-500\">Use at least 12 characters. Invalid account details never consume the registration code.</p>"
                        + "</div>"
                        + "<div><label class=\"block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2\">Confirm Password</label>"
                        + "<input type=\"password\" name=\"password_confirm\" required minlength=\"12\" maxlength=\"256\" autocomplete=\"new-password\" class=\"w-full rounded-xl bg-slate-900/80 border border-slate-700 px-4 py-3 text-white focus:border-primary focus:ring-2 focus:ring-primary/40 outline-none\" placeholder=\"Repeat password\"></div>"
                        + (setupRequired ? setupProfiles() : "")
                        + (!setupRequired
                                ? "<div>"
                                        + "<label class=\"block text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2\">Owner 2FA Code (30s)</label>"
                                        + "<input type=\"text\" name=\"owner_2fa_code\" required maxlength=\"6\" class=\"w-full rounded-xl bg-slate-900/80 border border-slate-700 px-4 py-3 text-center tracking-[0.2em] font-mono text-white placeholder-slate-500 focus:border-primary focus:ring-2 focus:ring-primary/40 outline-none\" placeholder=\"123456\">"
                                        + "<p class=\"text-[11px] text-slate-500 mt-1\">Ask the MAIN_ADMIN for the current 30-second code.</p>"
                                        + "</div>"
                                : "")
                        + "<button type=\"submit\" class=\"w-full rounded-xl bg-primary text-background-dark font-semibold py-3 hover:bg-white transition-all shadow-glow-primary\">Complete Setup</button>"
                        + "</form>"
                        + "<div class=\"px-8 pb-6 text-center\">"
                        + "<a href=\"/\" class=\"text-xs text-primary hover:text-white transition-colors\">Back to Login</a>"
                        + "</div>"
                        + "<script>(function(){var f=document.getElementById('dash-setup-form');if(!f)return;f.addEventListener('submit',function(e){var a=f.elements.password,b=f.elements.password_confirm;if(a&&b&&a.value!==b.value){e.preventDefault();b.setCustomValidity('Passwords do not match.');b.reportValidity();}else if(b)b.setCustomValidity('');});})();</script>"
                        + "</div>";

        return HtmlTemplate.authPage("Setup", content);
    }

    private static String setupProfiles() {
        return "<fieldset class='space-y-2 pt-2'><legend class='mb-2 text-xs font-semibold uppercase tracking-wider text-slate-400'>Feature profile</legend>"
                + profile("minimal", "Light", "Dashboard, console, players, files, plugins, updates and essential settings.", false)
                + profile("normal", "Normal", "Light pages plus tickets, notifications, graphs, users, audit and plugin settings.", true)
                + profile("full", "Full functionality", "Normal pages plus every advanced administration workspace; optional beta pages still require the beta opt-in below.", false)
                + "</fieldset><label class='flex items-start gap-3 rounded-xl border border-amber-400/25 bg-amber-400/5 p-3'><input type='checkbox' name='beta_opt_in' value='true' class='mt-0.5 h-4 w-4 rounded border-slate-600 bg-slate-900 text-primary'><span><b class='block text-sm text-white'>Opt in to beta features</b><small class='mt-1 block text-[11px] leading-relaxed text-slate-400'>Enables Dash AI, Intelligence, Maintenance and Guardian. These can be disabled individually later.</small></span></label>";
    }

    private static String profile(String value, String title, String description, boolean selected) {
        return "<label class='flex cursor-pointer items-start gap-3 rounded-xl border border-white/10 bg-white/[0.025] p-3 hover:border-primary/35'><input type='radio' name='setup_profile' value='" + value + "'"
                + (selected ? " checked" : "")
                + " class='mt-0.5 h-4 w-4 border-slate-600 bg-slate-900 text-primary'><span><b class='flex items-center gap-2 text-sm text-white'>"
                + title + (selected ? " <em class='rounded border border-primary/30 px-1.5 py-0.5 text-[9px] not-italic uppercase text-primary'>Recommended</em>" : "")
                + "</b><small class='mt-1 block text-[11px] leading-relaxed text-slate-500'>" + description + "</small></span></label>";
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
