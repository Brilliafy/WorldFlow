package com.cleanroommc.worldflow.mixin.otg;

import com.cleanroommc.worldflow.WorldFlow;
import com.pg85.otg.forge.ForgeEngine;
import com.pg85.otg.forge.world.ForgeWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * Intercepts ForgeEngine.onSave so structure data is never saved while a feature
 * worker is populating. No structure monitor is taken.
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

        if (com.cleanroommc.worldflow.pipeline.GenerationScheduler.getInstance().getInFlightCount() > 0) {
            // Workers may still be plotting; defer to the next quiesced save.
            WorldFlow.LOGGER.debug("[WorldFlow] Deferring OTG saveToDisk: generation is in flight");
            return;
        }
        try {
            forgeWorld.getStructureCache().saveToDisk();
        } catch (Throwable t) {
            WorldFlow.LOGGER.error("[WorldFlow] Error saving OTG structure cache to disk", t);
        }
    }
}
