package com.lauriethefish.betterportals.bukkit.entity;

import com.lauriethefish.betterportals.bukkit.portal.IPortal;
import org.bukkit.entity.Entity;

import java.util.Collection;

/**
 * Finds the entities within the view distance of the portal, as specified in the config.
 */
public interface IPortalEntityManager {
    Collection<Entity> getOriginEntities();
    Collection<Entity> getDestinationEntities();

    /**
     * Updates the current entities around the portal
     * @param ticksSinceActivated Ticks since the parent {@link com.lauriethefish.betterportals.bukkit.portal.Portal} was activated, since this can be configured to not happen every tick.
     */
    void update(int ticksSinceActivated);

    /**
     * Teleports non-player entities through this portal when no player is
     * around to activate it. Vanilla teleports any entity standing in
     * portal blocks in a loaded chunk, so portal farms (gold farms,
     * item/minecart transport, ...) must not depend on a player being
     * within activation distance. Players are never teleported by this:
     * a player in a portal always activates it, so the normal
     * {@link #update(int)} path handles them.
     *
     * <p>Cheap by design: it does nothing when the portal is cross-server,
     * forbids non-player teleportation, has the sweep disabled in config,
     * or its origin chunk is not loaded, and it never touches the
     * destination-entity list used for rendering through portals.
     */
    void updateNonPlayerTeleportation();

    interface Factory {
        IPortalEntityManager create(IPortal portal, boolean requireDestination);
    }
}
