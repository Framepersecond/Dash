package dash;

import dash.data.GuardianDataManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public class GuardianBukkitListener implements Listener {
    private final GuardianDataManager dataManager;

    public GuardianBukkitListener(GuardianDataManager dataManager) {
        this.dataManager = dataManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!FeatureFlags.enabled("guardian")) return;
        Block block = event.getBlock();
        Material material = block.getType();
        Player player = event.getPlayer();
        dataManager.logBlockActionAsync(
                player.getUniqueId().toString(),
                player.getName(),
                GuardianDataManager.ACTION_BREAK,
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ(),
                material.name(),
                material.name(),
                "dash");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!FeatureFlags.enabled("guardian")) return;
        Block block = event.getBlockPlaced();
        Player player = event.getPlayer();
        dataManager.logBlockActionAsync(
                player.getUniqueId().toString(),
                player.getName(),
                GuardianDataManager.ACTION_PLACE,
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ(),
                block.getType().name(),
                null,
                "dash");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!FeatureFlags.enabled("guardian")) return;
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory topInventory = event.getView().getTopInventory();
        Inventory clickedInventory = event.getClickedInventory();
        InventoryHolder holder = topInventory.getHolder();
        if (holder == null) {
            return;
        }
        Location location = getCanonicalLocation(holder);
        if (location == null || location.getWorld() == null) {
            return;
        }

        ItemStack cursorItem = event.getCursor();
        ItemStack currentItem = event.getCurrentItem();
        if (clickedInventory == topInventory && isRealItem(cursorItem)
                && !isRealItem(currentItem)) {
            logContainer(player, location, GuardianDataManager.CONTAINER_ACTION_ADD, cursorItem);
        } else if (event.isShiftClick() && clickedInventory != topInventory && clickedInventory != null
                && isRealItem(currentItem)) {
            logContainer(player, location, GuardianDataManager.CONTAINER_ACTION_ADD, currentItem);
        } else if (clickedInventory == topInventory && isRealItem(cursorItem)
                && isRealItem(currentItem) && cursorItem.isSimilar(currentItem)) {
            logContainer(player, location, GuardianDataManager.CONTAINER_ACTION_ADD, cursorItem);
        } else if (clickedInventory == topInventory && isRealItem(currentItem)
                && !isRealItem(cursorItem)) {
            logContainer(player, location, GuardianDataManager.CONTAINER_ACTION_REMOVE, currentItem);
        } else if (event.isShiftClick() && clickedInventory == topInventory && isRealItem(currentItem)) {
            logContainer(player, location, GuardianDataManager.CONTAINER_ACTION_REMOVE, currentItem);
        } else if (clickedInventory == topInventory && isRealItem(cursorItem)
                && isRealItem(currentItem) && !cursorItem.isSimilar(currentItem)) {
            logContainer(player, location, GuardianDataManager.CONTAINER_ACTION_REMOVE, currentItem);
            logContainer(player, location, GuardianDataManager.CONTAINER_ACTION_ADD, cursorItem);
        } else if (event.getAction().name().contains("HOTBAR")
                && clickedInventory == topInventory && isRealItem(currentItem)) {
            ItemStack hotbarItem = event.getHotbarButton() >= 0
                    ? player.getInventory().getItem(event.getHotbarButton())
                    : null;
            logContainer(player, location, GuardianDataManager.CONTAINER_ACTION_REMOVE, currentItem);
            if (isRealItem(hotbarItem)) {
                logContainer(player, location, GuardianDataManager.CONTAINER_ACTION_ADD, hotbarItem);
            }
        }
    }

    private void logContainer(Player player, Location location, int action, ItemStack item) {
        if (!isRealItem(item)) {
            return;
        }
        dataManager.logContainerActionAsync(
                player.getUniqueId().toString(),
                player.getName(),
                action,
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                item.getType().name(),
                item.getAmount(),
                "dash");
    }

    private static boolean isRealItem(ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.getAmount() > 0;
    }

    private static Location getCanonicalLocation(InventoryHolder holder) {
        if (holder instanceof DoubleChest doubleChest) {
            InventoryHolder leftHolder = doubleChest.getLeftSide();
            InventoryHolder rightHolder = doubleChest.getRightSide();
            if (leftHolder instanceof BlockState left && rightHolder instanceof BlockState right) {
                Location leftLoc = left.getLocation();
                Location rightLoc = right.getLocation();
                if (leftLoc.getBlockX() < rightLoc.getBlockX()
                        || (leftLoc.getBlockX() == rightLoc.getBlockX() && leftLoc.getBlockZ() <= rightLoc.getBlockZ())) {
                    return leftLoc;
                }
                return rightLoc;
            }
            return null;
        }
        if (holder instanceof Chest chest && chest.getInventory().getHolder() instanceof DoubleChest doubleChest) {
            return getCanonicalLocation(doubleChest);
        }
        if (holder instanceof BlockState state) {
            return state.getLocation();
        }
        return holder.getInventory() == null ? null : holder.getInventory().getLocation();
    }
}
