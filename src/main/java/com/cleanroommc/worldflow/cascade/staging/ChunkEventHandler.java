package com.cleanroommc.worldflow.cascade.staging;

import com.cleanroommc.worldflow.config.ModConfig;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class ChunkEventHandler {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPopulatePre(PopulateChunkEvent.Pre event) {
        if (ModConfig.GENERAL.enableCascadeFix && ModConfig.GENERAL.stageForgeDecorators) {
            GenerationContext.enterPopulate(event.getChunkX(), event.getChunkZ(), "ForgeDecorator");
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPopulatePost(PopulateChunkEvent.Post event) {
        if (ModConfig.GENERAL.enableCascadeFix && ModConfig.GENERAL.stageForgeDecorators) {
            GenerationContext.exitPopulate();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getWorld().isRemote || !(event.getWorld() instanceof WorldServer)) {
            return;
        }

        WorldServer worldServer = (WorldServer) event.getWorld();
        Chunk chunk = event.getChunk();
        CascadeBlockBuffer.INSTANCE.flushChunk(worldServer, chunk);
    }

    @SubscribeEvent
    public static void onWorldSave(WorldEvent.Save event) {
        if (event.getWorld().isRemote || !(event.getWorld() instanceof WorldServer)) {
            return;
        }

        if (ModConfig.GENERAL.persistStagedBlocks) {
            CascadeSavedData.get(event.getWorld()).markDirty();
        }
    }
}
