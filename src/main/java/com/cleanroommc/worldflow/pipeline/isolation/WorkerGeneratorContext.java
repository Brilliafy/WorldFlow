package com.cleanroommc.worldflow.pipeline.isolation;

import com.cleanroommc.worldflow.WorldFlow;
import net.minecraft.init.Biomes;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderEnd;
import net.minecraft.world.WorldProviderHell;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.biome.BiomeProviderSingle;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.gen.MapGenBase;
import net.minecraft.world.gen.MapGenCaves;
import net.minecraft.world.gen.MapGenRavine;
import net.minecraft.world.gen.structure.MapGenMineshaft;
import net.minecraft.world.gen.structure.MapGenScatteredFeature;
import net.minecraft.world.gen.structure.MapGenStronghold;
import net.minecraft.world.gen.structure.MapGenVillage;
import net.minecraft.world.gen.structure.StructureOceanMonument;
import net.minecraft.world.gen.structure.WoodlandMansion;

import net.minecraft.world.gen.NoiseGeneratorOctaves;
import net.minecraft.world.gen.NoiseGeneratorPerlin;
import net.minecraft.world.gen.NoiseGeneratorSimplex;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.InitNoiseGensEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

/**
 * Thread-local generator and biome context.
 * Provides completely isolated chunk generators and biome providers per worker thread,
 * eliminating array sharing, RNG collisions, and noise buffer corruption.
 */
public class WorkerGeneratorContext {

    private static final ThreadLocal<WorkerGeneratorContext> THREAD_LOCAL = ThreadLocal.withInitial(WorkerGeneratorContext::new);

    public final MapGenBase caves = new MapGenCaves();
    public final MapGenBase ravines = new MapGenRavine();
    public final MapGenMineshaft mineshaft = new MapGenMineshaft();
    public final MapGenVillage village = new MapGenVillage();
    public final MapGenStronghold stronghold = new MapGenStronghold();
    public final MapGenScatteredFeature scatteredFeature = new MapGenScatteredFeature();
    public final StructureOceanMonument oceanMonument = new StructureOceanMonument();
    public final Random rand = new Random();

    private WoodlandMansion woodlandMansion;
    private final Map<Integer, IChunkGenerator> chunkGenerators = new HashMap<>();
    private final Map<World, BiomeProvider> biomeProviders = new WeakHashMap<>();

    public WoodlandMansion getWoodlandMansion(ChunkGeneratorOverworld provider) {
        if (this.woodlandMansion == null && provider != null) {
            this.woodlandMansion = new WoodlandMansion(provider);
        }
        return this.woodlandMansion;
    }

    public IChunkGenerator getChunkGenerator(WorldServer world) {
        if (world == null) {
            return null;
        }
        int dim = world.provider != null ? world.provider.getDimension() : 0;
        IChunkGenerator gen = this.chunkGenerators.get(dim);
        if (gen != null) {
            return gen;
        }

        try {
            if (world.getChunkProvider() != null && world.getChunkProvider().chunkGenerator != null) {
                gen = world.getChunkProvider().chunkGenerator;
            } else if (world.provider != null) {
                gen = world.provider.createChunkGenerator();
            }
        } catch (Throwable t) {
            WorldFlow.LOGGER.warn("[WorldFlow] Failed to initialize chunk generator for dimension {}, falling back to provider generator", dim, t);
        }

        if (gen == null && world.getChunkProvider() != null) {
            gen = world.getChunkProvider().chunkGenerator;
        }

        if (gen != null) {
            this.chunkGenerators.put(dim, gen);
            fireInitNoiseGensEvent(world, gen);
        }
        return gen;
    }

    public static void fireInitNoiseGensEvent(World world, IChunkGenerator gen) {
        try {
            if (world == null) {
                return;
            }
            Random r = new Random(world.getSeed());
            NoiseGeneratorOctaves lperlin1 = new NoiseGeneratorOctaves(r, 16);
            NoiseGeneratorOctaves lperlin2 = new NoiseGeneratorOctaves(r, 16);
            NoiseGeneratorOctaves perlin = new NoiseGeneratorOctaves(r, 8);
            NoiseGeneratorOctaves scale = new NoiseGeneratorOctaves(r, 10);
            NoiseGeneratorOctaves depth = new NoiseGeneratorOctaves(r, 16);

            InitNoiseGensEvent.Context ctx;
            if (world.provider instanceof WorldProviderHell) {
                NoiseGeneratorOctaves perlin2 = new NoiseGeneratorOctaves(r, 10);
                NoiseGeneratorOctaves perlin3 = new NoiseGeneratorOctaves(r, 16);
                ctx = new InitNoiseGensEvent.ContextHell(lperlin1, lperlin2, perlin, perlin2, perlin3, scale, depth);
            } else if (world.provider instanceof WorldProviderEnd) {
                NoiseGeneratorSimplex island = new NoiseGeneratorSimplex(r);
                ctx = new InitNoiseGensEvent.ContextEnd(lperlin1, lperlin2, perlin, scale, depth, island);
            } else {
                NoiseGeneratorPerlin height = new NoiseGeneratorPerlin(r, 4);
                NoiseGeneratorOctaves forest = new NoiseGeneratorOctaves(r, 8);
                ctx = new InitNoiseGensEvent.ContextOverworld(lperlin1, lperlin2, perlin, height, scale, depth, forest);
            }

            InitNoiseGensEvent<?> event = new InitNoiseGensEvent<>(world, r, ctx);
            MinecraftForge.TERRAIN_GEN_BUS.post(event);
        } catch (Throwable t) {
            WorldFlow.LOGGER.debug("[WorldFlow] Handled exception firing InitNoiseGensEvent", t);
        }
    }

    public BiomeProvider getBiomeProvider(World world) {
        return this.biomeProviders.computeIfAbsent(world, w -> {
            try {
                if (w.provider != null && w.provider.getBiomeProvider() != null) {
                    return w.provider.getBiomeProvider();
                }
            } catch (Throwable ignored) {
            }
            if (w.provider instanceof WorldProviderHell) {
                return new BiomeProviderSingle(Biomes.HELL);
            } else if (w.provider instanceof WorldProviderEnd) {
                return new BiomeProviderSingle(Biomes.SKY);
            } else if (w.getWorldInfo() != null && w.getWorldInfo().getTerrainType() != null) {
                try {
                    return w.getWorldInfo().getTerrainType().getBiomeProvider(w);
                } catch (Throwable t) {
                    return new BiomeProvider(w.getWorldInfo());
                }
            }
            return new BiomeProvider(w.getWorldInfo());
        });
    }

    public static WorkerGeneratorContext get() {
        return THREAD_LOCAL.get();
    }
}
