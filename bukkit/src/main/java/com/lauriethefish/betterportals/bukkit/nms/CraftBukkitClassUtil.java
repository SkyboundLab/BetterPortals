package com.lauriethefish.betterportals.bukkit.nms;

import com.lauriethefish.betterportals.shared.util.ReflectionUtil;
import org.bukkit.Bukkit;

/**
 * Bukkit (and nowadays Spigot as well) no longer always uses a versioned package for the craftbukkit classes.
 */
public class CraftBukkitClassUtil {
    private static final String craftBukkitClassPrefix; // E.g. org.bukkit.craftbukkit.

    static {
        String packageName = Bukkit.getServer().getClass().getPackage().getName();

        craftBukkitClassPrefix = packageName + ".";
    }

    /**
     * @param name Name of the class relative to this package, with no dot at the start.
     * @return The located class
     */
    public static Class<?> findCraftBukkitClass(String name) {
        return ReflectionUtil.findClass(craftBukkitClassPrefix + name);
    }
}
