package com.lauriethefish.betterportals.bukkit.entity;

import com.google.inject.assistedinject.Assisted;
import com.lauriethefish.betterportals.api.PortalPosition;
import com.lauriethefish.betterportals.bukkit.config.MiscConfig;
import com.lauriethefish.betterportals.bukkit.config.RenderConfig;
import com.lauriethefish.betterportals.bukkit.entity.faking.MannequinFakePlayerManager;
import com.lauriethefish.betterportals.bukkit.entity.PortalMomentum;
import com.lauriethefish.betterportals.bukkit.math.MathUtil;
import com.lauriethefish.betterportals.bukkit.math.PortalTransformations;
import com.lauriethefish.betterportals.bukkit.net.IPortalClient;
import com.lauriethefish.betterportals.bukkit.player.IPlayerData;
import com.lauriethefish.betterportals.bukkit.player.IPlayerDataManager;
import com.lauriethefish.betterportals.bukkit.portal.IPortal;
import com.lauriethefish.betterportals.bukkit.portal.predicate.IPortalPredicateManager;
import com.lauriethefish.betterportals.bukkit.util.MaterialUtil;
import com.lauriethefish.betterportals.shared.logging.Logger;
import com.lauriethefish.betterportals.shared.net.RequestException;
import com.lauriethefish.betterportals.shared.net.requests.TeleportRequest;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import jakarta.inject.Inject;
import java.util.*;
import java.util.function.Consumer;

// Stores the two lists of entities at the origin and destination of a portal
// (or only 1 if specified)
public class PortalEntityManager implements IPortalEntityManager {
    private final IPortal portal;
    private final MiscConfig miscConfig;
    private final RenderConfig renderConfig;
    private final IPortalPredicateManager predicateManager;
    private final Logger logger;
    private final IPortalClient portalClient;
    private final Set<Player> alreadyTeleporting = new HashSet<>();
    private final JavaPlugin pl;
    private final IEntityFinder entityFinder;
    private final IPlayerDataManager playerDataManager;
    private final PortalMomentum portalMomentum;
    private final MannequinFakePlayerManager mannequinManager;

    private final boolean requireDestination;

    @Getter private Collection<Entity> destinationEntities = null;
    private Map<Entity, Location> originEntities = null;

    @Inject
    public PortalEntityManager(@Assisted IPortal portal, @Assisted boolean requireDestination, MiscConfig miscConfig, RenderConfig renderConfig, IPortalPredicateManager predicateManager, Logger logger, IPortalClient
            portalClient, JavaPlugin pl, IEntityFinder entityFinder, IPlayerDataManager playerDataManager, PortalMomentum portalMomentum, MannequinFakePlayerManager mannequinManager) {
        this.portal = portal;
        this.requireDestination = requireDestination;
        this.miscConfig = miscConfig;
        this.renderConfig = renderConfig;
        this.predicateManager = predicateManager;
        this.logger = logger;
        this.portalClient = portalClient;
        this.pl = pl;
        this.entityFinder = entityFinder;
        this.playerDataManager = playerDataManager;
        this.portalMomentum = portalMomentum;
        this.mannequinManager = mannequinManager;
    }

    @Override
    public void update(int ticksSinceActivated) {
        // Only update the entity lists when it's time to via the entity check interval
        if(ticksSinceActivated % miscConfig.getEntityCheckInterval() == 0) {
            updateEntityLists();
        }

        handleTeleportation(false);
    }

    @Override
    public void updateNonPlayerTeleportation() {
        if(!miscConfig.isNonPlayerTeleportationWithoutPlayers()) { return; }
        if(portal.isCrossServer() || !portal.allowsNonPlayerTeleportation()) { return; }

        // Vanilla only teleports entities in loaded chunks, and this must
        // never force-load a chunk just to look for mobs.
        Location origin = portal.getOriginPos().getLocation();
        World originWorld = origin == null ? null : origin.getWorld();
        if(originWorld == null || !originWorld.isChunkLoaded(origin.getBlockX() >> 4, origin.getBlockZ() >> 4)) { return; }

        // Origin list only: destination entities are for rendering through
        // the portal, which only matters while a player is viewing it.
        refreshOriginEntities();
        handleTeleportation(true);
    }

    private void updateEntityLists() {
        if(requireDestination) {
            destinationEntities = getNearbyEntities(destinationEntities, portal.getDestPos());
        }

        refreshOriginEntities();
    }

    private void refreshOriginEntities() {
        // Copy the old entities over to a new hashmap
        Map<Entity, Location> oldOriginEntities = originEntities;
        originEntities = new HashMap<>();

        getNearbyEntities(portal.getOriginPos(), entity -> {
            // A Mannequin fake is a real entity standing just behind the
            // portal in this world: it must never enter the origin list,
            // or the hide-behind-portal check would destroy it for the
            // viewer and the teleport check could teleport it (2026-11-06
            // live test: the fake appeared for a second, then vanished).
            if (mannequinManager.isFakePlayer(entity)) { return; }
            Location oldLocation = oldOriginEntities == null ? null : oldOriginEntities.get(entity);
            originEntities.put(entity, oldLocation != null ? oldLocation : entity.getLocation());
        });
    }

    private void handleTeleportation(boolean nonPlayersOnly) {
        if(originEntities == null) { return; }
        List<Entity> toRemove = new ArrayList<>();

        // Check each entity at the origin to see if it teleported
        for(Map.Entry<Entity, Location> entry : originEntities.entrySet()) {
            Entity entity = entry.getKey();
            Location lastPosition = entry.getValue();
            Location currentPosition = entity.getLocation();

            // Players are only ever teleported by the active path: a player
            // standing in a portal always activates it. Skipping them here
            // still refreshes their stored position (below).
            if(nonPlayersOnly && entity instanceof Player) {
                entry.setValue(currentPosition);
                continue;
            }

            // Only players can teleport through cross-server portals
            if (!(entity instanceof Player) && (!portal.allowsNonPlayerTeleportation() || portal.isCrossServer())) {
                entry.setValue(currentPosition);
                continue;
            }

            // Use an intersection check to see if it moved through the portal
            if (lastPosition != null) {
                boolean didWalkThroughPortal = portal.getTransformations()
                        .createIntersectionChecker(lastPosition.toVector())
                        .checkIfIntersects(currentPosition.toVector());

                // Vanilla also teleports an entity that is simply standing
                // in portal blocks, with no wait and no plane crossing:
                // a mob spawned/pushed into a portal, or an item/minecart
                // at rest in one, goes through within a couple of ticks
                // (measured on plain Paper 1.21.8: still pig ~4 ticks,
                // item ~5, minecart ~6). The crossing check alone never
                // fires for those, so they would sit in the portal forever.
                // Players are excluded on purpose: standing in a portal
                // looking through it is the point of the plugin, and a
                // dwell check would teleport a player who is only viewing.
                // But the dwell check must NOT fire for an entity that is
                // moving AWAY from the plane — one that just arrived at
                // the destination and is leaving the portal (a crossbow
                // arrow shot through it, 2026-11-09). Without that guard
                // the destination's own dwell check teleports it straight
                // back, and the two portals bounce it between them every
                // tick, so the arrow looks stuck in the portal instead of
                // flying on. Standing still (velocity ~0) is not moving
                // away, so mobs at rest in a portal still teleport.
                boolean isStandingInPortal = !(entity instanceof Player) && isInsidePortalBlocks(entity)
                        && !isMovingAwayFromPortal(entity);

                if ((didWalkThroughPortal || isStandingInPortal) && checkCanTeleport(entity)) {
                    if (portal.isCrossServer()) {
                        assert entity instanceof Player;
                        teleportCrossServer((Player) entity);
                    } else {
                        teleportLocal(entity);
                    }
                    toRemove.add(entity);
                    continue;
                }
            }

            entry.setValue(currentPosition);
        }
        toRemove.forEach(originEntities::remove);
    }

    /**
     * Whether any block overlapped by {@code entity}'s feet column is a
     * portal block. Every plugin portal (nether or custom) is built from
     * {@link MaterialUtil#PORTAL_MATERIAL}, so this is the same test for
     * both kinds.
     */
    private boolean isInsidePortalBlocks(@NotNull Entity entity) {
        Location loc = entity.getLocation();
        World world = loc.getWorld();
        if(world == null) { return false; }
        return isInsidePortalBlocks(loc.getY(), entity.getHeight(),
                y -> world.getBlockAt(loc.getBlockX(), y, loc.getBlockZ()).getType() == MaterialUtil.PORTAL_MATERIAL);
    }

    /**
     * Pure form of the in-portal test, for unit tests: {@code isPortalBlock}
     * answers for each block-Y in the entity's feet column. The entity
     * occupies blocks {@code floor(feetY)} .. {@code floor(feetY + height)}
     * (exclusive at the top, so a 1-block-tall entity at exactly y=65
     * occupies only block 65).
     */
    public static boolean isInsidePortalBlocks(double feetY, double height, @NotNull java.util.function.IntPredicate isPortalBlock) {
        int firstBlock = (int) Math.floor(feetY);
        int lastBlock = (int) Math.floor(feetY + height - 1e-6);
        for(int y = firstBlock; y <= lastBlock; y++) {
            if(isPortalBlock.test(y)) { return true; }
        }
        return false;
    }

    /**
     * Whether {@code entity} is moving away from this portal's plane, i.e.
     * leaving the portal rather than going through it. The plane passes
     * through the portal's origin position (always the centre of the
     * plane) with normal {@code originPos.getDirection()}.
     */
    private boolean isMovingAwayFromPortal(@NotNull Entity entity) {
        Vector normal = portal.getOriginPos().getDirection().toVector();
        Vector toEntity = entity.getLocation().toVector().subtract(portal.getOriginPos().getVector());
        return isMovingAwayFromPortalPlane(toEntity.dot(normal), entity.getVelocity().dot(normal));
    }

    /**
     * Pure form of the moving-away test, for unit tests:
     * {@code signedDistance} is the entity's signed distance from the
     * portal plane along the plane normal, {@code velocityAlongNormal} its
     * velocity component along that normal. It is moving away when both
     * have the same sign (and the velocity is non-zero): on the + side
     * moving further +, or on the - side moving further -. Standing still
     * (velocity 0) or exactly on the plane (distance 0) is not moving away.
     */
    public static boolean isMovingAwayFromPortalPlane(double signedDistance, double velocityAlongNormal) {
        return signedDistance * velocityAlongNormal > 0.0;
    }

    /** Whether the activation-independent non-player sweep may run for a portal. Pure logic, for unit tests. */
    public static boolean shouldSweepNonPlayerTeleportation(boolean crossServer, boolean allowsNonPlayerTeleportation,
                                                     boolean originChunkLoaded, boolean sweepEnabled) {
        return sweepEnabled && !crossServer && allowsNonPlayerTeleportation && originChunkLoaded;
    }

    public Collection<Entity> getOriginEntities() {
        return originEntities.keySet();
    }

    private Collection<Entity> getNearbyEntities(@Nullable Collection<Entity> existing, PortalPosition position) {
        return entityFinder.getNearbyEntities(existing, position.getLocation(), renderConfig.getMaxXZ(), renderConfig.getMaxY(), renderConfig.getMaxXZ());
    }

    private void getNearbyEntities(PortalPosition position, Consumer<Entity> sendTo) {
        entityFinder.getNearbyEntities(position.getLocation(), renderConfig.getMaxXZ(), renderConfig.getMaxY(), renderConfig.getMaxXZ(), sendTo);
    }

    /**
     * Verifies that <code>entity</code> can teleport using {@link IPortalPredicateManager}
     * @param entity Entity to check
     * @return Whether it can teleport
     */
    private boolean checkCanTeleport(Entity entity) {
        // Entities riding others do not get teleported by portals.
        // Instead, the vehicle teleports through the portal, and takes its passengers with it.
        if(entity.getVehicle() != null) {
            return false;
        }

        // Enforce teleportation predicates
        if(entity instanceof Player) {
            return predicateManager.canTeleport(portal, (Player) entity);
        }   else    {
            return true;
        }
    }

    /**
     * Limits the coordinates of <code>preferred</code> to avoid spawning players on top of portals when they're slightly inside the block hitbox
     * @param preferred Position to limit to the hitbox
     * @return The spawn position, or <code>preferred</code> if none was found.
     */
    private @NotNull Location limitToBlockHitbox(@NotNull Location preferred) {
        Location flooredPos = MathUtil.floor(preferred);
        Location blockOffset = preferred.clone().subtract(flooredPos);

        if(blockOffset.getZ() > 0.6 && preferred.clone().add(0.0, 0.0, 1.0).getBlock().getType().isSolid()) {
            blockOffset.setZ(0.6);
        }
        if(blockOffset.getX() > 0.6 && preferred.clone().add(1.0, 0.0, 0.0).getBlock().getType().isSolid()) {
            blockOffset.setX(0.6);
        }
        if(blockOffset.getZ() < 0.4 && preferred.clone().add(0.0, 0.0, -1.0).getBlock().getType().isSolid()) {
            blockOffset.setZ(0.4);
        }
        if(blockOffset.getX() < 0.4 && preferred.clone().add(-1.0, 0.0, 0.0).getBlock().getType().isSolid()) {
            blockOffset.setX(0.4);
        }
        logger.finer("Fixing position. Floored pos: %s. Block offset: %s", flooredPos.toVector(), blockOffset.toVector());

        return blockOffset.add(flooredPos);
    }

    /**
     * Moves the entity from the origin to the destination of the portal.
     * This also preserves/rotates entity velocity and direction.
     * @param entity The entity to be teleported
     */
    private void teleportLocal(Entity entity) {
        PortalTransformations transformations = portal.getTransformations();
        double scale = transformations.getScale();

        Location destPos;
        destPos = entity.getLocation().subtract(portal.getOriginPos().getVector());
        // Offsets (like velocities) are rotated *and scaled*; the look
        // direction below is rotated only, so it stays a unit vector.
        destPos = transformations.rotateAndScaleToDestination(destPos.toVector()).toLocation(Objects.requireNonNull(portal.getDestPos().getWorld()));
        destPos.add(portal.getDestPos().getVector());
        destPos.setDirection(transformations.rotateToDestination(entity.getLocation().getDirection()));

        if(entity instanceof Player) {
            destPos = limitToBlockHitbox(destPos);
        }   else    {
            destPos.add(0.0, 0.2, 0.0);
        }

        // Teleporting an entity removes the velocity, so we have to re-add
        // it — rotated and scaled by the portal transform, then protected
        // from the landing tick by the 26.2 attribute window (PortalMomentum).
        Vector velocity = PortalMomentum.transformVelocity(entity.getVelocity(), transformations.getRotateToDestination(), scale);

        logger.fine("Teleporting entity with ID %d and of type %s to position %s", entity.getEntityId(), entity.getType(), destPos.toVector());

        boolean handlePassengers = entity.getWorld() != destPos.getWorld();
        List<Entity> passengers = entity.getPassengers();
        if(handlePassengers) {
            for(Entity passenger : passengers) {
                entity.removePassenger(passenger);
                teleportLocal(passenger);
            }
        }


        portalMomentum.teleportWithMomentum(entity, destPos, velocity, scale);

        if(handlePassengers) {
            passengers.forEach(entity::addPassenger);
        }
    }

    private void teleportCrossServer(Player player) {
        if(alreadyTeleporting.contains(player)) {
            return;
        }

        alreadyTeleporting.add(player);

        IPlayerData playerData = playerDataManager.getPlayerData(player);
        if(playerData == null) {
            logger.warning("Player with unregistered data %s", player.getUniqueId());
            return;
        }
        playerData.freezePortalViews();

        Location destPosition = portal.getTransformations().moveToDestination(player.getLocation());
        destPosition.setDirection(portal.getTransformations().rotateToDestination(player.getLocation().getDirection()));
        // Cross-server velocity is also rotated and scaled; the receiving
        // server re-applies it with the momentum window (PlayerDataManager).
        Vector destVelocity = PortalMomentum.transformVelocity(player.getVelocity(),
                portal.getTransformations().getRotateToDestination(), portal.getTransformations().getScale());


        TeleportRequest request = new TeleportRequest();
        request.setDestWorldId(portal.getDestPos().getWorldId());
        request.setDestWorldName(portal.getDestPos().getWorldName());
        request.setDestServer(portal.getDestPos().getServerName());
        request.setPlayerId(player.getUniqueId());

        request.setDestX(destPosition.getX());
        request.setDestY(destPosition.getY());
        request.setDestZ(destPosition.getZ());
        request.setDestVelX(destVelocity.getX());
        request.setDestVelY(destVelocity.getY());
        request.setDestVelZ(destVelocity.getZ());

        request.setFlying(player.isFlying());
        request.setGliding(player.isGliding());

        request.setDestPitch(destPosition.getPitch());
        request.setDestYaw(destPosition.getYaw());

        portalClient.sendRequestToProxy(request, (response) -> {
            try {
                response.checkForErrors();
                alreadyTeleporting.remove(player);
            }   catch(RequestException ex) {
                if(!pl.isEnabled()) {return;}
                logger.warning("An error occurred while attempting to teleport a player across servers");
                ex.printStackTrace();
            }
        });
    }
}
