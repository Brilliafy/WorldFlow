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

        // Quark trivial chunk-local generators -> PARALLEL_SAFE (removes 90%+ lock convoy)
        registerClass("ClayGenerator", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerClass("vazkii.quark.world.world.ClayGenerator", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerClass("BasaltGenerator", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerClass("vazkii.quark.world.world.BasaltGenerator", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerClass("BiotiteGenerator", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerClass("vazkii.quark.world.world.BiotiteGenerator", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerClass("SpeleothemGenerator", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerClass("vazkii.quark.world.world.SpeleothemGenerator", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerClass("SmokerGenerator", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerClass("vazkii.quark.world.world.SmokerGenerator", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerClass("MonsterBoxGenerator", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerClass("vazkii.quark.world.world.MonsterBoxGenerator", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerClass("FairyRingGenerator", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerClass("vazkii.quark.world.world.FairyRingGenerator", GeneratorSafetyLevel.PARALLEL_SAFE);

        // DynamicTrees tree generation -> PARALLEL_SAFE
        registerClass("WorldGeneratorTrees", GeneratorSafetyLevel.PARALLEL_SAFE);
        registerClass("com.ferreusveritas.dynamictrees.worldgen.WorldGeneratorTrees", GeneratorSafetyLevel.PARALLEL_SAFE);

        // Explicitly keep serialized for complex cross-chunk structures
        registerClass("PirateShipGenerator", GeneratorSafetyLevel.SERIALIZE);
        registerClass("vazkii.quark.world.world.PirateShipGenerator", GeneratorSafetyLevel.SERIALIZE);
        registerClass("WorldGenCustomStructures", GeneratorSafetyLevel.SERIALIZE);
        registerClass("com.dhanantry.scapeandrunparasites.world.gen.WorldGenCustomStructures", GeneratorSafetyLevel.SERIALIZE);
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

        // 2. Simple class name or inner class name match
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        int dollarIndex = simpleName.lastIndexOf('$');
        if (dollarIndex >= 0) {
            GeneratorSafetyLevel outer = this.classRules.get(simpleName.substring(0, dollarIndex));
            if (outer != null) return outer;
            GeneratorSafetyLevel inner = this.classRules.get(simpleName.substring(dollarIndex + 1));
            if (inner != null) return inner;
        } else {
            GeneratorSafetyLevel simple = this.classRules.get(simpleName);
            if (simple != null) return simple;
        }

        // 3. Package prefix match
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

        // Default: PARALLEL_SAFE. ThreadLocalWorldView and CascadeBlockBuffer isolate block placements and chunk access.
        // Known unsafe multi-chunk structure generators are explicitly configured as SERIALIZE or UNSAFE.
        return GeneratorSafetyLevel.PARALLEL_SAFE;
    }
}
