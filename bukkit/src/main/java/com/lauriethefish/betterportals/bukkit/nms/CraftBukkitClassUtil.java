package com.lauriethefish.betterportals.bukkit.nms;

import com.lauriethefish.betterportals.shared.util.ReflectionUtil;
import org.bukkit.Bukkit;

/**
 * Bukkit (and nowadays Spigot as well) no longer always uses a versioned package for the craftbukkit classes.
 * This means we can no longer rely on the version segment being present, so we just use whatever package the server implementation is in.
 */
public class CraftBukkitClassUtil {
    private static final String craftBukkitClassPrefix; // E.g. org.bukkit.craftbukkit.

    static {
        String packageName = Bukkit.getServer().getClass().getPackage().getName();

        // org.bukkit.craftbukkit (26.1+) or org.bukkit.craftbukkit.v1_20_R3 (older versions) - append a dot either way
        craftBukkitClassPrefix = packageName + ".";
    }

    /**
     * Finds a class in the <code>org.bukkit.craftbukkit</code> package
     * @param name Name of the class relative to this package, with no dot at the start.
     * @return The located class
     */
    public static Class<?> findCraftBukkitClass(String name) {
        return ReflectionUtil.findClass(craftBukkitClassPrefix + name);
    }
}
