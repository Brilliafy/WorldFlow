package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.WorldFlow;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTracker;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityTracker.class)
public abstract class EntityTrackerMixin {

    @Shadow
    @Final
    private WorldServer world;

    @Inject(method = "track(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void worldflow$guardTrack1(Entity entityIn, CallbackInfo ci) {
        if (this.world != null && this.world.getMinecraftServer() != null && !this.world.getMinecraftServer().isCallingFromMinecraftThread()) {
            WorldFlow.LOGGER.warn("[WorldFlow] Illegal off-thread EntityTracker.track call for entity {} on thread {}!",
                    entityIn != null ? entityIn.getName() : "null", Thread.currentThread().getName());
            ci.cancel();
        }
    }

    @Inject(method = "track(Lnet/minecraft/entity/Entity;IIZ)V", at = @At("HEAD"), cancellable = true)
    private void worldflow$guardTrack2(Entity entityIn, int trackingRange, int updateFrequency, boolean sendVelocityUpdates, CallbackInfo ci) {
        if (this.world != null && this.world.getMinecraftServer() != null && !this.world.getMinecraftServer().isCallingFromMinecraftThread()) {
            WorldFlow.LOGGER.warn("[WorldFlow] Illegal off-thread EntityTracker.track call for entity {} on thread {}!",
                    entityIn != null ? entityIn.getName() : "null", Thread.currentThread().getName());
            ci.cancel();
        }
    }

    @Inject(method = "untrack", at = @At("HEAD"), cancellable = true)
    private void worldflow$guardUntrack(Entity entityIn, CallbackInfo ci) {
        if (this.world != null && this.world.getMinecraftServer() != null && !this.world.getMinecraftServer().isCallingFromMinecraftThread()) {
            WorldFlow.LOGGER.warn("[WorldFlow] Illegal off-thread EntityTracker.untrack call for entity {} on thread {}!",
                    entityIn != null ? entityIn.getName() : "null", Thread.currentThread().getName());
            this.world.getMinecraftServer().addScheduledTask(() -> ((EntityTracker) (Object) this).untrack(entityIn));
            ci.cancel();
        }
    }
}
