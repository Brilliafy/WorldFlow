package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.configuration.biome.BiomeConfig;
import com.pg85.otg.common.LocalBiome;
import com.pg85.otg.common.LocalWorld;
import com.pg85.otg.generator.ObjectSpawner;
import com.pg85.otg.generator.resource.Resource;
import com.pg85.otg.customobjects.structures.CustomStructureCache;
import com.pg85.otg.util.ChunkCoordinate;
import com.cleanroommc.worldflow.pipeline.otg.WorldFlowLockable;
import com.cleanroommc.worldflow.pipeline.otg.OTGStructureService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;


/** Phase timings for OTG population, surfaced in WorldFlow metrics. */
@Mixin(value = ObjectSpawner.class, remap = false)
public abstract class ObjectSpawnerMixin {

    /**
     * OTG shares its vanilla MapGenStructure instances between the terrain pass and
     * population. Both iterate/mutate plain fastutil maps, so population-time structure
     * placement has to take the same world-gen lock as the terrain hook.
     */
    @Redirect(
            method = "doPopulate",
            at = @At(value = "INVOKE",
                    target = "Lcom/pg85/otg/common/LocalWorld;placeDefaultStructures(Ljava/util/Random;Lcom/pg85/otg/util/ChunkCoordinate;)Z"),
            remap = false)
    private boolean worldflow$lockedPlaceDefaultStructures(LocalWorld world, Random rand, ChunkCoordinate chunkCoord) {
        com.cleanroommc.worldflow.pipeline.compat.WorldGenSafety.UNSAFE_WORLDGEN_LOCK.lock();
        try {
            return world.placeDefaultStructures(rand, chunkCoord);
        } finally {
            com.cleanroommc.worldflow.pipeline.compat.WorldGenSafety.UNSAFE_WORLDGEN_LOCK.unlock();
        }
    }

    @org.spongepowered.asm.mixin.Shadow
    private com.pg85.otg.common.LocalWorld world;
    @org.spongepowered.asm.mixin.Shadow
    private java.util.Random rand;

    @Inject(method = "doPopulate", at = @At("HEAD"), remap = false)
    private void worldflow$batchPlots(ChunkCoordinate chunkCoord, CallbackInfo ci) {
        com.cleanroommc.worldflow.pipeline.otg.OTGStructureService.clearBatch();
        if (this.world != null && this.world.isBo4Enabled()) {
            com.cleanroommc.worldflow.pipeline.otg.OTGStructureService.executePlotBatch(
                    this.world.getStructureCache(), this.rand, chunkCoord);
        }
    }

    @Inject(method = "doPopulate", at = @At("RETURN"), remap = false)
    private void worldflow$clearBatch(ChunkCoordinate chunkCoord, CallbackInfo ci) {
        com.cleanroommc.worldflow.pipeline.otg.OTGStructureService.clearBatch();
    }

    @Redirect(method = "processResourcesBeforeBo4s", at = @At(value = "INVOKE",
            target = "Lcom/pg85/otg/generator/resource/Resource;process(Lcom/pg85/otg/common/LocalWorld;Ljava/util/Random;ZLcom/pg85/otg/util/ChunkCoordinate;)V"),
            remap = false)
    private void worldflow$lockedProcessBefore(Resource resource, LocalWorld world, Random random, boolean village, ChunkCoordinate coord) {
        if (resource instanceof com.cleanroommc.worldflow.pipeline.otg.WorldFlowTreeState
                && ((com.cleanroommc.worldflow.pipeline.otg.WorldFlowTreeState) resource).worldflow$ready()) {
            resource.process(world, random, village, coord);
            return;
        }
        ReentrantLock lock = ((WorldFlowLockable) resource).worldflow$lock();
        lock.lock();
        boolean ok = false;
        try {
            resource.process(world, random, village, coord);
            ok = true;
        } finally {
            if (ok && resource instanceof com.cleanroommc.worldflow.pipeline.otg.WorldFlowTreeState) {
                ((com.cleanroommc.worldflow.pipeline.otg.WorldFlowTreeState) resource).worldflow$markReady();
            }
            lock.unlock();
        }
    }

    @Redirect(method = "processResourcesAfterBo4s", at = @At(value = "INVOKE",
            target = "Lcom/pg85/otg/generator/resource/Resource;process(Lcom/pg85/otg/common/LocalWorld;Ljava/util/Random;ZLcom/pg85/otg/util/ChunkCoordinate;)V"),
            remap = false)
    private void worldflow$lockedProcessAfter(Resource resource, LocalWorld world, Random random, boolean village, ChunkCoordinate coord) {
        if (resource instanceof com.cleanroommc.worldflow.pipeline.otg.WorldFlowTreeState
                && ((com.cleanroommc.worldflow.pipeline.otg.WorldFlowTreeState) resource).worldflow$ready()) {
            resource.process(world, random, village, coord);
            return;
        }
        ReentrantLock lock = ((WorldFlowLockable) resource).worldflow$lock();
        lock.lock();
        boolean ok = false;
        try {
            resource.process(world, random, village, coord);
            ok = true;
        } finally {
            if (ok && resource instanceof com.cleanroommc.worldflow.pipeline.otg.WorldFlowTreeState) {
                ((com.cleanroommc.worldflow.pipeline.otg.WorldFlowTreeState) resource).worldflow$markReady();
            }
            lock.unlock();
        }
    }

    @Redirect(method = "spawnBO4", at = @At(value = "INVOKE",
            target = "Lcom/pg85/otg/customobjects/structures/CustomStructureCache;spawnBo4Chunk(Lcom/pg85/otg/util/ChunkCoordinate;Lcom/pg85/otg/util/ChunkCoordinate;)V"),
            remap = false)
    private void worldflow$lockedSpawnBo4(CustomStructureCache cache, ChunkCoordinate coord, ChunkCoordinate pop) {
        com.cleanroommc.worldflow.pipeline.otg.WorldFlowStructureCacheAccess acc =
                (com.cleanroommc.worldflow.pipeline.otg.WorldFlowStructureCacheAccess) cache;
        com.cleanroommc.worldflow.mixin.otg.CustomStructurePlotterAccessor inv =
                (com.cleanroommc.worldflow.mixin.otg.CustomStructurePlotterAccessor) acc.worldflow$plotter();
        com.pg85.otg.customobjects.structures.bo4.BO4CustomStructure structure;
        java.util.concurrent.locks.ReentrantLock lock = OTGStructureService.STRUCTURE_CACHE_LOCK;
        lock.lock();
        try {
            structure = inv.worldflow$getFromCache(coord);
            inv.worldflow$removeFromCache(coord);
            inv.worldflow$setOccupied(coord);
        } finally {
            lock.unlock();
        }
        if (structure != null && structure.start != null) {
            // A BO4 structure spans many chunks and each chunk can be spawned by a
            // different worker. Its smoothing-area lists are plain ArrayLists; serialize
            // only spawns of the same structure so unrelated structures still run in parallel.
            synchronized (structure) {
                structure.spawnInChunk(coord, acc.worldflow$world(), pop);
            }
        }
    }

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
