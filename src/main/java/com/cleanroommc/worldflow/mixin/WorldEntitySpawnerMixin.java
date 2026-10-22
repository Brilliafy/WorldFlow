package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.pipeline.GenerationScheduler;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldEntitySpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suspend the periodic mob spawner while a bulk generation batch has chunks
 * in flight. performWorldGenSpawning is deliberately untouched: passive
 * animals created during population must still be staged and committed.
 */
@Mixin(WorldEntitySpawner.class)
public abstract class WorldEntitySpawnerMixin {

    @Inject(method = "findChunksForSpawning", at = @At("HEAD"), cancellable = true)
    private void worldflow$skipTickSpawnerDuringBatch(WorldServer world,
                                                      boolean hostile,
                                                      boolean peaceful,
                                                      boolean onSetTickRate,
                                                      CallbackInfoReturnable<Integer> cir) {
        if (GenerationScheduler.getInstance().getInFlightCount() > 0) {
            cir.setReturnValue(0);
        }
    }
}
