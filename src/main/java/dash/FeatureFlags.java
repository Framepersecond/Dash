package dash;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FeatureFlags {
    public static final String BETA_KEY = "beta.enabled";
    public static final List<String> IDS = List.of(
            "tickets", "notifications", "graphs", "ai", "intelligence", "maintenance", "guardian");
    public static final Set<String> BETA_IDS = Set.of("ai", "intelligence", "maintenance", "guardian");

    private FeatureFlags() {
    }

    public static boolean enabled(String feature) {
        Dash dash = Dash.getInstance();
        if (dash == null) return false;
        String id = normalize(feature);
        if (!IDS.contains(id)) return true;
        if (BETA_IDS.contains(id) && !dash.getConfig().getBoolean(BETA_KEY, false)) return false;
        return dash.getConfig().getBoolean("features." + id + ".enabled", defaultEnabled(id));
    }

    public static boolean configured(String feature) {
        Dash dash = Dash.getInstance();
        String id = normalize(feature);
        return dash != null && dash.getConfig().getBoolean(
                "features." + id + ".enabled", defaultEnabled(id));
    }

    public static boolean betaEnabled() {
        Dash dash = Dash.getInstance();
        return dash != null && dash.getConfig().getBoolean(BETA_KEY, false);
    }

    public static void save(boolean beta, java.util.Map<String, Boolean> values) {
        Dash dash = Dash.getInstance();
        if (dash == null) return;
        dash.getConfig().set(BETA_KEY, beta);
        for (String id : IDS) {
            dash.getConfig().set("features." + id + ".enabled",
                    values != null && Boolean.TRUE.equals(values.get(id)));
        }
        dash.saveConfig();
    }

    public static void applySetupPreset(String requestedProfile, boolean betaOptIn) {
        Dash dash = Dash.getInstance();
        if (dash == null) return;
        String profile = switch (normalize(requestedProfile)) {
            case "minimal", "full" -> normalize(requestedProfile);
            default -> "normal";
        };
        dash.getConfig().set("setup.profile", profile);
        dash.getConfig().set(BETA_KEY, betaOptIn);
        boolean stableFeatures = !"minimal".equals(profile);
        for (String id : IDS) {
            boolean value = BETA_IDS.contains(id) ? betaOptIn : stableFeatures;
            dash.getConfig().set("features." + id + ".enabled", value);
        }
        dash.getConfig().set("features.advanced.enabled", "full".equals(profile));
        dash.saveConfig();
    }

    public static boolean pageVisibleForSetupProfile(String path) {
        Dash dash = Dash.getInstance();
        String profile = dash == null ? "full" : normalize(dash.getConfig().getString("setup.profile", "full"));
        String page = path == null ? "" : path.split("\\?", 2)[0];
        if ("full".equals(profile)) return true;
        Set<String> light = Set.of("/", "/console", "/players", "/files", "/plugins", "/settings", "/updates");
        if ("minimal".equals(profile)) return light.contains(page);
        return !Set.of("/plugin-browser", "/permissions", "/scheduled-tasks").contains(page);
    }

    public static String featureForPath(String path) {
        if (path == null) return null;
        if (path.startsWith("/api/ai")) return "ai";
        if (path.startsWith("/api/guardian")) return "guardian";
        if (path.startsWith("/maintenance/staff/")) return "tickets";
        if (path.startsWith("/maintenance/")) return "maintenance";
        if (path.startsWith("/notifications/")) return "notifications";
        if (path.startsWith("/graphs/")) return "graphs";
        return switch (path) {
            case "/staff", "/tickets", "/report" -> "tickets";
            case "/notifications" -> "notifications";
            case "/graphs" -> "graphs";
            case "/ai", "/doctor" -> "ai";
            case "/intelligence", "/status" -> "intelligence";
            case "/maintenance" -> "maintenance";
            case "/guardian" -> "guardian";
            default -> null;
        };
    }

    public static String featureForAction(String action) {
        String value = normalize(action);
        if (value.startsWith("intel_")) return "intelligence";
        if (value.startsWith("staff_")) return "tickets";
        if (value.startsWith("guardian_")) return "guardian";
        if (value.startsWith("ai_")) return "ai";
        return switch (value) {
            case "save_notification_settings", "test_notification" -> "notifications";
            case "spark_profile", "doctor_delete_crash", "doctor_mark_reviewed" -> "maintenance";
            default -> null;
        };
    }

    public static boolean defaultEnabled(String feature) {
        return !BETA_IDS.contains(normalize(feature));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
