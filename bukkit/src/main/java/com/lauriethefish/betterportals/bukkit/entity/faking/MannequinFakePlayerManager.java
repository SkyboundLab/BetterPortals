package com.lauriethefish.betterportals.bukkit.entity.faking;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.lauriethefish.betterportals.shared.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fake players rendered through portals as real {@link Mannequin} entities.
 *
 * <p>Previously a player seen through a portal was faked entirely with
 * packets: a {@code PLAYER_INFO} add (with a throwaway UUID, removed from
 * the tab list 20 ticks later so it did not show twice) plus a hand-rolled
 * named-entity spawn packet. That path is the most version-fragile part of
 * the plugin — every packet-format change (the 26.x teleport/move
 * rewrites are the latest example) breaks it, and the tab-list dance is
 * visible if anything goes wrong.
 *
 * <p>A Mannequin (vanilla since 1.21.9, {@code org.bukkit.entity.Mannequin})
 * is a real server-side entity with a player's skin, equipment and pose,
 * but no connected player and therefore no tab-list entry and no
 * {@code PLAYER_INFO} at all. That removes the fragile packet path for
 * players entirely: the plugin spawns one Mannequin per tracked player,
 * in the <i>viewer's</i> world at the portal-transformed position, and
 * keeps it in sync per tick with ordinary entity calls
 * ({@code teleport}/{@code setRotation}/{@code setPose}/equipment).
 * Because the Mannequin is a real entity it can also be a passenger, be
 * pushed, and be tracked by the server like any other mob.
 *
 * <p>Limits, stated plainly:
 * <ul>
 *   <li>A Mannequin is not a {@link Player}: no arm-swing/walk animation
 *       beyond pose and rotation, no player-only interactions. Mirroring
 *       still needs the per-tick sync in {@link #sync}.</li>
 *   <li>One Mannequin is one world entity, so its position/pose are the
 *       same for every viewer. That is fine here: the portal transform is
 *       viewer-independent, so all viewers of one portal see the same
 *       rendered position. Per-viewer visibility is still controlled with
 *       {@code setVisibleByDefault(false)} + {@code showEntity}.</li>
 *   <li>The rendered position's world is the viewer's world. The old
 *       packet path never needed a world (packets are sent to a player and
 *       render in whatever world they are in); a real entity does, so the
 *       spawn location is built from the viewer's world explicitly.</li>
 *   <li>If Mannequins are unavailable (older server build, spawn failure)
 *       the caller falls back to the packet path — see
 *       {@link com.lauriethefish.betterportals.bukkit.entity.faking.EntityTracker}.
 *       Nothing in this class throws to its caller for that case.</li>
 * </ul>
 */
@Singleton
public class MannequinFakePlayerManager {
    /** Poses a Mannequin is allowed to take (others throw on {@code setPose}). */
    private static final Set<Pose> VALID_MANNEQUIN_POSES = EnumSet.of(
            Pose.STANDING, Pose.SNEAKING, Pose.SWIMMING, Pose.FALL_FLYING, Pose.SLEEPING);

    /** The divergent Mannequin calls, resolved once for this server's API (see {@link MannequinApi}). */
    private static final MannequinApi MANNEQUIN_API = MannequinApi.resolve();

    /**
     * Players whose Mannequin spawn failure has already been warned about
     * once. A spawn failure repeats for every tracker a server creates
     * (hundreds of identical warnings in a minute on a build where
     * Mannequins don't work), so only the first one per player is a
     * warning; the rest are fine-level.
     */
    private static final Set<String> spawnFailureWarnedPlayers = ConcurrentHashMap.newKeySet();

    /**
     * UUIDs of the Mannequins this plugin has spawned. A Mannequin is a
     * real entity in the viewer's world, so the portal's own entity logic
     * would otherwise treat it as a normal entity standing behind the
     * portal: the hide-behind-portal check would send the viewer a
     * destroy packet for it (it appeared for a second, then vanished —
     * 2026-11-06 live test), and the teleport check could teleport it.
     * Both paths skip entities in this set (see
     * {@link com.lauriethefish.betterportals.bukkit.entity.PortalEntityManager}
     * and {@link com.lauriethefish.betterportals.bukkit.player.view.entity.PlayerEntityView}).
     */
    private final Set<java.util.UUID> fakeMannequinIds = ConcurrentHashMap.newKeySet();

    private final JavaPlugin plugin;
    private final Logger logger;

    @Inject
    public MannequinFakePlayerManager(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    /**
     * Spawns a Mannequin mirroring {@code source} at {@code renderedLocation}
     * (already portal-transformed, in the viewer's world), visible only to
     * viewers it is explicitly shown to.
     * @param source The real player being mirrored
     * @param renderedLocation Where the fake should stand, in the viewer's world
     * @return The spawned Mannequin, or {@code null} if spawning failed
     */
    public @Nullable Mannequin spawn(@NotNull Player source, @NotNull Location renderedLocation) {
        World world = renderedLocation.getWorld();
        if (world == null) { return null; }
        try {
            Mannequin mannequin = world.spawn(renderedLocation, Mannequin.class,
                    CreatureSpawnEvent.SpawnReason.CUSTOM, false, m -> configure(m, source, renderedLocation));
            fakeMannequinIds.add(mannequin.getUniqueId());
            return mannequin;
        }   catch (RuntimeException | LinkageError ex) {
            // The spawn failure repeats for every tracker a server creates,
            // so warn once per player and log the rest at fine level.
            String message = String.format("Failed to spawn a Mannequin fake player for %s, falling back to packet fake players: %s",
                    source.getName(), ex.getMessage());
            if (spawnFailureWarnedPlayers.add(source.getName())) {
                logger.warning(message);
            }   else {
                logger.fine(message);
            }
            return null;
        }
    }

    private void configure(@NotNull Mannequin mannequin, @NotNull Player source, @NotNull Location renderedLocation) {
        // Hidden by default: it is only ever shown to a specific viewer via
        // showEntity. Set here (pre-spawn) so no client ever sees it first.
        mannequin.setVisibleByDefault(false);
        // Skin + name from the real player's profile. The call differs
        // between Spigot and Paper (see MannequinApi); it is best-effort —
        // a fake without the copied skin still spawns.
        try {
            MANNEQUIN_API.setProfile(mannequin, source.getPlayerProfile());
        }   catch (RuntimeException ex) {
            logger.fine("Could not copy player profile onto Mannequin for %s: %s", source.getName(), ex.getMessage());
        }
        mannequin.setCustomName(source.getName());
        mannequin.setCustomNameVisible(false);
        // The description is the "NPC label" line under the name; hide it so
        // the fake does not look like a named NPC. Best-effort as well —
        // Paper has no hide-description method, so an empty description
        // is the fallback there.
        try {
            MANNEQUIN_API.setDescription(mannequin);
            MANNEQUIN_API.setHideDescription(mannequin);
        }   catch (RuntimeException ex) {
            logger.fine("Could not hide Mannequin description for %s: %s", source.getName(), ex.getMessage());
        }
        mannequin.setImmovable(true);
        mannequin.setInvulnerable(true);
        mannequin.setSilent(true);
        mannequin.setPersistent(false);
        mannequin.setRemoveWhenFarAway(false);
        mannequin.setCanPickupItems(false);
        mannequin.setRotation(renderedLocation.getYaw(), renderedLocation.getPitch());
        copyPose(mannequin, source);
        copyEquipment(mannequin, source);
    }

    /**
     * Moves/rotates {@code mannequin} to the source's current rendered
     * position and re-syncs pose and equipment. Called every tick by the
     * entity tracker while the fake is visible.
     * @param mannequin The fake to sync
     * @param source The real player being mirrored
     * @param renderedLocation The source's current rendered location (viewer's world)
     */
    public void sync(@NotNull Mannequin mannequin, @NotNull Player source, @NotNull Location renderedLocation) {
        if (!mannequin.isValid()) { return; }
        mannequin.teleport(renderedLocation);
        mannequin.setRotation(renderedLocation.getYaw(), renderedLocation.getPitch());
        copyPose(mannequin, source);
        copyEquipment(mannequin, source);
    }

    /** Shows the Mannequin to one viewer only. */
    public void showTo(@NotNull Mannequin mannequin, @NotNull Player viewer) {
        viewer.showEntity(plugin, mannequin);
    }

    /** Hides the Mannequin from one viewer only. */
    public void hideFrom(@NotNull Mannequin mannequin, @NotNull Player viewer) {
        viewer.hideEntity(plugin, mannequin);
    }

    /** Removes the Mannequin from the world entirely. */
    public void remove(@NotNull Mannequin mannequin) {
        fakeMannequinIds.remove(mannequin.getUniqueId());
        mannequin.remove();
    }

    /**
     * Forgets a Mannequin that died or was removed out from under the
     * tracker (see {@code EntityTracker}): the entity is already gone,
     * only its id needs dropping from the fake set.
     */
    public void unregister(@NotNull Mannequin mannequin) {
        fakeMannequinIds.remove(mannequin.getUniqueId());
    }

    /** @return Whether {@code entity} is a Mannequin fake spawned by this plugin. */
    public boolean isFakePlayer(@NotNull Entity entity) {
        return entity instanceof Mannequin && fakeMannequinIds.contains(entity.getUniqueId());
    }

    private void copyPose(@NotNull Mannequin mannequin, @NotNull Player source) {
        Pose pose = source.getPose();
        if (pose != null && VALID_MANNEQUIN_POSES.contains(pose)) {
            try {
                mannequin.setPose(pose);
            }   catch (RuntimeException ignored) {
                // Pose rejected by this server build; standing is fine.
            }
        }
    }

    private void copyEquipment(@NotNull Mannequin mannequin, @NotNull LivingEntity source) {
        EntityEquipment sourceEquipment = source.getEquipment();
        EntityEquipment fakeEquipment = mannequin.getEquipment();
        if (sourceEquipment == null || fakeEquipment == null) { return; }
        fakeEquipment.setHelmet(copy(sourceEquipment.getHelmet()));
        fakeEquipment.setChestplate(copy(sourceEquipment.getChestplate()));
        fakeEquipment.setLeggings(copy(sourceEquipment.getLeggings()));
        fakeEquipment.setBoots(copy(sourceEquipment.getBoots()));
        fakeEquipment.setItemInMainHand(copy(sourceEquipment.getItemInMainHand()));
        fakeEquipment.setItemInOffHand(copy(sourceEquipment.getItemInOffHand()));
    }

    private static @Nullable ItemStack copy(@Nullable ItemStack item) {
        return item == null ? null : item.clone();
    }

    /** @return The rendered location of {@code entity} for a viewer in {@code viewerWorld}. */
    public static @NotNull Location renderedLocationFor(@NotNull EntityInfo entityInfo, @NotNull World viewerWorld) {
        Location rendered = entityInfo.findRenderedLocation();
        rendered.setWorld(viewerWorld);
        return rendered;
    }

    /** @return The rendered location, with the portal scale already applied by the transform. */
    public static @NotNull Vector renderedPosition(@NotNull EntityInfo entityInfo) {
        return entityInfo.findRenderedLocation().toVector();
    }

    /**
     * The Mannequin API differs between the two servers this plugin runs
     * on, even though both call themselves 26.3 — the interface was
     * reworked on Paper and the two APIs never converged:
     * <ul>
     *   <li>Spigot's {@code Mannequin} (the compile target) takes a Bukkit
     *       {@code PlayerProfile} ({@code setPlayerProfile}), a
     *       {@code String} description, and has
     *       {@code setHideDescription}.</li>
     *   <li>Paper 26.3-244's {@code Mannequin} takes a Paper
     *       {@code ResolvableProfile} ({@code setProfile}, built from the
     *       player's profile with
     *       {@code ResolvableProfile.resolvableProfile}), a Kyori
     *       {@code Component} description, and has no hide-description
     *       method at all.</li>
     * </ul>
     * Calling the Spigot methods directly throws
     * {@code NoSuchMethodError} on Paper — on 26.3-244 that killed every
     * Mannequin spawn (and, because the spawn is retried for every new
     * tracker, flooded the log) and silently fell back to the packet path.
     * So every divergent call goes through this adapter, resolved once by
     * reflection. Every method is best-effort and throws
     * {@link IllegalStateException} when its call cannot be made at all:
     * the caller logs that at fine level and the fake still spawns, just
     * without the copied skin or the hidden description.
     */
    static final class MannequinApi {
        final Method setPlayerProfile; // (PlayerProfile) — Spigot
        final Method setProfile; // (ResolvableProfile) — Paper
        final Method resolvableProfile; // static ResolvableProfile.resolvableProfile(Paper PlayerProfile)
        final Method setDescriptionString; // (String) — Spigot
        final Method setDescriptionComponent; // (Component) — Paper
        final Method componentEmpty; // static Component.empty()
        final Method setHideDescription; // (boolean) — Spigot only

        private MannequinApi(Method setPlayerProfile, Method setProfile, Method resolvableProfile,
                             Method setDescriptionString, Method setDescriptionComponent, Method componentEmpty,
                             Method setHideDescription) {
            this.setPlayerProfile = setPlayerProfile;
            this.setProfile = setProfile;
            this.resolvableProfile = resolvableProfile;
            this.setDescriptionString = setDescriptionString;
            this.setDescriptionComponent = setDescriptionComponent;
            this.componentEmpty = componentEmpty;
            this.setHideDescription = setHideDescription;
        }

        static MannequinApi resolve() {
            Method setPlayerProfile = find(Mannequin.class, "setPlayerProfile", org.bukkit.profile.PlayerProfile.class);
            Method setProfile = null;
            Method resolvableProfile = null;
            if (setPlayerProfile == null) {
                // Paper's variant: the profile is a ResolvableProfile,
                // built from the Paper PlayerProfile.
                try {
                    Class<?> resolvableProfileClass = Class.forName("io.papermc.paper.datacomponent.item.ResolvableProfile");
                    Class<?> paperProfileClass = Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
                    setProfile = find(Mannequin.class, "setProfile", resolvableProfileClass);
                    resolvableProfile = resolvableProfileClass.getMethod("resolvableProfile", paperProfileClass);
                }   catch (ClassNotFoundException | NoSuchMethodException ignored) {
                    setProfile = null;
                    resolvableProfile = null;
                }
            }

            Method setDescriptionString = find(Mannequin.class, "setDescription", String.class);
            Method setDescriptionComponent = null;
            Method componentEmpty = null;
            if (setDescriptionString == null) {
                // Paper's variant: the description is a Kyori Component.
                try {
                    Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
                    setDescriptionComponent = find(Mannequin.class, "setDescription", componentClass);
                    componentEmpty = componentClass.getMethod("empty");
                }   catch (ClassNotFoundException | NoSuchMethodException ignored) {
                    setDescriptionComponent = null;
                    componentEmpty = null;
                }
            }

            Method setHideDescription = find(Mannequin.class, "setHideDescription", boolean.class);
            return new MannequinApi(setPlayerProfile, setProfile, resolvableProfile,
                    setDescriptionString, setDescriptionComponent, componentEmpty, setHideDescription);
        }

        private static @Nullable Method find(Class<?> klass, String name, Class<?>... paramTypes) {
            try {
                return klass.getMethod(name, paramTypes);
            }   catch (NoSuchMethodException ex) {
                return null;
            }
        }

        /** Copies the source player's skin onto the Mannequin, Spigot or Paper variant. */
        void setProfile(@NotNull Mannequin mannequin, @NotNull org.bukkit.profile.PlayerProfile profile) {
            try {
                if (setPlayerProfile != null) {
                    setPlayerProfile.invoke(mannequin, profile);
                }   else if (setProfile != null && resolvableProfile != null) {
                    Object resolvable = resolvableProfile.invoke(null, profile);
                    setProfile.invoke(mannequin, resolvable);
                }
                // Neither variant exists: nothing to do, the fake spawns
                // with its default profile.
            }   catch (ReflectiveOperationException | IllegalArgumentException ex) {
                throw new IllegalStateException("Could not copy player profile onto Mannequin", ex);
            }
        }

        /** Sets the Mannequin's description to empty, so no NPC label shows. */
        void setDescription(@NotNull Mannequin mannequin) {
            try {
                if (setDescriptionString != null) {
                    setDescriptionString.invoke(mannequin, "");
                }   else if (setDescriptionComponent != null && componentEmpty != null) {
                    setDescriptionComponent.invoke(mannequin, componentEmpty.invoke(null));
                }
            }   catch (ReflectiveOperationException | IllegalArgumentException ex) {
                throw new IllegalStateException("Could not set Mannequin description", ex);
            }
        }

        /** Hides the Mannequin's description. No-op on Paper, which has no such method. */
        void setHideDescription(@NotNull Mannequin mannequin) {
            if (setHideDescription == null) { return; }
            try {
                setHideDescription.invoke(mannequin, true);
            }   catch (ReflectiveOperationException | IllegalArgumentException ex) {
                throw new IllegalStateException("Could not hide Mannequin description", ex);
            }
        }
    }
}
