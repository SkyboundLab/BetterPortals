package com.lauriethefish.betterportals.bukkit.entity.faking;

import com.comphenix.protocol.wrappers.EnumWrappers;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.lauriethefish.betterportals.bukkit.config.MiscConfig;
import com.lauriethefish.betterportals.bukkit.config.RenderConfig;
import com.lauriethefish.betterportals.bukkit.nms.AnimationType;
import com.lauriethefish.betterportals.bukkit.portal.IPortal;
import com.lauriethefish.betterportals.bukkit.util.FoliaScheduler;
import lombok.Getter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class EntityTracker implements IEntityTracker    {
    /**
     * Delay in ticks between sending the player info for fake players to the client and removing it (it is removed to avoid showing them twice in the tab menu)
     */
    private static final int fakePlayerTabListRemoveDelay = 20;

    private final Entity entity;
    @Getter private final EntityInfo entityInfo;
    @Getter private final IPortal portal;
    private final IEntityPacketManipulator packetManipulator;
    private final EntityTrackingManager entityTrackingManager;
    private final JavaPlugin pl;

    private final Set<Player> trackingPlayers = new HashSet<>();

    private final EntityEquipmentWatcher equipmentWatcher;
    private Vector lastPosition;
    private Vector lastDirection;
    private Vector lastVelocity;
    private float lastHeadRotation;
    private List<Entity> lastMounts;

    private final int metadataUpdateInterval;
    private int ticksSinceCreated = 0;

    private final MannequinFakePlayerManager mannequinManager;
    private final boolean useMannequin;
    private Mannequin mannequin;
    private boolean mannequinFailed;

    @Inject
    public EntityTracker(@Assisted Entity entity, @Assisted IPortal portal, IEntityPacketManipulator packetManipulator, EntityTrackingManager entityTrackingManager, RenderConfig renderConfig, JavaPlugin pl, MannequinFakePlayerManager mannequinManager, MiscConfig miscConfig) {
        // Non-living entities don't have equipment
        this.equipmentWatcher = entity instanceof LivingEntity ? new EntityEquipmentWatcher((LivingEntity) entity) : null;
        this.entity = entity;
        this.entityTrackingManager = entityTrackingManager;
        this.portal = portal;
        this.entityInfo = new EntityInfo(portal.getTransformations(), entity);
        this.packetManipulator = packetManipulator;
        this.metadataUpdateInterval = renderConfig.getEntityMetadataUpdateInterval();
        this.pl = pl;
        this.mannequinManager = mannequinManager;
        this.useMannequin = miscConfig.isUseMannequinFakePlayers();
    }

    public void update() {
        if (mannequin != null) {
            if (mannequin.isValid() && entity instanceof Player source) {
                mannequinManager.sync(mannequin, source,
                        MannequinFakePlayerManager.renderedLocationFor(entityInfo, mannequin.getWorld()));
                ticksSinceCreated++;
                return;
            }
            mannequinManager.unregister(mannequin);
            mannequin = null;
            mannequinFailed = true;
            fallBackToPackets();
        }

        sendMovementUpdates();

        // Equipment is disabled for living entities
        if(equipmentWatcher != null) {
            Map<EnumWrappers.ItemSlot, ItemStack> equipmentChanges = equipmentWatcher.checkForChanges();
            if(equipmentChanges.size() > 0) {
                packetManipulator.sendEntityEquipment(entityInfo, equipmentChanges, trackingPlayers);
            }
        }

        List<Entity> newMounts = entity.getPassengers();
        if(!newMounts.equals(lastMounts)) {
            lastMounts = newMounts;

            List<EntityInfo> visibleMounts = new ArrayList<>();
            for(Entity entity : newMounts) {
                IEntityTracker tracker = entityTrackingManager.getTracker(portal, entity);
                if(tracker != null) {
                    visibleMounts.add(tracker.getEntityInfo());
                }
            }

            packetManipulator.sendMount(entityInfo, visibleMounts, trackingPlayers);
        }

        // The metadata packet contains tons of stuff, e.g. sneaking and beds on newer versions
        // It's quite expensive to send a full update, so we only do this every N ticks
        if(ticksSinceCreated % metadataUpdateInterval == 0) {
            packetManipulator.sendMetadata(entityInfo, trackingPlayers);
        }

        packetManipulator.sendEntityHeadRotation(entityInfo, trackingPlayers);

        Vector velocity = entity.getVelocity();
        if(lastVelocity != null && !velocity.equals(lastVelocity)) {
            packetManipulator.sendEntityVelocity(entityInfo, velocity, trackingPlayers);
            lastVelocity = velocity;
        }

        ticksSinceCreated++;
    }

    @Override
    public void onAnimation(@NotNull AnimationType animationType) {
        if (mannequin != null) { return; }
        packetManipulator.sendEntityAnimation(entityInfo, trackingPlayers, animationType);
    }

    @Override
    public void onPickup(@NotNull EntityInfo pickedUp) {
        if (mannequin != null) { return; }
        packetManipulator.sendEntityPickupItem(entityInfo, pickedUp, trackingPlayers);
    }

    private void fallBackToPackets() {
        for (Player player : new HashSet<>(trackingPlayers)) {
            boolean sendingPlayerProfile = entity instanceof Player
                    && !entityInfo.getEntityUniqueId().equals(entityInfo.getEntity().getUniqueId());
            if (sendingPlayerProfile) {
                packetManipulator.sendAddPlayerProfile(entityInfo, Collections.singleton(player));
            }
            packetManipulator.showEntity(entityInfo, player);
            if (sendingPlayerProfile) {
                FoliaScheduler.runDelayedOnEntity(pl, entity,
                        () -> packetManipulator.sendRemovePlayerProfile(entityInfo, Collections.singleton(player)),
                        fakePlayerTabListRemoveDelay);
            }
        }
    }

    // Handles sending all movement and looking packets
    private void sendMovementUpdates() {
        Vector currentPosition = entity.getLocation().toVector();
        Vector currentDirection = entity.getLocation().getDirection();

        boolean positionChanged = lastPosition != null && !currentPosition.equals(lastPosition);
        boolean rotationChanged = lastDirection != null && !currentDirection.equals(lastDirection);
        Vector posOffset = lastPosition == null ? new Vector() : currentPosition.clone().subtract(lastPosition);

        lastPosition = currentPosition;
        lastDirection = currentDirection;

        // Relative move packets have a limit of 8 blocks before we have to just send a teleport packet
        boolean canUseRelativeMove = posOffset.getX() < 8 && posOffset.getY() < 8 && posOffset.getZ() < 8;
        // We must combine the move and look to avoid issues on newer versions
        if (positionChanged && !canUseRelativeMove) {
            packetManipulator.sendEntityTeleport(entityInfo, trackingPlayers);
        } else if (positionChanged && rotationChanged) {
            packetManipulator.sendEntityMoveLook(entityInfo, posOffset, trackingPlayers);
        } else if (positionChanged) {
            packetManipulator.sendEntityMove(entityInfo, posOffset, trackingPlayers);
        } else if (rotationChanged) {
            packetManipulator.sendEntityLook(entityInfo, trackingPlayers);
        }

        // Bukkit uses the yaw as the head rotation for some reason, so we do it with that
        float headRotation = entity.getLocation().getYaw();
        if(lastHeadRotation != headRotation) {
            lastHeadRotation = headRotation;
            packetManipulator.sendEntityHeadRotation(entityInfo, trackingPlayers);
        }
    }

    public void addTracking(@NotNull Player player) {
        if(trackingPlayers.contains(player)) {throw new IllegalArgumentException("Player is already tracking this entity");}

        trackingPlayers.add(player);

        if (useMannequin && !mannequinFailed && entity instanceof Player source) {
            if (mannequin == null) {
                mannequin = mannequinManager.spawn(source,
                        MannequinFakePlayerManager.renderedLocationFor(entityInfo, player.getWorld()));
                if (mannequin == null) {
                    mannequinFailed = true;
                }
            }
            if (mannequin != null) {
                mannequinManager.showTo(mannequin, player);
                return;
            }
        }

        // If the tracker's entity UID is different, and they are a player, we must send a player info packet to add their profile to the server
        boolean sendingPlayerProfile = !entityInfo.getEntityUniqueId().equals(entityInfo.getEntity().getUniqueId()) && entityInfo.getEntity() instanceof Player;
        if(sendingPlayerProfile) {
            packetManipulator.sendAddPlayerProfile(entityInfo, Collections.singleton(player));
        }

        packetManipulator.showEntity(entityInfo, player);

        if(sendingPlayerProfile) {
            FoliaScheduler.runDelayedOnEntity(pl, entity,
                    () -> packetManipulator.sendRemovePlayerProfile(entityInfo, Collections.singleton(player)),
                    fakePlayerTabListRemoveDelay);
        }
    }

    public void removeTracking(@NotNull Player player, boolean sendPackets) {
        if(!trackingPlayers.contains(player)) {throw new IllegalArgumentException("Cannot stop player from tracking entity, they weren't viewing in the first place");}

        trackingPlayers.remove(player);

        if (mannequin != null) {
            if (sendPackets) {
                mannequinManager.hideFrom(mannequin, player);
            }
            if (trackingPlayers.isEmpty()) {
                mannequinManager.remove(mannequin);
                mannequin = null;
            }
            return;
        }

        if(sendPackets) {
            packetManipulator.hideEntity(entityInfo, player);
        }
    }

    public int getTrackingPlayerCount() {
        return trackingPlayers.size();
    }
}
