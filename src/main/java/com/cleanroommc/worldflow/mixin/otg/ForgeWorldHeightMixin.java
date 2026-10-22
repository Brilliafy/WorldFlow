package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.forge.world.ForgeWorld;
import com.pg85.otg.util.ChunkCoordinate;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Zero-allocation rewrite of OTG's height scan.
 *
 * <p>The original allocates a ForgeMaterialData for every block in a column and dereferences it
 * several times. Resource spawning calls this hundreds of times per chunk, which measured as
 * ~99ms/chunk in structure-dense areas (processResourcesAfterBo4s). This version works directly
 * on IBlockState/Material so no objects are created per block.</p>
 */
@Mixin(value = ForgeWorld.class, remap = false)
public abstract class ForgeWorldHeightMixin {

    @Shadow
    public abstract int getHeightCap();

    @Overwrite(remap = false)
    public int getHighestBlockYAt(int x, int z, boolean findSolid, boolean findLiquid, boolean ignoreLiquid,
                                  boolean ignoreSnow, boolean ignoreLeaves, ChunkCoordinate chunkBeingPopulated) {
        ForgeWorld self = (ForgeWorld) (Object) this;
        Chunk chunk = null;
        if (chunkBeingPopulated != null && com.pg85.otg.OTG.IsInAreaBeingPopulated(x, z, chunkBeingPopulated)) {
            if (com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
                chunk = com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.getChunk(self.getWorld(), x >> 4, z >> 4);
            }
            if (chunk == null) {
                chunk = self.getChunkGenerator().getChunk(x, z);
            }
        }
        if (chunk == null && chunkBeingPopulated == null) {
            if (com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
                chunk = com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.getChunk(self.getWorld(), x >> 4, z >> 4);
            }
            if (chunk == null) {
                if (self.getWorld().isBlockLoaded(new BlockPos(x, 255, z))) {
                    chunk = self.getChunkGenerator().getChunk(x, z);
                } else {
                    return self.getChunkGenerator().getHighestBlockYInUnloadedChunk(x, z, findSolid, findLiquid, ignoreLiquid, ignoreSnow);
                }
            }
        }
        if (chunk == null) {
            return -1;
        }

        int internalX = x & 0xF;
        int internalZ = z & 0xF;
        int heightMapy = chunk.getHeightValue(internalX, internalZ);

        boolean incorrectHeightMap = false;
        while (heightMapy < self.getHeightCap()
                && chunk.getBlockState(internalX, heightMapy, internalZ).getMaterial().blocksLight()) {
            heightMapy++;
            incorrectHeightMap = true;
        }
        if (incorrectHeightMap && !com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
            self.getWorld().checkLight(new BlockPos(x, heightMapy, z));
        }

        for (int i = heightMapy; i >= 0; i--) {
            IBlockState blockState = chunk.getBlockState(internalX, i, internalZ);
            Block block = blockState.getBlock();
            Material material = blockState.getMaterial();

            boolean isLiquid = material.isLiquid();
            boolean leaves = block == Blocks.LEAVES || block == Blocks.LEAVES2;
            boolean isSolid = (material.isSolid() && (!ignoreLeaves || !leaves))
                    || (!ignoreLeaves && leaves)
                    || (!ignoreSnow && block == Blocks.SNOW_LAYER);

            if (!(ignoreLiquid && isLiquid)) {
                if ((findSolid && isSolid) || (findLiquid && isLiquid)) {
                    return i;
                }
                if ((findSolid && isLiquid) || (findLiquid && isSolid)) {
                    return -1;
                }
            }
        }
        return -1;
    }

    @Overwrite(remap = false)
    public int getHeightMapHeight(int x, int z, ChunkCoordinate chunkBeingPopulated) {
        ForgeWorld self = (ForgeWorld) (Object) this;
        Chunk chunk = null;
        if (chunkBeingPopulated != null && com.pg85.otg.OTG.IsInAreaBeingPopulated(x, z, chunkBeingPopulated)) {
            if (com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
                chunk = com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.getChunk(self.getWorld(), x >> 4, z >> 4);
            }
            if (chunk == null) {
                chunk = self.getChunkGenerator().getChunk(x, z);
            }
        }
        if (chunk == null && chunkBeingPopulated == null) {
            if (com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
                chunk = com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.getChunk(self.getWorld(), x >> 4, z >> 4);
            }
            if (chunk == null) {
                if (self.getWorld().isBlockLoaded(new BlockPos(x, 255, z))) {
                    chunk = self.getChunkGenerator().getChunk(x, z);
                } else {
                    return -1;
                }
            }
        }
        if (chunk == null) {
            return -1;
        }
        int internalX = x & 0xF;
        int internalZ = z & 0xF;
        int heightMapy = chunk.getHeightValue(internalX, internalZ);
        while (heightMapy < self.getHeightCap() && chunk.getBlockState(internalX, heightMapy, internalZ).getMaterial().blocksLight()) {
            heightMapy++;
        }
        return heightMapy;
    }
}
