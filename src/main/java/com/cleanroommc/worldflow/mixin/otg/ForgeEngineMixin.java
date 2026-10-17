package com.cleanroommc.worldflow.mixin.otg;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.pipeline.otg.OTGParallelContext;
import com.pg85.otg.forge.ForgeEngine;
import com.pg85.otg.forge.world.ForgeWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.TimeUnit;

/**
 * Intercepts ForgeEngine.onSave with a non-blocking tryLock and try/finally block,
 * ensuring the server tick thread never freezes during autosave and the lock is never leaked.
 */
@Mixin(value = ForgeEngine.class, remap = false)
public abstract class ForgeEngineMixin {

    @Shadow
    public abstract ForgeWorld getWorld(World world);

    @Inject(method = "onSave", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$onSaveNonBlocking(World world, CallbackInfo ci) {
        ci.cancel();

        ForgeWorld forgeWorld = this.getWorld(world);
        if (forgeWorld == null || !forgeWorld.getObjectSpawner().saveRequired || forgeWorld.getWorldSession().getPreGeneratorIsRunning()) {
            return;
        }

        // Try to acquire all structure locks with a short 50ms timeout so the main tick thread never stalls
        boolean acquired = false;
        try {
            acquired = OTGParallelContext.tryLockAll(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (acquired) {
            try {
                forgeWorld.getStructureCache().saveToDisk();
            } catch (Throwable t) {
                WorldFlow.LOGGER.error("[WorldFlow] Error saving OTG structure cache to disk", t);
            } finally {
                OTGParallelContext.unlockAll();
            }
        } else {
            // Gen-lever thread is currently populating structures.
            // Defer save to next autosave or quiesce/shutdown to avoid multi-second tick stalls.
            WorldFlow.LOGGER.debug("[WorldFlow] Deferring OTG saveToDisk: gen-lever thread is actively populating structures");
        }
    }
}
