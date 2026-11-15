package com.lauriethefish.betterportals.bukkit.util;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class DimensionUtil {
    private static final NamespacedKey OVERWORLD = new NamespacedKey("minecraft", "overworld");
    private static final NamespacedKey THE_NETHER = new NamespacedKey("minecraft", "the_nether");
    private static final NamespacedKey THE_END = new NamespacedKey("minecraft", "the_end");

    private DimensionUtil() {
    }

    public static @Nullable NamespacedKey dimensionKey(@Nullable World world) {
        return world == null ? null : world.getKey();
    }

    public static boolean isOverworldKey(@Nullable NamespacedKey key) {
        return OVERWORLD.equals(key);
    }

    public static boolean isNetherKey(@Nullable NamespacedKey key) {
        return THE_NETHER.equals(key);
    }

    public static boolean isEndKey(@Nullable NamespacedKey key) {
        return THE_END.equals(key);
    }

    public static boolean isNether(@Nullable World world) {
        if (world == null) { return false; }
        NamespacedKey key = world.getKey();
        if (key != null) {
            if (isNetherKey(key)) { return true; }
            if (isOverworldKey(key) || isEndKey(key)) { return false; }
        }
        return world.getEnvironment() == World.Environment.NETHER;
    }

    public static boolean isEnd(@Nullable World world) {
        if (world == null) { return false; }
        NamespacedKey key = world.getKey();
        if (key != null) {
            if (isEndKey(key)) { return true; }
            if (isOverworldKey(key) || isNetherKey(key)) { return false; }
        }
        return world.getEnvironment() == World.Environment.THE_END;
    }

    public static boolean isOverworld(@Nullable World world) {
        if (world == null) { return false; }
        return !isNether(world) && !isEnd(world);
    }

    public static boolean isVanillaDimension(@Nullable World world) {
        if (world == null) { return false; }
        NamespacedKey key = world.getKey();
        return isOverworldKey(key) || isNetherKey(key) || isEndKey(key);
    }

    public static int maxSpawnHeight(@NotNull World world, int fallbackMaxHeight) {
        return isNether(world) ? 128 : fallbackMaxHeight;
    }
}
