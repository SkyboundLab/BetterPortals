package com.lauriethefish.betterportals.bukkit.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Used to add simple marker tags to items, for example the portal wand.
 * Implemented with the Bukkit persistent data container API, so it doesn't rely on NMS at all.
 * (Items marked before this change was made won't be recognised.)
 */
public final class ItemMarkerUtil {
    private static final String MARKER_VALUE = "marked";

    private ItemMarkerUtil() {
    }

    /**
     * Adds a marker tag to <code>item</code>.
     * @param item Item to add the tag to
     * @param name Name of the tag
     * @return A new {@link ItemStack} with the tag. (original is unmodified)
     */
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

    /**
     * Checks if <code>item</code> has a marker tag with <code>name</code>.
     * @param item The item to check
     * @param name The name of the marker tag
     * @return Whether it has the tag
     */
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
