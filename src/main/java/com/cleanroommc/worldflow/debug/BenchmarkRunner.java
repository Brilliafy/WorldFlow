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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class BenchmarkRunner {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public static void executeBenchmark(MinecraftServer server, WorldServer world, int centerX, int centerZ, int radius, int workers, ICommandSender sender) {
        if (!RUNNING.compareAndSet(false, true)) {
            send(server, sender, TextFormatting.RED + "[WorldFlow Bench] A benchmark is already running! Please wait for it to complete.");
            return;
        }

        if (workers > 0) {
            GenerationWorkerPool.getInstance().setWorkerThreads(workers);
        }
        int effectiveWorkers = GenerationWorkerPool.effectiveThreads();

        int totalChunks = (2 * radius + 1) * (2 * radius + 1);
        send(server, sender, TextFormatting.GOLD + String.format(
                "[WorldFlow Bench] Starting async pipeline benchmark: radius=%d (%d chunks) with %d workers centered at [%d, %d]...",
                radius, totalChunks, effectiveWorkers, centerX, centerZ
        ));

        Thread benchThread = new Thread(() -> {
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

                int maxInFlight = ModConfig.CONCURRENCY != null ? ModConfig.CONCURRENCY.maxInFlightGenerations : 32;
                List<BenchChunkEntry> entries = new ArrayList<>(totalChunks);
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int x = centerX + dx;
                        int z = centerZ + dz;

                        // Bench backpressure: respect maxInFlight cap
                        while (GenerationScheduler.getInstance().getInFlightCount() >= maxInFlight) {
                            try {
                                Thread.sleep(5);
                            } catch (InterruptedException ignored) {
                                break;
                            }
                        }

                        CompletableFuture<Chunk> future = GenerationScheduler.getInstance().requestChunk(
                                world, x, z, ChunkStatus.FULL, GenerationPriority.PLAYER
                        );
                        entries.add(new BenchChunkEntry(x, z, future));
                    }
                }

                // Timeout at 3x expected duration (minimum 60s)
                double expectedSec = Math.max(10.0, totalChunks / 5.0);
                long timeoutMs = Math.max(60000L, (long) (expectedSec * 3.0 * 1000L));

                // Progress polling loop
                long lastLogMs = System.currentTimeMillis();
                int lastCompleted = 0;
                long stallStartTimeMs = -1L;
                boolean timedOut = false;

                while (true) {
                    int completed = 0;
                    for (BenchChunkEntry entry : entries) {
                        if (entry.future.isDone()) {
                            completed++;
                        }
                    }

                    if (completed >= totalChunks) {
                        break;
                    }

                    long now = System.currentTimeMillis();

                    if (completed > lastCompleted) {
                        lastCompleted = completed;
                        stallStartTimeMs = -1L;
                    }

                    int inFlightCount = GenerationScheduler.getInstance().getInFlightCount();
                    int queueDepth = PublishManager.INSTANCE.getPendingCount() + GenerationWorkerPool.getInstance().getQueueSize();
                    int unpublished = totalChunks - completed;

                    // Stall detector: If In-Flight == 0 && QueueDepth == 0 && unpublished > 0 for 5 s
                    if (inFlightCount == 0 && queueDepth == 0 && unpublished > 0) {
                        if (stallStartTimeMs < 0) {
                            stallStartTimeMs = now;
                        } else if (now - stallStartTimeMs >= 5000L) {
                            send(server, sender, TextFormatting.RED + String.format(
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
                                        send(server, sender, TextFormatting.RED + String.format(
                                                "[WorldFlow StallDetector] Stuck chunk [%d,%d] ownDone=%b pendingWriters=%d owed by: %s",
                                                entry.x, entry.z, ownDone, pendingWriters, owedBy
                                        ));
                                        if (holder.markPublishEnqueued()) {
                                            PublishManager.INSTANCE.publishChunk(world, holder);
                                        }
                                        if (holder.getFuture().isDone()) {
                                            try {
                                                entry.future.complete(holder.getFuture().getNow(null));
                                            } catch (Throwable ignored) {}
                                        }
                                    } else {
                                        Chunk loaded = world.getChunkProvider().getLoadedChunk(entry.x, entry.z);
                                        if (loaded == null) {
                                            loaded = world.getChunkProvider().provideChunk(entry.x, entry.z);
                                        }
                                        send(server, sender, TextFormatting.RED + String.format(
                                                "[WorldFlow StallDetector] Stuck chunk [%d,%d] ownDone=true pendingWriters=0 owed by: [] (recovered via provider)",
                                                entry.x, entry.z
                                        ));
                                        entry.future.complete(loaded);
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
                        timedOut = true;
                        send(server, sender, TextFormatting.RED + String.format(
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
                                    send(server, sender, TextFormatting.RED + String.format(
                                            "[WorldFlow StallDetector] Stuck chunk [%d,%d] ownDone=%b pendingWriters=%d owed by: %s",
                                            entry.x, entry.z, ownDone, pendingWriters, owedBy
                                    ));
                                } else {
                                    send(server, sender, TextFormatting.RED + String.format(
                                            "[WorldFlow StallDetector] Stuck chunk [%d,%d] ownDone=unknown pendingWriters=0 owed by: [] (not in flight)",
                                            entry.x, entry.z
                                    ));
                                }
                            }
                        }
                        break;
                    }

                    if (now - lastLogMs >= 5000L) {
                        lastLogMs = now;
                        double elapsedSec = Math.max(1.0, (now - startTimeMs) / 1000.0);
                        double pct = (completed * 100.0) / totalChunks;
                        double currentRate = completed / elapsedSec;
                        send(server, sender, TextFormatting.AQUA + String.format(
                                "[WorldFlow Bench] Progress: %d/%d (%.1f%%) | Rate: %.1f chunks/s | In-Flight: %d | Queued: %d | Guards blocked: %d | Elapsed: %.1fs",
                                completed, totalChunks, pct, currentRate,
                                GenerationScheduler.getInstance().getInFlightCount(),
                                GenerationWorkerPool.getInstance().getQueueSize(),
                                GenerationScheduler.getInstance().getGuardsBlockedCount(),
                                elapsedSec
                        ));
                    }

                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }

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
                long totalGenerated = deltaPipeline > 0 ? deltaPipeline : (timedOut ? finalCompleted : totalChunks);
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
                send(server, sender, TextFormatting.GOLD + "=== WorldFlow Pipeline Benchmark Results ===");
                send(server, sender, TextFormatting.GREEN + String.format(
                        "Area: radius %d (%d chunks) | Workers: %d | Center: [%d, %d] | Seed: %d",
                        radius, totalChunks, effectiveWorkers, centerX, centerZ, seed
                ));
                send(server, sender, TextFormatting.YELLOW + String.format(
                        "Time Elapsed: %.2f s (%.1f chunks/s | %.0f chunks/min)",
                        elapsedSec, chunksPerSec, chunksPerMin
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

                send(server, sender, TextFormatting.GREEN + String.format(
                        "[WorldFlow Bench] Benchmark complete! Run '/worldflow diffarea %d 4 %d %d' to verify chunk bit-equivalence.",
                        radius, centerX, centerZ
                ));

            } catch (Throwable t) {
                WorldFlow.LOGGER.error("[WorldFlow] Benchmark execution failed", t);
                send(server, sender, TextFormatting.RED + "[WorldFlow Bench] Benchmark failed: " + t.getMessage());
            } finally {
                RUNNING.set(false);
            }
        }, "WorldFlow-Benchmark");

        benchThread.setDaemon(true);
        benchThread.start();
    }

    private static void send(MinecraftServer server, ICommandSender sender, String message) {
        WorldFlow.LOGGER.info(TextFormatting.getTextWithoutFormattingCodes(message));
        if (server != null) {
            server.addScheduledTask(() -> sender.sendMessage(new TextComponentString(message)));
        } else {
            sender.sendMessage(new TextComponentString(message));
        }
    }

    private static class BenchChunkEntry {
        final int x;
        final int z;
        final CompletableFuture<Chunk> future;

        BenchChunkEntry(int x, int z, CompletableFuture<Chunk> future) {
            this.x = x;
            this.z = z;
            this.future = future;
        }
    }
}
