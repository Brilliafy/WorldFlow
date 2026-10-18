package com.cleanroommc.worldflow.pipeline.isolation;

import com.pg85.otg.common.LocalBiome;
import com.pg85.otg.util.ChunkCoordinate;

/**
 * Thread-isolated population biome cache.
 * Avoids cross-thread cache corruption on ForgeWorld.cachedBiomes during concurrent chunk population.
 */
public class PopulationBiomeCache {

    private static final ThreadLocal<PopulationBiomeCache> THREAD_LOCAL = new ThreadLocal<>();

    public static PopulationBiomeCache get() {
        PopulationBiomeCache cache = THREAD_LOCAL.get();
        if (cache == null) {
            cache = new PopulationBiomeCache();
            THREAD_LOCAL.set(cache);
        }
        return cache;
    }

    public ChunkCoordinate chunkCoord;
    public final LocalBiome[][] biomes = new LocalBiome[32][32];
    public boolean valid = false;

    public PopulationBiomeCache() {
    }

    public void invalidate() {
        this.valid = false;
        this.chunkCoord = null;
    }
}
