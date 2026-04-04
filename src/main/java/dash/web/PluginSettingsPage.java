package dash.web;

import dash.Dash;
import dash.DiscordWebhookManager;
import dash.DiscordWebhookManager.WebhookEntry;

import java.util.List;

/**
 * Web page for managing core plugin settings (config.yml) and Discord webhooks.
 */
public class PluginSettingsPage {

    public static String render(String message) {
        boolean canWrite = HtmlTemplate.can("dash.web.pluginsettings.write");

        int currentPort = Dash.getWebPort();
        String panelUrl = Dash.getInstance().getConfig().getString("panel-url", "");
        int maxBackups = Dash.getInstance().getConfig().getInt("backups.max-backups", 10);
        boolean bridgeEnabled = Dash.getInstance().getConfig().getBoolean("bridge.enabled", true);
        String bridgeSecret = Dash.getInstance().getConfig().getString("bridge.secret", "");
        String bridgeMasterUrl = Dash.getInstance().getConfig().getString("bridge.master_url", "");

        // -- Webhook section --
        DiscordWebhookManager whMgr = Dash.getDiscordWebhookManager();
        List<WebhookEntry> webhooks = whMgr != null ? whMgr.getWebhooks() : List.of();

        StringBuilder webhookRows = new StringBuilder();
        for (int i = 0; i < webhooks.size(); i++) {
            WebhookEntry wh = webhooks.get(i);
            webhookRows.append("<div class=\"p-4 rounded-xl bg-white/5 border border-white/5 space-y-3\" data-wh-row>\n");
            webhookRows.append("<div class=\"flex items-center gap-2\">\n");
            webhookRows.append("<input type=\"text\" name=\"wh_url_").append(i).append("\" value=\"").append(escapeHtml(wh.url()))
                    .append("\" placeholder=\"https://discord.com/api/webhooks/...\" ")
                    .append(canWrite ? "" : "readonly ")
                    .append("class=\"flex-1 bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white font-mono placeholder-slate-500 focus:border-primary outline-none")
                    .append(canWrite ? "" : " opacity-50 cursor-not-allowed")
                    .append("\">\n");
            if (canWrite) {
                webhookRows.append("<button type=\"button\" onclick=\"this.closest('[data-wh-row]').remove()\" class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-rose-400 hover:bg-rose-500/10 transition-colors\">\n")
                        .append("<span class=\"material-symbols-outlined text-[18px]\">delete</span>\n")
                        .append("</button>\n");
            }
            webhookRows.append("</div>\n");

            // Event checkboxes
            webhookRows.append("<div class=\"flex flex-wrap gap-3 pl-1\">\n");
            for (String evt : DiscordWebhookManager.ALL_EVENTS) {
                boolean checked = wh.events().contains(evt);
                webhookRows.append("<label class=\"flex items-center gap-1.5 text-xs text-slate-300 cursor-pointer select-none\">\n")
                        .append("<input type=\"checkbox\" name=\"wh_evt_").append(i).append("_").append(evt).append("\"")
                        .append(checked ? " checked" : "")
                        .append(canWrite ? "" : " disabled")
                        .append(" class=\"rounded border-slate-600 bg-slate-800 text-primary focus:ring-primary/50\">\n")
                        .append("<span>").append(eventLabel(evt)).append("</span>\n")
                        .append("</label>\n");
            }
            webhookRows.append("</div>\n");
            webhookRows.append("</div>\n");
        }

        String messageHtml = "";
        if (message != null && !message.isEmpty()) {
            boolean isError = message.toLowerCase().contains("error") || message.toLowerCase().contains("fail");
            messageHtml = "<div class=\"mb-4 px-4 py-3 rounded-xl text-sm font-medium "
                    + (isError ? "bg-rose-500/20 text-rose-400 border border-rose-500/20" : "bg-emerald-500/20 text-emerald-400 border border-emerald-500/20")
                    + "\">" + escapeHtml(message) + "</div>\n";
        }

        String content = HtmlTemplate.statsHeader()
                + "<main class=\"p-4 sm:p-6 flex-1 w-full\">\n"
                + messageHtml
                + "<form method=\"post\" action=\"/action\" id=\"plugin-settings-form\" class=\"flex flex-col gap-6 w-full\">\n"
                + "<input type=\"hidden\" name=\"action\" value=\"save_plugin_settings\">\n"

                // -- General Settings Card --
                + "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden mb-6\">\n"
                + "<div class=\"flex items-center gap-3 px-6 py-4 border-b border-white/5\">\n"
                + "<span class=\"material-symbols-outlined text-primary\">tune</span>\n"
                + "<h2 class=\"text-lg font-bold text-white\">General Settings</h2>\n"
                + "</div>\n"
                + "<div class=\"p-6 grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6\">\n"

                // Web Port
                + "<div>\n"
                + "<label class=\"block text-xs font-medium text-slate-400 uppercase tracking-wider mb-2\">Web Port</label>\n"
                + "<input type=\"number\" name=\"web_port\" value=\"" + currentPort + "\" min=\"1\" max=\"65535\" "
                + (canWrite ? "" : "readonly ")
                + "class=\"w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white font-mono focus:border-primary outline-none"
                + (canWrite ? "" : " opacity-50 cursor-not-allowed") + "\">\n"
                + "<p class=\"mt-1 text-xs text-amber-400/80\">⚠ Changing port requires a server restart</p>\n"
                + "</div>\n"

                // Panel URL
                + "<div>\n"
                + "<label class=\"block text-xs font-medium text-slate-400 uppercase tracking-wider mb-2\">Panel URL</label>\n"
                + "<input type=\"text\" name=\"panel_url\" value=\"" + escapeHtml(panelUrl) + "\" placeholder=\"https://panel.example.com\" "
                + (canWrite ? "" : "readonly ")
                + "class=\"w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white font-mono placeholder-slate-500 focus:border-primary outline-none"
                + (canWrite ? "" : " opacity-50 cursor-not-allowed") + "\">\n"
                + "<p class=\"mt-1 text-xs text-slate-500\">Public base URL for clickable setup links</p>\n"
                + "</div>\n"

                // Max Backups
                + "<div>\n"
                + "<label class=\"block text-xs font-medium text-slate-400 uppercase tracking-wider mb-2\">Max Backups</label>\n"
                + "<input type=\"number\" name=\"max_backups\" value=\"" + maxBackups + "\" min=\"1\" max=\"100\" "
                + (canWrite ? "" : "readonly ")
                + "class=\"w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white font-mono focus:border-primary outline-none"
                + (canWrite ? "" : " opacity-50 cursor-not-allowed") + "\">\n"
                + "</div>\n"

                + "</div>\n"
                + "</div>\n"

                // -- NeoBridge Card --
                + "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden mb-6\">\n"
                + "<div class=\"flex items-center gap-3 px-6 py-4 border-b border-white/5\">\n"
                + "<span class=\"material-symbols-outlined text-emerald-400\">hub</span>\n"
                + "<h2 class=\"text-lg font-bold text-white\">NeoBridge Settings</h2>\n"
                + "</div>\n"
                + "<div class=\"p-6 grid grid-cols-1 md:grid-cols-2 gap-6\">\n"
                + "<label class=\"flex items-center justify-between p-3 rounded-lg bg-white/5\">\n"
                + "<span class=\"text-sm text-white\">Bridge Enabled</span>\n"
                + "<input type=\"checkbox\" name=\"bridge_enabled\"" + (bridgeEnabled ? " checked " : " ")
                + (canWrite ? "" : "disabled ")
                + "class=\"h-4 w-4 rounded accent-emerald-400" + (canWrite ? "" : " opacity-50 cursor-not-allowed") + "\">\n"
                + "</label>\n"
                + "<div>\n"
                + "<label class=\"block text-xs font-medium text-slate-400 uppercase tracking-wider mb-2\">Bridge Secret</label>\n"
                + "<div class=\"relative\">\n"
                + "<input id=\"bridge-secret-input\" type=\"password\" name=\"bridge_secret\" value=\"" + escapeHtml(bridgeSecret) + "\" "
                + (canWrite ? "" : "readonly ")
                + "class=\"w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 pr-10 text-sm text-white font-mono placeholder-slate-500 focus:border-primary outline-none"
                + (canWrite ? "" : " opacity-50 cursor-not-allowed") + "\" placeholder=\"your-super-secret-key\">\n"
                + "<button type=\"button\" id=\"bridge-secret-toggle\""
                + (canWrite ? "" : " disabled")
                + " class=\"absolute right-2 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-200 transition-colors"
                + (canWrite ? "" : " opacity-50 cursor-not-allowed") + "\">\n"
                + "<span id=\"bridge-secret-eye\" class=\"material-symbols-outlined text-[18px]\">visibility</span>\n"
                + "</button>\n"
                + "</div>\n"
                + "</div>\n"
                + "<div class=\"md:col-span-2\">\n"
                + "<label class=\"block text-xs font-medium text-slate-400 uppercase tracking-wider mb-2\">NeoDash Master URL</label>\n"
                + "<input type=\"text\" name=\"bridge_master_url\" value=\"" + escapeHtml(bridgeMasterUrl) + "\" "
                + (canWrite ? "" : "readonly ")
                + "class=\"w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white font-mono placeholder-slate-500 focus:border-primary outline-none"
                + (canWrite ? "" : " opacity-50 cursor-not-allowed") + "\" placeholder=\"https://neodash.example.com\">\n"
                + "<p class=\"mt-1 text-xs text-slate-500\">Used for the sidebar \"Back to NeoDash\" button on SSO accounts.</p>\n"
                + "</div>\n"
                + "</div>\n"
                + "</div>\n"

                // -- Discord Webhooks Card --
                + "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden mb-6\">\n"
                + "<div class=\"flex items-center justify-between px-6 py-4 border-b border-white/5\">\n"
                + "<div class=\"flex items-center gap-3\">\n"
                + "<span class=\"material-symbols-outlined text-primary\">webhook</span>\n"
                + "<h2 class=\"text-lg font-bold text-white\">Discord Webhooks</h2>\n"
                + "<span class=\"px-2 py-0.5 rounded-full bg-primary/20 text-primary text-xs font-mono\">" + webhooks.size() + " configured</span>\n"
                + "</div>\n"
                + (canWrite
                    ? "<button type=\"button\" id=\"add-webhook-btn\" class=\"w-full sm:w-auto flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-primary/20 text-primary hover:bg-primary hover:text-black transition-colors text-sm font-medium\">\n"
                      + "<span class=\"material-symbols-outlined text-[18px]\">add</span>\n"
                      + "<span>Add Webhook</span>\n"
                      + "</button>\n"
                    : "")
                + "</div>\n"
                + "<div id=\"webhook-list\" class=\"p-6 space-y-4\">\n"
                + webhookRows.toString()
                + "</div>\n"
                + "</div>\n"

                // -- Save Button --
                + (canWrite
                    ? "<div class=\"flex flex-col sm:flex-row gap-3 sm:items-end w-full sm:justify-end\">\n"
                      + "<button type=\"submit\" class=\"w-full sm:w-auto flex items-center justify-center gap-2 px-8 py-3 rounded-full bg-primary/10 border border-primary/20 text-primary hover:bg-primary hover:text-black hover:shadow-glow-primary transition-all duration-300 font-semibold\">\n"
                      + "<span class=\"material-symbols-outlined text-[20px]\">save</span>\n"
                      + "<span>Save Settings</span>\n"
                      + "</button>\n"
                      + "</div>\n"
                    : "")

                + "</form>\n"
                + "</main>\n"
                + HtmlTemplate.statsScript()
                + webhookScript(canWrite)
                ;

        return HtmlTemplate.page("Plugin Settings", "/plugin-settings", content);
    }

    private static String webhookScript(boolean canWrite) {
        if (!canWrite) return "";
        return "<script>\n"
                + "function showSettingsToast(message, kind) {\n"
                + "  const hostId = 'settings-toast-host';\n"
                + "  let host = document.getElementById(hostId);\n"
                + "  if (!host) {\n"
                + "    host = document.createElement('div');\n"
                + "    host.id = hostId;\n"
                + "    host.className = 'fixed top-4 right-4 z-50 flex flex-col gap-2 pointer-events-none';\n"
                + "    document.body.appendChild(host);\n"
                + "  }\n"
                + "  const toast = document.createElement('div');\n"
                + "  const ok = kind === 'success';\n"
                + "  toast.className = 'pointer-events-auto min-w-[220px] max-w-sm rounded-xl border px-4 py-3 text-sm shadow-2xl backdrop-blur-xl transition-opacity duration-300 ' +\n"
                + "    (ok ? 'bg-emerald-500/20 text-emerald-300 border-emerald-500/30' : 'bg-rose-500/20 text-rose-300 border-rose-500/30');\n"
                + "  toast.textContent = message;\n"
                + "  host.appendChild(toast);\n"
                + "  setTimeout(() => { toast.style.opacity = '0'; }, 2600);\n"
                + "  setTimeout(() => { toast.remove(); }, 3000);\n"
                + "}\n"
                + "async function saveSettings(form) {\n"
                + "  const formData = new FormData(form);\n"
                + "  formData.delete('action');\n"
                + "  const payload = new URLSearchParams();\n"
                + "  for (const [k, v] of formData.entries()) { payload.append(k, v); }\n"
                + "  const res = await fetch('/api/settings', {\n"
                + "    method: 'POST',\n"
                + "    headers: {'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'},\n"
                + "    body: payload.toString()\n"
                + "  });\n"
                + "  let json = null;\n"
                + "  try { json = await res.json(); } catch (e) {}\n"
                + "  if (!res.ok || !json || json.success !== true) {\n"
                + "    throw new Error((json && json.error) ? json.error : 'Save failed');\n"
                + "  }\n"
                + "}\n"
                + "let whIdx = document.querySelectorAll('[data-wh-row]').length;\n"
                + "document.getElementById('add-webhook-btn')?.addEventListener('click', () => {\n"
                + "  const list = document.getElementById('webhook-list');\n"
                + "  const row = document.createElement('div');\n"
                + "  row.className = 'p-4 rounded-xl bg-white/5 border border-white/5 space-y-3';\n"
                + "  row.setAttribute('data-wh-row', '');\n"
                + "  const events = " + eventsJsonArray() + ";\n"
                + "  const labels = " + eventLabelsJsonObj() + ";\n"
                + "  let evtHtml = '';\n"
                + "  events.forEach(e => {\n"
                + "    evtHtml += '<label class=\"flex items-center gap-1.5 text-xs text-slate-300 cursor-pointer select-none\">' +\n"
                + "      '<input type=\"checkbox\" name=\"wh_evt_'+whIdx+'_'+e+'\" class=\"rounded border-slate-600 bg-slate-800 text-primary focus:ring-primary/50\">' +\n"
                + "      '<span>'+labels[e]+'</span></label>';\n"
                + "  });\n"
                + "  row.innerHTML = '<div class=\"flex items-center gap-2\">' +\n"
                + "    '<input type=\"text\" name=\"wh_url_'+whIdx+'\" placeholder=\"https://discord.com/api/webhooks/...\" class=\"flex-1 bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white font-mono placeholder-slate-500 focus:border-primary outline-none\">' +\n"
                + "    '<button type=\"button\" onclick=\"this.closest(\\'[data-wh-row]\\').remove()\" class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-rose-400 hover:bg-rose-500/10 transition-colors\"><span class=\"material-symbols-outlined text-[18px]\">delete</span></button>' +\n"
                + "    '</div>' +\n"
                + "    '<div class=\"flex flex-wrap gap-3 pl-1\">' + evtHtml + '</div>';\n"
                + "  list.appendChild(row);\n"
                + "  whIdx++;\n"
                + "});\n"

                // Before form submit, re-index all webhook rows for consistent parsing
                + "document.getElementById('plugin-settings-form')?.addEventListener('submit', async (e) => {\n"
                + "  const rows = document.querySelectorAll('[data-wh-row]');\n"
                + "  let hiddenContainer = document.getElementById('wh-hidden-fields');\n"
                + "  if (!hiddenContainer) {\n"
                + "    hiddenContainer = document.createElement('div');\n"
                + "    hiddenContainer.id = 'wh-hidden-fields';\n"
                + "    e.target.appendChild(hiddenContainer);\n"
                + "  }\n"
                + "  hiddenContainer.innerHTML = '';\n"
                + "  let input = document.createElement('input');\n"
                + "  input.type = 'hidden'; input.name = 'wh_count'; input.value = rows.length;\n"
                + "  hiddenContainer.appendChild(input);\n"
                + "  e.preventDefault();\n"
                + "  const submitBtn = e.target.querySelector('button[type=\"submit\"]');\n"
                + "  const oldText = submitBtn ? submitBtn.innerHTML : null;\n"
                + "  if (submitBtn) { submitBtn.disabled = true; submitBtn.innerHTML = '<span>Saving...</span>'; }\n"
                + "  try {\n"
                + "    await saveSettings(e.target);\n"
                + "    showSettingsToast('Settings saved successfully', 'success');\n"
                + "  } catch (err) {\n"
                + "    showSettingsToast((err && err.message) ? err.message : 'Failed to save settings', 'error');\n"
                + "    e.target.submit();\n"
                + "  } finally {\n"
                + "    if (submitBtn) { submitBtn.disabled = false; if (oldText !== null) submitBtn.innerHTML = oldText; }\n"
                + "  }\n"
                + "});\n"
                + "document.getElementById('bridge-secret-toggle')?.addEventListener('click', () => {\n"
                + "  const input = document.getElementById('bridge-secret-input');\n"
                + "  const eye = document.getElementById('bridge-secret-eye');\n"
                + "  if (!input) return;\n"
                + "  const showing = input.type === 'text';\n"
                + "  input.type = showing ? 'password' : 'text';\n"
                + "  if (eye) eye.textContent = showing ? 'visibility' : 'visibility_off';\n"
                + "});\n"
                + "</script>\n";
    }

    private static String eventsJsonArray() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < DiscordWebhookManager.ALL_EVENTS.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("'").append(DiscordWebhookManager.ALL_EVENTS[i]).append("'");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String eventLabelsJsonObj() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < DiscordWebhookManager.ALL_EVENTS.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("'").append(DiscordWebhookManager.ALL_EVENTS[i]).append("':'")
              .append(eventLabel(DiscordWebhookManager.ALL_EVENTS[i])).append("'");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String eventLabel(String event) {
        return switch (event) {
            case DiscordWebhookManager.EVENT_AUDIT -> "Audit Logs";
            case DiscordWebhookManager.EVENT_CHAT -> "Player Chat";
            case DiscordWebhookManager.EVENT_SERVER_START_STOP -> "Server Start/Stop";
            case DiscordWebhookManager.EVENT_CONSOLE_WARNINGS -> "Console Warnings";
            default -> event;
        };
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}

