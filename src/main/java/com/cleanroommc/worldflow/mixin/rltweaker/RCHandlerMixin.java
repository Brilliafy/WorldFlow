package com.cleanroommc.worldflow.mixin.rltweaker;

import com.charles445.rltweaker.handler.RecurrentHandler;
import com.charles445.rltweaker.reflect.RecurrentReflect;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Random;

/**
 * RLTweaker wraps RecurrentComplex's native populator and synchronizes the entire
 * structure-planning + generation pass on WorldStructureGenerationData. Recurrent
 * Complex's data collections are made concurrent by RCSavedDataMixin, so that global
 * lock only serialises all WorldFlow feature workers. Keep the wrapper and its event
 * handling, but drop the lock so each worker plans its own chunk.
 */
@Mixin(targets = "com.charles445.rltweaker.handler.RecurrentHandler$RCHandler", remap = false)
public abstract class RCHandlerMixin {

    @Shadow @Final private RecurrentHandler this$0;

    @Shadow
    private void planStructuresInChunk(Random random, ChunkPos chunkPos, WorldServer world, Biome biome)
            throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        throw new AssertionError();
    }

    @Overwrite
    private void decorate(WorldServer world, Random random, ChunkPos chunkPos)
            throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        RecurrentReflect reflector = ((RecurrentHandlerAccessor) (Object) this.this$0).worldflow$getReflector();
        boolean mapFeatures = world.getWorldInfo().isMapFeaturesEnabled();
        Object wsgData = reflector.getWorldStructureGenerationData(world);
        List structuresComplementList = reflector.getStructuresComplementList(wsgData, chunkPos);
        reflector.checkChunk(wsgData, chunkPos);
        reflector.complementStructuresInChunk(chunkPos, world, structuresComplementList);
        if (mapFeatures || !reflector.honorStructureGenerationOption()) {
            Biome biome = world.getBiome(chunkPos.getBlock(8, 0, 8));
            BlockPos spawnPos = world.getSpawnPoint();
            reflector.planStaticStructuresInChunk(random, chunkPos, world, spawnPos, null);
            boolean generate = reflector.isGenerationEnabled(biome) && reflector.isGenerationEnabled(world.provider);
            if (world.provider.getDimension() == 0) {
                double[] chunkArr = new double[]{chunkPos.x * 16 + 8, chunkPos.z * 16 + 8};
                double[] spawnArr = new double[]{spawnPos.getX(), spawnPos.getZ()};
                double spawnDistSq = reflector.distanceSq(chunkArr, spawnArr);
                float minDistSq = reflector.minDistToSpawnForGeneration();
                generate &= spawnDistSq >= (double) (minDistSq *= minDistSq);
            }
            if (generate) {
                this.planStructuresInChunk(random, chunkPos, world, biome);
            }
        }
    }
}
