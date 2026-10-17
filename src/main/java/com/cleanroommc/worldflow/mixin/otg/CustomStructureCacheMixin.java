package com.cleanroommc.worldflow.mixin.otg;

import com.cleanroommc.worldflow.pipeline.otg.OTGParallelContext;
import com.pg85.otg.customobjects.structures.CustomStructureCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ensures thread-safe synchronization between main-thread world-save (CustomStructureCache.saveToDisk)
 * and the off-thread gen-lever structure population thread.
 */
@Mixin(value = CustomStructureCache.class, remap = false)
public abstract class CustomStructureCacheMixin {

    @Inject(method = "saveToDisk", at = @At("HEAD"), remap = false)
    private void worldflow$onSaveToDiskStart(CallbackInfo ci) {
        OTGParallelContext.OTG_STRUCTURE_LOCK.lock();
    }

    @Inject(method = "saveToDisk", at = @At("RETURN"), remap = false)
    private void worldflow$onSaveToDiskEnd(CallbackInfo ci) {
        if (OTGParallelContext.OTG_STRUCTURE_LOCK.isHeldByCurrentThread()) {
            OTGParallelContext.OTG_STRUCTURE_LOCK.unlock();
        }
    }
}
