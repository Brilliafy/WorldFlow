package com.cleanroommc.worldflow.mixin.otg;

import com.cleanroommc.worldflow.pipeline.ProtoChunk;
import net.minecraft.util.math.ChunkPos;
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
    private com.pg85.otg.generator.ObjectSpawner spawner;

    @Shadow
    private FifoMap unloadedChunksCache;

    @Shadow
    private net.minecraft.util.datafix.DataFixer dataFixer;

    @Inject(method = "getChunk(II)Lnet/minecraft/world/chunk/Chunk;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void worldflow$onOTGGetChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        if (com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
            Chunk chunk = com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.getChunk(
                    this.world.getWorld(), x >> 4, z >> 4);
            if (chunk != null) {
                cir.setReturnValue(chunk);
            }
        }
    }

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
        if (y < 0 || y >= 256 || material == null || material.isEmpty()) {
            ci.cancel();
            return;
        }
        if (!(material instanceof ForgeMaterialData)) {
            return;
        }
        IBlockState state = ((ForgeMaterialData) material).internalBlock();
        if (state == null) {
            ci.cancel();
            return;
        }

        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        net.minecraft.world.World rawWorld = this.world.getWorld();

        if (rawWorld instanceof WorldServer) {
            WorldServer worldServer = (WorldServer) rawWorld;
            BlockPos pos = new BlockPos(x, y, z);

            NBTTagCompound nmsTag = null;
            if (metaDataTag != null) {
                nmsTag = NBTHelper.getNMSFromNBTTagCompound(metaDataTag);
                nmsTag.setInteger("x", x);
                nmsTag.setInteger("y", y);
                nmsTag.setInteger("z", z);
                if (this.dataFixer != null) {
                    nmsTag = this.dataFixer.process(net.minecraft.util.datafix.FixTypes.BLOCK_ENTITY, nmsTag);
                }
            }

            // All terrain writes must go through the ProtoChunk that owns the target
            // chunk. That path serializes cross-worker writes, and stages writes to
            // already-published chunks for the main-thread flush. Calling
            // Chunk.setBlockState directly races the chunk's block-state palette and
            // tile-entity map (IntIdentityHashBiMap AIOOBE / HashMap CME).
            long key = ChunkPos.asLong(chunkX, chunkZ);
            com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.GenerationWorkerContext ctx =
                    com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.getContext();
            ProtoChunk target = ctx != null ? ctx.lookup(key) : null;
            if (target == null) {
                target = com.cleanroommc.worldflow.pipeline.GenerationScheduler.getInstance().getProtoChunkAny(key);
            }
            if (target != null) {
                target.setBlockState(pos, state, nmsTag);
                ci.cancel();
                return;
            }

            Chunk chunk = com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.getChunk(worldServer, chunkX, chunkZ);
            if (chunk != null) {
                chunk.setBlockState(pos, state);
                if (nmsTag != null) {
                    net.minecraft.tileentity.TileEntity te = chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);
                    if (te == null && state.getBlock().hasTileEntity(state)) {
                        te = state.getBlock().createTileEntity(worldServer, state);
                        if (te != null) {
                            te.setWorld(worldServer);
                            te.setPos(pos);
                            chunk.addTileEntity(pos, te);
                        }
                    }
                    if (te != null) {
                        te.readFromNBT(nmsTag);
                    }
                }
                ci.cancel();
                return;
            }

            if (ModConfig.GENERAL.enableCascadeFix && ModConfig.GENERAL.stageOTGBlocks) {
                if (worldServer.getChunkProvider().getLoadedChunk(chunkX, chunkZ) == null) {
                    CascadeBlockBuffer.INSTANCE.stageBlock(worldServer, pos, state, nmsTag, "OTG/Dregora");
                    ci.cancel();
                }
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

    @Redirect(method = "func_185931_b",
              at = @At(value = "FIELD",
                       target = "Lcom/pg85/otg/forge/generator/OTGChunkGenerator;spawner:Lcom/pg85/otg/generator/ObjectSpawner;",
                       opcode = org.objectweb.asm.Opcodes.GETFIELD),
              remap = false)
    private com.pg85.otg.generator.ObjectSpawner worldflow$threadLocalSpawner(OTGChunkGenerator self) {
        if (com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
            com.pg85.otg.generator.ObjectSpawner localSpawner = com.cleanroommc.worldflow.pipeline.otg.OTGParallelContext.getThreadLocalSpawner(this.world);
            if (localSpawner != null) {
                return localSpawner;
            }
        }
        return this.spawner;
    }
}
