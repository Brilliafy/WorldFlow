package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.util.FifoMap;
import com.pg85.otg.util.materials.MaterialHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;

/**
 * Same treatment as the biome cache: OTG's material cache is a non-thread-safe {@link FifoMap}.
 * Material lookups are pure (string -> immutable material), so each worker keeps a read-through
 * cache and only synchronises on the global map for misses and writes.
 */
@Mixin(value = MaterialHelper.class, remap = false)
public abstract class MaterialHelperMixin {

    private static final ThreadLocal<HashMap<Object, Object>> WORLD_FLOW_LOCAL_MATERIAL_CACHE =
            ThreadLocal.withInitial(HashMap::new);
    private static final int WORLD_FLOW_LOCAL_MAX = 8192;

    @Redirect(method = "readMaterial",
            at = @At(value = "INVOKE", target = "Lcom/pg85/otg/util/FifoMap;get(Ljava/lang/Object;)Ljava/lang/Object;"),
            remap = false)
    private static Object worldflow$materialGet(FifoMap map, Object key) {
        HashMap<Object, Object> local = WORLD_FLOW_LOCAL_MATERIAL_CACHE.get();
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

    @Redirect(method = "readMaterial",
            at = @At(value = "INVOKE", target = "Lcom/pg85/otg/util/FifoMap;containsKey(Ljava/lang/Object;)Z"),
            remap = false)
    private static boolean worldflow$materialContainsKey(FifoMap map, Object key) {
        if (WORLD_FLOW_LOCAL_MATERIAL_CACHE.get().containsKey(key)) {
            return true;
        }
        synchronized (map) {
            return map.containsKey(key);
        }
    }

    @Redirect(method = "readMaterial",
            at = @At(value = "INVOKE", target = "Lcom/pg85/otg/util/FifoMap;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            remap = false)
    private static Object worldflow$materialPut(FifoMap map, Object key, Object value) {
        HashMap<Object, Object> local = WORLD_FLOW_LOCAL_MATERIAL_CACHE.get();
        if (local.size() >= WORLD_FLOW_LOCAL_MAX) {
            local.clear();
        }
        local.put(key, value);
        synchronized (map) {
            return map.put(key, value);
        }
    }
}
