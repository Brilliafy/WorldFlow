package com.cleanroommc.worldflow.pipeline.otg;

import com.cleanroommc.worldflow.WorldFlow;
import com.pg85.otg.common.LocalWorld;
import com.pg85.otg.forge.generator.ForgeChunkBuffer;
import com.pg85.otg.forge.world.ForgeWorld;
import com.pg85.otg.generator.ChunkBuffer;
import com.pg85.otg.generator.ChunkProviderOTG;
import com.pg85.otg.util.ChunkCoordinate;
import net.minecraft.world.World;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Thread-local cache of ChunkProviderOTG instances and reflection helper for ForgeChunkBuffer.
 * ChunkProviderOTG retains internal noise arrays and RNG state during terrain generation;
 * providing per-worker instances enables true concurrent OTG base terrain generation.
 */
public class OTGParallelContext {

    /** Per-worker buffer currently used by the parallel OTG terrain path. */
    public static final ThreadLocal<ForgeChunkBuffer> CURRENT_BUFFER = new ThreadLocal<>();

    /** Per-worker ObjectSpawner instances for lock-free parallel population. */
    private static final ThreadLocal<java.util.Map<ForgeWorld, com.pg85.otg.generator.ObjectSpawner>> THREAD_LOCAL_SPAWNERS =
            ThreadLocal.withInitial(java.util.WeakHashMap::new);

    public static com.pg85.otg.generator.ObjectSpawner getThreadLocalSpawner(ForgeWorld world) {
        if (world == null) {
            return null;
        }
        return THREAD_LOCAL_SPAWNERS.get().computeIfAbsent(world,
                w -> new com.pg85.otg.generator.ObjectSpawner(w.getConfigs(), (LocalWorld) w));
    }

    private static final ThreadLocal<java.util.Map<Integer, ChunkProviderOTG>> THREAD_LOCAL_PROVIDERS = ThreadLocal.withInitial(java.util.HashMap::new);
    private static Constructor<ForgeChunkBuffer> bufferConstructor;
    private static Method toChunkMethod;
    private static boolean reflectionInit = false;

    private static synchronized void initReflection() {
        if (reflectionInit) {
            return;
        }
        try {
            bufferConstructor = ForgeChunkBuffer.class.getDeclaredConstructor(ChunkCoordinate.class);
            bufferConstructor.setAccessible(true);

            toChunkMethod = ForgeChunkBuffer.class.getDeclaredMethod("toChunk", World.class);
            toChunkMethod.setAccessible(true);

            reflectionInit = true;
        } catch (Throwable t) {
            WorldFlow.LOGGER.error("[WorldFlow] Failed to reflect ForgeChunkBuffer methods for OTG parallel generation", t);
        }
    }

    public static ChunkProviderOTG getProvider(ForgeWorld world) {
        if (world == null) {
            return null;
        }
        int dim = 0;
        try {
            if (world.getWorld() != null && world.getWorld().provider != null) {
                dim = world.getWorld().provider.getDimension();
            }
        } catch (Throwable ignored) {
        }
        java.util.Map<Integer, ChunkProviderOTG> map = THREAD_LOCAL_PROVIDERS.get();
        ChunkProviderOTG provider = map.get(dim);
        if (provider == null) {
            provider = new ChunkProviderOTG(world.getConfigs(), (LocalWorld) world);
            map.put(dim, provider);
        }
        return provider;
    }

    public static Chunk generateOTGChunk(ForgeWorld world, int chunkX, int chunkZ) {
        initReflection();
        if (bufferConstructor == null || toChunkMethod == null) {
            return null;
        }

        ChunkCoordinate coord = ChunkCoordinate.fromChunkCoords(chunkX, chunkZ);
        try {
            ForgeChunkBuffer buffer = bufferConstructor.newInstance(coord);
            ChunkProviderOTG provider = getProvider(world);
            CURRENT_BUFFER.set(buffer);
            try {
                provider.generate((ChunkBuffer) buffer);
            } finally {
                CURRENT_BUFFER.remove();
            }
            Chunk chunk = (Chunk) toChunkMethod.invoke(buffer, world.getWorld());
            if (chunk != null) {
                fillBiomeArray(world, chunk);
                chunk.generateSkylightMap();
            }
            return chunk;
        } catch (Throwable t) {
            WorldFlow.LOGGER.error("[WorldFlow] Error during concurrent OTG chunk generation [{}, {}]", chunkX, chunkZ, t);
            return null;
        }
    }

    /**
     * Saved-id biome array per chunk. OTG's layered biome generator is expensive (recursive
     * LayerBiomeInBiome/LayerZoom math) and the same chunk is queried by every neighbour that
     * decorates around it, so results are cached once per chunk and copied out.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Long, byte[]> BIOME_ARRAY_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Long, short[]> OTG_CHUNK_BIOMES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int BIOME_ARRAY_CACHE_MAX = 16384;

    /**
     * All biome generators share one instance; the patched OTG layer chain keeps its
     * seed scratch per thread and the cached generator keeps a per-thread chunk map,
     * so this is lock-free now.
     */
    public static int[] getBiomesSynchronized(com.pg85.otg.generator.biome.BiomeGenerator generator,
                                              int[] array, int x, int z, int sizeX, int sizeZ,
                                              com.pg85.otg.generator.biome.OutputType type) {
        return generator.getBiomes(array, x, z, sizeX, sizeZ, type);
    }

    /** Worlds whose OTG tree resources were already pre-loaded. */
    private static final java.util.Map<net.minecraft.world.WorldServer, Boolean> TREE_WARMED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Eagerly performs OTG's one-time {@code TreeGen.loadTrees} work on the calling
     * thread, before the generation pools start accepting chunks. The loaded arrays
     * are read-only afterwards, so workers never race on lazy initialization.
     */
    public static void warmTreeResources(net.minecraft.world.WorldServer world) {
        if (world == null || TREE_WARMED.putIfAbsent(world, Boolean.TRUE) != null) {
            return;
        }
        boolean ok = false;
        try {
            if (!(com.pg85.otg.OTG.getEngine() instanceof com.pg85.otg.forge.ForgeEngine)) {
                return;
            }
            ForgeWorld forgeWorld = ((com.pg85.otg.forge.ForgeEngine) com.pg85.otg.OTG.getEngine()).getWorld(world);
            if (forgeWorld == null) {
                return;
            }
            com.pg85.otg.network.ConfigProvider configs = forgeWorld.getConfigs();
            if (configs == null) {
                return;
            }
            com.pg85.otg.common.LocalBiome[] biomes = configs.getBiomeArrayByOTGId();
            if (biomes == null) {
                return;
            }
            String worldName = forgeWorld.getName();
            java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            int warmed = 0;
            for (com.pg85.otg.common.LocalBiome biome : biomes) {
                if (biome == null) {
                    continue;
                }
                com.pg85.otg.configuration.biome.BiomeConfig config = biome.getBiomeConfig();
                if (config == null || config.resourceSequence == null) {
                    continue;
                }
                for (Object resource : config.resourceSequence) {
                    if (resource instanceof WorldFlowTreeState && seen.add(resource)) {
                        ((WorldFlowTreeState) resource).worldflow$warmLoad(worldName);
                        warmed++;
                    }
                }
            }
            ok = true;
            WorldFlow.LOGGER.info("[WorldFlow] Pre-warmed {} OTG tree resource(s) for world {}", warmed, worldName);
        } catch (Throwable t) {
            WorldFlow.LOGGER.warn("[WorldFlow] OTG tree pre-warm failed; safe lazy init will be used", t);
        } finally {
            if (!ok) {
                TREE_WARMED.remove(world);
            }
        }
    }

    public static short[] getChunkOTGBiomes(ForgeWorld world, int chunkX, int chunkZ) {
        if (world == null) return null;
        long key = ChunkPos.asLong(chunkX, chunkZ);
        short[] cached = OTG_CHUNK_BIOMES.get(key);
        if (cached != null) {
            return cached;
        }

        com.pg85.otg.generator.biome.BiomeGenerator biomeGenerator = world.getBiomeGenerator();
        if (biomeGenerator == null) {
            return null;
        }

        int[] biomeArray;
        cached = OTG_CHUNK_BIOMES.get(key);
        if (cached != null) return cached;
        // CachedBiomeGeneratorMixin gives each worker a thread-local read-through cache;
        // the generator monitor is no longer needed here.
        biomeArray = getBiomesSynchronized(biomeGenerator, null, chunkX * 16, chunkZ * 16, 16, 16,
                com.pg85.otg.generator.biome.OutputType.DEFAULT_FOR_WORLD);
        if (biomeArray == null || biomeArray.length < 256) {
            return null;
        }
        short[] arr = new short[256];
        for (int i = 0; i < 256; i++) {
            arr[i] = (short) biomeArray[i];
        }
        if (OTG_CHUNK_BIOMES.size() >= BIOME_ARRAY_CACHE_MAX) {
            OTG_CHUNK_BIOMES.clear();
        }
        OTG_CHUNK_BIOMES.putIfAbsent(key, arr);
        return arr;
    }

    public static void fillBiomeArray(ForgeWorld world, Chunk chunk) {
        if (world == null || chunk == null) {
            return;
        }
        byte[] chunkBiomeArray = chunk.getBiomeArray();
        com.pg85.otg.network.ConfigProvider configProvider = world.getConfigs();
        if (configProvider == null) {
            return;
        }

        long cacheKey = ChunkPos.asLong(chunk.x, chunk.z);
        byte[] saved = BIOME_ARRAY_CACHE.get(cacheKey);
        if (saved == null) {
            short[] otgIds = getChunkOTGBiomes(world, chunk.x, chunk.z);
            if (otgIds == null) {
                return;
            }
            saved = new byte[chunkBiomeArray.length];
            for (int i = 0; i < saved.length && i < otgIds.length; ++i) {
                com.pg85.otg.common.LocalBiome biome = configProvider.getBiomeByOTGIdOrNull(otgIds[i] & 0xFFFF);
                saved[i] = (byte) (biome != null && biome.getIds() != null ? biome.getIds().getSavedId() : 0);
            }
            if (BIOME_ARRAY_CACHE.size() >= BIOME_ARRAY_CACHE_MAX) {
                BIOME_ARRAY_CACHE.clear();
            }
            byte[] prev = BIOME_ARRAY_CACHE.putIfAbsent(cacheKey, saved);
            if (prev != null) {
                saved = prev;
            }
        }
        System.arraycopy(saved, 0, chunkBiomeArray, 0, Math.min(saved.length, chunkBiomeArray.length));
    }

    public static void clear() {
        THREAD_LOCAL_PROVIDERS.get().clear();
    }
}
