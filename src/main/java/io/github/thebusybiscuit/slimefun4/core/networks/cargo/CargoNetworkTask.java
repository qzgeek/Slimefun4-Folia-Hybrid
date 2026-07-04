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

        // Separate same-region and cross-region outputs
        java.util.ArrayList<Location> sameRegion = new java.util.ArrayList<>();
        java.util.ArrayList<Location> crossRegion = new java.util.ArrayList<>();
        for (Location output : outputNodes) {
            if (Slimefun.isFolia() && !FoliaRegionHelper.isSameRegion(inputNode, output)) {
                crossRegion.add(output);
            } else {
                sameRegion.add(output);
            }
        }

        int index = network.roundRobin.getOrDefault(inputNode, 0);
        Deque<Location> tempDests = new ArrayDeque<>(sameRegion);
        if (roundrobin) {
            for (int i = 0; i < index && i < tempDests.size(); i++)
                tempDests.addLast(tempDests.removeFirst());
        }

        // Phase 1: try same-region outputs (direct insert, can verify result)
        int outIdx = 0;
        for (Location output : tempDests) {
            Optional<Block> target = network.getAttachedBlock(output);
            if (target.isEmpty()) { outIdx++; continue; }

            ItemStackWrapper wrapper = ItemStackWrapper.wrap(item);
            item = CargoUtils.insert(network, inventories, output.getBlock(), target.get(), smartFill, item, wrapper);
            if (item == null) {
                if (roundrobin && outIdx < sameRegion.size())
                    network.roundRobin.put(inputNode, (outIdx + 1) % outputNodes.size());
                return null;
            }
            outIdx++;
        }

        // Phase 2: same-region full → try cross-region (同步等待)
        int crossIdx = 0;
        for (Location output : crossRegion) {
            World w = output.getWorld();
            if (w == null) continue;
            if (!w.isChunkLoaded(output.getBlockX() >> 4, output.getBlockZ() >> 4)) continue;

            // 多线程 Folia：manager 线程等 target 线程，不同线程不死锁
            // 单线程 Folia：runSyncAtLocation 内置 isOwnedByCurrentRegion 短路，直接执行
            final ItemStack toSend = item.clone();
            final int savedOutIdx = crossIdx;
            try {
                java.util.concurrent.CompletableFuture<ItemStack> future =
                    new java.util.concurrent.CompletableFuture<>();
                ItemStackWrapper wrapper = ItemStackWrapper.wrap(toSend);

                Slimefun.runSyncAtLocation(() -> {
                    try {
                        Optional<Block> target = network.getAttachedBlock(output);
                        if (target.isEmpty()) { future.complete(toSend); return; }
                        ItemStack remainder = CargoUtils.insert(
                                network, inventories, output.getBlock(), target.get(),
                                smartFill, toSend, wrapper);
                        future.complete(remainder);
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                }, output);

                ItemStack remainder = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
                if (remainder == null) {
                    // 插入成功
                    if (roundrobin) network.roundRobin.put(inputNode,
                            (sameRegion.size() + savedOutIdx + 1) % outputNodes.size());
                    return null;
                }
                // remainder != null → 输出拒绝/满 → 尝试下一个跨区域输出
                item = remainder;
            } catch (java.util.concurrent.TimeoutException e) {
                // 超时：物品退回输入端，不丢
                Slimefun.logger().log(Level.WARNING,
                        "货网跨区域传输超时 @ {0}", new BlockPosition(output));
                returnItemToSourceSync(inputNode, item);
                return null;
            } catch (Exception e) {
                returnItemToSourceSync(inputNode, item);
                return null;
            }
            crossIdx++;
        }

        return item;
    }

    /**
     * 同步归还物品到输入端（在当前线程上执行）。
     * 用于跨区域传输失败后的回退。
     */
    private void returnItemToSourceSync(Location sourceInput, ItemStack item) {
        if (manager.isItemDeletionEnabled()) return;
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
