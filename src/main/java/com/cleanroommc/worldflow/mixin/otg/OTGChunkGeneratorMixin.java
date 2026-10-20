package com.cleanroommc.worldflow.mixin.otg;

import com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer;
import com.cleanroommc.worldflow.cascade.staging.GenerationContext;
import com.cleanroommc.worldflow.config.ModConfig;
import com.cleanroommc.worldflow.pipeline.otg.OTGParallelContext;
import com.pg85.otg.common.LocalMaterialData;
import com.pg85.otg.forge.generator.ForgeChunkBuffer;
import com.pg85.otg.forge.generator.OTGChunkGenerator;
import com.pg85.otg.forge.materials.ForgeMaterialData;
import com.pg85.otg.forge.util.NBTHelper;
import com.pg85.otg.forge.world.ForgeWorld;
import com.pg85.otg.util.ChunkCoordinate;
import com.pg85.otg.util.FifoMap;
import com.pg85.otg.util.bo3.NamedBinaryTag;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = OTGChunkGenerator.class, remap = false)
public abstract class OTGChunkGeneratorMixin {

    @Shadow
    private ForgeWorld world;

    @Shadow
    private FifoMap unloadedChunksCache;

    @Inject(method = "getBlocks(IIZ)Lnet/minecraft/world/chunk/Chunk;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void worldflow$onOTGGetBlocks(int chunkX, int chunkZ, boolean provideChunk, CallbackInfoReturnable<Chunk> cir) {
        if (!ModConfig.CONCURRENCY.enableOTGParallelTerrain) {
            return;
        }
        if (!com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
            return;
        }

        ChunkCoordinate chunkCoord = ChunkCoordinate.fromChunkCoords(chunkX, chunkZ);
        Chunk chunk;
        synchronized (this.unloadedChunksCache) {
            chunk = (Chunk) this.unloadedChunksCache.get(chunkCoord);
        }
        if (chunk == null) {
            chunk = OTGParallelContext.generateOTGChunk(this.world, chunkX, chunkZ);
            if (chunk != null && !provideChunk) {
                synchronized (this.unloadedChunksCache) {
                    this.unloadedChunksCache.put(chunkCoord, chunk);
                }
            }
        } else {
            OTGParallelContext.fillBiomeArray(this.world, chunk);
            chunk.generateSkylightMap();
        }
        if (chunk != null) {
            cir.setReturnValue(chunk);
        }
    }

    /**
     * The parallel terrain path bypasses OTG's synchronized getBlocks(), which is the
     * only place that fills {@code chunkBuffer}. Structure generation calls
     * getHighestBlockInCurrentlyPopulatingChunk() during the generate() call; return
     * this worker's buffer instead of the shared (null) field.
     */
    @Redirect(method = "getHighestBlockInCurrentlyPopulatingChunk",
              at = @At(value = "FIELD",
                       target = "Lcom/pg85/otg/forge/generator/OTGChunkGenerator;chunkBuffer:Lcom/pg85/otg/forge/generator/ForgeChunkBuffer;",
                       opcode = Opcodes.GETFIELD),
              remap = false)
    private ForgeChunkBuffer worldflow$currentChunkBuffer(OTGChunkGenerator self) {
        ForgeChunkBuffer parallel = OTGParallelContext.CURRENT_BUFFER.get();
        if (parallel != null) {
            return parallel;
        }
        return ((OTGChunkGeneratorAccessor) self).worldflow$getChunkBuffer();
    }

    @Inject(method = "setBlock(IIILcom/pg85/otg/common/LocalMaterialData;Lcom/pg85/otg/util/bo3/NamedBinaryTag;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void cascadeFix$onOTGSetBlock(int x, int y, int z, LocalMaterialData material, NamedBinaryTag metaDataTag, CallbackInfo ci) {
        if (!com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
            return;
        }
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
        if (!com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
            return;
        }
        GenerationContext.enterPopulate(chunkX, chunkZ, "OTG");
    }

    @Inject(method = "func_185931_b", at = @At("RETURN"), remap = false)
    private void cascadeFix$onOTGPopulateEnd(int chunkX, int chunkZ, CallbackInfo ci) {
        if (!com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
            return;
        }
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
