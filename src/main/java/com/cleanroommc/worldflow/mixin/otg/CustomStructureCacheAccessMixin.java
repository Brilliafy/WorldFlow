package com.cleanroommc.worldflow.mixin.otg;

import com.cleanroommc.worldflow.pipeline.otg.WorldFlowStructureCacheAccess;
import com.pg85.otg.common.LocalWorld;
import com.pg85.otg.customobjects.structures.bo4.CustomStructurePlotter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "com.pg85.otg.customobjects.structures.CustomStructureCache", remap = false)
public abstract class CustomStructureCacheAccessMixin implements WorldFlowStructureCacheAccess {

    @Shadow
    private CustomStructurePlotter plotter;
    @Shadow
    private LocalWorld world;

    @Override
    public CustomStructurePlotter worldflow$plotter() {
        return this.plotter;
    }

    @Override
    public LocalWorld worldflow$world() {
        return this.world;
    }
}
