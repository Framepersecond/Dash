package dash;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.Location;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FreezeManager implements Listener {

    private static final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();

    public static Set<UUID> getFrozenPlayers() {
        return frozenPlayers;
    }

    public static boolean isFrozen(UUID uuid) {
        return frozenPlayers.contains(uuid);
    }

    public static void freeze(UUID uuid) {
        frozenPlayers.add(uuid);
    }

    public static void unfreeze(UUID uuid) {
        frozenPlayers.remove(uuid);
    }

    public static boolean toggleFreeze(UUID uuid) {
        if (frozenPlayers.contains(uuid)) {
            frozenPlayers.remove(uuid);
            return false;
        } else {
            frozenPlayers.add(uuid);
            return true;
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(),
                        to.getPitch()));
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        frozenPlayers.remove(event.getPlayer().getUniqueId());
    }
}
