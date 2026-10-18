package com.cleanroommc.worldflow.pipeline.isolation;

import java.util.Arrays;

/**
 * Thread-local cache for OpenTerrainGenerator's OreGen highest-blocks 2D byte array.
 * Replaces the unsafe instance field OreGen.highestBlocksCache with thread isolation
 * to eliminate NullPointerExceptions caused by concurrent population across worker threads.
 */
public class PopulationHighestBlocksCache {

    private static final ThreadLocal<byte[][]> THREAD_LOCAL = ThreadLocal.withInitial(PopulationHighestBlocksCache::createCache);

    private static byte[][] createCache() {
        return new byte[32][32];
    }

    public static byte[][] get() {
        byte[][] cache = THREAD_LOCAL.get();
        if (cache == null) {
            cache = createCache();
            THREAD_LOCAL.set(cache);
        }
        return cache;
    }

    public static void reset() {
        byte[][] cache = get();
        for (int i = 0; i < 32; i++) {
            Arrays.fill(cache[i], (byte) 0);
        }
    }
}
