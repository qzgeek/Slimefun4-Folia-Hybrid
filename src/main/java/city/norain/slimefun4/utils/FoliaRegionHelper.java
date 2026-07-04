package city.norain.slimefun4.utils;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import javax.annotation.Nonnull;
import lombok.experimental.UtilityClass;
import org.bukkit.Location;

/**
 * Folia 区域检测工具。
 *
 * Folia 将世界划分为独立区域，每个区域由专用线程管理。
 * 同一区域内的方块/实体操作安全；跨区域操作需要调度。
 *
 * @author qzgeek
 */
@UtilityClass
public class FoliaRegionHelper {

    /**
     * 检测两个位置是否在同一个 Folia 区域。
     * 非 Folia 环境始终返回 true。
     */
    public static boolean isSameRegion(@Nonnull Location a, @Nonnull Location b) {
        if (!Slimefun.isFolia()) {
            return true;
        }
        if (!a.getWorld().equals(b.getWorld())) {
            return false;
        }
        // Folia 区域基于 chunk 坐标分组。
        // 使用与 Folia 内部一致的 32-chunk 分组 (对应默认的 region size)。
        int regionAX = a.getBlockX() >> 9;  // 512 blocks = 32 chunks
        int regionAZ = a.getBlockZ() >> 9;
        int regionBX = b.getBlockX() >> 9;
        int regionBZ = b.getBlockZ() >> 9;
        return regionAX == regionBX && regionAZ == regionBZ;
    }

    /**
     * 获取位置的区域 key，用于 Worker 路由。
     */
    @Nonnull
    public static String getRegionKey(@Nonnull Location loc) {
        int rx = loc.getBlockX() >> 9;
        int rz = loc.getBlockZ() >> 9;
        return loc.getWorld().getName() + ":" + rx + ":" + rz;
    }

    /**
     * 检测当前线程是否拥有指定位置所在区域。
     * 用于判断是否可以直接操作方块。
     */
    @SuppressWarnings("deprecation")
    public static boolean isOwnedByCurrentThread(@Nonnull Location loc) {
        if (!Slimefun.isFolia()) {
            return true;
        }
        try {
            return Slimefun.getFoliaLib().getScheduler().isOwnedByCurrentRegion(loc);
        } catch (Exception e) {
            // Fallback: check thread name (belt and suspenders)
            String name = Thread.currentThread().getName();
            if (name.contains("Region Scheduler") || name.contains("TickThread")) {
                return isSameRegion(loc, loc); // always true
            }
            return false;
        }
    }
}
