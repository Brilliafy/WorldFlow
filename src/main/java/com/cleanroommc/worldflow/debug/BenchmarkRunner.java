package com.cleanroommc.worldflow.debug;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.config.ModConfig;
import com.cleanroommc.worldflow.pipeline.ChunkStatus;
import com.cleanroommc.worldflow.pipeline.GenerationPriority;
import com.cleanroommc.worldflow.pipeline.GenerationScheduler;
import com.cleanroommc.worldflow.pipeline.GenerationWorkerPool;
import com.cleanroommc.worldflow.pipeline.PublishManager;
import com.cleanroommc.worldflow.pipeline.StatusHolder;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class BenchmarkRunner {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public static void executeBenchmark(MinecraftServer server, WorldServer world, int centerX, int centerZ, int radius, int workers, int baseThreads, ICommandSender sender) {
        if (!RUNNING.compareAndSet(false, true)) {
            send(server, sender, TextFormatting.RED + "[WorldFlow Bench] A benchmark is already running! Please wait for it to complete.");
            return;
        }

        if (workers > 0) {
            GenerationWorkerPool.getInstance().setWorkerThreads(workers);
        }
        if (baseThreads > 0 && workers > baseThreads) {
            GenerationWorkerPool.getInstance().setWorkerSplit(baseThreads, workers - baseThreads);
        }
        int effectiveWorkers = GenerationWorkerPool.getInstance().getBaseGenWorkerCount()
                + GenerationWorkerPool.getInstance().getFeaturesWorkerCount();

        int totalChunks = (2 * radius + 1) * (2 * radius + 1);
        send(server, sender, TextFormatting.GOLD + String.format(
                "[WorldFlow Bench] Starting async pipeline benchmark: radius=%d (%d chunks) with %d workers centered at [%d, %d]...",
                radius, totalChunks, effectiveWorkers, centerX, centerZ
        ));

        Thread benchThread = new Thread(() -> {
            AtomicBoolean finished = new AtomicBoolean(false);
            AtomicBoolean timedOutFlag = new AtomicBoolean(false);

            try {
                long startTimeMs = System.currentTimeMillis();
                long startPublished = PublishManager.INSTANCE.totalChunksPublished.get();
                long startPipeline = GenerationScheduler.getInstance().totalChunksPipeline.get();
                long startInline = GenerationScheduler.getInstance().totalChunksInline.get();
                long startParallel = GenerationScheduler.getInstance().getParallelCount();
                long startSerialized = GenerationScheduler.getInstance().getSerializedCount();
                long startBaseGenNanos = GenerationScheduler.getInstance().getTotalBaseGenNanos();
                long startBaseGenCount = GenerationScheduler.getInstance().getTotalBaseGenCount();
                long startFeatNanos = GenerationScheduler.getInstance().getTotalFeaturesNanos();
                long startFeatCount = GenerationScheduler.getInstance().getTotalFeaturesCount();
                long startGuardsBlocked = GenerationScheduler.getInstance().getGuardsBlockedCount();
                Map<String, Long> startGenCounts = GenerationScheduler.getInstance().getSerializedGeneratorCountsSnapshot();

                // Keep the benchmark window at roughly one target per worker. The old
                // config cap (96) admitted several 3x3 dependency rings at once; the
                // carver cache then thrashed and G1 promoted thousands of transient chunks.
                int configuredInFlight = ModConfig.CONCURRENCY != null ? ModConfig.CONCURRENCY.maxInFlightGenerations : 32;
                int maxInFlight = Math.min(configuredInFlight, Math.max(32, workers > 0 ? workers : 32));
                List<BenchChunkEntry> entries = new CopyOnWriteArrayList<>();

                // Timeout at 3x expected duration (minimum 60s)
                double expectedSec = Math.max(10.0, totalChunks / 5.0);
                long timeoutMs = Math.max(60000L, (long) (expectedSec * 3.0 * 1000L));

                // Independent Watchdog Thread: started BEFORE submission loop so stalls during submission are caught
                Thread watchdogThread = new Thread(() -> {
                    long stallStartTimeMs = -1L;
                    long lastProgressMs = System.currentTimeMillis();
                    int lastCompletedCount = 0;

                    while (!finished.get() && RUNNING.get()) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            break;
                        }
                        if (finished.get() || !RUNNING.get()) {
                            break;
                        }

                        long now = System.currentTimeMillis();
                        int completed = 0;
                        for (BenchChunkEntry entry : entries) {
                            if (entry.future.isDone()) {
                                completed++;
                            }
                        }

                        if (completed > lastCompletedCount) {
                            lastCompletedCount = completed;
                            lastProgressMs = now;
                            stallStartTimeMs = -1L;
                        }

                        // Self-heal: if no completion progress for 10s, re-request every bench
                        // chunk whose future is not done and which is neither in flight nor
                        // queued. Such a chunk has an orphaned future (its holder was removed
                        // without completion) and would otherwise idle until the 3x timeout.
                        if (!finished.get() && now - lastProgressMs >= 10_000L && completed < totalChunks) {
                            int retried = 0;
                            for (BenchChunkEntry entry : entries) {
                                if (entry.future.isDone()) {
                                    continue;
                                }
                                long key = net.minecraft.util.math.ChunkPos.asLong(entry.x, entry.z);
                                if (GenerationScheduler.getInstance().getInFlight(key) != null
                                        || GenerationScheduler.getInstance().getPendingRequest(key) != null) {
                                    continue;
                                }
                                try {
                                    CompletableFuture<Chunk> retry = GenerationScheduler.getInstance().requestChunk(
                                            world, entry.x, entry.z, ChunkStatus.FULL, GenerationPriority.IMMEDIATE);
                                    if (retry != null) {
                                        entry.future = retry;
                                        retried++;
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                            if (retried > 0) {
                                WorldFlow.LOGGER.warn("[WorldFlow StallDetector] Self-heal: re-requested {} orphaned bench chunks", retried);
                            }
                            lastProgressMs = now;
                        }

                        int inFlightCount = GenerationScheduler.getInstance().getInFlightCount();
                        int queueDepth = PublishManager.INSTANCE.getPendingCount() + GenerationWorkerPool.getInstance().getQueueSize();
                        int unpublished = totalChunks - completed;

                        // Stall detector: If In-Flight == 0 && QueueDepth == 0 && unpublished > 0 for 5 s
                        if (inFlightCount == 0 && queueDepth == 0 && unpublished > 0) {
                            if (stallStartTimeMs < 0) {
                                stallStartTimeMs = now;
                            } else if (now - stallStartTimeMs >= 5000L) {
                                WorldFlow.LOGGER.warn(String.format(
                                        "[WorldFlow StallDetector] Stall detected! In-Flight: 0, QueueDepth: 0, Unpublished: %d for 5s. Dumping stuck chunks and force-publishing:",
                                        unpublished
                                ));
                                for (BenchChunkEntry entry : entries) {
                                    if (!entry.future.isDone()) {
                                        long key = net.minecraft.util.math.ChunkPos.asLong(entry.x, entry.z);
                                        StatusHolder holder = GenerationScheduler.getInstance().getInFlight(key);
                                        if (holder != null) {
                                            boolean ownDone = holder.isOwnDecorationDone();
                                            int pendingWriters = holder.getPendingWriters();
                                            List<net.minecraft.util.math.ChunkPos> owedBy = GenerationScheduler.getInstance().getOwedBy(holder);
                                            WorldFlow.LOGGER.warn(String.format(
                                                    "[WorldFlow StallDetector] Stuck chunk [%d,%d] stage=%s worker=%s ownDone=%b pendingWriters=%d futureDone=%b (workerDone=%b, carversDone=%b) owed by: %s",
                                                    entry.x, entry.z, holder.getCurrentStatus(), holder.getLastWorkerName(),
                                                    ownDone, pendingWriters, holder.getFuture().isDone(),
                                                    holder.isWorkerDone(), holder.getCarversFuture().isDone(), owedBy
                                            ));
                                            if (ownDone) {
                                                world.addScheduledTask(() -> PublishManager.INSTANCE.publishChunk(world, holder));
                                            } else {
                                                int active = GenerationWorkerPool.getInstance().getActiveWorkerCount();
                                                int qSize = GenerationWorkerPool.getInstance().getQueueSize();
                                                if (active == 0 && qSize == 0) {
                                                    GenerationScheduler.getInstance().requeueOrphaned(holder);
                                                }
                                            }
                                        } else {
                                            WorldFlow.LOGGER.warn(String.format(
                                                    "[WorldFlow StallDetector] Stuck chunk [%d,%d] stage=unknown worker=none ownDone=unknown pendingWriters=0 futureDone=false (workerDone=false, carversDone=false) owed by: [] (not in flight)",
                                                    entry.x, entry.z
                                            ));
                                            Chunk loaded = world.getChunkProvider().getLoadedChunk(entry.x, entry.z);
                                            if (loaded != null) {
                                                entry.future.complete(loaded);
                                            }
                                        }
                                        GenerationScheduler.getInstance().notifyNeighboursOfCompletion(entry.x, entry.z);
                                    }
                                }
                                stallStartTimeMs = now;
                            }
                        } else {
                            stallStartTimeMs = -1L;
                        }

                        // Timeout detector: at 3x expected duration, dump stall info and report partial results
                        if (now - startTimeMs > timeoutMs) {
                            timedOutFlag.set(true);
                            finished.set(true);
                            RUNNING.set(false);
                            WorldFlow.LOGGER.error(String.format(
                                    "[WorldFlow Bench] TIMEOUT reached after %.1fs (limit %.1fs, completed %d/%d)! Dumping stuck chunks and reporting partial results:",
                                    (now - startTimeMs) / 1000.0, timeoutMs / 1000.0, completed, totalChunks
                            ));
                            for (BenchChunkEntry entry : entries) {
                                if (!entry.future.isDone()) {
                                    long key = net.minecraft.util.math.ChunkPos.asLong(entry.x, entry.z);
                                    StatusHolder holder = GenerationScheduler.getInstance().getInFlight(key);
                                    if (holder != null) {
                                        boolean ownDone = holder.isOwnDecorationDone();
                                        int pendingWriters = holder.getPendingWriters();
                                        List<net.minecraft.util.math.ChunkPos> owedBy = GenerationScheduler.getInstance().getOwedBy(holder);
                                        WorldFlow.LOGGER.error(String.format(
                                                "[WorldFlow StallDetector] Stuck chunk [%d,%d] stage=%s worker=%s ownDone=%b pendingWriters=%d futureDone=%b (workerDone=%b, carversDone=%b) owed by: %s",
                                                entry.x, entry.z, holder.getCurrentStatus(), holder.getLastWorkerName(),
                                                ownDone, pendingWriters, holder.getFuture().isDone(),
                                                holder.isWorkerDone(), holder.getCarversFuture().isDone(), owedBy
                                        ));
                                        Chunk chunk = holder.getProtoChunk().getChunk();
                                        if (chunk == null) {
                                            chunk = new Chunk(world, entry.x, entry.z);
                                            holder.getProtoChunk().setUnderlyingChunk(chunk);
                                        }
                                        holder.setCurrentStatus(ChunkStatus.FULL);
                                        holder.setOwnDecorationDone(true);
                                        holder.getCarversFuture().complete(chunk);
                                        holder.getWorkerDoneFuture().complete(chunk);
                                        holder.getFuture().complete(chunk);
                                        entry.future.complete(chunk);
                                    } else {
                                        WorldFlow.LOGGER.error(String.format(
                                                "[WorldFlow StallDetector] Stuck chunk [%d,%d] stage=unknown worker=none ownDone=unknown pendingWriters=0 futureDone=false (workerDone=false, carversDone=false) owed by: [] (not in flight)",
                                                entry.x, entry.z
                                        ));
                                        Chunk fallback = world.getChunkProvider().getLoadedChunk(entry.x, entry.z);
                                        if (fallback == null) fallback = new Chunk(world, entry.x, entry.z);
                                        entry.future.complete(fallback);
                                    }
                                }
                            }
                            printBenchmarkReport(server, sender, world, radius, totalChunks, effectiveWorkers, centerX, centerZ,
                                    startTimeMs, startPublished, startPipeline, startInline, startParallel, startSerialized,
                                    startBaseGenNanos, startBaseGenCount, startFeatNanos, startFeatCount, startGuardsBlocked, startGenCounts,
                                    entries, true);
                            break;
                        }
                    }
                }, "WorldFlow-Bench-Watchdog");
                watchdogThread.setDaemon(true);
                watchdogThread.start();

                // Stage 1 used to prime the entire radius+1 square before any features ran.
                // That breadth-first burst allocated thousands of ProtoChunks and OTG
                // buffers that survived until the batch ended. Base terrain is now requested
                // on demand by the 3x3 feature barrier, so only the active wavefront is live.

                // Stage 2: Partition target chunks into 4 disjoint spatial stride phases
                @SuppressWarnings("unchecked")
                List<BenchChunkEntry>[] stridePhases = new List[4];
                for (int i = 0; i < 4; i++) stridePhases[i] = new ArrayList<>();
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int x = centerX + dx;
                        int z = centerZ + dz;
                        int p = ((x & 1) << 1) | (z & 1);
                        stridePhases[p].add(new BenchChunkEntry(x, z, null));
                    }
                }

                // Stage 3: Dispatch phases sequentially with non-overlapping 3x3 population windows
                long lastLogMs = System.currentTimeMillis();
                for (int p = 0; p < 4; p++) {
                    if (finished.get() || !RUNNING.get()) {
                        break;
                    }

                    List<CompletableFuture<Chunk>> phaseFutures = new ArrayList<>(stridePhases[p].size());
                    for (BenchChunkEntry entry : stridePhases[p]) {
                        if (finished.get() || !RUNNING.get()) {
                            break;
                        }

                        while (!finished.get() && RUNNING.get() && GenerationScheduler.getInstance().getInFlightCount() >= maxInFlight) {
                            try {
                                Thread.sleep(2);
                            } catch (InterruptedException ignored) {
                                break;
                            }
                        }
                        if (finished.get() || !RUNNING.get()) {
                            break;
                        }

                        entry.future = GenerationScheduler.getInstance().requestChunk(
                                world, entry.x, entry.z, ChunkStatus.FULL, GenerationPriority.IMMEDIATE
                        );
                        entries.add(entry);
                        if (entry.future != null) {
                            phaseFutures.add(entry.future);
                        }
                    }

                    // Await completion of this disjoint phase before moving to next phase
                    while (!finished.get() && RUNNING.get()) {
                        boolean allPhaseDone = true;
                        for (CompletableFuture<Chunk> pf : phaseFutures) {
                            if (!pf.isDone()) {
                                allPhaseDone = false;
                                break;
                            }
                        }
                        if (allPhaseDone) {
                            break;
                        }

                        long now = System.currentTimeMillis();
                        if (now - lastLogMs >= 2000L) {
                            lastLogMs = now;
                            int completed = 0;
                            for (BenchChunkEntry e : entries) {
                                if (e.future != null && e.future.isDone()) {
                                    completed++;
                                }
                            }
                            double elapsedSec = Math.max(0.1, (now - startTimeMs) / 1000.0);
                            double pct = (completed * 100.0) / totalChunks;
                            double currentRate = completed / elapsedSec;
                            send(server, sender, TextFormatting.AQUA + String.format(
                                    "[WorldFlow Bench] Phase %d/4 | Progress: %d/%d (%.1f%%) | Rate: %.1f chunks/s | In-Flight: %d | Queued: %d | Workers: base=%d feat=%d | Elapsed: %.1fs",
                                    p + 1, completed, totalChunks, pct, currentRate,
                                    GenerationScheduler.getInstance().getInFlightCount(),
                                    GenerationWorkerPool.getInstance().getQueueSize(),
                                    GenerationWorkerPool.getInstance().getBaseGenWorkerCount(),
                                    GenerationWorkerPool.getInstance().getFeaturesWorkerCount(),
                                    elapsedSec
                            ));
                        }

                        try {
                            Thread.sleep(25);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }

                // Final wait for all chunks to be marked complete
                while (!finished.get() && RUNNING.get()) {
                    int completed = 0;
                    for (BenchChunkEntry entry : entries) {
                        if (entry.future != null && entry.future.isDone()) {
                            completed++;
                        }
                    }

                    if (completed >= totalChunks) {
                        finished.set(true);
                        break;
                    }

                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException e) {
                        break;
                    }
                }

                finished.set(true);
                if (!timedOutFlag.get()) {
                    printBenchmarkReport(server, sender, world, radius, totalChunks, effectiveWorkers, centerX, centerZ,
                            startTimeMs, startPublished, startPipeline, startInline, startParallel, startSerialized,
                            startBaseGenNanos, startBaseGenCount, startFeatNanos, startFeatCount, startGuardsBlocked, startGenCounts,
                            entries, false);
                }

            } catch (Throwable t) {
                WorldFlow.LOGGER.error("[WorldFlow] Benchmark execution failed", t);
                send(server, sender, TextFormatting.RED + "[WorldFlow Bench] Benchmark failed: " + t.getMessage());
            } finally {
                finished.set(true);
                RUNNING.set(false);
                // Hand control back to the autoscaler now that the deterministic run is over.
                GenerationWorkerPool.getInstance().enableAutoscale();
            }
        }, "WorldFlow-Benchmark");

        benchThread.setDaemon(true);
        benchThread.start();
    }

    private static void printBenchmarkReport(
            MinecraftServer server,
            ICommandSender sender,
            WorldServer world,
            int radius,
            int totalChunks,
            int effectiveWorkers,
            int centerX,
            int centerZ,
            long startTimeMs,
            long startPublished,
            long startPipeline,
            long startInline,
            long startParallel,
            long startSerialized,
            long startBaseGenNanos,
            long startBaseGenCount,
            long startFeatNanos,
            long startFeatCount,
            long startGuardsBlocked,
            Map<String, Long> startGenCounts,
            List<BenchChunkEntry> entries,
            boolean timedOut) {
        long endTimeMs = System.currentTimeMillis();
        double elapsedSec = Math.max(0.1, (endTimeMs - startTimeMs) / 1000.0);

        long deltaPublished = PublishManager.INSTANCE.totalChunksPublished.get() - startPublished;
        long deltaPipeline = GenerationScheduler.getInstance().totalChunksPipeline.get() - startPipeline;
        long deltaInline = GenerationScheduler.getInstance().totalChunksInline.get() - startInline;
        long deltaParallel = GenerationScheduler.getInstance().getParallelCount() - startParallel;
        long deltaSerialized = GenerationScheduler.getInstance().getSerializedCount() - startSerialized;
        long deltaBaseGenCount = GenerationScheduler.getInstance().getTotalBaseGenCount() - startBaseGenCount;
        long deltaBaseGenNanos = GenerationScheduler.getInstance().getTotalBaseGenNanos() - startBaseGenNanos;
        long deltaFeatCount = GenerationScheduler.getInstance().getTotalFeaturesCount() - startFeatCount;
        long deltaFeatNanos = GenerationScheduler.getInstance().getTotalFeaturesNanos() - startFeatNanos;

        int finalCompleted = 0;
        for (BenchChunkEntry entry : entries) {
            if (entry.future.isDone()) {
                finalCompleted++;
            }
        }
        long totalGenerated = deltaPipeline > 0 ? deltaPipeline : finalCompleted;
        double chunksPerSec = totalGenerated / elapsedSec;
        double chunksPerMin = chunksPerSec * 60.0;
        double avgBaseMs = deltaBaseGenCount > 0 ? (deltaBaseGenNanos / 1_000_000.0) / deltaBaseGenCount : 0.0;
        double avgFeatMs = deltaFeatCount > 0 ? (deltaFeatNanos / 1_000_000.0) / deltaFeatCount : 0.0;

        long deltaGuardsBlocked = GenerationScheduler.getInstance().getGuardsBlockedCount() - startGuardsBlocked;
        Map<String, Long> endGenCounts = GenerationScheduler.getInstance().getSerializedGeneratorCountsSnapshot();
        Map<String, Long> deltaGenCounts = new HashMap<>();
        for (Map.Entry<String, Long> entry : endGenCounts.entrySet()) {
            long diff = entry.getValue() - startGenCounts.getOrDefault(entry.getKey(), 0L);
            if (diff > 0) {
                deltaGenCounts.put(entry.getKey(), diff);
            }
        }
        List<Map.Entry<String, Long>> top10 = deltaGenCounts.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
                .limit(10)
                .collect(Collectors.toList());

        long seed = world.getSeed();
        send(server, sender, TextFormatting.GOLD + (timedOut ? "=== WorldFlow Pipeline Benchmark PARTIAL Results (TIMED OUT) ===" : "=== WorldFlow Pipeline Benchmark Results ==="));
        send(server, sender, TextFormatting.GREEN + String.format(
                "Area: radius %d (%d chunks) | Workers: base=%d feat=%d | Center: [%d, %d] | Seed: %d",
                radius, totalChunks,
                GenerationWorkerPool.getInstance().getBaseGenWorkerCount(),
                GenerationWorkerPool.getInstance().getFeaturesWorkerCount(),
                centerX, centerZ, seed
        ));
        send(server, sender, TextFormatting.YELLOW + String.format(
                "Time Elapsed: %.2f s (%.1f chunks/s | %.0f chunks/min) | Completed: %d/%d",
                elapsedSec, chunksPerSec, chunksPerMin, finalCompleted, totalChunks
        ));
        send(server, sender, TextFormatting.AQUA + String.format(
                "Path Breakdown: pipeline=%d / inline=%d | Published: %d",
                deltaPipeline, deltaInline, deltaPublished
        ));
        send(server, sender, TextFormatting.WHITE + String.format(
                "Generator Modes: Parallel=%d / Serialized=%d | Guards blocked: %d",
                deltaParallel, deltaSerialized, deltaGuardsBlocked
        ));
        send(server, sender, TextFormatting.GRAY + String.format(
                "Avg Times: BaseGen=%.1fms / Features=%.1fms",
                avgBaseMs, avgFeatMs
        ));

        send(server, sender, TextFormatting.GOLD + "Top Serialized Generators:");
        if (top10.isEmpty()) {
            send(server, sender, TextFormatting.GRAY + "  None (all generators ran parallel)");
        } else {
            int rank = 1;
            for (Map.Entry<String, Long> entry : top10) {
                send(server, sender, TextFormatting.YELLOW + String.format(
                        "  #%d [%d calls] %s", rank++, entry.getValue(), entry.getKey()
                ));
            }
        }

        if (!timedOut) {
            send(server, sender, TextFormatting.GREEN + String.format(
                    "[WorldFlow Bench] Benchmark complete! Run '/worldflow diffarea %d 4 %d %d' to verify chunk bit-equivalence.",
                    radius, centerX, centerZ
            ));
        }
    }

    private static void send(MinecraftServer server, ICommandSender sender, String message) {
        WorldFlow.LOGGER.info(TextFormatting.getTextWithoutFormattingCodes(message));
        if (server != null && sender != null) {
            try {
                if (server.isCallingFromMinecraftThread() || !(sender instanceof net.minecraft.entity.player.EntityPlayerMP)) {
                    sender.sendMessage(new TextComponentString(message));
                } else {
                    server.addScheduledTask(() -> sender.sendMessage(new TextComponentString(message)));
                }
            } catch (Throwable ignored) {
            }
        } else if (sender != null) {
            sender.sendMessage(new TextComponentString(message));
        }
    }

    private static class BenchChunkEntry {
        final int x;
        final int z;
        volatile CompletableFuture<Chunk> future;

        BenchChunkEntry(int x, int z, CompletableFuture<Chunk> future) {
            this.x = x;
            this.z = z;
            this.future = future;
        }
    }
}
