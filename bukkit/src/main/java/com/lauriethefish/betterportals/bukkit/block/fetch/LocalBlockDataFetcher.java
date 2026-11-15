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
        if (!destinationWorld.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
            if (airData == null) {
                airData = Bukkit.createBlockData(Material.AIR);
            }
            return airData;
        }
        return position.getBlock(destinationWorld).getBlockData();
    }
}
