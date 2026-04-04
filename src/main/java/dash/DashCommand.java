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

public class DashCommand implements CommandExecutor {

    private final Dash plugin;

    public DashCommand(Dash plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /dash register [user] [rank] | /dash update");
            return true;
        }

        if (args[0].equalsIgnoreCase("update")) {
            return handleUpdate(sender);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
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
