package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.pipeline.structure.StructureNbtStager;
import net.minecraft.world.gen.structure.MapGenStructure;
import net.minecraft.world.gen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Defer the structure NBT write from the feature worker to the Stage 4 tick thread.
 * The redirect keeps AA's own injection into MapGenStructure.generateStructure intact.
 */
@Mixin(MapGenStructure.class)
public abstract class MapGenStructureNbtStagingMixin {

    @Redirect(
            method = "generateStructure",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/structure/MapGenStructure;setStructureStart(IILnet/minecraft/world/gen/structure/StructureStart;)V"
            ))
    private void worldflow$deferStructureNbt(MapGenStructure self, int chunkX, int chunkZ, StructureStart start) {
        StructureNbtStager.stage(((MapGenStructureAccessor) self).worldflow$getStructureData(), start, chunkX, chunkZ);
    }
}
