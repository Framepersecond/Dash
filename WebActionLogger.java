package dash;

import dash.data.AuditDataManager;
import org.bukkit.Bukkit;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

public class WebActionLogger {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static Logger logger;
    private static AuditDataManager auditManager;
    private static DiscordWebhookManager discordWebhookManager;

    public static void init(Logger pluginLogger) {
        logger = pluginLogger;
    }

    public static void setAuditManager(AuditDataManager manager) {
        auditManager = manager;
    }

    public static void setDiscordWebhookManager(DiscordWebhookManager manager) {
        discordWebhookManager = manager;
    }

    public static void log(String action, String details) {
        log(action, details, null, null);
    }

    public static void log(String action, String details, String username, String ip) {
        if (logger == null) {
            logger = Bukkit.getLogger();
        }
        String time = LocalDateTime.now().format(TIME_FORMAT);
        logger.info("[WebAdmin] [" + time + "] " + action + ": " + details);

        if (auditManager != null) {
            auditManager.insertLog(
                    username != null ? username : extractUser(details),
                    action,
                    details,
                    ip != null ? ip : extractIp(details));
        }

        if (discordWebhookManager != null) {
            discordWebhookManager.dispatch(DiscordWebhookManager.EVENT_AUDIT,
                    "**[" + action + "]** " + details);
        }
    }

    public static void logLogin(String username, String ip) {
        log("LOGIN", "User '" + username + "' logged in from " + ip, username, ip);
    }

    public static void logLogout(String ip) {
        log("LOGOUT", "Session ended from " + ip, null, ip);
    }

    public static void logCommand(String command, String ip) {
        log("COMMAND", "'" + command + "' executed from " + ip, null, ip);
    }

    public static void logPlayerAction(String action, String targetPlayer, String ip) {
        log(action.toUpperCase(), "Target: " + targetPlayer + " from " + ip, null, ip);
    }

    public static void logFileEdit(String filePath, String ip) {
        log("FILE_EDIT", "Modified: " + filePath + " from " + ip, null, ip);
    }

    public static void logUpload(String type, String fileName, String ip) {
        log("UPLOAD_" + type.toUpperCase(), "File: " + fileName + " from " + ip, null, ip);
    }

    public static void logBackup(String action, String details) {
        log("BACKUP_" + action.toUpperCase(), details);
    }

    public static void logPluginAction(String action, String pluginName, String ip) {
        log("PLUGIN_" + action.toUpperCase(), "Plugin: " + pluginName + " from " + ip, null, ip);
    }

    public static void logSettingChange(String setting, String value, String ip) {
        log("SETTING_CHANGE", setting + " = " + value + " from " + ip, null, ip);
    }

    public static void logRegistration(String username, String playerName) {
        log("REGISTRATION", "User '" + username + "' registered (linked to player: " + playerName + ")", username, null);
    }

    private static String extractUser(String details) {
        if (details == null) return "SYSTEM";
        int idx = details.indexOf("User '");
        if (idx >= 0) {
            int end = details.indexOf("'", idx + 6);
            if (end > idx) return details.substring(idx + 6, end);
        }
        idx = details.indexOf("user=");
        if (idx >= 0) {
            int end = details.indexOf(" ", idx + 5);
            return end > idx ? details.substring(idx + 5, end) : details.substring(idx + 5);
        }
        return "SYSTEM";
    }

    private static String extractIp(String details) {
        if (details == null) return "";
        int idx = details.lastIndexOf(" from ");
        if (idx >= 0) {
            return details.substring(idx + 6).trim();
        }
        int ipIdx = details.indexOf("ip=");
        if (ipIdx >= 0) {
            int end = details.indexOf(" ", ipIdx + 3);
            return end > ipIdx ? details.substring(ipIdx + 3, end) : details.substring(ipIdx + 3);
        }
        return "";
    }
}
