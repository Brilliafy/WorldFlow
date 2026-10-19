package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer;
import com.cleanroommc.worldflow.cascade.staging.GenerationContext;
import com.cleanroommc.worldflow.config.ModConfig;
import com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityFallingBlock;
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
import net.minecraft.world.EnumSkyBlock;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = World.class, priority = 500)
public abstract class WorldMixin {

    @Shadow
    public boolean isRemote;

    @Inject(method = "getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onGetBlockState(BlockPos pos, CallbackInfoReturnable<IBlockState> cir) {
        if (this.isRemote) {
            return;
        }
        if (ModConfig.CONCURRENCY == null || !ModConfig.CONCURRENCY.enableThreadLocalWorldView) {
            return;
        }
        // Single ThreadLocal lookup: getBlockState itself returns null when no worker
        // context is active, so the old pre-check doubled the ThreadLocal cost.
        IBlockState state = ThreadLocalWorldView.getBlockState((World) (Object) this, pos.getX(), pos.getY(), pos.getZ());
        if (state != null) {
            cir.setReturnValue(state);
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
        if (ThreadLocalWorldView.isGenerating() && ModConfig.CONCURRENCY != null && ModConfig.CONCURRENCY.enableThreadLocalWorldView) {
            Boolean res = ThreadLocalWorldView.setBlockState(world, pos, newState, flags);
            if (res != null) {
                cir.setReturnValue(res);
                return;
            }
        }

        // 2. Cascade fix for synchronous Forge population
        if (ModConfig.GENERAL != null && ModConfig.GENERAL.enableCascadeFix && ModConfig.GENERAL.stageForgeDecorators && GenerationContext.isPopulating()) {
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
        if (!ThreadLocalWorldView.isGenerating()) {
            return;
        }
        if (!this.isRemote && ModConfig.CONCURRENCY != null && ModConfig.CONCURRENCY.enableThreadLocalWorldView) {
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
        if (!ThreadLocalWorldView.isGenerating()) {
            return;
        }
        if (!this.isRemote && ModConfig.CONCURRENCY != null && ModConfig.CONCURRENCY.enableThreadLocalWorldView) {
            Biome biome = ThreadLocalWorldView.getBiome((World) (Object) this, pos);
            if (biome != null) {
                cir.setReturnValue(biome);
            } else {
                cir.setReturnValue(net.minecraft.init.Biomes.PLAINS);
            }
        }
    }

    @Inject(method = "getHeight(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/util/math/BlockPos;",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onGetHeight(BlockPos pos, CallbackInfoReturnable<BlockPos> cir) {
        if (!ThreadLocalWorldView.isGenerating()) {
            return;
        }
        if (!this.isRemote && ModConfig.CONCURRENCY != null && ModConfig.CONCURRENCY.enableThreadLocalWorldView) {
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
        if (!ThreadLocalWorldView.isGenerating()) {
            return;
        }
        if (!this.isRemote && ModConfig.CONCURRENCY != null && ModConfig.CONCURRENCY.enableThreadLocalWorldView) {
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
        if (!ThreadLocalWorldView.isGenerating()) {
            return;
        }
        if (!this.isRemote && ModConfig.CONCURRENCY != null && ModConfig.CONCURRENCY.enableThreadLocalWorldView) {
            TileEntity te = ThreadLocalWorldView.getTileEntity((World) (Object) this, pos);
            if (te != null) {
                cir.setReturnValue(te);
            }
        }
    }

    @Inject(method = "addTileEntity(Lnet/minecraft/tileentity/TileEntity;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void worldflow$guardWorldAddTileEntity(TileEntity tileEntity, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (tileEntity == null || this.isRemote) {
            return;
        }
        BlockPos pos = tileEntity.getPos();
        if (pos == null) {
            return;
        }
        World world = (World) (Object) this;
        IBlockState state = world.getBlockState(pos);
        if (state != null && state.getBlock() != net.minecraft.init.Blocks.AIR
                && !state.getBlock().hasTileEntity(state)) {
            WorldFlow.LOGGER.debug("[WorldFlow] Blocked world TE {} at {} on unsupported block {}",
                    tileEntity.getClass().getName(), pos, state.getBlock().getRegistryName());
            ci.cancel();
        }
    }

    @Inject(method = "setTileEntity(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/tileentity/TileEntity;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onSetTileEntity(BlockPos pos, TileEntity tileEntity, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (ThreadLocalWorldView.isGenerating()) {
            ci.cancel();
            if (tileEntity == null) {
                return;
            }
            ThreadLocalWorldView.setTileEntity((World) (Object) this, pos, tileEntity);
            return;
        }

        // Main-thread guard: never let a tile entity exist on a block that cannot own one.
        // Mods (rustic cabinets, nuclearcraft radiation) iterate World.loadedTileEntityList and
        // throw IllegalArgumentException when the block under the TE changed to e.g. bedrock.
        if (tileEntity == null || this.isRemote) {
            return;
        }
        World world = (World) (Object) this;
        IBlockState state = world.getBlockState(pos);
        if (state != null && state.getBlock() != net.minecraft.init.Blocks.AIR
                && !state.getBlock().hasTileEntity(state)) {
            WorldFlow.LOGGER.debug("[WorldFlow] Blocked tile entity {} at {} on unsupported block {}",
                    tileEntity.getClass().getName(), pos, state.getBlock().getRegistryName());
            ci.cancel();
        }
    }

    @Inject(method = "spawnEntity",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onSpawnEntity(Entity entityIn, CallbackInfoReturnable<Boolean> cir) {
        if (!ThreadLocalWorldView.isGenerating()) {
            return;
        }
        if (entityIn == null) {
            cir.setReturnValue(false);
            return;
        }
        if (!this.isRemote && entityIn instanceof EntityFallingBlock) {
            EntityFallingBlock falling = (EntityFallingBlock) entityIn;
            IBlockState state = falling.getBlock();
            if (state != null) {
                BlockPos pos = falling.getOrigin() != null ? falling.getOrigin() : new BlockPos(falling.posX, falling.posY, falling.posZ);
                ThreadLocalWorldView.setBlockState((World) (Object) this, pos, state, 3);
            }
            cir.setReturnValue(false);
            return;
        }
        if (!this.isRemote && (Object) this instanceof WorldServer) {
            CascadeBlockBuffer.INSTANCE.stageEntity((WorldServer) (Object) this, entityIn);
            cir.setReturnValue(true);
        }
    }

    /**
     * Off-thread chunk loads (the pipeline's disk guard) used to add entities straight
     * into {@code World.loadedEntityList}. SRP Mixins' mob-cap handler iterates that
     * list every tick on the server thread and crashed with a CME. Defer the exact
     * vanilla add to the next server tick instead of re-spawning (which would duplicate
     * entities already present in the chunk's own entity lists).
     */
    @Shadow
    protected abstract void loadEntities(java.util.Collection<Entity> entities);

    @Inject(method = "loadEntities", at = @At("HEAD"), cancellable = true)
    private void worldflow$deferOffThreadLoadEntities(java.util.Collection<Entity> entities, CallbackInfo ci) {
        if (this.isRemote || !((Object) this instanceof WorldServer) || entities == null) {
            return;
        }
        WorldServer worldServer = (WorldServer) (Object) this;
        if (worldServer.getMinecraftServer() != null
                && !worldServer.getMinecraftServer().isCallingFromMinecraftThread()) {
            ci.cancel();
            java.util.List<Entity> copy = new java.util.ArrayList<>(entities);
            worldServer.addScheduledTask(() -> this.loadEntities(copy));
        }
    }

    @Inject(method = "updateBlockTick(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;II)V",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onUpdateBlockTick(BlockPos pos, net.minecraft.block.Block blockIn, int delay, int priority, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (ThreadLocalWorldView.isGenerating()) {
            if ((Object) this instanceof WorldServer) {
                CascadeBlockBuffer.INSTANCE.stageTickUpdate((WorldServer) (Object) this, pos, blockIn, delay, priority);
            }
            ci.cancel();
        }
    }

    @Inject(method = "markAndNotifyBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$onMarkAndNotifyBlock(BlockPos pos, Chunk chunk, IBlockState iblockstate, IBlockState newState, int flags, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (ThreadLocalWorldView.isGenerating()) {
            ci.cancel();
        }
    }

    @Inject(method = "notifyNeighborsOfStateChange", at = @At("HEAD"), cancellable = true)
    private void worldflow$onNotifyNeighbors(BlockPos pos, net.minecraft.block.Block blockType, boolean updateObservers, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (ThreadLocalWorldView.isGenerating()) {
            ci.cancel();
        }
    }

    @Inject(method = "getLight(Lnet/minecraft/util/math/BlockPos;)I", at = @At("HEAD"), cancellable = true)
    private void worldflow$onGetLight(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (ThreadLocalWorldView.isGenerating()) {
            cir.setReturnValue(ThreadLocalWorldView.getLight((World) (Object) this, pos, false));
        }
    }

    @Inject(method = "getLight(Lnet/minecraft/util/math/BlockPos;Z)I", at = @At("HEAD"), cancellable = true)
    private void worldflow$onGetLightWithNeighbors(BlockPos pos, boolean checkNeighbors, CallbackInfoReturnable<Integer> cir) {
        if (ThreadLocalWorldView.isGenerating()) {
            cir.setReturnValue(ThreadLocalWorldView.getLight((World) (Object) this, pos, checkNeighbors));
        }
    }

    @Inject(method = "getLightFor", at = @At("HEAD"), cancellable = true)
    private void worldflow$onGetLightFor(EnumSkyBlock type, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (ThreadLocalWorldView.isGenerating()) {
            cir.setReturnValue(ThreadLocalWorldView.getLightFor((World) (Object) this, type, pos));
        }
    }

    @Inject(method = "getLightFromNeighbors", at = @At("HEAD"), cancellable = true)
    private void worldflow$onGetLightFromNeighbors(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (ThreadLocalWorldView.isGenerating()) {
            cir.setReturnValue(ThreadLocalWorldView.getLight((World) (Object) this, pos, true));
        }
    }

    @Inject(method = "getLightFromNeighborsFor", at = @At("HEAD"), cancellable = true)
    private void worldflow$onGetLightFromNeighborsFor(EnumSkyBlock type, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (ThreadLocalWorldView.isGenerating()) {
            cir.setReturnValue(ThreadLocalWorldView.getLightFor((World) (Object) this, type, pos));
        }
    }

    @Inject(method = "getCombinedLight", at = @At("HEAD"), cancellable = true)
    private void worldflow$onGetCombinedLight(BlockPos pos, int lightValue, CallbackInfoReturnable<Integer> cir) {
        if (ThreadLocalWorldView.isGenerating()) {
            cir.setReturnValue(ThreadLocalWorldView.getCombinedLight((World) (Object) this, pos, lightValue));
        }
    }

    @Inject(method = "checkLightFor(Lnet/minecraft/world/EnumSkyBlock;Lnet/minecraft/util/math/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void worldflow$onCheckLightFor(EnumSkyBlock lightType, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (ThreadLocalWorldView.isGenerating()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "checkLight(Lnet/minecraft/util/math/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void worldflow$onCheckLight(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (ThreadLocalWorldView.isGenerating()) {
            cir.setReturnValue(true);
        }
    }
}
