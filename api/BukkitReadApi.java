package dash.api;

import dash.Dash;
import dash.StatsCollector;
import dash.data.PlayerDataManager;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Bukkit/Paper implementation of {@link DashReadApi}.
 *
 * <p>Every method observes and returns; nothing here writes. Server values that
 * are cheap and thread-safe are read directly, and anything unavailable falls
 * back to a neutral default rather than throwing at an integration.
 */
public final class BukkitReadApi implements DashReadApi {

    private final long startedAtEpochMillis = System.currentTimeMillis();

    @Override
    public String apiVersion() {
        return API_VERSION;
    }

    @Override
    public ServerSnapshot server() {
        return new ServerSnapshot(
                "bukkit",
                safe(Bukkit.getName(), "unknown"),
                safe(Bukkit.getVersion(), "unknown"),
                minecraftVersion(),
                dashVersion(),
                Bukkit.getOnlinePlayers().size(),
                Bukkit.getMaxPlayers(),
                System.currentTimeMillis() - startedAtEpochMillis,
                Bukkit.hasWhitelist());
    }

    @Override
    public PerformanceSnapshot performance() {
        double tps = 20.0;
        try {
            double[] samples = Bukkit.getTPS();
            if (samples != null && samples.length > 0) {
                tps = Math.min(20.0, samples[0]);
            }
        } catch (Throwable ignored) {
            // Not every server implementation exposes TPS.
        }

        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();

        refreshCounts();

        return new PerformanceSnapshot(
                System.currentTimeMillis(),
                tps,
                tps > 0 ? 1000.0 / tps : 0.0,
                heapUsed,
                runtime.maxMemory(),
                cpuLoad(),
                cachedChunks,
                cachedEntities);
    }

    @Override
    public List<PerformanceSnapshot> performanceHistory(int maxPoints) {
        if (maxPoints < 1) {
            return List.of();
        }
        StatsCollector collector = Dash.getStatsCollector();
        if (collector == null) {
            return List.of();
        }
        List<StatsCollector.StatsSample> history;
        try {
            history = collector.getHistory();
        } catch (Throwable ignored) {
            return List.of();
        }
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, history.size() - maxPoints);
        List<PerformanceSnapshot> out = new ArrayList<>(history.size() - from);
        for (int i = from; i < history.size(); i++) {
            StatsCollector.StatsSample sample = history.get(i);
            out.add(new PerformanceSnapshot(
                    sample.timestamp,
                    Math.min(20.0, sample.tps),
                    sample.mspt,
                    sample.ramUsed * 1024L * 1024L,
                    sample.ramMax * 1024L * 1024L,
                    sample.cpuUsage / 100.0,
                    sample.overworldChunks + sample.netherChunks + sample.endChunks,
                    0));
        }
        return List.copyOf(out);
    }

    @Override
    public List<PlayerSnapshot> players() {
        List<PlayerSnapshot> out = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            out.add(snapshot(player));
        }
        return List.copyOf(out);
    }

    @Override
    public Optional<PlayerSnapshot> player(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return Optional.of(snapshot(online));
        }
        return offlineSnapshot(uuid.toString());
    }

    @Override
    public Optional<PlayerSnapshot> playerByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(snapshot(online));
        }
        return offlineSnapshot(name);
    }

    @Override
    public List<WorldSnapshot> worlds() {
        List<WorldSnapshot> out = new ArrayList<>();
        try {
            for (World world : Bukkit.getWorlds()) {
                out.add(new WorldSnapshot(
                        world.getName(),
                        world.getEnvironment().name(),
                        world.getLoadedChunks().length,
                        world.getEntities().size(),
                        world.getPlayers().size(),
                        world.getDifficulty().name()));
            }
        } catch (Throwable ignored) {
            // Return whatever was gathered rather than failing outright.
        }
        return List.copyOf(out);
    }

    @Override
    public List<ExtensionSnapshot> extensions() {
        List<ExtensionSnapshot> out = new ArrayList<>();
        try {
            for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                out.add(new ExtensionSnapshot(
                        plugin.getName().toLowerCase(Locale.ROOT),
                        plugin.getName(),
                        safe(plugin.getDescription().getVersion(), "unknown"),
                        plugin.isEnabled(),
                        plugin.getDescription().getAuthors()));
            }
        } catch (Throwable ignored) {
            // Ditto.
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------

    /**
     * Entity and chunk totals, refreshed at most once every few seconds.
     *
     * <p>Enumerating entities is O(n) and touches collections the server
     * mutates on the main thread. Caching keeps a polled API from becoming a
     * source of load, or of a mid-iteration failure, however often it is hit.
     */
    private static final long COUNT_CACHE_MILLIS = 5_000L;
    private volatile long countsRefreshedAt;
    private volatile int cachedChunks;
    private volatile int cachedEntities;

    private void refreshCounts() {
        long now = System.currentTimeMillis();
        if (now - countsRefreshedAt < COUNT_CACHE_MILLIS) {
            return;
        }
        int chunks = 0;
        int entities = 0;
        try {
            for (World world : Bukkit.getWorlds()) {
                chunks += world.getLoadedChunks().length;
                entities += world.getEntities().size();
            }
        } catch (Throwable ignored) {
            // Keep whatever the previous refresh produced.
            return;
        }
        cachedChunks = chunks;
        cachedEntities = entities;
        countsRefreshedAt = now;
    }

    private PlayerSnapshot snapshot(Player player) {
        PlayerDataManager.PlayerInfo stored = lookup(player.getUniqueId().toString());
        double maxHealth = 20.0;
        try {
            maxHealth = player.getMaxHealth();
        } catch (Throwable ignored) {
            // Deprecated on some forks; the default is close enough for a read API.
        }
        return new PlayerSnapshot(
                player.getUniqueId(),
                player.getName(),
                true,
                player.getWorld() == null ? null : player.getWorld().getName(),
                player.getGameMode().name(),
                player.getLevel(),
                player.getHealth(),
                maxHealth,
                player.isOp(),
                stored == null ? 0L : stored.firstJoin(),
                stored == null ? 0L : stored.lastJoin(),
                stored == null ? 0L : stored.totalPlaytime());
    }

    private Optional<PlayerSnapshot> offlineSnapshot(String uuidOrName) {
        PlayerDataManager.PlayerInfo stored = lookup(uuidOrName);
        if (stored == null) {
            return Optional.empty();
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(stored.uuid());
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        boolean operator = false;
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            operator = offline.isOp();
        } catch (Throwable ignored) {
            // Offline lookups can touch disk; a missing flag is not fatal.
        }
        return Optional.of(new PlayerSnapshot(
                uuid, stored.name(), false, null, null, 0, 0.0, 0.0, operator,
                stored.firstJoin(), stored.lastJoin(), stored.totalPlaytime()));
    }

    private static PlayerDataManager.PlayerInfo lookup(String uuidOrName) {
        PlayerDataManager manager = Dash.getPlayerDataManager();
        if (manager == null) {
            return null;
        }
        try {
            return manager.getPlayerInfo(uuidOrName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String minecraftVersion() {
        try {
            String bukkitVersion = Bukkit.getBukkitVersion();
            if (bukkitVersion != null && bukkitVersion.contains("-")) {
                return bukkitVersion.substring(0, bukkitVersion.indexOf('-'));
            }
            return safe(bukkitVersion, "unknown");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String dashVersion() {
        try {
            Plugin dash = Bukkit.getPluginManager().getPlugin("Dash");
            return dash == null ? "unknown" : safe(dash.getDescription().getVersion(), "unknown");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static double cpuLoad() {
        try {
            java.lang.management.OperatingSystemMXBean bean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                double load = sunBean.getCpuLoad();
                return load < 0.0 ? -1.0 : load;
            }
        } catch (Throwable ignored) {
            // Not available on every JVM.
        }
        return -1.0;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
