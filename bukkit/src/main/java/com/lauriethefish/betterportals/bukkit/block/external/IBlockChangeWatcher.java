package com.lauriethefish.betterportals.bukkit.block.external;

import com.lauriethefish.betterportals.api.IntVector;
import com.lauriethefish.betterportals.bukkit.net.requests.GetBlockDataChangesRequest;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Used to check for changes of blocks at the destination of an external portal, then relay the changes back to the origin.
 * This allows a block data array to be constructed at the origin of the portal, so that cross-server portals can work.
 */
public interface IBlockChangeWatcher {
    /**
     * Checks for any changes in the configured area.
     * This will return all blocks in the area the first time it is called on one instance.
     * @return The new block data, as a string (See {@link org.bukkit.block.data.BlockData#getAsString()})
     */
    @NotNull Map<IntVector, String> checkForChanges();

    interface Factory {
        IBlockChangeWatcher create(GetBlockDataChangesRequest request);
    }
}
