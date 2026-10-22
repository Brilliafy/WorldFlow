package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.customobjects.structures.bo4.CustomStructurePlotter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = CustomStructurePlotter.class, remap = false)
public abstract class CustomStructurePlotterMixin {

    @Redirect(method = "plotStructures(Lcom/pg85/otg/customobjects/structures/CustomStructureCache;Lcom/pg85/otg/customobjects/bo4/BO4;Ljava/util/ArrayList;Lcom/pg85/otg/common/LocalWorld;Ljava/util/Random;Lcom/pg85/otg/util/ChunkCoordinate;Z)Lcom/pg85/otg/customobjects/structures/bo4/BO4CustomStructure;",
              at = @At(value = "INVOKE", target = "Ljava/lang/Thread;sleep(J)V"),
              require = 0,
              remap = false)
    private void worldflow$onPlotterSleep(long millis) {
        // Never sleep 20ms in plotStructures! Yield execution instead to eliminate 20ms spin-wait.
        Thread.yield();
    }
}
