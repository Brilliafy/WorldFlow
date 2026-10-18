package com.cleanroommc.worldflow.mixin.otg;

import com.cleanroommc.worldflow.pipeline.isolation.PopulationHighestBlocksCache;
import com.pg85.otg.common.LocalWorld;
import com.pg85.otg.generator.resource.OreGen;
import com.pg85.otg.util.ChunkCoordinate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

@Mixin(value = OreGen.class, remap = false)
public abstract class OreGenMixin {

    @Shadow
    byte[][] highestBlocksCache;

    @Inject(method = "createCache", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$onCreateCache(CallbackInfo ci) {
        PopulationHighestBlocksCache.reset();
        this.highestBlocksCache = PopulationHighestBlocksCache.get();
        ci.cancel();
    }

    @Inject(method = "clearCache", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$onClearCache(CallbackInfo ci) {
        // Do not set this.highestBlocksCache = null as shared OreGen singletons are concurrently used by workers.
        PopulationHighestBlocksCache.reset();
        ci.cancel();
    }

    @Inject(method = "spawn", at = @At("HEAD"), remap = false)
    private void worldflow$onSpawn(LocalWorld world, Random rand, boolean villageInChunk, int x, int z, ChunkCoordinate chunkBeingPopulated, CallbackInfo ci) {
        this.highestBlocksCache = PopulationHighestBlocksCache.get();
    }

    @Redirect(method = "spawn",
            at = @At(value = "FIELD",
                    target = "Lcom/pg85/otg/generator/resource/OreGen;highestBlocksCache:[[B",
                    opcode = 180), // GETFIELD
            remap = false)
    private byte[][] worldflow$redirectHighestBlocksCache(OreGen self) {
        return PopulationHighestBlocksCache.get();
    }
}
