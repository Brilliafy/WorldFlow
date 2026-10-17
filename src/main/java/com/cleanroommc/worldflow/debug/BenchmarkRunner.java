package com.cleanroommc.worldflow.debug;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.config.ModConfig;
import com.cleanroommc.worldflow.pipeline.ChunkStatus;
import com.cleanroommc.worldflow.pipeline.GenerationPriority;
import com.cleanroommc.worldflow.pipeline.GenerationScheduler;
import com.cleanroommc.worldflow.pipeline.GenerationWorkerPool;
import com.cleanroommc.worldflow.pipeline.PublishManager;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

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

                List<CompletableFuture<Chunk>> futures = new ArrayList<>(totalChunks);
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int x = centerX + dx;
                        int z = centerZ + dz;
                        CompletableFuture<Chunk> future = GenerationScheduler.getInstance().requestChunk(
                                world, x, z, ChunkStatus.FULL, GenerationPriority.PLAYER
                        );
                        futures.add(future);
                    }
                }

                // Progress polling loop
                long lastLogMs = System.currentTimeMillis();
                while (true) {
                    int completed = 0;
                    for (CompletableFuture<Chunk> f : futures) {
                        if (f.isDone()) {
                            completed++;
                        }
                    }

                    if (completed >= totalChunks) {
                        break;
                    }

                    long now = System.currentTimeMillis();
                    if (now - lastLogMs >= 5000L) {
                        lastLogMs = now;
                        double elapsedSec = Math.max(1.0, (now - startTimeMs) / 1000.0);
                        double pct = (completed * 100.0) / totalChunks;
                        double currentRate = completed / elapsedSec;
                        send(server, sender, TextFormatting.AQUA + String.format(
                                "[WorldFlow Bench] Progress: %d/%d (%.1f%%) | Rate: %.1f chunks/s | In-Flight: %d | Queued: %d | Elapsed: %.1fs",
                                completed, totalChunks, pct, currentRate,
                                GenerationScheduler.getInstance().getInFlightCount(),
                                GenerationWorkerPool.getInstance().getQueueSize(),
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

                long totalGenerated = deltaPipeline > 0 ? deltaPipeline : totalChunks;
                double chunksPerSec = totalGenerated / elapsedSec;
                double chunksPerMin = chunksPerSec * 60.0;
                double avgBaseMs = deltaBaseGenCount > 0 ? (deltaBaseGenNanos / 1_000_000.0) / deltaBaseGenCount : 0.0;
                double avgFeatMs = deltaFeatCount > 0 ? (deltaFeatNanos / 1_000_000.0) / deltaFeatCount : 0.0;

                send(server, sender, TextFormatting.GOLD + "=== WorldFlow Pipeline Benchmark Results ===");
                send(server, sender, TextFormatting.GREEN + String.format(
                        "Area: radius %d (%d chunks) | Workers: %d",
                        radius, totalChunks, effectiveWorkers
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
                        "Generator Modes: Parallel=%d / Serialized=%d",
                        deltaParallel, deltaSerialized
                ));
                send(server, sender, TextFormatting.GRAY + String.format(
                        "Avg Times: BaseGen=%.1fms / Features=%.1fms",
                        avgBaseMs, avgFeatMs
                ));

                send(server, sender, TextFormatting.GREEN + String.format(
                        "[WorldFlow Bench] Benchmark complete! Run '/worldflow diffarea %d 4' to verify chunk bit-equivalence.",
                        radius
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
}
