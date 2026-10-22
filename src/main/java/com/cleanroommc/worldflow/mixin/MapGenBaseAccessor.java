package com.cleanroommc.worldflow.mixin;

import net.minecraft.world.gen.MapGenBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Random;

@Mixin(MapGenBase.class)
public interface MapGenBaseAccessor {
    @Accessor("rand")
    Random worldflow$getRand();
}
