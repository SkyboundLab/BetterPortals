package com.lauriethefish.betterportals.bukkit.nms;

import com.lauriethefish.betterportals.shared.util.ReflectionUtil;
import org.bukkit.Bukkit;

/**
 */
public class CraftBukkitClassUtil {
    private static final String craftBukkitClassPrefix;

    static {
        String packageName = Bukkit.getServer().getClass().getPackage().getName();

        // org.bukkit.craftbukkit (26.1+) or org.bukkit.craftbukkit.v1_20_R3 (older versions) - append a dot either way
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
