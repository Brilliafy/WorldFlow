package com.cleanroommc.worldflow.mixin.otg;

import com.cleanroommc.worldflow.pipeline.otg.OTGStructureService;
import com.pg85.otg.customobjects.structures.CustomStructureCache;
import com.pg85.otg.util.ChunkCoordinate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

@Mixin(value = CustomStructureCache.class, remap = false)
public abstract class CustomStructureCacheMixin {

    @Inject(method = "plotBo4Structures(Ljava/util/Random;Lcom/pg85/otg/util/ChunkCoordinate;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void worldflow$onPlotBo4Structures(Random rand, ChunkCoordinate chunkCoord, CallbackInfo ci) {
        if (!OTGStructureService.isStructureThread()) {
            ci.cancel();
            OTGStructureService.executePlot((CustomStructureCache) (Object) this, rand, chunkCoord);
        }
    }

    @Inject(method = "plotBo4Structure(Lcom/pg85/otg/customobjects/bo4/BO4;Ljava/util/ArrayList;Lcom/pg85/otg/util/ChunkCoordinate;)Lcom/pg85/otg/util/ChunkCoordinate;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void worldflow$onPlotBo4Structure(com.pg85.otg.customobjects.bo4.BO4 structure, java.util.ArrayList<String> biomes, ChunkCoordinate chunkCoord, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<ChunkCoordinate> cir) {
        if (!OTGStructureService.isStructureThread()) {
            cir.setReturnValue(OTGStructureService.executePlotSingle((CustomStructureCache) (Object) this, structure, biomes, chunkCoord));
        }
    }
}
