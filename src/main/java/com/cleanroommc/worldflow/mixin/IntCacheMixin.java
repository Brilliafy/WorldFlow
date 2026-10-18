package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.pipeline.isolation.ThreadCacheState;
import net.minecraft.world.gen.layer.IntCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(IntCache.class)
public abstract class IntCacheMixin {

    private static final ThreadLocal<ThreadCacheState> LOCAL_CACHE = ThreadLocal.withInitial(ThreadCacheState::new);

    /**
     * @author WorldFlow
     * @reason Make IntCache thread-local for lockless, contention-free parallel biome generation.
     */
    @Overwrite
    public static int[] getIntCache(int size) {
        return LOCAL_CACHE.get().get(size);
    }

    /**
     * @author WorldFlow
     * @reason Make IntCache thread-local so resetting on one worker thread does not corrupt other threads.
     */
    @Overwrite
    public static void resetIntCache() {
        LOCAL_CACHE.get().reset();
    }

    /**
     * @author WorldFlow
     * @reason Query per-thread cache sizes.
     */
    @Overwrite
    public static String getCacheSizes() {
        return LOCAL_CACHE.get().sizes();
    }
}
