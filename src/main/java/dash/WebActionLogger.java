package dash;

import org.bukkit.Bukkit;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

public class WebActionLogger {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static Logger logger;

    public static void init(Logger pluginLogger) {
        logger = pluginLogger;
    }

    public static void log(String action, String details) {
        if (logger == null) {
            logger = Bukkit.getLogger();
        }
        String time = LocalDateTime.now().format(TIME_FORMAT);
        logger.info("[WebAdmin] [" + time + "] " + action + ": " + details);
    }

    public static void logLogin(String username, String ip) {
        log("LOGIN", "User '" + username + "' logged in from " + ip);
    }

    public static void logLogout(String ip) {
        log("LOGOUT", "Session ended from " + ip);
    }

    public static void logCommand(String command, String ip) {
        log("COMMAND", "'" + command + "' executed from " + ip);
    }

    public static void logPlayerAction(String action, String targetPlayer, String ip) {
        log(action.toUpperCase(), "Target: " + targetPlayer + " from " + ip);
    }

    public static void logFileEdit(String filePath, String ip) {
        log("FILE_EDIT", "Modified: " + filePath + " from " + ip);
    }

    public static void logUpload(String type, String fileName, String ip) {
        log("UPLOAD_" + type.toUpperCase(), "File: " + fileName + " from " + ip);
    }

    public static void logBackup(String action, String details) {
        log("BACKUP_" + action.toUpperCase(), details);
    }

    public static void logPluginAction(String action, String pluginName, String ip) {
        log("PLUGIN_" + action.toUpperCase(), "Plugin: " + pluginName + " from " + ip);
    }

    public static void logSettingChange(String setting, String value, String ip) {
        log("SETTING_CHANGE", setting + " = " + value + " from " + ip);
    }

    public static void logRegistration(String username, String playerName) {
        log("REGISTRATION", "User '" + username + "' registered (linked to player: " + playerName + ")");
    }
}
