package io.github.thebusybiscuit.slimefun4.core.networks.cargo;

import city.norain.slimefun4.utils.FoliaRegionHelper;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.bakedlibs.dough.blocks.BlockPosition;
import io.github.thebusybiscuit.slimefun4.api.items.ItemSpawnReason;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.virtual.VirtualItemHandler.InventoryContext;
import io.github.thebusybiscuit.slimefun4.core.networks.NetworkManager;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.SlimefunItems;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import io.github.thebusybiscuit.slimefun4.utils.itemstack.ItemStackWrapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import me.mrCookieSlime.Slimefun.api.inventory.DirtyChestMenu;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The {@link CargoNetworkTask} is responsible for moving {@link ItemStack ItemStacks}
 * around the {@link CargoNet}.
 *
 * <h3>Folia 跨区域支持</h3>
 * 跨区域传输使用火焰遗弃 (fire-and-forget) 模式：在输入端所在的线程抽取物品，
 * 然后将插入操作派发到输出端所在的区域线程，不等待结果。
 * 如果目标未加载则安全跳过。不阻塞任何区域线程。
 *
 * @author TheBusyBiscuit
 * @author qzgeek (Folia 跨区域改造)
 */
class CargoNetworkTask implements Runnable {

    private final NetworkManager manager;
    private final CargoNet network;
    private final Map<Location, Inventory> inventories = new HashMap<>();

    private final Map<Location, Integer> inputs;
    private final Map<Integer, List<Location>> outputs;

    @ParametersAreNonnullByDefault
    CargoNetworkTask(CargoNet network, Map<Location, Integer> inputs, Map<Integer, List<Location>> outputs) {
        this.network = network;
        this.manager = Slimefun.getNetworkManager();
        this.inputs = inputs;
        this.outputs = outputs;
    }

    @Override
    public void run() {
        long timestamp = System.nanoTime();

        try {
            SlimefunItem itemType = SlimefunItems.CARGO_INPUT_NODE.getItem();
            for (Map.Entry<Location, Integer> entry : inputs.entrySet()) {
                long nodeTimestamp = System.nanoTime();
                Location input = entry.getKey();

                boolean inputCrossRegion = Slimefun.isFolia()
                        && !FoliaRegionHelper.isSameRegion(network.getRegulator(), input);

                if (inputCrossRegion) {
                    processCrossRegionInput(input, entry.getValue());
                } else {
                    Optional<Block> attachedBlock = network.getAttachedBlock(input);
                    attachedBlock.ifPresent(block -> routeItems(input, block, entry.getValue()));
                }

                timestamp += Slimefun.getProfiler().closeEntry(entry.getKey(), itemType, nodeTimestamp);
            }
        } catch (Exception | LinkageError x) {
            Slimefun.logger()
                    .log(Level.SEVERE, x,
                            () -> "货网 tick 异常 @ " + new BlockPosition(network.getRegulator()));
        }

        Slimefun.getProfiler().closeEntry(network.getRegulator(), SlimefunItems.CARGO_MANAGER.getItem(), timestamp);
    }

    /**
     * 跨区域输入：派发整个 routeItems 到输入节点所在的区域线程。
     * 火焰遗弃，不等待结果。
     */
    private void processCrossRegionInput(Location input, int frequency) {
        World world = input.getWorld();
        if (world == null) return;
        int cx = input.getBlockX() >> 4, cz = input.getBlockZ() >> 4;
        if (!world.isChunkLoaded(cx, cz)) return;

        Slimefun.runSyncAtLocation(() -> {
            try {
                Optional<Block> attachedBlock = network.getAttachedBlock(input);
                if (attachedBlock.isPresent()) {
                    routeItems(input, attachedBlock.get(), frequency);
                }
            } catch (Exception e) {
                Slimefun.logger().log(Level.WARNING,
                        "货网跨区域输入异常 @ {0}", new BlockPosition(input));
            }
        }, input);
    }

    @ParametersAreNonnullByDefault
    private void routeItems(Location inputNode, Block inputTarget, int frequency) {
        List<Location> destinations = outputs.get(frequency);
        if (destinations == null || destinations.isEmpty()) return;

        ItemStackAndInteger slot = CargoUtils.withdraw(network, inventories, inputNode.getBlock(), inputTarget);
        if (slot == null) return;

        ItemStack stack = slot.getItem();
        int previousSlot = slot.getInt();
        stack = distributeItem(stack, inputNode, destinations);

        if (stack != null) {
            insertItem(inputTarget, previousSlot, stack);
        }
    }

    @Nullable @ParametersAreNonnullByDefault
    private ItemStack distributeItem(ItemStack stack, Location inputNode, List<Location> outputNodes) {
        ItemStack item = stack;

        var blockData = StorageCacheUtils.getBlock(inputNode);
        boolean roundrobin = blockData != null && "true".equals(blockData.getData("round-robin"));
        boolean smartFill = blockData != null && "true".equals(blockData.getData("smart-fill"));

        int index = network.roundRobin.getOrDefault(inputNode, 0);
        Deque<Location> tempDests = new ArrayDeque<>(outputNodes);
        if (roundrobin) {
            for (int i = 0; i < index && i < tempDests.size(); i++) {
                tempDests.addLast(tempDests.removeFirst());
            }
        }

        int outIdx = 0;
        for (Location output : tempDests) {
            boolean crossRegion = Slimefun.isFolia()
                    && !FoliaRegionHelper.isSameRegion(inputNode, output);

            if (crossRegion) {
                // 跨区域：火焰遗弃，不等待
                fireAndForgetInsert(item.clone(), output, smartFill);
                return null;
            }

            Optional<Block> target = network.getAttachedBlock(output);
            if (target.isEmpty()) { outIdx++; continue; }

            ItemStackWrapper wrapper = ItemStackWrapper.wrap(item);
            item = CargoUtils.insert(network, inventories, output.getBlock(), target.get(), smartFill, item, wrapper);

            if (item == null) {
                if (roundrobin) network.roundRobin.put(inputNode, (outIdx + 1) % outputNodes.size());
                return null;
            }
            outIdx++;
        }
        return item;
    }

    /**
     * 跨区域插入：派发到目标区域线程执行，火焰遗弃不等待。
     * 目标未加载时静默跳过，物品掉落在输入端附近。
     */
    private void fireAndForgetInsert(ItemStack item, Location output, boolean smartFill) {
        World world = output.getWorld();
        if (world == null) { dropAtSource(item, null); return; }

        int cx = output.getBlockX() >> 4, cz = output.getBlockZ() >> 4;
        if (!world.isChunkLoaded(cx, cz)) { dropAtSource(item, null); return; }

        Slimefun.runSyncAtLocation(() -> {
            try {
                Optional<Block> target = network.getAttachedBlock(output);
                if (target.isEmpty()) { dropAtSource(item, output); return; }

                ItemStackWrapper wrapper = ItemStackWrapper.wrap(item);
                ItemStack result = CargoUtils.insert(
                        network, inventories, output.getBlock(), target.get(), smartFill, item, wrapper);

                if (result != null && !manager.isItemDeletionEnabled()) {
                    output.getWorld().dropItemNaturally(output, result);
                }
            } catch (Exception e) {
                Slimefun.logger().log(Level.WARNING, "货网跨区域插入异常 @ {0}", new BlockPosition(output));
                dropAtSource(item, output);
            }
        }, output);
    }

    private void dropAtSource(ItemStack item, @Nullable Location target) {
        if (manager.isItemDeletionEnabled()) return;
        try {
            network.getRegulator().getWorld()
                    .dropItemNaturally(network.getRegulator(), item);
        } catch (Exception ignored) {}
    }

    @ParametersAreNonnullByDefault
    private void insertItem(Block inputTarget, int previousSlot, ItemStack item) {
        Inventory inv = inventories.get(inputTarget.getLocation());
        if (inv != null) {
            ItemStack rest;
            if (inv.getItem(previousSlot) == null) {
                rest = Slimefun.getItemStackService().addItem(inv, item, InventoryContext.CARGO_INSERT, previousSlot);
                if (rest != null) rest = Slimefun.getItemStackService().addItem(inv, rest, InventoryContext.CARGO_INSERT);
            } else {
                rest = Slimefun.getItemStackService().addItem(inv, item, InventoryContext.CARGO_INSERT);
            }
            if (rest != null && !manager.isItemDeletionEnabled()) {
                SlimefunUtils.spawnItem(inputTarget.getLocation().add(0, 1, 0), rest, ItemSpawnReason.CARGO_OVERFLOW);
            }
        } else {
            DirtyChestMenu menu = CargoUtils.getChestMenu(inputTarget);
            if (menu != null) {
                if (menu.getItemInSlot(previousSlot) == null) {
                    menu.replaceExistingItem(previousSlot, item);
                } else if (!manager.isItemDeletionEnabled()) {
                    SlimefunUtils.spawnItem(inputTarget.getLocation().add(0, 1, 0), item, ItemSpawnReason.CARGO_OVERFLOW);
                }
            }
        }
    }
}
