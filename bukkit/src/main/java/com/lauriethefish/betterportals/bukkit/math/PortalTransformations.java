package com.lauriethefish.betterportals.bukkit.math;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.lauriethefish.betterportals.api.IntVector;
import com.lauriethefish.betterportals.api.PortalDirection;
import com.lauriethefish.betterportals.api.PortalPosition;
import com.lauriethefish.betterportals.bukkit.config.RenderConfig;
import com.lauriethefish.betterportals.bukkit.portal.IPortal;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

/**
 * Handles the matrices used for transforming coordinates around the origin and destination of portals.
 *
 * <p>Scale/rotation transforms (Immersive Portals-style scaled portals):
 * a portal may have a scale factor (from config, see
 * {@link RenderConfig#getPortalScale(String)}) and a roll angle
 * ({@link RenderConfig#getPortalRollDegrees()}). The scale is applied
 * around the portal centre, between the origin translation and the
 * rotation: {@code originToDestination = T(dest) * R * S * T(-origin)}.
 * Positions, velocities and the fake-block projection all go through the
 * scaled matrices, so a 0.5× portal really does show the destination at
 * half size and shrink your velocity to match. This is an approximation
 * in one place: fake blocks live on an integer grid, so the scaled
 * projection is rounded to whole blocks ({@link Matrix#transform(int, int, int)}).
 * Directions (look vectors) are never scaled — only rotated — since a
 * direction must stay a unit vector.
 */
public class PortalTransformations {
    private final PortalPosition originPos;
    private final PortalPosition destPos;
    private final RenderConfig renderConfig;
    private final Vector portalSize;

    /** Uniform scale factor of this portal (1.0 = unscaled). */
    @Getter private final double scale;
    /** Extra roll about the portal normal, in degrees (0 = none). */
    @Getter private final double rollDegrees;

    @Getter private final Matrix originToDestination; // Translates a vector from the origin of the portal to its destination
    @Getter private Matrix rotateToDestination; // Rotates a unit vector to the destination

    @Getter private final Matrix destinationToOrigin;
    @Getter private Matrix rotateToOrigin;

    private final World originWorld;
    private final World destinationWorld;

    @Inject
    public PortalTransformations(@Assisted IPortal portal, RenderConfig renderConfig) {
        this.originPos = portal.getOriginPos();
        this.destPos = portal.getDestPos();
        this.portalSize = portal.getSize();
        this.renderConfig = renderConfig;
        this.scale = renderConfig.getPortalScale(portal.getName());
        this.rollDegrees = renderConfig.getPortalRollDegrees();

        rotateToDestination = Matrix.makeRotation(originPos.getDirection(), destPos.getDirection());
        rotateToOrigin = Matrix.makeRotation(destPos.getDirection(), originPos.getDirection());

        // Apply the dinnerbone easter egg
        if("dinnerbone".equalsIgnoreCase(portal.getName())) {
            applyDinnerbone();
        }

        // Optional roll about the portal normal, on top of the axis-aligned rotation
        if(rollDegrees != 0.0) {
            applyRoll(rollDegrees);
        }

        // Scale is applied around the portal centre: translate to the origin,
        // scale, rotate, then translate to the destination.
        Matrix scaleMatrix = Matrix.makeScale(scale);
        Matrix inverseScaleMatrix = Matrix.makeScale(1.0 / scale);

        originToDestination = Matrix.makeTranslation(destPos.getVector())
                .multiply(rotateToDestination)
                .multiply(scaleMatrix)
                .multiply(Matrix.makeTranslation(originPos.getVector().multiply(-1.0)));

        destinationToOrigin = Matrix.makeTranslation(originPos.getVector())
                .multiply(rotateToOrigin)
                .multiply(inverseScaleMatrix)
                .multiply(Matrix.makeTranslation(destPos.getVector().multiply(-1.0)));

        originWorld = originPos.getWorld();
        destinationWorld = destPos.getWorld();
    }

    /**
     * Rolls the portal view around its normal axis. Does nothing for a 0° roll.
     */
    private void applyRoll(double rollDegrees) {
        double rollRadians = Math.toRadians(rollDegrees);
        Vector destAxis = destPos.getDirection().toVector();
        Vector originAxis = originPos.getDirection().toVector();

        rotateToDestination = rotateToDestination.multiply(Matrix.makeRotation(destAxis, rollRadians));
        rotateToOrigin = rotateToOrigin.multiply(Matrix.makeRotation(originAxis, -rollRadians));
    }

    /**
     * Flips the portal view 180 degrees. Does nothing for horizontal portals
     */
    private void applyDinnerbone() {
        PortalDirection originDirection = originPos.getDirection();
        PortalDirection destinationDirection = destPos.getDirection();

        if(originDirection.isHorizontal() || destinationDirection.isHorizontal()) {return;}
        Vector axis = destinationDirection.toVector();

        rotateToDestination = rotateToDestination.multiply(Matrix.makeRotation(axis, Math.PI));
        rotateToOrigin = rotateToOrigin.multiply(Matrix.makeRotation(axis, -Math.PI));
    }

    /**
     * Moves the location from the origin coordinate space to the destination coordinate space.
     * @param loc The location to be moved
     * @return The location in the destination coordinate space
     */
    public Location moveToDestination(Location loc) {
        Location result = originToDestination.transform(loc.toVector()).toLocation(destinationWorld);
        result.setDirection(rotateToDestination.transform(loc.getDirection()));

        return result;
    }

    /**
     * Transforms <code>vec</code> from the destination coordinate space to the origin coordinate space.
     * @param vec The vector to move
     * @return The vector in the origin coordinate space
     */
    public IntVector moveToOrigin(IntVector vec) {
        return destinationToOrigin.transform(vec);
    }

    /**
     * Transforms <code>vec</code> from the origin coordinate space to the destination coordinate space.
     * @param vec The vector to move
     * @return The vector in the destination coordinate space
     */
    public IntVector moveToDestination(IntVector vec) {
        return originToDestination.transform(vec);
    }

    /**
     * Moves the location from the destination coordinate space to the origin coordinate space.
     * @param loc The location to be moved
     * @return A new location in the origin coordinate space
     */
    public Location moveToOrigin(Location loc) {
        Location result = destinationToOrigin.transform(loc.toVector()).toLocation(originWorld);
        result.setDirection(rotateToOrigin.transform(loc.getDirection()));

        return result;
    }

    /**
     * Rotates the vector from the origin coordinate space to the destination coordinate space - no translation is performed.
     * @param vec The vector to be rotated
     * @return A new vector in the destination coordinate space
     */
    public Vector rotateToDestination(Vector vec) {
        return rotateToDestination.transform(vec);
    }

    /**
     * Rotates <i>and scales</i> a vector (an offset or a velocity) from the
     * origin coordinate space to the destination coordinate space.
     * Use this for anything with a magnitude; directions must use
     * {@link #rotateToDestination(Vector)} instead so they stay unit vectors.
     * @param vec The vector to transform
     * @return The rotated, scaled vector in the destination coordinate space
     */
    public Vector rotateAndScaleToDestination(Vector vec) {
        return rotateToDestination.transform(vec).multiply(scale);
    }

    /**
     * Rotates <i>and scales</i> a vector from the destination coordinate
     * space to the origin coordinate space (inverse scale).
     * @param vec The vector to transform
     * @return The rotated, inversely-scaled vector in the origin coordinate space
     */
    public Vector rotateAndScaleToOrigin(Vector vec) {
        return rotateToOrigin.transform(vec).multiply(1.0 / scale);
    }

    /**
     * The rotation-plus-scale matrix used by the fake-block projection
     * ({@link com.lauriethefish.betterportals.bukkit.block.FloodFillBlockMap}):
     * destination-relative block offsets are rotated <i>and</i> scaled, then
     * rounded to the integer block grid — the approximation at the heart of
     * scaled portals.
     * @return Rotation * scale, origin → destination
     */
    public Matrix getRotateAndScaleToDestination() {
        return rotateToDestination.multiply(Matrix.makeScale(scale));
    }

    /**
     * Rotates the vector from the destination coordinate space to the origin coordinate space - no translation is performed.
     * @param vec The vector to be rotated
     * @return A new vector in the origin coordinate space
     */
    public Vector rotateToOrigin(Vector vec) {
        return rotateToOrigin.transform(vec);
    }

    /**
     * Finds the correct {@link PlaneIntersectionChecker} for checking if blocks/entities are visible through the parent portal.
     * @param rayOrigin The origin of rays to check, usually the player's eye position
     * @return The intersection checker
     */
    public PlaneIntersectionChecker createIntersectionChecker(Vector rayOrigin) {
        Vector planeSize = portalSize.clone().multiply(0.5 * scale); // The size for the intersection checker is a radius, so we half this since it's the size of the full portal window; scaled portals have a scaled window
        planeSize = originPos.getDirection().swapVector(planeSize); // Must be on the Z and Y, or Z and X if horizontal (portal's sizes always use the XZ, but we don't want this here)

        Vector collisionBoxOffset = originPos.getDirection().swapVector(renderConfig.getCollisionBox());
        planeSize.add(collisionBoxOffset); // Expand the size slightly by the values in the config

        return new PlaneIntersectionChecker(
                originPos.getVector(),
                originPos.getDirection().toVector(),
                rayOrigin, // The origin pos of a portal is always exactly in the center of the plane
                planeSize // The max deviation acts as a radius, so we half this
        );
    }
}