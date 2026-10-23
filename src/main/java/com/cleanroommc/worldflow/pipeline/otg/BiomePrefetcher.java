package com.cleanroommc.worldflow.pipeline.otg;

import com.cleanroommc.worldflow.WorldFlow;
import com.pg85.otg.forge.world.ForgeWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Batched, bit-identical biome pre-primer for the wavefront.
 *
 * <p>Warms the per-chunk OTG biome array cache a few rows ahead of the feature
 * workers. The arrays are produced by the same code path and caches that the
 * feature stage uses, so there is no approximation and no floating-point drift.
 * This is the CPU/SIMD-compatible reference for the GPU kernel work: the same
 * batch could later be filled by an OpenCL kernel without changing consumers.</p>
 *
 * <p>Opt-in via {@code -Dworldflow.prefetchBiomes=true}. The pool is small and
 * low priority so it never starves BaseGen/Features.</p>
 */
public final class BiomePrefetcher {

    private static final Set<Long> QUEUED = ConcurrentHashMap.newKeySet();
    private static volatile ExecutorService pool;
    private static volatile ForgeWorld forgeWorld;

    private BiomePrefetcher() {
    }

    public static synchronized void start(WorldServer world) {
        if (pool != null || world == null) {
            return;
        }
        forgeWorld = com.cleanroommc.worldflow.pipeline.isolation.WorkerGeneratorContext.get()
                .getForgeWorld(world, world.getChunkProvider().chunkGenerator);
        if (forgeWorld == null) {
            WorldFlow.LOGGER.info("[WorldFlow] Biome pre-primer disabled: OTG ForgeWorld not available yet.");
            return;
        }
        QUEUED.clear();
        AtomicInteger id = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "WorldFlow-BiomePrefetch-" + id.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        };
        // Two workers: the feature workers are already using every core; this is a
        // latency-hiding helper, not a throughput multiplier.
        pool = Executors.newFixedThreadPool(2, factory);
        WorldFlow.LOGGER.info("[WorldFlow] Biome pre-primer started (2 threads, batched).");
    }

    public static void submit(ChunkPos pos) {
        ExecutorService executor = pool;
        if (executor == null || pos == null) {
            return;
        }
        long key = ChunkPos.asLong(pos.x, pos.z);
        if (!QUEUED.add(key)) {
            return;
        }
        executor.execute(() -> {
            try {
                ForgeWorld world = forgeWorld;
                if (world != null) {
                    OTGParallelContext.getChunkOTGBiomes(world, pos.x, pos.z);
                }
            } catch (Throwable t) {
                WorldFlow.LOGGER.debug("[WorldFlow] Biome pre-primer failed for chunk [{}, {}]", pos.x, pos.z, t);
            }
        });
    }

    public static synchronized void stop() {
        ExecutorService executor = pool;
        pool = null;
        forgeWorld = null;
        QUEUED.clear();
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
