package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.pipeline.GenerationScheduler;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the world-generation quiesce window for the whole save, not just the
 * {@code WorldEvent.Save} dispatch. Mods such as Recurrent Complex read and write
 * {@code MapStorage} from generator threads; if a worker decorates while the server
 * thread saves, the vanilla HashMap is corrupted (ConcurrentModificationException).
 */
@Mixin(WorldServer.class)
public abstract class WorldServerSaveMixin {

    @Inject(method = "saveAll", at = @At("HEAD"))
    private void worldflow$beforeSaveAll(boolean silent, IProgressUpdate progress, CallbackInfo ci) {
        WorldServer world = (WorldServer) (Object) this;
        GenerationScheduler.getInstance().beginQuiesce(300_000L);
        GenerationScheduler.getInstance().quiesceAndDrain(world, 60_000L);
    }

    @Inject(method = "saveAll", at = @At("RETURN"))
    private void worldflow$afterSaveAll(boolean silent, IProgressUpdate progress, CallbackInfo ci) {
        GenerationScheduler.getInstance().endQuiesce();
    }
}
