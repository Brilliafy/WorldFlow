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
    public static final java.util.concurrent.locks.ReentrantReadWriteLock STRUCTURE_CACHE_LOCK =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    /** Wall time spent waiting for the structure executor (contention indicator). */
    public static final java.util.concurrent.atomic.AtomicLong TOTAL_WAIT_NANOS = new java.util.concurrent.atomic.AtomicLong();
    /** Time the plot itself took on the structure thread. */
    public static final java.util.concurrent.atomic.AtomicLong TOTAL_WORK_NANOS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong CALL_COUNT = new java.util.concurrent.atomic.AtomicLong();

    private static final ExecutorService STRUCTURE_EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, THREAD_NAME);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        }
    });

    public static boolean isStructureThread() {
        return THREAD_NAME.equals(Thread.currentThread().getName());
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
        if (isStructureThread()) {
            for (ChunkCoordinate c : coords) cache.plotBo4Structures(rand, c);
            return;
        }
        long submitNanos = System.nanoTime();
        java.util.concurrent.Future<?> future = STRUCTURE_EXECUTOR.submit(() -> {
            long workStart = System.nanoTime();
            STRUCTURE_CACHE_LOCK.writeLock().lock();
            try {
                for (ChunkCoordinate c : coords) cache.plotBo4Structures(rand, c);
            } finally {
                STRUCTURE_CACHE_LOCK.writeLock().unlock();
                TOTAL_WORK_NANOS.addAndGet(System.nanoTime() - workStart);
            }
        });
        CALL_COUNT.incrementAndGet();
        try {
            future.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            WorldFlow.LOGGER.warn("[WorldFlow] Batch structure plot for chunk {} timed out; continuing", chunkCoord);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            WorldFlow.LOGGER.error("[WorldFlow] Error in batch structure plot for chunk {}", chunkCoord, cause);
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        } finally {
            TOTAL_WAIT_NANOS.addAndGet(System.nanoTime() - submitNanos);
        }
        java.util.Set<Long> done = BATCH_DONE.get();
        for (ChunkCoordinate c : coords) {
            done.add(net.minecraft.util.math.ChunkPos.asLong(c.getChunkX(), c.getChunkZ()));
        }
    }

    public static void executePlot(CustomStructureCache cache, Random rand, ChunkCoordinate chunkCoord) {
        if (isStructureThread()) {
            cache.plotBo4Structures(rand, chunkCoord);
            return;
        }

        long submitNanos = System.nanoTime();
        long key = net.minecraft.util.math.ChunkPos.asLong(chunkCoord.getChunkX(), chunkCoord.getChunkZ());
        if (consumeBatch(key)) {
            return;
        }
        java.util.concurrent.CompletableFuture<Void> own = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<Void> existing = IN_FLIGHT_PLOTS.putIfAbsent(key, own);
        final java.util.concurrent.CompletableFuture<Void> future;
        if (existing == null) {
            future = own;
            STRUCTURE_EXECUTOR.submit(() -> {
                long workStart = System.nanoTime();
                STRUCTURE_CACHE_LOCK.writeLock().lock();
                try {
                    cache.plotBo4Structures(rand, chunkCoord);
                    own.complete(null);
                } catch (Throwable t) {
                    own.completeExceptionally(t);
                } finally {
                    STRUCTURE_CACHE_LOCK.writeLock().unlock();
                    TOTAL_WORK_NANOS.addAndGet(System.nanoTime() - workStart);
                    IN_FLIGHT_PLOTS.remove(key, own);
                }
            });
        } else {
            future = existing;
        }
        CALL_COUNT.incrementAndGet();

        try {
            // Bounded wait: plotting is idempotent and re-entrant calls can otherwise park a
            // worker forever on its own queued task (observed as In-Flight: 96 / Rate: 0).
            future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            WorldFlow.LOGGER.warn("[WorldFlow] Structure plot for chunk {} timed out; continuing (plot is idempotent)", chunkCoord);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            WorldFlow.LOGGER.error("[WorldFlow] Error plotting structures for chunk {}", chunkCoord, cause);
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        } finally {
            TOTAL_WAIT_NANOS.addAndGet(System.nanoTime() - submitNanos);
        }
    }

    public static ChunkCoordinate executePlotSingle(CustomStructureCache cache, com.pg85.otg.customobjects.bo4.BO4 structure, java.util.ArrayList<String> biomes, ChunkCoordinate chunkCoord) {
        if (isStructureThread()) {
            return cache.plotBo4Structure(structure, biomes, chunkCoord);
        }

        Future<ChunkCoordinate> future = STRUCTURE_EXECUTOR.submit(() -> {
            return cache.plotBo4Structure(structure, biomes, chunkCoord);
        });

        try {
            return future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            WorldFlow.LOGGER.warn("[WorldFlow] Single structure plot for chunk {} timed out; continuing", chunkCoord);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            WorldFlow.LOGGER.error("[WorldFlow] Error plotting single structure for chunk {}", chunkCoord, cause);
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        }
    }
}
