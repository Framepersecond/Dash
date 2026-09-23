package dash.web;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import dash.Dash;
import dash.FeatureFlags;
import dash.WebAuth;
import dash.data.BackupManager;
import dash.data.DatapackManager;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;

public class SettingsPage {

    public static String render(String sessionUser, boolean isMainAdmin, String message) {
        ServerSettingsPage.ServerType serverType = ServerSettingsPage.detectServerType();
        boolean supportsPaperExtras = ServerSettingsPage.supportsPaperExtras(serverType);
        boolean supportsSparkUi = ServerSettingsPage.supportsSparkUi(serverType);
        World overworld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        boolean canDistanceViewWrite = canSettingWrite("dash.web.settings.distance.view");
        boolean canDistanceSimulationWrite = supportsPaperExtras && canSettingWrite("dash.web.settings.distance.simulation");
        boolean canAnyDistanceWrite = canDistanceViewWrite || canDistanceSimulationWrite;
        boolean canMotdWrite = canSettingWrite("dash.web.settings.motd.write");
        boolean canIconWrite = canSettingWrite("dash.web.settings.icon.write");
        boolean canWhitelistManage = HtmlTemplate.can("dash.web.whitelist.manage");
        boolean canBackupsRead = HtmlTemplate.can("dash.web.backups.read");
        boolean canBackupsCreate = HtmlTemplate.can("dash.web.backups.create");
        boolean canBackupsDelete = HtmlTemplate.can("dash.web.backups.delete");
        boolean canBackupsSchedule = HtmlTemplate.can("dash.web.backups.schedule");
        boolean canDatapacksWrite = HtmlTemplate.can("dash.web.datapacks.write");
        boolean canToolsSpark = HtmlTemplate.can("dash.web.tools.spark");
        boolean canSettingsWrite = HtmlTemplate.can("dash.web.settings.write");
        boolean betaFeaturesEnabled = Dash.getInstance() != null
                && Dash.getInstance().getConfig().getBoolean("beta.enabled", false);

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
                boolean canWriteRule = canSettingWrite(permissionForGamerule(ruleName));

                gamerulesHtml.append(
                        "<div class=\"flex items-center justify-between p-3 rounded-lg bg-white/5 hover:bg-white/10 transition-colors\">\n")
                        .append("<div><p class=\"text-white font-medium text-sm\">").append(label).append("</p>")
                        .append("<p class=\"text-slate-500 text-xs\">").append(desc).append("</p></div>\n")
                        .append("<form action='/action' method='post' class='inline'>")
                        .append("<input type='hidden' name='action' value='gamerule'>")
                        .append("<input type='hidden' name='rule' value='").append(ruleName).append("'>")
                        .append("<input type='hidden' name='value' value='").append(!value)
                        .append("'>")
                        .append("<button type='submit'").append(canWriteRule ? "" : " disabled")
                        .append(" class=\"relative inline-flex h-6 w-11 items-center rounded-full transition-colors ")
                        .append(value ? "bg-primary" : "bg-slate-600")
                        .append(canWriteRule ? "" : " opacity-45 cursor-not-allowed grayscale")
                        .append("\">")
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
                    .append("<button").append(canWhitelistManage ? "" : " disabled")
                    .append(" class=\"text-slate-400 hover:text-rose-400")
                    .append(canWhitelistManage ? "" : " opacity-50 cursor-not-allowed")
                    .append("\"><span class=\"material-symbols-outlined text-[16px]\">close</span></button>")
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
                        .append(canBackupsRead
                                ? "<a href='/api/backups/download?name=" + backup.name()
                                        + "' class=\"px-2 py-1 rounded bg-primary/20 text-primary text-xs hover:bg-primary hover:text-black transition-colors\">Download</a>"
                                : "<span class=\"px-2 py-1 rounded bg-slate-700 text-slate-400 text-xs cursor-not-allowed\">Download</span>")
                        .append("<form action='/action' method='post' class='inline' onsubmit=\"return confirm('Delete backup?');\">")
                        .append("<input type='hidden' name='action' value='backup_delete'><input type='hidden' name='name' value='")
                        .append(backup.name()).append("'>")
                        .append("<button").append(canBackupsDelete ? "" : " disabled")
                        .append(" class=\"px-2 py-1 rounded bg-rose-500/20 text-rose-400 text-xs hover:bg-rose-500 hover:text-white transition-colors")
                        .append(canBackupsDelete ? "" : " opacity-50 cursor-not-allowed")
                        .append("\">Delete</button>")
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
                    .append("<button").append(canDatapacksWrite ? "" : " disabled")
                    .append(" class=\"px-2 py-1 rounded text-xs ")
                    .append(dp.enabled() ? "bg-emerald-500/20 text-emerald-400" : "bg-slate-600 text-slate-300")
                    .append(canDatapacksWrite ? "" : " opacity-50 cursor-not-allowed")
                    .append("\">")
                    .append(dp.enabled() ? "Enabled" : "Disabled").append("</button></form>")
                    .append("<form action='/action' method='post' class='inline' onsubmit=\"return confirm('Delete datapack?');\">")
                    .append("<input type='hidden' name='action' value='datapack_delete'><input type='hidden' name='name' value='")
                    .append(dp.name()).append("'>")
                    .append("<button").append(canDatapacksWrite ? "" : " disabled")
                    .append(" class=\"text-slate-400 hover:text-rose-400")
                    .append(canDatapacksWrite ? "" : " opacity-50 cursor-not-allowed")
                    .append("\"><span class=\"material-symbols-outlined text-[16px]\">delete</span></button>")
                    .append("</form></div></div>\n");
        }
        if (datapacks.isEmpty()) {
            datapacksHtml.append("<p class=\"text-slate-500 text-center text-sm py-4\">No datapacks installed</p>\n");
        }

        String currentMotd = ServerSettingsPage.readServerPropertySafe("motd", "");

        String iconPreview = "";
        File iconFile = new File(Bukkit.getWorldContainer(), "server-icon.png");
        if (iconFile.exists()) {
            try {
                byte[] iconData = Files.readAllBytes(iconFile.toPath());
                iconPreview = "data:image/png;base64," + Base64.getEncoder().encodeToString(iconData);
            } catch (Exception ignored) {
            }
        }

        boolean sparkInstalled = supportsSparkUi
                && (Bukkit.getPluginManager().getPlugin("spark") != null || isSparkAvailable());
        boolean discordInstalled = Bukkit.getPluginManager().getPlugin("DiscordSRV") != null;

        boolean whitelistEnabled = Bukkit.hasWhitelist();
        int viewDistance = Bukkit.getViewDistance();
        int simDistance = getSimulationDistanceSafe();
        boolean simulationDistanceSupported = supportsPaperExtras && simDistance >= 0;
        int simDistanceDisplay = simulationDistanceSupported ? simDistance : 2;
        int backupSchedule = backupMgr != null ? backupMgr.getScheduleHours() : 0;
        String simulationDistanceHintHtml = simulationDistanceSupported
                ? ""
                : "<p class=\"mt-2 text-[11px] text-amber-300\">Not supported on pure Spigot/Bukkit. Switch to Paper/Purpur to enable this feature.</p>\n";
        String simulationDistanceControlHtml = "<div><div class=\"flex justify-between text-xs mb-1\"><span class=\"text-slate-400\">Simulation Distance</span><span id=\"sim-val\" class=\"text-white font-mono\">"
                + (simulationDistanceSupported ? String.valueOf(simDistanceDisplay) : "N/A") + "</span></div>\n"
                + "<input type=\"range\" id=\"sim-slider\" min=\"2\" max=\"32\" value=\"" + simDistanceDisplay
                + "\"" + (canDistanceSimulationWrite ? "" : " disabled")
                + " class=\"w-full h-1.5 bg-slate-700 rounded appearance-none cursor-pointer accent-primary"
                + (canDistanceSimulationWrite ? "" : " opacity-50 cursor-not-allowed") + "\">\n"
                + simulationDistanceHintHtml
                + "</div>\n";
        String serverConfigOverview = ServerSettingsPage.renderConfigOverview(serverType);

        String settingsMessage = (message != null && !message.isBlank())
                ? "<div class='mb-4 p-3 rounded-lg bg-sky-500/10 border border-sky-500/20 text-sky-200 text-sm'>"
                        + escapeHtml(message) + "</div>"
                : "";

        String content = HtmlTemplate.statsHeader() +
                "<main class=\"p-4 sm:p-6 flex-1 w-full\">\n" +
                settingsMessage +
                "<div class=\"grid grid-cols-1 lg:grid-cols-3 gap-6\">\n" +
 
                "<div class=\"space-y-6\">\n" +
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border dash-hover-lift overflow-hidden\">\n"
                +
                "<div class=\"flex items-center gap-3 px-4 py-3 border-b border-white/5\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">sports_esports</span>\n" +
                "<h2 class=\"text-sm font-display font-semibold text-white tracking-tight\">Gamerules</h2>\n" +
                "</div>\n" +
                "<div class=\"p-3 flex flex-col gap-2 max-h-96 overflow-y-auto console-scrollbar\">\n" +
                gamerulesHtml.toString() +
                "</div></div>\n" +
 
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border dash-hover-lift overflow-hidden\">\n"
                +
                "<div class=\"flex items-center justify-between px-4 py-3 border-b border-white/5\">\n" +
                "<div class=\"flex items-center gap-3\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">shield_person</span>\n" +
                "<h2 class=\"text-sm font-display font-semibold text-white tracking-tight\">Whitelist</h2>\n" +
                "</div>\n" +
                "<form action='/action' method='post' class='inline'><input type='hidden' name='action' value='whitelist_toggle'>\n"
                +
                "<button" + (canWhitelistManage ? "" : " disabled") + " class=\"px-3 py-1 rounded-full text-xs font-bold transition-all scale-[1.02] "
                + (whitelistEnabled ? "bg-emerald-500/20 text-emerald-400" : "bg-slate-600 text-slate-300") + "\">"
                + (whitelistEnabled ? "ON" : "OFF") + "</button></form>\n" +
                "</div>\n" +
                (canWhitelistManage ? "" : "<p class='px-3 pt-2 text-xs text-slate-500'>Read-only access: whitelist changes are disabled.</p>\n") +
                "<div class=\"p-3\">\n" +
                "<form action='/action' method='post' class=\"flex flex-col sm:flex-row gap-3 sm:items-end w-full mb-3\">\n" +
                "<input type='hidden' name='action' value='whitelist_add'>\n" +
                "<input type='text' name='player' placeholder='Player name'" + (canWhitelistManage ? "" : " disabled") + " class=\"w-full sm:w-auto sm:flex-1 bg-slate-950/40 border border-glass-border rounded-full px-5 py-2 text-xs text-white placeholder-slate-500 focus:border-primary/50 focus:bg-slate-950/80 outline-none transition-all" + (canWhitelistManage ? "" : " opacity-50 cursor-not-allowed") + "\">\n"
                +
                "<button" + (canWhitelistManage ? "" : " disabled") + " class=\"w-full sm:w-auto px-5 py-2 rounded-full bg-primary text-black text-xs font-bold hover:shadow-lg transition-all scale-[1.02]" + (canWhitelistManage ? "" : " opacity-50 cursor-not-allowed") + "\">Add</button>\n" +
                "</form>\n" +
                "<div class=\"flex flex-col gap-1 max-h-32 overflow-y-auto console-scrollbar\">\n" +
                whitelistHtml.toString() +
                "</div></div></div>\n" +

                "</div>\n" +
 
                "<div class=\"space-y-6\">\n" +
 
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border dash-hover-lift p-4\">\n" +
                "<div class=\"flex items-center gap-3 mb-4\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">visibility</span>\n" +
                "<h2 class=\"text-sm font-display font-semibold text-white tracking-tight\">View Distance</h2>\n" +
                "</div>\n" +
                "<div class=\"space-y-4\">\n" +
                "<div><div class=\"flex justify-between text-xs mb-1\"><span class=\"text-slate-400\">View Distance</span><span id=\"view-val\" class=\"text-white font-mono\">"
                + viewDistance + "</span></div>\n" +
                "<input type=\"range\" id=\"view-slider\" min=\"2\" max=\"32\" value=\"" + viewDistance
                + "\"" + (canDistanceViewWrite ? "" : " disabled") + " class=\"w-full h-1.5 bg-slate-950/60 rounded-full appearance-none cursor-pointer accent-primary" + (canDistanceViewWrite ? "" : " opacity-50 cursor-not-allowed") + "\"></div>\n"
                +
                simulationDistanceControlHtml +
                "<button id=\"apply-distance\"" + (canAnyDistanceWrite ? "" : " disabled") + " class=\"w-full py-2.5 rounded-full bg-primary/20 text-primary border border-primary/20 hover:bg-primary hover:text-black transition-all text-xs font-bold scale-[1.01]" + (canAnyDistanceWrite ? "" : " opacity-50 cursor-not-allowed") + "\">Apply</button>\n"
                +
                "</div></div>\n" +
 
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border dash-hover-lift p-4\">\n" +
                "<div class=\"flex items-center gap-3 mb-4\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">chat_bubble</span>\n" +
                "<h2 class=\"text-sm font-display font-semibold text-white tracking-tight\">MOTD</h2>\n" +
                "</div>\n" +
                "<div class=\"mb-3\">\n" +
                "<textarea id=\"motd-input\"" + (canMotdWrite ? "" : " readonly") + " class=\"w-full bg-slate-950/40 border border-glass-border rounded-2xl p-3 text-sm text-white font-mono placeholder-slate-500 focus:border-primary/50 focus:bg-slate-950/80 outline-none resize-none transition-all" + (canMotdWrite ? "" : " opacity-50 cursor-not-allowed") + "\" rows=\"2\" placeholder=\"Enter server MOTD...\">"
                + escapeHtml(currentMotd) + "</textarea>\n" +
                "</div>\n" +
                "<div class=\"bg-slate-900 rounded-2xl p-3 mb-3\">\n" +
                "<p class=\"text-xs text-slate-400 mb-1\">Preview:</p>\n" +
                "<p id=\"motd-preview\" class=\"text-sm text-white\"></p>\n" +
                "</div>\n" +
                "<button id=\"save-motd\"" + (canMotdWrite ? "" : " disabled") + " class=\"w-full py-2.5 rounded-full bg-primary/20 text-primary border border-primary/20 hover:bg-primary hover:text-black transition-all text-xs font-bold scale-[1.01]" + (canMotdWrite ? "" : " opacity-50 cursor-not-allowed") + "\">Save MOTD</button>\n"
                +
                "</div>\n" +
 
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border dash-hover-lift p-4\">\n" +
                "<div class=\"flex items-center gap-3 mb-4\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">image</span>\n" +
                "<h2 class=\"text-sm font-display font-semibold text-white tracking-tight\">Server Icon</h2>\n" +
                "</div>\n" +
                "<div class=\"flex items-center gap-4\">\n" +
                "<div class=\"h-16 w-16 rounded-2xl bg-slate-950/40 border border-glass-border flex items-center justify-center overflow-hidden\">\n" +
                (iconPreview.isEmpty()
                        ? "<span class=\"material-symbols-outlined text-slate-600 text-3xl\">image</span>"
                        : "<img src=\"" + iconPreview + "\" class=\"h-full w-full object-cover\">")
                +
                "</div>\n" +
                "<div class=\"flex-1\">\n" +
                "<p class=\"text-xs text-slate-400 mb-2\">64x64 PNG recommended</p>\n" +
                "<label class=\"inline-block px-5 py-2 rounded-full bg-primary/20 text-primary border border-primary/20 text-xs font-bold hover:bg-primary hover:text-black transition-all" + (canIconWrite ? " cursor-pointer" : " opacity-50 cursor-not-allowed") + "\">\n"
                +
                "<input type=\"file\" id=\"icon-upload\" accept=\"image/png\" class=\"hidden\"" + (canIconWrite ? "" : " disabled") + ">\n" +
                "Upload Icon\n" +
                "</label>\n" +
                "</div></div></div>\n" +
                "</div>\n" +
 
                "<div class=\"space-y-6\">\n" +
 
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border dash-hover-lift overflow-hidden\">\n"
                +
                "<div class=\"flex items-center justify-between px-4 py-3 border-b border-white/5\">\n" +
                "<div class=\"flex items-center gap-3\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">backup</span>\n" +
                "<h2 class=\"text-sm font-display font-semibold text-white tracking-tight\">Backups</h2>\n" +
                "</div>\n" +
                "<form action='/action' method='post' class='inline'><input type='hidden' name='action' value='backup_create'>\n"
                +
                "<button" + (canBackupsCreate ? "" : " disabled") + " class=\"px-4 py-1.5 rounded-full bg-emerald-500/20 text-emerald-400 border border-emerald-500/20 text-xs font-bold hover:bg-emerald-500 hover:text-white transition-all" + (canBackupsCreate ? "" : " opacity-50 cursor-not-allowed") + "\">Create Now</button></form>\n"
                +
                "</div>\n" +
                "<div class=\"p-3\">\n" +
                "<div class=\"flex flex-col sm:flex-row sm:items-center gap-2 mb-3\">\n" +
                "<span class=\"text-xs text-slate-400\">Schedule:</span>\n" +
                "<select id=\"backup-schedule\"" + (canBackupsSchedule ? "" : " disabled") + " class=\"w-full sm:w-auto bg-slate-950/40 border border-glass-border rounded-full px-4 py-1.5 text-xs text-white focus:border-primary/50 focus:bg-slate-950/80 outline-none transition-all" + (canBackupsSchedule ? "" : " opacity-50 cursor-not-allowed") + "\">\n"
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
 
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border dash-hover-lift overflow-hidden\">\n"
                +
                "<div class=\"flex items-center justify-between px-4 py-3 border-b border-white/5\">\n" +
                "<div class=\"flex items-center gap-3\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">package_2</span>\n" +
                "<h2 class=\"text-sm font-display font-semibold text-white tracking-tight\">Datapacks</h2>\n" +
                "</div>\n" +
                "<label class=\"px-4 py-1.5 rounded-full bg-primary/20 text-primary border border-primary/20 text-xs font-bold hover:bg-primary hover:text-black transition-all" + (canDatapacksWrite ? " cursor-pointer" : " opacity-50 cursor-not-allowed") + "\">\n"
                +
                "<input type=\"file\" id=\"datapack-upload\" accept=\".zip\" class=\"hidden\"" + (canDatapacksWrite ? "" : " disabled") + ">\n" +
                "Upload\n" +
                "</label>\n" +
                "</div>\n" +
                "<div class=\"p-3 flex flex-col gap-2 max-h-40 overflow-y-auto console-scrollbar\">\n" +
                datapacksHtml.toString() +
                "</div></div>\n" +
 
                serverConfigOverview + "\n" +
 
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border dash-hover-lift p-4\">\n" +
                "<div class=\"flex items-center gap-3 mb-4\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">extension</span>\n" +
                "<h2 class=\"text-sm font-display font-semibold text-white tracking-tight\">Integrations</h2>\n" +
                "</div>\n" +
                "<div class=\"space-y-3\">\n" +
                "<div class=\"flex items-center justify-between p-3 rounded-lg bg-white/5\">\n"
                + "<div class=\"flex items-center gap-2\">\n"
                + "<span class=\"material-symbols-outlined " + (sparkInstalled ? "text-emerald-400" : "text-slate-500")
                + "\">bolt</span>\n"
                + "<div><p class=\"text-white text-sm\">Spark Profiler</p>\n"
                + "<p class=\"text-xs " + (sparkInstalled ? "text-emerald-400" : "text-slate-500") + "\">"
                + (sparkInstalled ? "Installed" : (supportsSparkUi ? "Not installed" : "Unsupported on this server type"))
                + "</p>"
                + (supportsSparkUi ? "" : "<p class=\"text-[11px] text-amber-300 mt-1\">Not supported on pure Spigot/Bukkit. Switch to Paper/Purpur to enable this feature.</p>")
                + "</div></div>\n"
                + "<button id=\"spark-profile\""
                + ((sparkInstalled && canToolsSpark) ? "" : " disabled")
                + " class=\"px-4 py-1.5 rounded-full bg-amber-500/20 text-amber-400 border border-amber-500/20 text-xs font-bold hover:bg-amber-500 hover:text-black transition-all"
                + ((sparkInstalled && canToolsSpark) ? "" : " opacity-50 cursor-not-allowed")
                + "\">Start Profile</button>"
                + "</div>\n" +
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
                "<script>\n(function(){\n" +
                "document.getElementById('view-slider')?.addEventListener('input', e => document.getElementById('view-val').textContent = e.target.value);\n"
                +
                "document.getElementById('sim-slider')?.addEventListener('input', e => document.getElementById('sim-val').textContent = e.target.value);\n"
                +
                (canAnyDistanceWrite ? "document.getElementById('apply-distance')?.addEventListener('click', async () => {\n" +
                        "  try {\n" +
                        "  const resp = await fetch('/action', {method:'POST', credentials:'same-origin', headers:{'Content-Type':'application/x-www-form-urlencoded'},\n" +
                        "    body:'action=set_distance'"
                        + (canDistanceViewWrite ? "+'&view='+document.getElementById('view-slider').value" : "")
                        + (canDistanceSimulationWrite ? "+'&sim='+document.getElementById('sim-slider').value" : "")
                        + "\n"
                        +
                        "  });\n" +
                        "  if(!resp.ok) throw new Error('HTTP '+resp.status);\n" +
                        "  showToast('Saved! Restart to apply.', 'success');\n" +
                        "  } catch(e) { showToast('Missing permission or save failed.', 'error'); }\n" +
                        "});\n" : "") +
                "function parseMotd(text) { return text.replace(/&([0-9a-fk-or])/gi, ''); }\n" +
                "document.getElementById('motd-input')?.addEventListener('input', e => document.getElementById('motd-preview').textContent = parseMotd(e.target.value));\n"
                +
                "document.getElementById('motd-preview').textContent = parseMotd(document.getElementById('motd-input').value);\n"
                +
                (canMotdWrite ? "document.getElementById('save-motd')?.addEventListener('click', async () => {\n" +
                        "  try {\n" +
                        "  const resp = await fetch('/action', {method:'POST', credentials:'same-origin', headers:{'Content-Type':'application/x-www-form-urlencoded'},\n" +
                        "    body:'action=set_motd&motd='+encodeURIComponent(document.getElementById('motd-input').value)\n" +
                        "  });\n" +
                        "  if(!resp.ok) throw new Error('HTTP '+resp.status);\n" +
                        "  showToast('MOTD saved! Restart to apply.', 'success');\n" +
                        "  } catch(e) { showToast('Missing permission or save failed.', 'error'); }\n" +
                        "});\n" : "") +
                (canIconWrite ? "document.getElementById('icon-upload')?.addEventListener('change', e => {\n" +
                "  const file = e.target.files[0]; if (!file) return;\n" +
                "  const formData = new FormData(); formData.append('file', file);\n" +
                "  fetch('/api/upload/icon', {method:'POST', body: formData, credentials:'same-origin'}).then(r => r.json()).then(d => {\n" +
                "    if(d.success) { showToast('Icon uploaded!', 'success'); if(window.dashNavigate){window.dashNavigate(location.pathname+location.search,'replace',undefined,true);} } else showToast('Error: '+d.error, 'error');\n" +
                "  }).catch(() => showToast('Icon upload failed.', 'error'));\n" +
                "});\n" : "") +
                (canDatapacksWrite ? "document.getElementById('datapack-upload')?.addEventListener('change', e => {\n" +
                "  const file = e.target.files[0]; if (!file) return;\n" +
                "  const formData = new FormData(); formData.append('file', file);\n" +
                "  fetch('/api/upload/datapack', {method:'POST', body: formData, credentials:'same-origin'}).then(r => r.json()).then(d => {\n" +
                "    if(d.success) { showToast('Datapack uploaded!', 'success'); if(window.dashNavigate){window.dashNavigate(location.pathname+location.search,'replace',undefined,true);} } else showToast('Error: '+d.error, 'error');\n"
                +
                "  }).catch(() => showToast('Datapack upload failed.', 'error'));\n" +
                "});\n" : "") +
                (canBackupsSchedule ? "document.getElementById('backup-schedule')?.addEventListener('change', async e => {\n" +
                "  try { const resp=await fetch('/action', {method:'POST', credentials:'same-origin', headers:{'Content-Type':'application/x-www-form-urlencoded'},\n" +
                "    body:'action=backup_schedule&hours='+e.target.value\n" +
                "  }); if(!resp.ok)throw new Error('HTTP '+resp.status);showToast('Schedule updated!', 'success'); } catch(_){showToast('Schedule update failed.', 'error');}\n" +
                "});\n" : "") +
                (sparkInstalled && canToolsSpark ? "document.getElementById('spark-profile')?.addEventListener('click', async () => {\n" +
                        "  try { const resp=await fetch('/action', {method:'POST', credentials:'same-origin', headers:{'Content-Type':'application/x-www-form-urlencoded'},\n"
                        +
                        "    body:'action=spark_profile'\n" +
                        "  });if(!resp.ok)throw new Error('HTTP '+resp.status);showToast('Spark profile started! Check console for link.', 'success');}catch(_){showToast('Spark profile could not be started.', 'error');}\n" +
                        "});\n" : "")
                +
                "})();\n</script>\n";

        return HtmlTemplate.page("Settings", "/settings", content);
    }

    private static String permissionForGamerule(String ruleName) {
        if (ruleName == null || ruleName.isBlank()) {
            return "";
        }
        return switch (ruleName) {
            case "keepInventory" -> "dash.web.settings.gamerule.keep_inventory";
            case "doMobSpawning" -> "dash.web.settings.gamerule.mob_spawning";
            case "doDaylightCycle" -> "dash.web.settings.gamerule.daylight_cycle";
            case "doWeatherCycle" -> "dash.web.settings.gamerule.weather_cycle";
            case "mobGriefing" -> "dash.web.settings.gamerule.mob_griefing";
            case "doFireTick" -> "dash.web.settings.gamerule.fire_tick";
            case "naturalRegeneration" -> "dash.web.settings.gamerule.natural_regeneration";
            default -> "";
        };
    }

    private static boolean canSettingWrite(String permission) {
        if (HtmlTemplate.can("dash.web.settings.write")) {
            return true;
        }
        return permission != null && !permission.isBlank() && HtmlTemplate.can(permission);
    }

    public static String featureControlPanel(boolean canWrite) {
        StringBuilder rows = new StringBuilder();
        rows.append(featureToggle("tickets", "Tickets", "Player reports and private staff workflows.", false, canWrite));
        rows.append(featureToggle("notifications", "Notifications", "Destinations, events and delivery health.", false, canWrite));
        rows.append(featureToggle("graphs", "Graphs", "Live and historical server charts with export.", false, canWrite));
        rows.append(featureToggle("ai", "Dash AI", "Optional Google-powered assistant and guarded proposals.", true, canWrite));
        rows.append(featureToggle("intelligence", "Intelligence", "Investigation, policy and reliability workspace.", true, canWrite));
        rows.append(featureToggle("maintenance", "Maintenance", "Diagnostics, profiler and repair tooling.", true, canWrite));
        rows.append(featureToggle("guardian", "Guardian", "Cases, protection, recovery and retention.", true, canWrite));
        return "<section class='rounded-2xl bg-glass-surface border border-glass-border overflow-hidden'>"
                + "<header class='flex items-start gap-3 border-b border-white/5 px-4 py-4'><span class='material-symbols-outlined text-primary'>toggle_on</span><div><h2 class='text-sm font-semibold text-white'>Feature availability</h2><p class='mt-1 text-xs leading-relaxed text-slate-400'>Disabled features disappear from navigation and reject direct page, action and API access.</p></div></header>"
                + "<form action='/action' method='post' class='p-3 space-y-2'><input type='hidden' name='action' value='save_feature_settings'>"
                + "<label class='flex items-center justify-between gap-3 rounded-lg border border-amber-400/20 bg-amber-400/5 px-3 py-3'><span><span class='flex items-center gap-2 text-sm font-semibold text-white'>Beta features <b class='rounded border border-amber-400/30 px-1.5 py-0.5 text-[9px] uppercase text-amber-300'>Opt in</b></span><span class='mt-1 block text-[11px] text-slate-500'>Required for Dash AI, Intelligence, Maintenance and Guardian. Off means no background work or provider calls.</span></span><input type='checkbox' name='beta_enabled' value='true'"
                + (FeatureFlags.betaEnabled() ? " checked" : "") + (canWrite ? "" : " disabled")
                + " class='h-5 w-5 rounded border-slate-600 bg-slate-900 text-primary'></label>"
                + rows
                + "<button" + (canWrite ? "" : " disabled")
                + " class='mt-3 w-full rounded-lg border border-primary/30 bg-primary/10 px-4 py-2.5 text-xs font-bold text-primary hover:bg-primary hover:text-black disabled:opacity-50'>Save feature availability</button></form></section>";
    }

    private static String featureToggle(String id, String label, String description, boolean beta,
            boolean canWrite) {
        return "<label class='flex items-center justify-between gap-3 rounded-lg border border-white/10 bg-white/[0.025] px-3 py-2.5'><span><span class='flex items-center gap-2 text-sm font-semibold text-slate-100'>"
                + escapeHtml(label)
                + (beta ? "<b class='rounded border border-slate-600 px-1.5 py-0.5 text-[9px] uppercase text-slate-400'>Beta</b>" : "")
                + "</span><span class='mt-0.5 block text-[11px] text-slate-500'>" + escapeHtml(description)
                + "</span></span><input type='checkbox' name='feature_" + id + "' value='true'"
                + (FeatureFlags.configured(id) ? " checked" : "") + (canWrite ? "" : " disabled")
                + " class='h-5 w-5 rounded border-slate-600 bg-slate-900 text-primary'></label>";
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
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

    private static int getSimulationDistanceSafe() {
        try {
            Method method = Bukkit.class.getMethod("getSimulationDistance");
            Object value = method.invoke(null);
            if (value instanceof Number number) {
                return number.intValue();
            }
        } catch (Exception ignored) {
            // Older or non-paper distributions may not expose simulation distance.
        }
        return -1;
    }
}
