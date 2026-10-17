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

    private static final ThreadLocal<ChunkProviderOTG> THREAD_LOCAL_PROVIDER = new ThreadLocal<>();
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
        ChunkProviderOTG provider = THREAD_LOCAL_PROVIDER.get();
        if (provider == null && world != null) {
            provider = new ChunkProviderOTG(world.getConfigs(), (LocalWorld) world);
            THREAD_LOCAL_PROVIDER.set(provider);
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
            return (Chunk) toChunkMethod.invoke(buffer, world.getWorld());
        } catch (Throwable t) {
            WorldFlow.LOGGER.error("[WorldFlow] Error during concurrent OTG chunk generation [{}, {}]", chunkX, chunkZ, t);
            return null;
        }
    }

    public static void clear() {
        THREAD_LOCAL_PROVIDER.remove();
    }
}
