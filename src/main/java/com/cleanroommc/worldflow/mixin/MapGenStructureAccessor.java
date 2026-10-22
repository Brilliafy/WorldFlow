package com.cleanroommc.worldflow.mixin;

import net.minecraft.world.gen.structure.MapGenStructure;
import net.minecraft.world.gen.structure.MapGenStructureData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MapGenStructure.class)
public interface MapGenStructureAccessor {
    @Accessor("structureData")
    MapGenStructureData worldflow$getStructureData();
}
