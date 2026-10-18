package com.cleanroommc.worldflow.pipeline;

import com.cleanroommc.worldflow.WorldFlow;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server kill-switch watchdog (WORLD_FLOW_STALL).
 * Monitors the main server tick thread. If the main server thread remains parked / waiting
 * inside parallel pipeline chunk provision code for > 60 seconds, dumps thread stack traces
 * of "Server thread" and all "WorldFlow-*" workers, aborts/interrupts the stalled request,
 * and completes the chunk future so the server never wedges and remains responsive to /stop.
 */
public class ServerStallWatchdog {

    private static final long STALL_TIMEOUT_MS = 60_000L; // 60 seconds

    private static final AtomicLong MAIN_THREAD_WAIT_START = new AtomicLong(0L);
    private static final AtomicReference<ChunkPos> STALLED_CHUNK_POS = new AtomicReference<>(null);
    private static volatile Thread mainServerThread = null;
    private static final AtomicBoolean ABORT_TRIGGERED = new AtomicBoolean(false);

    private static final ScheduledExecutorService WATCHDOG_EXECUTOR = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "WorldFlow-StallWatchdog");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        }
    });

    static {
        WATCHDOG_EXECUTOR.scheduleWithFixedDelay(ServerStallWatchdog::checkServerThreadStall, 1, 1, TimeUnit.SECONDS);
    }

    public static void init() {
        // Classloading trigger
    }

    public static void recordMainThreadWaitStart(int x, int z) {
        mainServerThread = Thread.currentThread();
        STALLED_CHUNK_POS.set(new ChunkPos(x, z));
        ABORT_TRIGGERED.set(false);
        MAIN_THREAD_WAIT_START.set(System.currentTimeMillis());
    }

    public static void recordMainThreadWaitEnd() {
        MAIN_THREAD_WAIT_START.set(0L);
        STALLED_CHUNK_POS.set(null);
    }

    private static void checkServerThreadStall() {
        long start = MAIN_THREAD_WAIT_START.get();
        if (start <= 0L) {
            return;
        }

        long elapsed = System.currentTimeMillis() - start;
        if (elapsed >= STALL_TIMEOUT_MS && ABORT_TRIGGERED.compareAndSet(false, true)) {
            ChunkPos pos = STALLED_CHUNK_POS.get();
            int cx = pos != null ? pos.x : 0;
            int cz = pos != null ? pos.z : 0;

            WorldFlow.LOGGER.error("================================================================================");
            WorldFlow.LOGGER.error(String.format(
                    "[WORLD_FLOW_STALL] Main server thread has been waiting in pipeline for %.1f seconds on chunk [%d, %d]!",
                    elapsed / 1000.0, cx, cz
            ));
            WorldFlow.LOGGER.error("[WORLD_FLOW_STALL] Dumping thread stacks for 'Server thread' and 'WorldFlow-*':");

            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Thread t = entry.getKey();
                String name = t.getName();
                if ("Server thread".equals(name) || name.startsWith("WorldFlow-")) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("\nThread '%s' (id=%d, state=%s, daemon=%b):\n", name, t.getId(), t.getState(), t.isDaemon()));
                    for (StackTraceElement ste : entry.getValue()) {
                        sb.append("    at ").append(ste.toString()).append("\n");
                    }
                    WorldFlow.LOGGER.error(sb.toString());
                }
            }
            WorldFlow.LOGGER.error("================================================================================");

            // Abort and interrupt the stalled generation request to keep server responsive to /stop
            long key = ChunkPos.asLong(cx, cz);
            StatusHolder holder = GenerationScheduler.getInstance().getInFlight(key);
            if (holder != null) {
                WorldFlow.LOGGER.warn("[WORLD_FLOW_STALL] Force-completing stuck chunk [{}, {}] to resume server thread.", cx, cz);
                Chunk chunk = holder.getProtoChunk().getChunk();
                if (chunk == null) {
                    chunk = new Chunk(holder.getWorld(), cx, cz);
                    holder.getProtoChunk().setUnderlyingChunk(chunk);
                }
                holder.getWorkerDoneFuture().complete(chunk);
                holder.getFuture().complete(chunk);
                holder.setOwnDecorationDone(true);
                GenerationScheduler.getInstance().notifyNeighboursOfCompletion(cx, cz);
            }

            Thread serverThread = mainServerThread;
            if (serverThread != null && serverThread.isAlive()) {
                WorldFlow.LOGGER.warn("[WORLD_FLOW_STALL] Interrupting main server thread to break out of wait.");
                serverThread.interrupt();
            }
        }
    }
}
