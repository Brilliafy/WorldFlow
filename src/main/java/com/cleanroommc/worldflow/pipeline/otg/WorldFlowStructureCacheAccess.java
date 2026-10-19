package com.cleanroommc.worldflow.pipeline.otg;

import com.pg85.otg.common.LocalWorld;
import com.pg85.otg.customobjects.structures.bo4.CustomStructurePlotter;

/** Read-only access to the structure cache's plotter and world for split spawning. */
public interface WorldFlowStructureCacheAccess {
    CustomStructurePlotter worldflow$plotter();

    LocalWorld worldflow$world();
}
