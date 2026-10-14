package com.cleanroommc.worldflow.mixin.otg;

import com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer;
import com.cleanroommc.worldflow.cascade.staging.GenerationContext;
import com.cleanroommc.worldflow.config.ModConfig;
import com.pg85.otg.common.LocalMaterialData;
import com.pg85.otg.forge.generator.OTGChunkGenerator;
import com.pg85.otg.forge.materials.ForgeMaterialData;
import com.pg85.otg.forge.util.NBTHelper;
import com.pg85.otg.forge.world.ForgeWorld;
import com.pg85.otg.util.bo3.NamedBinaryTag;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = OTGChunkGenerator.class, remap = false)
public abstract class OTGChunkGeneratorMixin {

    @Shadow
    private ForgeWorld world;

    @Inject(method = "setBlock(IIILcom/pg85/otg/common/LocalMaterialData;Lcom/pg85/otg/util/bo3/NamedBinaryTag;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void cascadeFix$onOTGSetBlock(int x, int y, int z, LocalMaterialData material, NamedBinaryTag metaDataTag, CallbackInfo ci) {
        if (!ModConfig.GENERAL.enableCascadeFix || !ModConfig.GENERAL.stageOTGBlocks) {
            return;
        }

        if (y < 0 || y >= 256 || material == null || material.isEmpty()) {
            return;
        }

        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        net.minecraft.world.World rawWorld = this.world.getWorld();

        if (rawWorld instanceof WorldServer) {
            WorldServer worldServer = (WorldServer) rawWorld;
            if (worldServer.getChunkProvider().getLoadedChunk(chunkX, chunkZ) == null) {
                // Target chunk is not loaded! Staging the block prevents the dreaded recursive provideChunk() cascade!
                if (!(material instanceof ForgeMaterialData)) {
                    // Not a Forge material implementation we can safely resolve; let OTG handle it normally.
                    return;
                }
                IBlockState state = ((ForgeMaterialData) material).internalBlock();
                if (state == null) {
                    return;
                }
                NBTTagCompound teNbt = metaDataTag != null ? NBTHelper.getNMSFromNBTTagCompound(metaDataTag) : null;

                CascadeBlockBuffer.INSTANCE.stageBlock(worldServer, new BlockPos(x, y, z), state, teNbt, "OTG/Dregora");
                ci.cancel();
            }
        }
    }

    @Inject(method = "func_185931_b", at = @At("HEAD"), remap = false)
    private void cascadeFix$onOTGPopulateStart(int chunkX, int chunkZ, CallbackInfo ci) {
        GenerationContext.enterPopulate(chunkX, chunkZ, "OTG");
    }

    @Inject(method = "func_185931_b", at = @At("RETURN"), remap = false)
    private void cascadeFix$onOTGPopulateEnd(int chunkX, int chunkZ, CallbackInfo ci) {
        GenerationContext.exitPopulate();

        net.minecraft.world.World rawWorld = this.world.getWorld();
        if (rawWorld instanceof WorldServer) {
            WorldServer worldServer = (WorldServer) rawWorld;
            Chunk chunk = worldServer.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
            if (chunk != null) {
                CascadeBlockBuffer.INSTANCE.flushChunk(worldServer, chunk);
            }
        }
    }
}
