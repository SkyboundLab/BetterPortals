package com.lauriethefish.betterportals.bukkit.util;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Dimension handling that is generic over vanilla and datapack dimensions.
 *
 * <p>Older code branched on {@link World.Environment}, which only knows
 * {@code NORMAL}/{@code NETHER}/{@code THE_END}. Datapack dimensions (and the
 * announced 2027 dimension, The Sift, once Mojang ships it) have no
 * {@code Environment} value of their own, so they were silently treated as
 * overworld. This class keys everything off {@link World#getKey()} — the
 * dimension's {@link NamespacedKey}, e.g. {@code minecraft:overworld},
 * {@code minecraft:the_nether}, {@code minecraft:the_end}, or a datapack
 * dimension such as {@code mypack:my_dimension} — and only falls back to
 * {@code Environment} for the three vanilla dimensions.
 *
 * <p>Unknown (datapack) dimensions are treated as overworld-like for
 * lighting/background purposes and get no nether height cap: that is the
 * safe default, since a custom dimension's ceiling is defined by its own
 * {@code DimensionType}, not by being the nether.
 */
public final class DimensionUtil {
    private static final NamespacedKey OVERWORLD = new NamespacedKey("minecraft", "overworld");
    private static final NamespacedKey THE_NETHER = new NamespacedKey("minecraft", "the_nether");
    private static final NamespacedKey THE_END = new NamespacedKey("minecraft", "the_end");

    private DimensionUtil() {
    }

    /** @return The dimension key of {@code world}, or {@code null} if the world is null. */
    public static @Nullable NamespacedKey dimensionKey(@Nullable World world) {
        return world == null ? null : world.getKey();
    }

    /** @return Whether the key is the vanilla overworld key. */
    public static boolean isOverworldKey(@Nullable NamespacedKey key) {
        return OVERWORLD.equals(key);
    }

    /** @return Whether the key is the vanilla nether key. */
    public static boolean isNetherKey(@Nullable NamespacedKey key) {
        return THE_NETHER.equals(key);
    }

    /** @return Whether the key is the vanilla end key. */
    public static boolean isEndKey(@Nullable NamespacedKey key) {
        return THE_END.equals(key);
    }

    /** @return Whether {@code world} is the nether, by dimension key first, {@code Environment} second. */
    public static boolean isNether(@Nullable World world) {
        if (world == null) { return false; }
        NamespacedKey key = world.getKey();
        if (key != null) {
            // A datapack dimension that happens to report Environment.NETHER is
            // still a custom dimension; only the vanilla nether key counts here,
            // unless the key itself is unknown and Environment is all we have.
            if (isNetherKey(key)) { return true; }
            if (isOverworldKey(key) || isEndKey(key)) { return false; }
        }
        return world.getEnvironment() == World.Environment.NETHER;
    }

    /** @return Whether {@code world} is the end, by dimension key first, {@code Environment} second. */
    public static boolean isEnd(@Nullable World world) {
        if (world == null) { return false; }
        NamespacedKey key = world.getKey();
        if (key != null) {
            if (isEndKey(key)) { return true; }
            if (isOverworldKey(key) || isNetherKey(key)) { return false; }
        }
        return world.getEnvironment() == World.Environment.THE_END;
    }

    /** @return Whether {@code world} is the overworld (or an unknown datapack dimension, treated as overworld-like). */
    public static boolean isOverworld(@Nullable World world) {
        if (world == null) { return false; }
        return !isNether(world) && !isEnd(world);
    }

    /**
     * @return Whether {@code world} is a known vanilla dimension, as opposed
     * to a datapack/custom dimension. Custom dimensions need generic handling
     * (no hardcoded height caps or background blocks).
     */
    public static boolean isVanillaDimension(@Nullable World world) {
        if (world == null) { return false; }
        NamespacedKey key = world.getKey();
        return isOverworldKey(key) || isNetherKey(key) || isEndKey(key);
    }

    /**
     * The nether's build ceiling used for portal spawn limits. Vanilla nether
     * worlds cap at 128; every other dimension uses its own max height.
     * @param world The destination world
     * @param fallbackMaxHeight The world's own max height, used for non-nether dimensions
     * @return 128 for the (vanilla) nether, {@code fallbackMaxHeight} otherwise
     */
    public static int maxSpawnHeight(@NotNull World world, int fallbackMaxHeight) {
        return isNether(world) ? 128 : fallbackMaxHeight;
    }
}
