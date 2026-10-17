package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.pipeline.PublishManager;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldServer.class)
public abstract class WorldServerMixin {

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void worldflow$onWorldTick(CallbackInfo ci) {
        WorldServer world = (WorldServer) (Object) this;
        // Process chunks completed by parallel worker pipeline and publish to live world
        PublishManager.INSTANCE.tick(world, 32);
        com.cleanroommc.worldflow.pipeline.GenerationScheduler.getInstance().checkLogMetrics();
    }
}
