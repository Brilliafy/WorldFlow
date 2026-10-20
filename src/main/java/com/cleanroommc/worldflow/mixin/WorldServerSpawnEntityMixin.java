package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer;
import com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView;
import net.minecraft.entity.Entity;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feature workers must not add entities to World.loadedEntityList / the entity
 * tracker. Other mods (Aether) iterate those plain ArrayLists on the server tick
 * and crash with ConcurrentModificationException. Stage worker spawns and let the
 * publish/load flush spawn them on the server thread.
 */
@Mixin(WorldServer.class)
public abstract class WorldServerSpawnEntityMixin {

    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void worldflow$stageEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (ThreadLocalWorldView.isGenerating()) {
            CascadeBlockBuffer.INSTANCE.stageEntity((WorldServer) (Object) this, entity);
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
