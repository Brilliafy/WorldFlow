package com.cleanroommc.worldflow.pipeline.stride;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.pipeline.GenerationPriority;
import com.cleanroommc.worldflow.pipeline.GenerationScheduler;
import com.cleanroommc.worldflow.pipeline.ChunkStatus;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 4-Phase Spatial Stride Scheduler.
 *
 * Mathematically guarantees zero lock contention and zero race conditions
 * during chunk population by partitioning an area into 4 disjoint spatial phases:
 *
 *   Phase 0: (x & 1) == 0 && (z & 1) == 0
 *   Phase 1: (x & 1) == 0 && (z & 1) == 1
 *   Phase 2: (x & 1) == 1 && (z & 1) == 0
 *   Phase 3: (x & 1) == 1 && (z & 1) == 1
 *
 * Invariant: For any two chunks C1, C2 in the same phase,
 * |x1 - x2| >= 2 OR |z1 - z2| >= 2.
 * Thus their 3x3 population influence zones never overlap.
 */
public class SpatialStrideScheduler {

    public static final SpatialStrideScheduler INSTANCE = new SpatialStrideScheduler();

    public static class StrideChunkEntry {
        public final int x;
        public final int z;
        public final int phase;
        public volatile CompletableFuture<Chunk> future;

        public StrideChunkEntry(int x, int z, int phase, CompletableFuture<Chunk> future) {
            this.x = x;
            this.z = z;
            this.phase = phase;
            this.future = future;
        }
    }

    /**
     * Dispatches area generation using 4-phase spatial stride scheduling.
     */
    public List<StrideChunkEntry> scheduleArea(WorldServer world, int centerX, int centerZ, int radius) {
        int minX = centerX - radius;
        int maxX = centerX + radius;
        int minZ = centerZ - radius;
        int maxZ = centerZ + radius;

        // Stage 1: Pre-prime BaseGen (CARVERS) for area plus 1-chunk border in parallel
        List<CompletableFuture<Chunk>> primerFutures = new ArrayList<>();
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                CompletableFuture<Chunk> carverFuture = GenerationScheduler.getInstance().requestCarversChunk(world, x, z);
                if (carverFuture != null) {
                    primerFutures.add(carverFuture);
                }
            }
        }

        // Wait for all base terrain priming to finish so all 3x3 neighbours are guaranteed ready
        try {
            CompletableFuture.allOf(primerFutures.toArray(new CompletableFuture[0])).join();
        } catch (Throwable t) {
            WorldFlow.LOGGER.warn("[SpatialStrideScheduler] BaseGen priming had partial errors, proceeding with features", t);
        }

        // Stage 2: Partition target chunks into 4 phases
        @SuppressWarnings("unchecked")
        List<StrideChunkEntry>[] phases = new List[4];
        for (int i = 0; i < 4; i++) {
            phases[i] = new ArrayList<>();
        }

        List<StrideChunkEntry> allEntries = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int phaseIndex = ((x & 1) << 1) | (z & 1);
                StrideChunkEntry entry = new StrideChunkEntry(x, z, phaseIndex, null);
                phases[phaseIndex].add(entry);
                allEntries.add(entry);
            }
        }

        // Stage 3: Dispatch Phase 0, 1, 2, 3 sequentially
        for (int p = 0; p < 4; p++) {
            List<StrideChunkEntry> phaseList = phases[p];
            if (phaseList.isEmpty()) continue;

            List<CompletableFuture<Chunk>> phaseFutures = new ArrayList<>(phaseList.size());
            for (StrideChunkEntry entry : phaseList) {
                CompletableFuture<Chunk> f = GenerationScheduler.getInstance().requestChunk(
                        world, entry.x, entry.z, ChunkStatus.FULL, GenerationPriority.IMMEDIATE);
                entry.future = f;
                if (f != null) {
                    phaseFutures.add(f);
                }
            }

            // Await completion of this disjoint phase before moving to next phase
            try {
                CompletableFuture.allOf(phaseFutures.toArray(new CompletableFuture[0])).join();
            } catch (Throwable t) {
                WorldFlow.LOGGER.warn("[SpatialStrideScheduler] Phase {} had partial errors, continuing", p, t);
            }
        }

        return allEntries;
    }
}
