package dash.web;

import java.util.Map;

/** Replay-safe reason challenge for guarded actions that intentionally use full-page submission. */
public final class GuardrailChallengePage {
    private GuardrailChallengePage() {
    }

    public static String render(String message, Map<String, String> parameters) {
        StringBuilder hidden = new StringBuilder();
        if (parameters != null) {
            parameters.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                    .filter(entry -> !"reason".equalsIgnoreCase(entry.getKey()))
                    .filter(entry -> !"password".equalsIgnoreCase(entry.getKey()))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> hidden.append("<input type='hidden' name='")
                            .append(esc(entry.getKey())).append("' value='")
                            .append(esc(entry.getValue())).append("'>"));
        }
        String returnTo = parameters == null ? "/" : safeReturn(parameters.get("return_to"));
        String content = "<main class='flex-1 min-w-0 p-4 sm:p-6'><div class='mx-auto flex min-h-[70vh] max-w-xl items-center'>"
                + "<section class='w-full overflow-hidden rounded-lg border border-amber-400/30 bg-slate-900/90 shadow-2xl'>"
                + "<div class='border-b border-slate-700/70 bg-amber-400/10 p-5'><div class='flex items-center gap-3'>"
                + "<span class='material-symbols-outlined grid h-10 w-10 place-items-center rounded-md bg-amber-300 text-slate-950'>policy</span>"
                + "<div><p class='text-xs font-bold uppercase text-amber-300'>Action Guardrail</p>"
                + "<h1 class='mt-1 text-xl font-bold text-white'>Operator reason required</h1></div></div>"
                + "<p class='mt-3 text-sm text-slate-300'>" + esc(message) + "</p></div>"
                + "<form method='post' action='/action' class='space-y-4 p-5'>" + hidden
                + "<label class='block'><span class='mb-2 block text-xs font-bold text-slate-300'>Reason</span>"
                + "<textarea name='reason' rows='4' maxlength='500' required autofocus placeholder='Describe why this action is necessary now' class='w-full rounded-md border border-slate-600 bg-slate-950/70 p-3 text-sm text-white outline-none focus:border-cyan-400'></textarea></label>"
                + "<div class='flex flex-col-reverse gap-2 sm:flex-row sm:justify-end'><a href='" + esc(returnTo)
                + "' class='rounded-md border border-slate-600 px-4 py-2.5 text-center text-sm font-bold text-slate-200'>Cancel</a>"
                + "<button class='inline-flex items-center justify-center gap-2 rounded-md bg-amber-300 px-4 py-2.5 text-sm font-bold text-slate-950'><span class='material-symbols-outlined text-[18px]'>verified_user</span>Continue guarded action</button></div>"
                + "</form></section></div></main>";
        return HtmlTemplate.page("Reason required", "", content);
    }

    private static String safeReturn(String value) {
        if (value != null && value.startsWith("/") && !value.startsWith("//")
                && !value.contains("\\") && !value.contains("\r") && !value.contains("\n")) {
            return value;
        }
        return "/";
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
