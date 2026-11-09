package com.lauriethefish.betterportals.bukkit.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.lauriethefish.betterportals.bukkit.block.external.IExternalBlockWatcherManager;
import com.lauriethefish.betterportals.bukkit.config.MiscConfig;
import com.lauriethefish.betterportals.bukkit.entity.IPortalEntityManager;
import com.lauriethefish.betterportals.bukkit.entity.faking.EntityTrackingManager;
import com.lauriethefish.betterportals.bukkit.net.ClientRequestHandler;
import com.lauriethefish.betterportals.bukkit.player.IPlayerData;
import com.lauriethefish.betterportals.bukkit.player.PlayerDataManager;
import com.lauriethefish.betterportals.bukkit.portal.IPortal;
import com.lauriethefish.betterportals.bukkit.portal.IPortalActivityManager;
import com.lauriethefish.betterportals.bukkit.portal.IPortalManager;
import com.lauriethefish.betterportals.bukkit.util.FoliaScheduler;
import com.lauriethefish.betterportals.shared.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Invokes the players to update their portal views every tick.
 * The entry point for most plugin processing each tick.
 */
@Singleton
public class MainUpdate implements Runnable {
    private static final String ISSUES_URL = "https://github.com/Lauriethefish/BetterPortals/issues";

    private final JavaPlugin pl;
    private final PlayerDataManager playerDataManager;
    private final IPortalActivityManager activityManager;
    private final EntityTrackingManager entityTrackingManager;
    private final ClientRequestHandler requestHandler;
    private final IExternalBlockWatcherManager blockWatcherManager;
    private final IPortalManager portalManager;
    private final MiscConfig miscConfig;
    private final Logger logger;

    private long tickCount = 0;

    @Inject
    public MainUpdate(JavaPlugin pl,
                      PlayerDataManager playerDataManager,
                      IPortalActivityManager activityManager,
                      EntityTrackingManager entityTrackingManager,
                      ClientRequestHandler requestHandler,
                      IExternalBlockWatcherManager blockWatcherManager, IPortalManager portalManager, MiscConfig miscConfig, Logger logger) {
        this.pl = pl;
        this.playerDataManager = playerDataManager;
        this.activityManager = activityManager;
        this.entityTrackingManager = entityTrackingManager;
        this.requestHandler = requestHandler;
        this.blockWatcherManager = blockWatcherManager;
        this.portalManager = portalManager;
        this.miscConfig = miscConfig;
        this.logger = logger;
    }

    private FoliaScheduler.TaskHandle tickTask;

    public void start() {
        // Global region scheduler on Folia/Paper (the global region thread
        // is the closest equivalent to Paper's single main thread), plain
        // Bukkit scheduler on Spigot — see FoliaScheduler.
        tickTask = FoliaScheduler.runGlobalAtFixedRate(pl, this, 0L, 1L);
    }

    public void stop() {
        FoliaScheduler.cancel(tickTask);
        tickTask = null;
    }

    @Override
    public void run() {
        try {
            tickCount++;

            playerDataManager.getPlayers().forEach(IPlayerData::onUpdate);

            // Update replicated entities
            entityTrackingManager.update();

            // Mobs/items/minecarts through portals with no player nearby:
            // vanilla teleports any entity in portal blocks in a loaded
            // chunk, so this runs on the same cadence as the per-portal
            // entity check, for every registered portal. Active portals
            // already teleported their mobs above; the sweep is idempotent
            // for them (teleported entities are removed from the origin
            // list), and it never touches players or portal rendering.
            if(tickCount % Math.max(1, miscConfig.getEntityCheckInterval()) == 0) {
                updateNonPlayerTeleportation();
            }

            // Deactivates and view-deactivates any unused portals that were active last tick
            activityManager.postUpdate();

            requestHandler.handlePendingRequests();

            blockWatcherManager.update();

        }   catch(RuntimeException ex) {
            logger.severe("A critical error occurred during main update.");
            logger.severe("Please create an issue at %s to get this fixed.", ISSUES_URL);
            ex.printStackTrace();
        }
    }

    private void updateNonPlayerTeleportation() {
        for(IPortal portal : portalManager.getAllPortals()) {
            try {
                IPortalEntityManager entityList = portal.getEntityList();
                if(entityList != null) {
                    entityList.updateNonPlayerTeleportation();
                }
            }   catch(RuntimeException ex) {
                // One broken portal must not stop the others teleporting.
                logger.warning("Failed to update non-player teleportation for a portal at %s", portal.getOriginPos());
                ex.printStackTrace();
            }
        }
    }
}
