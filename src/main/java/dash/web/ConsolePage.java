package dash.web;

public class ConsolePage {

    public static String render() {
        String content = HtmlTemplate.statsHeader() +
                "<main class=\"flex-1 p-6 flex flex-col overflow-hidden\">\n" +
                "<div class=\"flex-1 relative rounded-3xl bg-[#000000]/60 backdrop-blur-xl border border-glass-border overflow-hidden flex flex-col shadow-inner\">\n"
                +
                "<div class=\"flex items-center justify-between px-6 py-3 border-b border-white/5 bg-white/5 flex-shrink-0\">\n"
                +
                "<div class=\"flex items-center gap-2\">\n" +
                "<span class=\"material-symbols-outlined text-primary text-[18px]\">terminal</span>\n" +
                "<span class=\"text-sm font-mono font-medium text-slate-300\">Server Console</span>\n" +
                "</div>\n" +
                "<div class=\"flex items-center gap-3\">\n" +
                "<button onclick=\"document.getElementById('console').innerText='';\" class=\"px-3 py-1 rounded-lg text-xs font-medium bg-slate-700 text-slate-300 hover:bg-slate-600 transition-colors\">Clear</button>\n"
                +
                "<div class=\"flex gap-1.5\">\n" +
                "<div class=\"h-2.5 w-2.5 rounded-full bg-rose-500/20\"></div>\n" +
                "<div class=\"h-2.5 w-2.5 rounded-full bg-amber-500/20\"></div>\n" +
                "<div class=\"h-2.5 w-2.5 rounded-full bg-emerald-500 animate-pulse\"></div>\n" +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div id=\"console\" class=\"flex-1 overflow-y-auto p-6 font-mono text-sm leading-relaxed console-scrollbar whitespace-pre-wrap text-emerald-400 bg-black/30\">Loading...</div>\n"
                +
                "</div>\n" +
                "<div class=\"h-16 flex-shrink-0 mt-4\">\n" +
                "<form action='/action' method='post' class=\"relative\">\n" +
                "<input type='hidden' name='action' value='command'>\n" +
                "<input name=\"cmd\" id=\"cmd-input\" class=\"w-full h-14 rounded-full bg-glass-surface backdrop-blur-xl border border-glass-border px-8 pr-16 text-white placeholder-slate-500 font-mono focus:outline-none focus:ring-2 focus:ring-primary/50 focus:border-primary/50 transition-all shadow-lg text-sm\" placeholder=\"Enter command... (e.g. say Hello, give Player diamond 1)\" type=\"text\" autocomplete=\"off\"/>\n"
                +
                "<button type=\"submit\" class=\"absolute right-3 top-1/2 -translate-y-1/2 h-10 w-10 rounded-full bg-primary flex items-center justify-center text-background-dark hover:bg-white hover:scale-105 transition-all shadow-glow-primary\">\n"
                +
                "<span class=\"material-symbols-outlined\">send</span>\n" +
                "</button>\n" +
                "</form>\n" +
                "</div>\n" +
                "</main>\n" +
                HtmlTemplate.statsScript() +
                "<script>\n" +
                "function pollConsole() {\n" +
                "  fetch('/api/console').then(r => r.text()).then(t => {\n" +
                "    let el = document.getElementById('console');\n" +
                "    if(el && el.innerText !== t) {\n" +
                "      el.innerText = t;\n" +
                "      el.scrollTop = el.scrollHeight;\n" +
                "    }\n" +
                "  }).catch(e => {});\n" +
                "}\n" +
                "pollConsole();\n" +
                "setInterval(pollConsole, 1000);\n" +
                "document.getElementById('cmd-input').focus();\n" +
                "</script>\n";

        return HtmlTemplate.page("Console", "/console", content);
    }
}
