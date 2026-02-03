package dash;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import dash.data.BackupManager;
import dash.data.PlayerDataManager;

public class Dash extends JavaPlugin {

    private BukkitTask killTask;
    private AdminWebServer webServer;
    private static StatsCollector statsCollector;
    private static PlayerDataManager playerDataManager;
    private static BackupManager backupManager;
    private static RegistrationManager registrationManager;
    private static int webPort;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        webPort = getConfig().getInt("web-port", 6745);

        playerDataManager = new PlayerDataManager(this);
        backupManager = new BackupManager(this);
        registrationManager = new RegistrationManager();

        statsCollector = new StatsCollector(this);
        statsCollector.start();

        webServer = new AdminWebServer(this, webPort);
        webServer.start();

        getServer().getPluginManager().registerEvents(new FreezeManager(), this);

        getCommand("dash").setExecutor(new DashCommand(this));

        getLogger().info("Dash Admin started on port " + webPort);
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

    public static int getWebPort() {
        return webPort;
    }

    @Override
    public void onDisable() {
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
        getLogger().info("Dash Admin stopped");
    }
}
