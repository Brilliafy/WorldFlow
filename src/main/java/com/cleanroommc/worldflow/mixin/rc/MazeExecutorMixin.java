package com.cleanroommc.worldflow.mixin.rc;

import com.cleanroommc.worldflow.pipeline.rc.MazePool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.ExecutorService;

/**
 * RecurrentComplex allocates a single-thread executor for every maze and shuts
 * it down immediately. Reuse one long-lived pool instead.
 */
@Mixin(targets = "ivorius.reccomplex.world.gen.script.WorldScriptMazeGenerator", remap = false)
public class MazeExecutorMixin {

    @Redirect(
            method = "getPlacedRooms",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/concurrent/Executors;newSingleThreadExecutor()Ljava/util/concurrent/ExecutorService;"),
            remap = false)
    private ExecutorService worldflow$sharedMazeExecutor() {
        return MazePool.EXECUTOR;
    }

    @Redirect(
            method = "getPlacedRooms",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/concurrent/ExecutorService;shutdown()V"),
            remap = false)
    private void worldflow$doNotShutdownSharedPool(ExecutorService executor) {
        // shared pool must outlive this maze
    }
}
