package com.lauriethefish.betterportals.bukkit.portal.spawning;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.lauriethefish.betterportals.api.PortalDirection;
import com.lauriethefish.betterportals.bukkit.config.PortalSpawnConfig;
import com.lauriethefish.betterportals.bukkit.entity.PortalMomentum;
import com.lauriethefish.betterportals.bukkit.math.MathUtil;
import com.lauriethefish.betterportals.bukkit.portal.IPortal;
import com.lauriethefish.betterportals.bukkit.portal.IPortalManager;
import com.lauriethefish.betterportals.bukkit.portal.storage.IPortalStorage;
import com.lauriethefish.betterportals.bukkit.util.MaterialUtil;
import com.lauriethefish.betterportals.shared.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Recovers nether portals that exist as blocks in the world but are not
 * registered in the {@link IPortalManager}, so players never have to
 * rebuild a portal after an update, a crash, or a lost
 * <code>portals.yml</code>.
 *
 * <p>Portals are only persisted to <code>portals.yml</code> on the
 * autosave (every {@code portalSaveInterval}, 5 minutes by default) and
 * on a clean shutdown. A portal lit shortly before a restart or a crash,
 * or one whose save entry was lost or failed to load, is simply gone
 * from the manager on the next startup: the player walks into it, the
 * plugin finds no portal there, and vanilla takes over with a warning
 * telling them to relight it. Rebuilding (re-lighting) re-registers it,
 * which is why the 2026-11-09 report "fixed itself" when the player
 * rebuilt the portal.
 *
 * <p>Recovery runs lazily, the first time a player stands in an
 * unregistered portal block: the portal window is detected from the
 * blocks, then the same destination search the lighting path uses
 * ({@link IPortalSpawner#findAndSpawnDestination}) finds the existing
 * destination portal (or creates one) and both directions are
 * registered. Players standing in the recovered portal are teleported
 * through at once, so the first use after an update feels seamless
 * instead of needing a step out and back in. The recovered portal is
 * saved to <code>portals.yml</code> immediately, so it survives the
 * next restart without needing to be recovered again.
 *
 * <p>Only nether portals are recovered (end portals are not supported
 * on this branch). Worlds configured to use vanilla portals are left
 * alone, and oversized windows are not recovered.
 */
@Singleton
public class PortalRecovery {
    /** Don't retry the same portal block more often than this, in milliseconds. */
    private static final long RETRY_COOLDOWN_MS = 10_000L;

    private final IPortalManager portalManager;
    private final IPortalSpawner portalSpawner;
    private final IPortal.Factory portalFactory;
    private final PortalSpawnConfig spawnConfig;
    private final IPortalStorage portalStorage;
    private final PortalMomentum portalMomentum;
    private final Logger logger;

    /** Portal-block locations a recovery was already attempted for, and when. */
    private final Map<String, Long> attemptedRecoveries = new HashMap<>();

    @Inject
    public PortalRecovery(IPortalManager portalManager, IPortalSpawner portalSpawner, IPortal.Factory portalFactory,
                          PortalSpawnConfig spawnConfig, IPortalStorage portalStorage,
                          PortalMomentum portalMomentum, Logger logger) {
        this.portalManager = portalManager;
        this.portalSpawner = portalSpawner;
        this.portalFactory = portalFactory;
        this.spawnConfig = spawnConfig;
        this.portalStorage = portalStorage;
        this.portalMomentum = portalMomentum;
        this.logger = logger;
    }

    /**
     * Recovers the nether portal window containing the portal block at
     * {@code portalBlockLoc}, if it is not already registered.
     * Safe to call every tick: it is a no-op unless the block is a portal
     * block with no registered portal nearby, and attempts for the same
     * block are throttled.
     * @return true if a recovery was started for this block
     */
    public boolean recoverPortalAt(@NotNull Location portalBlockLoc) {
        World world = portalBlockLoc.getWorld();
        if(world == null) { return false; }
        if(spawnConfig.isWorldDisabled(world)) { return false; }
        if(portalBlockLoc.getBlock().getType() != MaterialUtil.PORTAL_MATERIAL) { return false; }

        // Already registered? Nothing to recover.
        Vector maxPortalSize = spawnConfig.getMaxPortalSize();
        double radius = Math.max(maxPortalSize.getX(), maxPortalSize.getY()) + 2;
        if(portalManager.findClosestPortal(portalBlockLoc, radius) != null) { return false; }

        // Throttle: the destination search is async and takes a few ticks,
        // and a failed recovery must not be retried every tick.
        String key = recoveryKey(portalBlockLoc);
        long now = System.currentTimeMillis();
        Long lastAttempt = attemptedRecoveries.get(key);
        if(lastAttempt != null && now - lastAttempt < RETRY_COOLDOWN_MS) { return false; }
        attemptedRecoveries.put(key, now);
        // Drop stale entries so the map cannot grow without bound.
        attemptedRecoveries.entrySet().removeIf(entry -> now - entry.getValue() > RETRY_COOLDOWN_MS);

        PortalWindow window = findPortalWindow(portalBlockLoc, (x, y, z) ->
                world.getBlockAt(x, y, z).getType() == MaterialUtil.PORTAL_MATERIAL);
        if(window == null) {
            logger.fine("Not recovering portal at %s: no valid rectangular window", portalBlockLoc.toVector());
            return false;
        }
        if(!MathUtil.lessThanEq(window.size, maxPortalSize)) {
            logger.fine("Not recovering portal at %s: window %s exceeds the max portal size %s",
                    portalBlockLoc.toVector(), window.size, maxPortalSize);
            return false;
        }

        Location bottomLeftLocation = window.bottomLeftFrame.toLocation(world);
        logger.fine("Recovering unregistered portal at %s (size %s, direction %s)",
                bottomLeftLocation.toVector(), window.size, window.direction);

        PortalSpawnPosition originPosition = new PortalSpawnPosition(bottomLeftLocation, window.size, window.direction);
        boolean started = portalSpawner.findAndSpawnDestination(bottomLeftLocation, window.size,
                destination -> {
                    IPortal portal = registerPortals(originPosition, destination, window.size);
                    teleportPlayersInside(portal);
                    savePortals();
                });
        if(!started) {
            logger.fine("Not recovering portal at %s: no world link for its world", portalBlockLoc.toVector());
        }
        return started;
    }

    private static String recoveryKey(Location loc) {
        return loc.getWorld().getUID() + ":" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    /** Registers both directions of a portal, mirroring SpawningEvents. Returns the forward portal. */
    private @NotNull IPortal registerPortals(PortalSpawnPosition origin, PortalSpawnPosition destination, Vector size) {
        IPortal portal = portalFactory.create(origin.toPortalPosition(), destination.toPortalPosition(), size, false, UUID.randomUUID(), null, null, true);
        IPortal reversePortal = portalFactory.create(destination.toPortalPosition(), origin.toPortalPosition(), size, false, UUID.randomUUID(), null, null, true);

        portalManager.registerPortal(portal);
        portalManager.registerPortal(reversePortal);
        logger.fine("Recovered portal registered: origin %s, destination %s", origin, destination);
        return portal;
    }

    /**
     * Teleports any player currently standing inside the recovered
     * portal's origin window through it at once. Without this, a player
     * who triggered the recovery is already past the plane, so the
     * normal crossing check would never fire for them and they would
     * have to step out and back in.
     */
    private void teleportPlayersInside(@NotNull IPortal portal) {
        for(Player player : Bukkit.getOnlinePlayers()) {
            if(player.getLocation().getBlock().getType() != MaterialUtil.PORTAL_MATERIAL
                    && player.getEyeLocation().getBlock().getType() != MaterialUtil.PORTAL_MATERIAL) {
                continue;
            }
            // Only players at this portal, not one elsewhere.
            Vector maxPortalSize = spawnConfig.getMaxPortalSize();
            double radius = Math.max(maxPortalSize.getX(), maxPortalSize.getY()) + 2;
            if(portalManager.findClosestPortal(player.getLocation(), radius) != portal) { continue; }

            Location dest = portal.getTransformations().moveToDestination(player.getLocation());
            dest.setDirection(portal.getTransformations().rotateToDestination(player.getLocation().getDirection()));
            Vector velocity = PortalMomentum.transformVelocity(player.getVelocity(),
                    portal.getTransformations().getRotateToDestination(), portal.getTransformations().getScale());
            portalMomentum.teleportWithMomentum(player, dest, velocity, portal.getTransformations().getScale());
            logger.fine("Teleported player %s through recovered portal", player.getUniqueId());
        }
    }

    /** Persists the recovered portal immediately, so it survives the next restart. */
    private void savePortals() {
        try {
            portalStorage.savePortals();
        }   catch(IOException | RuntimeException ex) {
            logger.warning("Failed to save portals after recovering one: %s", ex.getMessage());
        }
    }

    /**
     * A detected portal window: its direction, window size, and the
     * bottom-left frame block (the same inputs
     * {@link PortalSpawnPosition} takes).
     */
    public static class PortalWindow {
        public final PortalDirection direction;
        public final Vector size;
        /** Block coordinates of the bottom-left obsidian frame block. */
        public final Vector bottomLeftFrame;

        public PortalWindow(PortalDirection direction, Vector size, Vector bottomLeftFrame) {
            this.direction = direction;
            this.size = size;
            this.bottomLeftFrame = bottomLeftFrame;
        }
    }

    /** Tests whether the block at the given coordinates is a portal block. */
    @FunctionalInterface
    public interface PortalBlockTest {
        boolean isPortalBlock(int x, int y, int z);
    }

    /**
     * Finds the rectangular nether portal window containing the portal
     * block at {@code portalBlockLoc}. Pure logic over an
     * {@link PortalBlockTest}, for unit tests: no Bukkit state.
     * Mirrors the direction/size math in SpawningEvents.
     * @return the window, or null if the block is not part of a valid
     *         rectangular window at least 2 wide and 3 high
     */
    public static @Nullable PortalWindow findPortalWindow(@NotNull Location portalBlockLoc, @NotNull PortalBlockTest isPortalBlock) {
        int x = portalBlockLoc.getBlockX();
        int y = portalBlockLoc.getBlockY();
        int z = portalBlockLoc.getBlockZ();
        if(!isPortalBlock.isPortalBlock(x, y, z)) { return null; }

        // Which way does the window extend? A portal is at least 2 wide,
        // so a horizontal portal-block neighbour decides the plane.
        boolean extendsX = isPortalBlock.isPortalBlock(x + 1, y, z) || isPortalBlock.isPortalBlock(x - 1, y, z);
        boolean extendsZ = isPortalBlock.isPortalBlock(x, y, z + 1) || isPortalBlock.isPortalBlock(x, y, z - 1);
        if(!extendsX && !extendsZ) { return null; }
        // If it extends both ways it is not a single rectangular window.
        if(extendsX && extendsZ) { return null; }

        // Expand along the window's horizontal axis and vertically.
        int minAxis, maxAxis, minY = y, maxY = y;
        if(extendsX) {
            minAxis = x;
            while(isPortalBlock.isPortalBlock(minAxis - 1, y, z)) { minAxis--; }
            maxAxis = x;
            while(isPortalBlock.isPortalBlock(maxAxis + 1, y, z)) { maxAxis++; }
        }   else {
            minAxis = z;
            while(isPortalBlock.isPortalBlock(x, y, minAxis - 1)) { minAxis--; }
            maxAxis = z;
            while(isPortalBlock.isPortalBlock(x, y, maxAxis + 1)) { maxAxis++; }
        }
        while(isPortalBlock.isPortalBlock(x, minY - 1, z)) { minY--; }
        while(isPortalBlock.isPortalBlock(x, maxY + 1, z)) { maxY++; }

        // The window must be a full rectangle of portal blocks.
        for(int axis = minAxis; axis <= maxAxis; axis++) {
            for(int yy = minY; yy <= maxY; yy++) {
                int px = extendsX ? axis : x;
                int pz = extendsX ? z : axis;
                if(!isPortalBlock.isPortalBlock(px, yy, pz)) { return null; }
            }
        }

        Vector lowPosition, highPosition;
        if(extendsX) {
            lowPosition = new Vector(minAxis, minY, z);
            highPosition = new Vector(maxAxis, maxY, z);
        }   else {
            lowPosition = new Vector(x, minY, minAxis);
            highPosition = new Vector(x, maxY, maxAxis);
        }

        PortalDirection direction = findPortalDirection(highPosition, lowPosition);
        Vector size = findPortalSize(highPosition, lowPosition, direction);
        if(size.getBlockX() < 2 || size.getBlockY() < 3) { return null; }

        // Bottom-left frame block, one left and one down from the lowest
        // portal block (mirrors SpawningEvents).
        Vector bottomLeftFrame = lowPosition.clone().subtract(direction.swapVector(new Vector(1.0, 1.0, 0.0)));
        return new PortalWindow(direction, size, bottomLeftFrame);
    }

    /** Mirrors SpawningEvents.findPortalDirection. */
    private static PortalDirection findPortalDirection(Vector highPosition, Vector lowPosition) {
        if(highPosition.getX() == lowPosition.getX()) { return PortalDirection.EAST; }
        if(highPosition.getZ() == lowPosition.getZ()) { return PortalDirection.NORTH; }
        throw new IllegalStateException("Portal window is not on a valid plane");
    }

    /** Mirrors SpawningEvents.findPortalSize. */
    private static Vector findPortalSize(Vector highPosition, Vector lowPosition, PortalDirection direction) {
        Vector portalSize = direction.swapVector(highPosition.clone().subtract(lowPosition));
        portalSize.add(new Vector(1.0, 1.0, 0.0));
        return portalSize;
    }
}
