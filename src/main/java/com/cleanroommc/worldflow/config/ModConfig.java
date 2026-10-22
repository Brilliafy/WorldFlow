package com.cleanroommc.worldflow.config;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = "worldflow", name = "worldflow")
public class ModConfig {

    @Config.Name("Noise")
    @Config.Comment("Vectorized terrain noise settings")
    public static Noise NOISE = new Noise();

    @Config.Name("Cascade Fix")
    @Config.Comment("Core settings for worldgen chunk cascade prevention")
    public static GeneralCategory GENERAL = new GeneralCategory();

    @Config.Name("Pregenerator")
    @Config.Comment("Gentle budgeted background pre-generation settings")
    public static Pregenerator PREGENERATOR = new Pregenerator();

    @Config.Name("Concurrency")
    @Config.Comment("True parallel chunk generation pipeline settings")
    public static Concurrency CONCURRENCY = new Concurrency();

    public static class Concurrency {

        @Config.Comment("Master toggle for parallel chunk generation pipeline.")
        @Config.Name("Enable Parallel Generation")
        public boolean enableParallelGeneration = false;

        @Config.Comment("Number of parallel worker threads for chunk generation (0 = auto: CPU cores - 1).")
        @Config.RangeInt(min = 0, max = 256)
        @Config.Name("Parallel Worker Threads")
        public int parallelWorkerThreads = 0;

        @Config.Comment("Maximum concurrent chunks in-flight across all generation pipeline stages.")
        @Config.RangeInt(min = 8, max = 512)
        @Config.Name("Max In-Flight Generations")
        public int maxInFlightGenerations = 96;

        @Config.Comment("Force falling blocks (sand, gravel) to fall instantly during off-thread generation instead of spawning EntityFallingBlock.")
        @Config.Name("Force Instant Falling During Generation")
        public boolean forceInstantFallingDuringGen = true;

        @Config.Comment("Enable thread-local generation-world isolation barrier.")
        @Config.Name("Enable ThreadLocal Isolation")
        public boolean enableThreadLocalWorldView = true;

        @Config.Comment("Skip chunk.checkLight() during publish (fast pregen; chunks relight when loaded by a player).")
        @Config.Name("Skip Publish Lighting")
        public boolean skipPublishLighting = false;

        @Config.Comment("Fire Forge PopulateChunkEvent.Post / ChunkEvent.Load for pipeline chunks. Disable only for measurement.")
        @Config.Name("Fire Chunk Events On Publish")
        public boolean fireChunkEventsOnPublish = true;

        @Config.Comment("Enable concurrent OTG/Dregora terrain generation via per-worker ChunkProviderOTG.")
        @Config.Name("Enable Concurrent OTG Terrain")
        public boolean enableOTGParallelTerrain = true;

        @Config.Comment("List of class or package substrings to treat as PARALLEL_SAFE world generators.")
        @Config.Name("Parallel Safe Generators")
        public String[] parallelSafeGenerators = new String[] {
                "net.minecraft.",
                "cofh.",
                "forestry.",
                "biomesoplenty."
        };

        @Config.Comment("List of class or package substrings to serialize through the off-tick gen-lever thread.")
        @Config.Name("Serialized Generators")
        public String[] serializedGenerators = new String[] {
                "com.pg85.otg.",
                "ivorius.reccomplex.",
                "battletowers.",
                "dungeonmobs.",
                "greymerk.roguelike.",
                "com.github.alexthe666.iceandfire.",
                "com.bobmowzie.mowziesmobs."
        };

        @Config.Comment("List of class or package substrings strictly unsafe for off-thread execution (deferred to main tick thread).")
        @Config.Name("Unsafe Generators")
        public String[] unsafeGenerators = new String[] {};

        @Config.Comment("Serialize every IWorldGenerator that is not explicitly whitelisted as parallel-safe. " +
                "Safety-first default: unknown mods may keep shared maps or global RNG state.")
        @Config.Name("Serialize Unknown Generators")
        public boolean serializeUnknownGenerators = true;
    }

    public static class Noise {

        @Config.Comment("Master toggle to enable/disable vectorized noise calculation engine.")
        @Config.Name("Enable Mod")
        public boolean enabled = true;

        @Config.Comment("Use branchless fast gradient evaluations.")
        @Config.Name("Branchless Gradients")
        public boolean enableBranchlessGradients = true;

        @Config.Comment("Enable debug console logging.")
        @Config.Name("Debug Logging")
        public boolean debugLogging = false;
    }

    public static class GeneralCategory {
        @Config.Comment("Enable the chunk cascade prevention engine. Setting to false turns off all block staging.")
        public boolean enableCascadeFix = true;

        @Config.Comment("Stage cross-chunk block placements from OpenTerrainGenerator (OTG / Dregora). Eliminates Dregora structure cascades.")
        public boolean stageOTGBlocks = true;

        @Config.Comment("Stage cross-chunk block placements from general Forge decorators (vanilla & other mods) during population.")
        public boolean stageForgeDecorators = true;

        @Config.Comment("Log each prevented cascade attempt with chunk coordinates and mod source to the console (useful for debugging).")
        public boolean logPreventedCascades = false;

        @Config.Comment("Maximum number of staged blocks permitted per chunk to prevent infinite memory growth from broken generators.")
        @Config.RangeInt(min = 256, max = 65536)
        public int maxStagedBlocksPerChunk = 16384;

        @Config.Comment("Persist staged blocks across game restarts into world/data/cascade_staging.dat.")
        public boolean persistStagedBlocks = true;
    }

    public static class Pregenerator {

        @Config.Comment("Master toggle for background pre-generation.")
        @Config.Name("Enable Mod")
        public boolean enabled = true;

        @Config.Comment("Outer radius (in chunks) around each player to pre-generate.")
        @Config.RangeInt(min = 1, max = 512)
        @Config.Name("Radius (chunks)")
        public int radiusChunks = 32;

        @Config.Comment("Inner radius (in chunks) that is NEVER pre-generated, so pre-generation never competes with the players' own active chunk area. Keep this at or above your server view distance.")
        @Config.RangeInt(min = 0, max = 512)
        @Config.Name("Inner Radius (chunks)")
        public int innerRadiusChunks = 12;

        @Config.Comment("Minimum number of server ticks between each pre-generated chunk. Higher = slower and gentler.")
        @Config.RangeInt(min = 1, max = 2400)
        @Config.Name("Interval (ticks per chunk)")
        public int intervalTicks = 40;

        @Config.Comment("Maximum candidate chunks inspected per work tick (cheap in-memory checks).")
        @Config.RangeInt(min = 1, max = 4096)
        @Config.Name("Max Candidate Checks Per Tick")
        public int maxChecksPerTick = 64;

        @Config.Comment("If the previous server tick took longer than this (ms), skip pre-generation until the server is healthy again.")
        @Config.RangeDouble(min = 5.0D, max = 200.0D)
        @Config.Name("Max Tick Time (ms)")
        public double maxTickTimeMs = 45.0D;

        @Config.Comment("Only pre-generate while no player has moved for a while (recommended).")
        @Config.Name("Only When Players Settled")
        public boolean onlyWhenIdle = true;

        @Config.Comment("Consecutive ticks without player movement required before working.")
        @Config.RangeInt(min = 0, max = 12000)
        @Config.Name("Idle Ticks Required")
        public int idleTicks = 100;

        @Config.Comment("Per-tick player movement (blocks) above which a player counts as 'moving'.")
        @Config.RangeDouble(min = 0.0D, max = 1.0D)
        @Config.Name("Idle Movement Threshold")
        public double idleMovementThreshold = 0.01D;

        @Config.Comment("Ticks to rest after scanning a full ring without finding work (players may have moved).")
        @Config.RangeInt(min = 0, max = 72000)
        @Config.Name("Empty Pass Cooldown (ticks)")
        public int emptyPassCooldownTicks = 600;

        @Config.Comment("Only pre-generate the overworld (dimension 0).")
        @Config.Name("Overworld Only")
        public boolean overworldOnly = true;

        @Config.Comment("Log each pre-generated chunk and ring rebuilds.")
        @Config.Name("Debug Logging")
        public boolean debugLogging = false;
    }

    @Mod.EventBusSubscriber(modid = "worldflow")
    public static class ConfigSyncHandler {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (event.getModID().equals("worldflow")) {
                ConfigManager.sync("worldflow", Config.Type.INSTANCE);
            }
        }
    }
}
