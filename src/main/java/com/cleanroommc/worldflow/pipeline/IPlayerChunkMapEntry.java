package com.cleanroommc.worldflow.pipeline;

import net.minecraft.world.chunk.Chunk;

/**
 * Mixin interface accessor for PlayerChunkMapEntry to allow setting the underlying Chunk
 * upon parallel generation pipeline publication.
 */
public interface IPlayerChunkMapEntry {

    void worldflow$setChunk(Chunk chunk);

}
