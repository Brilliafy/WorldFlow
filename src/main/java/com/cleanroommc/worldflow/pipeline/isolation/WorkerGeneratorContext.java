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

import com.pg85.otg.forge.world.ForgeWorld;
import net.minecraft.world.gen.NoiseGeneratorOctaves;
import net.minecraft.world.gen.NoiseGeneratorPerlin;
import net.minecraft.world.gen.NoiseGeneratorSimplex;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.InitNoiseGensEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
    private final Map<MapGenBase, MapGenBase> workerCavesGens = new WeakHashMap<>();

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
            IChunkGenerator mainGen = world.getChunkProvider() != null ? world.getChunkProvider().chunkGenerator : null;
            if (mainGen != null && "com.pg85.otg.forge.generator.OTGChunkGenerator".equals(mainGen.getClass().getName())) {
                try {
                    ForgeWorld forgeWorld = null;
                    if (com.pg85.otg.OTG.getEngine() instanceof com.pg85.otg.forge.ForgeEngine) {
                        forgeWorld = ((com.pg85.otg.forge.ForgeEngine) com.pg85.otg.OTG.getEngine()).getWorld(world);
                    }
                    if (forgeWorld == null) {
                        Field fWorld = mainGen.getClass().getDeclaredField("world");
                        fWorld.setAccessible(true);
                        forgeWorld = (ForgeWorld) fWorld.get(mainGen);
                    }
                    if (forgeWorld != null) {
                        gen = new com.pg85.otg.forge.generator.OTGChunkGenerator(forgeWorld);
                    }
                } catch (Throwable t) {
                    WorldFlow.LOGGER.warn("[WorldFlow] Failed to create dedicated OTGChunkGenerator for worker, falling back to main generator", t);
                    gen = mainGen;
                }
            } else if (mainGen != null) {
                gen = mainGen;
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

    public MapGenBase getWorkerCavesGen(ForgeWorld forgeWorld, MapGenBase mainCaves) {
        if (mainCaves == null || forgeWorld == null) {
            return null;
        }

        MapGenBase cached = this.workerCavesGens.get(mainCaves);
        if (cached != null) {
            return cached;
        }

        if ("com.yungnickyoung.minecraft.bettercaves.world.MapGenBetterCaves".equals(mainCaves.getClass().getName())) {
            try {
                MapGenBase clone = createWorkerBetterCaves(forgeWorld.getWorld(), mainCaves);
                if (clone != null) {
                    this.workerCavesGens.put(mainCaves, clone);
                    return clone;
                }
            } catch (Throwable t) {
                WorldFlow.LOGGER.warn("[WorldFlow] Failed to create isolated MapGenBetterCaves worker instance", t);
            }
        }

        return null;
    }

    private MapGenBase createWorkerBetterCaves(World world, MapGenBase mainCaves) throws Exception {
        Class<?> bcClass = mainCaves.getClass();

        // 1. Allocate instance without running constructor
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        MapGenBase workerGen = (MapGenBase) unsafe.allocateInstance(bcClass);

        // 2. Resolve world if null
        if (world == null) {
            for (Class<?> c = bcClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (World.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object val = f.get(mainCaves);
                        if (val instanceof World) {
                            world = (World) val;
                            break;
                        }
                    }
                }
                if (world != null) break;
            }
        }
        if (world == null) {
            return null;
        }

        // 3. Set MapGenBase superclass fields (world, rand, range)
        for (Class<?> c = bcClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                field.setAccessible(true);
                if (World.class.isAssignableFrom(field.getType())) {
                    field.set(workerGen, world);
                } else if (Random.class.isAssignableFrom(field.getType())) {
                    field.set(workerGen, new Random());
                } else if (field.getType() == int.class && ("range".equals(field.getName()) || "field_75040_a".equals(field.getName()))) {
                    field.setInt(workerGen, 8);
                }
            }
        }

        // 4. Resolve config
        Field configField = bcClass.getDeclaredField("config");
        configField.setAccessible(true);
        Object config = configField.get(mainCaves);
        if (config == null && world.provider != null) {
            try {
                Method loadConfig = Class.forName("com.yungnickyoung.minecraft.bettercaves.config.io.ConfigLoader")
                        .getMethod("loadConfigFromFileForDimension", int.class);
                config = loadConfig.invoke(null, world.provider.getDimension());
            } catch (Throwable ignored) {
            }
        }
        if (config == null) {
            config = Class.forName("com.yungnickyoung.minecraft.bettercaves.config.util.ConfigHolder").newInstance();
        }
        configField.set(workerGen, config);

        // 5. Copy defaultCaveGen
        Field defaultCaveGenField = bcClass.getDeclaredField("defaultCaveGen");
        defaultCaveGenField.setAccessible(true);
        Object defaultGen = defaultCaveGenField.get(mainCaves);
        if (defaultGen == null) {
            defaultGen = new MapGenCaves();
        }
        defaultCaveGenField.set(workerGen, defaultGen);

        // 6. Instantiate fresh, isolated controllers
        Class<?> configHolderClass = Class.forName("com.yungnickyoung.minecraft.bettercaves.config.util.ConfigHolder");

        Constructor<?> waterCons = Class.forName("com.yungnickyoung.minecraft.bettercaves.world.WaterRegionController")
                .getConstructor(World.class, configHolderClass);
        Object waterController = waterCons.newInstance(world, config);
        Field waterField = bcClass.getDeclaredField("waterRegionController");
        waterField.setAccessible(true);
        waterField.set(workerGen, waterController);

        Constructor<?> caveCons = Class.forName("com.yungnickyoung.minecraft.bettercaves.world.CaveCarverController")
                .getConstructor(World.class, configHolderClass);
        Object caveController = caveCons.newInstance(world, config);
        Field caveField = bcClass.getDeclaredField("caveCarverController");
        caveField.setAccessible(true);
        caveField.set(workerGen, caveController);

        Constructor<?> cavernCons = Class.forName("com.yungnickyoung.minecraft.bettercaves.world.CavernCarverController")
                .getConstructor(World.class, configHolderClass);
        Object cavernController = cavernCons.newInstance(world, config);
        Field cavernField = bcClass.getDeclaredField("cavernCarverController");
        cavernField.setAccessible(true);
        cavernField.set(workerGen, cavernController);

        WorldFlow.LOGGER.info("[WorldFlow] Created thread-isolated MapGenBetterCaves instance for worker thread: {}", Thread.currentThread().getName());
        return workerGen;
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

    public void clear() {
        this.chunkGenerators.clear();
        this.biomeProviders.clear();
        this.workerCavesGens.clear();
    }

    public static WorkerGeneratorContext get() {
        return THREAD_LOCAL.get();
    }
}
