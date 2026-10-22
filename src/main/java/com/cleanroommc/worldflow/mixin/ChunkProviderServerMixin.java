package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.config.ModConfig;
import com.cleanroommc.worldflow.pipeline.GenerationScheduler;
import com.cleanroommc.worldflow.pipeline.PublishManager;
import com.cleanroommc.worldflow.pipeline.StatusHolder;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

@Mixin(ChunkProviderServer.class)
public abstract class ChunkProviderServerMixin {

    @Shadow
    @Final
    public WorldServer world;

    @Shadow
    public net.minecraft.world.chunk.storage.IChunkLoader chunkLoader;

    @Shadow
    @Nullable
    public abstract Chunk getLoadedChunk(int x, int z);

    @Shadow
    @Nullable
    public abstract Chunk loadChunk(int x, int z);

    @Inject(method = "provideChunk(II)Lnet/minecraft/world/chunk/Chunk;",
            at = @At("HEAD"),
            cancellable = true)
    private void worldflow$onProvideChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        if (!ModConfig.CONCURRENCY.enableParallelGeneration) {
            return;
        }

        // 0. Off-thread worker guard: Never trigger synchronous vanilla chunk generation/population on worker threads!
        if (com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
            Chunk chunk = com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.getChunk(this.world, x, z);
            if (chunk != null) {
                cir.setReturnValue(chunk);
                return;
            }
        }

        // 1. Check if chunk is already loaded in RAM
        Chunk loaded = this.getLoadedChunk(x, z);
        if (loaded != null) {
            cir.setReturnValue(loaded);
            return;
        }

        // 2. DISK-EXISTENCE GUARD: If saved in region files, load from disk — NEVER generate!
        if (this.chunkLoader != null && this.chunkLoader.isChunkGeneratedAt(x, z)) {
            Chunk diskChunk = this.loadChunk(x, z);
            if (diskChunk != null) {
                cir.setReturnValue(diskChunk);
                return;
            }
        }

        // 3. Coordinate with the parallel pipeline:
        long key = ChunkPos.asLong(x, z);
        StatusHolder holder = GenerationScheduler.getInstance().getInFlight(key);
        if (holder == null) {
            holder = GenerationScheduler.getInstance().getPendingRequest(key);
        }

        // If chunk is not yet requested, request it through the pipeline
        if (holder == null) {
            GenerationScheduler.getInstance().requestChunk(
                    this.world, x, z,
                    com.cleanroommc.worldflow.pipeline.ChunkStatus.FULL,
                    com.cleanroommc.worldflow.pipeline.GenerationPriority.IMMEDIATE
            );
            holder = GenerationScheduler.getInstance().getInFlight(key);
            if (holder == null) {
                holder = GenerationScheduler.getInstance().getPendingRequest(key);
            }
        }

        if (holder != null) {
            if (holder.isDone()) {
                cir.setReturnValue(holder.getProtoChunk().getChunk());
                return;
            }
            if (holder.isWorkerDone() || holder.getCurrentStatus().isAtLeast(com.cleanroommc.worldflow.pipeline.ChunkStatus.LIGHT)) {
                PublishManager.INSTANCE.publishChunk(this.world, holder);
                cir.setReturnValue(holder.getProtoChunk().getChunk());
                return;
            }

            // Non-blocking main-thread handling. Never park the server or wait on a
            // worker future here: that is the old priority-inversion freeze. Keep
            // pumping the ready queue and serve the current proto to the caller.
            holder.bumpPriority(com.cleanroommc.worldflow.pipeline.GenerationPriority.IMMEDIATE);
            GenerationScheduler.getInstance().pump(holder);
            if (holder.isDone()
                    || holder.getCurrentStatus().isAtLeast(com.cleanroommc.worldflow.pipeline.ChunkStatus.LIGHT)) {
                PublishManager.INSTANCE.publishChunk(this.world, holder);
                Chunk ready = holder.getProtoChunk().getChunk();
                if (ready != null) {
                    cir.setReturnValue(ready);
                    return;
                }
            }

            // Serve the in-progress proto rather than blocking for completion.
            Chunk readyChunk = holder.getProtoChunk().getChunk();
            if (readyChunk == null) {
                readyChunk = new Chunk(this.world, x, z);
                holder.getProtoChunk().setUnderlyingChunk(readyChunk);
            }
            cir.setReturnValue(readyChunk);
            return;
        }

        // Failsafe: Never fall back to full inline populate on main thread while pipeline is enabled
        Chunk fallbackChunk = new Chunk(this.world, x, z);
        cir.setReturnValue(fallbackChunk);
    }

    @Inject(method = "provideChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("RETURN"))
    private void worldflow$afterProvideChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = cir.getReturnValue();
        if (chunk != null) {
            long key = ChunkPos.asLong(x, z);
            StatusHolder holder = GenerationScheduler.getInstance().getInFlight(key);
            // Only a fully published pipeline chunk may satisfy a holder future.
            // A partial proto/placeholder returned by the non-blocking provideChunk
            // path must never be promoted to FULL here.
            if (holder != null && !holder.isDone()
                    && chunk instanceof com.cleanroommc.worldflow.pipeline.IWorldFlowChunk
                    && ((com.cleanroommc.worldflow.pipeline.IWorldFlowChunk) chunk).worldflow$isPublished()) {
                holder.getProtoChunk().setUnderlyingChunk(chunk);
                holder.getProtoChunk().setTerrainPopulated(true);
                holder.setCurrentStatus(com.cleanroommc.worldflow.pipeline.ChunkStatus.FULL);
                holder.setOwnDecorationDone(true);
                holder.getCarversFuture().complete(chunk);
                holder.getWorkerDoneFuture().complete(chunk);
                holder.getFuture().complete(chunk);
                GenerationScheduler.getInstance().removeInFlight(key);
                GenerationScheduler.getInstance().notifyNeighboursOfCompletion(x, z);
            }
            if (chunk instanceof com.cleanroommc.worldflow.pipeline.IWorldFlowChunk) {
                com.cleanroommc.worldflow.pipeline.IWorldFlowChunk wfChunk = (com.cleanroommc.worldflow.pipeline.IWorldFlowChunk) chunk;
                if (wfChunk.worldflow$getGenPath() == com.cleanroommc.worldflow.pipeline.IWorldFlowChunk.GenPath.UNKNOWN) {
                    wfChunk.worldflow$setGenPath(com.cleanroommc.worldflow.pipeline.IWorldFlowChunk.GenPath.INLINE);
                    GenerationScheduler.getInstance().totalChunksInline.incrementAndGet();
                }
            }
        }
    }
}
