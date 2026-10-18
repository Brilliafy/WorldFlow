package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.util.FifoMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.pg85.otg.generator.biome.CachedBiomeGenerator", remap = false)
public abstract class CachedBiomeGeneratorMixin {

    @Redirect(method = "getBiomeCacheChunk", at = @At(value = "INVOKE", target = "Lcom/pg85/otg/util/FifoMap;get(Ljava/lang/Object;)Ljava/lang/Object;"), remap = false)
    private Object worldflow$syncGetBiomeCacheChunk(FifoMap map, Object key) {
        synchronized (map) {
            return map.get(key);
        }
    }

    @Redirect(method = "getBiomeCacheChunk", at = @At(value = "INVOKE", target = "Lcom/pg85/otg/util/FifoMap;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), remap = false)
    private Object worldflow$syncPutBiomeCacheChunk(FifoMap map, Object key, Object value) {
        synchronized (map) {
            return map.put(key, value);
        }
    }
}
