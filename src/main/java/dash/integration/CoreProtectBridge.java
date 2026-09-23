package dash.integration;

import dash.data.GuardianDataManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class CoreProtectBridge {
    private static final List<Integer> BLOCK_ACTIONS = List.of(0, 1);
    private final Plugin owner;

    public CoreProtectBridge(Plugin owner) {
        this.owner = owner;
    }

    public Status status() {
        Object api = getApi();
        if (api == null) {
            return new Status(false, 0, "CoreProtect is not installed or its API is unavailable.");
        }
        int version = apiVersion(api);
        boolean enabled = apiEnabled(api);
        return new Status(enabled, version,
                enabled ? "CoreProtect API v" + version + " is available." : "CoreProtect API is disabled.");
    }

    public ImportResult importRecent(GuardianDataManager guardian, int seconds, int limit, String playerName) {
        Object api = getApi();
        if (api == null || !apiEnabled(api)) {
            return new ImportResult(false, 0, 0, "CoreProtect API unavailable.");
        }
        int safeSeconds = Math.max(60, Math.min(seconds, 60 * 60 * 24 * 90));
        int safeLimit = Math.max(1, Math.min(limit, 10000));
        String user = playerName == null || playerName.isBlank() ? null : playerName.trim();
        int blocks = importBlockLookup(api, guardian, safeSeconds, safeLimit, user);
        int containers = importContainerLookup(api, guardian, safeSeconds, safeLimit, user);
        return new ImportResult(true, blocks, containers,
                "Imported " + blocks + " block rows and " + containers + " container rows from CoreProtect.");
    }

    private int importBlockLookup(Object api, GuardianDataManager guardian, int seconds, int limit, String playerName) {
        try {
            Method performLookup = api.getClass().getMethod("performLookup", int.class, List.class, List.class,
                    List.class, List.class, List.class, int.class, Location.class);
            List<String> users = playerName == null ? null : List.of(playerName);
            Object rows = performLookup.invoke(api, seconds, users, null, null, null, BLOCK_ACTIONS, 0, null);
            if (!(rows instanceof List<?> list)) {
                return 0;
            }
            Method parseResult = api.getClass().getMethod("parseResult", String[].class);
            int imported = 0;
            for (Object row : list) {
                if (imported >= limit) break;
                if (!(row instanceof String[] result)) continue;
                Object parsed = parseResult.invoke(api, (Object) result);
                Integer actionId = intValue(parsed, "getActionId");
                if (actionId == null || (actionId != 0 && actionId != 1)) continue;
                long timestamp = longValue(parsed, "getTimestamp", System.currentTimeMillis() / 1000L);
                String player = stringValue(parsed, "getPlayer", "CoreProtect");
                String world = stringValue(parsed, "worldName", "world");
                int x = intValue(parsed, "getX", 0);
                int y = intValue(parsed, "getY", 0);
                int z = intValue(parsed, "getZ", 0);
                String type = materialName(value(parsed, "getType"));
                if (type.isBlank()) continue;
                boolean inserted = guardian.importBlockLog(timestamp, null, player, actionId, world, x, y, z,
                        type, actionId == GuardianDataManager.ACTION_BREAK ? type : null,
                        "coreprotect", "block:" + timestamp + ":" + player + ":" + world + ":" + x + ":" + y + ":" + z + ":" + type);
                if (inserted) imported++;
            }
            return imported;
        } catch (NoSuchMethodException e) {
            owner.getLogger().warning("[Guardian] CoreProtect legacy lookup API is not available: " + e.getMessage());
        } catch (Exception e) {
            owner.getLogger().warning("[Guardian] CoreProtect block import failed: " + e.getMessage());
        }
        return 0;
    }

    private int importContainerLookup(Object api, GuardianDataManager guardian, int seconds, int limit, String playerName) {
        try {
            Class<?> lookupOptionsClass = Class.forName("net.coreprotect.model.LookupOptions");
            Object builder = lookupOptionsClass.getMethod("builder").invoke(null);
            invokeIfPresent(builder, "time", new Class<?>[] { int.class }, seconds);
            invokeIfPresent(builder, "limit", new Class<?>[] { int.class, int.class }, 0, limit);
            if (playerName != null && !playerName.isBlank()) {
                invokeIfPresent(builder, "user", new Class<?>[] { String.class }, playerName);
            }
            Object options = builder.getClass().getMethod("build").invoke(builder);
            Method containerLookup = api.getClass().getMethod("containerLookup", lookupOptionsClass);
            Object rows = containerLookup.invoke(api, options);
            if (!(rows instanceof List<?> list)) {
                return 0;
            }
            int imported = 0;
            for (Object row : list) {
                if (imported >= limit) break;
                Integer actionId = intValue(row, "actionId");
                if (actionId == null || (actionId != 0 && actionId != 1)) continue;
                long timestamp = longValue(row, "timestamp", System.currentTimeMillis() / 1000L);
                String player = stringValue(row, "user", "CoreProtect");
                String world = stringValue(row, "worldName", "world");
                int x = intValue(row, "x", 0);
                int y = intValue(row, "y", 0);
                int z = intValue(row, "z", 0);
                String type = materialName(value(row, "type"));
                int amount = Math.max(1, intValue(row, "amount", 1));
                boolean inserted = guardian.importContainerLog(timestamp, null, player, actionId, world, x, y, z,
                        type, amount, "coreprotect",
                        "container:" + timestamp + ":" + player + ":" + world + ":" + x + ":" + y + ":" + z + ":" + type + ":" + amount);
                if (inserted) imported++;
            }
            return imported;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return 0;
        } catch (Exception e) {
            owner.getLogger().warning("[Guardian] CoreProtect container import failed: " + e.getMessage());
            return 0;
        }
    }

    private Object getApi() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (plugin == null) {
            return null;
        }
        try {
            Method getApi = plugin.getClass().getMethod("getAPI");
            return getApi.invoke(plugin);
        } catch (Exception e) {
            owner.getLogger().warning("[Guardian] Could not access CoreProtect API: " + e.getMessage());
            return null;
        }
    }

    private boolean apiEnabled(Object api) {
        Boolean test = boolValue(api, "testAPI");
        if (test != null) return test;
        Boolean enabled = boolValue(api, "isEnabled");
        if (enabled != null) return enabled;
        return true;
    }

    private int apiVersion(Object api) {
        Integer version = intValue(api, "APIVersion");
        return version == null ? 0 : version;
    }

    private static void invokeIfPresent(Object target, String name, Class<?>[] types, Object... args) {
        try {
            target.getClass().getMethod(name, types).invoke(target, args);
        } catch (Exception ignored) {
        }
    }

    private static Object value(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            try {
                return target.getClass().getMethod(name).invoke(target);
            } catch (Exception ignored) {
            }
            try {
                var field = target.getClass().getField(name);
                return field.get(target);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String stringValue(Object target, String name, String fallback) {
        Object value = value(target, name);
        return value == null ? fallback : String.valueOf(value);
    }

    private static Integer intValue(Object target, String name) {
        Object value = value(target, name);
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static int intValue(Object target, String name, int fallback) {
        Integer value = intValue(target, name);
        return value == null ? fallback : value;
    }

    private static Long longValue(Object target, String name) {
        Object value = value(target, name);
        if (value instanceof Number number) return number.longValue();
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static long longValue(Object target, String name, long fallback) {
        Long value = longValue(target, name);
        return value == null ? fallback : value;
    }

    private static Boolean boolValue(Object target, String name) {
        Object value = value(target, name);
        return value instanceof Boolean bool ? bool : null;
    }

    private static String materialName(Object material) {
        if (material == null) return "";
        if (material instanceof Enum<?> enumValue) return enumValue.name();
        String text = String.valueOf(material);
        int lastDot = text.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < text.length()) {
            text = text.substring(lastDot + 1);
        }
        return text.trim().replace(':', '_').toUpperCase(Locale.ROOT);
    }

    public record Status(boolean available, int apiVersion, String message) {
    }

    public record ImportResult(boolean success, int blocksImported, int containersImported, String message) {
    }
}
