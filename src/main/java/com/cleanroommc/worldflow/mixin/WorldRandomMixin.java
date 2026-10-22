package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.pipeline.util.ThreadLocalWorldRandom;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Random;

/** Replace the single World.rand with a per-thread Random delegate. */
@Mixin(World.class)
public abstract class WorldRandomMixin {

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "java/util/Random"))
    private static Random worldflow$threadLocalWorldRandom() {
        return new ThreadLocalWorldRandom();
    }
}
