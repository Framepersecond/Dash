package dash;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import dash.web.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.management.ManagementFactory;

public class AdminWebServer {

    private final JavaPlugin plugin;
    private final WebAuth auth;
    private HttpServer server;
    private final int port;
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    public AdminWebServer(JavaPlugin plugin, int port) {
        this.plugin = plugin;
        this.port = port;
        this.auth = new WebAuth(plugin);
    }

    public void start() {
        ConsoleLogAppender.register();

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);

            server.createContext("/", new PageHandler());
            server.createContext("/console", new PageHandler());
            server.createContext("/players", new PageHandler());
            server.createContext("/files", new PageHandler());
            server.createContext("/files/edit", new PageHandler());
            server.createContext("/plugins", new PageHandler());
            server.createContext("/settings", new PageHandler());

            server.createContext("/api/console", new ConsoleApiHandler());
            server.createContext("/api/stats", new StatsApiHandler());
            server.createContext("/api/stats/history", new StatsHistoryHandler());
            server.createContext("/api/files/save", new FileSaveHandler());
            server.createContext("/api/backups/download", new BackupDownloadHandler());
            server.createContext("/api/upload/icon", new IconUploadHandler());
            server.createContext("/api/upload/datapack", new DatapackUploadHandler());
            server.createContext("/api/upload/file", new FileUploadHandler());
            server.createContext("/api/upload/plugin", new PluginUploadHandler());
            server.createContext("/api/players/profile", new PlayerProfileHandler());

            server.createContext("/action", new ActionHandler());

            server.setExecutor(null);
            server.start();
            plugin.getLogger().info("Web admin started on port " + port);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to start web server: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null)
            server.stop(0);
    }

    private boolean isAuthenticated(HttpExchange t) {
        String cookie = t.getRequestHeaders().getFirst("Cookie");
        if (cookie != null && cookie.contains("session=")) {
            String token = cookie.split("session=")[1].split(";")[0];
            return sessions.containsKey(token) && sessions.get(token) > System.currentTimeMillis();
        }
        return false;
    }

    private void setSession(HttpExchange t) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, System.currentTimeMillis() + 3600000);
        t.getResponseHeaders().add("Set-Cookie", "session=" + token + "; Path=/; HttpOnly");
    }

    private String getClientIp(HttpExchange t) {
        return t.getRemoteAddress().getAddress().getHostAddress();
    }

    private class PageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String path = t.getRequestURI().getPath();
            String query = t.getRequestURI().getQuery();

            if (!auth.isRegistered()) {
                serveRegistration(t);
                return;
            }

            if (!isAuthenticated(t)) {
                serveLogin(t);
                return;
            }

            String html;
            if (path.equals("/")) {
                html = DashboardPage.render();
            } else if (path.equals("/console")) {
                html = dash.web.ConsolePage.render();
            } else if (path.equals("/players")) {
                html = PlayersPage.render();
            } else if (path.startsWith("/players/") && path.endsWith("/inventory")) {
                String playerName = path.replace("/players/", "").replace("/inventory", "");
                html = InventoryPage.render(playerName);
            } else if (path.startsWith("/players/") && path.endsWith("/enderchest")) {
                String playerName = path.replace("/players/", "").replace("/enderchest", "");
                html = InventoryPage.renderEnderChest(playerName);
            } else if (path.equals("/plugins")) {
                html = PluginsPage.render();
            } else if (path.equals("/settings")) {
                html = SettingsPage.render();
            } else if (path.equals("/files")) {
                String filePath = query != null && query.startsWith("path=")
                        ? URLDecoder.decode(query.substring(5), "UTF-8")
                        : "";
                html = FilesPage.render(filePath);
            } else if (path.equals("/files/edit")) {
                String filePath = query != null && query.startsWith("path=")
                        ? URLDecoder.decode(query.substring(5), "UTF-8")
                        : "";
                html = FilesPage.renderEditor(filePath);
            } else if (path.startsWith("/players/") && path.endsWith("/profile")) {
                String playerName = path.replace("/players/", "").replace("/profile", "");
                html = PlayerProfilePage.render(playerName);
            } else if (path.startsWith("/players/") && path.endsWith("/teleport")) {
                String playerName = path.replace("/players/", "").replace("/teleport", "");
                html = TeleportPage.render(playerName);
            } else if (path.startsWith("/players/") && !path.contains("/")) {
                String playerName = path.replace("/players/", "");
                if (!playerName.isEmpty()) {
                    html = PlayerProfilePage.render(playerName);
                } else {
                    html = PlayersPage.render();
                }
            } else {
                html = DashboardPage.render();
            }

            sendResponse(t, html);
        }
    }

    private void serveRegistration(HttpExchange t) throws IOException {
        String html = "<!DOCTYPE html><html class=\"dark\" lang=\"en\"><head><meta charset=\"utf-8\"/><title>Dash Setup</title>"
                +
                "<script src=\"https://cdn.tailwindcss.com\"></script></head><body class=\"bg-slate-900 text-white flex items-center justify-center h-screen\">"
                +
                "<div class=\"bg-slate-800 p-8 rounded-xl shadow-2xl w-96 border border-slate-700\">" +
                "<h2 class=\"text-2xl font-bold mb-6 text-center text-sky-400\">Dash Admin Setup</h2>" +
                "<p class=\"text-sm text-slate-400 mb-4 text-center\">Enter the registration code from /dash register</p>"
                +
                "<form action=\"/action\" method=\"post\" class=\"flex flex-col gap-4\">" +
                "<input type=\"hidden\" name=\"action\" value=\"register\">" +
                "<div><label class=\"text-sm text-slate-400\">Registration Code</label><input type=\"text\" name=\"code\" required placeholder=\"XXXXXXXX\" class=\"w-full bg-slate-900 border border-slate-600 rounded p-2 focus:border-sky-500 outline-none uppercase tracking-widest text-center font-mono\"></div>"
                +
                "<div><label class=\"text-sm text-slate-400\">Username</label><input type=\"text\" name=\"username\" required class=\"w-full bg-slate-900 border border-slate-600 rounded p-2 focus:border-sky-500 outline-none\"></div>"
                +
                "<div><label class=\"text-sm text-slate-400\">Password</label><input type=\"password\" name=\"password\" required class=\"w-full bg-slate-900 border border-slate-600 rounded p-2 focus:border-sky-500 outline-none\"></div>"
                +
                "<button type=\"submit\" class=\"bg-sky-500 hover:bg-sky-600 text-white font-bold py-2 rounded transition\">Complete Setup</button>"
                +
                "</form></div></body></html>";
        sendResponse(t, html);
    }

    private void serveLogin(HttpExchange t) throws IOException {
        String html = "<!DOCTYPE html><html class=\"dark\" lang=\"en\"><head><meta charset=\"utf-8\"/><title>Dash Login</title>"
                +
                "<script src=\"https://cdn.tailwindcss.com\"></script></head><body class=\"bg-slate-900 text-white flex items-center justify-center h-screen\">"
                +
                "<div class=\"bg-slate-800 p-8 rounded-xl shadow-2xl w-96 border border-slate-700\">" +
                "<h2 class=\"text-2xl font-bold mb-6 text-center text-sky-400\">Dash Admin</h2>" +
                "<form action=\"/action\" method=\"post\" class=\"flex flex-col gap-4\">" +
                "<input type=\"hidden\" name=\"action\" value=\"login\">" +
                "<input type=\"text\" name=\"username\" placeholder=\"Username\" required class=\"w-full bg-slate-900 border border-slate-600 rounded p-2 focus:border-sky-500 outline-none\">"
                +
                "<input type=\"password\" name=\"password\" placeholder=\"Password\" required class=\"w-full bg-slate-900 border border-slate-600 rounded p-2 focus:border-sky-500 outline-none\">"
                +
                "<button type=\"submit\" class=\"bg-sky-500 hover:bg-sky-600 text-white font-bold py-2 rounded transition\">Login</button>"
                +
                "</form></div></body></html>";
        sendResponse(t, html);
    }

    private class ConsoleApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!isAuthenticated(t)) {
                t.sendResponseHeaders(403, 0);
                t.close();
                return;
            }
            List<String> logs = ConsoleLogAppender.getLogs();
            String response = String.join("\n", logs);
            sendResponse(t, response);
        }
    }

    private class StatsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            try {
                if (!isAuthenticated(t)) {
                    t.sendResponseHeaders(403, 0);
                    t.close();
                    return;
                }

                double currentTps = 20.0;
                try {
                    double[] tps = Bukkit.getTPS();
                    currentTps = (tps != null && tps.length > 0) ? tps[0] : 20.0;
                    if (currentTps > 20.0)
                        currentTps = 20.0;
                } catch (Throwable ignored) {
                }

                long maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024;
                long totalMem = Runtime.getRuntime().totalMemory() / 1024 / 1024;
                long freeMem = Runtime.getRuntime().freeMemory() / 1024 / 1024;
                long usedMem = totalMem - freeMem;

                String uptimeStr = "Unknown";
                try {
                    long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
                    long uptimeSeconds = uptimeMillis / 1000;
                    long h = uptimeSeconds / 3600;
                    long m = (uptimeSeconds % 3600) / 60;
                    uptimeStr = String.format("%dh %dm", h, m);
                } catch (Exception ignored) {
                }

                String json = String.format("{\"tps\": %.2f, \"ram_used\": %d, \"ram_max\": %d, \"uptime\": \"%s\"}",
                        currentTps, usedMem, maxMem, uptimeStr);

                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, json);
            } catch (Exception e) {
                sendResponse(t, "{\"error\": \"Internal Error\"}");
            }
        }
    }

    private class StatsHistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!isAuthenticated(t)) {
                t.sendResponseHeaders(403, 0);
                t.close();
                return;
            }
            try {
                StatsCollector collector = Dash.getStatsCollector();
                String json = collector != null ? collector.getHistoryJson() : "[]";
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, json);
            } catch (Exception e) {
                sendResponse(t, "[]");
            }
        }
    }

    private class FileSaveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!isAuthenticated(t)) {
                t.sendResponseHeaders(403, 0);
                t.close();
                return;
            }

            if ("POST".equals(t.getRequestMethod())) {
                String body = new String(t.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> params = parseFormData(body);

                String filePath = params.get("path");
                String content = params.get("content");

                if (filePath == null || content == null) {
                    t.getResponseHeaders().add("Content-Type", "application/json");
                    sendResponse(t, "{\"success\": false, \"error\": \"Missing parameters\"}");
                    return;
                }

                File serverDir = Bukkit.getWorldContainer();
                File file = new File(serverDir, filePath);

                try {
                    if (!file.getCanonicalPath().startsWith(serverDir.getCanonicalPath())) {
                        t.getResponseHeaders().add("Content-Type", "application/json");
                        sendResponse(t, "{\"success\": false, \"error\": \"Access denied\"}");
                        return;
                    }

                    if (file.exists()) {
                        File backup = new File(file.getPath() + ".bak");
                        Files.copy(file.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }

                    Files.writeString(file.toPath(), content);
                    WebActionLogger.logFileEdit(filePath, getClientIp(t));
                    t.getResponseHeaders().add("Content-Type", "application/json");
                    sendResponse(t, "{\"success\": true}");
                } catch (Exception e) {
                    t.getResponseHeaders().add("Content-Type", "application/json");
                    sendResponse(t, "{\"success\": false, \"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
                }
            }
        }
    }

    private class ActionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, 0);
                t.close();
                return;
            }

            String body = new String(t.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(body);
            String action = params.get("action");
            String referer = t.getRequestHeaders().getFirst("Referer");
            String redirectTo = referer != null ? referer : "/";
            String clientIp = getClientIp(t);

            if ("register".equals(action)) {
                String code = params.get("code");
                String username = params.get("username");
                String password = params.get("password");

                if (code != null && username != null && password != null) {
                    boolean success = auth.registerWithCode(code, username, password);
                    if (success) {
                        redirect(t, "/");
                        return;
                    }
                }
                sendResponse(t,
                        "<!DOCTYPE html><html><body style='background:#0f172a;color:white;display:flex;justify-content:center;align-items:center;height:100vh;font-family:sans-serif'><div style='text-align:center'><h1 style='color:#f43f5e'>Registration Failed</h1><p>Invalid or expired registration code.</p><a href='/' style='color:#0ea5e9'>Try Again</a></div></body></html>");
                return;
            }

            if ("login".equals(action)) {
                if (auth.check(params.get("username"), params.get("password"))) {
                    setSession(t);
                    WebActionLogger.logLogin(params.get("username"), clientIp);
                    redirect(t, "/");
                } else {
                    sendResponse(t, "<h1>Login Failed</h1><a href='/'>Try Again</a>");
                }
                return;
            }

            if (!isAuthenticated(t)) {
                redirect(t, "/");
                return;
            }

            if ("logout".equals(action)) {
                t.getResponseHeaders().add("Set-Cookie", "session=; Path=/; Max-Age=0");
                WebActionLogger.logLogout(clientIp);
                redirect(t, "/");
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                switch (action) {
                    case "command":
                        String cmd = params.get("cmd");
                        if (cmd != null && !cmd.isEmpty()) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                            WebActionLogger.logCommand(cmd, clientIp);
                        }
                        break;
                    case "restart":
                        WebActionLogger.log("RESTART", "Server restart initiated from " + clientIp);
                        Bukkit.spigot().restart();
                        break;
                    case "stop":
                        WebActionLogger.log("STOP", "Server stop initiated from " + clientIp);
                        Bukkit.shutdown();
                        break;
                    case "kick":
                        Player kickTarget = Bukkit.getPlayerExact(params.get("player"));
                        if (kickTarget != null) {
                            kickTarget.kick(Component.text("Kicked by admin"));
                            WebActionLogger.logPlayerAction("KICK", params.get("player"), clientIp);
                        }
                        break;
                    case "ban":
                        String banName = params.get("player");
                        Bukkit.getBanList(BanList.Type.NAME).addBan(banName, "Banned by admin", null, "WebConsole");
                        Player banTarget = Bukkit.getPlayerExact(banName);
                        if (banTarget != null)
                            banTarget.kick(Component.text("Banned by admin"));
                        WebActionLogger.logPlayerAction("BAN", banName, clientIp);
                        break;
                    case "freeze":
                        Player freezeTarget = Bukkit.getPlayerExact(params.get("player"));
                        if (freezeTarget != null) {
                            UUID uuid = freezeTarget.getUniqueId();
                            if (FreezeManager.toggleFreeze(uuid)) {
                                freezeTarget.sendMessage(
                                        net.kyori.adventure.text.Component.text("§cYou have been frozen by an admin."));
                                WebActionLogger.logPlayerAction("FREEZE", params.get("player"), clientIp);
                            } else {
                                freezeTarget.sendMessage(net.kyori.adventure.text.Component
                                        .text("§aYou have been unfrozen by an admin."));
                                WebActionLogger.logPlayerAction("UNFREEZE", params.get("player"), clientIp);
                            }
                        }
                        break;
                    case "tp_to_coords":
                        String coordsPlayer = params.get("player");
                        String xStr = params.get("x");
                        String yStr = params.get("y");
                        String zStr = params.get("z");
                        if (coordsPlayer != null && xStr != null && yStr != null && zStr != null) {
                            Player coordsTarget = Bukkit.getPlayerExact(coordsPlayer);
                            if (coordsTarget != null) {
                                try {
                                    double x = Double.parseDouble(xStr);
                                    double y = Double.parseDouble(yStr);
                                    double z = Double.parseDouble(zStr);
                                    coordsTarget.teleport(new Location(coordsTarget.getWorld(), x, y, z));
                                    WebActionLogger.logPlayerAction("TELEPORT_COORDS",
                                            coordsPlayer + " to " + x + "," + y + "," + z, clientIp);
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        break;
                    case "tp_player_to_player":
                        String targetName = params.get("target");
                        String destName = params.get("destination");
                        if (targetName != null && destName != null) {
                            Player targetPlayer = Bukkit.getPlayerExact(targetName);
                            Player destPlayer = Bukkit.getPlayerExact(destName);
                            if (targetPlayer != null && destPlayer != null) {
                                targetPlayer.teleport(destPlayer.getLocation());
                                WebActionLogger.logPlayerAction("TELEPORT_TO_PLAYER", targetName + " to " + destName,
                                        clientIp);
                            }
                        }
                        break;
                    case "gamerule":
                        String rule = params.get("rule");
                        String value = params.get("value");
                        if (rule != null && value != null) {
                            boolean boolValue = Boolean.parseBoolean(value);
                            for (World world : Bukkit.getWorlds()) {
                                switch (rule) {
                                    case "keepInventory":
                                        world.setGameRule(GameRule.KEEP_INVENTORY, boolValue);
                                        break;
                                    case "doMobSpawning":
                                        world.setGameRule(GameRule.DO_MOB_SPAWNING, boolValue);
                                        break;
                                    case "doDaylightCycle":
                                        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, boolValue);
                                        break;
                                    case "doWeatherCycle":
                                        world.setGameRule(GameRule.DO_WEATHER_CYCLE, boolValue);
                                        break;
                                    case "mobGriefing":
                                        world.setGameRule(GameRule.MOB_GRIEFING, boolValue);
                                        break;
                                    case "doFireTick":
                                        world.setGameRule(GameRule.DO_FIRE_TICK, boolValue);
                                        break;
                                    case "naturalRegeneration":
                                        world.setGameRule(GameRule.NATURAL_REGENERATION, boolValue);
                                        break;
                                }
                            }
                            WebActionLogger.logSettingChange("gamerule." + rule, value, clientIp);
                        }
                        break;
                    case "whitelist_add":
                        String addPlayer = params.get("player");
                        if (addPlayer != null) {
                            OfflinePlayer op = Bukkit.getOfflinePlayer(addPlayer);
                            op.setWhitelisted(true);
                            WebActionLogger.logPlayerAction("WHITELIST_ADD", addPlayer, clientIp);
                        }
                        break;
                    case "whitelist_remove":
                        String removePlayer = params.get("player");
                        if (removePlayer != null) {
                            OfflinePlayer op = Bukkit.getOfflinePlayer(removePlayer);
                            op.setWhitelisted(false);
                            WebActionLogger.logPlayerAction("WHITELIST_REMOVE", removePlayer, clientIp);
                        }
                        break;
                    case "whitelist_toggle":
                        boolean newState = !Bukkit.hasWhitelist();
                        Bukkit.setWhitelist(newState);
                        WebActionLogger.logSettingChange("whitelist", String.valueOf(newState), clientIp);
                        break;
                    case "plugin_enable":
                        String enablePlugin = params.get("plugin");
                        if (enablePlugin != null) {
                            Plugin p = Bukkit.getPluginManager().getPlugin(enablePlugin);
                            if (p != null) {
                                Bukkit.getPluginManager().enablePlugin(p);
                                WebActionLogger.logPluginAction("ENABLE", enablePlugin, clientIp);
                            }
                        }
                        break;
                    case "plugin_disable":
                        String disablePlugin = params.get("plugin");
                        if (disablePlugin != null) {
                            Plugin p = Bukkit.getPluginManager().getPlugin(disablePlugin);
                            if (p != null) {
                                Bukkit.getPluginManager().disablePlugin(p);
                                WebActionLogger.logPluginAction("DISABLE", disablePlugin, clientIp);
                            }
                        }
                        break;
                    case "chat":
                        String chatMsg = params.get("message");
                        String chatAs = params.get("as");
                        if (chatMsg != null && !chatMsg.isEmpty()) {
                            String prefix = "as".equals(chatAs) ? "[ADMIN]" : "[SERVER]";
                            Bukkit.broadcast(Component.text(prefix + " " + chatMsg));
                            WebActionLogger.log("BROADCAST", prefix + " " + chatMsg + " from " + clientIp);
                        }
                        break;
                    case "mute":
                        WebActionLogger.logPlayerAction("MUTE", params.get("player"), clientIp);
                        break;
                    case "backup_create":
                        dash.data.BackupManager bm = Dash.getBackupManager();
                        if (bm != null) {
                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                bm.createBackup();
                                WebActionLogger.logBackup("CREATE", "Manual backup from " + clientIp);
                            });
                        }
                        break;
                    case "backup_delete":
                        dash.data.BackupManager bmd = Dash.getBackupManager();
                        String backupName = params.get("name");
                        if (bmd != null && backupName != null) {
                            bmd.deleteBackup(backupName);
                            WebActionLogger.logBackup("DELETE", backupName + " from " + clientIp);
                        }
                        break;
                    case "backup_schedule":
                        dash.data.BackupManager bms = Dash.getBackupManager();
                        String hours = params.get("hours");
                        if (bms != null && hours != null) {
                            bms.startSchedule(Integer.parseInt(hours));
                            WebActionLogger.logBackup("SCHEDULE", hours + " hours from " + clientIp);
                        }
                        break;
                    case "datapack_toggle":
                        String dpName = params.get("name");
                        boolean dpEnable = Boolean.parseBoolean(params.get("enable"));
                        if (dpName != null) {
                            dash.data.DatapackManager.toggleDatapack(dpName, dpEnable);
                            WebActionLogger.log("DATAPACK_TOGGLE", dpName + " = " + dpEnable + " from " + clientIp);
                        }
                        break;
                    case "datapack_delete":
                        String dpDelName = params.get("name");
                        if (dpDelName != null) {
                            dash.data.DatapackManager.deleteDatapack(dpDelName);
                            WebActionLogger.log("DATAPACK_DELETE", dpDelName + " from " + clientIp);
                        }
                        break;
                    case "set_motd":
                        String motd = params.get("motd");
                        if (motd != null) {
                            try {
                                File serverProps = new File(Bukkit.getWorldContainer(), "server.properties");
                                if (serverProps.exists()) {
                                    java.util.Properties props = new java.util.Properties();
                                    try (FileInputStream fis = new FileInputStream(serverProps)) {
                                        props.load(fis);
                                    }
                                    props.setProperty("motd", motd);
                                    try (FileOutputStream fos = new FileOutputStream(serverProps)) {
                                        props.store(fos, null);
                                    }
                                    WebActionLogger.logSettingChange("motd", motd, clientIp);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        break;
                    case "spark_profile":
                        Plugin spark = Bukkit.getPluginManager().getPlugin("spark");
                        if (spark != null && spark.isEnabled()) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "spark profiler start --timeout 60");
                            WebActionLogger.log("SPARK_PROFILE", "Started from " + clientIp);
                        }
                        break;
                    case "add_note":
                        String noteUuid = params.get("uuid");
                        String noteText = params.get("note");
                        if (noteUuid != null && noteText != null) {
                            dash.data.PlayerDataManager pdm = Dash.getPlayerDataManager();
                            if (pdm != null) {
                                pdm.addNote(noteUuid, "WebAdmin", noteText);
                                WebActionLogger.log("ADD_NOTE", "Player " + noteUuid + " from " + clientIp);
                            }
                        }
                        break;
                    case "delete_note":
                        String noteIdStr = params.get("id");
                        if (noteIdStr != null) {
                            dash.data.PlayerDataManager pdm2 = Dash.getPlayerDataManager();
                            if (pdm2 != null) {
                                pdm2.deleteNote(Integer.parseInt(noteIdStr));
                                WebActionLogger.log("DELETE_NOTE", "Note " + noteIdStr + " from " + clientIp);
                            }
                        }
                        break;
                    case "set_distance":
                        String viewDist = params.get("view");
                        String simDist = params.get("sim");
                        if (viewDist != null && simDist != null) {
                            try {
                                File propsFile = new File(Bukkit.getWorldContainer(), "server.properties");
                                java.util.Properties props = new java.util.Properties();
                                try (java.io.FileInputStream fis = new java.io.FileInputStream(propsFile)) {
                                    props.load(fis);
                                }
                                props.setProperty("view-distance", viewDist);
                                props.setProperty("simulation-distance", simDist);
                                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(propsFile)) {
                                    props.store(fos, null);
                                }
                                WebActionLogger.logSettingChange("distances", "view=" + viewDist + ",sim=" + simDist,
                                        clientIp);
                            } catch (Exception ignored) {
                            }
                        }
                        break;
                    case "give_item":
                        String giveItemPlayer = params.get("player");
                        String giveItemMaterial = params.get("material");
                        String giveItemAmountStr = params.get("amount");
                        if (giveItemPlayer != null && giveItemMaterial != null) {
                            Player target = Bukkit.getPlayerExact(giveItemPlayer);
                            if (target != null) {
                                try {
                                    Material mat = Material.matchMaterial(giveItemMaterial.toUpperCase());
                                    int amount = giveItemAmountStr != null ? Integer.parseInt(giveItemAmountStr) : 1;
                                    amount = Math.max(1, Math.min(amount, 64));
                                    if (mat != null && mat.isItem()) {
                                        ItemStack item = new ItemStack(mat, amount);
                                        target.getInventory().addItem(item);
                                        WebActionLogger.log("GIVE_ITEM", amount + "x " + mat.name() + " to "
                                                + giveItemPlayer + " from " + clientIp);
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        break;
                    case "give_enderchest":
                        String giveECPlayer = params.get("player");
                        String giveECMaterial = params.get("material");
                        String giveECAmountStr = params.get("amount");
                        if (giveECPlayer != null && giveECMaterial != null) {
                            Player target = Bukkit.getPlayerExact(giveECPlayer);
                            if (target != null) {
                                try {
                                    Material mat = Material.matchMaterial(giveECMaterial.toUpperCase());
                                    int amount = giveECAmountStr != null ? Integer.parseInt(giveECAmountStr) : 1;
                                    amount = Math.max(1, Math.min(amount, 64));
                                    if (mat != null && mat.isItem()) {
                                        ItemStack item = new ItemStack(mat, amount);
                                        target.getEnderChest().addItem(item);
                                        WebActionLogger.log("GIVE_ENDERCHEST", amount + "x " + mat.name() + " to "
                                                + giveECPlayer + " from " + clientIp);
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        break;
                }
            });

            redirect(t, redirectTo);
        }
    }

    private void redirect(HttpExchange t, String location) throws IOException {
        t.getResponseHeaders().set("Location", location);
        t.sendResponseHeaders(302, -1);
        t.close();
    }

    private void sendResponse(HttpExchange t, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(200, bytes.length);
        OutputStream os = t.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private Map<String, String> parseFormData(String formData) {
        Map<String, String> map = new HashMap<>();
        String[] pairs = formData.split("&");
        for (String pair : pairs) {
            String[] keyVal = pair.split("=", 2);
            if (keyVal.length == 2) {
                try {
                    String key = URLDecoder.decode(keyVal[0], "UTF-8");
                    String val = URLDecoder.decode(keyVal[1], "UTF-8");
                    map.put(key, val);
                } catch (Exception ignored) {
                }
            } else if (keyVal.length == 1) {
                try {
                    String key = URLDecoder.decode(keyVal[0], "UTF-8");
                    map.put(key, "");
                } catch (Exception ignored) {
                }
            }
        }
        return map;
    }

    private class BackupDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!isAuthenticated(t)) {
                t.sendResponseHeaders(403, 0);
                t.close();
                return;
            }

            String query = t.getRequestURI().getQuery();
            String name = null;
            if (query != null && query.startsWith("name=")) {
                name = URLDecoder.decode(query.substring(5), "UTF-8");
            }

            dash.data.BackupManager bm = Dash.getBackupManager();
            if (bm == null || name == null) {
                t.sendResponseHeaders(404, 0);
                t.close();
                return;
            }

            File file = bm.getBackupFile(name);
            if (file == null) {
                t.sendResponseHeaders(404, 0);
                t.close();
                return;
            }

            WebActionLogger.logBackup("DOWNLOAD", name + " from " + getClientIp(t));

            t.getResponseHeaders().set("Content-Type", "application/zip");
            t.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");
            t.sendResponseHeaders(200, file.length());

            try (OutputStream os = t.getResponseBody(); FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
            }
        }
    }

    private class IconUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!isAuthenticated(t)) {
                t.sendResponseHeaders(403, 0);
                t.close();
                return;
            }

            if (!"POST".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, 0);
                t.close();
                return;
            }

            try {
                byte[] body = t.getRequestBody().readAllBytes();
                String contentType = t.getRequestHeaders().getFirst("Content-Type");

                if (contentType != null && contentType.contains("multipart/form-data")) {
                    String boundary = contentType.split("boundary=")[1];
                    byte[] fileData = extractFileFromMultipart(body, boundary);

                    if (fileData != null) {
                        File iconFile = new File(Bukkit.getWorldContainer(), "server-icon.png");
                        Files.write(iconFile.toPath(), fileData);
                        WebActionLogger.logUpload("ICON", "server-icon.png", getClientIp(t));

                        t.getResponseHeaders().add("Content-Type", "application/json");
                        sendResponse(t, "{\"success\": true}");
                        return;
                    }
                }

                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\": false, \"error\": \"Invalid upload\"}");
            } catch (Exception e) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\": false, \"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        }
    }

    private class DatapackUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!isAuthenticated(t)) {
                t.sendResponseHeaders(403, 0);
                t.close();
                return;
            }

            if (!"POST".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, 0);
                t.close();
                return;
            }

            try {
                byte[] body = t.getRequestBody().readAllBytes();
                String contentType = t.getRequestHeaders().getFirst("Content-Type");

                if (contentType != null && contentType.contains("multipart/form-data")) {
                    String boundary = contentType.split("boundary=")[1];
                    byte[] fileData = extractFileFromMultipart(body, boundary);
                    String fileName = extractFileNameFromMultipart(body, boundary);

                    if (fileData != null && fileName != null) {
                        boolean success = dash.data.DatapackManager.uploadDatapack(fileName, fileData);
                        if (success) {
                            WebActionLogger.logUpload("DATAPACK", fileName, getClientIp(t));
                        }
                        t.getResponseHeaders().add("Content-Type", "application/json");
                        sendResponse(t, "{\"success\": " + success + "}");
                        return;
                    }
                }

                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\": false, \"error\": \"Invalid upload\"}");
            } catch (Exception e) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\": false, \"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        }
    }

    private class PlayerProfileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!isAuthenticated(t)) {
                t.sendResponseHeaders(403, 0);
                t.close();
                return;
            }

            String query = t.getRequestURI().getQuery();
            String name = null;
            if (query != null && query.startsWith("name=")) {
                name = URLDecoder.decode(query.substring(5), "UTF-8");
            }

            if (name == null) {
                t.sendResponseHeaders(400, 0);
                t.close();
                return;
            }

            dash.data.PlayerDataManager pdm = Dash.getPlayerDataManager();
            if (pdm == null) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"error\": \"Player data not available\"}");
                return;
            }

            dash.data.PlayerDataManager.PlayerInfo info = pdm.getPlayerInfo(name);
            if (info == null) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"error\": \"Player not found\"}");
                return;
            }

            StringBuilder json = new StringBuilder();
            json.append("{\"uuid\":\"").append(info.uuid()).append("\",");
            json.append("\"name\":\"").append(info.name()).append("\",");
            json.append("\"firstJoin\":").append(info.firstJoin()).append(",");
            json.append("\"lastJoin\":").append(info.lastJoin()).append(",");
            json.append("\"playtime\":\"").append(info.getFormattedPlaytime()).append("\",");
            json.append("\"sessions\":[");

            var sessions = pdm.getPlayerSessions(info.uuid(), 10);
            for (int i = 0; i < sessions.size(); i++) {
                var s = sessions.get(i);
                if (i > 0)
                    json.append(",");
                json.append("{\"join\":").append(s.joinTime()).append(",\"duration\":\"").append(s.getDuration())
                        .append("\"}");
            }
            json.append("],\"notes\":[");

            var notes = pdm.getPlayerNotes(info.uuid());
            for (int i = 0; i < notes.size(); i++) {
                var n = notes.get(i);
                if (i > 0)
                    json.append(",");
                json.append("{\"id\":").append(n.id()).append(",\"admin\":\"").append(n.adminName())
                        .append("\",\"note\":\"").append(n.note().replace("\"", "\\\"")).append("\",\"time\":")
                        .append(n.createdAt()).append("}");
            }
            json.append("]}");

            t.getResponseHeaders().add("Content-Type", "application/json");
            sendResponse(t, json.toString());
        }
    }

    private byte[] extractFileFromMultipart(byte[] body, String boundary) {
        try {
            String bodyStr = new String(body, StandardCharsets.ISO_8859_1);
            String delimiter = "--" + boundary;
            int start = bodyStr.indexOf(delimiter);
            if (start == -1)
                return null;

            int headerEnd = bodyStr.indexOf("\r\n\r\n", start);
            if (headerEnd == -1)
                return null;

            int contentStart = headerEnd + 4;
            int contentEnd = bodyStr.indexOf("\r\n" + delimiter, contentStart);
            if (contentEnd == -1)
                return null;

            return Arrays.copyOfRange(body, contentStart, contentEnd);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractFileNameFromMultipart(byte[] body, String boundary) {
        try {
            String bodyStr = new String(body, StandardCharsets.UTF_8);
            int fnIndex = bodyStr.indexOf("filename=\"");
            if (fnIndex == -1)
                return "upload.zip";

            int fnStart = fnIndex + 10;
            int fnEnd = bodyStr.indexOf("\"", fnStart);
            if (fnEnd == -1)
                return "upload.zip";

            return bodyStr.substring(fnStart, fnEnd);
        } catch (Exception e) {
            return "upload.zip";
        }
    }

    private class FileUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!isAuthenticated(t)) {
                t.sendResponseHeaders(403, 0);
                t.close();
                return;
            }
            if (!"POST".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, 0);
                t.close();
                return;
            }

            try {
                byte[] body = t.getRequestBody().readAllBytes();
                String contentType = t.getRequestHeaders().getFirst("Content-Type");

                if (contentType != null && contentType.contains("multipart/form-data")) {
                    String boundary = contentType.split("boundary=")[1];
                    byte[] fileData = extractFileFromMultipart(body, boundary);
                    String fileName = extractFileNameFromMultipart(body, boundary);
                    String path = extractFormField(body, boundary, "path");
                    String fullPath = extractFormField(body, boundary, "fullpath");

                    if (fileData != null && fileName != null) {
                        File serverDir = Bukkit.getWorldContainer();
                        File targetFile;

                        if (fullPath != null && !fullPath.isEmpty()) {
                            fullPath = fullPath.replaceAll("\\.\\.", "").replaceAll("//+", "/");
                            targetFile = new File(serverDir, fullPath);
                        } else {
                            fileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
                            File targetDir = path != null && !path.isEmpty() ? new File(serverDir, path) : serverDir;
                            targetFile = new File(targetDir, fileName);
                        }

                        if (targetFile.getCanonicalPath().startsWith(serverDir.getCanonicalPath())) {
                            File parentDir = targetFile.getParentFile();
                            if (parentDir != null && !parentDir.exists()) {
                                parentDir.mkdirs();
                            }
                            Files.write(targetFile.toPath(), fileData);
                            WebActionLogger.logUpload("FILE", targetFile.getName(), getClientIp(t));
                            t.getResponseHeaders().add("Content-Type", "application/json");
                            sendResponse(t, "{\"success\": true}");
                            return;
                        }
                    }
                }
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\": false, \"error\": \"Invalid upload\"}");
            } catch (Exception e) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\": false, \"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        }
    }

    private class PluginUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!isAuthenticated(t)) {
                t.sendResponseHeaders(403, 0);
                t.close();
                return;
            }
            if (!"POST".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, 0);
                t.close();
                return;
            }

            try {
                byte[] body = t.getRequestBody().readAllBytes();
                String contentType = t.getRequestHeaders().getFirst("Content-Type");

                if (contentType != null && contentType.contains("multipart/form-data")) {
                    String boundary = contentType.split("boundary=")[1];
                    byte[] fileData = extractFileFromMultipart(body, boundary);
                    String fileName = extractFileNameFromMultipart(body, boundary);

                    if (fileData != null && fileName != null && fileName.endsWith(".jar")) {
                        File pluginsDir = new File(Bukkit.getWorldContainer(), "plugins");
                        fileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
                        File targetFile = new File(pluginsDir, fileName);

                        Files.write(targetFile.toPath(), fileData);
                        WebActionLogger.logUpload("PLUGIN", fileName, getClientIp(t));
                        t.getResponseHeaders().add("Content-Type", "application/json");
                        sendResponse(t, "{\"success\": true, \"message\": \"Plugin uploaded. Restart to load.\"}");
                        return;
                    }
                }
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\": false, \"error\": \"Only .jar files allowed\"}");
            } catch (Exception e) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\": false, \"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        }
    }

    private String extractFormField(byte[] body, String boundary, String fieldName) {
        try {
            String bodyStr = new String(body, StandardCharsets.UTF_8);
            String search = "name=\"" + fieldName + "\"";
            int fieldIdx = bodyStr.indexOf(search);
            if (fieldIdx == -1)
                return null;

            int valueStart = bodyStr.indexOf("\r\n\r\n", fieldIdx) + 4;
            int valueEnd = bodyStr.indexOf("\r\n--", valueStart);
            if (valueEnd == -1)
                return null;

            return bodyStr.substring(valueStart, valueEnd).trim();
        } catch (Exception e) {
            return null;
        }
    }
}
