package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.configuration.biome.BiomeConfig;
import com.pg85.otg.common.LocalBiome;
import com.pg85.otg.generator.ObjectSpawner;
import com.pg85.otg.util.ChunkCoordinate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/** Phase timings for OTG population, surfaced in WorldFlow metrics. */
@Mixin(value = ObjectSpawner.class, remap = false)
public abstract class ObjectSpawnerMixin {

    private static void start(int i) { com.cleanroommc.worldflow.pipeline.otg.OTGPhaseProfiler.start(i); }

    private static void end(int i) { com.cleanroommc.worldflow.pipeline.otg.OTGPhaseProfiler.end(i); }

    @Inject(method = "processResourcesBeforeBo4s", at = @At("HEAD"), remap = false)
    private void wf$beforeHead(ChunkCoordinate c, LocalBiome b, BiomeConfig cfg, CallbackInfo ci) { start(0); }

    @Inject(method = "processResourcesBeforeBo4s", at = @At("RETURN"), remap = false)
    private void wf$beforeRet(ChunkCoordinate c, LocalBiome b, BiomeConfig cfg, CallbackInfo ci) { end(0); }

    @Inject(method = "spawnBO4s", at = @At("HEAD"), remap = false)
    private void wf$bo4Head(ChunkCoordinate c, ChunkCoordinate p, CallbackInfo ci) { start(1); }

    @Inject(method = "spawnBO4s", at = @At("RETURN"), remap = false)
    private void wf$bo4Ret(ChunkCoordinate c, ChunkCoordinate p, CallbackInfo ci) { end(1); }

    @Inject(method = "processResourcesAfterBo4s", at = @At("HEAD"), remap = false)
    private void wf$afterHead(ChunkCoordinate c, boolean v, LocalBiome b, BiomeConfig cfg, CallbackInfo ci) { start(2); }

    @Inject(method = "processResourcesAfterBo4s", at = @At("RETURN"), remap = false)
    private void wf$afterRet(ChunkCoordinate c, boolean v, LocalBiome b, BiomeConfig cfg, CallbackInfo ci) { end(2); }
}
