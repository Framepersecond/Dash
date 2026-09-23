package dash;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import dash.bridge.ConsoleCatcher;
import dash.bridge.BridgeSecurity;
import dash.data.BackupManager;
import dash.data.PlayerDataManager;
import dash.data.AuditDataManager;
import dash.data.GuardianDataManager;
import dash.data.ScheduledTaskManager;
import dash.api.ApiKeyStore;
import dash.api.BukkitReadApi;
import dash.api.DashApiProvider;
import dash.api.DashReadApi;
import dash.security.FilePermissions;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

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
    private static GuardianDataManager guardianDataManager;
    private static ScheduledTaskManager scheduledTaskManager;
    private static GithubUpdater githubUpdater;
    private static int webPort;
    private BukkitAudiences adventure;
    private BukkitTask updaterScanTask;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getConfig().addDefault("bridge.enabled", true);
        getConfig().addDefault("bridge.secret", "");
        getConfig().addDefault("bridge.master_url", "");
        getConfig().addDefault("beta.enabled", false);
        getConfig().addDefault("server-ip", "");
        getConfig().addDefault("ssl-enabled", false);
        getConfig().addDefault("panel-url", "");
        getConfig().addDefault("report-url", "");
        getConfig().addDefault("discord.webhook_url", "");
        getConfig().addDefault("updater.github-repo", "Framepersecond/Dash");
        getConfig().options().copyDefaults(true);
        ensureSecureBridgeSecret();
        saveConfig();
        FilePermissions.ownerReadWrite(getDataFolder().toPath().resolve("config.yml"));

        webPort = getConfig().getInt("port", 8080);
        WebActionLogger.init(getLogger());

        if (getConfig().getBoolean("bridge.enabled", true)) {
            ConsoleCatcher.register();
            getLogger().info("NeoBridge mode enabled.");
        }
        this.adventure = BukkitAudiences.create(this);
        logStartupBetaBanner();

        playerDataManager = new PlayerDataManager(this);
        backupManager = new BackupManager(this);
        registrationManager = new RegistrationManager();
        registrationApprovalManager = new RegistrationApprovalManager();
        auditDataManager = new AuditDataManager(this);
        guardianDataManager = new GuardianDataManager(
                getDataFolder().toPath(),
                task -> Bukkit.getScheduler().runTaskAsynchronously(this, task),
                msg -> getLogger().warning(msg));
        discordWebhookManager = new DiscordWebhookManager(this);
        scheduledTaskManager = new ScheduledTaskManager(this);
        githubUpdater = new GithubUpdater(this);

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

        // Publish the read-only API. This happens regardless of whether the
        // HTTP API is enabled, so plugins on this server can integrate in
        // process without any network surface being opened at all.
        DashReadApi readApi = new BukkitReadApi();
        DashApiProvider.install(readApi);
        getServer().getServicesManager().register(DashReadApi.class, readApi, this,
                org.bukkit.plugin.ServicePriority.Normal);

        webServer = new AdminWebServer(this, webPort);
        webServer.start();

        startUpdaterSchedule();

        getServer().getPluginManager().registerEvents(new FreezeManager(), this);
        getServer().getPluginManager().registerEvents(new DiscordWebhookChatListener(), this);
        getServer().getPluginManager().registerEvents(new GuardianBukkitListener(guardianDataManager), this);
        getServer().getPluginManager().registerEvents(new DashIngameUi(this), this);

        getCommand("dash").setExecutor(new DashCommand(this));

        getLogger().info("Dash Admin started on port " + webPort);

        if (discordWebhookManager != null) {
            discordWebhookManager.dispatchEmbed(
                    DiscordWebhookManager.EVENT_SERVER_START_STOP,
                    "Server Started", "The Minecraft server is now online.", 0x10B981);
        }
    }

    private void logStartupBetaBanner() {
        getLogger().info("=======================");
        getLogger().info("  333333         000   ");
        getLogger().info("       33       0   0  ");
        getLogger().info("  33333    ..  0   0   ");
        getLogger().info("       33  ..   0   0  ");
        getLogger().info("  333333        000    ");
        getLogger().info("=======================");
    }

    public BukkitAudiences adventure() {
        return this.adventure;
    }

    public static StatsCollector getStatsCollector() {
        return statsCollector;
    }

    private static dash.api.ApiKeyStore apiKeyStore;

    /**
     * The read-only API key store, created on first use.
     *
     * <p>Deliberately a single instance: the store serialises its own writes,
     * so two instances over the same file could interleave and lose a key.
     */
    public static dash.api.ApiKeyStore getApiKeyStore() {
        if (apiKeyStore == null) {
            apiKeyStore = new dash.api.ApiKeyStore(
                    getPlugin(Dash.class).getDataFolder().toPath().resolve("api-keys.txt"));
        }
        return apiKeyStore;
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

    private void ensureSecureBridgeSecret() {
        String secret = getConfig().getString("bridge.secret", "").trim();
        if (secret.isBlank() || "your-super-secret-key".equals(secret)) {
            getConfig().set("bridge.secret", BridgeSecurity.generateSecret());
            getLogger().warning("Generated a new random NeoBridge secret because the configured value was empty or insecure.");
        }
    }

    public static GuardianDataManager getGuardianDataManager() {
        return guardianDataManager;
    }

    public static ScheduledTaskManager getScheduledTaskManager() {
        return scheduledTaskManager;
    }

    public static GithubUpdater getGithubUpdater() {
        return githubUpdater;
    }

    public static int getWebPort() {
        return webPort;
    }

    @Override
    public void onDisable() {
        // Withdraw the API first, so integrations stop seeing a live handle
        // bound to a server that is going away.
        DashApiProvider.uninstall();
        getServer().getServicesManager().unregisterAll(this);
        if (scheduledTaskManager != null) {
            scheduledTaskManager.close();
        }
        if (auditDataManager != null) {
            auditDataManager.close();
        }
        if (guardianDataManager != null) {
            guardianDataManager.close();
        }
        if (backupManager != null) {
            backupManager.stop();
        }
        if (playerDataManager != null) {
            playerDataManager.close();
        }
        if (this.adventure != null) {
            this.adventure.close();
            this.adventure = null;
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
        stopUpdaterSchedule();
        // Apply any staged update: delete the running jar and rename the staged file to Dash.jar
        if (githubUpdater != null) {
            githubUpdater.applyUpdateOnShutdown();
        }
        if (discordWebhookManager != null) {
            discordWebhookManager.dispatchEmbed(
                    DiscordWebhookManager.EVENT_SERVER_START_STOP,
                    "Server Stopped", "The Minecraft server has been shut down.", 0xF43F5E);
        }
        getLogger().info("Dash Admin stopped");
    }

    private void startUpdaterSchedule() {
        stopUpdaterSchedule();
        if (githubUpdater == null || !githubUpdater.isEnabled()) {
            return;
        }

        final long periodTicks = 2L * 60L * 60L * 20L; // every 2 hours
        final long initialDelayTicks = computeDelayToNextTwoHourBoundaryTicks();
        updaterScanTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::runUpdaterScan,
                initialDelayTicks, periodTicks);

        ZonedDateTime nextRun = ZonedDateTime.now().plusSeconds(Math.max(1L, initialDelayTicks / 20L));
        getLogger().info("[Updater] Scheduled update scan every 2 hours. Next scan at "
                + nextRun.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")) + ".");
    }

    private void stopUpdaterSchedule() {
        if (updaterScanTask != null) {
            updaterScanTask.cancel();
            updaterScanTask = null;
        }
    }

    private void runUpdaterScan() {
        try {
            if (githubUpdater == null || !githubUpdater.isEnabled()) {
                return;
            }
            if (!githubUpdater.isUpdateAvailable()) {
                return;
            }
            if (githubUpdater.isUpdatePrepared()) {
                // Already staged — no need to re-download
                return;
            }
            getLogger().info("[Dash] New version available. Auto-downloading update in the background...");
            boolean ok = githubUpdater.downloadUpdate();
            if (!ok) {
                getLogger().warning("[Dash] Auto-download failed. You can retry from the admin panel under Updates.");
            }
        } catch (Exception ex) {
            getLogger().warning("[Updater] Scheduled update scan failed: " + ex.getMessage());
        }
    }

    private long computeDelayToNextTwoHourBoundaryTicks() {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = now.withMinute(0).withSecond(0).withNano(0);
        if ((next.getHour() % 2) != 0) {
            next = next.plusHours(1);
        }
        if (!next.isAfter(now)) {
            next = next.plusHours(2);
        }

        long secondsUntilNext = Math.max(1L, Duration.between(now, next).getSeconds());
        return Math.max(20L, secondsUntilNext * 20L);
    }
}
