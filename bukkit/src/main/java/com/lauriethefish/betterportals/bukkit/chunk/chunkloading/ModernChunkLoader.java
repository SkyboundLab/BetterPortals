package com.lauriethefish.betterportals.bukkit.chunk.chunkloading;

import com.lauriethefish.betterportals.bukkit.chunk.chunkpos.ChunkPosition;
import org.bukkit.Chunk;
import org.jetbrains.annotations.NotNull;

import jakarta.inject.Singleton;
import java.util.HashSet;
import java.util.Set;

@Singleton
public class ModernChunkLoader implements IChunkLoader {
    // Used to only unforceload chunks if they are *loaded by the plugin*
    private final Set<ChunkPosition> loadedChunks = new HashSet<>();

    @Override
    public void setForceLoaded(Chunk chunk) {
        chunk.setForceLoaded(true);
        loadedChunks.add(new ChunkPosition(chunk));
    }

    /**
     * Force-loads a chunk by coordinates, without fetching the
     * {@link Chunk} first. {@code ChunkPosition.getChunk()} loads the
     * chunk synchronously on the calling thread; force-loading a portal's
     * destination area that way stalled the server for 10s+ when the
     * chunks were cold (2026-11-06 live test: watchdog dump in the
     * portal view update, after activation had synchronously loaded the
     * destination chunks). {@code World.setChunkForceLoaded} only
     * tickets the chunk — the server loads it asynchronously and keeps
     * it loaded — so activation no longer blocks the main thread.
     */
    @Override
    public void setForceLoaded(@NotNull ChunkPosition chunk) {
        chunk.getWorld().setChunkForceLoaded(chunk.x, chunk.z, true);
        loadedChunks.add(chunk);
    }

    @Override
    public void setNotForceLoaded(@NotNull ChunkPosition chunk) {
        if(loadedChunks.remove(chunk)) {
            // Do it this way to avoid loading the chunk by calling getChunk
            chunk.getWorld().setChunkForceLoaded(chunk.x, chunk.z, false);
        }
    }

    @Override
    public boolean isForceLoaded(@NotNull ChunkPosition chunk) {
        return loadedChunks.contains(chunk);
    }   
}
