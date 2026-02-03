package dash.data;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupManager {

    private final JavaPlugin plugin;
    private final File backupDir;
    private BukkitTask scheduledTask;
    private int scheduleHours = 0;

    public BackupManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.backupDir = new File(plugin.getDataFolder(), "backups");
        if (!backupDir.exists())
            backupDir.mkdirs();

        scheduleHours = plugin.getConfig().getInt("backup-schedule-hours", 0);
        if (scheduleHours > 0) {
            startSchedule(scheduleHours);
        }
    }

    public void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
        }
    }

    public void startSchedule(int hours) {
        this.scheduleHours = hours;
        plugin.getConfig().set("backup-schedule-hours", hours);
        plugin.saveConfig();

        if (scheduledTask != null) {
            scheduledTask.cancel();
        }

        if (hours > 0) {
            long ticks = hours * 60L * 60L * 20L;
            scheduledTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::createBackup, ticks, ticks);
        }
    }

    public void stopSchedule() {
        scheduleHours = 0;
        plugin.getConfig().set("backup-schedule-hours", 0);
        plugin.saveConfig();

        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
    }

    public int getScheduleHours() {
        return scheduleHours;
    }

    public BackupResult createBackup() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String fileName = "backup_" + timestamp + ".zip";
        File backupFile = new File(backupDir, fileName);

        try {
            File serverDir = Bukkit.getWorldContainer();

            List<String> toBackup = Arrays.asList(
                    "world",
                    "world_nether",
                    "world_the_end",
                    "plugins",
                    "server.properties",
                    "bukkit.yml",
                    "spigot.yml",
                    "paper.yml",
                    "config");

            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(backupFile))) {
                for (String name : toBackup) {
                    File file = new File(serverDir, name);
                    if (file.exists()) {
                        if (file.isDirectory()) {
                            zipDirectory(file, name, zos);
                        } else {
                            zipFile(file, name, zos);
                        }
                    }
                }
            }

            long size = backupFile.length();
            cleanOldBackups(10);
            return new BackupResult(true, fileName, size, null);
        } catch (Exception e) {
            return new BackupResult(false, null, 0, e.getMessage());
        }
    }

    private void zipDirectory(File folder, String parentPath, ZipOutputStream zos) throws IOException {
        File[] files = folder.listFiles();
        if (files == null)
            return;

        for (File file : files) {
            String path = parentPath + "/" + file.getName();
            if (file.isDirectory()) {
                zipDirectory(file, path, zos);
            } else {
                zipFile(file, path, zos);
            }
        }
    }

    private void zipFile(File file, String path, ZipOutputStream zos) {
        try {
            if (file.length() > 100 * 1024 * 1024)
                return;
            String name = file.getName().toLowerCase();
            if (name.endsWith(".jar") || name.endsWith(".log") || name.endsWith(".lck") || name.endsWith(".db-journal"))
                return;

            zos.putNextEntry(new ZipEntry(path));
            Files.copy(file.toPath(), zos);
            zos.closeEntry();
        } catch (IOException ignored) {
        }
    }

    private void cleanOldBackups(int keep) {
        File[] backups = backupDir.listFiles((dir, name) -> name.startsWith("backup_") && name.endsWith(".zip"));
        if (backups == null || backups.length <= keep)
            return;

        Arrays.sort(backups, Comparator.comparingLong(File::lastModified).reversed());

        for (int i = keep; i < backups.length; i++) {
            backups[i].delete();
        }
    }

    public List<BackupInfo> listBackups() {
        List<BackupInfo> list = new ArrayList<>();
        File[] backups = backupDir.listFiles((dir, name) -> name.startsWith("backup_") && name.endsWith(".zip"));
        if (backups == null)
            return list;

        Arrays.sort(backups, Comparator.comparingLong(File::lastModified).reversed());

        for (File backup : backups) {
            list.add(new BackupInfo(
                    backup.getName(),
                    backup.lastModified(),
                    backup.length()));
        }

        return list;
    }

    public File getBackupFile(String name) {
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            return null;
        }
        File file = new File(backupDir, name);
        return file.exists() ? file : null;
    }

    public boolean deleteBackup(String name) {
        File file = getBackupFile(name);
        return file != null && file.delete();
    }

    public boolean restoreBackup(String name) {
        File backup = getBackupFile(name);
        if (backup == null)
            return false;

        File restoreDir = new File(backupDir, "restore_" + System.currentTimeMillis());

        try {
            java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(backup);
            zipFile.stream().forEach(entry -> {
                try {
                    File destFile = new File(restoreDir, entry.getName());
                    if (entry.isDirectory()) {
                        destFile.mkdirs();
                    } else {
                        destFile.getParentFile().mkdirs();
                        Files.copy(zipFile.getInputStream(entry), destFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException ignored) {
                }
            });
            zipFile.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024)
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public record BackupInfo(String name, long timestamp, long size) {
        public String getFormattedSize() {
            if (size < 1024)
                return size + " B";
            if (size < 1024 * 1024)
                return String.format("%.1f KB", size / 1024.0);
            if (size < 1024 * 1024 * 1024)
                return String.format("%.1f MB", size / (1024.0 * 1024.0));
            return String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
        }

        public String getFormattedDate() {
            return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp), java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
    }

    public record BackupResult(boolean success, String fileName, long size, String error) {
    }
}
