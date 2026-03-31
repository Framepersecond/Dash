package dash;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import dash.bridge.ConsoleCatcher;
import dash.data.BackupManager;
import dash.data.PlayerDataManager;
import dash.data.AuditDataManager;
import dash.data.ScheduledTaskManager;

public class Dash extends JavaPlugin {

    private static Dash instance;
    private BukkitTask killTask;
    private AdminWebServer webServer;
    private static StatsCollector statsCollector;
    private static PlayerDataManager playerDataManager;
    private static BackupManager backupManager;
    private static RegistrationManager registrationManager;
    private static RegistrationApprovalManager registrationApprovalManager;
    private static DiscordWebhookManager discordWebhookManager;
    private static AuditDataManager auditDataManager;
    private static ScheduledTaskManager scheduledTaskManager;
    private static int webPort;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getConfig().addDefault("bridge.enabled", true);
        getConfig().addDefault("bridge.secret", "your-super-secret-key");
        getConfig().addDefault("bridge.master_url", "");
        getConfig().addDefault("discord.webhook_url", "");
        getConfig().options().copyDefaults(true);
        saveConfig();

        webPort = getConfig().getInt("port", 8080);
        WebActionLogger.init(getLogger());

        if (getConfig().getBoolean("bridge.enabled", true)) {
            ConsoleCatcher.register();
            getLogger().info("NeoBridge mode enabled.");
        }

        logStartupBetaBanner();

        playerDataManager = new PlayerDataManager(this);
        backupManager = new BackupManager(this);
        registrationManager = new RegistrationManager();
        registrationApprovalManager = new RegistrationApprovalManager();
        auditDataManager = new AuditDataManager(this);
        discordWebhookManager = new DiscordWebhookManager(this);
        scheduledTaskManager = new ScheduledTaskManager(this);

        WebActionLogger.setAuditManager(auditDataManager);
        WebActionLogger.setDiscordWebhookManager(discordWebhookManager);

        WebAuth webAuth = new WebAuth(this);
        if (webAuth.isSetupRequired()) {
            SetupNotifier setupNotifier = new SetupNotifier(this, webAuth, registrationManager);
            getServer().getPluginManager().registerEvents(setupNotifier, this);
            setupNotifier.sendDiscordSetupNotificationIfConfigured();
        }

        statsCollector = new StatsCollector(this);
        statsCollector.start();

        webServer = new AdminWebServer(this, webPort);
        webServer.start();

        getServer().getPluginManager().registerEvents(new FreezeManager(), this);
        getServer().getPluginManager().registerEvents(new DiscordWebhookChatListener(), this);

        getCommand("dash").setExecutor(new DashCommand(this));

        getLogger().info("Dash Admin started on port " + webPort);

        if (discordWebhookManager != null) {
            discordWebhookManager.dispatchEmbed(
                    DiscordWebhookManager.EVENT_SERVER_START_STOP,
                    "Server Started", "The Minecraft server is now online.", 0x10B981);
        }
    }

    private void logStartupBetaBanner() {
        getLogger().info("==============================================================");
        getLogger().info("BBBBBB   EEEEEEE  TTTTTTT   AAAAA  ");
        getLogger().info("BB   BB  EE         TTT    AA   AA ");
        getLogger().info("BBBBBB   EEEEE      TTT    AAAAAAA ");
        getLogger().info("BB   BB  EE         TTT    AA   AA ");
        getLogger().info("BBBBBB   EEEEEEE    TTT    AA   AA ");
        getLogger().info(" ");
        getLogger().info("for Techno (Non Comercial use Only), Bugs may occur.");
        getLogger().info("==============================================================");
    }

    public static StatsCollector getStatsCollector() {
        return statsCollector;
    }

    public static PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public static BackupManager getBackupManager() {
        return backupManager;
    }

    public static RegistrationManager getRegistrationManager() {
        return registrationManager;
    }

    public static RegistrationApprovalManager getRegistrationApprovalManager() {
        return registrationApprovalManager;
    }

    public static Dash getInstance() {
        return instance;
    }

    public static DiscordWebhookManager getDiscordWebhookManager() {
        return discordWebhookManager;
    }

    public static AuditDataManager getAuditDataManager() {
        return auditDataManager;
    }

    public static ScheduledTaskManager getScheduledTaskManager() {
        return scheduledTaskManager;
    }

    public static int getWebPort() {
        return webPort;
    }

    @Override
    public void onDisable() {
        if (scheduledTaskManager != null) {
            scheduledTaskManager.close();
        }
        if (auditDataManager != null) {
            auditDataManager.close();
        }
        if (backupManager != null) {
            backupManager.stop();
        }
        if (playerDataManager != null) {
            playerDataManager.close();
        }
        if (statsCollector != null) {
            statsCollector.stop();
        }
        if (webServer != null) {
            webServer.stop();
        }
        if (killTask != null) {
            killTask.cancel();
        }
        if (discordWebhookManager != null) {
            discordWebhookManager.dispatchEmbed(
                    DiscordWebhookManager.EVENT_SERVER_START_STOP,
                    "Server Stopped", "The Minecraft server has been shut down.", 0xF43F5E);
        }
        getLogger().info("Dash Admin stopped");
    }
}
