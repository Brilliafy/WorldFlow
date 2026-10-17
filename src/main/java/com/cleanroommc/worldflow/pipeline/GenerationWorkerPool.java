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

        int targetThreads = effectiveThreads();
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
                targetThreads,
                targetThreads,
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
                targetThreads,
                targetThreads,
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

        this.initialized = true;
        WorldFlow.LOGGER.info("[WorldFlow] Generation worker pools initialized: {} BaseGen + {} Features workers, 1 gen-lever, 1 scheduler.", targetThreads, targetThreads);
    }

    public static int effectiveThreads() {
        if (ModConfig.CONCURRENCY != null && ModConfig.CONCURRENCY.parallelWorkerThreads > 0) {
            return Math.max(1, ModConfig.CONCURRENCY.parallelWorkerThreads);
        }
        int cores = Runtime.getRuntime().availableProcessors();
        if (cores <= 2) {
            return 1;
        }
        return Math.min(8, Math.max(1, cores - 1));
    }

    public synchronized void setWorkerThreads(int count) {
        if (!this.initialized) {
            init();
        }
        int target = Math.max(1, count);
        resizePool(this.baseGenPool, target);
        resizePool(this.featuresPool, target);
        if (ModConfig.CONCURRENCY != null) {
            ModConfig.CONCURRENCY.parallelWorkerThreads = target;
        }
        WorldFlow.LOGGER.info("[WorldFlow] Resized generation worker pools to {} BaseGen and {} Features threads.", target, target);
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
