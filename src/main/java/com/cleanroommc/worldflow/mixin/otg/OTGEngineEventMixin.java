package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.common.LocalWorld;
import com.pg85.otg.events.EventHandler;
import com.pg85.otg.generator.resource.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Random;

/**
 * OTG fires ResourceProcessEvent for every resource on every worker. Keep the
 * event ordering but dispatch directly; the previous per-listener lock convoys
 * every feature worker on the same listener.
 */
@Mixin(targets = "com.pg85.otg.OTGEngine", remap = false)
public abstract class OTGEngineEventMixin {

    @Shadow
    private List<EventHandler> cancelableEventHandlers;

    @Shadow
    private List<EventHandler> monitoringEventHandlers;

    @Overwrite(remap = false)
    public boolean fireResourceProcessEvent(Resource resource, LocalWorld world, Random random,
                                            boolean villageInChunk, int chunkX, int chunkZ) {
        boolean success = true;
        for (EventHandler handler : this.cancelableEventHandlers) {
            if (handler.onResourceProcess(resource, world, random, villageInChunk, chunkX, chunkZ, !success)) continue;
            success = false;
        }
        for (EventHandler handler : this.monitoringEventHandlers) {
            handler.onResourceProcess(resource, world, random, villageInChunk, chunkX, chunkZ, !success);
        }
        return success;
    }
}
