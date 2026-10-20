package com.cleanroommc.worldflow.mixin.otg;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView;
import com.pg85.otg.forge.world.ForgeWorld;
import net.minecraft.world.World;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * ForgeWorld.world is a plain public field set by provideWorldInstance. A worker can
 * observe it as null (uninitialised world instance / visibility), and placeTree then
 * calls World.getBlockState on null, NPEs, and skips the tree.
 *
 * <p>The worker already has the correct world in its pipeline context. Return that
 * world for the duration of the tree call; WorldMixin redirects block access back to
 * the thread-local proto, so isolation is preserved.</p>
 */
@Mixin(value = ForgeWorld.class, remap = false)
public abstract class ForgeWorldNullGuardMixin {

    @Redirect(method = "placeTree",
              at = @At(value = "FIELD",
                       target = "Lcom/pg85/otg/forge/world/ForgeWorld;world:Lnet/minecraft/world/World;",
                       opcode = Opcodes.GETFIELD),
              remap = false)
    private World worldflow$placeTreeWorld(ForgeWorld self) {
        World world = self.world;
        if (world == null) {
            World fallback = ThreadLocalWorldView.getCurrentWorld();
            if (fallback != null) {
                WorldFlow.LOGGER.warn("[WorldFlow] ForgeWorld.placeTree saw null world on {}; using worker world {} (dim {})",
                        Thread.currentThread().getName(), fallback.getClass().getSimpleName(),
                        fallback.provider != null ? fallback.provider.getDimension() : -999);
                return fallback;
            }
        }
        return world;
    }
}
