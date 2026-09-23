package dash;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import dash.web.PublicReportLinks;

public class DashCommand implements CommandExecutor {

    private final Dash plugin;

    public DashCommand(Dash plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /dash ticket | /dash report [player] | /dash ui | /dash register [user] [rank] | /dash update | /dash api");
            return true;
        }

        if (args[0].equalsIgnoreCase("update")) {
            return handleUpdate(sender);
        }

        // Key administration is console- and operator-only, and works from the
        // console too so a headless server can mint its first key.
        if (args[0].equalsIgnoreCase("api")) {
            return handleApi(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        if (args[0].equalsIgnoreCase("ticket") || args[0].equalsIgnoreCase("report")) {
            return openPlayerReportPage(player, args);
        }

        if (args[0].equalsIgnoreCase("ui")) {
            new DashIngameUi(plugin).open(player);
            return true;
        }

        if (!player.isOp()) {
            player.sendMessage(Component.text("Only OPs can use this command.", NamedTextColor.RED));
            return true;
        }

        if (!args[0].equalsIgnoreCase("register")) {
            player.sendMessage(Component.text("Usage: /dash register [user] [rank]", NamedTextColor.RED));
            return true;
        }

        RegistrationManager regManager = Dash.getRegistrationManager();

        if (args.length == 1) {
            String code = regManager.generateCode(player.getUniqueId().toString(), player.getName());
            String setupUrl = SetupNotifier.buildSetupUrl(plugin, player, code);

            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.AQUA));
            player.sendMessage(
                    Component.text(" Dash Web Registration", NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
            player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.AQUA));
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text(" Your registration code:", NamedTextColor.GRAY));
            player.sendMessage(Component.text(" " + code, NamedTextColor.GREEN).decorate(TextDecoration.BOLD));
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text(" Setup URL: ", NamedTextColor.GRAY)
                    .append(Component.text(setupUrl, NamedTextColor.AQUA)));
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text(" This code expires in 5 minutes.", NamedTextColor.YELLOW));
            player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.AQUA));
            player.sendMessage(Component.empty());

            WebActionLogger.log("REGISTER_CODE_GENERATED",
                    "Player " + player.getName() + " generated registration code");
            return true;
        }

        String targetName = args[1];
        String requestedRank = args.length >= 3 ? args[2] : "MODERATOR";
        String rank = requestedRank.toUpperCase(Locale.ROOT);
        String code;
        String setupUrl;

        if ("ADMIN".equals(rank)) {
            code = regManager.generateCode("UNBOUND", targetName, "ADMIN", List.of());
        } else {
            code = regManager.generateCode("UNBOUND", targetName, rank, List.of());
        }
        setupUrl = SetupNotifier.buildSetupUrl(plugin, player, code);

        Player onlineTarget = Bukkit.getPlayerExact(targetName);
        if (onlineTarget != null) {
            onlineTarget.sendMessage(Component.empty());
            onlineTarget.sendMessage(Component.text("[Dash] ", NamedTextColor.AQUA)
                    .append(Component.text("Du wurdest von " + player.getName()
                            + " in das Dash-Panel eingeladen! Dein Rang: " + rank
                            + ". Klicke hier, um dich zu registrieren.", NamedTextColor.YELLOW)
                            .decorate(TextDecoration.BOLD)
                            .clickEvent(ClickEvent.openUrl(setupUrl))));
            onlineTarget.sendMessage(Component.text("Setup URL: " + setupUrl, NamedTextColor.GRAY));
            onlineTarget.sendMessage(Component.text("Code expires in 5 minutes.", NamedTextColor.RED));
        }

        OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(targetName);
        player.sendMessage(Component.text("Invite generated for " + targetName + " (" + rank + ").",
                NamedTextColor.GREEN));
        player.sendMessage(Component.text("Code: " + code, NamedTextColor.AQUA));
        player.sendMessage(Component.text("Setup URL: " + setupUrl, NamedTextColor.AQUA));
        if (onlineTarget == null && offlineTarget.getName() == null) {
            player.sendMessage(Component.text("Target player not seen before; invite remains code-based.",
                    NamedTextColor.YELLOW));
        }

        WebActionLogger.log("REGISTER_CODE_GENERATED",
                "Player " + player.getName() + " generated invite for " + targetName + " role=" + rank);

        return true;
    }

    private boolean openPlayerReportPage(Player player, String[] args) {
        if (!FeatureFlags.enabled("tickets")) {
            player.sendMessage(Component.text("Tickets are currently disabled.", NamedTextColor.RED));
            return true;
        }
        boolean report = args[0].equalsIgnoreCase("report");
        String target = report && args.length >= 2 ? args[1].trim() : "";
        PublicReportLinks.CreatedLink link = PublicReportLinks.create(plugin.getDataFolder().toPath(),
                player.getName(), 30, target, report ? "player" : "other");
        if (!link.success()) {
            player.sendMessage(Component.text("The secure report page could not be created.", NamedTextColor.RED));
            return true;
        }
        String reportUrl = SetupNotifier.buildReportUrl(plugin, player, link.token());
        if (reportUrl.isBlank()) {
            player.sendMessage(Component.text(
                    "Set Public Report URL to this PaperDash HTTPS domain in Plugin Settings first.",
                    NamedTextColor.RED));
            return true;
        }
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("[Dash] Open secure report form", NamedTextColor.AQUA)
                .decorate(TextDecoration.BOLD).clickEvent(ClickEvent.openUrl(reportUrl)));
        player.sendMessage(Component.text(reportUrl, NamedTextColor.GRAY).clickEvent(ClickEvent.openUrl(reportUrl)));
        player.sendMessage(Component.text("This private one-time link expires in 30 minutes.", NamedTextColor.YELLOW));
        WebActionLogger.log("PUBLIC_REPORT_LINK_PLAYER", "reporter=" + player.getName()
                + (target.isBlank() ? "" : " target=" + target));
        return true;
    }

    /**
     * {@code /dash api create|list|revoke} -- administration for the read-only
     * HTTP API. Restricted to operators and the console; every action is
     * written to the audit log, and a new token is never echoed here (see
     * {@link dash.api.ApiKeyCommands}).
     */
    private boolean handleApi(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !sender.isOp()) {
            sender.sendMessage(Component.text("Only OPs can manage API keys.", NamedTextColor.RED));
            return true;
        }
        String actor = sender.getName();
        java.nio.file.Path dataFolder = plugin.getDataFolder().toPath();
        dash.api.ApiKeyStore store = Dash.getApiKeyStore();

        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        List<String> output;
        switch (action) {
            case "create" -> {
                if (args.length < 3) {
                    output = dash.api.ApiKeyCommands.usage();
                    break;
                }
                String scopes = args.length > 3 ? args[3] : "*";
                int days = 0;
                if (args.length > 4) {
                    try {
                        days = Integer.parseInt(args[4]);
                    } catch (NumberFormatException ignored) {
                        days = 0;
                    }
                }
                output = dash.api.ApiKeyCommands.create(store, dataFolder, args[2], scopes, days);
                WebActionLogger.log("api.key.create", "label=" + args[2] + " scopes=" + scopes
                        + " days=" + days + " by=" + actor);
            }
            case "list" -> output = dash.api.ApiKeyCommands.list(store);
            case "revoke" -> {
                if (args.length < 3) {
                    output = dash.api.ApiKeyCommands.usage();
                    break;
                }
                output = dash.api.ApiKeyCommands.revoke(store, args[2]);
                WebActionLogger.log("api.key.revoke", "id=" + args[2] + " by=" + actor);
            }
            default -> output = dash.api.ApiKeyCommands.usage();
        }
        for (String line : output) {
            sender.sendMessage(line);
        }
        return true;
    }

    private boolean handleUpdate(CommandSender sender) {
        boolean isConsole = sender instanceof ConsoleCommandSender;
        if (!isConsole && (!(sender instanceof Player player) || !player.isOp())) {
            sender.sendMessage(Component.text("Only OPs or console can use /dash update.", NamedTextColor.RED));
            return true;
        }

        GithubUpdater updater = Dash.getGithubUpdater();
        if (updater == null || !updater.isEnabled()) {
            sender.sendMessage(Component.text("Updater is currently unavailable. Check console logs for details.",
                    NamedTextColor.RED));
            return true;
        }

        sender.sendMessage(Component.text("Downloading Dash update...", NamedTextColor.YELLOW));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = updater.downloadUpdate();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ok) {
                    sender.sendMessage(Component.text(
                            "Update downloaded! Dash will be updated on the next server restart.",
                            NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text(
                            "Update download failed. Check console logs for updater errors.",
                            NamedTextColor.RED));
                }
            });
        });
        return true;
    }
}
