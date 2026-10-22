package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.common.LocalMaterialData;
import com.pg85.otg.forge.materials.ForgeMaterialData;
import com.pg85.otg.forge.world.ForgeWorld;
import com.pg85.otg.util.ChunkCoordinate;
import com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

/**
 * Zero-allocation, lock-free block reads during decoration.
 *
 * <p>Bypasses BlockPos instantiation and OTGChunkGenerator.getChunk() by reading
 * directly from ThreadLocalWorldView using primitive block coordinates. Caches
 * LocalMaterialData by IBlockState ID to eliminate millions of ForgeMaterialData
 * allocations per generation cycle.</p>
 */
@Mixin(value = ForgeWorld.class, remap = false)
public abstract class ForgeWorldMaterialMixin {

    @Unique
    private static final LocalMaterialData WORLD_FLOW_AIR =
            ForgeMaterialData.ofMinecraftBlockState(Blocks.AIR.getDefaultState());

    @Unique
    private static final LocalMaterialData[] STATE_TO_MATERIAL = new LocalMaterialData[8192];

    @Unique
    private static LocalMaterialData getCachedMaterial(IBlockState state) {
        if (state == null) {
            return WORLD_FLOW_AIR;
        }
        int id = Block.getStateId(state);
        if (id >= 0 && id < STATE_TO_MATERIAL.length) {
            LocalMaterialData cached = STATE_TO_MATERIAL[id];
            if (cached != null) {
                return cached;
            }
            LocalMaterialData mat = ForgeMaterialData.ofMinecraftBlockState(state);
            STATE_TO_MATERIAL[id] = mat;
            return mat;
        }
        return ForgeMaterialData.ofMinecraftBlockState(state);
    }

    @Overwrite(remap = false)
    public LocalMaterialData getMaterial(int x, int y, int z, ChunkCoordinate chunkBeingPopulated) {
        if (y >= 256 || y < 0) {
            return WORLD_FLOW_AIR;
        }
        ForgeWorld self = (ForgeWorld) (Object) this;
        if (chunkBeingPopulated != null && com.pg85.otg.OTG.IsInAreaBeingPopulated(x, z, chunkBeingPopulated)) {
            if (ThreadLocalWorldView.isGenerating()) {
                IBlockState state = ThreadLocalWorldView.getBlockState(self.getWorld(), x, y, z);
                if (state != null) {
                    return getCachedMaterial(state);
                }
            }
            net.minecraft.world.chunk.Chunk chunk = ThreadLocalWorldView.isGenerating()
                    ? ThreadLocalWorldView.getChunk(self.getWorld(), x >> 4, z >> 4)
                    : self.getWorld().getChunk(x >> 4, z >> 4);
            if (chunk != null) {
                return getCachedMaterial(chunk.getBlockState(x & 15, y, z & 15));
            }
            return WORLD_FLOW_AIR;
        }
        if (chunkBeingPopulated != null) {
            return WORLD_FLOW_AIR;
        }
        net.minecraft.world.chunk.Chunk chunk = self.getWorld().getChunkProvider().getLoadedChunk(x >> 4, z >> 4);
        if (chunk == null) {
            return self.getChunkGenerator().getMaterialInUnloadedChunk(x, y, z);
        }
        return getCachedMaterial(chunk.getBlockState(x & 15, y, z & 15));
    }
}
