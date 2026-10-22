package com.cleanroommc.worldflow.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.structure.MapGenStructure;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraft.world.gen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Random;

/**
 * Vanilla MapGenStructure synchronizes both structure generation and recursive
 * generation on the shared generator object. The structure map/data are now
 * concurrent snapshots, and the 4-phase scheduler guarantees disjoint 3x3
 * population windows, so these monitors are no longer needed.
 */
@Mixin(MapGenStructure.class)
public abstract class MapGenStructureLocklessMixin {

    @Shadow protected Long2ObjectMap<StructureStart> structureMap;
    @Shadow protected abstract void initializeStructureData(World worldIn);
    @Shadow protected abstract boolean canSpawnStructureAtCoords(int chunkX, int chunkZ);
    @Shadow protected abstract StructureStart getStructureStart(int chunkX, int chunkZ);
    @Shadow(remap = false)
    private void func_143026_a(int chunkX, int chunkZ, StructureStart start) { throw new AssertionError(); }

    @Overwrite
    protected void recursiveGenerate(World worldIn, int chunkX, int chunkZ, int originalX, int originalZ, ChunkPrimer primer) {
        this.initializeStructureData(worldIn);
        long key = ChunkPos.asLong(chunkX, chunkZ);
        if (this.structureMap.containsKey(key)) {
            return;
        }
        ((MapGenBaseAccessor) (Object) this).worldflow$getRand().nextInt();
        try {
            if (this.canSpawnStructureAtCoords(chunkX, chunkZ)) {
                StructureStart start = this.getStructureStart(chunkX, chunkZ);
                this.structureMap.put(key, start);
                if (start.isSizeableStructure()) {
                    this.func_143026_a(chunkX, chunkZ, start);
                }
            }
        } catch (Throwable t) {
            throw new RuntimeException("Exception preparing structure feature at " + chunkX + "," + chunkZ, t);
        }
    }
}
