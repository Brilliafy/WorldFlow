package com.cleanroommc.worldflow;

import com.cleanroommc.worldflow.cascade.staging.ChunkEventHandler;
import com.cleanroommc.worldflow.command.CommandWorldFlow;
import com.cleanroommc.worldflow.pregen.PregeneratorEngine;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = WorldFlow.MOD_ID,
        name = WorldFlow.MOD_NAME,
        version = WorldFlow.VERSION,
        acceptableRemoteVersions = "*"
)
public class WorldFlow {

    public static final String MOD_ID = "worldflow";
    public static final String MOD_NAME = "World Flow";
    public static final String VERSION = "1.0.0";

    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    @Mod.Instance(MOD_ID)
    public static WorldFlow instance;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("World Flow initialized. Protecting world generation from recursive chunk cascades.");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new PregeneratorEngine());
        MinecraftForge.EVENT_BUS.register(ChunkEventHandler.class);
        if (com.cleanroommc.worldflow.config.ModConfig.CONCURRENCY != null && com.cleanroommc.worldflow.config.ModConfig.CONCURRENCY.enableParallelGeneration) {
            com.cleanroommc.worldflow.pipeline.GenerationWorkerPool.getInstance().init();
            LOGGER.info("[WorldFlow] True parallel staged generation pipeline ACTIVE ({} worker threads).",
                    com.cleanroommc.worldflow.pipeline.GenerationWorkerPool.effectiveThreads());
        } else {
            LOGGER.info("[WorldFlow] True parallel staged generation pipeline DISABLED (vanilla inline synchronous chunk generation).");
        }
        LOGGER.info("[WorldFlow] Vectorized SIMD-friendly noise calculation engine initialized.");
        LOGGER.info("[WorldFlow] Gentle pre-generator initialised.");
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandWorldFlow());
    }

    @Mod.EventHandler
    public void serverStopping(net.minecraftforge.fml.common.event.FMLServerStoppingEvent event) {
        LOGGER.info("[WorldFlow] Shutting down parallel generation worker pools.");
        com.cleanroommc.worldflow.pipeline.GenerationWorkerPool.getInstance().shutdown();
    }
}
