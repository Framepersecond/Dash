package dash.guardian;

import dash.data.GuardianDataManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class GuardianActionService {
    private final JavaPlugin plugin;

    public GuardianActionService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public ActionResult rollback(GuardianDataManager guardian, ActionRequest request) {
        return run("rollback", guardian, request);
    }

    public ActionResult restore(GuardianDataManager guardian, ActionRequest request) {
        return run("restore", guardian, request);
    }

    private ActionResult run(String mode, GuardianDataManager guardian, ActionRequest request) {
        boolean restore = "restore".equalsIgnoreCase(mode);
        List<GuardianDataManager.BlockLogEntry> blocks = request.includeBlocks()
                ? guardian.searchBlockLogsAdvanced(request.player(), request.world(), request.fromTime(), null,
                        GuardianDataManager.parseBlockAction(request.action()), request.x(), request.y(), request.z(),
                        request.radius(), request.include(), request.exclude(), 1, request.limit(), restore)
                : List.of();
        List<GuardianDataManager.ContainerLogEntry> containers = request.includeContainers()
                ? guardian.searchContainerLogsAdvanced(request.player(), request.world(), request.fromTime(), null,
                        GuardianDataManager.parseContainerAction(request.action()), request.x(), request.y(),
                        request.z(), request.radius(), request.include(), request.exclude(), 1, request.limit(), restore)
                : List.of();
        if (request.preview()) {
            return new ActionResult(true, mode, true, blocks.size(), containers.size(), 0, 0, 0,
                    "Preview matched " + blocks.size() + " block rows and " + containers.size()
                            + " container rows.");
        }

        CompletableFuture<ActionResult> future = new CompletableFuture<>();
        Runnable task = () -> future.complete(apply(mode, blocks, containers));
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new ActionResult(false, mode, false, blocks.size(), containers.size(), 0, 0,
                    blocks.size() + containers.size(), "Guardian " + mode + " timed out: " + e.getMessage());
        }
    }

    private ActionResult apply(String mode, List<GuardianDataManager.BlockLogEntry> blocks,
            List<GuardianDataManager.ContainerLogEntry> containers) {
        boolean restore = "restore".equalsIgnoreCase(mode);
        int changedBlocks = 0;
        int changedContainers = 0;
        int skipped = 0;
        for (GuardianDataManager.BlockLogEntry row : blocks) {
            if (applyBlock(row, restore)) {
                changedBlocks++;
            } else {
                skipped++;
            }
        }
        for (GuardianDataManager.ContainerLogEntry row : containers) {
            int changed = applyContainer(row, restore);
            if (changed > 0) {
                changedContainers += changed;
            } else {
                skipped++;
            }
        }
        return new ActionResult(true, mode, false, blocks.size(), containers.size(), changedBlocks, changedContainers,
                skipped, "Guardian " + mode + " changed " + changedBlocks + " blocks and " + changedContainers
                        + " container items.");
    }

    private boolean applyBlock(GuardianDataManager.BlockLogEntry row, boolean restore) {
        World world = Bukkit.getWorld(row.world());
        if (world == null) {
            return false;
        }
        Material material;
        if (restore) {
            material = row.action() == GuardianDataManager.ACTION_PLACE ? material(row.blockType()) : Material.AIR;
        } else {
            material = row.action() == GuardianDataManager.ACTION_PLACE ? Material.AIR
                    : material(row.oldBlockType() == null ? row.blockType() : row.oldBlockType());
        }
        if (material == null) {
            return false;
        }
        Block block = world.getBlockAt(row.x(), row.y(), row.z());
        block.setType(material, false);
        return true;
    }

    private int applyContainer(GuardianDataManager.ContainerLogEntry row, boolean restore) {
        World world = Bukkit.getWorld(row.world());
        Material material = material(row.itemMaterial());
        if (world == null || material == null || material == Material.AIR || row.itemAmount() <= 0) {
            return 0;
        }
        Block block = world.getBlockAt(row.x(), row.y(), row.z());
        if (!(block.getState() instanceof InventoryHolder holder)) {
            return 0;
        }
        Inventory inventory = holder.getInventory();
        boolean add = restore
                ? row.action() == GuardianDataManager.CONTAINER_ACTION_ADD
                : row.action() == GuardianDataManager.CONTAINER_ACTION_REMOVE;
        return add ? addItem(inventory, material, row.itemAmount()) : removeItem(inventory, material, row.itemAmount());
    }

    private int addItem(Inventory inventory, Material material, int amount) {
        ItemStack stack = new ItemStack(material, amount);
        int leftover = inventory.addItem(stack).values().stream().mapToInt(ItemStack::getAmount).sum();
        return amount - leftover;
    }

    private int removeItem(Inventory inventory, Material material, int amount) {
        int remaining = amount;
        for (int i = 0; i < inventory.getSize() && remaining > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int take = Math.min(stack.getAmount(), remaining);
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() <= 0) {
                inventory.setItem(i, null);
            } else {
                inventory.setItem(i, stack);
            }
            remaining -= take;
        }
        return amount - remaining;
    }

    private Material material(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Material material = Material.matchMaterial(raw);
        if (material == null && raw.contains(":")) {
            material = Material.matchMaterial(raw.substring(raw.indexOf(':') + 1));
        }
        if (material == null) {
            material = Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
        }
        return material;
    }

    public record ActionRequest(String player, String world, Long fromTime, Integer x, Integer y, Integer z,
            Integer radius, String action, List<String> include, List<String> exclude, int limit, boolean preview,
            boolean includeBlocks, boolean includeContainers) {
    }

    public record ActionResult(boolean success, String mode, boolean preview, int matchedBlocks, int matchedContainers,
            int changedBlocks, int changedContainers, int skipped, String message) {
    }
}
