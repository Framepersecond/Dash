package dash;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class StatsCollector {

    private static final int MAX_SAMPLES = 360;
    private static final int SAMPLE_INTERVAL_TICKS = 200;

    private final JavaPlugin plugin;
    private final List<StatsSample> history = new CopyOnWriteArrayList<>();
    private BukkitTask task;

    public StatsCollector(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::collectSample, SAMPLE_INTERVAL_TICKS,
                SAMPLE_INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    private void collectSample() {
        double tps = 20.0;
        double mspt = 0.0;

        try {
            double[] tpsArray = Bukkit.getTPS();
            if (tpsArray != null && tpsArray.length > 0) {
                tps = Math.min(tpsArray[0], 20.0);
            }
        } catch (Throwable ignored) {
        }

        try {
            mspt = Bukkit.getAverageTickTime();
        } catch (Throwable ignored) {
            mspt = tps > 0 ? 1000.0 / tps : 50.0;
        }

        long maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        long totalMem = Runtime.getRuntime().totalMemory() / 1024 / 1024;
        long freeMem = Runtime.getRuntime().freeMemory() / 1024 / 1024;
        long usedMem = totalMem - freeMem;

        int overworldChunks = 0;
        int netherChunks = 0;
        int endChunks = 0;

        try {
            for (World world : Bukkit.getWorlds()) {
                int chunks = world.getLoadedChunks().length;
                switch (world.getEnvironment()) {
                    case NORMAL:
                        overworldChunks += chunks;
                        break;
                    case NETHER:
                        netherChunks += chunks;
                        break;
                    case THE_END:
                        endChunks += chunks;
                        break;
                }
            }
        } catch (Throwable ignored) {
        }

        StatsSample sample = new StatsSample(
                System.currentTimeMillis(),
                tps,
                mspt,
                usedMem,
                maxMem,
                overworldChunks,
                netherChunks,
                endChunks);

        synchronized (history) {
            history.add(sample);
            while (history.size() > MAX_SAMPLES) {
                history.remove(0);
            }
        }
    }

    public List<StatsSample> getHistory() {
        return new LinkedList<>(history);
    }

    public StatsSample getLatest() {
        if (history.isEmpty()) {
            return new StatsSample(System.currentTimeMillis(), 20.0, 0, 0, 0, 0, 0, 0);
        }
        return history.get(history.size() - 1);
    }

    public String getHistoryJson() {
        StringBuilder json = new StringBuilder("[");
        List<StatsSample> samples = getHistory();
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0)
                json.append(",");
            json.append(samples.get(i).toJson());
        }
        json.append("]");
        return json.toString();
    }

    public static class StatsSample {
        public final long timestamp;
        public final double tps;
        public final double mspt;
        public final long ramUsed;
        public final long ramMax;
        public final int overworldChunks;
        public final int netherChunks;
        public final int endChunks;

        public StatsSample(long timestamp, double tps, double mspt, long ramUsed, long ramMax,
                int overworldChunks, int netherChunks, int endChunks) {
            this.timestamp = timestamp;
            this.tps = tps;
            this.mspt = mspt;
            this.ramUsed = ramUsed;
            this.ramMax = ramMax;
            this.overworldChunks = overworldChunks;
            this.netherChunks = netherChunks;
            this.endChunks = endChunks;
        }

        public String toJson() {
            return String.format(
                    "{\"t\":%d,\"tps\":%.2f,\"mspt\":%.2f,\"ram\":%d,\"ramMax\":%d,\"ow\":%d,\"nether\":%d,\"end\":%d}",
                    timestamp, tps, mspt, ramUsed, ramMax, overworldChunks, netherChunks, endChunks);
        }
    }
}
