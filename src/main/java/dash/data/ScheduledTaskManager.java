package dash.data;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages user-defined recurring scheduled tasks stored in SQLite.
 * Each task has: type (broadcast / command / save-all), interval (minutes), payload, enabled flag.
 * Tasks execute on the Bukkit main thread via runTaskTimer.
 */
public class ScheduledTaskManager {

    private final JavaPlugin plugin;
    private Connection connection;
    private final Map<Integer, BukkitTask> runningTasks = new ConcurrentHashMap<>();

    public static final String TYPE_BROADCAST = "broadcast";
    public static final String TYPE_COMMAND = "command";

    public ScheduledTaskManager(JavaPlugin plugin) {
        this.plugin = plugin;
        initDatabase();
        startAllEnabled();
    }

    private void initDatabase() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) dataFolder.mkdirs();

            File dbFile = new File(dataFolder, "scheduled_tasks.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS scheduled_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        task_type TEXT NOT NULL,
                        interval_minutes INTEGER NOT NULL,
                        payload TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        created_at INTEGER NOT NULL
                    )
                """);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("ScheduledTask DB init failed: " + e.getMessage());
        }
    }

    /** Start all enabled tasks from the database. */
    public void startAllEnabled() {
        for (ScheduledTask task : getAllTasks()) {
            if (task.enabled()) {
                scheduleTask(task);
            }
        }
    }

    /** Stop all running timers. */
    public void stopAll() {
        for (BukkitTask task : runningTasks.values()) {
            task.cancel();
        }
        runningTasks.clear();
    }

    /**
     * Add a new scheduled task and start it if enabled.
     */
    public int addTask(String taskType, int intervalMinutes, String payload, boolean enabled) {
        String normalizedType = taskType == null ? "" : taskType.trim().toLowerCase();
        String normalizedPayload = payload == null ? "" : payload.trim();
        if ((!TYPE_BROADCAST.equals(normalizedType) && !TYPE_COMMAND.equals(normalizedType))
                || intervalMinutes < 1 || intervalMinutes > 10080
                || normalizedPayload.isBlank()) {
            return -1;
        }

        int id = -1;
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO scheduled_tasks (task_type, interval_minutes, payload, enabled, created_at) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, normalizedType);
            stmt.setInt(2, intervalMinutes);
            stmt.setString(3, normalizedPayload);
            stmt.setInt(4, enabled ? 1 : 0);
            stmt.setLong(5, System.currentTimeMillis());
            stmt.executeUpdate();
            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next()) {
                id = rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to add scheduled task: " + e.getMessage());
            return -1;
        }

        if (id > 0 && enabled) {
            ScheduledTask task = getTask(id);
            if (task != null)
                scheduleTask(task);
        }
        return id;
    }

    /**
     * Toggle enabled/disabled for a task.
     */
    public void setEnabled(int taskId, boolean enabled) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE scheduled_tasks SET enabled = ? WHERE id = ?")) {
            stmt.setInt(1, enabled ? 1 : 0);
            stmt.setInt(2, taskId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to toggle task: " + e.getMessage());
        }

        if (enabled) {
            ScheduledTask task = getTask(taskId);
            if (task != null) scheduleTask(task);
        } else {
            cancelTask(taskId);
        }
    }

    /**
     * Delete a scheduled task permanently.
     */
    public void deleteTask(int taskId) {
        cancelTask(taskId);
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM scheduled_tasks WHERE id = ?")) {
            stmt.setInt(1, taskId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to delete task: " + e.getMessage());
        }
    }

    /** Cancel a running timer for a task. */
    private void cancelTask(int taskId) {
        BukkitTask bt = runningTasks.remove(taskId);
        if (bt != null) bt.cancel();
    }

    /** Schedule a repeating timer on the main thread. */
    private void scheduleTask(ScheduledTask task) {
        cancelTask(task.id()); // avoid duplicates
        long intervalTicks = task.intervalMinutes() * 60L * 20L; // minutes -> ticks
        BukkitTask bt = Bukkit.getScheduler().runTaskTimer(plugin, () -> executeTask(task), intervalTicks, intervalTicks);
        runningTasks.put(task.id(), bt);
    }

    /** Execute the task action on the main thread. */
    private void executeTask(ScheduledTask task) {
        switch (task.taskType()) {
            case TYPE_BROADCAST -> {
                Bukkit.broadcast(net.kyori.adventure.text.Component.text(task.payload()));
                dash.WebActionLogger.log("SCHEDULED_BROADCAST", task.payload());
            }
            case TYPE_COMMAND -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), task.payload());
                dash.WebActionLogger.log("SCHEDULED_COMMAND", task.payload());
            }
            default -> plugin.getLogger().warning("Unknown scheduled task type: " + task.taskType());
        }
    }

    /** Retrieve a single task by id. */
    public ScheduledTask getTask(int id) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT * FROM scheduled_tasks WHERE id = ?")) {
            stmt.setInt(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return fromRow(rs);
            }
        } catch (SQLException ignored) {}
        return null;
    }

    /** Retrieve all tasks ordered by creation time. */
    public List<ScheduledTask> getAllTasks() {
        List<ScheduledTask> tasks = new ArrayList<>();
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT * FROM scheduled_tasks ORDER BY created_at DESC");
            while (rs.next()) {
                tasks.add(fromRow(rs));
            }
        } catch (SQLException ignored) {}
        return tasks;
    }

    private ScheduledTask fromRow(ResultSet rs) throws SQLException {
        return new ScheduledTask(
                rs.getInt("id"),
                rs.getString("task_type"),
                rs.getInt("interval_minutes"),
                rs.getString("payload"),
                rs.getInt("enabled") == 1,
                rs.getLong("created_at")
        );
    }

    public boolean isRunning(int taskId) {
        return runningTasks.containsKey(taskId);
    }

    public void close() {
        stopAll();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {}
    }

    public record ScheduledTask(int id, String taskType, int intervalMinutes, String payload, boolean enabled, long createdAt) {
        public String getFormattedType() {
            return switch (taskType) {
                case TYPE_BROADCAST -> "Broadcast";
                case TYPE_COMMAND -> "Console Command";
                default -> taskType;
            };
        }

        public String getFormattedInterval() {
            if (intervalMinutes < 60) return intervalMinutes + " min";
            int h = intervalMinutes / 60;
            int m = intervalMinutes % 60;
            return m == 0 ? h + "h" : h + "h " + m + "m";
        }
    }
}

