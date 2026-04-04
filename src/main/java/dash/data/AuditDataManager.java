package dash.data;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed storage for audit/action log entries produced by {@link dash.WebActionLogger}.
 * Stores: id, timestamp, username, action, details, ip_address.
 */
public class AuditDataManager {

    private final JavaPlugin plugin;
    private Connection connection;

    public AuditDataManager(JavaPlugin plugin) {
        this.plugin = plugin;
        initDatabase();
    }

    private void initDatabase() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) dataFolder.mkdirs();

            File dbFile = new File(dataFolder, "audit.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        timestamp INTEGER NOT NULL,
                        username TEXT NOT NULL,
                        action TEXT NOT NULL,
                        details TEXT,
                        ip_address TEXT
                    )
                """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_log(timestamp DESC)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_log(action)");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Audit DB init failed: " + e.getMessage());
        }
    }

    /**
     * Insert a new audit entry. Called asynchronously from WebActionLogger.
     */
    public void insertLog(String username, String action, String details, String ipAddress) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO audit_log (timestamp, username, action, details, ip_address) VALUES (?, ?, ?, ?, ?)")) {
                stmt.setLong(1, System.currentTimeMillis());
                stmt.setString(2, username != null ? username : "SYSTEM");
                stmt.setString(3, action);
                stmt.setString(4, details);
                stmt.setString(5, ipAddress != null ? ipAddress : "");
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to insert audit log: " + e.getMessage());
            }
        });
    }

    /**
     * Retrieve the most recent audit entries, up to limit.
     */
    public List<AuditEntry> getRecentLogs(int limit) {
        List<AuditEntry> entries = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT id, timestamp, username, action, details, ip_address FROM audit_log ORDER BY timestamp DESC LIMIT ?")) {
            stmt.setInt(1, limit);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                entries.add(new AuditEntry(
                        rs.getInt("id"),
                        rs.getLong("timestamp"),
                        rs.getString("username"),
                        rs.getString("action"),
                        rs.getString("details"),
                        rs.getString("ip_address")
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to query audit log: " + e.getMessage());
        }
        return entries;
    }

    /**
     * Search audit entries by action or username.
     */
    public List<AuditEntry> searchLogs(String query, int limit) {
        List<AuditEntry> entries = new ArrayList<>();
        String sql = "SELECT id, timestamp, username, action, details, ip_address FROM audit_log " +
                "WHERE username LIKE ? OR action LIKE ? OR details LIKE ? ORDER BY timestamp DESC LIMIT ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            String pattern = "%" + query + "%";
            stmt.setString(1, pattern);
            stmt.setString(2, pattern);
            stmt.setString(3, pattern);
            stmt.setInt(4, limit);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                entries.add(new AuditEntry(
                        rs.getInt("id"),
                        rs.getLong("timestamp"),
                        rs.getString("username"),
                        rs.getString("action"),
                        rs.getString("details"),
                        rs.getString("ip_address")
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to search audit log: " + e.getMessage());
        }
        return entries;
    }

    /**
     * Count total entries (useful for pagination info).
     */
    public int countLogs() {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM audit_log");
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {}
    }

    public record AuditEntry(int id, long timestamp, String username, String action, String details, String ipAddress) {
        public String getFormattedTime() {
            java.time.Instant instant = java.time.Instant.ofEpochMilli(timestamp);
            java.time.LocalDateTime ldt = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
            return ldt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }
}

