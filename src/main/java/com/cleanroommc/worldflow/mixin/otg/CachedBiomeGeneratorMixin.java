package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.util.FifoMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;

/**
 * OTG's {@code CachedBiomeGenerator} caches biome data in a {@link FifoMap}, which is not
 * thread-safe (backed by a LinkedHashMap). A plain {@code synchronized(map)} around every get
 * serialises all workers - measured as a 3x inflation of OTG populate time with 4 workers.
 *
 * <p>Biome cache entries are immutable for a given chunk coordinate, so a per-thread
 * read-through cache is safe: reads almost always hit the thread-local map, and the global
 * map is only touched on a miss or a write.</p>
 */
@Mixin(targets = "com.pg85.otg.generator.biome.CachedBiomeGenerator", remap = false)
public abstract class CachedBiomeGeneratorMixin {

    private static final ThreadLocal<HashMap<Object, Object>> WORLD_FLOW_LOCAL_BIOME_CACHE =
            ThreadLocal.withInitial(HashMap::new);
    private static final int WORLD_FLOW_LOCAL_MAX = 8192;

    @Redirect(method = "getBiomeCacheChunk",
            at = @At(value = "INVOKE", target = "Lcom/pg85/otg/util/FifoMap;get(Ljava/lang/Object;)Ljava/lang/Object;"),
            remap = false)
    private Object worldflow$cachedBiomeGet(FifoMap map, Object key) {
        HashMap<Object, Object> local = WORLD_FLOW_LOCAL_BIOME_CACHE.get();
        Object value = local.get(key);
        if (value != null) {
            return value;
        }
        synchronized (map) {
            value = map.get(key);
        }
        if (value != null) {
            if (local.size() >= WORLD_FLOW_LOCAL_MAX) {
                local.clear();
            }
            local.put(key, value);
        }
        return value;
    }

    @Redirect(method = "getBiomeCacheChunk",
            at = @At(value = "INVOKE", target = "Lcom/pg85/otg/util/FifoMap;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            remap = false)
    private Object worldflow$cachedBiomePut(FifoMap map, Object key, Object value) {
        HashMap<Object, Object> local = WORLD_FLOW_LOCAL_BIOME_CACHE.get();
        if (local.size() >= WORLD_FLOW_LOCAL_MAX) {
            local.clear();
        }
        local.put(key, value);
        synchronized (map) {
            return map.put(key, value);
        }
    }
}
