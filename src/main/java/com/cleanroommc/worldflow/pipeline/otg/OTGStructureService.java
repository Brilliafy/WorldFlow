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

    public static void executePlot(CustomStructureCache cache, Random rand, ChunkCoordinate chunkCoord) {
        if (isStructureThread()) {
            cache.plotBo4Structures(rand, chunkCoord);
            return;
        }

        Future<?> future = STRUCTURE_EXECUTOR.submit(() -> {
            cache.plotBo4Structures(rand, chunkCoord);
        });

        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            WorldFlow.LOGGER.error("[WorldFlow] Error plotting structures for chunk {}", chunkCoord, cause);
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
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
            return future.get();
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
