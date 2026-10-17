package com.cleanroommc.worldflow.pipeline.otg;

import com.cleanroommc.worldflow.WorldFlow;
import com.pg85.otg.common.LocalWorld;
import com.pg85.otg.forge.generator.ForgeChunkBuffer;
import com.pg85.otg.forge.world.ForgeWorld;
import com.pg85.otg.generator.ChunkBuffer;
import com.pg85.otg.generator.ChunkProviderOTG;
import com.pg85.otg.util.ChunkCoordinate;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Thread-local cache of ChunkProviderOTG instances and reflection helper for ForgeChunkBuffer.
 * ChunkProviderOTG retains internal noise arrays and RNG state during terrain generation;
 * providing per-worker instances enables true concurrent OTG base terrain generation.
 */
public class OTGParallelContext {

    public static final int NUM_SHARDS = 16;
    private static final java.util.concurrent.locks.ReentrantLock[] SHARDED_LOCKS = new java.util.concurrent.locks.ReentrantLock[NUM_SHARDS];

    static {
        for (int i = 0; i < NUM_SHARDS; i++) {
            SHARDED_LOCKS[i] = new java.util.concurrent.locks.ReentrantLock();
        }
    }

    public static final java.util.concurrent.locks.ReentrantLock OTG_STRUCTURE_LOCK = SHARDED_LOCKS[0];

    public static java.util.concurrent.locks.ReentrantLock getStructureLock(int chunkX, int chunkZ) {
        int macroX = chunkX >> 2;
        int macroZ = chunkZ >> 2;
        int hash = (macroX * 31 + macroZ) & (NUM_SHARDS - 1);
        return SHARDED_LOCKS[hash];
    }

    public static boolean tryLockAll(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        int acquired = 0;
        for (int i = 0; i < NUM_SHARDS; i++) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !SHARDED_LOCKS[i].tryLock(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                for (int j = 0; j < acquired; j++) {
                    SHARDED_LOCKS[j].unlock();
                }
                return false;
            }
            acquired++;
        }
        return true;
    }

    public static void unlockAll() {
        for (int i = 0; i < NUM_SHARDS; i++) {
            if (SHARDED_LOCKS[i].isHeldByCurrentThread()) {
                SHARDED_LOCKS[i].unlock();
            }
        }
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
            provider.generate((ChunkBuffer) buffer);
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

    public static void fillBiomeArray(ForgeWorld world, Chunk chunk) {
        if (world == null || chunk == null) {
            return;
        }
        byte[] chunkBiomeArray = chunk.getBiomeArray();
        com.pg85.otg.network.ConfigProvider configProvider = world.getConfigs();
        com.pg85.otg.generator.biome.BiomeGenerator biomeGenerator = world.getBiomeGenerator();
        if (configProvider == null || biomeGenerator == null) {
            return;
        }
        int[] biomeShortArray;
        synchronized (biomeGenerator) {
            biomeShortArray = biomeGenerator.getBiomes(null, chunk.x * 16, chunk.z * 16, 16, 16, com.pg85.otg.generator.biome.OutputType.DEFAULT_FOR_WORLD);
        }
        if (biomeShortArray != null) {
            for (int i = 0; i < chunkBiomeArray.length && i < biomeShortArray.length; ++i) {
                int generationId = biomeShortArray[i];
                com.pg85.otg.common.LocalBiome biome = configProvider.getBiomeByOTGIdOrNull(generationId);
                chunkBiomeArray[i] = (byte) (biome != null ? biome.getIds().getSavedId() : 0);
            }
        }
    }

    public static void clear() {
        THREAD_LOCAL_PROVIDERS.get().clear();
    }
}
