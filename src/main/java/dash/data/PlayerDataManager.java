package dash.data;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager implements Listener {

    private final JavaPlugin plugin;
    private Connection connection;
    private final ConcurrentHashMap<UUID, Long> sessionStartTimes = new ConcurrentHashMap<>();

    public PlayerDataManager(JavaPlugin plugin) {
        this.plugin = plugin;
        initDatabase();
        Bukkit.getPluginManager().registerEvents(this, plugin);

        for (Player p : Bukkit.getOnlinePlayers()) {
            sessionStartTimes.put(p.getUniqueId(), System.currentTimeMillis());
        }
    }

    private void initDatabase() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists())
                dataFolder.mkdirs();

            File dbFile = new File(dataFolder, "playerdata.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                            CREATE TABLE IF NOT EXISTS players (
                                uuid TEXT PRIMARY KEY,
                                name TEXT NOT NULL,
                                first_join INTEGER NOT NULL,
                                last_join INTEGER NOT NULL,
                                total_playtime INTEGER DEFAULT 0
                            )
                        """);

                stmt.execute("""
                            CREATE TABLE IF NOT EXISTS sessions (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                uuid TEXT NOT NULL,
                                join_time INTEGER NOT NULL,
                                leave_time INTEGER,
                                ip_address TEXT,
                                FOREIGN KEY (uuid) REFERENCES players(uuid)
                            )
                        """);

                stmt.execute("""
                            CREATE TABLE IF NOT EXISTS notes (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                uuid TEXT NOT NULL,
                                admin_name TEXT NOT NULL,
                                note TEXT NOT NULL,
                                created_at INTEGER NOT NULL,
                                FOREIGN KEY (uuid) REFERENCES players(uuid)
                            )
                        """);

                stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_uuid ON sessions(uuid)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_notes_uuid ON notes(uuid)");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("SQLite init failed: " + e.getMessage());
        }
    }

    public void close() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            recordPlayerLeave(p);
        }

        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        long now = System.currentTimeMillis();
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "Unknown";

        sessionStartTimes.put(uuid, now);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                try (PreparedStatement stmt = connection.prepareStatement("""
                            INSERT INTO players (uuid, name, first_join, last_join, total_playtime)
                            VALUES (?, ?, ?, ?, 0)
                            ON CONFLICT(uuid) DO UPDATE SET name = ?, last_join = ?
                        """)) {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, name);
                    stmt.setLong(3, now);
                    stmt.setLong(4, now);
                    stmt.setString(5, name);
                    stmt.setLong(6, now);
                    stmt.executeUpdate();
                }

                try (PreparedStatement stmt = connection.prepareStatement(
                        "INSERT INTO sessions (uuid, join_time, ip_address) VALUES (?, ?, ?)")) {
                    stmt.setString(1, uuid.toString());
                    stmt.setLong(2, now);
                    stmt.setString(3, ip);
                    stmt.executeUpdate();
                }
            } catch (SQLException ignored) {
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        recordPlayerLeave(event.getPlayer());
    }

    private void recordPlayerLeave(Player player) {
        UUID uuid = player.getUniqueId();
        Long joinTime = sessionStartTimes.remove(uuid);
        if (joinTime == null)
            return;

        long now = System.currentTimeMillis();
        long sessionDuration = now - joinTime;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                try (PreparedStatement stmt = connection.prepareStatement(
                        "UPDATE sessions SET leave_time = ? WHERE id = (SELECT id FROM sessions WHERE uuid = ? AND leave_time IS NULL ORDER BY join_time DESC LIMIT 1)")) {
                    stmt.setLong(1, now);
                    stmt.setString(2, uuid.toString());
                    stmt.executeUpdate();
                }

                try (PreparedStatement stmt = connection.prepareStatement(
                        "UPDATE players SET total_playtime = total_playtime + ? WHERE uuid = ?")) {
                    stmt.setLong(1, sessionDuration);
                    stmt.setString(2, uuid.toString());
                    stmt.executeUpdate();
                }
            } catch (SQLException ignored) {
            }
        });
    }

    public PlayerInfo getPlayerInfo(String uuidOrName) {
        try {
            String sql = "SELECT * FROM players WHERE uuid = ? OR name = ? COLLATE NOCASE";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, uuidOrName);
                stmt.setString(2, uuidOrName);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return new PlayerInfo(
                            rs.getString("uuid"),
                            rs.getString("name"),
                            rs.getLong("first_join"),
                            rs.getLong("last_join"),
                            rs.getLong("total_playtime"));
                }
            }
        } catch (SQLException ignored) {
        }
        return null;
    }

    public List<SessionInfo> getPlayerSessions(String uuid, int limit) {
        List<SessionInfo> sessions = new ArrayList<>();
        try {
            String sql = "SELECT * FROM sessions WHERE uuid = ? ORDER BY join_time DESC LIMIT ?";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, uuid);
                stmt.setInt(2, limit);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    sessions.add(new SessionInfo(
                            rs.getLong("join_time"),
                            rs.getLong("leave_time"),
                            rs.getString("ip_address")));
                }
            }
        } catch (SQLException ignored) {
        }
        return sessions;
    }

    public List<NoteInfo> getPlayerNotes(String uuid) {
        List<NoteInfo> notes = new ArrayList<>();
        try {
            String sql = "SELECT * FROM notes WHERE uuid = ? ORDER BY created_at DESC";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, uuid);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    notes.add(new NoteInfo(
                            rs.getInt("id"),
                            rs.getString("admin_name"),
                            rs.getString("note"),
                            rs.getLong("created_at")));
                }
            }
        } catch (SQLException ignored) {
        }
        return notes;
    }

    public void addNote(String uuid, String adminName, String note) {
        try {
            String sql = "INSERT INTO notes (uuid, admin_name, note, created_at) VALUES (?, ?, ?, ?)";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, uuid);
                stmt.setString(2, adminName);
                stmt.setString(3, note);
                stmt.setLong(4, System.currentTimeMillis());
                stmt.executeUpdate();
            }
        } catch (SQLException ignored) {
        }
    }

    public void deleteNote(int noteId) {
        try {
            try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM notes WHERE id = ?")) {
                stmt.setInt(1, noteId);
                stmt.executeUpdate();
            }
        } catch (SQLException ignored) {
        }
    }

    public List<PlayerInfo> getAllPlayers(int limit, int offset) {
        List<PlayerInfo> players = new ArrayList<>();
        try {
            String sql = "SELECT * FROM players ORDER BY last_join DESC LIMIT ? OFFSET ?";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setInt(1, limit);
                stmt.setInt(2, offset);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    players.add(new PlayerInfo(
                            rs.getString("uuid"),
                            rs.getString("name"),
                            rs.getLong("first_join"),
                            rs.getLong("last_join"),
                            rs.getLong("total_playtime")));
                }
            }
        } catch (SQLException ignored) {
        }
        return players;
    }

    public record PlayerInfo(String uuid, String name, long firstJoin, long lastJoin, long totalPlaytime) {
        public String getFormattedPlaytime() {
            long hours = totalPlaytime / 3600000;
            long minutes = (totalPlaytime % 3600000) / 60000;
            return hours + "h " + minutes + "m";
        }
    }

    public record SessionInfo(long joinTime, long leaveTime, String ipAddress) {
        public String getDuration() {
            if (leaveTime == 0)
                return "Online";
            long duration = leaveTime - joinTime;
            long hours = duration / 3600000;
            long minutes = (duration % 3600000) / 60000;
            return hours + "h " + minutes + "m";
        }
    }

    public record NoteInfo(int id, String adminName, String note, long createdAt) {
    }
}
