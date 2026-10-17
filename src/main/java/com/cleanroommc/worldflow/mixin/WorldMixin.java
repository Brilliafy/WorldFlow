package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer;
import com.cleanroommc.worldflow.cascade.staging.GenerationContext;
import com.cleanroommc.worldflow.config.ModConfig;
import com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public abstract class WorldMixin {

    @Shadow
    public boolean isRemote;

    @Inject(method = "getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onGetBlockState(BlockPos pos, CallbackInfoReturnable<IBlockState> cir) {
        if (!this.isRemote && ModConfig.CONCURRENCY.enableThreadLocalWorldView && ThreadLocalWorldView.isGenerating()) {
            IBlockState state = ThreadLocalWorldView.getBlockState((World) (Object) this, pos);
            if (state != null) {
                cir.setReturnValue(state);
            }
        }
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onSetBlockState(BlockPos pos, IBlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        if (this.isRemote) {
            return;
        }

        World world = (World) (Object) this;

        // 1. Thread-local worker view isolation
        if (ModConfig.CONCURRENCY.enableThreadLocalWorldView && ThreadLocalWorldView.isGenerating()) {
            Boolean res = ThreadLocalWorldView.setBlockState(world, pos, newState, flags);
            if (res != null) {
                cir.setReturnValue(res);
                return;
            }
        }

        // 2. Cascade fix for synchronous Forge population
        if (ModConfig.GENERAL.enableCascadeFix && ModConfig.GENERAL.stageForgeDecorators && GenerationContext.isPopulating()) {
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;

            if (world instanceof WorldServer && GenerationContext.isCascading(world, chunkX, chunkZ)) {
                CascadeBlockBuffer.INSTANCE.stageBlock(world, pos, newState, null, GenerationContext.getGeneratorName());
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "getChunk(II)Lnet/minecraft/world/chunk/Chunk;",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onGetChunk(int chunkX, int chunkZ, CallbackInfoReturnable<Chunk> cir) {
        if (!this.isRemote && ModConfig.CONCURRENCY.enableThreadLocalWorldView && ThreadLocalWorldView.isGenerating()) {
            Chunk chunk = ThreadLocalWorldView.getChunk((World) (Object) this, chunkX, chunkZ);
            if (chunk != null) {
                cir.setReturnValue(chunk);
            }
        }
    }

    @Inject(method = "getBiome(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/world/biome/Biome;",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onGetBiome(BlockPos pos, CallbackInfoReturnable<Biome> cir) {
        if (!this.isRemote && ModConfig.CONCURRENCY.enableThreadLocalWorldView && ThreadLocalWorldView.isGenerating()) {
            Biome biome = ThreadLocalWorldView.getBiome((World) (Object) this, pos);
            if (biome != null) {
                cir.setReturnValue(biome);
            }
        }
    }

    @Inject(method = "getHeight(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/util/math/BlockPos;",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onGetHeight(BlockPos pos, CallbackInfoReturnable<BlockPos> cir) {
        if (!this.isRemote && ModConfig.CONCURRENCY.enableThreadLocalWorldView && ThreadLocalWorldView.isGenerating()) {
            Integer height = ThreadLocalWorldView.getHeight((World) (Object) this, pos);
            if (height != null) {
                cir.setReturnValue(new BlockPos(pos.getX(), height, pos.getZ()));
            }
        }
    }

    @Inject(method = "isBlockLoaded(Lnet/minecraft/util/math/BlockPos;)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onIsBlockLoaded(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!this.isRemote && ModConfig.CONCURRENCY.enableThreadLocalWorldView && ThreadLocalWorldView.isGenerating()) {
            Boolean loaded = ThreadLocalWorldView.isBlockLoaded((World) (Object) this, pos);
            if (loaded != null) {
                cir.setReturnValue(loaded);
            }
        }
    }

    @Inject(method = "getTileEntity(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/tileentity/TileEntity;",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onGetTileEntity(BlockPos pos, CallbackInfoReturnable<TileEntity> cir) {
        if (!this.isRemote && ModConfig.CONCURRENCY.enableThreadLocalWorldView && ThreadLocalWorldView.isGenerating()) {
            TileEntity te = ThreadLocalWorldView.getTileEntity((World) (Object) this, pos);
            if (te != null) {
                cir.setReturnValue(te);
            }
        }
    }

    @Inject(method = "getBiomeProvider",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$redirectBiomeProvider(CallbackInfoReturnable<net.minecraft.world.biome.BiomeProvider> cir) {
        if (!this.isRemote && ModConfig.CONCURRENCY.enableThreadLocalWorldView && ThreadLocalWorldView.isGenerating()) {
            net.minecraft.world.biome.BiomeProvider workerProvider = com.cleanroommc.worldflow.pipeline.isolation.WorkerGeneratorContext.get().getBiomeProvider((World) (Object) this);
            if (workerProvider != null) {
                cir.setReturnValue(workerProvider);
            }
        }
    }
}
