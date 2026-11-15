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

@Singleton
public class MannequinFakePlayerManager {
    private static final Set<Pose> VALID_MANNEQUIN_POSES = EnumSet.of(
            Pose.STANDING, Pose.SNEAKING, Pose.SWIMMING, Pose.FALL_FLYING, Pose.SLEEPING);

    private static final MannequinApi MANNEQUIN_API = MannequinApi.resolve();

    private static final Set<String> spawnFailureWarnedPlayers = ConcurrentHashMap.newKeySet();

    private final Set<java.util.UUID> fakeMannequinIds = ConcurrentHashMap.newKeySet();

    private final JavaPlugin plugin;
    private final Logger logger;

    @Inject
    public MannequinFakePlayerManager(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public @Nullable Mannequin spawn(@NotNull Player source, @NotNull Location renderedLocation) {
        World world = renderedLocation.getWorld();
        if (world == null) { return null; }
        try {
            Mannequin mannequin = world.spawn(renderedLocation, Mannequin.class,
                    CreatureSpawnEvent.SpawnReason.CUSTOM, false, m -> configure(m, source, renderedLocation));
            fakeMannequinIds.add(mannequin.getUniqueId());
            return mannequin;
        }   catch (RuntimeException | LinkageError ex) {
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
        mannequin.setVisibleByDefault(false);
        try {
            MANNEQUIN_API.setProfile(mannequin, source.getPlayerProfile());
        }   catch (RuntimeException ex) {
            logger.fine("Could not copy player profile onto Mannequin for %s: %s", source.getName(), ex.getMessage());
        }
        mannequin.setCustomName(source.getName());
        mannequin.setCustomNameVisible(false);
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

    public void sync(@NotNull Mannequin mannequin, @NotNull Player source, @NotNull Location renderedLocation) {
        if (!mannequin.isValid()) { return; }
        mannequin.teleport(renderedLocation);
        mannequin.setRotation(renderedLocation.getYaw(), renderedLocation.getPitch());
        copyPose(mannequin, source);
        copyEquipment(mannequin, source);
    }

    public void showTo(@NotNull Mannequin mannequin, @NotNull Player viewer) {
        viewer.showEntity(plugin, mannequin);
    }

    public void hideFrom(@NotNull Mannequin mannequin, @NotNull Player viewer) {
        viewer.hideEntity(plugin, mannequin);
    }

    public void remove(@NotNull Mannequin mannequin) {
        fakeMannequinIds.remove(mannequin.getUniqueId());
        mannequin.remove();
    }

    public void unregister(@NotNull Mannequin mannequin) {
        fakeMannequinIds.remove(mannequin.getUniqueId());
    }

    public boolean isFakePlayer(@NotNull Entity entity) {
        return entity instanceof Mannequin && fakeMannequinIds.contains(entity.getUniqueId());
    }

    private void copyPose(@NotNull Mannequin mannequin, @NotNull Player source) {
        Pose pose = source.getPose();
        if (pose != null && VALID_MANNEQUIN_POSES.contains(pose)) {
            try {
                mannequin.setPose(pose);
            }   catch (RuntimeException ignored) {
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

    public static @NotNull Location renderedLocationFor(@NotNull EntityInfo entityInfo, @NotNull World viewerWorld) {
        Location rendered = entityInfo.findRenderedLocation();
        rendered.setWorld(viewerWorld);
        return rendered;
    }

    public static @NotNull Vector renderedPosition(@NotNull EntityInfo entityInfo) {
        return entityInfo.findRenderedLocation().toVector();
    }

    // Spigot and Paper 26.3 Mannequin methods differ; calls are resolved by reflection
    static final class MannequinApi {
        final Method setPlayerProfile;
        final Method setProfile;
        final Method resolvableProfile;
        final Method setDescriptionString;
        final Method setDescriptionComponent;
        final Method componentEmpty;
        final Method setHideDescription;

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

        void setProfile(@NotNull Mannequin mannequin, @NotNull org.bukkit.profile.PlayerProfile profile) {
            try {
                if (setPlayerProfile != null) {
                    setPlayerProfile.invoke(mannequin, profile);
                }   else if (setProfile != null && resolvableProfile != null) {
                    Object resolvable = resolvableProfile.invoke(null, profile);
                    setProfile.invoke(mannequin, resolvable);
                }
            }   catch (ReflectiveOperationException | IllegalArgumentException ex) {
                throw new IllegalStateException("Could not copy player profile onto Mannequin", ex);
            }
        }

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
