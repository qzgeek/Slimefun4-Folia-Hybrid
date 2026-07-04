package city.norain.slimefun4.utils;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import org.bukkit.Location;

/**
 * 跨区域传输调度器。
 *
 * 管理每个 Folia 区域的 {@link RegionWorker}，
 * 接收 {@link TransferRequest} 并按区域路由。
 *
 * 同区域传输直接执行；跨区域传输通过两阶段提交处理。
 *
 * @author qzgeek
 */
public class TransferDispatcher {

    private static TransferDispatcher instance;

    private final Map<String, RegionWorker> workers = new ConcurrentHashMap<>();
    private final List<TransferRequest> inFlight = new CopyOnWriteArrayList<>();

    private TransferDispatcher() {}

    @Nonnull
    public static TransferDispatcher getInstance() {
        if (instance == null) {
            instance = new TransferDispatcher();
        }
        return instance;
    }

    /**
     * 获取所有 Worker (供 TransferTickTask 驱动)。
     */
    @Nonnull
    public Map<String, RegionWorker> getWorkers() {
        return workers;
    }

    /**
     * 提交传输请求。
     *
     * 同区域 → 直接执行 (原路径)
     * 跨区域 → 投递到源区域 Worker (prepare)，然后目标区域 Worker (commit)
     */
    public void submit(@Nonnull TransferRequest req) {
        if (!Slimefun.isFolia()
                || FoliaRegionHelper.isSameRegion(req.getSource(), req.getTarget())) {
            // Paper 环境或同区域：直接执行
            executeDirect(req);
            return;
        }

        inFlight.add(req);

        // 投递到源区域 Worker 执行阶段 1 (prepare)
        String sourceRegion = FoliaRegionHelper.getRegionKey(req.getSource());
        getOrCreateWorker(sourceRegion).enqueue(req);
    }

    /**
     * 当 prepare 成功后，将请求转发到目标区域的 Worker。
     */
    void forwardToTarget(@Nonnull TransferRequest req) {
        String targetRegion = FoliaRegionHelper.getRegionKey(
            getTargetLocation(req));
        getOrCreateWorker(targetRegion).enqueueCommit(req);
    }

    /**
     * 直接执行传输 (同区域或 Paper 环境)。
     */
    private void executeDirect(@Nonnull TransferRequest req) {
        try {
            if (req.prepare()) {
                if (!req.commit()) {
                    Slimefun.logger().log(Level.WARNING,
                        "同区域传输 commit 失败: {0}",
                        req.getSnapshot().getType());
                }
            }
        } catch (Exception e) {
            Slimefun.logger().log(Level.WARNING, "同区域传输异常", e);
        } finally {
            inFlight.remove(req);
        }
    }

    @Nonnull
    private RegionWorker getOrCreateWorker(@Nonnull String regionKey) {
        return workers.computeIfAbsent(regionKey, k -> {
            RegionWorker worker = new RegionWorker(k, this);
            worker.start();
            return worker;
        });
    }

    /**
     * Worker 处理完成后从 inFlight 移除。
     */
    void markComplete(@Nonnull TransferRequest req) {
        inFlight.remove(req);
    }

    /**
     * 获取所有在途传输 (用于调试)。
     */
    @Nonnull
    public List<TransferRequest> getInFlight() {
        return List.copyOf(inFlight);
    }

    /**
     * 关闭所有 Worker (插件卸载时调用)。
     */
    public void shutdown() {
        for (RegionWorker worker : workers.values()) {
            worker.halt();
        }
        workers.clear();
        inFlight.clear();
    }

    private Location getSourceLocation(TransferRequest req) {
        return req.getSource();
    }

    private Location getTargetLocation(TransferRequest req) {
        return req.getTarget();
    }

    /**
     * 区域 Worker —— 每个 Folia 区域一个实例。
     * 在对应区域的调度器上执行队列中的传输请求。
     */
    static class RegionWorker {
        private final String regionKey;
        private final TransferDispatcher dispatcher;
        private final Queue<TransferRequest> prepareQueue = new ConcurrentLinkedQueue<>();
        private final Queue<TransferRequest> commitQueue = new ConcurrentLinkedQueue<>();
        private volatile boolean halted = false;

        RegionWorker(@Nonnull String regionKey, @Nonnull TransferDispatcher dispatcher) {
            this.regionKey = regionKey;
            this.dispatcher = dispatcher;
        }

        void enqueue(@Nonnull TransferRequest req) {
            prepareQueue.add(req);
        }

        void enqueueCommit(@Nonnull TransferRequest req) {
            commitQueue.add(req);
        }

        void start() {
            // Worker 通过 Slimefun tick 系统驱动
            // 实际 tick 在 TransferTickTask 中调用
        }

        void halt() {
            halted = true;
            prepareQueue.clear();
            commitQueue.clear();
        }

        /**
         * 每个 tick 由 TransferTickTask 调用。
         * 处理 prepare 和 commit 队列。
         */
        void tick() {
            if (halted) return;

            // 处理准备队列 (阶段 1)
            TransferRequest req;
            while ((req = prepareQueue.poll()) != null) {
                try {
                    if (req.isTimedOut()) {
                        dispatcher.markComplete(req);
                        continue;
                    }
                    if (req.prepare()) {
                        // 成功: 转发到目标区域做 commit
                        dispatcher.forwardToTarget(req);
                    } else {
                        // 失败 (物品已被移动等): 丢弃
                        dispatcher.markComplete(req);
                    }
                } catch (Exception e) {
                    Slimefun.logger().log(Level.WARNING,
                        "RegionWorker[" + regionKey + "] prepare 异常", e);
                    try { req.rollback(); } catch (Exception ignored) {}
                    dispatcher.markComplete(req);
                }
            }

            // 处理提交队列 (阶段 2)
            while ((req = commitQueue.poll()) != null) {
                try {
                    if (req.isTimedOut()) {
                        req.rollback();
                        dispatcher.markComplete(req);
                        continue;
                    }
                    if (!req.commit()) {
                        req.rollback();
                    }
                } catch (Exception e) {
                    Slimefun.logger().log(Level.WARNING,
                        "RegionWorker[" + regionKey + "] commit 异常", e);
                    try { req.rollback(); } catch (Exception ignored) {}
                } finally {
                    dispatcher.markComplete(req);
                }
            }
        }
    }
}
