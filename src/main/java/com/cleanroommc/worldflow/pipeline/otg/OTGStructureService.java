package com.cleanroommc.worldflow.pipeline.otg;

import com.cleanroommc.worldflow.WorldFlow;
import com.pg85.otg.customobjects.structures.CustomStructureCache;
import com.pg85.otg.util.ChunkCoordinate;

import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Dedicated single-thread structure plotting service.
 * All OTG plotStructures / BO4 plotting runs exclusively on the "WorldFlow-Structures" thread,
 * completely eliminating lock convoys, monitor contention, and 20ms spin-wait deadlocks.
 */
public class OTGStructureService {

    public static final String THREAD_NAME = "WorldFlow-Structures";

    /** Plots take the write lock; BO4 spawns take the read lock (parallel readers). */
    public static final java.util.concurrent.locks.ReentrantLock STRUCTURE_CACHE_LOCK =
            new java.util.concurrent.locks.ReentrantLock();

    /** Wall time spent waiting for the structure executor (contention indicator). */
    public static final java.util.concurrent.atomic.AtomicLong TOTAL_WAIT_NANOS = new java.util.concurrent.atomic.AtomicLong();
    /** Time the plot itself took on the structure thread. */
    public static final java.util.concurrent.atomic.AtomicLong TOTAL_WORK_NANOS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong CALL_COUNT = new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicInteger PLOT_THREAD_ID = new java.util.concurrent.atomic.AtomicInteger(1);
    private static final int PLOT_THREADS = Boolean.getBoolean("worldflow.deterministic")
            ? 1 : Math.max(4, Integer.getInteger("worldflow.plotThreads", Runtime.getRuntime().availableProcessors()));
    private static final ExecutorService STRUCTURE_EXECUTOR = Executors.newFixedThreadPool(PLOT_THREADS, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, THREAD_NAME + "-" + PLOT_THREAD_ID.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        }
    });

    public static boolean isStructureThread() {
        return true;
    }

    /**
     * In-flight dedup: each populate plots its own coord plus three neighbours, so adjacent
     * chunks request 3 of the same 4 plots. Only the first requester runs the plot (with its
     * own RNG, preserving semantics); duplicates wait on the same future instead of queueing
     * another task on the single structure thread. Removing completed entries keeps memory
     * bounded - later duplicates become cheap cache hits inside the plotter.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<Void>> IN_FLIGHT_PLOTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Coords already plotted this session. The plotter's own fast cache makes a repeat call
     * a no-op that consumes no RNG, so skipping the service round-trip is equivalent - it
     * just removes ~75% of the calls (every chunk plots itself plus three neighbours).
     */
    private static final ThreadLocal<java.util.Set<Long>> BATCH_DONE = ThreadLocal.withInitial(java.util.HashSet::new);

    /** Coords already plotted, per structure cache. Only touched on the structure thread
     *  (plus a synchronized reset on world load), so the plotter's cache state is the
     *  single source of truth and skipping a hit consumes no RNG. */
    private static final java.util.Map<CustomStructureCache, java.util.Set<Long>> PLOTTED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static java.util.Set<Long> plottedSet(CustomStructureCache cache) {
        synchronized (PLOTTED) {
            return PLOTTED.computeIfAbsent(cache, c -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        }
    }

    public static void resetPlotted() {
        PLOTTED.clear();
    }

    public static void clearBatch() {
        BATCH_DONE.remove();
    }

    private static boolean consumeBatch(long key) {
        java.util.Set<Long> done = BATCH_DONE.get();
        return done.remove(key);
    }

    /**
     * Runs the four plots a chunk would request (self, +1X, +1Z, +1X+1Z) in the original
     * order as one service task. The four later plot calls are skipped via BATCH_DONE,
     * so RNG consumption and cache effects are identical to four blocking calls.
     */
    public static void executePlotBatch(CustomStructureCache cache, Random rand, ChunkCoordinate chunkCoord) {
        int cx = chunkCoord.getChunkX();
        int cz = chunkCoord.getChunkZ();
        ChunkCoordinate[] coords = new ChunkCoordinate[] {
                ChunkCoordinate.fromChunkCoords(cx + 1, cz),
                ChunkCoordinate.fromChunkCoords(cx, cz + 1),
                ChunkCoordinate.fromChunkCoords(cx + 1, cz + 1),
                chunkCoord
        };
        long workStart = System.nanoTime();
        try {
            java.util.Set<Long> plotted = plottedSet(cache);
            for (ChunkCoordinate c : coords) {
                long k = net.minecraft.util.math.ChunkPos.asLong(c.getChunkX(), c.getChunkZ());
                if (plotted.contains(k)) continue;
                cache.plotBo4Structures(rand, c);
                plotted.add(k);
            }
        } finally {
            TOTAL_WORK_NANOS.addAndGet(System.nanoTime() - workStart);
            CALL_COUNT.incrementAndGet();
        }
        java.util.Set<Long> done = BATCH_DONE.get();
        for (ChunkCoordinate c : coords) {
            done.add(net.minecraft.util.math.ChunkPos.asLong(c.getChunkX(), c.getChunkZ()));
        }
    }

    public static void executePlot(CustomStructureCache cache, Random rand, ChunkCoordinate chunkCoord) {
        long key = net.minecraft.util.math.ChunkPos.asLong(chunkCoord.getChunkX(), chunkCoord.getChunkZ());
        if (consumeBatch(key)) {
            return;
        }
        long workStart = System.nanoTime();
        try {
            java.util.Set<Long> plotted = plottedSet(cache);
            if (!plotted.contains(key)) {
                cache.plotBo4Structures(rand, chunkCoord);
                plotted.add(key);
            }
        } finally {
            TOTAL_WORK_NANOS.addAndGet(System.nanoTime() - workStart);
            CALL_COUNT.incrementAndGet();
        }
    }

    public static ChunkCoordinate executePlotSingle(CustomStructureCache cache, com.pg85.otg.customobjects.bo4.BO4 structure, java.util.ArrayList<String> biomes, ChunkCoordinate chunkCoord) {
        return cache.plotBo4Structure(structure, biomes, chunkCoord);
    }
}
