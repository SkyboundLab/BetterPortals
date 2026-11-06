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

/**
 * Momentum-preserving teleport physics, using the entity-physics
 * attributes added in 26.2.
 *
 * <p>What the 26.2 attributes can and cannot do — verified against the
 * 26.3 Spigot API, not assumed:
 * <ul>
 *   <li>{@link Attribute#AIR_DRAG_MODIFIER} (default 1.0) multiplies the
 *       air drag an entity experiences; 0.0 means no drag.</li>
 *   <li>{@link Attribute#FRICTION_MODIFIER} (default 1.0) multiplies the
 *       ground friction from the block stood on.</li>
 *   <li>{@link Attribute#BOUNCINESS} (default 0.0) is the fraction of
 *       velocity restituted on collision.</li>
 * </ul>
 * None of them stores, carries or rotates a velocity vector, and a vanilla
 * teleport still resets an entity's motion. So the velocity itself is
 * <b>still carried explicitly</b>: read it before the teleport, rotate (and
 * scale) it by the portal transform, teleport, then re-apply it. The
 * attributes only stop the physics engine eating that velocity on the
 * landing tick — a temporary, transient modifier that zeroes air drag and
 * adds a little bounciness for a few ticks after arrival, then removes
 * itself. Base attribute values are never changed, and the modifiers are
 * keyed per-plugin so they can be removed by key without touching
 * modifiers from other plugins.
 *
 * <p>For non-living entities (items, minecarts, …) there are no attributes
 * to modulate, so only the explicit velocity carry applies.
 */
@Singleton
public class PortalMomentum {
    /** How much air drag to remove while the momentum window is open (1.0 = all of it). */
    private static final double DRAG_REMOVAL = 0.95;
    /** Bounciness added during the momentum window, so a landing does not eat the velocity. */
    private static final double BOUNCE_BONUS = 0.5;

    private final JavaPlugin plugin;
    private final MiscConfig miscConfig;

    @Inject
    public PortalMomentum(JavaPlugin plugin, MiscConfig miscConfig) {
        this.plugin = plugin;
        this.miscConfig = miscConfig;
    }

    /**
     * Rotates (and scales) a velocity vector through a portal.
     * Pure math, no Bukkit state — unit-testable.
     * @param velocity The velocity in the origin coordinate space
     * @param rotation The portal's rotation matrix (origin → destination)
     * @param scale The portal's scale factor (1.0 = unscaled)
     * @return The velocity in the destination coordinate space
     */
    public static @NotNull Vector transformVelocity(@NotNull Vector velocity, @NotNull Matrix rotation, double scale) {
        Vector rotated = rotation.transform(velocity);
        if (scale != 1.0) {
            rotated.multiply(scale);
        }
        return rotated;
    }

    /**
     * Teleports {@code entity} to {@code destination} and restores its
     * (transformed) velocity, with the 26.2 attribute window applied.
     * @param entity The entity to teleport
     * @param destination Where it ends up (already transformed)
     * @param velocity The velocity it should have after teleporting, in destination space
     * @param scale The portal scale factor, for the attribute window (unused for the vector itself here)
     */
    public void teleportWithMomentum(@NotNull Entity entity, @NotNull org.bukkit.Location destination,
                                    @NotNull Vector velocity, double scale) {
        entity.teleport(destination);
        entity.setVelocity(velocity);
        applyMomentumWindow(entity, scale);
    }

    /**
     * Applies the temporary 26.2 attribute modifiers to {@code entity} so
     * its velocity survives the landing tick, then removes them again.
     * Safe to call on any entity; a no-op when momentum preservation is
     * disabled in config or the entity is not living.
     */
    public void applyMomentumWindow(@NotNull Entity entity, double scale) {
        if (!miscConfig.isPreserveMomentum()) { return; }
        if (!(entity instanceof LivingEntity living)) { return; }

        int windowTicks = Math.max(1, miscConfig.getMomentumDragFreeTicks());
        addTransientModifier(living, Attribute.AIR_DRAG_MODIFIER,
                new NamespacedKey(plugin, "portal_air_drag"),
                AttributeModifier.Operation.MULTIPLY_SCALAR_1, -DRAG_REMOVAL);
        // A scaled-down portal shrinks the velocity, so the landing is
        // softer; a scaled-up portal needs the extra restitution more.
        double bounce = BOUNCE_BONUS * Math.max(0.25, scale);
        addTransientModifier(living, Attribute.BOUNCINESS,
                new NamespacedKey(plugin, "portal_bounciness"),
                AttributeModifier.Operation.ADD_NUMBER, bounce);

        // Remove the modifiers on the entity's own scheduler (follows it
        // across regions on Folia) so they can never leak onto the entity.
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
        // Replace any stale copy of our own modifier first (e.g. two
        // teleports inside one window).
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

    /** @return The configured drag-free window, in ticks (test seam). */
    public int getWindowTicks() {
        return miscConfig.getMomentumDragFreeTicks();
    }

    /** @return The NamespacedKey used for the air-drag modifier (test seam). */
    public @NotNull NamespacedKey airDragKey() {
        return new NamespacedKey(plugin, "portal_air_drag");
    }

    /** @return The NamespacedKey used for the bounciness modifier (test seam). */
    public @NotNull NamespacedKey bouncinessKey() {
        return new NamespacedKey(plugin, "portal_bounciness");
    }
}
