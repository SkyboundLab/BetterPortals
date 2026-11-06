package com.lauriethefish.betterportals.bukkit.portal.spawning;

import com.lauriethefish.betterportals.bukkit.chunk.chunkpos.ChunkPosition;
import com.lauriethefish.betterportals.bukkit.chunk.chunkpos.SpiralChunkAreaIterator;
import com.lauriethefish.betterportals.bukkit.config.PortalSpawnConfig;
import com.lauriethefish.betterportals.bukkit.util.FoliaScheduler;
import com.lauriethefish.betterportals.bukkit.util.performance.OperationTimer;
import com.lauriethefish.betterportals.shared.logging.Logger;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Handles checking for existing/new spawn position across multiple ticks.
 * This is done to avoid freezing the server, since checking for a spawn position can take a couple of seconds
 * This class also contains various optimisations that improve the speed by allowing us to skip certain chunks
 */
public class AsyncPortalChecker implements Runnable {
    /**
     * Used to skip this chunk if there's no way that any block in the chunk can be any closer.
     * We approximate this by finding the distance to the chunk center, then subtracting this value.
     * Theoretically, this could skip a portal and not actually find the closest portal.
     * However, I haven't seen any get missed.
     * Ideally, the chunk skip distance would be nearly 150 - half of the the diagonal length of a chunk. However, this slows down portal spawning quite a lot
     */
    private static final double CHUNK_SKIP_DISTANCE = 45.0;

    /**
     * Radius for existing portal checking/portal spawning.
     */
    private static final double PORTAL_SEARCH_RADIUS = 128.0;

    private final Logger logger;
    private final PortalSpawningContext context;
    private final PortalSpawnConfig config;

    private Iterator<ChunkPosition> iterator;
    private final IChunkChecker chunkChecker;
    private final Consumer<PortalSpawnPosition> onFinish;
    private final FoliaScheduler.TaskHandle repeatingTask;

    private PortalSpawnPosition currentClosest;
    private double closestDistance = Double.POSITIVE_INFINITY;

    /**
     * Chunks skipped on the first pass because they were not loaded yet.
     * The search area is force-loaded (by coordinates, asynchronously) when
     * the search starts, so these will have arrived by the time the first
     * pass finishes; they get a second pass below.
     */
    private List<ChunkPosition> skippedChunks = new ArrayList<>();
    /** Whether the second pass over {@link #skippedChunks} is running. */
    private boolean secondPass = false;
    /**
     * Whether {@link #onFinish()} has run. The repeating task is cancelled
     * there, but if the scheduler fires once more anyway the callback must
     * never run twice: a second run would start a second spawn search and
     * build a second destination portal (2026-11-06 live test: ~30 portals
     * around one lit portal).
     */
    private boolean finished = false;

    private int updateCount;

    /**
     * Starts checking for portals in a spiral area
     * @param context Position/size to check for and {@link com.lauriethefish.betterportals.bukkit.config.WorldLink} to use.
     * @param chunkChecker Used to find the closest {@link PortalSpawnPosition} in each chunk.
     * @param onFinish Called with the spawn position (may be null), when this check is complete
     */
    public AsyncPortalChecker(PortalSpawningContext context, IChunkChecker chunkChecker,
                              Consumer<PortalSpawnPosition> onFinish, JavaPlugin pl, Logger logger, PortalSpawnConfig config) {
        this.logger = logger;
        this.context = context;
        this.config = config;
        this.chunkChecker = chunkChecker;
        this.onFinish = onFinish;

        // Make an iterator around the chunks of the portal search radius from our destination position
        Location spawnPos = context.getPreferredLocation();
        this.iterator = new SpiralChunkAreaIterator(
            spawnPos.clone().subtract(PORTAL_SEARCH_RADIUS, 0.0, PORTAL_SEARCH_RADIUS),
            spawnPos.clone().add(PORTAL_SEARCH_RADIUS, 0.0, PORTAL_SEARCH_RADIUS)
        );

        // Global repeating task: the search roams a 128-block spiral across
        // many regions, so no single region owns it. On Folia/Paper this is
        // the global region scheduler; on Spigot the Bukkit scheduler.
        this.repeatingTask = FoliaScheduler.runGlobalAtFixedRate(pl, this, 1, 1);
    }

    @Override
    public void run() {
        if (finished) { return; } // The task fired once more after finishing; ignore it.
        updateCount += 1;

        OperationTimer timer = new OperationTimer();

        // Make sure to stop when we're over the maximum allowed time
        // NOTE: This might go a little over (by 1ms or so), depending on how long the IChunkChecker takes to do its think
        while(timer.getTimeTakenMillis() < config.getAllowedSpawnTimePerTick()) {
            if(!iterator.hasNext()) {
                if(!secondPass && !skippedChunks.isEmpty()) {
                    // Give the skipped chunks a second pass: the search area
                    // was force-loaded when the search started, so by now
                    // (the first pass takes many ticks) they will have arrived.
                    iterator = skippedChunks.iterator();
                    skippedChunks = new ArrayList<>();
                    secondPass = true;
                    continue;
                }
                onFinish();
                return;
            }

            checkChunk(iterator.next());
        }
    }

    private void onFinish() {
        if (finished) { return; } // Never run the callback twice (see field comment).
        finished = true;
        logger.fine("Finished delayed portal check within %d ticks", updateCount);
        repeatingTask.cancel();
        onFinish.accept(currentClosest);
    }

    // Checks this chunk to see if there are any valid positions in it closer than our current closest
    private void checkChunk(ChunkPosition chunk) {
        // Never load chunks from the spawn search: getBlockAt/getChunk load
        // (and possibly generate) the chunk synchronously on the main thread,
        // which stalled the server for seconds every time a portal was lit
        // (2026-11-06 live test). Unloaded chunks are skipped and revisited
        // on the second pass, once the force-loaded area has arrived.
        if(!chunk.isLoaded()) {
            if(!secondPass) {
                skippedChunks.add(chunk);
            }
            return;
        }

        // Perform the rough check specified in the comment for CHUNK_SKIP_DISTANCE
        double closestTheoreticalDistanceInChunk = chunk.getCenterPos().distance(context.getPreferredLocation()) - CHUNK_SKIP_DISTANCE;
        if(closestTheoreticalDistanceInChunk > closestDistance) {
            return;
        }

        PortalSpawnPosition result = chunkChecker.findClosestInChunk(chunk, context);
        if(result == null) {return;} // Some chunks might not have any valid positions

        double distance = result.getPosition().distance(context.getPreferredLocation());
        if(distance < closestDistance) {
            closestDistance = distance;
            currentClosest = result;
        }
    }
}
