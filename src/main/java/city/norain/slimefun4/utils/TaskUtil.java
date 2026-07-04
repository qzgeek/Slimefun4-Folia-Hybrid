package city.norain.slimefun4.utils;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

@UtilityClass
public class TaskUtil {
    /**
     * Check if the current thread is a Folia region tick thread
     * (on which block/entity operations are safe).
     */
    private static boolean isTickThread() {
        if (!Slimefun.isFolia()) {
            return Bukkit.isPrimaryThread();
        }
        String name = Thread.currentThread().getName();
        return name.contains("Region Scheduler") || name.contains("TickThread");
    }

    @SneakyThrows
    public void runSyncMethod(Runnable runnable) {
        if (isTickThread()) {
            runnable.run();
        } else {
            Slimefun.runSync(runnable);
        }
    }

    @SneakyThrows
    public <T> T runSyncMethod(@Nonnull Callable<T> callable) {
        // When no location is provided on Folia, try to execute directly
        // if we're on a tick thread; otherwise dispatch via runNextTick
        if (isTickThread()) {
            return callable.call();
        }
        if (!Slimefun.isFolia()) {
            return Bukkit.getScheduler()
                    .callSyncMethod(Slimefun.instance(), callable)
                    .get(5, TimeUnit.SECONDS);
        }
        final CompletableFuture<T> result = new CompletableFuture<>();
        Slimefun.getPlatformScheduler().runNextTick(task -> {
            try {
                result.complete(callable.call());
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    @SneakyThrows
    public <T> T runSyncMethod(@Nonnull Callable<T> callable, @Nonnull Location l) {
        // On Folia: if we're on a region tick thread, execute directly
        // to avoid dispatching to ourselves and deadlocking
        if (Slimefun.isFolia() && isTickThread()) {
            return callable.call();
        }
        if (!Slimefun.isFolia()) {
            return Bukkit.isPrimaryThread()
                    ? callable.call()
                    : Bukkit.getScheduler()
                            .callSyncMethod(Slimefun.instance(), callable)
                            .get(5, TimeUnit.SECONDS);
        }
        final CompletableFuture<T> result = new CompletableFuture<>();
        Slimefun.getPlatformScheduler().runAtLocation(l, task -> {
            try {
                result.complete(callable.call());
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    @SneakyThrows
    public <T> T runSyncMethod(@Nonnull Callable<T> callable, @Nonnull Entity entity) {
        if (Slimefun.isFolia() && isTickThread()) {
            return callable.call();
        }
        if (!Slimefun.isFolia()) {
            return Bukkit.isPrimaryThread()
                    ? callable.call()
                    : Bukkit.getScheduler()
                            .callSyncMethod(Slimefun.instance(), callable)
                            .get(5, TimeUnit.SECONDS);
        }
        final CompletableFuture<T> result = new CompletableFuture<>();
        Slimefun.getPlatformScheduler().runAtEntity(entity, task -> {
            try {
                result.complete(callable.call());
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }
}
