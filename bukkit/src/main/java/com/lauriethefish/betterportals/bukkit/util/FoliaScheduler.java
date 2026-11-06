package com.lauriethefish.betterportals.bukkit.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Scheduler access that works on Paper, Folia and Spigot from a single
 * {@code spigot-api} compile target.
 *
 * <p>Folia (and modern Paper) provide four region-aware schedulers —
 * {@code GlobalRegionScheduler}, {@code RegionScheduler},
 * {@code AsyncScheduler} and per-entity {@code EntityScheduler} — but those
 * types live in the Paper API, not in {@code spigot-api}, so this plugin
 * cannot reference them directly without dropping Spigot compatibility.
 * This helper reaches them reflectively when the running server has them
 * (Paper 1.20+ / Folia) and falls back to the ordinary
 * {@link BukkitScheduler} on plain Spigot, where the reflective lookup
 * simply fails. The reflective path is only used for <i>scheduling</i>;
 * the work itself is unchanged.
 *
 * <p>Rules followed by the call sites:
 * <ul>
 *   <li>global, server-wide work (the per-tick update loop, autosave,
 *       proxy reconnect) → {@link #runGlobalAtFixedRate} /
 *       {@link #runGlobalDelayed} (GlobalRegionScheduler on Folia/Paper);
 *   <li>work tied to a block/location → {@link #runDelayedAtLocation}
 *       (RegionScheduler);
 *   <li>work tied to one entity → {@link #runDelayedOnEntity}
 *       (EntityScheduler, which follows the entity across regions);
 *   <li>off-thread work that touches no world state →
 *       {@link #runAsync} / {@link #runAsyncAtFixedRate} (AsyncScheduler).
 * </ul>
 *
 * <p>Honest limitation: swapping schedulers does not by itself make the
 * plugin fully region-thread-safe. Portal state is still shared mutable
 * state across regions (two sides of one portal can live in different
 * regions on Folia), so {@code folia-supported: true} in plugin.yml means
 * "loads and runs on Folia with region-aware scheduling", not "every
 * cross-region access has been audited". The per-tick view loop runs on
 * the global region thread, which is the closest Folia equivalent to
 * Paper's single main thread.
 */
public final class FoliaScheduler {
    private FoliaScheduler() {
    }

    /** A scheduled task from either scheduler family. */
    public interface TaskHandle {
        void cancel();
        boolean isCancelled();
    }

    private static final class BukkitTaskHandle implements TaskHandle {
        private final BukkitTask task;

        BukkitTaskHandle(BukkitTask task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            task.cancel();
        }

        @Override
        public boolean isCancelled() {
            return task.isCancelled();
        }
    }

    private static final class ReflectiveTaskHandle implements TaskHandle {
        private final Object scheduledTask;
        private final Method cancelMethod;
        private final Method isCancelledMethod;
        private volatile boolean cancelled;

        ReflectiveTaskHandle(Object scheduledTask) throws ReflectiveOperationException {
            this.scheduledTask = scheduledTask;
            this.cancelMethod = scheduledTask.getClass().getMethod("cancel");
            Method isCancelled;
            try {
                isCancelled = scheduledTask.getClass().getMethod("isCancelled");
            }   catch (NoSuchMethodException ex) {
                isCancelled = null;
            }
            this.isCancelledMethod = isCancelled;
        }

        @Override
        public void cancel() {
            cancelled = true;
            try {
                cancelMethod.invoke(scheduledTask);
            }   catch (ReflectiveOperationException ignored) {
                // Already cancelled or the server is shutting down; nothing to do.
            }
        }

        @Override
        public boolean isCancelled() {
            if (cancelled) { return true; }
            if (isCancelledMethod == null) { return false; }
            try {
                return (boolean) isCancelledMethod.invoke(scheduledTask);
            }   catch (ReflectiveOperationException ex) {
                return false;
            }
        }
    }

    /** Cancels a handle, tolerating {@code null}. */
    public static void cancel(@Nullable TaskHandle handle) {
        if (handle != null) {
            handle.cancel();
        }
    }

    /** Repeating global work: the per-tick loop, autosave, reconnect timer. */
    public static @NotNull TaskHandle runGlobalAtFixedRate(@NotNull Plugin plugin, @NotNull Runnable task,
                                                           long delayTicks, long periodTicks) {
        Object globalScheduler = getSchedulerObject("getGlobalRegionScheduler");
        if (globalScheduler != null) {
            try {
                Method run = globalScheduler.getClass().getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
                Object scheduled = run.invoke(globalScheduler, plugin, consumer(task), delayTicks, periodTicks);
                return new ReflectiveTaskHandle(scheduled);
            }   catch (ReflectiveOperationException ignored) {
                // Fall through to the Bukkit scheduler.
            }
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks));
    }

    /** One-shot global work, e.g. a callback that must run on the main/global thread. */
    public static @NotNull TaskHandle runGlobalDelayed(@NotNull Plugin plugin, @NotNull Runnable task, long delayTicks) {
        Object globalScheduler = getSchedulerObject("getGlobalRegionScheduler");
        if (globalScheduler != null) {
            try {
                Method run = globalScheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                Object scheduled = run.invoke(globalScheduler, plugin, consumer(task), delayTicks);
                return new ReflectiveTaskHandle(scheduled);
            }   catch (ReflectiveOperationException ignored) {
                // Fall through to the Bukkit scheduler.
            }
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
    }

    /** One-shot work owned by the region containing {@code location}. */
    public static @NotNull TaskHandle runDelayedAtLocation(@NotNull Plugin plugin, @NotNull Location location,
                                                           @NotNull Runnable task, long delayTicks) {
        Object regionScheduler = getSchedulerObject("getRegionScheduler");
        if (regionScheduler != null) {
            try {
                Method run = regionScheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, Location.class, long.class);
                Object scheduled = run.invoke(regionScheduler, plugin, consumer(task), location, delayTicks);
                return new ReflectiveTaskHandle(scheduled);
            }   catch (ReflectiveOperationException ignored) {
                // Fall through to the Bukkit scheduler.
            }
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
    }

    /**
     * One-shot work on a specific entity. The entity scheduler follows the
     * entity across regions, so this is the correct scheduler for anything
     * that mutates one entity (e.g. removing a temporary attribute modifier).
     */
    public static @NotNull TaskHandle runDelayedOnEntity(@NotNull Plugin plugin, @NotNull Entity entity,
                                                         @NotNull Runnable task, long delayTicks) {
        try {
            Method getScheduler = entity.getClass().getMethod("getScheduler");
            Object entityScheduler = getScheduler.invoke(entity);
            if (entityScheduler != null) {
                // EntityScheduler.runDelayed(Plugin, Consumer<ScheduledTask>, Runnable retired, long delayTicks)
                Method run = entityScheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
                Object scheduled = run.invoke(entityScheduler, plugin, consumer(task), null, delayTicks);
                return new ReflectiveTaskHandle(scheduled);
            }
        }   catch (ReflectiveOperationException ignored) {
            // Fall through to the Bukkit scheduler.
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
    }

    /** Off-thread work that must not touch world/block/entity state. */
    public static @NotNull TaskHandle runAsync(@NotNull Plugin plugin, @NotNull Runnable task) {
        Object asyncScheduler = getSchedulerObject("getAsyncScheduler");
        if (asyncScheduler != null) {
            try {
                Method run = asyncScheduler.getClass().getMethod("runNow", Plugin.class, Consumer.class);
                Object scheduled = run.invoke(asyncScheduler, plugin, consumer(task));
                return new ReflectiveTaskHandle(scheduled);
            }   catch (ReflectiveOperationException ignored) {
                // Fall through to the Bukkit scheduler.
            }
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
    }

    /** Repeating off-thread work (the async block-update finisher). */
    public static @NotNull TaskHandle runAsyncAtFixedRate(@NotNull Plugin plugin, @NotNull Runnable task,
                                                         long delayTicks, long periodTicks) {
        Object asyncScheduler = getSchedulerObject("getAsyncScheduler");
        if (asyncScheduler != null) {
            try {
                // AsyncScheduler.runAtFixedRate(Plugin, Consumer, long initialDelay, long period, TimeUnit)
                Method run = asyncScheduler.getClass().getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
                Object scheduled = run.invoke(asyncScheduler, plugin, consumer(task),
                        delayTicks * 50L, periodTicks * 50L, TimeUnit.MILLISECONDS);
                return new ReflectiveTaskHandle(scheduled);
            }   catch (ReflectiveOperationException ignored) {
                // Fall through to the Bukkit scheduler.
            }
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks));
    }

    /** @return The Paper/Folia scheduler object for {@code getter}, or {@code null} on plain Spigot / no server. */
    private static @Nullable Object getSchedulerObject(@NotNull String getter) {
        try {
            return Bukkit.getServer().getClass().getMethod(getter).invoke(Bukkit.getServer());
        }   catch (ReflectiveOperationException | NullPointerException | IllegalStateException ex) {
            return null;
        }
    }

    /**
     * Wraps a {@link Runnable} as a {@link Consumer} (the Paper/Folia
     * scheduler APIs take {@code Consumer<ScheduledTask>}; the task argument
     * is ignored here because callers that need cancellation keep the
     * returned {@link TaskHandle} instead).
     */
    private static @NotNull Consumer<Object> consumer(@NotNull Runnable task) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "accept" -> task.run();
                case "hashCode" -> { return System.identityHashCode(proxy); }
                case "equals" -> { return proxy == (args == null ? null : args[0]); }
                case "toString" -> { return "FoliaSchedulerConsumer"; }
                default -> { /* ignore */ }
            }
            return null;
        };
        return (Consumer<Object>) Proxy.newProxyInstance(
                FoliaScheduler.class.getClassLoader(),
                new Class<?>[]{Consumer.class}, handler);
    }
}
