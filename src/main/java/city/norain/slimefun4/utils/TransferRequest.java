package city.norain.slimefun4.utils;

import io.github.thebusybiscuit.slimefun4.api.items.virtual.VirtualItemHandler;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 跨区域传输请求，支持两阶段提交。
 *
 * <pre>
 * PENDING → WITHDRAWN → INSERTED  (成功)
 * PENDING → CANCELLED             (物品已移动/容器不可用)
 * WITHDRAWN → ROLLED_BACK         (insert 失败, 归还物品)
 * </pre>
 *
 * 防刷物品：prepare 使用乐观锁 (再次确认物品还在再取走)
 * 防吞物品：rollback 优先归还原位, 其次其他空位, 最后掉落到地上
 *
 * @author qzgeek
 */
public class TransferRequest {

    enum State { PENDING, WITHDRAWN, INSERTED, CANCELLED, ROLLED_BACK }

    @Getter private final Location source;
    @Getter private final int sourceSlot;
    @Getter private final Location target;
    @Getter private final ItemStack snapshot;
    @Getter private State state = State.PENDING;

    private final long createdAt = System.nanoTime();
    private static final long TIMEOUT_NANOS = 10_000_000_000L;

    /** 源容器缓存 */
    @Getter private Inventory sourceInventory;
    /** 目标容器缓存 */
    @Getter private Inventory targetInventory;

    public TransferRequest(@Nonnull Location source, int sourceSlot,
                           @Nonnull Location target, @Nonnull ItemStack snapshot,
                           @Nonnull Inventory sourceInventory, @Nonnull Inventory targetInventory) {
        this.source = source;
        this.sourceSlot = sourceSlot;
        this.target = target;
        this.snapshot = snapshot.clone();
        this.sourceInventory = sourceInventory;
        this.targetInventory = targetInventory;
    }

    /**
     * 阶段 1：乐观取走。
     * 再次确认槽位物品与快照一致，然后移除。
     */
    public boolean prepare() {
        if (state != State.PENDING) return false;
        if (sourceInventory == null) { state = State.CANCELLED; return false; }

        ItemStack current = sourceInventory.getItem(sourceSlot);
        if (current == null || !current.isSimilar(snapshot)) {
            state = State.CANCELLED;
            return false;
        }

        sourceInventory.setItem(sourceSlot, null);
        state = State.WITHDRAWN;
        return true;
    }

    /**
     * 阶段 2：插入目标容器。
     */
    public boolean commit() {
        if (state != State.WITHDRAWN) return false;
        if (targetInventory == null) { rollback(); return false; }

        ItemStack remainder = Slimefun.getItemStackService()
            .addItem(targetInventory, snapshot.clone(),
                     VirtualItemHandler.InventoryContext.CARGO_INSERT);

        if (remainder != null) {
            rollback();
            return false;
        }

        state = State.INSERTED;
        return true;
    }

    /**
     * 回退：归还物品。优先原位 → 其他空位 → 掉落地上。
     */
    public void rollback() {
        if (state != State.WITHDRAWN) return;

        if (sourceInventory != null) {
            // 优先归还原位
            if (sourceInventory.getItem(sourceSlot) == null) {
                sourceInventory.setItem(sourceSlot, snapshot.clone());
                state = State.ROLLED_BACK;
                return;
            }
            // 尝试其他空位
            ItemStack remainder = Slimefun.getItemStackService()
                .addItem(sourceInventory, snapshot.clone(),
                         VirtualItemHandler.InventoryContext.CARGO_INSERT);
            if (remainder == null) {
                state = State.ROLLED_BACK;
                return;
            }
        }

        // 最后手段：掉落地面
        if (source != null && source.getWorld() != null) {
            source.getWorld().dropItemNaturally(source, snapshot.clone());
        }
        state = State.ROLLED_BACK;
    }

    public boolean isTimedOut() {
        return (System.nanoTime() - createdAt) > TIMEOUT_NANOS;
    }

    public boolean isSourceInRegion(@Nonnull String regionKey) {
        return FoliaRegionHelper.getRegionKey(source).equals(regionKey);
    }

    public boolean isTargetInRegion(@Nonnull String regionKey) {
        return FoliaRegionHelper.getRegionKey(target).equals(regionKey);
    }
}
