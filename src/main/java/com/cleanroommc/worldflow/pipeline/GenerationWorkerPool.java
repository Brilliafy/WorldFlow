package com.cleanroommc.worldflow.pipeline;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.config.ModConfig;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages thread pools for parallel chunk generation:
 * 1. General worker pool for independent stages (biomes, noise, surface, carvers, parallel-safe features).
 * 2. Dedicated serializer lever executor for SERIALIZE-marked generators (e.g. OTG/Recurrent Complex structures).
 */
public class GenerationWorkerPool {

    private static final GenerationWorkerPool INSTANCE = new GenerationWorkerPool();

    private ThreadPoolExecutor workerPool;
    private ExecutorService genLeverExecutor;
    private boolean initialized = false;

    public static GenerationWorkerPool getInstance() {
        return INSTANCE;
    }

    public synchronized void init() {
        if (this.initialized) {
            return;
        }

        int targetThreads = effectiveThreads();
        AtomicInteger workerThreadId = new AtomicInteger(1);

        this.workerPool = new ThreadPoolExecutor(
                targetThreads,
                targetThreads,
                60L, TimeUnit.SECONDS,
                new PriorityBlockingQueue<>(1024),
                r -> {
                    Thread t = new Thread(r, "WorldFlow-GenWorker-" + workerThreadId.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        this.workerPool.allowCoreThreadTimeOut(true);

        this.genLeverExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "WorldFlow-GenLever-0");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        this.initialized = true;
        WorldFlow.LOGGER.info("[WorldFlow] Generation worker pool initialized with {} parallel workers + 1 gen-lever serializer.", targetThreads);
    }

    public static int effectiveThreads() {
        if (ModConfig.CONCURRENCY != null && ModConfig.CONCURRENCY.parallelWorkerThreads > 0) {
            return Math.max(1, ModConfig.CONCURRENCY.parallelWorkerThreads);
        }
        int cores = Runtime.getRuntime().availableProcessors();
        if (cores <= 2) {
            return 1;
        }
        // Auto default: cores - 1 (capped at 8 for standard stability)
        return Math.min(8, Math.max(1, cores - 1));
    }

    public void executeWorker(Runnable command) {
        if (!this.initialized) {
            init();
        }
        this.workerPool.execute(command);
    }

    public Future<?> submitWorker(Runnable task) {
        if (!this.initialized) {
            init();
        }
        return this.workerPool.submit(task);
    }

    public <T> Future<T> submitWorker(Callable<T> task) {
        if (!this.initialized) {
            init();
        }
        return this.workerPool.submit(task);
    }

    public void executeLever(Runnable command) {
        if (!this.initialized) {
            init();
        }
        this.genLeverExecutor.execute(command);
    }

    public Future<?> submitLever(Runnable task) {
        if (!this.initialized) {
            init();
        }
        return this.genLeverExecutor.submit(task);
    }

    public void executeSerializer(Runnable command) {
        executeLever(command);
    }

    public Future<?> submitSerializer(Runnable task) {
        return submitLever(task);
    }

    public synchronized void shutdown() {
        if (this.workerPool != null) {
            this.workerPool.shutdown();
        }
        if (this.genLeverExecutor != null) {
            this.genLeverExecutor.shutdown();
        }
        this.initialized = false;
    }

    public int getActiveWorkerCount() {
        return this.workerPool != null ? this.workerPool.getActiveCount() : 0;
    }

    public int getQueueSize() {
        return this.workerPool != null ? this.workerPool.getQueue().size() : 0;
    }
}
