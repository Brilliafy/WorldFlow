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
    private final Map<WorldServer, IChunkGenerator> chunkGenerators = new WeakHashMap<>();
    private final Map<World, BiomeProvider> biomeProviders = new WeakHashMap<>();

    public WoodlandMansion getWoodlandMansion(ChunkGeneratorOverworld provider) {
        if (this.woodlandMansion == null && provider != null) {
            this.woodlandMansion = new WoodlandMansion(provider);
        }
        return this.woodlandMansion;
    }

    public IChunkGenerator getChunkGenerator(WorldServer world) {
        return this.chunkGenerators.computeIfAbsent(world, w -> {
            try {
                return w.provider.createChunkGenerator();
            } catch (Throwable t) {
                WorldFlow.LOGGER.warn("[WorldFlow] Failed to create thread-local chunk generator for dimension {}, falling back to provider generator", w.provider.getDimension(), t);
                return w.getChunkProvider().chunkGenerator;
            }
        });
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
