package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.customobjects.structures.bo4.BO4CustomStructure;
import com.pg85.otg.util.ChunkCoordinate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "com.pg85.otg.customobjects.structures.bo4.CustomStructurePlotter", remap = false)
public interface CustomStructurePlotterAccessor {

    @Invoker("getFromStructureCache")
    BO4CustomStructure worldflow$getFromCache(ChunkCoordinate coord);

    @Invoker("removeFromStructureCache")
    void worldflow$removeFromCache(ChunkCoordinate coord);

    @Invoker("setChunkOccupied")
    void worldflow$setOccupied(ChunkCoordinate coord);
}
