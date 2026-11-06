package com.lauriethefish.betterportals.bukkit.block.fetch;

import com.lauriethefish.betterportals.api.IntVector;
import com.lauriethefish.betterportals.bukkit.portal.IPortal;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;

/**
 * Local portals already have their blocks accessible.
 * This is a simple pass-through to Bukkit's API.
 */
public class LocalBlockDataFetcher implements IBlockDataFetcher {
    private final World destinationWorld;

    /** Cached air data returned for unloaded chunks (see {@link #getData}). */
    private BlockData airData;

    public LocalBlockDataFetcher(IPortal portal) {
        this.destinationWorld = portal.getDestPos().getWorld();
    }

    @Override
    public void update() {
        // Do nothing, this is just a pass-through to Bukkit's API for local blocks.
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public @NotNull BlockData getData(@NotNull IntVector position) {
        // Never load chunks from the block-map update path: getBlockAt
        // loads (and possibly generates) the chunk synchronously on the
        // main thread. A portal looking into unloaded terrain — a portal
        // viewed but not activated (so no force-load ran), or viewed
        // while its force-loaded chunks are still arriving — stalled the
        // server for 10s+ that way (2026-11-06 live test, watchdog dump
        // in the flood fill). Unloaded chunks read as air, which is
        // non-occluding, so the fill still completes quickly; the
        // incremental update (checkForChanges) corrects the view once
        // the chunk arrives.
        if (!destinationWorld.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
            if (airData == null) {
                airData = Bukkit.createBlockData(Material.AIR);
            }
            return airData;
        }
        return position.getBlock(destinationWorld).getBlockData();
    }
}
