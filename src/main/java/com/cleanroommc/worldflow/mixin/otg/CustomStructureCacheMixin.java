package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.customobjects.structures.CustomStructureCache;
import com.pg85.otg.util.ChunkCoordinate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CustomStructureCache.class, remap = false)
public abstract class CustomStructureCacheMixin {

    @org.spongepowered.asm.mixin.Shadow
    private java.util.Map<ChunkCoordinate, com.pg85.otg.customobjects.structures.StructureDataRegion> worldInfoChunks;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void worldflow$initConcurrent(com.pg85.otg.common.LocalWorld world, CallbackInfo ci) {
        if (!(this.worldInfoChunks instanceof java.util.concurrent.ConcurrentHashMap)) {
            this.worldInfoChunks = new java.util.concurrent.ConcurrentHashMap<>(this.worldInfoChunks != null ? this.worldInfoChunks : java.util.Collections.emptyMap());
        }
    }

    @Inject(method = "addToWorldInfoChunks(Lcom/pg85/otg/customobjects/structures/CustomStructure;Lcom/pg85/otg/util/ChunkCoordinate;Z)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void worldflow$concurrentAddToWorldInfoChunks(com.pg85.otg.customobjects.structures.CustomStructure structure, ChunkCoordinate chunkCoordinate, boolean requiresSave, CallbackInfo ci) {
        ci.cancel();
        ChunkCoordinate regionCoord = chunkCoordinate.toRegionCoord();
        com.pg85.otg.customobjects.structures.StructureDataRegion chunkRegion = this.worldInfoChunks.computeIfAbsent(regionCoord, k -> new com.pg85.otg.customobjects.structures.StructureDataRegion());
        chunkRegion.setStructure(chunkCoordinate.getRegionInternalX(), chunkCoordinate.getRegionInternalZ(), structure, requiresSave);
    }
}
