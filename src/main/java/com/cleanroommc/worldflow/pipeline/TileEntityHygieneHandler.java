package com.cleanroommc.worldflow.pipeline;

import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Runs at the very start of each world tick, before mods' START-phase handlers.
 *
 * <p>Nuclearcraft scans World.loadedTileEntityList from onPreWorldTick, which is the same
 * WorldTickEvent.START. Draining pending tile-entity removals here guarantees stale entries
 * are gone before that scan.</p>
 */
public final class TileEntityHygieneHandler {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.START && event.world instanceof WorldServer) {
            PublishManager.INSTANCE.maintainTileEntities((WorldServer) event.world);
        }
    }
}
