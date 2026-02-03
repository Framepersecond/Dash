package dash.data;

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
            } else if (file.getName().endsWith(".zip")) {
                String description = readZipPackDescription(file);
                String packName = file.getName().replace(".zip", "");
                datapacks.add(new DatapackInfo(packName, description, true, true, file.length()));
            }
        }

        return datapacks;
    }

    private static String readPackDescription(File mcmeta) {
        try {
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
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    String content = baos.toString();
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
            String command = enable
                    ? "datapack enable \"file/" + name + "\""
                    : "datapack disable \"file/" + name + "\"";
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

        fileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!fileName.endsWith(".zip")) {
            fileName += ".zip";
        }

        File targetFile = new File(datapacksFolder, fileName);

        try {
            Files.write(targetFile.toPath(), data);
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

        File folder = new File(datapacksFolder, name);
        File zip = new File(datapacksFolder, name + ".zip");

        boolean deleted = false;
        if (folder.exists() && folder.isDirectory()) {
            deleteRecursive(folder);
            deleted = true;
        }
        if (zip.exists()) {
            zip.delete();
            deleted = true;
        }

        return deleted;
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
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
