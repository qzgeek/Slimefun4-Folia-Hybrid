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
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            Slimefun.logger().log(Level.SEVERE, x,
                    () -> "货网 tick 异常 @ " + new BlockPosition(network.getRegulator()));
        }
        Slimefun.getProfiler().closeEntry(network.getRegulator(), SlimefunItems.CARGO_MANAGER.getItem(), timestamp);
    }

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
                Slimefun.logger().log(Level.WARNING, "货网跨区域输入异常 @ {0}", new BlockPosition(input));
            }
        }, input);
    }

    @ParametersAreNonnullByDefault
    private void routeItems(Location inputNode, Block inputTarget, int frequency) {
        List<Location> destinations = outputs.get(frequency);
        if (destinations == null || destinations.isEmpty()) return;

        // 检查所有输出节点：如有跨区域节点且目标未加载，跳过本次传输
        if (Slimefun.isFolia()) {
            boolean allUnloaded = true;
            for (Location dest : destinations) {
                if (FoliaRegionHelper.isSameRegion(inputNode, dest)) {
                    allUnloaded = false; break;
                }
                World w = dest.getWorld();
                if (w != null && w.isChunkLoaded(dest.getBlockX() >> 4, dest.getBlockZ() >> 4)) {
                    allUnloaded = false; break;
                }
            }
            if (allUnloaded) return;
        }

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
        Deque<Location> sorted = new ArrayDeque<>(outputNodes);
        if (roundrobin) {
            for (int i = 0; i < index && i < sorted.size(); i++)
                sorted.addLast(sorted.removeFirst());
        }

        int outIdx = 0;
        for (Location output : sorted) {
            boolean crossRegion = Slimefun.isFolia()
                    && !FoliaRegionHelper.isSameRegion(inputNode, output);
            World w = output.getWorld();
            if (w == null) { outIdx++; continue; }

            // 同区域：直接插入
            if (!crossRegion) {
                Optional<Block> target = network.getAttachedBlock(output);
                if (target.isEmpty()) { outIdx++; continue; }
                ItemStackWrapper wrapper = ItemStackWrapper.wrap(item);
                item = CargoUtils.insert(network, inventories,
                        output.getBlock(), target.get(), smartFill, item, wrapper);
                if (item == null) {
                    if (roundrobin) network.roundRobin.put(inputNode,
                            (outIdx + 1) % outputNodes.size());
                    return null;
                }
                outIdx++;
                continue;
            }

            // 跨区域：路由决策在管理器线程，仅派发 insert
            if (!w.isChunkLoaded(output.getBlockX() >> 4, output.getBlockZ() >> 4)) {
                outIdx++; continue;
            }

            // 用 filterCache 做路由决策（纯内存，不碰方块）
            ItemFilter filter = network.filterCache.get(output);
            if (filter != null && !filter.test(item)) { outIdx++; continue; }

            // 跨区域：火焰遗弃 + 退回保证，不阻塞管理器线程
            // 单线程 Folia：调度到同一线程下一 tick，不会死锁
            // 多线程 Folia：调度到目标区域线程，不同线程不阻塞
            final ItemStack toSend = item.clone();
            Slimefun.runSyncAtLocation(() -> {
                try {
                    Optional<Block> target = network.getAttachedBlock(output);
                    if (target.isEmpty()) { returnToSource(toSend, inputNode); return; }
                    ItemStackWrapper wrapper = ItemStackWrapper.wrap(toSend);
                    ItemStack remainder = CargoUtils.insert(network,
                            inventories, output.getBlock(), target.get(), smartFill, toSend, wrapper);
                    if (remainder != null) returnToSource(remainder, inputNode);
                } catch (Exception e) {
                    returnToSource(toSend, inputNode);
                }
            }, output);

            if (roundrobin) network.roundRobin.put(inputNode,
                    (outIdx + 1) % outputNodes.size());
            return null;
        }
        return item;
    }

    /**
     * 非阻塞归还物品到输入端。火焰遗弃，不等待结果。
     */
    private void returnToSource(ItemStack item, Location sourceInput) {
        if (manager.isItemDeletionEnabled()) return;
        Slimefun.runSyncAtLocation(() -> {
            try {
                Optional<Block> attached = network.getAttachedBlock(sourceInput);
                if (attached.isEmpty()) return;
                Inventory inv = inventories.get(sourceInput);
                if (inv != null) {
                    ItemStack rest = Slimefun.getItemStackService()
                        .addItem(inv, item, InventoryContext.CARGO_INSERT);
                    if (rest != null && !manager.isItemDeletionEnabled()) {
                        SlimefunUtils.spawnItem(sourceInput.clone().add(0, 1, 0),
                                rest, ItemSpawnReason.CARGO_OVERFLOW);
                    }
                }
            } catch (Exception e) {
                Slimefun.logger().log(Level.WARNING,
                        "货网跨区域归还异常 @ {0}", new BlockPosition(sourceInput));
            }
        }, sourceInput);
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
