package com.cleanroommc.worldflow.pipeline.compat;

import com.cleanroommc.worldflow.config.ModConfig;
import net.minecraftforge.fml.common.IWorldGenerator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry mapping world generators and event listeners to thread-safety levels.
 * Guarantees that mod generators run in the correct environment (parallel, serialized, or main-thread).
 */
public class CompatRegistry {

    public static final CompatRegistry INSTANCE = new CompatRegistry();

    private final Map<String, GeneratorSafetyLevel> classRules = new ConcurrentHashMap<>();
    private final Map<String, GeneratorSafetyLevel> packageRules = new ConcurrentHashMap<>();
    private final Map<Class<?>, GeneratorSafetyLevel> resolvedCache = new ConcurrentHashMap<>();

    private CompatRegistry() {
        initDefaults();
    }

    private void initDefaults() {
        // Parallel safe: standard vanilla-style decorators, ores, and flora
        registerPackage("net.minecraft.", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerPackage("cofh.", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerPackage("forestry.", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerPackage("biomesoplenty.", GeneratorSafetyLevel.PARALLEL_SAFE);

        // Serialize: multi-chunk complex structures with global cache or shared RNG state.
        // NOTE ON OTG/DREGORA: Base terrain (ChunkProviderOTG / ForgeChunkBuffer) runs FULLY PARALLEL via
        // OTGChunkGeneratorMixin & OTGParallelContext. Therefore OTGChunkGenerator is PARALLEL_SAFE.
        // OTG custom structure generation (BO3/BO4, ObjectSpawner) is locked specifically during feature population via OTG_STRUCTURE_LOCK.
        registerClass("com.pg85.otg.forge.generator.OTGChunkGenerator", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerPackage("com.pg85.otg.", GeneratorSafetyLevel.SERIALIZE);
        registerPackage("ivorius.reccomplex.", GeneratorSafetyLevel.SERIALIZE);
        registerPackage("battletowers.", GeneratorSafetyLevel.SERIALIZE);
        registerPackage("atomicstryker.battletowers.", GeneratorSafetyLevel.SERIALIZE);
        registerPackage("dungeonmobs.", GeneratorSafetyLevel.SERIALIZE);
        registerPackage("greymerk.roguelike.", GeneratorSafetyLevel.SERIALIZE);
        registerPackage("com.github.fnar.roguelike.", GeneratorSafetyLevel.SERIALIZE);
        registerPackage("jaredbgreat.dldungeons.", GeneratorSafetyLevel.SERIALIZE);
        registerPackage("com.github.alexthe666.iceandfire.", GeneratorSafetyLevel.SERIALIZE);
        registerPackage("com.bobmowzie.mowziesmobs.", GeneratorSafetyLevel.SERIALIZE);
        registerPackage("com.legacy.aether.", GeneratorSafetyLevel.SERIALIZE);
        registerPackage("vazkii.quark.", GeneratorSafetyLevel.SERIALIZE);
        registerPackage("com.ferreusveritas.dynamictrees.", GeneratorSafetyLevel.SERIALIZE);
        registerPackage("com.dhanantry.scapeandrunparasites.", GeneratorSafetyLevel.SERIALIZE);
    }

    public void registerPackage(String pkgPrefix, GeneratorSafetyLevel level) {
        this.packageRules.put(pkgPrefix, level);
        this.resolvedCache.clear();
    }

    public void registerClass(String className, GeneratorSafetyLevel level) {
        this.classRules.put(className, level);
        this.resolvedCache.clear();
    }

    public GeneratorSafetyLevel getSafetyLevel(Object generator) {
        if (generator == null) {
            return GeneratorSafetyLevel.PARALLEL_SAFE;
        }
        Class<?> clazz = generator.getClass();
        GeneratorSafetyLevel cached = this.resolvedCache.get(clazz);
        if (cached != null) {
            return cached;
        }

        GeneratorSafetyLevel level = resolveSafetyLevel(clazz.getName(), generator instanceof IWorldGenerator);
        this.resolvedCache.put(clazz, level);
        return level;
    }

    private GeneratorSafetyLevel resolveSafetyLevel(String className, boolean isWorldGen) {
        // 1. Direct class match
        GeneratorSafetyLevel direct = this.classRules.get(className);
        if (direct != null) {
            return direct;
        }

        // 2. Package prefix match
        for (Map.Entry<String, GeneratorSafetyLevel> entry : this.packageRules.entrySet()) {
            if (className.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }

        // 3. User config overrides
        if (ModConfig.CONCURRENCY != null) {
            if (ModConfig.CONCURRENCY.unsafeGenerators != null) {
                for (String pattern : ModConfig.CONCURRENCY.unsafeGenerators) {
                    if (!pattern.isEmpty() && className.contains(pattern)) {
                        return GeneratorSafetyLevel.UNSAFE;
                    }
                }
            }
            if (ModConfig.CONCURRENCY.serializedGenerators != null) {
                for (String pattern : ModConfig.CONCURRENCY.serializedGenerators) {
                    if (!pattern.isEmpty() && className.contains(pattern)) {
                        return GeneratorSafetyLevel.SERIALIZE;
                    }
                }
            }
            if (ModConfig.CONCURRENCY.parallelSafeGenerators != null) {
                for (String pattern : ModConfig.CONCURRENCY.parallelSafeGenerators) {
                    if (!pattern.isEmpty() && className.contains(pattern)) {
                        return GeneratorSafetyLevel.PARALLEL_SAFE;
                    }
                }
            }
        }

        // Default: serialize unknown modded IWorldGenerators for maximum stability
        if (isWorldGen) {
            return GeneratorSafetyLevel.SERIALIZE;
        }

        return GeneratorSafetyLevel.PARALLEL_SAFE;
    }
}
