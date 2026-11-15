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

@Singleton
public class PortalRecovery {
    private static final long RETRY_COOLDOWN_MS = 10_000L;

    private final IPortalManager portalManager;
    private final IPortalSpawner portalSpawner;
    private final IPortal.Factory portalFactory;
    private final PortalSpawnConfig spawnConfig;
    private final IPortalStorage portalStorage;
    private final PortalMomentum portalMomentum;
    private final Logger logger;

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

    public boolean recoverPortalAt(@NotNull Location portalBlockLoc) {
        World world = portalBlockLoc.getWorld();
        if(world == null) { return false; }
        if(spawnConfig.isWorldDisabled(world)) { return false; }
        if(portalBlockLoc.getBlock().getType() != MaterialUtil.PORTAL_MATERIAL) { return false; }

        Vector maxPortalSize = spawnConfig.getMaxPortalSize();
        double radius = Math.max(maxPortalSize.getX(), maxPortalSize.getY()) + 2;
        if(portalManager.findClosestPortal(portalBlockLoc, radius) != null) { return false; }

        String key = recoveryKey(portalBlockLoc);
        long now = System.currentTimeMillis();
        Long lastAttempt = attemptedRecoveries.get(key);
        if(lastAttempt != null && now - lastAttempt < RETRY_COOLDOWN_MS) { return false; }
        attemptedRecoveries.put(key, now);
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

    private @NotNull IPortal registerPortals(PortalSpawnPosition origin, PortalSpawnPosition destination, Vector size) {
        IPortal portal = portalFactory.create(origin.toPortalPosition(), destination.toPortalPosition(), size, false, UUID.randomUUID(), null, null, true);
        IPortal reversePortal = portalFactory.create(destination.toPortalPosition(), origin.toPortalPosition(), size, false, UUID.randomUUID(), null, null, true);

        portalManager.registerPortal(portal);
        portalManager.registerPortal(reversePortal);
        logger.fine("Recovered portal registered: origin %s, destination %s", origin, destination);
        return portal;
    }

    private void teleportPlayersInside(@NotNull IPortal portal) {
        for(Player player : Bukkit.getOnlinePlayers()) {
            if(player.getLocation().getBlock().getType() != MaterialUtil.PORTAL_MATERIAL
                    && player.getEyeLocation().getBlock().getType() != MaterialUtil.PORTAL_MATERIAL) {
                continue;
            }
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

    private void savePortals() {
        try {
            portalStorage.savePortals();
        }   catch(IOException | RuntimeException ex) {
            logger.warning("Failed to save portals after recovering one: %s", ex.getMessage());
        }
    }

    public static class PortalWindow {
        public final PortalDirection direction;
        public final Vector size;
        public final Vector bottomLeftFrame;

        public PortalWindow(PortalDirection direction, Vector size, Vector bottomLeftFrame) {
            this.direction = direction;
            this.size = size;
            this.bottomLeftFrame = bottomLeftFrame;
        }
    }

    @FunctionalInterface
    public interface PortalBlockTest {
        boolean isPortalBlock(int x, int y, int z);
    }

    public static @Nullable PortalWindow findPortalWindow(@NotNull Location portalBlockLoc, @NotNull PortalBlockTest isPortalBlock) {
        int x = portalBlockLoc.getBlockX();
        int y = portalBlockLoc.getBlockY();
        int z = portalBlockLoc.getBlockZ();
        if(!isPortalBlock.isPortalBlock(x, y, z)) { return null; }

        boolean extendsX = isPortalBlock.isPortalBlock(x + 1, y, z) || isPortalBlock.isPortalBlock(x - 1, y, z);
        boolean extendsZ = isPortalBlock.isPortalBlock(x, y, z + 1) || isPortalBlock.isPortalBlock(x, y, z - 1);
        if(!extendsX && !extendsZ) { return null; }
        if(extendsX && extendsZ) { return null; }

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

        Vector bottomLeftFrame = lowPosition.clone().subtract(direction.swapVector(new Vector(1.0, 1.0, 0.0)));
        return new PortalWindow(direction, size, bottomLeftFrame);
    }

    private static PortalDirection findPortalDirection(Vector highPosition, Vector lowPosition) {
        if(highPosition.getX() == lowPosition.getX()) { return PortalDirection.EAST; }
        if(highPosition.getZ() == lowPosition.getZ()) { return PortalDirection.NORTH; }
        throw new IllegalStateException("Portal window is not on a valid plane");
    }

    private static Vector findPortalSize(Vector highPosition, Vector lowPosition, PortalDirection direction) {
        Vector portalSize = direction.swapVector(highPosition.clone().subtract(lowPosition));
        portalSize.add(new Vector(1.0, 1.0, 0.0));
        return portalSize;
    }
}
