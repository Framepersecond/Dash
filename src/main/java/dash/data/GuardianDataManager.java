package dash.data;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class GuardianDataManager implements Closeable {
    public static final int ACTION_BREAK = 0;
    public static final int ACTION_PLACE = 1;
    public static final int CONTAINER_ACTION_REMOVE = 0;
    public static final int CONTAINER_ACTION_ADD = 1;

    private final Path dataFolder;
    private final Executor executor;
    private final Consumer<String> warnLogger;
    private Connection connection;

    public GuardianDataManager(Path dataFolder, Executor executor, Consumer<String> warnLogger) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.executor = executor == null ? Runnable::run : executor;
        this.warnLogger = warnLogger == null ? ignored -> { } : warnLogger;
        initDatabase();
    }

    private void initDatabase() {
        try {
            Files.createDirectories(dataFolder);
            connection = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.resolve("guardian.db").toAbsolutePath());
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS guardian_block_log (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            source TEXT NOT NULL DEFAULT 'dash',
                            external_id TEXT,
                            timestamp INTEGER NOT NULL,
                            player_uuid TEXT,
                            player_name TEXT NOT NULL,
                            action INTEGER NOT NULL,
                            world TEXT NOT NULL,
                            x INTEGER NOT NULL,
                            y INTEGER NOT NULL,
                            z INTEGER NOT NULL,
                            block_type TEXT NOT NULL,
                            old_block_type TEXT,
                            created_at INTEGER NOT NULL DEFAULT (strftime('%s','now'))
                        )
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS guardian_container_log (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            source TEXT NOT NULL DEFAULT 'dash',
                            external_id TEXT,
                            timestamp INTEGER NOT NULL,
                            player_uuid TEXT,
                            player_name TEXT NOT NULL,
                            action INTEGER NOT NULL,
                            world TEXT NOT NULL,
                            x INTEGER NOT NULL,
                            y INTEGER NOT NULL,
                            z INTEGER NOT NULL,
                            item_material TEXT NOT NULL,
                            item_amount INTEGER NOT NULL,
                            created_at INTEGER NOT NULL DEFAULT (strftime('%s','now'))
                        )
                        """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_guardian_block_time ON guardian_block_log(timestamp DESC)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_guardian_block_player ON guardian_block_log(player_name)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_guardian_block_location ON guardian_block_log(world, x, y, z)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_guardian_container_time ON guardian_container_log(timestamp DESC)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_guardian_container_player ON guardian_container_log(player_name)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_guardian_container_location ON guardian_container_log(world, x, y, z)");
                stmt.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS idx_guardian_block_dedupe
                        ON guardian_block_log(source, timestamp, player_name, action, world, x, y, z, block_type, IFNULL(old_block_type, ''))
                        """);
                stmt.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS idx_guardian_container_dedupe
                        ON guardian_container_log(source, timestamp, player_name, action, world, x, y, z, item_material, item_amount)
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS guardian_case (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            title TEXT NOT NULL,
                            status TEXT NOT NULL DEFAULT 'OPEN',
                            priority TEXT NOT NULL DEFAULT 'NORMAL',
                            player_name TEXT,
                            world TEXT,
                            x INTEGER,
                            y INTEGER,
                            z INTEGER,
                            notes TEXT,
                            created_by TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            locked INTEGER NOT NULL DEFAULT 0
                        )
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS guardian_case_evidence (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            case_id INTEGER NOT NULL,
                            event_type TEXT NOT NULL,
                            event_id INTEGER NOT NULL,
                            label TEXT,
                            added_by TEXT,
                            created_at INTEGER NOT NULL
                        )
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS guardian_saved_filter (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            name TEXT NOT NULL UNIQUE,
                            query TEXT NOT NULL,
                            created_by TEXT,
                            created_at INTEGER NOT NULL
                        )
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS guardian_player_note (
                            player_name TEXT PRIMARY KEY,
                            severity TEXT NOT NULL DEFAULT 'WATCH',
                            notes TEXT,
                            created_by TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS guardian_protected_region (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            name TEXT NOT NULL UNIQUE,
                            world TEXT NOT NULL,
                            min_x INTEGER NOT NULL,
                            min_y INTEGER NOT NULL,
                            min_z INTEGER NOT NULL,
                            max_x INTEGER NOT NULL,
                            max_y INTEGER NOT NULL,
                            max_z INTEGER NOT NULL,
                            severity TEXT NOT NULL DEFAULT 'WATCH',
                            created_by TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS guardian_alert_rule (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            name TEXT NOT NULL UNIQUE,
                            enabled INTEGER NOT NULL DEFAULT 1,
                            window_seconds INTEGER NOT NULL DEFAULT 600,
                            min_actions INTEGER NOT NULL DEFAULT 25,
                            action TEXT,
                            material TEXT,
                            auto_case INTEGER NOT NULL DEFAULT 0,
                            priority TEXT NOT NULL DEFAULT 'HIGH',
                            created_by TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """);
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS guardian_retention_policy (
                            id INTEGER PRIMARY KEY CHECK (id = 1),
                            log_days INTEGER NOT NULL DEFAULT 90,
                            keep_cases INTEGER NOT NULL DEFAULT 1,
                            updated_by TEXT,
                            updated_at INTEGER NOT NULL
                        )
                        """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_guardian_case_status ON guardian_case(status, updated_at DESC)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_guardian_case_player ON guardian_case(player_name)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_guardian_case_evidence_case ON guardian_case_evidence(case_id)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_guardian_player_note_severity ON guardian_player_note(severity, updated_at DESC)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_guardian_region_world ON guardian_protected_region(world)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_guardian_alert_enabled ON guardian_alert_rule(enabled, updated_at DESC)");
                ensureGuardianDefaults(stmt);
            }
        } catch (Exception e) {
            warn("Guardian DB init failed: " + e.getMessage());
        }
    }

    private void ensureGuardianDefaults(Statement stmt) throws SQLException {
        long now = System.currentTimeMillis() / 1000L;
        stmt.execute("INSERT OR IGNORE INTO guardian_retention_policy(id, log_days, keep_cases, updated_by, updated_at) VALUES(1, 90, 1, 'system', " + now + ")");
        stmt.execute("INSERT OR IGNORE INTO guardian_alert_rule "
                + "(name, enabled, window_seconds, min_actions, action, material, auto_case, priority, created_by, created_at, updated_at) "
                + "VALUES ('Burst Block Breaks', 1, 600, 40, 'break', NULL, 1, 'HIGH', 'system', "
                + now + ", " + now + ")");
        stmt.execute("INSERT OR IGNORE INTO guardian_alert_rule "
                + "(name, enabled, window_seconds, min_actions, action, material, auto_case, priority, created_by, created_at, updated_at) "
                + "VALUES ('Container Sweep', 1, 600, 18, 'remove', NULL, 1, 'URGENT', 'system', "
                + now + ", " + now + ")");
    }

    public void logBlockActionAsync(String playerUuid, String playerName, int action, String world, int x, int y, int z,
            String blockType, String oldBlockType, String source) {
        long timestamp = System.currentTimeMillis() / 1000L;
        executor.execute(() -> insertBlockLog(timestamp, playerUuid, playerName, action, world, x, y, z, blockType,
                oldBlockType, normalizeSource(source), null));
    }

    public void logContainerActionAsync(String playerUuid, String playerName, int action, String world, int x, int y,
            int z, String itemMaterial, int amount, String source) {
        if (amount <= 0 || itemMaterial == null || itemMaterial.isBlank()) {
            return;
        }
        long timestamp = System.currentTimeMillis() / 1000L;
        executor.execute(() -> insertContainerLog(timestamp, playerUuid, playerName, action, world, x, y, z,
                itemMaterial, amount, normalizeSource(source), null));
    }

    public boolean importBlockLog(long timestamp, String playerUuid, String playerName, int action, String world, int x,
            int y, int z, String blockType, String oldBlockType, String source, String externalId) {
        return insertBlockLog(timestamp, playerUuid, playerName, action, world, x, y, z, blockType, oldBlockType,
                normalizeSource(source), externalId);
    }

    public boolean importContainerLog(long timestamp, String playerUuid, String playerName, int action, String world,
            int x, int y, int z, String itemMaterial, int amount, String source, String externalId) {
        return insertContainerLog(timestamp, playerUuid, playerName, action, world, x, y, z, itemMaterial, amount,
                normalizeSource(source), externalId);
    }

    private synchronized boolean insertBlockLog(long timestamp, String playerUuid, String playerName, int action,
            String world, int x, int y, int z, String blockType, String oldBlockType, String source, String externalId) {
        if (connection == null || blockType == null || blockType.isBlank()) {
            return false;
        }
        String sql = """
                INSERT OR IGNORE INTO guardian_block_log
                (source, external_id, timestamp, player_uuid, player_name, action, world, x, y, z, block_type, old_block_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, source);
            stmt.setString(2, blankToNull(externalId));
            stmt.setLong(3, timestamp);
            stmt.setString(4, blankToNull(playerUuid));
            stmt.setString(5, safeName(playerName));
            stmt.setInt(6, action == ACTION_PLACE ? ACTION_PLACE : ACTION_BREAK);
            stmt.setString(7, safeWorld(world));
            stmt.setInt(8, x);
            stmt.setInt(9, y);
            stmt.setInt(10, z);
            stmt.setString(11, normalizeMaterial(blockType));
            stmt.setString(12, blankToNull(normalizeMaterial(oldBlockType)));
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            warn("Failed to insert Guardian block log: " + e.getMessage());
            return false;
        }
    }

    private synchronized boolean insertContainerLog(long timestamp, String playerUuid, String playerName, int action,
            String world, int x, int y, int z, String itemMaterial, int amount, String source, String externalId) {
        if (connection == null || itemMaterial == null || itemMaterial.isBlank() || amount <= 0) {
            return false;
        }
        String sql = """
                INSERT OR IGNORE INTO guardian_container_log
                (source, external_id, timestamp, player_uuid, player_name, action, world, x, y, z, item_material, item_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, source);
            stmt.setString(2, blankToNull(externalId));
            stmt.setLong(3, timestamp);
            stmt.setString(4, blankToNull(playerUuid));
            stmt.setString(5, safeName(playerName));
            stmt.setInt(6, action == CONTAINER_ACTION_ADD ? CONTAINER_ACTION_ADD : CONTAINER_ACTION_REMOVE);
            stmt.setString(7, safeWorld(world));
            stmt.setInt(8, x);
            stmt.setInt(9, y);
            stmt.setInt(10, z);
            stmt.setString(11, normalizeMaterial(itemMaterial));
            stmt.setInt(12, amount);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            warn("Failed to insert Guardian container log: " + e.getMessage());
            return false;
        }
    }

    public synchronized List<BlockLogEntry> searchBlockLogs(String playerName, String world, Long fromTime, Long toTime,
            Integer action, int page, int limit) {
        return searchBlockLogsAdvanced(playerName, world, fromTime, toTime, action, null, null, null, null,
                List.of(), List.of(), page, limit, false);
    }

    public synchronized List<BlockLogEntry> searchBlockLogsAdvanced(String playerName, String world, Long fromTime,
            Long toTime, Integer action, Integer x, Integer y, Integer z, Integer radius, List<String> include,
            List<String> exclude, int page, int limit, boolean ascending) {
        List<BlockLogEntry> results = new ArrayList<>();
        QueryParts query = buildBlockQuery(playerName, world, fromTime, toTime, action);
        addLocationFilter(query, x, y, z, radius);
        addMaterialFilter(query, "block_type", include, exclude);
        int safeLimit = clampLimit(limit);
        query.sql.append(ascending ? " ORDER BY timestamp ASC, id ASC LIMIT ? OFFSET ?"
                : " ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?");
        query.params.add(safeLimit);
        query.params.add(Math.max(0, page - 1) * safeLimit);
        try (PreparedStatement stmt = prepare(query)) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new BlockLogEntry(
                            rs.getLong("id"),
                            rs.getString("source"),
                            rs.getLong("timestamp"),
                            rs.getString("player_uuid"),
                            rs.getString("player_name"),
                            rs.getInt("action"),
                            rs.getString("world"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("block_type"),
                            rs.getString("old_block_type")));
                }
            }
        } catch (SQLException e) {
            warn("Failed to query Guardian block logs: " + e.getMessage());
        }
        return results;
    }

    public synchronized List<ContainerLogEntry> searchContainerLogs(String playerName, String world, Long fromTime,
            Long toTime, Integer action, int page, int limit) {
        return searchContainerLogsAdvanced(playerName, world, fromTime, toTime, action, null, null, null, null,
                List.of(), List.of(), page, limit, false);
    }

    public synchronized List<ContainerLogEntry> searchContainerLogsAdvanced(String playerName, String world,
            Long fromTime, Long toTime, Integer action, Integer x, Integer y, Integer z, Integer radius,
            List<String> include, List<String> exclude, int page, int limit, boolean ascending) {
        List<ContainerLogEntry> results = new ArrayList<>();
        QueryParts query = buildContainerQuery(playerName, world, fromTime, toTime, action);
        addLocationFilter(query, x, y, z, radius);
        addMaterialFilter(query, "item_material", include, exclude);
        int safeLimit = clampLimit(limit);
        query.sql.append(ascending ? " ORDER BY timestamp ASC, id ASC LIMIT ? OFFSET ?"
                : " ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?");
        query.params.add(safeLimit);
        query.params.add(Math.max(0, page - 1) * safeLimit);
        try (PreparedStatement stmt = prepare(query)) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new ContainerLogEntry(
                            rs.getLong("id"),
                            rs.getString("source"),
                            rs.getLong("timestamp"),
                            rs.getString("player_uuid"),
                            rs.getString("player_name"),
                            rs.getInt("action"),
                            rs.getString("world"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("item_material"),
                            rs.getInt("item_amount")));
                }
            }
        } catch (SQLException e) {
            warn("Failed to query Guardian container logs: " + e.getMessage());
        }
        return results;
    }

    public synchronized QueryCount countAdvanced(String playerName, String world, Long fromTime, Long toTime,
            String action, Integer x, Integer y, Integer z, Integer radius, List<String> include,
            List<String> exclude) {
        int blocks = countRows(buildBlockCountQuery(playerName, world, fromTime, toTime, parseBlockAction(action), x, y,
                z, radius, include, exclude));
        int containers = countRows(buildContainerCountQuery(playerName, world, fromTime, toTime,
                parseContainerAction(action), x, y, z, radius, include, exclude));
        return new QueryCount(blocks, containers);
    }

    public synchronized boolean hasBlockAction(String playerName, String world, int x, int y, int z, int action,
            long since, int offsetSeconds) {
        Long toTime = offsetSeconds > 0 ? (System.currentTimeMillis() / 1000L) - offsetSeconds : null;
        QueryParts query = buildBlockCountQuery(playerName, world, since, toTime, action, x, y, z, 0, List.of(),
                List.of());
        return countRows(query) > 0;
    }

    public synchronized PurgeResult purgeOlderThan(long cutoff, String world, List<String> include) {
        int blocks = purgeRows("guardian_block_log", "block_type", cutoff, world, include);
        int containers = purgeRows("guardian_container_log", "item_material", cutoff, world, include);
        return new PurgeResult(blocks, containers);
    }

    public GuardianStatus getStatus() {
        int[] counts = countLogsSince(0);
        long dbBytes = 0L;
        try {
            dbBytes = Files.size(dataFolder.resolve("guardian.db"));
        } catch (Exception ignored) {
        }
        return new GuardianStatus(connection != null, dataFolder.resolve("guardian.db").toAbsolutePath().toString(),
                dbBytes, counts[0], counts[1]);
    }

    public synchronized List<String> getDistinctWorlds() {
        List<String> worlds = new ArrayList<>();
        String sql = "SELECT DISTINCT world FROM guardian_block_log UNION SELECT DISTINCT world FROM guardian_container_log ORDER BY world";
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                worlds.add(rs.getString("world"));
            }
        } catch (SQLException e) {
            warn("Failed to query Guardian worlds: " + e.getMessage());
        }
        return worlds;
    }

    public synchronized ServerStats getServerStats() {
        ServerStats stats = new ServerStats();
        try (Statement stmt = connection.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT action, COUNT(*) count FROM guardian_block_log GROUP BY action")) {
                while (rs.next()) {
                    if (rs.getInt("action") == ACTION_BREAK) stats.totalBlocksBroken = rs.getInt("count");
                    if (rs.getInt("action") == ACTION_PLACE) stats.totalBlocksPlaced = rs.getInt("count");
                }
            }
            try (ResultSet rs = stmt.executeQuery("SELECT action, COUNT(*) count FROM guardian_container_log GROUP BY action")) {
                while (rs.next()) {
                    if (rs.getInt("action") == CONTAINER_ACTION_REMOVE) stats.totalItemsRemoved = rs.getInt("count");
                    if (rs.getInt("action") == CONTAINER_ACTION_ADD) stats.totalItemsAdded = rs.getInt("count");
                }
            }
            try (ResultSet rs = stmt.executeQuery("""
                    SELECT COUNT(DISTINCT player_name) count FROM (
                        SELECT player_name FROM guardian_block_log
                        UNION ALL
                        SELECT player_name FROM guardian_container_log
                    )
                    """)) {
                if (rs.next()) stats.uniquePlayers = rs.getInt("count");
            }
            try (ResultSet rs = stmt.executeQuery("""
                    SELECT player_name, COUNT(*) total_actions,
                           SUM(CASE WHEN action = 0 THEN 1 ELSE 0 END) blocks_broken,
                           SUM(CASE WHEN action = 1 THEN 1 ELSE 0 END) blocks_placed
                    FROM guardian_block_log
                    GROUP BY player_name
                    ORDER BY total_actions DESC
                    LIMIT 5
                    """)) {
                while (rs.next()) {
                    stats.topPlayers.add(new PlayerActivity(rs.getString("player_name"), rs.getInt("total_actions"),
                            rs.getInt("blocks_broken"), rs.getInt("blocks_placed")));
                }
            }
        } catch (SQLException e) {
            warn("Failed to query Guardian stats: " + e.getMessage());
        }
        return stats;
    }

    public synchronized List<TimelineEntry> getTimelineStats(long from, long to) {
        List<TimelineEntry> results = new ArrayList<>();
        String sql = """
                SELECT slots.slot,
                       COALESCE(blocks.count, 0) block_count,
                       COALESCE(containers.count, 0) container_count
                FROM (
                    SELECT DISTINCT strftime('%Y-%m-%d %H:00', datetime(timestamp, 'unixepoch')) slot
                    FROM guardian_block_log WHERE timestamp BETWEEN ? AND ?
                    UNION
                    SELECT DISTINCT strftime('%Y-%m-%d %H:00', datetime(timestamp, 'unixepoch')) slot
                    FROM guardian_container_log WHERE timestamp BETWEEN ? AND ?
                ) slots
                LEFT JOIN (
                    SELECT strftime('%Y-%m-%d %H:00', datetime(timestamp, 'unixepoch')) slot, COUNT(*) count
                    FROM guardian_block_log WHERE timestamp BETWEEN ? AND ? GROUP BY slot
                ) blocks ON slots.slot = blocks.slot
                LEFT JOIN (
                    SELECT strftime('%Y-%m-%d %H:00', datetime(timestamp, 'unixepoch')) slot, COUNT(*) count
                    FROM guardian_container_log WHERE timestamp BETWEEN ? AND ? GROUP BY slot
                ) containers ON slots.slot = containers.slot
                ORDER BY slots.slot ASC
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 1; i <= 8; i += 2) {
                stmt.setLong(i, from);
                stmt.setLong(i + 1, to);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new TimelineEntry(rs.getString("slot"), rs.getInt("block_count"),
                            rs.getInt("container_count")));
                }
            }
        } catch (SQLException e) {
            warn("Failed to query Guardian timeline: " + e.getMessage());
        }
        return results;
    }

    public synchronized List<UnifiedTimelineEntry> searchTimeline(String search, String playerName, String world,
            Long fromTime, Long toTime, int limit) {
        List<UnifiedTimelineEntry> results = new ArrayList<>();
        QueryParts block = new QueryParts("""
                SELECT 'block' event_type, id, source, timestamp, player_name,
                       CASE WHEN action = 1 THEN 'PLACE' ELSE 'BREAK' END action_label,
                       world, x, y, z, block_type target, 0 amount
                FROM guardian_block_log
                WHERE 1=1
                """);
        QueryParts container = new QueryParts("""
                SELECT 'container' event_type, id, source, timestamp, player_name,
                       CASE WHEN action = 1 THEN 'ADD' ELSE 'REMOVE' END action_label,
                       world, x, y, z, item_material target, item_amount amount
                FROM guardian_container_log
                WHERE 1=1
                """);
        addTimelineFilters(block, "block_type", search, playerName, world, fromTime, toTime);
        addTimelineFilters(container, "item_material", search, playerName, world, fromTime, toTime);

        String sql = block.sql + " UNION ALL " + container.sql
                + " ORDER BY timestamp DESC, id DESC LIMIT ?";
        List<Object> params = new ArrayList<>();
        params.addAll(block.params);
        params.addAll(container.params);
        params.add(clampLimit(limit));
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            bindParams(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new UnifiedTimelineEntry(
                            rs.getString("event_type"),
                            rs.getLong("id"),
                            rs.getString("source"),
                            rs.getLong("timestamp"),
                            rs.getString("player_name"),
                            rs.getString("action_label"),
                            rs.getString("world"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("target"),
                            rs.getInt("amount")));
                }
            }
        } catch (SQLException e) {
            warn("Failed to query Guardian timeline events: " + e.getMessage());
        }
        return results;
    }

    public synchronized CaseRecord createCase(String title, String priority, String playerName, String world,
            Integer x, Integer y, Integer z, String notes, String createdBy) {
        long now = System.currentTimeMillis() / 1000L;
        String sql = """
                INSERT INTO guardian_case
                (title, status, priority, player_name, world, x, y, z, notes, created_by, created_at, updated_at, locked)
                VALUES (?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, safeTitle(title));
            stmt.setString(2, normalizePriority(priority));
            stmt.setString(3, blankToNull(playerName));
            stmt.setString(4, blankToNull(world));
            setNullableInt(stmt, 5, x);
            setNullableInt(stmt, 6, y);
            setNullableInt(stmt, 7, z);
            stmt.setString(8, blankToNull(notes));
            stmt.setString(9, blankToNull(createdBy));
            stmt.setLong(10, now);
            stmt.setLong(11, now);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    return getCase(keys.getLong(1));
                }
            }
        } catch (SQLException e) {
            warn("Failed to create Guardian case: " + e.getMessage());
        }
        return null;
    }

    public synchronized CaseRecord getCase(long caseId) {
        String sql = """
                SELECT id, title, status, priority, player_name, world, x, y, z, notes, created_by,
                       created_at, updated_at, locked
                FROM guardian_case
                WHERE id = ?
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, caseId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return caseFromRow(rs);
                }
            }
        } catch (SQLException e) {
            warn("Failed to load Guardian case: " + e.getMessage());
        }
        return null;
    }

    public synchronized List<CaseRecord> listCases(String status, String playerName, int limit) {
        List<CaseRecord> results = new ArrayList<>();
        QueryParts query = new QueryParts("""
                SELECT id, title, status, priority, player_name, world, x, y, z, notes, created_by,
                       created_at, updated_at, locked
                FROM guardian_case
                WHERE 1=1
                """);
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            query.sql.append(" AND status = ?");
            query.params.add(normalizeStatus(status));
        }
        if (playerName != null && !playerName.isBlank()) {
            query.sql.append(" AND player_name LIKE ?");
            query.params.add("%" + playerName.trim() + "%");
        }
        query.sql.append(" ORDER BY CASE status WHEN 'OPEN' THEN 0 WHEN 'INVESTIGATING' THEN 1 ELSE 2 END, updated_at DESC LIMIT ?");
        query.params.add(clampLimit(limit));
        try (PreparedStatement stmt = prepare(query); ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                results.add(caseFromRow(rs));
            }
        } catch (SQLException e) {
            warn("Failed to list Guardian cases: " + e.getMessage());
        }
        return results;
    }

    public synchronized boolean updateCase(long caseId, String status, String priority, String notes, String updatedBy) {
        CaseRecord current = getCase(caseId);
        if (current == null) {
            return false;
        }
        String nextStatus = status == null || status.isBlank() ? current.status() : normalizeStatus(status);
        String nextPriority = priority == null || priority.isBlank() ? current.priority() : normalizePriority(priority);
        String nextNotes = notes == null ? current.notes() : notes.trim();
        String suffix = updatedBy == null || updatedBy.isBlank() ? "" : "\n\nUpdated by " + updatedBy.trim();
        if (!suffix.isBlank() && (current.notes() == null || !current.notes().endsWith(suffix))) {
            nextNotes = nextNotes + suffix;
        }
        String sql = """
                UPDATE guardian_case
                SET status = ?, priority = ?, notes = ?, updated_at = ?
                WHERE id = ?
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, nextStatus);
            stmt.setString(2, nextPriority);
            stmt.setString(3, blankToNull(nextNotes));
            stmt.setLong(4, System.currentTimeMillis() / 1000L);
            stmt.setLong(5, caseId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            warn("Failed to update Guardian case: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean addCaseEvidence(long caseId, String eventType, long eventId, String label,
            String addedBy) {
        if (getCase(caseId) == null) {
            return false;
        }
        String type = normalizeEvidenceType(eventType);
        String sql = """
                INSERT INTO guardian_case_evidence(case_id, event_type, event_id, label, added_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, caseId);
            stmt.setString(2, type);
            stmt.setLong(3, eventId);
            stmt.setString(4, blankToNull(label));
            stmt.setString(5, blankToNull(addedBy));
            stmt.setLong(6, System.currentTimeMillis() / 1000L);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            warn("Failed to add Guardian evidence: " + e.getMessage());
            return false;
        }
    }

    public synchronized List<EvidenceRecord> listCaseEvidence(long caseId) {
        List<EvidenceRecord> results = new ArrayList<>();
        String sql = """
                SELECT id, case_id, event_type, event_id, label, added_by, created_at
                FROM guardian_case_evidence
                WHERE case_id = ?
                ORDER BY created_at DESC, id DESC
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, caseId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new EvidenceRecord(
                            rs.getLong("id"),
                            rs.getLong("case_id"),
                            rs.getString("event_type"),
                            rs.getLong("event_id"),
                            rs.getString("label"),
                            rs.getString("added_by"),
                            rs.getLong("created_at")));
                }
            }
        } catch (SQLException e) {
            warn("Failed to list Guardian evidence: " + e.getMessage());
        }
        return results;
    }

    public synchronized List<SavedFilterRecord> listSavedFilters() {
        List<SavedFilterRecord> results = new ArrayList<>();
        String sql = """
                SELECT id, name, query, created_by, created_at
                FROM guardian_saved_filter
                ORDER BY name COLLATE NOCASE ASC
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql); ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                results.add(new SavedFilterRecord(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("query"),
                        rs.getString("created_by"),
                        rs.getLong("created_at")));
            }
        } catch (SQLException e) {
            warn("Failed to list Guardian filters: " + e.getMessage());
        }
        return results;
    }

    public synchronized boolean saveFilter(String name, String query, String createdBy) {
        if (name == null || name.isBlank() || query == null || query.isBlank()) {
            return false;
        }
        String sql = """
                INSERT INTO guardian_saved_filter(name, query, created_by, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET query = excluded.query, created_by = excluded.created_by,
                                                created_at = excluded.created_at
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, name.trim());
            stmt.setString(2, query.trim());
            stmt.setString(3, blankToNull(createdBy));
            stmt.setLong(4, System.currentTimeMillis() / 1000L);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            warn("Failed to save Guardian filter: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean deleteFilter(long id) {
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM guardian_saved_filter WHERE id = ?")) {
            stmt.setLong(1, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            warn("Failed to delete Guardian filter: " + e.getMessage());
            return false;
        }
    }

    public synchronized List<PlayerNoteRecord> listPlayerNotes(String search, String severity, int limit) {
        List<PlayerNoteRecord> results = new ArrayList<>();
        QueryParts query = new QueryParts("""
                SELECT player_name, severity, notes, created_by, created_at, updated_at
                FROM guardian_player_note
                WHERE 1=1
                """);
        if (search != null && !search.isBlank()) {
            String value = "%" + search.trim() + "%";
            query.sql.append(" AND (player_name LIKE ? OR notes LIKE ?)");
            query.params.add(value);
            query.params.add(value);
        }
        if (severity != null && !severity.isBlank() && !"ALL".equalsIgnoreCase(severity)) {
            query.sql.append(" AND severity = ?");
            query.params.add(normalizeNoteSeverity(severity));
        }
        query.sql.append(" ORDER BY CASE severity WHEN 'ALERT' THEN 0 WHEN 'WATCH' THEN 1 WHEN 'INFO' THEN 2 ELSE 3 END, updated_at DESC LIMIT ?");
        query.params.add(clampLimit(limit));
        try (PreparedStatement stmt = prepare(query); ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                results.add(playerNoteFromRow(rs));
            }
        } catch (SQLException e) {
            warn("Failed to list Guardian player notes: " + e.getMessage());
        }
        return results;
    }

    public synchronized PlayerNoteRecord upsertPlayerNote(String playerName, String severity, String notes,
            String actor) {
        String player = safeName(playerName);
        if (player.isBlank() || "SYSTEM".equals(player)) {
            return null;
        }
        long now = System.currentTimeMillis() / 1000L;
        String sql = """
                INSERT INTO guardian_player_note(player_name, severity, notes, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(player_name) DO UPDATE SET
                    severity = excluded.severity,
                    notes = excluded.notes,
                    created_by = excluded.created_by,
                    updated_at = excluded.updated_at
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, player);
            stmt.setString(2, normalizeNoteSeverity(severity));
            stmt.setString(3, blankToNull(notes == null ? null : notes.trim()));
            stmt.setString(4, blankToNull(actor));
            stmt.setLong(5, now);
            stmt.setLong(6, now);
            stmt.executeUpdate();
            return getPlayerNote(player);
        } catch (SQLException e) {
            warn("Failed to save Guardian player note: " + e.getMessage());
            return null;
        }
    }

    public synchronized PlayerNoteRecord getPlayerNote(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        String sql = """
                SELECT player_name, severity, notes, created_by, created_at, updated_at
                FROM guardian_player_note
                WHERE player_name = ?
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, playerName.trim());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return playerNoteFromRow(rs);
                }
            }
        } catch (SQLException e) {
            warn("Failed to load Guardian player note: " + e.getMessage());
        }
        return null;
    }

    public synchronized boolean deletePlayerNote(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return false;
        }
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM guardian_player_note WHERE player_name = ?")) {
            stmt.setString(1, playerName.trim());
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            warn("Failed to delete Guardian player note: " + e.getMessage());
            return false;
        }
    }

    public synchronized List<IncidentRecord> listIncidents(long since, int limit) {
        List<IncidentRecord> results = new ArrayList<>();
        String sql = """
                SELECT player_name, world, (x / 16) chunk_x, (z / 16) chunk_z,
                       MIN(timestamp) first_ts, MAX(timestamp) last_ts, COUNT(*) total_actions,
                       SUM(CASE WHEN event_type = 'block' THEN 1 ELSE 0 END) block_actions,
                       SUM(CASE WHEN event_type = 'container' THEN 1 ELSE 0 END) container_actions
                FROM (
                    SELECT 'block' event_type, timestamp, player_name, world, x, z FROM guardian_block_log WHERE timestamp >= ?
                    UNION ALL
                    SELECT 'container' event_type, timestamp, player_name, world, x, z FROM guardian_container_log WHERE timestamp >= ?
                ) events
                GROUP BY player_name, world, chunk_x, chunk_z
                HAVING total_actions >= 5
                ORDER BY total_actions DESC, last_ts DESC
                LIMIT ?
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, since);
            stmt.setLong(2, since);
            stmt.setInt(3, clampLimit(limit));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int total = rs.getInt("total_actions");
                    int containers = rs.getInt("container_actions");
                    long first = rs.getLong("first_ts");
                    long last = rs.getLong("last_ts");
                    int score = total + (containers * 2) + (last - first <= 600 ? 15 : 0);
                    results.add(new IncidentRecord(
                            rs.getString("player_name"),
                            rs.getString("world"),
                            rs.getInt("chunk_x"),
                            rs.getInt("chunk_z"),
                            first,
                            last,
                            total,
                            rs.getInt("block_actions"),
                            containers,
                            score));
                }
            }
        } catch (SQLException e) {
            warn("Failed to build Guardian incidents: " + e.getMessage());
        }
        return results;
    }

    public synchronized List<SuspicionScoreRecord> listSuspicionScores(long since, int limit) {
        List<SuspicionScoreRecord> results = new ArrayList<>();
        String sql = """
                SELECT player_name,
                       COUNT(*) total_actions,
                       SUM(block_breaks) block_breaks,
                       SUM(container_removes) container_removes,
                       SUM(rare_hits) rare_hits,
                       SUM(danger_hits) danger_hits,
                       MIN(timestamp) first_ts,
                       MAX(timestamp) last_ts
                FROM (
                    SELECT player_name, timestamp,
                           CASE WHEN action = 0 THEN 1 ELSE 0 END block_breaks,
                           0 container_removes,
                           CASE WHEN block_type LIKE '%DIAMOND%' OR block_type LIKE '%ANCIENT_DEBRIS%' OR block_type LIKE '%NETHERITE%' OR block_type LIKE '%EMERALD%' THEN 1 ELSE 0 END rare_hits,
                           CASE WHEN block_type LIKE '%TNT%' OR block_type LIKE '%LAVA%' OR block_type LIKE '%FIRE%' THEN 1 ELSE 0 END danger_hits
                    FROM guardian_block_log WHERE timestamp >= ?
                    UNION ALL
                    SELECT player_name, timestamp,
                           0 block_breaks,
                           CASE WHEN action = 0 THEN 1 ELSE 0 END container_removes,
                           CASE WHEN item_material LIKE '%DIAMOND%' OR item_material LIKE '%NETHERITE%' OR item_material LIKE '%ELYTRA%' OR item_material LIKE '%EMERALD%' THEN 1 ELSE 0 END rare_hits,
                           0 danger_hits
                    FROM guardian_container_log WHERE timestamp >= ?
                ) events
                GROUP BY player_name
                ORDER BY (SUM(block_breaks) + (SUM(container_removes) * 2) + (SUM(rare_hits) * 8) + (SUM(danger_hits) * 5)) DESC,
                         total_actions DESC
                LIMIT ?
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, since);
            stmt.setLong(2, since);
            stmt.setInt(3, clampLimit(limit));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int blockBreaks = rs.getInt("block_breaks");
                    int containerRemoves = rs.getInt("container_removes");
                    int rareHits = rs.getInt("rare_hits");
                    int dangerHits = rs.getInt("danger_hits");
                    int total = rs.getInt("total_actions");
                    int score = blockBreaks + (containerRemoves * 2) + (rareHits * 8) + (dangerHits * 5)
                            + (total > 100 ? 20 : 0);
                    results.add(new SuspicionScoreRecord(
                            rs.getString("player_name"),
                            score,
                            score >= 120 ? "CRITICAL" : score >= 70 ? "HIGH" : score >= 35 ? "WATCH" : "LOW",
                            total,
                            blockBreaks,
                            containerRemoves,
                            rareHits,
                            dangerHits,
                            rs.getLong("first_ts"),
                            rs.getLong("last_ts")));
                }
            }
        } catch (SQLException e) {
            warn("Failed to build Guardian suspicion scores: " + e.getMessage());
        }
        return results;
    }

    public synchronized ActionPreviewDiff buildActionPreviewDiff(String playerName, String world, Long fromTime,
            String action, Integer x, Integer y, Integer z, Integer radius, List<String> include,
            List<String> exclude, int limit, boolean includeBlocks, boolean includeContainers) {
        List<BlockLogEntry> blocks = includeBlocks
                ? searchBlockLogsAdvanced(playerName, world, fromTime, null, parseBlockAction(action), x, y, z, radius,
                        include, exclude, 1, limit, false)
                : List.of();
        List<ContainerLogEntry> containers = includeContainers
                ? searchContainerLogsAdvanced(playerName, world, fromTime, null, parseContainerAction(action), x, y, z,
                        radius, include, exclude, 1, limit, false)
                : List.of();
        int blockBreaks = 0;
        int blockPlaces = 0;
        int containerAdds = 0;
        int containerRemoves = 0;
        Map<String, Integer> targets = new LinkedHashMap<>();
        for (BlockLogEntry row : blocks) {
            if (row.action() == ACTION_PLACE) blockPlaces++;
            else blockBreaks++;
            bump(targets, row.blockType(), 1);
        }
        for (ContainerLogEntry row : containers) {
            if (row.action() == CONTAINER_ACTION_ADD) containerAdds += row.itemAmount();
            else containerRemoves += row.itemAmount();
            bump(targets, row.itemMaterial(), row.itemAmount());
        }
        return new ActionPreviewDiff(blocks.size(), containers.size(), blockBreaks, blockPlaces, containerRemoves,
                containerAdds, topCounts(targets, 8));
    }

    public synchronized List<ItemAmountRecord> containerRestorePlan(String playerName, String world, Long fromTime,
            Integer x, Integer y, Integer z, Integer radius, int limit) {
        List<ContainerLogEntry> rows = searchContainerLogsAdvanced(playerName, world, fromTime, null,
                CONTAINER_ACTION_REMOVE, x, y, z, radius, List.of(), List.of(), 1, limit, true);
        Map<String, Integer> amounts = new LinkedHashMap<>();
        for (ContainerLogEntry row : rows) {
            bump(amounts, row.itemMaterial(), row.itemAmount());
        }
        return topCounts(amounts, 24);
    }

    public synchronized List<UnifiedTimelineEntry> searchTimelineReplay(String search, String playerName, String world,
            Long fromTime, Long toTime, int limit) {
        List<UnifiedTimelineEntry> newestFirst = searchTimeline(search, playerName, world, fromTime, toTime, limit);
        List<UnifiedTimelineEntry> replay = new ArrayList<>();
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            replay.add(newestFirst.get(i));
        }
        return replay;
    }

    public synchronized List<ProtectedRegionRecord> listProtectedRegions() {
        List<ProtectedRegionRecord> results = new ArrayList<>();
        String sql = """
                SELECT id, name, world, min_x, min_y, min_z, max_x, max_y, max_z, severity, created_by, created_at, updated_at
                FROM guardian_protected_region
                ORDER BY severity DESC, name ASC
                """;
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                results.add(protectedRegionFromRow(rs));
            }
        } catch (SQLException e) {
            warn("Failed to list Guardian protected regions: " + e.getMessage());
        }
        return results;
    }

    public synchronized ProtectedRegionRecord upsertProtectedRegion(Long id, String name, String world,
            Integer x1, Integer y1, Integer z1, Integer x2, Integer y2, Integer z2, String severity, String actor) {
        if (name == null || name.isBlank() || world == null || world.isBlank()
                || x1 == null || y1 == null || z1 == null || x2 == null || y2 == null || z2 == null) {
            return null;
        }
        int minX = Math.min(x1, x2), minY = Math.min(y1, y2), minZ = Math.min(z1, z2);
        int maxX = Math.max(x1, x2), maxY = Math.max(y1, y2), maxZ = Math.max(z1, z2);
        long now = System.currentTimeMillis() / 1000L;
        String sql = id != null && id > 0
                ? """
                UPDATE guardian_protected_region
                SET name = ?, world = ?, min_x = ?, min_y = ?, min_z = ?, max_x = ?, max_y = ?, max_z = ?,
                    severity = ?, created_by = COALESCE(created_by, ?), updated_at = ?
                WHERE id = ?
                """
                : """
                INSERT INTO guardian_protected_region
                (name, world, min_x, min_y, min_z, max_x, max_y, max_z, severity, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET
                    world = excluded.world,
                    min_x = excluded.min_x,
                    min_y = excluded.min_y,
                    min_z = excluded.min_z,
                    max_x = excluded.max_x,
                    max_y = excluded.max_y,
                    max_z = excluded.max_z,
                    severity = excluded.severity,
                    updated_at = excluded.updated_at
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, safeTitle(name));
            stmt.setString(2, safeWorld(world));
            stmt.setInt(3, minX);
            stmt.setInt(4, minY);
            stmt.setInt(5, minZ);
            stmt.setInt(6, maxX);
            stmt.setInt(7, maxY);
            stmt.setInt(8, maxZ);
            stmt.setString(9, normalizeRegionSeverity(severity));
            stmt.setString(10, blankToNull(actor));
            if (id != null && id > 0) {
                stmt.setLong(11, now);
                stmt.setLong(12, id);
            } else {
                stmt.setLong(11, now);
                stmt.setLong(12, now);
            }
            stmt.executeUpdate();
            if (id != null && id > 0) {
                return getProtectedRegion(id);
            }
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    return getProtectedRegion(keys.getLong(1));
                }
            }
            return findProtectedRegionByName(name);
        } catch (SQLException e) {
            warn("Failed to save Guardian protected region: " + e.getMessage());
            return null;
        }
    }

    public synchronized boolean deleteProtectedRegion(long id) {
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM guardian_protected_region WHERE id = ?")) {
            stmt.setLong(1, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            warn("Failed to delete Guardian protected region: " + e.getMessage());
            return false;
        }
    }

    public synchronized List<ProtectedRegionHitRecord> listProtectedRegionHits(long since, int limit) {
        List<ProtectedRegionHitRecord> results = new ArrayList<>();
        String sql = """
                SELECT r.name, r.severity, events.player_name, r.world, COUNT(*) total_actions, MAX(events.timestamp) last_ts
                FROM guardian_protected_region r
                JOIN (
                    SELECT timestamp, player_name, world, x, y, z FROM guardian_block_log WHERE timestamp >= ?
                    UNION ALL
                    SELECT timestamp, player_name, world, x, y, z FROM guardian_container_log WHERE timestamp >= ?
                ) events ON events.world = r.world
                    AND events.x BETWEEN r.min_x AND r.max_x
                    AND events.y BETWEEN r.min_y AND r.max_y
                    AND events.z BETWEEN r.min_z AND r.max_z
                GROUP BY r.name, r.severity, events.player_name, r.world
                ORDER BY total_actions DESC, last_ts DESC
                LIMIT ?
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, since);
            stmt.setLong(2, since);
            stmt.setInt(3, clampLimit(limit));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new ProtectedRegionHitRecord(rs.getString("name"), rs.getString("severity"),
                            rs.getString("player_name"), rs.getString("world"), rs.getInt("total_actions"),
                            rs.getLong("last_ts")));
                }
            }
        } catch (SQLException e) {
            warn("Failed to list Guardian protected region hits: " + e.getMessage());
        }
        return results;
    }

    public synchronized List<AlertRuleRecord> listAlertRules() {
        List<AlertRuleRecord> results = new ArrayList<>();
        String sql = """
                SELECT id, name, enabled, window_seconds, min_actions, action, material, auto_case, priority, created_by, created_at, updated_at
                FROM guardian_alert_rule
                ORDER BY enabled DESC, updated_at DESC
                """;
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                results.add(alertRuleFromRow(rs));
            }
        } catch (SQLException e) {
            warn("Failed to list Guardian alert rules: " + e.getMessage());
        }
        return results;
    }

    public synchronized AlertRuleRecord upsertAlertRule(Long id, String name, boolean enabled, int windowSeconds,
            int minActions, String action, String material, boolean autoCase, String priority, String actor) {
        if (name == null || name.isBlank()) {
            return null;
        }
        long now = System.currentTimeMillis() / 1000L;
        int safeWindow = Math.max(60, Math.min(windowSeconds, 604800));
        int safeMin = Math.max(1, Math.min(minActions, 100000));
        String normalizedAction = normalizeRuleAction(action);
        String normalizedMaterial = material == null || material.isBlank() ? null : normalizeMaterial(material);
        String sql = id != null && id > 0
                ? """
                UPDATE guardian_alert_rule
                SET name = ?, enabled = ?, window_seconds = ?, min_actions = ?, action = ?, material = ?,
                    auto_case = ?, priority = ?, created_by = COALESCE(created_by, ?), updated_at = ?
                WHERE id = ?
                """
                : """
                INSERT INTO guardian_alert_rule
                (name, enabled, window_seconds, min_actions, action, material, auto_case, priority, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET
                    enabled = excluded.enabled,
                    window_seconds = excluded.window_seconds,
                    min_actions = excluded.min_actions,
                    action = excluded.action,
                    material = excluded.material,
                    auto_case = excluded.auto_case,
                    priority = excluded.priority,
                    updated_at = excluded.updated_at
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, safeTitle(name));
            stmt.setInt(2, enabled ? 1 : 0);
            stmt.setInt(3, safeWindow);
            stmt.setInt(4, safeMin);
            stmt.setString(5, normalizedAction);
            stmt.setString(6, normalizedMaterial);
            stmt.setInt(7, autoCase ? 1 : 0);
            stmt.setString(8, normalizePriority(priority));
            stmt.setString(9, blankToNull(actor));
            stmt.setLong(10, now);
            if (id != null && id > 0) {
                stmt.setLong(11, id);
            } else {
                stmt.setLong(11, now);
            }
            stmt.executeUpdate();
            if (id != null && id > 0) {
                return getAlertRule(id);
            }
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    return getAlertRule(keys.getLong(1));
                }
            }
            return findAlertRuleByName(name);
        } catch (SQLException e) {
            warn("Failed to save Guardian alert rule: " + e.getMessage());
            return null;
        }
    }

    public synchronized boolean deleteAlertRule(long id) {
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM guardian_alert_rule WHERE id = ?")) {
            stmt.setLong(1, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            warn("Failed to delete Guardian alert rule: " + e.getMessage());
            return false;
        }
    }

    public synchronized List<AlertHitRecord> evaluateAlertRules(boolean createCases, String actor) {
        List<AlertHitRecord> hits = new ArrayList<>();
        for (AlertRuleRecord rule : listAlertRules()) {
            if (!rule.enabled()) {
                continue;
            }
            for (AlertHitRecord hit : queryAlertRuleHits(rule)) {
                hits.add(hit);
                if (createCases && rule.autoCase() && !hasOpenAutoCase(rule.name(), hit.playerName())) {
                    createCase("Auto: " + rule.name() + " - " + hit.playerName(), rule.priority(),
                            hit.playerName(), null, null, null, null,
                            "Guardian alert rule matched " + hit.count() + " actions in "
                                    + rule.windowSeconds() + " seconds.",
                            actor);
                }
            }
        }
        return hits;
    }

    public synchronized RetentionPolicyRecord getRetentionPolicy() {
        String sql = "SELECT id, log_days, keep_cases, updated_by, updated_at FROM guardian_retention_policy WHERE id = 1";
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return new RetentionPolicyRecord(rs.getInt("log_days"), rs.getInt("keep_cases") != 0,
                        rs.getString("updated_by"), rs.getLong("updated_at"));
            }
        } catch (SQLException e) {
            warn("Failed to load Guardian retention policy: " + e.getMessage());
        }
        return new RetentionPolicyRecord(90, true, "system", System.currentTimeMillis() / 1000L);
    }

    public synchronized RetentionPolicyRecord saveRetentionPolicy(int logDays, boolean keepCases, String actor) {
        int safeDays = Math.max(1, Math.min(logDays, 3650));
        long now = System.currentTimeMillis() / 1000L;
        String sql = """
                INSERT INTO guardian_retention_policy(id, log_days, keep_cases, updated_by, updated_at)
                VALUES(1, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    log_days = excluded.log_days,
                    keep_cases = excluded.keep_cases,
                    updated_by = excluded.updated_by,
                    updated_at = excluded.updated_at
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, safeDays);
            stmt.setInt(2, keepCases ? 1 : 0);
            stmt.setString(3, blankToNull(actor));
            stmt.setLong(4, now);
            stmt.executeUpdate();
        } catch (SQLException e) {
            warn("Failed to save Guardian retention policy: " + e.getMessage());
        }
        return getRetentionPolicy();
    }

    public synchronized PurgeResult applyRetentionPolicy() {
        RetentionPolicyRecord policy = getRetentionPolicy();
        long cutoff = (System.currentTimeMillis() / 1000L) - (policy.logDays() * 86400L);
        return purgeOlderThan(cutoff, null, List.of());
    }

    public synchronized GuardianInboxRecord getInbox(long since) {
        return new GuardianInboxRecord(
                listCases("OPEN", null, 10),
                evaluateAlertRules(false, "system"),
                listPlayerNotes(null, "ALERT", 10),
                listIncidents(since, 10));
    }

    public synchronized List<HeatmapEntry> getHeatmapData(long since, int limit) {
        List<HeatmapEntry> results = new ArrayList<>();
        String sql = """
                SELECT world, (x / 16) chunk_x, (z / 16) chunk_z, COUNT(*) count
                FROM guardian_block_log
                WHERE timestamp >= ?
                GROUP BY world, chunk_x, chunk_z
                ORDER BY count DESC
                LIMIT ?
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, since);
            stmt.setInt(2, clampLimit(limit));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new HeatmapEntry(rs.getString("world"), rs.getInt("chunk_x"),
                            rs.getInt("chunk_z"), rs.getInt("count")));
                }
            }
        } catch (SQLException e) {
            warn("Failed to query Guardian heatmap: " + e.getMessage());
        }
        return results;
    }

    public synchronized List<SuspiciousEntry> getSuspiciousPlayers(long since) {
        List<SuspiciousEntry> results = new ArrayList<>();
        String sql = """
                SELECT player_name,
                       COUNT(*) total_broken,
                       SUM(CASE WHEN block_type LIKE '%DIAMOND_ORE%' THEN 1 ELSE 0 END) diamonds,
                       SUM(CASE WHEN block_type LIKE '%ANCIENT_DEBRIS%' THEN 1 ELSE 0 END) debris
                FROM guardian_block_log
                WHERE action = 0 AND timestamp >= ?
                GROUP BY player_name
                HAVING total_broken > 100
                   AND ((CAST(diamonds AS FLOAT) / total_broken) > 0.05
                    OR  (CAST(debris AS FLOAT) / total_broken) > 0.05)
                ORDER BY diamonds DESC, debris DESC
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, since);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new SuspiciousEntry(rs.getString("player_name"), rs.getInt("total_broken"),
                            rs.getInt("diamonds"), rs.getInt("debris")));
                }
            }
        } catch (SQLException e) {
            warn("Failed to query Guardian suspicious players: " + e.getMessage());
        }
        return results;
    }

    public synchronized Map<Integer, Integer> getPeakHoursData(long since) {
        Map<Integer, Integer> hours = new LinkedHashMap<>();
        for (int i = 0; i < 24; i++) {
            hours.put(i, 0);
        }
        String sql = """
                SELECT strftime('%H', datetime(timestamp, 'unixepoch')) hour, COUNT(*) count
                FROM guardian_block_log
                WHERE timestamp >= ?
                GROUP BY hour
                ORDER BY hour
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, since);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    hours.put(Integer.parseInt(rs.getString("hour")), rs.getInt("count"));
                }
            }
        } catch (Exception e) {
            warn("Failed to query Guardian peak hours: " + e.getMessage());
        }
        return hours;
    }

    public synchronized List<PlayerActivity> getTopPlayersData(long since, int limit) {
        List<PlayerActivity> players = new ArrayList<>();
        String sql = """
                SELECT player_name,
                       COUNT(*) total_actions,
                       SUM(CASE WHEN action = 0 THEN 1 ELSE 0 END) blocks_broken,
                       SUM(CASE WHEN action = 1 THEN 1 ELSE 0 END) blocks_placed
                FROM guardian_block_log
                WHERE timestamp >= ?
                GROUP BY player_name
                ORDER BY total_actions DESC
                LIMIT ?
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, since);
            stmt.setInt(2, clampLimit(limit));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    players.add(new PlayerActivity(rs.getString("player_name"), rs.getInt("total_actions"),
                            rs.getInt("blocks_broken"), rs.getInt("blocks_placed")));
                }
            }
        } catch (SQLException e) {
            warn("Failed to query Guardian top players: " + e.getMessage());
        }
        return players;
    }

    public synchronized Map<String, Integer> getBlockTypesData(long since, String action, int limit) {
        Map<String, Integer> types = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("""
                SELECT block_type, COUNT(*) count
                FROM guardian_block_log
                WHERE timestamp >= ?
                """);
        Integer actionId = parseBlockAction(action);
        if (actionId != null) {
            sql.append(" AND action = ?");
        }
        sql.append(" GROUP BY block_type ORDER BY count DESC LIMIT ?");
        try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            stmt.setLong(1, since);
            int idx = 2;
            if (actionId != null) stmt.setInt(idx++, actionId);
            stmt.setInt(idx, clampLimit(limit));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    types.put(rs.getString("block_type"), rs.getInt("count"));
                }
            }
        } catch (SQLException e) {
            warn("Failed to query Guardian block types: " + e.getMessage());
        }
        return types;
    }

    public synchronized int[] countLogsSince(long since) {
        int[] counts = new int[2];
        try (PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM guardian_block_log WHERE timestamp >= ?")) {
            stmt.setLong(1, since);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) counts[0] = rs.getInt(1);
            }
        } catch (SQLException e) {
            warn("Failed to count Guardian block logs: " + e.getMessage());
        }
        try (PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM guardian_container_log WHERE timestamp >= ?")) {
            stmt.setLong(1, since);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) counts[1] = rs.getInt(1);
            }
        } catch (SQLException e) {
            warn("Failed to count Guardian container logs: " + e.getMessage());
        }
        return counts;
    }

    private QueryParts buildBlockQuery(String playerName, String world, Long fromTime, Long toTime, Integer action) {
        QueryParts query = new QueryParts("""
                SELECT id, source, timestamp, player_uuid, player_name, action, world, x, y, z, block_type, old_block_type
                FROM guardian_block_log
                WHERE 1=1
                """);
        addFilters(query, playerName, world, fromTime, toTime, action);
        return query;
    }

    private QueryParts buildContainerQuery(String playerName, String world, Long fromTime, Long toTime, Integer action) {
        QueryParts query = new QueryParts("""
                SELECT id, source, timestamp, player_uuid, player_name, action, world, x, y, z, item_material, item_amount
                FROM guardian_container_log
                WHERE 1=1
                """);
        addFilters(query, playerName, world, fromTime, toTime, action);
        return query;
    }

    private QueryParts buildBlockCountQuery(String playerName, String world, Long fromTime, Long toTime, Integer action,
            Integer x, Integer y, Integer z, Integer radius, List<String> include, List<String> exclude) {
        QueryParts query = new QueryParts("SELECT COUNT(*) FROM guardian_block_log WHERE 1=1");
        addFilters(query, playerName, world, fromTime, toTime, action);
        addLocationFilter(query, x, y, z, radius);
        addMaterialFilter(query, "block_type", include, exclude);
        return query;
    }

    private QueryParts buildContainerCountQuery(String playerName, String world, Long fromTime, Long toTime,
            Integer action, Integer x, Integer y, Integer z, Integer radius, List<String> include,
            List<String> exclude) {
        QueryParts query = new QueryParts("SELECT COUNT(*) FROM guardian_container_log WHERE 1=1");
        addFilters(query, playerName, world, fromTime, toTime, action);
        addLocationFilter(query, x, y, z, radius);
        addMaterialFilter(query, "item_material", include, exclude);
        return query;
    }

    private void addFilters(QueryParts query, String playerName, String world, Long fromTime, Long toTime,
            Integer action) {
        if (playerName != null && !playerName.isBlank()) {
            query.sql.append(" AND player_name LIKE ?");
            query.params.add("%" + playerName.trim() + "%");
        }
        if (world != null && !world.isBlank()) {
            query.sql.append(" AND world = ?");
            query.params.add(world.trim());
        }
        if (fromTime != null) {
            query.sql.append(" AND timestamp >= ?");
            query.params.add(fromTime);
        }
        if (toTime != null) {
            query.sql.append(" AND timestamp <= ?");
            query.params.add(toTime);
        }
        if (action != null) {
            query.sql.append(" AND action = ?");
            query.params.add(action);
        }
    }

    private void addTimelineFilters(QueryParts query, String materialColumn, String search, String playerName,
            String world, Long fromTime, Long toTime) {
        if (playerName != null && !playerName.isBlank()) {
            query.sql.append(" AND player_name LIKE ?");
            query.params.add("%" + playerName.trim() + "%");
        }
        if (world != null && !world.isBlank()) {
            query.sql.append(" AND world = ?");
            query.params.add(world.trim());
        }
        if (fromTime != null) {
            query.sql.append(" AND timestamp >= ?");
            query.params.add(fromTime);
        }
        if (toTime != null) {
            query.sql.append(" AND timestamp <= ?");
            query.params.add(toTime);
        }
        if (search != null && !search.isBlank()) {
            String value = "%" + search.trim() + "%";
            query.sql.append(" AND (player_name LIKE ? OR world LIKE ? OR ").append(materialColumn).append(" LIKE ?)");
            query.params.add(value);
            query.params.add(value);
            query.params.add(value);
        }
    }

    private void addLocationFilter(QueryParts query, Integer x, Integer y, Integer z, Integer radius) {
        if (x == null || y == null || z == null) {
            return;
        }
        int safeRadius = Math.max(0, Math.min(radius == null ? 0 : radius, 10000));
        if (safeRadius == 0) {
            query.sql.append(" AND x = ? AND y = ? AND z = ?");
            query.params.add(x);
            query.params.add(y);
            query.params.add(z);
            return;
        }
        query.sql.append(" AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?");
        query.params.add(x - safeRadius);
        query.params.add(x + safeRadius);
        query.params.add(y - safeRadius);
        query.params.add(y + safeRadius);
        query.params.add(z - safeRadius);
        query.params.add(z + safeRadius);
    }

    private void addMaterialFilter(QueryParts query, String column, List<String> include, List<String> exclude) {
        List<String> includeValues = materialVariants(include);
        if (!includeValues.isEmpty()) {
            appendInClause(query, column, includeValues, false);
        }
        List<String> excludeValues = materialVariants(exclude);
        if (!excludeValues.isEmpty()) {
            appendInClause(query, column, excludeValues, true);
        }
    }

    private void appendInClause(QueryParts query, String column, List<String> values, boolean exclude) {
        query.sql.append(exclude ? " AND " : " AND ");
        query.sql.append(column).append(exclude ? " NOT IN (" : " IN (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) query.sql.append(',');
            query.sql.append('?');
            query.params.add(values.get(i));
        }
        query.sql.append(')');
    }

    private int countRows(QueryParts query) {
        try (PreparedStatement stmt = prepare(query); ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            warn("Failed to count Guardian logs: " + e.getMessage());
            return 0;
        }
    }

    private int purgeRows(String table, String materialColumn, long cutoff, String world, List<String> include) {
        QueryParts query = new QueryParts("DELETE FROM " + table + " WHERE timestamp < ?");
        query.params.add(cutoff);
        if (world != null && !world.isBlank()) {
            query.sql.append(" AND world = ?");
            query.params.add(world.trim());
        }
        List<String> includeValues = materialVariants(include);
        if (!includeValues.isEmpty()) {
            appendInClause(query, materialColumn, includeValues, false);
        }
        try (PreparedStatement stmt = prepare(query)) {
            return stmt.executeUpdate();
        } catch (SQLException e) {
            warn("Failed to purge Guardian logs: " + e.getMessage());
            return 0;
        }
    }

    private PreparedStatement prepare(QueryParts query) throws SQLException {
        PreparedStatement stmt = connection.prepareStatement(query.sql.toString());
        bindParams(stmt, query.params);
        return stmt;
    }

    private void bindParams(PreparedStatement stmt, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            int pos = i + 1;
            if (value instanceof Integer intValue) {
                stmt.setInt(pos, intValue);
            } else if (value instanceof Long longValue) {
                stmt.setLong(pos, longValue);
            } else {
                stmt.setString(pos, String.valueOf(value));
            }
        }
    }

    private static void bump(Map<String, Integer> counts, String key, int amount) {
        if (key == null || key.isBlank() || amount <= 0) {
            return;
        }
        counts.put(key, counts.getOrDefault(key, 0) + amount);
    }

    private static List<ItemAmountRecord> topCounts(Map<String, Integer> counts, int limit) {
        List<ItemAmountRecord> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            rows.add(new ItemAmountRecord(entry.getKey(), entry.getValue()));
        }
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                if (rows.get(j).amount() > rows.get(i).amount()) {
                    ItemAmountRecord tmp = rows.get(i);
                    rows.set(i, rows.get(j));
                    rows.set(j, tmp);
                }
            }
        }
        return rows.size() <= limit ? rows : new ArrayList<>(rows.subList(0, Math.max(0, limit)));
    }

    private ProtectedRegionRecord getProtectedRegion(long id) {
        String sql = """
                SELECT id, name, world, min_x, min_y, min_z, max_x, max_y, max_z, severity, created_by, created_at, updated_at
                FROM guardian_protected_region
                WHERE id = ?
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return protectedRegionFromRow(rs);
                }
            }
        } catch (SQLException e) {
            warn("Failed to load Guardian protected region: " + e.getMessage());
        }
        return null;
    }

    private ProtectedRegionRecord findProtectedRegionByName(String name) {
        String sql = """
                SELECT id, name, world, min_x, min_y, min_z, max_x, max_y, max_z, severity, created_by, created_at, updated_at
                FROM guardian_protected_region
                WHERE name = ?
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, safeTitle(name));
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return protectedRegionFromRow(rs);
                }
            }
        } catch (SQLException e) {
            warn("Failed to find Guardian protected region: " + e.getMessage());
        }
        return null;
    }

    private AlertRuleRecord getAlertRule(long id) {
        String sql = """
                SELECT id, name, enabled, window_seconds, min_actions, action, material, auto_case, priority, created_by, created_at, updated_at
                FROM guardian_alert_rule
                WHERE id = ?
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return alertRuleFromRow(rs);
                }
            }
        } catch (SQLException e) {
            warn("Failed to load Guardian alert rule: " + e.getMessage());
        }
        return null;
    }

    private AlertRuleRecord findAlertRuleByName(String name) {
        String sql = """
                SELECT id, name, enabled, window_seconds, min_actions, action, material, auto_case, priority, created_by, created_at, updated_at
                FROM guardian_alert_rule
                WHERE name = ?
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, safeTitle(name));
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return alertRuleFromRow(rs);
                }
            }
        } catch (SQLException e) {
            warn("Failed to find Guardian alert rule: " + e.getMessage());
        }
        return null;
    }

    private List<AlertHitRecord> queryAlertRuleHits(AlertRuleRecord rule) {
        List<AlertHitRecord> hits = new ArrayList<>();
        QueryParts query = new QueryParts("""
                SELECT player_name, COUNT(*) total_actions, MIN(timestamp) first_ts, MAX(timestamp) last_ts
                FROM (
                    SELECT timestamp, player_name, CASE WHEN action = 1 THEN 'place' ELSE 'break' END event_action, block_type target
                    FROM guardian_block_log WHERE timestamp >= ?
                    UNION ALL
                    SELECT timestamp, player_name, CASE WHEN action = 1 THEN 'add' ELSE 'remove' END event_action, item_material target
                    FROM guardian_container_log WHERE timestamp >= ?
                ) events
                WHERE 1=1
                """);
        long since = (System.currentTimeMillis() / 1000L) - rule.windowSeconds();
        query.params.add(since);
        query.params.add(since);
        if (rule.action() != null && !rule.action().isBlank()) {
            query.sql.append(" AND event_action = ?");
            query.params.add(rule.action());
        }
        List<String> materials = materialVariants(rule.material() == null ? List.of() : List.of(rule.material()));
        if (!materials.isEmpty()) {
            appendInClause(query, "target", materials, false);
        }
        query.sql.append(" GROUP BY player_name HAVING total_actions >= ? ORDER BY total_actions DESC LIMIT 25");
        query.params.add(rule.minActions());
        try (PreparedStatement stmt = prepare(query); ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                hits.add(new AlertHitRecord(rule.id(), rule.name(), rs.getString("player_name"),
                        rs.getInt("total_actions"), rs.getLong("first_ts"), rs.getLong("last_ts"),
                        rule.priority(), rule.autoCase()));
            }
        } catch (SQLException e) {
            warn("Failed to evaluate Guardian alert rule: " + e.getMessage());
        }
        return hits;
    }

    private boolean hasOpenAutoCase(String ruleName, String playerName) {
        String titlePrefix = "Auto: " + safeTitle(ruleName) + " - ";
        String sql = """
                SELECT COUNT(*) FROM guardian_case
                WHERE status IN ('OPEN', 'INVESTIGATING')
                  AND title LIKE ?
                  AND IFNULL(player_name, '') = ?
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, titlePrefix + "%");
            stmt.setString(2, playerName == null ? "" : playerName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            warn("Failed to check Guardian auto case: " + e.getMessage());
            return false;
        }
    }

    private static void setNullableInt(PreparedStatement stmt, int index, Integer value) throws SQLException {
        if (value == null) {
            stmt.setNull(index, java.sql.Types.INTEGER);
        } else {
            stmt.setInt(index, value);
        }
    }

    private static CaseRecord caseFromRow(ResultSet rs) throws SQLException {
        Integer x = nullableInt(rs, "x");
        Integer y = nullableInt(rs, "y");
        Integer z = nullableInt(rs, "z");
        return new CaseRecord(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getString("priority"),
                rs.getString("player_name"),
                rs.getString("world"),
                x,
                y,
                z,
                rs.getString("notes"),
                rs.getString("created_by"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getInt("locked") != 0);
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static PlayerNoteRecord playerNoteFromRow(ResultSet rs) throws SQLException {
        return new PlayerNoteRecord(
                rs.getString("player_name"),
                rs.getString("severity"),
                rs.getString("notes"),
                rs.getString("created_by"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    private static ProtectedRegionRecord protectedRegionFromRow(ResultSet rs) throws SQLException {
        return new ProtectedRegionRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("world"),
                rs.getInt("min_x"),
                rs.getInt("min_y"),
                rs.getInt("min_z"),
                rs.getInt("max_x"),
                rs.getInt("max_y"),
                rs.getInt("max_z"),
                rs.getString("severity"),
                rs.getString("created_by"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    private static AlertRuleRecord alertRuleFromRow(ResultSet rs) throws SQLException {
        return new AlertRuleRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getInt("enabled") != 0,
                rs.getInt("window_seconds"),
                rs.getInt("min_actions"),
                rs.getString("action"),
                rs.getString("material"),
                rs.getInt("auto_case") != 0,
                rs.getString("priority"),
                rs.getString("created_by"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    private static String safeTitle(String value) {
        String title = value == null ? "" : value.trim();
        if (title.isBlank()) {
            return "Guardian Case";
        }
        return title.length() > 120 ? title.substring(0, 120) : title;
    }

    private static String normalizeStatus(String value) {
        if (value == null || value.isBlank()) {
            return "OPEN";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "OPEN", "INVESTIGATING", "RESOLVED", "FALSE_ALARM" -> normalized;
            default -> "OPEN";
        };
    }

    private static String normalizePriority(String value) {
        if (value == null || value.isBlank()) {
            return "NORMAL";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "LOW", "NORMAL", "HIGH", "URGENT" -> normalized;
            default -> "NORMAL";
        };
    }

    private static String normalizeEvidenceType(String value) {
        if (value == null || value.isBlank()) {
            return "block";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "container".equals(normalized) ? "container" : "block";
    }

    private static String normalizeNoteSeverity(String value) {
        if (value == null || value.isBlank()) {
            return "WATCH";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "INFO", "WATCH", "ALERT", "TRUSTED" -> normalized;
            default -> "WATCH";
        };
    }

    private static String normalizeRegionSeverity(String value) {
        if (value == null || value.isBlank()) {
            return "WATCH";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "INFO", "WATCH", "ALERT", "CRITICAL" -> normalized;
            default -> "WATCH";
        };
    }

    private static String normalizeRuleAction(String value) {
        if (value == null || value.isBlank() || "all".equalsIgnoreCase(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "break", "place", "remove", "add" -> normalized;
            default -> null;
        };
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) return 50;
        return Math.min(10000, limit);
    }

    private static String safeName(String value) {
        return value == null || value.isBlank() ? "SYSTEM" : value.trim();
    }

    private static String safeWorld(String value) {
        return value == null || value.isBlank() ? "world" : value.trim();
    }

    private static String normalizeSource(String source) {
        return source == null || source.isBlank() ? "dash" : source.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeMaterial(String material) {
        if (material == null) return "";
        return material.trim().toUpperCase(Locale.ROOT);
    }

    private static List<String> materialVariants(List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String raw : rawValues) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String normalized = normalizeMaterial(raw);
            if (!values.contains(normalized)) {
                values.add(normalized);
            }
            if (!normalized.contains(":")) {
                String namespaced = "MINECRAFT:" + normalized;
                if (!values.contains(namespaced)) {
                    values.add(namespaced);
                }
            } else {
                String shortName = normalized.substring(normalized.indexOf(':') + 1);
                if (!values.contains(shortName)) {
                    values.add(shortName);
                }
            }
        }
        return values;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public static Integer parseBlockAction(String action) {
        if (action == null || action.isBlank() || "all".equalsIgnoreCase(action)) return null;
        return switch (action.trim().toLowerCase(Locale.ROOT)) {
            case "break", "broken", "destroy", "remove", "removed", "0" -> ACTION_BREAK;
            case "place", "placed", "add", "added", "1" -> ACTION_PLACE;
            default -> null;
        };
    }

    public static Integer parseContainerAction(String action) {
        if (action == null || action.isBlank() || "all".equalsIgnoreCase(action)) return null;
        return switch (action.trim().toLowerCase(Locale.ROOT)) {
            case "remove", "removed", "take", "0" -> CONTAINER_ACTION_REMOVE;
            case "add", "added", "put", "1" -> CONTAINER_ACTION_ADD;
            default -> null;
        };
    }

    private void warn(String message) {
        warnLogger.accept(message);
    }

    @Override
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }

    private static final class QueryParts {
        final StringBuilder sql;
        final List<Object> params = new ArrayList<>();

        QueryParts(String sql) {
            this.sql = new StringBuilder(sql);
        }
    }

    public record BlockLogEntry(long id, String source, long timestamp, String playerUuid, String playerName,
            int action, String world, int x, int y, int z, String blockType, String oldBlockType) {
        public String actionLabel() {
            return action == ACTION_PLACE ? "PLACE" : "BREAK";
        }

        public String formattedTime() {
            return formatTimestamp(timestamp);
        }
    }

    public record ContainerLogEntry(long id, String source, long timestamp, String playerUuid, String playerName,
            int action, String world, int x, int y, int z, String itemMaterial, int itemAmount) {
        public String actionLabel() {
            return action == CONTAINER_ACTION_ADD ? "ADD" : "REMOVE";
        }

        public String formattedTime() {
            return formatTimestamp(timestamp);
        }
    }

    public static class ServerStats {
        public int totalBlocksBroken;
        public int totalBlocksPlaced;
        public int totalItemsRemoved;
        public int totalItemsAdded;
        public int uniquePlayers;
        public final List<PlayerActivity> topPlayers = new ArrayList<>();
    }

    public record PlayerActivity(String playerName, int totalActions, int blocksBroken, int blocksPlaced) {
    }

    public record QueryCount(int blocks, int containers) {
    }

    public record PurgeResult(int blockRows, int containerRows) {
    }

    public record GuardianStatus(boolean available, String databasePath, long databaseBytes, int blockRows,
            int containerRows) {
    }

    public record TimelineEntry(String timeSlot, int blockCount, int containerCount) {
    }

    public record UnifiedTimelineEntry(String eventType, long id, String source, long timestamp, String playerName,
            String action, String world, int x, int y, int z, String target, int amount) {
        public String formattedTime() {
            return formatTimestamp(timestamp);
        }
    }

    public record CaseRecord(long id, String title, String status, String priority, String playerName, String world,
            Integer x, Integer y, Integer z, String notes, String createdBy, long createdAt, long updatedAt,
            boolean locked) {
        public String formattedCreatedAt() {
            return formatTimestamp(createdAt);
        }

        public String formattedUpdatedAt() {
            return formatTimestamp(updatedAt);
        }
    }

    public record EvidenceRecord(long id, long caseId, String eventType, long eventId, String label, String addedBy,
            long createdAt) {
        public String formattedCreatedAt() {
            return formatTimestamp(createdAt);
        }
    }

    public record SavedFilterRecord(long id, String name, String query, String createdBy, long createdAt) {
        public String formattedCreatedAt() {
            return formatTimestamp(createdAt);
        }
    }

    public record PlayerNoteRecord(String playerName, String severity, String notes, String createdBy, long createdAt,
            long updatedAt) {
        public String formattedCreatedAt() {
            return formatTimestamp(createdAt);
        }

        public String formattedUpdatedAt() {
            return formatTimestamp(updatedAt);
        }
    }

    public record HeatmapEntry(String world, int chunkX, int chunkZ, int count) {
    }

    public record SuspiciousEntry(String playerName, int totalBroken, int diamonds, int debris) {
    }

    public record IncidentRecord(String playerName, String world, int chunkX, int chunkZ, long firstAt, long lastAt,
            int totalActions, int blockActions, int containerActions, int score) {
        public String formattedFirstAt() {
            return formatTimestamp(firstAt);
        }

        public String formattedLastAt() {
            return formatTimestamp(lastAt);
        }
    }

    public record SuspicionScoreRecord(String playerName, int score, String severity, int totalActions,
            int blockBreaks, int containerRemoves, int rareHits, int dangerHits, long firstAt, long lastAt) {
        public String formattedFirstAt() {
            return formatTimestamp(firstAt);
        }

        public String formattedLastAt() {
            return formatTimestamp(lastAt);
        }
    }

    public record ItemAmountRecord(String item, int amount) {
    }

    public record ActionPreviewDiff(int blockRows, int containerRows, int blockBreaks, int blockPlaces,
            int containerRemovedItems, int containerAddedItems, List<ItemAmountRecord> topTargets) {
    }

    public record ProtectedRegionRecord(long id, String name, String world, int minX, int minY, int minZ, int maxX,
            int maxY, int maxZ, String severity, String createdBy, long createdAt, long updatedAt) {
        public String formattedCreatedAt() {
            return formatTimestamp(createdAt);
        }

        public String formattedUpdatedAt() {
            return formatTimestamp(updatedAt);
        }
    }

    public record ProtectedRegionHitRecord(String regionName, String severity, String playerName, String world,
            int totalActions, long lastAt) {
        public String formattedLastAt() {
            return formatTimestamp(lastAt);
        }
    }

    public record AlertRuleRecord(long id, String name, boolean enabled, int windowSeconds, int minActions,
            String action, String material, boolean autoCase, String priority, String createdBy, long createdAt,
            long updatedAt) {
        public String formattedCreatedAt() {
            return formatTimestamp(createdAt);
        }

        public String formattedUpdatedAt() {
            return formatTimestamp(updatedAt);
        }
    }

    public record AlertHitRecord(long ruleId, String ruleName, String playerName, int count, long firstAt, long lastAt,
            String priority, boolean autoCase) {
        public String formattedFirstAt() {
            return formatTimestamp(firstAt);
        }

        public String formattedLastAt() {
            return formatTimestamp(lastAt);
        }
    }

    public record RetentionPolicyRecord(int logDays, boolean keepCases, String updatedBy, long updatedAt) {
        public String formattedUpdatedAt() {
            return formatTimestamp(updatedAt);
        }
    }

    public record GuardianInboxRecord(List<CaseRecord> openCases, List<AlertHitRecord> alerts,
            List<PlayerNoteRecord> alertNotes, List<IncidentRecord> incidents) {
    }

    private static String formatTimestamp(long timestamp) {
        LocalDateTime ldt = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault());
        return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
