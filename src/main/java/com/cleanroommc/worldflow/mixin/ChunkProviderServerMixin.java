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

        // 3. Check if chunk is completed in the parallel pipeline
        long key = ChunkPos.asLong(x, z);
        StatusHolder holder = GenerationScheduler.getInstance().getInFlight(key);
        if (holder != null && holder.getProtoChunk() != null && holder.getProtoChunk().getChunk() != null) {
            PublishManager.INSTANCE.publishChunk(this.world, holder);
            cir.setReturnValue(holder.getProtoChunk().getChunk());
            return;
        }

        // 4. Await or generate via parallel pipeline (Pregen / Sync caller fallback)
        Chunk chunk = GenerationScheduler.getInstance().awaitOrGenerateSync(this.world, x, z, 200L);
        if (chunk != null) {
            cir.setReturnValue(chunk);
        }
    }
}
