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

public final class FoliaScheduler {
    private FoliaScheduler() {
    }

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

    public static void cancel(@Nullable TaskHandle handle) {
        if (handle != null) {
            handle.cancel();
        }
    }

    public static @NotNull TaskHandle runGlobalAtFixedRate(@NotNull Plugin plugin, @NotNull Runnable task,
                                                           long delayTicks, long periodTicks) {
        Object globalScheduler = getSchedulerObject("getGlobalRegionScheduler");
        if (globalScheduler != null) {
            try {
                Method run = globalScheduler.getClass().getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
                Object scheduled = run.invoke(globalScheduler, plugin, consumer(task), delayTicks, periodTicks);
                return new ReflectiveTaskHandle(scheduled);
            }   catch (ReflectiveOperationException ignored) {
            }
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks));
    }

    public static @NotNull TaskHandle runGlobalDelayed(@NotNull Plugin plugin, @NotNull Runnable task, long delayTicks) {
        Object globalScheduler = getSchedulerObject("getGlobalRegionScheduler");
        if (globalScheduler != null) {
            try {
                Method run = globalScheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                Object scheduled = run.invoke(globalScheduler, plugin, consumer(task), delayTicks);
                return new ReflectiveTaskHandle(scheduled);
            }   catch (ReflectiveOperationException ignored) {
            }
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
    }

    public static @NotNull TaskHandle runDelayedAtLocation(@NotNull Plugin plugin, @NotNull Location location,
                                                           @NotNull Runnable task, long delayTicks) {
        Object regionScheduler = getSchedulerObject("getRegionScheduler");
        if (regionScheduler != null) {
            try {
                Method run = regionScheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, Location.class, long.class);
                Object scheduled = run.invoke(regionScheduler, plugin, consumer(task), location, delayTicks);
                return new ReflectiveTaskHandle(scheduled);
            }   catch (ReflectiveOperationException ignored) {
            }
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
    }

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
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
    }

    public static @NotNull TaskHandle runAsync(@NotNull Plugin plugin, @NotNull Runnable task) {
        Object asyncScheduler = getSchedulerObject("getAsyncScheduler");
        if (asyncScheduler != null) {
            try {
                Method run = asyncScheduler.getClass().getMethod("runNow", Plugin.class, Consumer.class);
                Object scheduled = run.invoke(asyncScheduler, plugin, consumer(task));
                return new ReflectiveTaskHandle(scheduled);
            }   catch (ReflectiveOperationException ignored) {
            }
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
    }

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
            }
        }
        return new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks));
    }

    private static @Nullable Object getSchedulerObject(@NotNull String getter) {
        try {
            return Bukkit.getServer().getClass().getMethod(getter).invoke(Bukkit.getServer());
        }   catch (ReflectiveOperationException | NullPointerException | IllegalStateException ex) {
            return null;
        }
    }

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
