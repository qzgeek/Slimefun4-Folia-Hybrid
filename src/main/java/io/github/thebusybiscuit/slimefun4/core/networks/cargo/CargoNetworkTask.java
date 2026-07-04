package io.github.thebusybiscuit.slimefun4.core.networks.cargo;

import city.norain.slimefun4.utils.FoliaRegionHelper;
import city.norain.slimefun4.utils.TaskUtil;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import me.mrCookieSlime.Slimefun.api.inventory.DirtyChestMenu;
import org.bukkit.Bukkit;
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
 * 目标区域已加载时，通过 {@code runSyncAtLocation} 派发插入操作到目标区域线程；
 * 目标区域未加载时，跳过并记录警告日志。
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

    /** 跨区域操作超时（秒） */
    private static final int CROSS_REGION_TIMEOUT = 3;

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
            SlimefunItem inputNode = SlimefunItems.CARGO_INPUT_NODE.getItem();
            for (Map.Entry<Location, Integer> entry : inputs.entrySet()) {
                long nodeTimestamp = System.nanoTime();
                Location input = entry.getKey();

                // 输入节点跨区域检测
                boolean inputCrossRegion = Slimefun.isFolia()
                        && !FoliaRegionHelper.isSameRegion(network.getRegulator(), input);

                if (inputCrossRegion) {
                    // 在输入节点所在区域线程上处理
                    processInputInRegion(input, entry.getValue(), inputNode);
                } else {
                    Optional<Block> attachedBlock = network.getAttachedBlock(input);
                    attachedBlock.ifPresent(block -> routeItems(input, block, entry.getValue()));
                }

                timestamp += Slimefun.getProfiler().closeEntry(entry.getKey(), inputNode, nodeTimestamp);
            }
        } catch (Exception | LinkageError x) {
            Slimefun.logger()
                    .log(Level.SEVERE, x,
                            () -> "货网 tick 异常 @ " + new BlockPosition(network.getRegulator()));
        }

        Slimefun.getProfiler().closeEntry(network.getRegulator(), SlimefunItems.CARGO_MANAGER.getItem(), timestamp);
    }

    /**
     * 在输入节点的区域线程上处理跨区域输入。
     * 派发到目标区域执行 withdraw + distribute，然后异步等待结果。
     */
    private void processInputInRegion(Location input, int frequency, SlimefunItem inputNode) {
        World world = input.getWorld();
        if (world == null) return;

        int cx = input.getBlockX() >> 4;
        int cz = input.getBlockZ() >> 4;
        if (!world.isChunkLoaded(cx, cz)) return;

        try {
            CompletableFuture<Void> future = new CompletableFuture<>();
            Slimefun.runSyncAtLocation(() -> {
                try {
                    Optional<Block> attachedBlock = network.getAttachedBlock(input);
                    if (attachedBlock.isPresent()) {
                        routeItems(input, attachedBlock.get(), frequency);
                    }
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }, input);
            future.get(CROSS_REGION_TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception e) {
            Slimefun.logger().log(Level.WARNING,
                    "货网跨区域输入处理异常 @ {0}", new BlockPosition(input));
        }
    }

    @ParametersAreNonnullByDefault
    private void routeItems(Location inputNode, Block inputTarget, int frequency) {
        List<Location> destinations = outputs.get(frequency);
        if (destinations == null || destinations.isEmpty()) {
            return;
        }

        // 从输入容器取物品
        ItemStackAndInteger slot = CargoUtils.withdraw(network, inventories, inputNode.getBlock(), inputTarget);
        if (slot == null) {
            return;
        }

        ItemStack stack = slot.getItem();
        int previousSlot = slot.getInt();

        // 分发到输出节点
        stack = distributeItem(stack, inputNode, destinations);

        // 没塞进去的退回去
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
        Deque<Location> tempDestinations = new ArrayDeque<>(outputNodes);
        if (roundrobin) {
            for (int i = 0; i < index && i < tempDestinations.size(); i++) {
                tempDestinations.addLast(tempDestinations.removeFirst());
            }
        }

        int outputIndex = 0;
        for (Location output : tempDestinations) {
            boolean crossRegion = Slimefun.isFolia()
                    && !FoliaRegionHelper.isSameRegion(inputNode, output);

            if (crossRegion) {
                // 跨区域：不在此处访问方块，交给 insertCrossRegion 在目标线程处理
                item = insertCrossRegion(item, inputNode, output, smartFill);
                if (item == null) {
                    if (roundrobin) {
                        network.roundRobin.put(inputNode, (outputIndex + 1) % outputNodes.size());
                    }
                    return null;
                }
                outputIndex++;
                continue;
            }

            Optional<Block> target = network.getAttachedBlock(output);
            if (target.isEmpty()) {
                outputIndex++;
                continue;
            }

            ItemStackWrapper wrapper = ItemStackWrapper.wrap(item);
            item = CargoUtils.insert(network, inventories, output.getBlock(), target.get(), smartFill, item, wrapper);

            if (item == null) {
                if (roundrobin) {
                    network.roundRobin.put(inputNode, (outputIndex + 1) % outputNodes.size());
                }
                return null;
            }
            outputIndex++;
        }

        return item;
    }

    /**
     * 跨区域插入物品。
     *
     * 目标区域已加载：通过 runSyncAtLocation 派发到目标区域线程执行。
     * 在目标线程内获取 attachedBlock 和執行 insert，避免跨区域方块访问。
     * 目标区域未加载：静默跳过。
     */
    @Nullable
    private ItemStack insertCrossRegion(ItemStack item, Location inputNode, Location output, boolean smartFill) {
        World world = output.getWorld();
        if (world == null) return item;

        int chunkX = output.getBlockX() >> 4;
        int chunkZ = output.getBlockZ() >> 4;

        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return item;
        }

        try {
            ItemStackWrapper wrapper = ItemStackWrapper.wrap(item);

            boolean currentThreadOwnsTarget = false;
            try {
                currentThreadOwnsTarget = Slimefun.getFoliaLib()
                    .getScheduler().isOwnedByCurrentRegion(output);
            } catch (Exception ignored) {}

            if (currentThreadOwnsTarget) {
                // 当前线程拥有目标区域，直接执行（包含 getAttachedBlock）
                Optional<Block> target = network.getAttachedBlock(output);
                if (target.isEmpty()) return item;
                return CargoUtils.insert(network, inventories,
                    output.getBlock(), target.get(), smartFill, item, wrapper);
            }

            CompletableFuture<ItemStack> future = new CompletableFuture<>();
            Slimefun.runSyncAtLocation(() -> {
                try {
                    Optional<Block> target = network.getAttachedBlock(output);
                    if (target.isEmpty()) {
                        future.complete(item);
                        return;
                    }
                    ItemStack result = CargoUtils.insert(
                            network, inventories, output.getBlock(), target.get(), smartFill, item, wrapper);
                    future.complete(result);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }, output);

            return future.get(CROSS_REGION_TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception e) {
            Slimefun.logger().log(Level.WARNING, "货网跨区域传输异常, 物品退回: {0} @ {1}",
                    new Object[]{item.getType(), output});
            return item;
        }
    }

    @ParametersAreNonnullByDefault
    private void insertItem(Block inputTarget, int previousSlot, ItemStack item) {
        Inventory inv = inventories.get(inputTarget.getLocation());

        if (inv != null) {
            ItemStack rest;
            if (inv.getItem(previousSlot) == null) {
                rest = Slimefun.getItemStackService().addItem(inv, item, InventoryContext.CARGO_INSERT, previousSlot);
                if (rest != null) {
                    rest = Slimefun.getItemStackService().addItem(inv, rest, InventoryContext.CARGO_INSERT);
                }
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
                    SlimefunUtils.spawnItem(
                            inputTarget.getLocation().add(0, 1, 0), item, ItemSpawnReason.CARGO_OVERFLOW);
                }
            }
        }
    }
}
