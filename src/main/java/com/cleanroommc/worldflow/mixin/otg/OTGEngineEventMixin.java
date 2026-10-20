package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.common.LocalWorld;
import com.pg85.otg.events.EventHandler;
import com.pg85.otg.generator.resource.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * OTG fires ResourceProcessEvent for every resource on every worker. Mod listeners
 * (e.g. Charm's VillageDecorations) keep plain HashMaps and throw CMEs when two
 * chunks fire concurrently. Serialize per listener instance: the same unsafe
 * listener never runs concurrently, while unrelated mods still run in parallel.
 */
@Mixin(targets = "com.pg85.otg.OTGEngine", remap = false)
public abstract class OTGEngineEventMixin {

    private static final ConcurrentHashMap<EventHandler, ReentrantLock> WORLD_FLOW_HANDLER_LOCKS = new ConcurrentHashMap<>();

    @Shadow
    private List<EventHandler> cancelableEventHandlers;
    @Shadow
    private List<EventHandler> monitoringEventHandlers;

    private static boolean callLocked(EventHandler handler, Resource resource, LocalWorld world, Random random,
                                      boolean villageInChunk, int chunkX, int chunkZ, boolean cancelled) {
        ReentrantLock lock = WORLD_FLOW_HANDLER_LOCKS.computeIfAbsent(handler, k -> new ReentrantLock());
        lock.lock();
        try {
            return handler.onResourceProcess(resource, world, random, villageInChunk, chunkX, chunkZ, cancelled);
        } finally {
            lock.unlock();
        }
    }

    @Overwrite(remap = false)
    public boolean fireResourceProcessEvent(Resource resource, LocalWorld world, Random random,
                                            boolean villageInChunk, int chunkX, int chunkZ) {
        boolean success = true;
        for (EventHandler handler : this.cancelableEventHandlers) {
            if (callLocked(handler, resource, world, random, villageInChunk, chunkX, chunkZ, !success)) continue;
            success = false;
        }
        for (EventHandler handler : this.monitoringEventHandlers) {
            callLocked(handler, resource, world, random, villageInChunk, chunkX, chunkZ, !success);
        }
        return success;
    }
}
