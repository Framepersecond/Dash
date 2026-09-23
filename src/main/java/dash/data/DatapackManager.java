package dash.data;

import dash.security.DatapackSecurity;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DatapackManager {

    public static List<DatapackInfo> listDatapacks() {
        List<DatapackInfo> datapacks = new ArrayList<>();

        World mainWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (mainWorld == null)
            return datapacks;

        File worldFolder = mainWorld.getWorldFolder();
        File datapacksFolder = new File(worldFolder, "datapacks");

        if (!datapacksFolder.exists()) {
            datapacksFolder.mkdirs();
            return datapacks;
        }

        File[] files = datapacksFolder.listFiles();
        if (files == null)
            return datapacks;

        for (File file : files) {
            if (file.isDirectory()) {
                File mcmeta = new File(file, "pack.mcmeta");
                if (mcmeta.exists()) {
                    String description = readPackDescription(mcmeta);
                    datapacks.add(new DatapackInfo(file.getName(), description, true, false, file.length()));
                }
            } else if (file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                String description = readZipPackDescription(file);
                String packName = file.getName().substring(0, file.getName().length() - 4);
                datapacks.add(new DatapackInfo(packName, description, true, true, file.length()));
            }
        }

        return datapacks;
    }

    private static String readPackDescription(File mcmeta) {
        try {
            if (Files.size(mcmeta.toPath()) > 64 * 1024L) {
                return "Description unavailable";
            }
            String content = Files.readString(mcmeta.toPath());
            int descStart = content.indexOf("\"description\"");
            if (descStart == -1)
                return "No description";

            int colonPos = content.indexOf(":", descStart);
            int quoteStart = content.indexOf("\"", colonPos + 1);
            int quoteEnd = content.indexOf("\"", quoteStart + 1);

            if (quoteStart != -1 && quoteEnd != -1) {
                return content.substring(quoteStart + 1, quoteEnd);
            }
        } catch (Exception ignored) {
        }
        return "No description";
    }

    private static String readZipPackDescription(File zipFile) {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("pack.mcmeta") || entry.getName().endsWith("/pack.mcmeta")) {
                    byte[] metadata = zis.readNBytes(64 * 1024 + 1);
                    if (metadata.length > 64 * 1024) return "Description unavailable";
                    String content = new String(metadata, java.nio.charset.StandardCharsets.UTF_8);
                    int descStart = content.indexOf("\"description\"");
                    if (descStart != -1) {
                        int colonPos = content.indexOf(":", descStart);
                        int quoteStart = content.indexOf("\"", colonPos + 1);
                        int quoteEnd = content.indexOf("\"", quoteStart + 1);
                        if (quoteStart != -1 && quoteEnd != -1) {
                            return content.substring(quoteStart + 1, quoteEnd);
                        }
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return "No description";
    }

    public static boolean toggleDatapack(String name, boolean enable) {
        World mainWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (mainWorld == null)
            return false;

        try {
            Path target = DatapackSecurity.resolveInstalled(
                    new File(mainWorld.getWorldFolder(), "datapacks").toPath(), name);
            if (target == null) return false;
            String targetName = target.getFileName().toString();
            String command = enable
                    ? "datapack enable \"file/" + targetName + "\""
                    : "datapack disable \"file/" + targetName + "\"";
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean uploadDatapack(String fileName, byte[] data) {
        World mainWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (mainWorld == null)
            return false;

        File worldFolder = mainWorld.getWorldFolder();
        File datapacksFolder = new File(worldFolder, "datapacks");
        if (!datapacksFolder.exists())
            datapacksFolder.mkdirs();

        try {
            DatapackSecurity.validateArchive(data);
            String safeName = DatapackSecurity.sanitizeUploadName(fileName);
            DatapackSecurity.writeAtomically(datapacksFolder.toPath(), safeName, data);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "datapack list");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean deleteDatapack(String name) {
        World mainWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (mainWorld == null)
            return false;

        File worldFolder = mainWorld.getWorldFolder();
        File datapacksFolder = new File(worldFolder, "datapacks");

        try {
            Path target = DatapackSecurity.resolveInstalled(datapacksFolder.toPath(), name);
            if (target == null) return false;
            DatapackSecurity.deleteNoFollow(target);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    public static void reloadDatapacks() {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "datapack list");
    }

    public record DatapackInfo(String name, String description, boolean enabled, boolean isZip, long size) {
        public String getFormattedSize() {
            if (size < 1024)
                return size + " B";
            if (size < 1024 * 1024)
                return String.format("%.1f KB", size / 1024.0);
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        }
    }
}
