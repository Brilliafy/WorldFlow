package com.cleanroommc.worldflow.mixin;

import com.google.common.collect.Lists;
import net.minecraft.world.gen.layer.IntCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.List;

@Mixin(IntCache.class)
public abstract class IntCacheMixin {

    private static final class ThreadCacheState {
        private int intCacheSize = 256;
        private final List<int[]> freeSmallArrays = Lists.newArrayList();
        private final List<int[]> inUseSmallArrays = Lists.newArrayList();
        private final List<int[]> freeLargeArrays = Lists.newArrayList();
        private final List<int[]> inUseLargeArrays = Lists.newArrayList();

        int[] get(int size) {
            if (size <= 256) {
                if (this.freeSmallArrays.isEmpty()) {
                    int[] arr = new int[256];
                    this.inUseSmallArrays.add(arr);
                    return arr;
                } else {
                    int[] arr = this.freeSmallArrays.remove(this.freeSmallArrays.size() - 1);
                    this.inUseSmallArrays.add(arr);
                    return arr;
                }
            } else if (size > this.intCacheSize) {
                this.intCacheSize = size;
                this.freeLargeArrays.clear();
                this.inUseLargeArrays.clear();
                int[] arr = new int[this.intCacheSize];
                this.inUseLargeArrays.add(arr);
                return arr;
            } else if (this.freeLargeArrays.isEmpty()) {
                int[] arr = new int[this.intCacheSize];
                this.inUseLargeArrays.add(arr);
                return arr;
            } else {
                int[] arr = this.freeLargeArrays.remove(this.freeLargeArrays.size() - 1);
                this.inUseLargeArrays.add(arr);
                return arr;
            }
        }

        void reset() {
            if (!this.freeLargeArrays.isEmpty()) {
                this.freeLargeArrays.remove(this.freeLargeArrays.size() - 1);
            }
            if (!this.freeSmallArrays.isEmpty()) {
                this.freeSmallArrays.remove(this.freeSmallArrays.size() - 1);
            }
            this.freeLargeArrays.addAll(this.inUseLargeArrays);
            this.freeSmallArrays.addAll(this.inUseSmallArrays);
            this.inUseLargeArrays.clear();
            this.inUseSmallArrays.clear();
        }

        String sizes() {
            return "cache: " + this.freeLargeArrays.size() + ", tcache: " + this.freeSmallArrays.size() +
                    ", allocated: " + this.inUseLargeArrays.size() + ", tallocated: " + this.inUseSmallArrays.size();
        }
    }

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
