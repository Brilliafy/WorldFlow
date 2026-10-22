package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.config.ModConfig;
import com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer;
import com.cleanroommc.worldflow.cascade.staging.GenerationContext;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.cleanroommc.worldflow.pipeline.IWorldFlowChunk;

@Mixin(value = Chunk.class, priority = 500)
public abstract class ChunkMixin implements IWorldFlowChunk {

    @Inject(method = "getLightSubtracted", at = @At("HEAD"), cancellable = true)
    private void worldflow$onGetLightSubtracted(net.minecraft.util.math.BlockPos pos, int amount, CallbackInfoReturnable<Integer> cir) {
        if (com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
            Chunk chunk = (Chunk) (Object) this;
            int light = com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.getChunkSafeLight(chunk, pos);
            cir.setReturnValue(Math.max(0, light - amount));
        }
    }

    @Inject(method = "getLightFor", at = @At("HEAD"), cancellable = true)
    private void worldflow$onGetLightFor(net.minecraft.world.EnumSkyBlock type, net.minecraft.util.math.BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
            Chunk chunk = (Chunk) (Object) this;
            cir.setReturnValue(com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.getChunkSafeLightFor(chunk, type, pos));
        }
    }

    private IWorldFlowChunk.GenPath worldflow$genPath = IWorldFlowChunk.GenPath.UNKNOWN;
    @org.spongepowered.asm.mixin.Unique
    private volatile boolean worldflow$published = false;

    @Override
    public IWorldFlowChunk.GenPath worldflow$getGenPath() {
        return this.worldflow$genPath;
    }

    @Override
    public void worldflow$setGenPath(IWorldFlowChunk.GenPath path) {
        this.worldflow$genPath = path;
    }

    @Override
    public boolean worldflow$isPublished() {
        return this.worldflow$published;
    }

    @Override
    public void worldflow$setPublished(boolean published) {
        this.worldflow$published = published;
    }

    @Shadow
    @Final
    public int x;

    @Shadow
    @Final
    public int z;

    @Shadow
    @Final
    private World world;

    @Inject(method = "removeTileEntity(Lnet/minecraft/util/math/BlockPos;)V", at = @At("HEAD"), cancellable = true)
    private void worldflow$onRemoveTileEntity(net.minecraft.util.math.BlockPos pos, CallbackInfo ci) {
        Chunk self = (Chunk) (Object) this;
        if (!self.isLoaded()) {
            net.minecraft.tileentity.TileEntity te = self.getTileEntityMap().remove(pos);
            if (te != null) {
                te.invalidate();
            }
            net.minecraft.block.state.IBlockState state = self.getBlockState(pos);
            if (state.getBlock() == net.minecraft.init.Blocks.MOB_SPAWNER) {
                net.minecraft.tileentity.TileEntity spawner = state.getBlock().createTileEntity(this.world, state);
                if (spawner != null) {
                    self.addTileEntity(pos, spawner);
                }
            }
            ci.cancel();
        }
    }

    @Inject(method = "addTileEntity(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/tileentity/TileEntity;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$guardAddTileEntity(net.minecraft.util.math.BlockPos pos, net.minecraft.tileentity.TileEntity tileEntity,
                                              CallbackInfo ci) {
        if (tileEntity == null) {
            return;
        }
        // Final net: mods/structures sometimes add a TE directly to the chunk. If the block at
        // that position cannot own one (e.g. bedrock), refuse - otherwise the world TE list
        // carries a stale entry that crashes nuclearcraft/rustic on the next tick.
        net.minecraft.block.state.IBlockState state = ((Chunk) (Object) this).getBlockState(pos);
        if (state != null && state.getBlock() != net.minecraft.init.Blocks.AIR
                && !state.getBlock().hasTileEntity(state)) {
            ci.cancel();
        }
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)Lnet/minecraft/block/state/IBlockState;",
            at = @At("RETURN"))
    private void worldflow$purgeOrphanedTileEntity(net.minecraft.util.math.BlockPos pos, net.minecraft.block.state.IBlockState newState,
                                                   CallbackInfoReturnable<net.minecraft.block.state.IBlockState> cir) {
        if (newState == null) {
            return;
        }
        if (newState.getBlock().hasTileEntity(newState)) {
            return;
        }
        // A TE must never outlive its block: mods tick these maps and crash on stale entries
        // (rustic cabinet on bedrock + nuclearcraft radiation scan). This also applies to
        // worker-thread flushes of staged structure blocks, so the check is unconditional.
        Chunk self = (Chunk) (Object) this;
        if (self.getTileEntityMap().containsKey(pos)) {
            self.removeTileEntity(pos);
            if (this.world instanceof WorldServer) {
                WorldServer ws = (WorldServer) this.world;
                if (ws.isCallingFromMinecraftThread()) {
                    ws.loadedTileEntityList.removeIf(te -> te != null && pos.equals(te.getPos()));
                } else {
                    // World list is main-thread only; hand the cleanup over.
                    com.cleanroommc.worldflow.pipeline.PublishManager.INSTANCE.enqueueTileEntityPurge(ws, pos);
                }
            }
        }
    }

    @Inject(method = "populate(Lnet/minecraft/world/gen/IChunkGenerator;)V", at = @At("HEAD"))
    private void cascadeFix$onChunkPopulateHead(IChunkGenerator generator, CallbackInfo ci) {
        if (!com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
            return;
        }
        if (ModConfig.GENERAL.enableCascadeFix && ModConfig.GENERAL.stageForgeDecorators) {
            String genName = generator != null ? generator.getClass().getSimpleName() : "VanillaChunkGen";
            GenerationContext.enterPopulate(this.x, this.z, genName);
        }
    }

    @Inject(method = "populate(Lnet/minecraft/world/gen/IChunkGenerator;)V", at = @At("RETURN"))
    private void cascadeFix$onChunkPopulateReturn(IChunkGenerator generator, CallbackInfo ci) {
        if (!com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
            return;
        }
        if (ModConfig.GENERAL.enableCascadeFix && ModConfig.GENERAL.stageForgeDecorators) {
            GenerationContext.exitPopulate();
            if (this.world instanceof WorldServer) {
                CascadeBlockBuffer.INSTANCE.flushChunk((WorldServer) this.world, (Chunk) (Object) this);
            }
        }
    }
}
