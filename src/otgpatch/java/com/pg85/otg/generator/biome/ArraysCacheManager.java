package com.pg85.otg.generator.biome;

public class ArraysCacheManager {

    private static final ThreadLocal<ArraysCache> THREAD_LOCAL_CACHE = ThreadLocal.withInitial(ArraysCache::new);

    public static ArraysCache getCache() {
        ArraysCache cache = THREAD_LOCAL_CACHE.get();
        cache.isFree = false;
        return cache;
    }

    public static void releaseCache(ArraysCache cache) {
        if (cache != null) {
            cache.release();
        }
    }

    private ArraysCacheManager() {
    }
}
