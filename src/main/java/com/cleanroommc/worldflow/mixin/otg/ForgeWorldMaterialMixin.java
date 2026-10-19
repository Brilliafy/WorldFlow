package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.common.LocalMaterialData;
import com.pg85.otg.forge.materials.ForgeMaterialData;
import com.pg85.otg.forge.world.ForgeWorld;
import com.pg85.otg.util.ChunkCoordinate;
import com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Block reads during decoration must never go through OTGChunkGenerator.getChunk().
 *
 * <p>JFR showed ForgeWorld.getMaterial calling OTGChunkGenerator.getChunk on every block read
 * (~11% of all CPU, from PlantGen/OreGen). Those chunks are already available in the worker's
 * thread-local view, so read them directly; only fall back to the original path outside the
 * population area.</p>
 */
@Mixin(value = ForgeWorld.class, remap = false)
public abstract class ForgeWorldMaterialMixin {

    @org.spongepowered.asm.mixin.Unique
    private static final LocalMaterialData WORLD_FLOW_AIR =
            ForgeMaterialData.ofMinecraftBlockState(net.minecraft.init.Blocks.AIR.getDefaultState());

    @Overwrite(remap = false)
    public LocalMaterialData getMaterial(int x, int y, int z, ChunkCoordinate chunkBeingPopulated) {
        if (y >= 256 || y < 0) {
            return WORLD_FLOW_AIR;
        }
        ForgeWorld self = (ForgeWorld) (Object) this;
        if (chunkBeingPopulated != null
                && com.pg85.otg.OTG.IsInAreaBeingPopulated(x, z, chunkBeingPopulated)
                && ThreadLocalWorldView.isGenerating()) {
            IBlockState state = ThreadLocalWorldView.getBlockState(self.getWorld(), new BlockPos(x, y, z));
            if (state != null) {
                return ForgeMaterialData.ofMinecraftBlockState(state);
            }
        }
        if (chunkBeingPopulated != null) {
            if (!com.pg85.otg.OTG.IsInAreaBeingPopulated(x, z, chunkBeingPopulated)) {
                return WORLD_FLOW_AIR;
            }
            net.minecraft.world.chunk.Chunk chunk = self.getWorld().getChunk(x >> 4, z >> 4);
            return ForgeMaterialData.ofMinecraftBlockState(chunk.getBlockState(x & 15, y, z & 15));
        }
        net.minecraft.world.chunk.Chunk chunk = self.getWorld().getChunkProvider().getLoadedChunk(x >> 4, z >> 4);
        if (chunk == null) {
            return self.getChunkGenerator().getMaterialInUnloadedChunk(x, y, z);
        }
        return ForgeMaterialData.ofMinecraftBlockState(chunk.getBlockState(x & 15, y, z & 15));
    }
}
