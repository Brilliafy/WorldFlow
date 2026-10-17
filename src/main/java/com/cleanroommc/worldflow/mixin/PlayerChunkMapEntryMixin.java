package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.config.ModConfig;
import com.cleanroommc.worldflow.pipeline.ChunkStatus;
import com.cleanroommc.worldflow.pipeline.GenerationPriority;
import com.cleanroommc.worldflow.pipeline.GenerationScheduler;
import com.cleanroommc.worldflow.pipeline.IPlayerChunkMapEntry;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerChunkMapEntry.class)
public abstract class PlayerChunkMapEntryMixin implements IPlayerChunkMapEntry {

    @Shadow
    @Final
    private PlayerChunkMap playerChunkMap;

    @Shadow
    @Final
    private ChunkPos pos;

    @Shadow
    private Chunk chunk;

    @Override
    public void worldflow$setChunk(Chunk chunk) {
        this.chunk = chunk;
    }

    @Inject(method = "providePlayerChunk(Z)Z", at = @At("HEAD"), cancellable = true)
    private void worldflow$onProvidePlayerChunk(boolean canGenerate, CallbackInfoReturnable<Boolean> cir) {
        if (!ModConfig.CONCURRENCY.enableParallelGeneration) {
            return;
        }

        if (this.chunk != null) {
            cir.setReturnValue(true);
            return;
        }

        WorldServer world = this.playerChunkMap.getWorldServer();

        // 1. If chunk is already loaded in ChunkProviderServer, capture it immediately
        Chunk loaded = world.getChunkProvider().getLoadedChunk(this.pos.x, this.pos.z);
        if (loaded != null) {
            this.chunk = loaded;
            cir.setReturnValue(true);
            return;
        }

        if (!canGenerate) {
            cir.setReturnValue(false);
            return;
        }

        // 2. Request asynchronous generation from the staged pipeline with PLAYER priority
        GenerationScheduler.getInstance().requestChunk(
                world,
                this.pos.x,
                this.pos.z,
                ChunkStatus.FULL,
                GenerationPriority.PLAYER
        );

        // 3. Check again if it was published concurrently
        loaded = world.getChunkProvider().getLoadedChunk(this.pos.x, this.pos.z);
        if (loaded != null) {
            this.chunk = loaded;
            cir.setReturnValue(true);
            return;
        }

        // 4. Leave chunk null and return false: entry remains pending without blocking server tick
        cir.setReturnValue(false);
    }
}
