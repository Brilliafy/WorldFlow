package com.cleanroommc.worldflow.pipeline;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.config.ModConfig;

import java.util.Comparator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

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

    /** Autoscaling is enabled by default; benchmarks and manual resizes pin it. */
    private volatile boolean autoscaleEnabled = true;
    private volatile int manualSplitBase = -1;
    private volatile int manualSplitFeatures = -1;
    private volatile int idleTicks = 0;
    private volatile IntSupplier inFlightSupplier = () -> 0;

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
        // Start minimal; the autoscaler ramps to max the moment there is work.
        int initialBase = 1;
        int initialFeat = 1;

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
        if (!this.autoscaleEnabled || this.manualSplitBase > 0) {
            return;
        }

        int cap = getMaxTotalWorkers();
        int currentBase = this.baseGenPool.getCorePoolSize();
        int currentFeat = this.featuresPool.getCorePoolSize();

        int baseQueue = this.baseGenPool.getQueue().size();
        int featQueue = this.featuresPool.getQueue().size();
        int inFlight = this.inFlightSupplier.getAsInt();

        // Global load: queued work in either pool plus chunks still in the pipeline.
        // Per-pool "idle" is meaningless here: BaseGen is legitimately idle while Features is busy.
        int load = baseQueue + featQueue + inFlight;

        if (load <= 0) {
            this.idleTicks++;
            // Shrink only after a long quiet period, and never below a small floor.
            if (this.idleTicks >= 10) {
                int floor = Math.min(2, Math.max(1, cap / 3));
                int wantFeat = Math.max(1, floor);
                int wantBase = Math.max(1, floor);
                if (currentBase > wantBase || currentFeat > wantFeat) {
                    resizePool(this.baseGenPool, wantBase);
                    resizePool(this.featuresPool, wantFeat);
                    WorldFlow.LOGGER.info("[WorldFlow Autoscaler] idle {}s -> shrink to base={} feat={}", this.idleTicks, wantBase, wantFeat);
                }
                this.idleTicks = 0;
            }
            return;
        }

        this.idleTicks = 0;

        // Demand: as long as anything is in flight, use the full budget. Features dominate the
        // per-chunk cost (structure plotting + decoration), so bias the split toward Features.
        int wantTotal = Math.min(cap, Math.max(2, Math.min(cap, load + 1)));
        int wantFeat = Math.max(1, (wantTotal * 2) / 3);
        int wantBase = Math.max(1, wantTotal - wantFeat);
        if (wantBase + wantFeat > cap) {
            wantFeat = Math.max(1, cap - wantBase);
        }

        if (wantBase != currentBase || wantFeat != currentFeat) {
            resizePool(this.baseGenPool, wantBase);
            resizePool(this.featuresPool, wantFeat);
            WorldFlow.LOGGER.info("[WorldFlow Autoscaler] load={} (q={}+{}, inFlight={}) -> base={} feat={}",
                    load, baseQueue, featQueue, inFlight, wantBase, wantFeat);
        }
    }

    /** Give the autoscaler the pipeline's in-flight count (set once by the scheduler). */
    public void setInFlightSupplier(IntSupplier supplier) {
        if (supplier != null) {
            this.inFlightSupplier = supplier;
        }
    }

    public void enableAutoscale() {
        this.manualSplitBase = -1;
        this.manualSplitFeatures = -1;
        this.autoscaleEnabled = true;
        this.idleTicks = 0;
    }

    public void disableAutoscale() {
        this.autoscaleEnabled = false;
    }

    public boolean isAutoscaleEnabled() {
        return this.autoscaleEnabled;
    }

    public synchronized void setWorkerThreads(int count) {
        if (!this.initialized) {
            init();
        }
        int target = Math.max(1, count);
        // Features dominate the per-chunk cost (structure plotting + decoration), so give them
        // ~2/3 of the budget: base/feat = 1/1, 1/3, 2/5 for 2, 4, 7 threads.
        // Decoration (features) dominates per-chunk cost, especially in structure-dense areas,
        // so bias the split to 1/3 base, 2/3 features.
        int base = Math.max(1, target / 3);
        int feat = Math.max(1, target - base);
        // Pin the split: benchmarks and manual resizes must be deterministic, so the
        // autoscaler stands down until enableAutoscale() is called again.
        this.autoscaleEnabled = false;
        this.manualSplitBase = base;
        this.manualSplitFeatures = feat;
        resizePool(this.baseGenPool, base);
        resizePool(this.featuresPool, feat);
        WorldFlow.LOGGER.info("[WorldFlow] Resized generation worker pools to {} BaseGen and {} Features threads (manual, autoscale paused).",
                base, feat);
    }

    /** Explicit base/feature split for benchmarking; pins the autoscaler. */
    public synchronized void setWorkerSplit(int base, int features) {
        if (!this.initialized) {
            init();
        }
        int b = Math.max(1, base);
        int f = Math.max(1, features);
        this.autoscaleEnabled = false;
        this.manualSplitBase = b;
        this.manualSplitFeatures = f;
        resizePool(this.baseGenPool, b);
        resizePool(this.featuresPool, f);
        WorldFlow.LOGGER.info("[WorldFlow] Worker split set to {} BaseGen / {} Features (manual).", b, f);
    }

    /** Total workers the autoscaler is allowed to use. */
    public int getWorkerCap() {
        return getMaxTotalWorkers();
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
