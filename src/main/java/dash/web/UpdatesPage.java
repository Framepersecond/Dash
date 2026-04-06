package dash.web;

public class UpdatesPage {

    public static String render(String currentVersion, String latestVersion, boolean updateAvailable,
            boolean updatePrepared, boolean updaterEnabled) {
        String safeCurrent = escapeHtml(currentVersion == null || currentVersion.isBlank() ? "unknown" : currentVersion);
        String safeLatest = escapeHtml(latestVersion == null || latestVersion.isBlank() ? safeCurrent : latestVersion);

        String statusBadge;
        if (!updaterEnabled) {
            statusBadge = "<span class=\"px-3 py-1 rounded-full bg-rose-500/20 text-rose-300 text-xs font-semibold\">Updater Disabled</span>";
        } else if (updatePrepared) {
            statusBadge = "<span class=\"px-3 py-1 rounded-full bg-emerald-500/20 text-emerald-300 text-xs font-semibold\">Ready to Apply</span>";
        } else if (updateAvailable) {
            statusBadge = "<span class=\"px-3 py-1 rounded-full bg-amber-500/20 text-amber-300 text-xs font-semibold\">Update Available</span>";
        } else {
            statusBadge = "<span class=\"px-3 py-1 rounded-full bg-slate-600/40 text-slate-300 text-xs font-semibold\">Up to Date</span>";
        }

        String primaryAction = "";
        if (updaterEnabled && updateAvailable && !updatePrepared) {
            primaryAction = "<button id=\"update-download-btn\" class=\"w-full sm:w-auto px-6 py-3 rounded-xl bg-primary/20 text-primary border border-primary/30 hover:bg-primary hover:text-black transition-all font-semibold\">Download Now</button>";
        }

        String restartAction = "";
        if (updatePrepared) {
            restartAction = "<button id=\"update-restart-btn\" class=\"w-full sm:w-auto px-7 py-3 rounded-xl bg-emerald-500 text-white hover:bg-emerald-400 transition-all font-bold shadow-lg\">Stop &amp; Apply Update</button>";
        }

        String pendingNote = updatePrepared
                ? "<p class=\"text-sm text-emerald-300 mt-2\">Update is staged and will be applied automatically when the server stops. You can also click the button above to stop and apply it now.</p>"
                : "";

        String disabledHint = updaterEnabled
                ? ""
                : "<p class=\"text-sm text-rose-300\">Updater is disabled. Check the server console for the exact reason.</p>";

        String content = HtmlTemplate.statsHeader()
                + "<main class=\"p-4 sm:p-6 flex-1 w-full\">"
                + "<div class=\"flex flex-col gap-6 w-full\">"
                + "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border p-6\">"
                + "<div class=\"flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3\">"
                + "<h2 class=\"text-xl font-bold text-white\">Dash Updates</h2>"
                + statusBadge
                + "</div>"
                + "<p class=\"mt-2 text-slate-400 text-sm\">Manage plugin updates directly from the panel.</p>"
                + "</div>"

                + "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border p-6\">"
                + "<div class=\"grid grid-cols-1 md:grid-cols-2 gap-4\">"
                + "<div class=\"p-4 rounded-xl bg-white/5 border border-white/10\">"
                + "<p class=\"text-xs text-slate-400 uppercase tracking-wider\">Current Version</p>"
                + "<p class=\"mt-2 text-2xl font-bold text-white\">" + safeCurrent + "</p>"
                + "</div>"
                + "<div class=\"p-4 rounded-xl bg-white/5 border border-white/10\">"
                + "<p class=\"text-xs text-slate-400 uppercase tracking-wider\">Latest Version</p>"
                + "<p class=\"mt-2 text-2xl font-bold text-primary\">" + safeLatest + "</p>"
                + "</div>"
                + "</div>"
                + "<div class=\"mt-6 flex flex-col sm:flex-row gap-3\">"
                + primaryAction
                + restartAction
                + "</div>"
                + pendingNote
                + disabledHint
                + "</div>"
                + "</div>"
                + "</main>"
                + HtmlTemplate.statsScript()
                + "<script>"
                + "document.getElementById('update-download-btn')?.addEventListener('click', async function(){"
                + "  const btn=this; btn.disabled=true;"
                + "  try {"
                + "    const res = await fetch('/api/update/download',{method:'POST'});"
                + "    const data = await res.json();"
                + "    if (data && data.success) { showToast('Update downloaded! It will be applied automatically when the server stops.', 'success'); if(window.dashNavigate){dashNavigate(location.pathname+location.search,false);} }"
                + "    else { showToast((data && data.error) ? data.error : 'Update download failed.', 'error'); btn.disabled=false; }"
                + "  } catch (e) { showToast('Update download failed.', 'error'); btn.disabled=false; }"
                + "});"
                + "document.getElementById('update-restart-btn')?.addEventListener('click', async function(){"
                + "  if (!confirm('Stop the server now to apply the update?')) return;"
                + "  const btn=this; btn.disabled=true;"
                + "  try {"
                + "    const res = await fetch('/api/update/restart',{method:'POST'});"
                + "    const data = await res.json();"
                + "    if (data && data.success) { showToast('Server is shutting down. The update will be applied automatically.', 'success'); }"
                + "    else { showToast((data && data.error) ? data.error : 'Restart trigger failed.', 'error'); btn.disabled=false; }"
                + "  } catch (e) { showToast('Restart trigger failed.', 'error'); btn.disabled=false; }"
                + "});"
                + "</script>";

        return HtmlTemplate.page("Updates", "/updates", content);
    }

    private static String escapeHtml(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

