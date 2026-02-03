package dash;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class DashCommand implements CommandExecutor {

    private final Dash plugin;

    public DashCommand(Dash plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        if (args.length == 0 || !args[0].equalsIgnoreCase("register")) {
            player.sendMessage(Component.text("Usage: /dash register", NamedTextColor.RED));
            return true;
        }

        if (!player.hasPermission("dash.register")) {
            player.sendMessage(Component.text("You don't have permission to do this.", NamedTextColor.RED));
            return true;
        }

        RegistrationManager regManager = Dash.getRegistrationManager();
        String code = regManager.generateCode(player.getUniqueId().toString(), player.getName());
        int port = Dash.getWebPort();

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.AQUA));
        player.sendMessage(
                Component.text(" Dash Web Registration", NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
        player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.AQUA));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text(" Your registration code:", NamedTextColor.GRAY));
        player.sendMessage(Component.text(" " + code, NamedTextColor.GREEN).decorate(TextDecoration.BOLD));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text(" Web Panel: ", NamedTextColor.GRAY)
                .append(Component.text("http://localhost:" + port, NamedTextColor.AQUA)));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text(" This code expires in 5 minutes.", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.AQUA));
        player.sendMessage(Component.empty());

        WebActionLogger.log("REGISTER_CODE_GENERATED", "Player " + player.getName() + " generated registration code");

        return true;
    }
}
