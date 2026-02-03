package dash.web;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import dash.Dash;
import dash.data.BackupManager;
import dash.data.DatapackManager;

import java.io.*;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;

public class SettingsPage {

    public static String render() {
        World overworld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);

        StringBuilder gamerulesHtml = new StringBuilder();
        if (overworld != null) {
            Object[][] booleanRules = {
                    { GameRule.KEEP_INVENTORY, "keepInventory", "Keep Inventory", "Players keep inventory on death" },
                    { GameRule.DO_MOB_SPAWNING, "doMobSpawning", "Mob Spawning", "Mobs spawn naturally" },
                    { GameRule.DO_DAYLIGHT_CYCLE, "doDaylightCycle", "Daylight Cycle", "Time passes naturally" },
                    { GameRule.DO_WEATHER_CYCLE, "doWeatherCycle", "Weather Cycle", "Weather changes naturally" },
                    { GameRule.MOB_GRIEFING, "mobGriefing", "Mob Griefing", "Mobs can modify blocks" },
                    { GameRule.DO_FIRE_TICK, "doFireTick", "Fire Spread", "Fire spreads and extinguishes" },
                    { GameRule.NATURAL_REGENERATION, "naturalRegeneration", "Natural Regen",
                            "Players regenerate health" }
            };

            for (Object[] rule : booleanRules) {
                @SuppressWarnings("unchecked")
                GameRule<Boolean> gameRule = (GameRule<Boolean>) rule[0];
                String ruleName = (String) rule[1];
                String label = (String) rule[2];
                String desc = (String) rule[3];

                Boolean value = overworld.getGameRuleValue(gameRule);
                if (value == null)
                    value = false;

                gamerulesHtml.append(
                        "<div class=\"flex items-center justify-between p-3 rounded-lg bg-white/5 hover:bg-white/10 transition-colors\">\n")
                        .append("<div><p class=\"text-white font-medium text-sm\">").append(label).append("</p>")
                        .append("<p class=\"text-slate-500 text-xs\">").append(desc).append("</p></div>\n")
                        .append("<form action='/action' method='post' class='inline'>")
                        .append("<input type='hidden' name='action' value='gamerule'>")
                        .append("<input type='hidden' name='rule' value='").append(ruleName).append("'>")
                        .append("<input type='hidden' name='value' value='").append(!value)
                        .append("'>")
                        .append("<button type='submit' class=\"relative inline-flex h-6 w-11 items-center rounded-full transition-colors ")
                        .append(value ? "bg-primary" : "bg-slate-600").append("\">")
                        .append("<span class=\"inline-block h-4 w-4 transform rounded-full bg-white transition-transform ")
                        .append(value ? "translate-x-6" : "translate-x-1").append("\"></span>")
                        .append("</button></form></div>\n");
            }
        }

        StringBuilder whitelistHtml = new StringBuilder();
        for (OfflinePlayer op : Bukkit.getWhitelistedPlayers()) {
            whitelistHtml.append("<div class=\"flex items-center justify-between p-2 rounded bg-white/5\">")
                    .append("<span class=\"text-white text-sm\">").append(op.getName()).append("</span>")
                    .append("<form action='/action' method='post' class='inline'>")
                    .append("<input type='hidden' name='action' value='whitelist_remove'>")
                    .append("<input type='hidden' name='player' value='").append(op.getName()).append("'>")
                    .append("<button class=\"text-slate-400 hover:text-rose-400\"><span class=\"material-symbols-outlined text-[16px]\">close</span></button>")
                    .append("</form></div>\n");
        }
        if (whitelistHtml.length() == 0) {
            whitelistHtml.append("<p class=\"text-slate-500 text-center text-sm py-2\">No whitelisted players</p>\n");
        }

        StringBuilder backupsHtml = new StringBuilder();
        BackupManager backupMgr = Dash.getBackupManager();
        if (backupMgr != null) {
            List<BackupManager.BackupInfo> backups = backupMgr.listBackups();
            for (BackupManager.BackupInfo backup : backups) {
                backupsHtml.append("<div class=\"flex items-center justify-between p-3 rounded-lg bg-white/5\">")
                        .append("<div><p class=\"text-white text-sm font-mono\">").append(backup.name()).append("</p>")
                        .append("<p class=\"text-slate-500 text-xs\">").append(backup.getFormattedDate()).append(" • ")
                        .append(backup.getFormattedSize()).append("</p></div>")
                        .append("<div class=\"flex gap-2\">")
                        .append("<a href='/api/backups/download?name=").append(backup.name())
                        .append("' class=\"px-2 py-1 rounded bg-primary/20 text-primary text-xs hover:bg-primary hover:text-black transition-colors\">Download</a>")
                        .append("<form action='/action' method='post' class='inline' onsubmit=\"return confirm('Delete backup?');\">")
                        .append("<input type='hidden' name='action' value='backup_delete'><input type='hidden' name='name' value='")
                        .append(backup.name()).append("'>")
                        .append("<button class=\"px-2 py-1 rounded bg-rose-500/20 text-rose-400 text-xs hover:bg-rose-500 hover:text-white transition-colors\">Delete</button>")
                        .append("</form></div></div>\n");
            }
            if (backups.isEmpty()) {
                backupsHtml.append("<p class=\"text-slate-500 text-center text-sm py-4\">No backups yet</p>\n");
            }
        }

        StringBuilder datapacksHtml = new StringBuilder();
        List<DatapackManager.DatapackInfo> datapacks = DatapackManager.listDatapacks();
        for (DatapackManager.DatapackInfo dp : datapacks) {
            datapacksHtml.append("<div class=\"flex items-center justify-between p-3 rounded-lg bg-white/5\">")
                    .append("<div class=\"flex items-center gap-2\">")
                    .append("<span class=\"material-symbols-outlined text-amber-400\">")
                    .append(dp.isZip() ? "folder_zip" : "folder").append("</span>")
                    .append("<div><p class=\"text-white text-sm\">").append(dp.name()).append("</p>")
                    .append("<p class=\"text-slate-500 text-xs\">").append(dp.description()).append("</p></div></div>")
                    .append("<div class=\"flex gap-2\">")
                    .append("<form action='/action' method='post' class='inline'>")
                    .append("<input type='hidden' name='action' value='datapack_toggle'>")
                    .append("<input type='hidden' name='name' value='").append(dp.name()).append("'>")
                    .append("<input type='hidden' name='enable' value='").append(!dp.enabled()).append("'>")
                    .append("<button class=\"px-2 py-1 rounded text-xs ")
                    .append(dp.enabled() ? "bg-emerald-500/20 text-emerald-400" : "bg-slate-600 text-slate-300")
                    .append("\">")
                    .append(dp.enabled() ? "Enabled" : "Disabled").append("</button></form>")
                    .append("<form action='/action' method='post' class='inline' onsubmit=\"return confirm('Delete datapack?');\">")
                    .append("<input type='hidden' name='action' value='datapack_delete'><input type='hidden' name='name' value='")
                    .append(dp.name()).append("'>")
                    .append("<button class=\"text-slate-400 hover:text-rose-400\"><span class=\"material-symbols-outlined text-[16px]\">delete</span></button>")
                    .append("</form></div></div>\n");
        }
        if (datapacks.isEmpty()) {
            datapacksHtml.append("<p class=\"text-slate-500 text-center text-sm py-4\">No datapacks installed</p>\n");
        }

        String currentMotd = "";
        try {
            File serverProps = new File(Bukkit.getWorldContainer(), "server.properties");
            if (serverProps.exists()) {
                for (String line : Files.readAllLines(serverProps.toPath())) {
                    if (line.startsWith("motd=")) {
                        currentMotd = line.substring(5);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        String iconPreview = "";
        File iconFile = new File(Bukkit.getWorldContainer(), "server-icon.png");
        if (iconFile.exists()) {
            try {
                byte[] iconData = Files.readAllBytes(iconFile.toPath());
                iconPreview = "data:image/png;base64," + Base64.getEncoder().encodeToString(iconData);
            } catch (Exception ignored) {
            }
        }

        boolean sparkInstalled = Bukkit.getPluginManager().getPlugin("spark") != null || isSparkAvailable();
        boolean discordInstalled = Bukkit.getPluginManager().getPlugin("DiscordSRV") != null;

        boolean whitelistEnabled = Bukkit.hasWhitelist();
        int viewDistance = Bukkit.getViewDistance();
        int simDistance = Bukkit.getSimulationDistance();
        int backupSchedule = backupMgr != null ? backupMgr.getScheduleHours() : 0;

        String content = HtmlTemplate.statsHeader() +
                "<main class=\"flex-1 p-6 overflow-auto\">\n" +
                "<div class=\"grid grid-cols-1 lg:grid-cols-3 gap-6\">\n" +

                "<div class=\"space-y-6\">\n" +
                "<div class=\"rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden\">\n"
                +
                "<div class=\"flex items-center gap-3 px-4 py-3 border-b border-white/5\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">sports_esports</span>\n" +
                "<h2 class=\"text-sm font-bold text-white uppercase tracking-wider\">Gamerules</h2>\n" +
                "</div>\n" +
                "<div class=\"p-3 flex flex-col gap-2 max-h-96 overflow-y-auto console-scrollbar\">\n" +
                gamerulesHtml.toString() +
                "</div></div>\n" +

                "<div class=\"rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden\">\n"
                +
                "<div class=\"flex items-center justify-between px-4 py-3 border-b border-white/5\">\n" +
                "<div class=\"flex items-center gap-3\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">shield_person</span>\n" +
                "<h2 class=\"text-sm font-bold text-white uppercase tracking-wider\">Whitelist</h2>\n" +
                "</div>\n" +
                "<form action='/action' method='post' class='inline'><input type='hidden' name='action' value='whitelist_toggle'>\n"
                +
                "<button class=\"px-2 py-1 rounded-full text-xs font-semibold "
                + (whitelistEnabled ? "bg-emerald-500/20 text-emerald-400" : "bg-slate-600 text-slate-300") + "\">"
                + (whitelistEnabled ? "ON" : "OFF") + "</button></form>\n" +
                "</div>\n" +
                "<div class=\"p-3\">\n" +
                "<form action='/action' method='post' class=\"flex gap-2 mb-3\">\n" +
                "<input type='hidden' name='action' value='whitelist_add'>\n" +
                "<input type='text' name='player' placeholder='Player name' class=\"flex-1 bg-slate-800 border border-slate-600 rounded px-3 py-1.5 text-sm text-white placeholder-slate-500 focus:border-primary outline-none\">\n"
                +
                "<button class=\"px-3 py-1.5 rounded bg-primary text-black text-sm font-semibold\">Add</button>\n" +
                "</form>\n" +
                "<div class=\"flex flex-col gap-1 max-h-32 overflow-y-auto console-scrollbar\">\n" +
                whitelistHtml.toString() +
                "</div></div></div>\n" +
                "</div>\n" +

                "<div class=\"space-y-6\">\n" +

                "<div class=\"rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border p-4\">\n" +
                "<div class=\"flex items-center gap-3 mb-4\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">visibility</span>\n" +
                "<h2 class=\"text-sm font-bold text-white uppercase tracking-wider\">View Distance</h2>\n" +
                "</div>\n" +
                "<div class=\"space-y-4\">\n" +
                "<div><div class=\"flex justify-between text-xs mb-1\"><span class=\"text-slate-400\">View Distance</span><span id=\"view-val\" class=\"text-white font-mono\">"
                + viewDistance + "</span></div>\n" +
                "<input type=\"range\" id=\"view-slider\" min=\"2\" max=\"32\" value=\"" + viewDistance
                + "\" class=\"w-full h-1.5 bg-slate-700 rounded appearance-none cursor-pointer accent-primary\"></div>\n"
                +
                "<div><div class=\"flex justify-between text-xs mb-1\"><span class=\"text-slate-400\">Simulation Distance</span><span id=\"sim-val\" class=\"text-white font-mono\">"
                + simDistance + "</span></div>\n" +
                "<input type=\"range\" id=\"sim-slider\" min=\"2\" max=\"32\" value=\"" + simDistance
                + "\" class=\"w-full h-1.5 bg-slate-700 rounded appearance-none cursor-pointer accent-primary\"></div>\n"
                +
                "<button id=\"apply-distance\" class=\"w-full py-2 rounded bg-primary/20 text-primary hover:bg-primary hover:text-black transition-all text-sm font-semibold\">Apply</button>\n"
                +
                "</div></div>\n" +

                "<div class=\"rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border p-4\">\n" +
                "<div class=\"flex items-center gap-3 mb-4\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">chat_bubble</span>\n" +
                "<h2 class=\"text-sm font-bold text-white uppercase tracking-wider\">MOTD</h2>\n" +
                "</div>\n" +
                "<div class=\"mb-3\">\n" +
                "<textarea id=\"motd-input\" class=\"w-full bg-slate-800 border border-slate-600 rounded p-2 text-sm text-white font-mono placeholder-slate-500 focus:border-primary outline-none resize-none\" rows=\"2\" placeholder=\"Enter server MOTD...\">"
                + escapeHtml(currentMotd) + "</textarea>\n" +
                "</div>\n" +
                "<div class=\"bg-slate-900 rounded p-3 mb-3\">\n" +
                "<p class=\"text-xs text-slate-400 mb-1\">Preview:</p>\n" +
                "<p id=\"motd-preview\" class=\"text-sm text-white\"></p>\n" +
                "</div>\n" +
                "<button id=\"save-motd\" class=\"w-full py-2 rounded bg-primary/20 text-primary hover:bg-primary hover:text-black transition-all text-sm font-semibold\">Save MOTD</button>\n"
                +
                "</div>\n" +

                "<div class=\"rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border p-4\">\n" +
                "<div class=\"flex items-center gap-3 mb-4\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">image</span>\n" +
                "<h2 class=\"text-sm font-bold text-white uppercase tracking-wider\">Server Icon</h2>\n" +
                "</div>\n" +
                "<div class=\"flex items-center gap-4\">\n" +
                "<div class=\"h-16 w-16 rounded bg-slate-800 flex items-center justify-center overflow-hidden\">\n" +
                (iconPreview.isEmpty()
                        ? "<span class=\"material-symbols-outlined text-slate-600 text-3xl\">image</span>"
                        : "<img src=\"" + iconPreview + "\" class=\"h-full w-full object-cover\">")
                +
                "</div>\n" +
                "<div class=\"flex-1\">\n" +
                "<p class=\"text-xs text-slate-400 mb-2\">64x64 PNG recommended</p>\n" +
                "<label class=\"cursor-pointer inline-block px-3 py-1.5 rounded bg-primary/20 text-primary text-sm font-semibold hover:bg-primary hover:text-black transition-colors\">\n"
                +
                "<input type=\"file\" id=\"icon-upload\" accept=\"image/png\" class=\"hidden\">\n" +
                "Upload Icon\n" +
                "</label>\n" +
                "</div></div></div>\n" +
                "</div>\n" +

                "<div class=\"space-y-6\">\n" +

                "<div class=\"rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden\">\n"
                +
                "<div class=\"flex items-center justify-between px-4 py-3 border-b border-white/5\">\n" +
                "<div class=\"flex items-center gap-3\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">backup</span>\n" +
                "<h2 class=\"text-sm font-bold text-white uppercase tracking-wider\">Backups</h2>\n" +
                "</div>\n" +
                "<form action='/action' method='post' class='inline'><input type='hidden' name='action' value='backup_create'>\n"
                +
                "<button class=\"px-2 py-1 rounded bg-emerald-500/20 text-emerald-400 text-xs hover:bg-emerald-500 hover:text-white transition-colors\">Create Now</button></form>\n"
                +
                "</div>\n" +
                "<div class=\"p-3\">\n" +
                "<div class=\"flex items-center gap-2 mb-3\">\n" +
                "<span class=\"text-xs text-slate-400\">Schedule:</span>\n" +
                "<select id=\"backup-schedule\" class=\"bg-slate-800 border border-slate-600 rounded px-2 py-1 text-xs text-white\">\n"
                +
                "<option value=\"0\"" + (backupSchedule == 0 ? " selected" : "") + ">Disabled</option>\n" +
                "<option value=\"1\"" + (backupSchedule == 1 ? " selected" : "") + ">Every hour</option>\n" +
                "<option value=\"6\"" + (backupSchedule == 6 ? " selected" : "") + ">Every 6 hours</option>\n" +
                "<option value=\"12\"" + (backupSchedule == 12 ? " selected" : "") + ">Every 12 hours</option>\n" +
                "<option value=\"24\"" + (backupSchedule == 24 ? " selected" : "") + ">Daily</option>\n" +
                "</select>\n" +
                "</div>\n" +
                "<div class=\"flex flex-col gap-2 max-h-40 overflow-y-auto console-scrollbar\">\n" +
                backupsHtml.toString() +
                "</div></div></div>\n" +

                "<div class=\"rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden\">\n"
                +
                "<div class=\"flex items-center justify-between px-4 py-3 border-b border-white/5\">\n" +
                "<div class=\"flex items-center gap-3\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">package_2</span>\n" +
                "<h2 class=\"text-sm font-bold text-white uppercase tracking-wider\">Datapacks</h2>\n" +
                "</div>\n" +
                "<label class=\"cursor-pointer px-2 py-1 rounded bg-primary/20 text-primary text-xs hover:bg-primary hover:text-black transition-colors\">\n"
                +
                "<input type=\"file\" id=\"datapack-upload\" accept=\".zip\" class=\"hidden\">\n" +
                "Upload\n" +
                "</label>\n" +
                "</div>\n" +
                "<div class=\"p-3 flex flex-col gap-2 max-h-40 overflow-y-auto console-scrollbar\">\n" +
                datapacksHtml.toString() +
                "</div></div>\n" +

                "<div class=\"rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border p-4\">\n" +
                "<div class=\"flex items-center gap-3 mb-4\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">extension</span>\n" +
                "<h2 class=\"text-sm font-bold text-white uppercase tracking-wider\">Integrations</h2>\n" +
                "</div>\n" +
                "<div class=\"space-y-3\">\n" +
                "<div class=\"flex items-center justify-between p-3 rounded-lg bg-white/5\">\n" +
                "<div class=\"flex items-center gap-2\">\n" +
                "<span class=\"material-symbols-outlined " + (sparkInstalled ? "text-emerald-400" : "text-slate-500")
                + "\">bolt</span>\n" +
                "<div><p class=\"text-white text-sm\">Spark Profiler</p>\n" +
                "<p class=\"text-xs " + (sparkInstalled ? "text-emerald-400" : "text-slate-500") + "\">"
                + (sparkInstalled ? "Installed" : "Not installed") + "</p></div></div>\n" +
                (sparkInstalled
                        ? "<button id=\"spark-profile\" class=\"px-3 py-1 rounded bg-amber-500/20 text-amber-400 text-xs hover:bg-amber-500 hover:text-black transition-colors\">Start Profile</button>"
                        : "")
                +
                "</div>\n" +
                "<div class=\"flex items-center justify-between p-3 rounded-lg bg-white/5\">\n" +
                "<div class=\"flex items-center gap-2\">\n" +
                "<span class=\"material-symbols-outlined " + (discordInstalled ? "text-emerald-400" : "text-slate-500")
                + "\">forum</span>\n" +
                "<div><p class=\"text-white text-sm\">DiscordSRV</p>\n" +
                "<p class=\"text-xs " + (discordInstalled ? "text-emerald-400" : "text-slate-500") + "\">"
                + (discordInstalled ? "Connected" : "Not installed") + "</p></div></div>\n" +
                "</div>\n" +
                "</div></div>\n" +

                "</div>\n" +
                "</div>\n" +
                "</main>\n" +
                HtmlTemplate.statsScript() +
                "<script>\n" +
                "document.getElementById('view-slider').addEventListener('input', e => document.getElementById('view-val').textContent = e.target.value);\n"
                +
                "document.getElementById('sim-slider').addEventListener('input', e => document.getElementById('sim-val').textContent = e.target.value);\n"
                +
                "document.getElementById('apply-distance').addEventListener('click', () => {\n" +
                "  fetch('/action', {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'},\n" +
                "    body:'action=set_distance&view='+document.getElementById('view-slider').value+'&sim='+document.getElementById('sim-slider').value\n"
                +
                "  }).then(() => alert('Saved! Restart to apply.'));\n" +
                "});\n" +
                "function parseMotd(text) { return text.replace(/&([0-9a-fk-or])/gi, ''); }\n" +
                "document.getElementById('motd-input').addEventListener('input', e => document.getElementById('motd-preview').textContent = parseMotd(e.target.value));\n"
                +
                "document.getElementById('motd-preview').textContent = parseMotd(document.getElementById('motd-input').value);\n"
                +
                "document.getElementById('save-motd').addEventListener('click', () => {\n" +
                "  fetch('/action', {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'},\n" +
                "    body:'action=set_motd&motd='+encodeURIComponent(document.getElementById('motd-input').value)\n" +
                "  }).then(() => alert('MOTD saved! Restart to apply.'));\n" +
                "});\n" +
                "document.getElementById('icon-upload').addEventListener('change', e => {\n" +
                "  const file = e.target.files[0]; if (!file) return;\n" +
                "  const formData = new FormData(); formData.append('file', file);\n" +
                "  fetch('/api/upload/icon', {method:'POST', body: formData}).then(r => r.json()).then(d => {\n" +
                "    if(d.success) { alert('Icon uploaded!'); location.reload(); } else alert('Error: '+d.error);\n" +
                "  });\n" +
                "});\n" +
                "document.getElementById('datapack-upload').addEventListener('change', e => {\n" +
                "  const file = e.target.files[0]; if (!file) return;\n" +
                "  const formData = new FormData(); formData.append('file', file);\n" +
                "  fetch('/api/upload/datapack', {method:'POST', body: formData}).then(r => r.json()).then(d => {\n" +
                "    if(d.success) { alert('Datapack uploaded!'); location.reload(); } else alert('Error: '+d.error);\n"
                +
                "  });\n" +
                "});\n" +
                "document.getElementById('backup-schedule').addEventListener('change', e => {\n" +
                "  fetch('/action', {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'},\n" +
                "    body:'action=backup_schedule&hours='+e.target.value\n" +
                "  }).then(() => alert('Schedule updated!'));\n" +
                "});\n" +
                (sparkInstalled ? "document.getElementById('spark-profile')?.addEventListener('click', () => {\n" +
                        "  fetch('/action', {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'},\n"
                        +
                        "    body:'action=spark_profile'\n" +
                        "  }).then(() => alert('Spark profile started! Check console for link.'));\n" +
                        "});\n" : "")
                +
                "</script>\n";

        return HtmlTemplate.page("Settings", "/settings", content);
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static boolean isSparkAvailable() {
        try {
            Class.forName("me.lucko.spark.api.Spark");
            return true;
        } catch (ClassNotFoundException e) {
            try {
                Class.forName("me.lucko.spark.common.SparkPlatform");
                return true;
            } catch (ClassNotFoundException e2) {
                return false;
            }
        }
    }
}
