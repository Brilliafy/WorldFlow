package com.cleanroommc.worldflow.mixin.rltweaker;

import com.charles445.rltweaker.reflect.RecurrentReflect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.charles445.rltweaker.handler.RecurrentHandler", remap = false)
public interface RecurrentHandlerAccessor {

    @Accessor("reflector")
    RecurrentReflect worldflow$getReflector();
}
