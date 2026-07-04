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
    @SneakyThrows
    public void runSyncMethod(Runnable runnable) {
        if (!Slimefun.isFolia() && Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Slimefun.runSync(runnable);
        }
    }

    @SneakyThrows
    public <T> T runSyncMethod(@Nonnull Callable<T> callable) {
        return runSyncMethod(callable, null, null);
    }

    @SneakyThrows
    public <T> T runSyncMethod(@Nonnull Callable<T> callable, @Nonnull Location l) {
        return runSyncMethod(callable, l, null);
    }

    @SneakyThrows
    public <T> T runSyncMethod(@Nonnull Callable<T> callable, @Nonnull Entity entity) {
        return runSyncMethod(callable, null, entity);
    }

    @SneakyThrows
    private <T> T runSyncMethod(@Nonnull Callable<T> callable, @Nullable Location l, @Nullable Entity entity) {
        if (!Slimefun.isFolia()) {
            return Bukkit.isPrimaryThread()
                    ? callable.call()
                    : Bukkit.getScheduler()
                            .callSyncMethod(Slimefun.instance(), callable)
                            .get(5, TimeUnit.SECONDS);
        }

        // On Folia: check if we're already on the correct thread to avoid deadlock
        if (l != null) {
            try {
                if (Slimefun.getFoliaLib().getScheduler().isOwnedByCurrentRegion(l)) {
                    return callable.call();
                }
            } catch (Exception ignored) {
                // Fall through to dispatch
            }
            final CompletableFuture<T> result = new CompletableFuture<>();
            Slimefun.getPlatformScheduler().runAtLocation(l, task -> {
                try {
                    result.complete(callable.call());
                } catch (Exception e) {
                    result.completeExceptionally(e);
                }
            });
            return result.get(5, TimeUnit.SECONDS);
        } else if (entity != null) {
            try {
                if (Slimefun.getFoliaLib().getScheduler().isOwnedByCurrentRegion(entity)) {
                    return callable.call();
                }
            } catch (Exception ignored) {
            }
            final CompletableFuture<T> result = new CompletableFuture<>();
            Slimefun.getPlatformScheduler().runAtEntity(entity, task -> {
                try {
                    result.complete(callable.call());
                } catch (Exception e) {
                    result.completeExceptionally(e);
                }
            });
            return result.get(5, TimeUnit.SECONDS);
        } else {
            final CompletableFuture<T> result = new CompletableFuture<>();
            Slimefun.getPlatformScheduler().runNextTick(task -> {
                try {
                    result.complete(callable.call());
                } catch (Exception e) {
                    result.completeExceptionally(e);
                }
            });
            return result.get(5, TimeUnit.SECONDS);
        }
    }
}
