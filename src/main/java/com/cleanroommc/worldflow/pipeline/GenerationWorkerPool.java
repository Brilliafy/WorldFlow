package com.cleanroommc.worldflow.pipeline;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.config.ModConfig;

import java.util.Comparator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages thread pools for parallel chunk generation:
 * 1. Dedicated BaseGen pool for pure-compute independent stages (biomes, noise, surface, carvers).
 * 2. Dedicated Features pool for feature population (ores, flora, structures).
 * 3. Dedicated GenLever pool for SERIALIZE-marked generators.
 * 4. Dedicated Scheduler for timeouts and Deadlock Watchdog.
 */
public class GenerationWorkerPool {

    private static final GenerationWorkerPool INSTANCE = new GenerationWorkerPool();

    private ThreadPoolExecutor baseGenPool;
    private ThreadPoolExecutor featuresPool;
    private ExecutorService genLeverExecutor;
    private ScheduledExecutorService scheduler;
    private boolean initialized = false;

    private final AtomicInteger baseGenHighCount = new AtomicInteger(0);
    private final AtomicInteger baseGenEmptyCount = new AtomicInteger(0);
    private final AtomicInteger featuresHighCount = new AtomicInteger(0);
    private final AtomicInteger featuresEmptyCount = new AtomicInteger(0);

    public static class PrioritizedTask implements Runnable, Comparable<PrioritizedTask> {
        private final Runnable task;
        private final int priority;
        private final long submissionTimeNanos;

        public PrioritizedTask(Runnable task, int priority) {
            this.task = task;
            this.priority = priority;
            this.submissionTimeNanos = System.nanoTime();
        }

        public int getPriority() {
            return this.priority;
        }

        public long getSubmissionTimeNanos() {
            return this.submissionTimeNanos;
        }

        @Override
        public void run() {
            this.task.run();
        }

        @Override
        public int compareTo(PrioritizedTask o) {
            int cmp = Integer.compare(o.priority, this.priority);
            if (cmp != 0) {
                return cmp;
            }
            return Long.compare(this.submissionTimeNanos, o.submissionTimeNanos);
        }
    }

    public static GenerationWorkerPool getInstance() {
        return INSTANCE;
    }

    public synchronized void init() {
        if (this.initialized) {
            return;
        }

        int maxTotal = getMaxTotalWorkers();
        int initialBase = Math.max(1, Math.min(2, maxTotal / 2));
        int initialFeat = Math.max(1, Math.min(2, maxTotal / 2));

        AtomicInteger baseGenThreadId = new AtomicInteger(1);
        AtomicInteger featuresThreadId = new AtomicInteger(1);

        Comparator<Runnable> taskComparator = (r1, r2) -> {
            int p1 = (r1 instanceof PrioritizedTask) ? ((PrioritizedTask) r1).getPriority() : 0;
            int p2 = (r2 instanceof PrioritizedTask) ? ((PrioritizedTask) r2).getPriority() : 0;
            int cmp = Integer.compare(p2, p1);
            if (cmp != 0) {
                return cmp;
            }
            long t1 = (r1 instanceof PrioritizedTask) ? ((PrioritizedTask) r1).getSubmissionTimeNanos() : 0L;
            long t2 = (r2 instanceof PrioritizedTask) ? ((PrioritizedTask) r2).getSubmissionTimeNanos() : 0L;
            return Long.compare(t1, t2);
        };

        this.baseGenPool = new ThreadPoolExecutor(
                initialBase,
                maxTotal,
                60L, TimeUnit.SECONDS,
                new PriorityBlockingQueue<>(1024, taskComparator),
                r -> {
                    Thread t = new Thread(r, "WorldFlow-BaseGen-" + baseGenThreadId.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        this.baseGenPool.allowCoreThreadTimeOut(true);

        this.featuresPool = new ThreadPoolExecutor(
                initialFeat,
                maxTotal,
                60L, TimeUnit.SECONDS,
                new PriorityBlockingQueue<>(1024, taskComparator),
                r -> {
                    Thread t = new Thread(r, "WorldFlow-Features-" + featuresThreadId.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        this.featuresPool.allowCoreThreadTimeOut(true);

        this.genLeverExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "WorldFlow-GenLever-0");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "WorldFlow-Scheduler-0");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 2);
            return t;
        });

        // Start autoscaler periodic tick (every 1 second)
        this.scheduler.scheduleWithFixedDelay(this::tickAutoscaler, 1, 1, TimeUnit.SECONDS);

        this.initialized = true;
        WorldFlow.LOGGER.info("[WorldFlow] Generation worker pools initialized: {} BaseGen + {} Features workers (max total {}), 1 gen-lever, 1 scheduler.",
                initialBase, initialFeat, maxTotal);
    }

    public static int detectPhysicalCores() {
        int logical = Runtime.getRuntime().availableProcessors();
        try {
            java.io.File cpuinfo = new java.io.File("/proc/cpuinfo");
            if (cpuinfo.exists()) {
                java.util.Set<String> coreIds = new java.util.HashSet<>();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(cpuinfo))) {
                    String line;
                    String physicalId = "0";
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("physical id")) {
                            physicalId = line.substring(line.indexOf(':') + 1).trim();
                        } else if (line.startsWith("core id")) {
                            String coreId = line.substring(line.indexOf(':') + 1).trim();
                            coreIds.add(physicalId + ":" + coreId);
                        }
                    }
                }
                if (!coreIds.isEmpty()) {
                    return Math.max(1, coreIds.size());
                }
            }
        } catch (Throwable ignored) {
        }
        return logical <= 2 ? 1 : Math.max(2, logical / 2);
    }

    public static int getMaxTotalWorkers() {
        if (ModConfig.CONCURRENCY != null && ModConfig.CONCURRENCY.parallelWorkerThreads > 0) {
            return ModConfig.CONCURRENCY.parallelWorkerThreads;
        }
        return Math.min(6, detectPhysicalCores() + 2);
    }

    public static int effectiveThreads() {
        return getMaxTotalWorkers();
    }

    private void tickAutoscaler() {
        if (!this.initialized || this.baseGenPool == null || this.featuresPool == null) {
            return;
        }

        int currentBase = this.baseGenPool.getCorePoolSize();
        int currentFeat = this.featuresPool.getCorePoolSize();
        int baseQueue = this.baseGenPool.getQueue().size();
        int featQueue = this.featuresPool.getQueue().size();

        int maxTotal = getMaxTotalWorkers();

        // Scaling BaseGen:
        if (baseQueue > 2 * currentBase) {
            this.baseGenEmptyCount.set(0);
            if (this.baseGenHighCount.incrementAndGet() >= 2) {
                this.baseGenHighCount.set(0);
                if (currentBase + currentFeat < maxTotal && currentBase < 4) {
                    int next = currentBase + 1;
                    resizePool(this.baseGenPool, next);
                    WorldFlow.LOGGER.info("[WorldFlow Autoscaler] Scaled BaseGen pool: {} -> {} (queue: {})", currentBase, next, baseQueue);
                }
            }
        } else if (baseQueue == 0) {
            this.baseGenHighCount.set(0);
            if (this.baseGenEmptyCount.incrementAndGet() >= 5) {
                this.baseGenEmptyCount.set(0);
                if (currentBase > 1) {
                    int next = currentBase - 1;
                    resizePool(this.baseGenPool, next);
                    WorldFlow.LOGGER.info("[WorldFlow Autoscaler] Scaled BaseGen pool: {} -> {} (idle)", currentBase, next);
                }
            }
        } else {
            this.baseGenHighCount.set(0);
            this.baseGenEmptyCount.set(0);
        }

        // Re-read currentBase for features scaling check
        currentBase = this.baseGenPool.getCorePoolSize();

        // Scaling Features:
        if (featQueue > 2 * currentFeat) {
            this.featuresEmptyCount.set(0);
            if (this.featuresHighCount.incrementAndGet() >= 2) {
                this.featuresHighCount.set(0);
                if (currentBase + currentFeat < maxTotal && currentFeat < 4) {
                    int next = currentFeat + 1;
                    resizePool(this.featuresPool, next);
                    WorldFlow.LOGGER.info("[WorldFlow Autoscaler] Scaled Features pool: {} -> {} (queue: {})", currentFeat, next, featQueue);
                }
            }
        } else if (featQueue == 0) {
            this.featuresHighCount.set(0);
            if (this.featuresEmptyCount.incrementAndGet() >= 5) {
                this.featuresEmptyCount.set(0);
                if (currentFeat > 1) {
                    int next = currentFeat - 1;
                    resizePool(this.featuresPool, next);
                    WorldFlow.LOGGER.info("[WorldFlow Autoscaler] Scaled Features pool: {} -> {} (idle)", currentFeat, next);
                }
            }
        } else {
            this.featuresHighCount.set(0);
            this.featuresEmptyCount.set(0);
        }
    }

    public synchronized void setWorkerThreads(int count) {
        if (!this.initialized) {
            init();
        }
        int target = Math.max(1, count);
        int half = Math.max(1, target / 2);
        resizePool(this.baseGenPool, half);
        resizePool(this.featuresPool, target - half > 0 ? target - half : 1);
        if (ModConfig.CONCURRENCY != null) {
            ModConfig.CONCURRENCY.parallelWorkerThreads = target;
        }
        WorldFlow.LOGGER.info("[WorldFlow] Resized generation worker pools to {} BaseGen and {} Features threads.",
                this.baseGenPool.getCorePoolSize(), this.featuresPool.getCorePoolSize());
    }

    private void resizePool(ThreadPoolExecutor pool, int target) {
        if (pool != null) {
            if (target > pool.getMaximumPoolSize()) {
                pool.setMaximumPoolSize(target);
                pool.setCorePoolSize(target);
            } else {
                pool.setCorePoolSize(target);
                pool.setMaximumPoolSize(target);
            }
        }
    }

    public int getBaseGenWorkerCount() {
        return this.baseGenPool != null ? this.baseGenPool.getCorePoolSize() : 0;
    }

    public int getFeaturesWorkerCount() {
        return this.featuresPool != null ? this.featuresPool.getCorePoolSize() : 0;
    }

    public void executeBaseGen(Runnable command) {
        executeBaseGen(command, 0);
    }

    public void executeBaseGen(Runnable command, int priority) {
        if (!this.initialized) {
            init();
        }
        Runnable r = (command instanceof PrioritizedTask) ? command : new PrioritizedTask(command, priority);
        this.baseGenPool.execute(r);
    }

    public void executeBaseGen(StatusHolder holder, Runnable command) {
        int priority = holder != null ? holder.getPriorityScore() : 0;
        executeBaseGen(command, priority);
    }

    public void executeFeatures(Runnable command) {
        executeFeatures(command, 0);
    }

    public void executeFeatures(Runnable command, int priority) {
        if (!this.initialized) {
            init();
        }
        Runnable r = (command instanceof PrioritizedTask) ? command : new PrioritizedTask(command, priority);
        this.featuresPool.execute(r);
    }

    public void executeFeatures(StatusHolder holder, Runnable command) {
        int priority = holder != null ? holder.getPriorityScore() : 0;
        executeFeatures(command, priority);
    }

    public void executeWorker(Runnable command) {
        executeFeatures(command, 0);
    }

    public void executeWorker(Runnable command, int priority) {
        executeFeatures(command, priority);
    }

    public void executeWorker(StatusHolder holder, Runnable command) {
        executeFeatures(holder, command);
    }

    public Future<?> submitWorker(Runnable task) {
        if (!this.initialized) {
            init();
        }
        return this.featuresPool.submit(new PrioritizedTask(task, 0));
    }

    public <T> Future<T> submitWorker(Callable<T> task) {
        if (!this.initialized) {
            init();
        }
        return this.featuresPool.submit(task);
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

    public void scheduleTimeout(CompletableFuture<?> future, long timeout, TimeUnit unit) {
        if (!this.initialized) {
            init();
        }
        if (future.isDone()) {
            return;
        }
        this.scheduler.schedule(() -> {
            if (!future.isDone()) {
                future.completeExceptionally(new TimeoutException("Timed out after " + timeout + " " + unit));
            }
        }, timeout, unit);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        if (!this.initialized) {
            init();
        }
        return this.scheduler.scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }

    public synchronized void shutdown() {
        if (this.baseGenPool != null) {
            this.baseGenPool.shutdown();
        }
        if (this.featuresPool != null) {
            this.featuresPool.shutdown();
        }
        if (this.genLeverExecutor != null) {
            this.genLeverExecutor.shutdown();
        }
        if (this.scheduler != null) {
            this.scheduler.shutdown();
        }
        this.initialized = false;
    }

    public int getBaseGenActiveCount() {
        return this.baseGenPool != null ? this.baseGenPool.getActiveCount() : 0;
    }

    public int getBaseGenQueueSize() {
        return this.baseGenPool != null ? this.baseGenPool.getQueue().size() : 0;
    }

    public int getFeaturesActiveCount() {
        return this.featuresPool != null ? this.featuresPool.getActiveCount() : 0;
    }

    public int getFeaturesQueueSize() {
        return this.featuresPool != null ? this.featuresPool.getQueue().size() : 0;
    }

    public int getLeverActiveCount() {
        if (this.genLeverExecutor instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) this.genLeverExecutor).getActiveCount();
        }
        return 0;
    }

    public int getLeverQueueSize() {
        if (this.genLeverExecutor instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) this.genLeverExecutor).getQueue().size();
        }
        return 0;
    }

    public int getActiveWorkerCount() {
        return getBaseGenActiveCount() + getFeaturesActiveCount();
    }

    public int getQueueSize() {
        return getBaseGenQueueSize() + getFeaturesQueueSize();
    }
}
