package com.lauriethefish.betterportals.bukkit.entity;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.lauriethefish.betterportals.bukkit.config.MiscConfig;
import com.lauriethefish.betterportals.bukkit.math.Matrix;
import com.lauriethefish.betterportals.bukkit.util.FoliaScheduler;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

@Singleton
public class PortalMomentum {
    private static final double DRAG_REMOVAL = 0.95;
    private static final double BOUNCE_BONUS = 0.5;

    private final JavaPlugin plugin;
    private final MiscConfig miscConfig;

    @Inject
    public PortalMomentum(JavaPlugin plugin, MiscConfig miscConfig) {
        this.plugin = plugin;
        this.miscConfig = miscConfig;
    }

    public static @NotNull Vector transformVelocity(@NotNull Vector velocity, @NotNull Matrix rotation, double scale) {
        Vector rotated = rotation.transform(velocity);
        if (scale != 1.0) {
            rotated.multiply(scale);
        }
        return rotated;
    }

    public void teleportWithMomentum(@NotNull Entity entity, @NotNull org.bukkit.Location destination,
                                    @NotNull Vector velocity, double scale) {
        entity.teleport(destination);
        entity.setVelocity(velocity);
        applyMomentumWindow(entity, scale);
    }

    public void applyMomentumWindow(@NotNull Entity entity, double scale) {
        if (!miscConfig.isPreserveMomentum()) { return; }
        if (!(entity instanceof LivingEntity living)) { return; }

        int windowTicks = Math.max(1, miscConfig.getMomentumDragFreeTicks());
        addTransientModifier(living, Attribute.AIR_DRAG_MODIFIER,
                new NamespacedKey(plugin, "portal_air_drag"),
                AttributeModifier.Operation.MULTIPLY_SCALAR_1, -DRAG_REMOVAL);
        double bounce = BOUNCE_BONUS * Math.max(0.25, scale);
        addTransientModifier(living, Attribute.BOUNCINESS,
                new NamespacedKey(plugin, "portal_bounciness"),
                AttributeModifier.Operation.ADD_NUMBER, bounce);

        FoliaScheduler.runDelayedOnEntity(plugin, living, () -> {
            removeModifier(living, Attribute.AIR_DRAG_MODIFIER, new NamespacedKey(plugin, "portal_air_drag"));
            removeModifier(living, Attribute.BOUNCINESS, new NamespacedKey(plugin, "portal_bounciness"));
        }, windowTicks);
    }

    private void addTransientModifier(@NotNull LivingEntity living, @NotNull Attribute attribute,
                                      @NotNull NamespacedKey key,
                                      @NotNull AttributeModifier.Operation operation, double amount) {
        AttributeInstance instance = living.getAttribute(attribute);
        if (instance == null) { return; }
        removeModifier(living, attribute, key);
        instance.addModifier(new AttributeModifier(key, amount, operation,
                org.bukkit.inventory.EquipmentSlotGroup.ANY));
    }

    private void removeModifier(@NotNull LivingEntity living, @NotNull Attribute attribute, @NotNull NamespacedKey key) {
        AttributeInstance instance = living.getAttribute(attribute);
        if (instance == null) { return; }
        for (AttributeModifier modifier : instance.getModifiers()) {
            if (Objects.equals(modifier.getKey(), key)) {
                instance.removeModifier(modifier);
            }
        }
    }

    public int getWindowTicks() {
        return miscConfig.getMomentumDragFreeTicks();
    }

    public @NotNull NamespacedKey airDragKey() {
        return new NamespacedKey(plugin, "portal_air_drag");
    }

    public @NotNull NamespacedKey bouncinessKey() {
        return new NamespacedKey(plugin, "portal_bounciness");
    }
}
