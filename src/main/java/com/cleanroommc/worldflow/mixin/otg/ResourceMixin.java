package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.common.LocalWorld;
import com.pg85.otg.generator.resource.Resource;
import com.pg85.otg.util.ChunkCoordinate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

/** Per-resource-class timing so the hot generator can be identified. */
@Mixin(value = Resource.class, remap = false)
public abstract class ResourceMixin {

    @Unique
    private static final ThreadLocal<long[]> WORLD_FLOW_RES_START = ThreadLocal.withInitial(() -> new long[]{0L});

    @Inject(method = "process", at = @At("HEAD"), remap = false)
    private void wf$resHead(LocalWorld world, Random rand, boolean village, ChunkCoordinate coord, CallbackInfo ci) {
        WORLD_FLOW_RES_START.get()[0] = System.nanoTime();
    }

    @Inject(method = "process", at = @At("RETURN"), remap = false)
    private void wf$resRet(LocalWorld world, Random rand, boolean village, ChunkCoordinate coord, CallbackInfo ci) {
        long start = WORLD_FLOW_RES_START.get()[0];
        if (start != 0L) {
            com.cleanroommc.worldflow.pipeline.otg.OTGPhaseProfiler.recordResource(
                    this.getClass().getSimpleName(), System.nanoTime() - start);
            WORLD_FLOW_RES_START.get()[0] = 0L;
        }
    }
}
