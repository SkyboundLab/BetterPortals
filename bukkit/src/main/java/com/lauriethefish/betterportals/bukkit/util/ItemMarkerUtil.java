package com.lauriethefish.betterportals.bukkit.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ItemMarkerUtil {
    private static final String MARKER_VALUE = "marked";

    private ItemMarkerUtil() {
    }

    @NotNull
    public static ItemStack addMarkerTag(@NotNull ItemStack item, @NotNull String name) {
        ItemStack copy = item.clone();

        ItemMeta meta = copy.getItemMeta();
        if (meta == null) {
            return copy;
        }

        meta.getPersistentDataContainer().set(key(name), org.bukkit.persistence.PersistentDataType.STRING, MARKER_VALUE);
        copy.setItemMeta(meta);

        return copy;
    }

    public static boolean hasMarkerTag(@Nullable ItemStack item, @NotNull String name) {
        if (item == null) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        String value = meta.getPersistentDataContainer().get(key(name), org.bukkit.persistence.PersistentDataType.STRING);
        return MARKER_VALUE.equals(value);
    }

    @NotNull
    private static NamespacedKey key(@NotNull String name) {
        return new NamespacedKey("betterportals", "marker_" + name.toLowerCase(java.util.Locale.ROOT));
    }
}
