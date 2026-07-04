package city.norain.slimefun4.utils;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * 驱动所有 RegionWorker 的周期性 tick 任务。
 *
 * 每个 tick 遍历所有活跃的 Worker，处理其队列中的传输请求。
 * 仅在 Folia 环境运行；Paper 上不需要。
 *
 * @author qzgeek
 */
public class TransferTickTask implements Runnable {

    private static TransferTickTask instance;

    private TransferTickTask() {}

    public static void start() {
        if (!Slimefun.isFolia()) return;
        if (instance != null) return;
        instance = new TransferTickTask();
        Slimefun.getFoliaLib().getScheduler().runTimerAsync(instance, 20L, 1L);
        Slimefun.logger().info("[Folia] 跨区域传输调度器已启动");
    }

    public static void stop() {
        if (instance == null) return;
        TransferDispatcher.getInstance().shutdown();
        instance = null;
        Slimefun.logger().info("[Folia] 跨区域传输调度器已停止");
    }

    @Override
    public void run() {
        // 遍历所有 RegionWorker 并 tick
        for (var worker : TransferDispatcher.getInstance().getWorkers().values()) {
            worker.tick();
        }
    }
}
