package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.util.FifoMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.ConcurrentHashMap;

/**
 * OTG's CachedBiomeGenerator uses a non-thread-safe FifoMap. Keep one shared,
 * concurrent read-through cache per biome generator; no process-wide monitor and
 * no per-thread divergence in biome identity.
 */
@Mixin(targets = "com.pg85.otg.generator.biome.CachedBiomeGenerator", remap = false)
public abstract class CachedBiomeGeneratorMixin {

    private static final ConcurrentHashMap<FifoMap, ConcurrentHashMap<Object, Object>> WORLD_FLOW_CACHES =
            new ConcurrentHashMap<>();
    private static final int WORLD_FLOW_MAX = 512;

    private static ConcurrentHashMap<Object, Object> worldflow$cache(FifoMap map) {
        return WORLD_FLOW_CACHES.computeIfAbsent(map, ignored -> new ConcurrentHashMap<>());
    }

    /** OTG's global cache is a FifoMap(4096) of ~1 KB biome chunks. */
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 4096), remap = false)
    private int worldflow$capBiomeCache(int original) {
        return WORLD_FLOW_MAX;
    }

    @Redirect(method = "getBiomeCacheChunk",
            at = @At(value = "INVOKE", target = "Lcom/pg85/otg/util/FifoMap;get(Ljava/lang/Object;)Ljava/lang/Object;"),
            remap = false)
    private Object worldflow$cachedBiomeGet(FifoMap map, Object key) {
        return worldflow$cache(map).get(key);
    }

    @Redirect(method = "getBiomeCacheChunk",
            at = @At(value = "INVOKE", target = "Lcom/pg85/otg/util/FifoMap;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            remap = false)
    private Object worldflow$cachedBiomePut(FifoMap map, Object key, Object value) {
        ConcurrentHashMap<Object, Object> cache = worldflow$cache(map);
        if (cache.size() >= WORLD_FLOW_MAX) {
            cache.clear();
        }
        cache.put(key, value);
        return null;
    }
}
