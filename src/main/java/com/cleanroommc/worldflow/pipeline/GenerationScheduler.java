package com.cleanroommc.worldflow.pipeline;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.config.ModConfig;
import com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import com.cleanroommc.worldflow.pipeline.compat.CompatRegistry;
import com.cleanroommc.worldflow.pipeline.compat.GeneratorSafetyLevel;
import com.cleanroommc.worldflow.pipeline.isolation.WorkerGeneratorContext;
import com.cleanroommc.worldflow.pipeline.otg.OTGParallelContext;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.fml.common.IWorldGenerator;
import net.minecraftforge.fml.common.registry.GameRegistry;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates staged chunk generation dependencies, dedicated worker pool execution,
 * carver caching, backpressure, and deadlock detection/recovery.
 */
public class GenerationScheduler {

    private static final GenerationScheduler INSTANCE = new GenerationScheduler();

    private final Map<Long, StatusHolder> inFlight = new ConcurrentHashMap<>();
    private final Queue<StatusHolder> pendingRequestQueue = new ConcurrentLinkedQueue<>();
    private final Map<Long, StatusHolder> pendingRequests = new ConcurrentHashMap<>();
    private final Map<Long, ProtoChunk> carverCache = new ConcurrentHashMap<>();
    private final Map<IWorldGenerator, ReentrantLock> generatorLocks = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> IS_DECORATING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final AtomicBoolean dirtyPublishSweep = new AtomicBoolean(false);

    public final AtomicLong totalGenerationsRequested = new AtomicLong(0);
    private final long pipelineStartTimeMs = System.currentTimeMillis();
    private final AtomicLong windowStartTimeMs = new AtomicLong(0);
    private final AtomicLong windowStartGen = new AtomicLong(0);
    private final AtomicLong totalBaseGenNanos = new AtomicLong(0);
    private final AtomicLong totalBaseGenCount = new AtomicLong(0);
    private final AtomicLong totalFeaturesNanos = new AtomicLong(0);
    private final AtomicLong totalFeaturesCount = new AtomicLong(0);
    public final AtomicLong totalChunksPipeline = new AtomicLong(0);
    public final AtomicLong totalChunksInline = new AtomicLong(0);

    private final AtomicLong lastMetricsLog = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong parallelCount = new AtomicLong(0);
    private final AtomicLong serializedCount = new AtomicLong(0);

    public final AtomicLong guardsBlockedCount = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> serializedGeneratorCounts = new ConcurrentHashMap<>();

    // Deadlock Watchdog tracking
    private final AtomicLong watchdogLastPublished = new AtomicLong(0);
    private final AtomicLong watchdogLastProgressTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean watchdogTriggered = new AtomicBoolean(false);

    public static GenerationScheduler getInstance() {
        return INSTANCE;
    }

    public long getGuardsBlockedCount() {
        return this.guardsBlockedCount.get();
    }

    public void recordSerializedGenerator(String className) {
        if (className != null) {
            this.serializedGeneratorCounts.computeIfAbsent(className, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    public Map<String, Long> getSerializedGeneratorCountsSnapshot() {
        Map<String, Long> map = new HashMap<>();
        for (Map.Entry<String, AtomicLong> entry : this.serializedGeneratorCounts.entrySet()) {
            map.put(entry.getKey(), entry.getValue().get());
        }
        return map;
    }

    public void registerPendingWriters(StatusHolder holder) {
        // No-op in pure-predicate publish architecture: publish readiness is evaluated on demand via canPublish()
    }

    /**
     * A chunk is publishable as soon as its own decoration completed.
     *
     * <p>The previous implementation required all 8 neighbours to be decorated too. With the
     * in-flight cap this is a deadlock: decorated chunks cannot publish, so the cap fills with
     * them and the neighbours they wait for can never be admitted. Late neighbour writes are
     * safe because ThreadLocalWorldView.setBlockState synchronises on the published chunk and
     * marks it dirty, so the write is persisted.</p>
     */
    public boolean canPublish(StatusHolder holder) {
        return holder != null && holder.isOwnDecorationDone();
    }

    public List<ChunkPos> getOwedBy(StatusHolder holder) {
        if (holder == null) {
            return java.util.Collections.emptyList();
        }
        List<ChunkPos> owed = new ArrayList<>();
        int cx = holder.getPos().x;
        int cz = holder.getPos().z;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long nKey = ChunkPos.asLong(cx + dx, cz + dz);
                StatusHolder nHolder = this.inFlight.get(nKey);
                if (nHolder != null) {
                    if (nHolder.getTargetStatus() == ChunkStatus.FULL && !nHolder.isOwnDecorationDone() && !nHolder.isDone()) {
                        owed.add(nHolder.getPos());
                    }
                    continue;
                }
                StatusHolder pending = this.pendingRequests.get(nKey);
                if (pending != null && pending.getTargetStatus() == ChunkStatus.FULL && !pending.isOwnDecorationDone() && !pending.isDone()) {
                    owed.add(pending.getPos());
                }
            }
        }
        return owed;
    }

    public int getPendingWritersCount(StatusHolder holder) {
        return getOwedBy(holder).size();
    }

    public void completeWriter(StatusHolder holder) {
        if (holder == null) return;
        holder.setOwnDecorationDone(true);
        if (canPublish(holder)) {
            WorldServer world = holder.getWorld();
            if (world != null && world.isCallingFromMinecraftThread()) {
                PublishManager.INSTANCE.publishChunk(world, holder);
            } else if (holder.markPublishEnqueued()) {
                PublishManager.INSTANCE.enqueueReady(holder);
            }
        }
        notifyNeighboursOfCompletion(holder.getPos().x, holder.getPos().z);
    }

    public void markPublishDirty() {
        this.dirtyPublishSweep.set(true);
    }

    public void sweepPublishableIfDirty() {
        if (this.dirtyPublishSweep.compareAndSet(true, false)) {
            sweepPublishable();
        }
    }

    public void sweepPublishable() {
        for (StatusHolder holder : this.inFlight.values()) {
            if (holder.isOwnDecorationDone() && !holder.isDone()) {
                if (canPublish(holder)) {
                    WorldServer world = holder.getWorld();
                    if (world != null && world.isCallingFromMinecraftThread()) {
                        PublishManager.INSTANCE.publishChunk(world, holder);
                    } else {
                        if (holder.markPublishEnqueued()) {
                            PublishManager.INSTANCE.enqueueReady(holder);
                        } else {
                            long ageMs = (System.nanoTime() - holder.getSubmissionTimeNanos()) / 1_000_000L;
                            if (ageMs > 2000L && !PublishManager.INSTANCE.isPending(holder)) {
                                PublishManager.INSTANCE.enqueueReady(holder);
                            }
                        }
                    }
                }
            }
        }
    }

    public void requeueOrphaned(StatusHolder holder) {
        if (holder == null || holder.isDone() || holder.isOwnDecorationDone()) {
            return;
        }
        WorldFlow.LOGGER.info(String.format(
                "[WorldFlow] Requeueing orphaned chunk [%d, %d] (stage=%s, worker=%s) for decoration",
                holder.getPos().x, holder.getPos().z, holder.getCurrentStatus(), holder.getLastWorkerName()
        ));
        if (holder.getCurrentStatus() == ChunkStatus.EMPTY) {
            // Must claim the scheduled flag, otherwise the pump sees EMPTY with the flag
            // clear and queues a second base-gen for the same chunk.
            if (holder.markBaseGenScheduled()) {
                GenerationWorkerPool.getInstance().executeBaseGen(holder, () -> executeBaseGeneration(holder));
            }
            return;
        }

        WorldServer world = holder.getWorld();
        int cx = holder.getPos().x;
        int cz = holder.getPos().z;
        Map<Long, ProtoChunk> neighbourhood = new HashMap<>();
        neighbourhood.put(holder.getChunkKey(), holder.getProtoChunk());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long nKey = ChunkPos.asLong(cx + dx, cz + dz);
                ProtoChunk p = getProtoChunkAny(nKey);
                if (p != null) {
                    neighbourhood.put(nKey, p);
                }
            }
        }
        if (holder.markFeaturesScheduled()) {
            GenerationWorkerPool.getInstance().executeFeatures(holder, () -> executeFeatures(holder, neighbourhood));
        }
    }

    public void notifyNeighboursOfCompletion(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long nKey = ChunkPos.asLong(cx + dx, cz + dz);
                StatusHolder nHolder = this.inFlight.get(nKey);
                if (nHolder != null && nHolder.isOwnDecorationDone() && !nHolder.isDone()) {
                    if (canPublish(nHolder)) {
                        WorldServer world = nHolder.getWorld();
                        if (world != null && world.isCallingFromMinecraftThread()) {
                            PublishManager.INSTANCE.publishChunk(world, nHolder);
                        } else {
                            if (nHolder.markPublishEnqueued()) {
                                PublishManager.INSTANCE.enqueueReady(nHolder);
                            } else {
                                long ageMs = (System.nanoTime() - nHolder.getSubmissionTimeNanos()) / 1_000_000L;
                                if (ageMs > 2000L && !PublishManager.INSTANCE.isPending(nHolder)) {
                                    PublishManager.INSTANCE.enqueueReady(nHolder);
                                }
                            }
                        }
                    }
                }
            }
        }
        markPublishDirty();
    }

    public void notifyNeighboursOfCarverReadiness(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long nKey = ChunkPos.asLong(cx + dx, cz + dz);
                StatusHolder nHolder = this.inFlight.get(nKey);
                if (nHolder != null && nHolder.getCurrentStatus() == ChunkStatus.CARVERS
                        && nHolder.getTargetStatus() != ChunkStatus.CARVERS
                        && nHolder.isCarversDone() && !nHolder.isWorkerDone() && !nHolder.isDone()) {
                    checkAndTriggerFeaturesIfReady(nHolder);
                }
            }
        }
    }

    private void checkAndTriggerFeaturesIfReady(StatusHolder holder) {
        if (holder == null || holder.isDone() || holder.isWorkerDone()
                || holder.getTargetStatus() == ChunkStatus.CARVERS) {
            return;
        }
        WorldServer world = holder.getWorld();
        int cx = holder.getPos().x;
        int cz = holder.getPos().z;

        Map<Long, ProtoChunk> neighbourhood = new HashMap<>();
        neighbourhood.put(holder.getChunkKey(), holder.getProtoChunk());
        boolean allNeighboursReady = true;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = cx + dx;
                int nz = cz + dz;
                long nKey = ChunkPos.asLong(nx, nz);

                Chunk loaded = world.getChunkProvider().getLoadedChunk(nx, nz);
                if (loaded != null) {
                    continue;
                }
                ProtoChunk cached = this.carverCache.get(nKey);
                if (cached != null) {
                    neighbourhood.put(nKey, cached);
                    continue;
                }
                StatusHolder nHolder = this.inFlight.get(nKey);
                if (nHolder != null && nHolder.getCurrentStatus().isAtLeast(ChunkStatus.CARVERS)) {
                    neighbourhood.put(nKey, nHolder.getProtoChunk());
                    continue;
                }
                // At least one neighbour is not yet at CARVERS
                return;
            }
        }

        // All 3x3 neighbours are ready at CARVERS! Advance immediately to features.
        // The guard matters: without it every neighbour reaching CARVERS re-decorated this
        // chunk (measured 2.3 feature executions per chunk).
        if (holder.markFeaturesScheduled()) {
            GenerationWorkerPool.getInstance().executeFeatures(holder, () -> executeFeatures(holder, neighbourhood));
        }
    }

    private GenerationScheduler() {
        // Feed the pool autoscaler with the real pipeline depth (queue alone is misleading).
        GenerationWorkerPool.getInstance().setInFlightSupplier(() -> this.inFlight.size());
        // Start background Deadlock Watchdog daemon (1s: drives the chunk state machine)
        GenerationWorkerPool.getInstance().scheduleWithFixedDelay(this::runWatchdogCheck, 1, 1, TimeUnit.SECONDS);
    }

    public CompletableFuture<Chunk> requestChunk(WorldServer world, int x, int z, GenerationPriority priority) {
        return requestChunk(world, x, z, ChunkStatus.FULL, priority);
    }

    public CompletableFuture<Chunk> requestChunk(WorldServer world, int x, int z, ChunkStatus targetStatus, GenerationPriority priority) {
        long key = ChunkPos.asLong(x, z);
        totalGenerationsRequested.incrementAndGet();

        // Initialize burst window if idle
        if (this.inFlight.isEmpty() && this.pendingRequests.isEmpty()) {
            long now = System.currentTimeMillis();
            long totalGen = PublishManager.INSTANCE.totalChunksPublished.get() + this.totalChunksInline.get();
            this.windowStartTimeMs.set(now);
            this.windowStartGen.set(totalGen);
        }

        // 1. Is chunk already in memory?
        Chunk loaded = world.getChunkProvider().getLoadedChunk(x, z);
        if (loaded != null) {
            return CompletableFuture.completedFuture(loaded);
        }

        // 2. DISK-EXISTENCE GUARD: Check if chunk is saved on disk — NEVER overwrite existing world data!
        if (world.getChunkProvider().chunkLoader != null && world.getChunkProvider().chunkLoader.isChunkGeneratedAt(x, z)) {
            Chunk diskChunk = world.getChunkProvider().loadChunk(x, z);
            if (diskChunk != null) {
                return CompletableFuture.completedFuture(diskChunk);
            }
        }

        // 3. Already tracked in pipeline?
        StatusHolder existing = this.inFlight.get(key);
        if (existing != null) {
            existing.bumpPriority(priority);
            if (targetStatus == ChunkStatus.FULL && existing.getTargetStatus() != ChunkStatus.FULL) {
                existing.setTargetStatus(ChunkStatus.FULL);
                registerPendingWriters(existing);
                if (existing.getCurrentStatus().isAtLeast(ChunkStatus.CARVERS) && !existing.isDone()) {
                    advanceChunk(existing);
                }
            }
            return existing.getFuture();
        }

        StatusHolder pending = this.pendingRequests.get(key);
        if (pending != null) {
            pending.bumpPriority(priority);
            pending.setTargetStatus(targetStatus);
            if (priority == GenerationPriority.IMMEDIATE) {
                this.pendingRequests.remove(key);
                // Never use an unconditional put here: another thread may have admitted
                // a holder for the same chunk in between. Replacing it would strand that
                // holder's future (and its callers) forever.
                StatusHolder prev = this.inFlight.putIfAbsent(key, pending);
                if (prev != null && prev != pending) {
                    chainFutures(prev, pending);
                } else {
                    registerPendingWriters(pending);
                    advanceChunk(pending);
                }
            }
            return pending.getFuture();
        }

        // 5. Carver cache check: Re-use cached base terrain and jump directly to CARVERS!
        ProtoChunk cachedCarvers = this.carverCache.get(key);
        if (cachedCarvers != null && targetStatus == ChunkStatus.FULL) {
            StatusHolder holder = new StatusHolder(world, x, z, ChunkStatus.FULL, priority);
            holder.getProtoChunk().setUnderlyingChunk(cachedCarvers.getChunk());
            holder.setCurrentStatus(ChunkStatus.CARVERS);
            holder.getCarversFuture().complete(cachedCarvers.getChunk());

            StatusHolder prev = this.inFlight.putIfAbsent(key, holder);
            if (prev != null) {
                prev.bumpPriority(priority);
                prev.setTargetStatus(targetStatus);
                return prev.getFuture();
            }
            registerPendingWriters(holder);
            advanceChunk(holder);
            return holder.getFuture();
        }

        // 6. In-flight backpressure / memory guard: Cap maximum concurrent in-flight generations
        int maxInFlight = ModConfig.CONCURRENCY != null ? ModConfig.CONCURRENCY.maxInFlightGenerations : 32;
        boolean isServerThread = world.getMinecraftServer() != null && world.getMinecraftServer().isCallingFromMinecraftThread();
        boolean isWorkerThread = Thread.currentThread().getName().startsWith("WorldFlow-");

        if (this.inFlight.size() >= maxInFlight && !isWorkerThread && priority != GenerationPriority.IMMEDIATE) {
            if (!isServerThread) {
                // Background callers (e.g. bench or async threads): throttle smoothly
                while (this.inFlight.size() >= maxInFlight) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            } else {
                // Main tick thread callers: queue to prevent stalling the tick
                StatusHolder holder = new StatusHolder(world, x, z, targetStatus, priority);
                this.pendingRequests.put(key, holder);
                this.pendingRequestQueue.add(holder);
                return holder.getFuture();
            }
        }

        // 7. Register new StatusHolder in inFlight
        StatusHolder holder = new StatusHolder(world, x, z, targetStatus, priority);
        StatusHolder prev = this.inFlight.putIfAbsent(key, holder);
        if (prev != null) {
            prev.bumpPriority(priority);
            prev.setTargetStatus(targetStatus);
            return prev.getFuture();
        }
        registerPendingWriters(holder);

        // 8. Begin staged pipeline execution
        advanceChunk(holder);
        return holder.getFuture();
    }

    public CompletableFuture<Chunk> requestCarversChunk(WorldServer world, int x, int z) {
        long key = ChunkPos.asLong(x, z);

        // 1. Is it loaded in live world?
        Chunk loaded = world.getChunkProvider().getLoadedChunk(x, z);
        if (loaded != null) {
            return CompletableFuture.completedFuture(loaded);
        }

        // 2. Is it in carverCache?
        ProtoChunk cached = this.carverCache.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached.getChunk());
        }

        // 3. Is it already in flight?
        StatusHolder existing = this.inFlight.get(key);
        if (existing != null) {
            existing.bumpPriority(GenerationPriority.PLAYER);
            return existing.getCarversFuture();
        }

        // 4. Was it queued in pendingRequests? Promote it immediately!
        StatusHolder pending = this.pendingRequests.remove(key);
        if (pending != null) {
            StatusHolder prev = this.inFlight.putIfAbsent(key, pending);
            if (prev != null && prev != pending) {
                chainFutures(prev, pending);
            } else {
                registerPendingWriters(pending);
                advanceChunk(pending);
            }
            return pending.getCarversFuture();
        }

        // 5. Create new StatusHolder with targetStatus = CARVERS
        StatusHolder holder = new StatusHolder(world, x, z, ChunkStatus.CARVERS, GenerationPriority.PLAYER);
        StatusHolder prev = this.inFlight.putIfAbsent(key, holder);
        if (prev != null) {
            prev.bumpPriority(GenerationPriority.PLAYER);
            return prev.getCarversFuture();
        }

        advanceChunk(holder);
        return holder.getCarversFuture();
    }

    /**
     * Self-healing advance: drives a holder forward when a scheduling hand-off was lost.
     * Safe to call from the server thread, the watchdog, or a bench thread: duplicate work is
     * prevented by the scheduled/running flags and a 1s grace period.
     */
    public void pump(StatusHolder holder) {
        if (holder == null || holder.isDone()) {
            return;
        }
        long now = System.nanoTime();
        ChunkStatus stage = holder.getCurrentStatus();
        if (stage == ChunkStatus.EMPTY) {
            if (!holder.isCarversDone() && !holder.isBaseGenRunning()) {
                long ageMs = holder.getBaseGenScheduledAtNanos() > 0L
                        ? (now - holder.getBaseGenScheduledAtNanos()) / 1_000_000L : Long.MAX_VALUE;
                if (!holder.isBaseGenScheduled() || ageMs > 1000L) {
                    holder.resetBaseGenScheduled();
                    advanceChunk(holder);
                }
            }
        } else if (stage == ChunkStatus.CARVERS) {
            if (holder.getTargetStatus() == ChunkStatus.CARVERS) {
                if (holder.isCarversDone() && !holder.isDone()) {
                    holder.getFuture().complete(holder.getProtoChunk().getChunk());
                    removeInFlight(holder.getChunkKey());
                }
                return;
            }
            if (holder.isCarversDone() && !holder.isWorkerDone()) {
                long ageMs = holder.getFeaturesScheduledAtNanos() > 0L
                        ? (now - holder.getFeaturesScheduledAtNanos()) / 1_000_000L : Long.MAX_VALUE;
                // A "running" flag older than 60s means the task died without clearing it.
                if (holder.isFeaturesRunning() && ageMs > 60_000L) {
                    WorldFlow.LOGGER.warn("[WorldFlow] Clearing stale feature-running flag for chunk [{}, {}] (age {}ms)",
                            holder.getPos().x, holder.getPos().z, ageMs);
                    holder.setFeaturesRunning(false);
                    holder.resetFeaturesScheduled();
                }
                // 30s, not 1s: a task that is merely QUEUED is not stuck. Retrying after 1s
                // duplicated decoration for every chunk waiting in a >1s queue.
                if (!holder.isFeaturesRunning()
                        && (!holder.isFeaturesScheduled() || ageMs > 30_000L)) {
                    holder.resetFeaturesScheduled();
                    checkAndScheduleFeatures(holder);
                }
            }
        } else if (stage == ChunkStatus.FEATURES || stage == ChunkStatus.LIGHT) {
            if (holder.isWorkerDone() && !holder.isOwnDecorationDone()) {
                completeWriter(holder);
            }
        }
    }

    public void advanceChunk(StatusHolder holder) {
        if (holder.isDone()) {
            return;
        }

        ChunkStatus current = holder.getCurrentStatus();

        if (current == ChunkStatus.EMPTY) {
            // Stage 0 -> CARVERS: generate base terrain on dedicated BaseGen pool (pure compute, zero dependencies)
            if (holder.markBaseGenScheduled()) {
                GenerationWorkerPool.getInstance().executeBaseGen(holder, () -> executeBaseGeneration(holder));
            }
        } else if (current == ChunkStatus.CARVERS) {
            if (holder.getTargetStatus() == ChunkStatus.CARVERS) {
                // Neighbour-only request: it exists to supply terrain to a decorating chunk.
                // Decorating it would cascade to its own 8 neighbours (queue explosion).
                if (holder.isCarversDone() && !holder.isDone()) {
                    holder.getFuture().complete(holder.getProtoChunk().getChunk());
                    removeInFlight(holder.getChunkKey());
                }
                return;
            }
            // Stage CARVERS -> FEATURES: decorate with a best-effort 3x3 neighbourhood
            checkAndScheduleFeatures(holder);
        } else if (current == ChunkStatus.FEATURES || current == ChunkStatus.LIGHT) {
            // Hand off to completeWriter to publish only when no pending writers remain
            completeWriter(holder);
        }
    }

    private void executeBaseGeneration(StatusHolder holder) {
        executeBaseGeneration(holder, true);
    }

    private void executeBaseGeneration(StatusHolder holder, boolean retryOnMainThread) {
        WorldServer world = holder.getWorld();
        ChunkPos pos = holder.getPos();
        ProtoChunk proto = holder.getProtoChunk();
        holder.setLastWorkerName(Thread.currentThread().getName());

        long startNanos = System.nanoTime();
        holder.setBaseGenRunning(true);
        proto.setOwnerThread(Thread.currentThread());
        ThreadLocalWorldView.enter(world, pos, proto, "BaseGen");
        try {
            IChunkGenerator generator = WorkerGeneratorContext.get().getChunkGenerator(world);

            Chunk generatedChunk = generator.generateChunk(pos.x, pos.z);
            if (generatedChunk != null) {
                proto.setUnderlyingChunk(generatedChunk);
            }
            if (generator.getClass().getName().startsWith("com.pg85.otg.")) {
                com.pg85.otg.forge.world.ForgeWorld forgeWorld = WorkerGeneratorContext.get().getForgeWorld(world, generator);
                if (forgeWorld != null) {
                    proto.ensureBiomesFilled(forgeWorld);
                }
            }
            holder.setCurrentStatus(ChunkStatus.CARVERS);
            holder.getCarversFuture().complete(proto.getChunk());
        } catch (Throwable t) {
            if (retryOnMainThread) {
                // Never publish a partial, undecorated chunk. Re-run terrain generation on
                // the server thread (where unsafe mod hooks are safe), then continue the
                // normal feature pipeline from the completed carver stage.
                WorldFlow.LOGGER.warn("[WorldFlow] Off-thread error in base terrain generation for chunk [{}, {}], retrying on the main thread", pos.x, pos.z, t);
                retryBaseGenOnMainThread(holder);
                return;
            }
            throw new RuntimeException(t);
        } finally {
            ThreadLocalWorldView.exit();
            this.totalBaseGenNanos.addAndGet(System.nanoTime() - startNanos);
            this.totalBaseGenCount.incrementAndGet();
            this.parallelCount.incrementAndGet();
            holder.setBaseGenRunning(false);
            proto.setOwnerThread(null);
            checkLogMetrics();
        }

        // Cache completed CARVERS ProtoChunk for fast neighbour reuse
        putCarverCache(holder.getChunkKey(), proto);

        // Notify neighbours that this chunk has reached CARVERS
        notifyNeighboursOfCarverReadiness(pos.x, pos.z);

        // If target was only CARVERS (e.g. neighbour dependency), complete future and remove from inFlight!
        if (holder.getTargetStatus() == ChunkStatus.CARVERS) {
            holder.getFuture().complete(proto.getChunk());
            removeInFlight(holder.getChunkKey());
            return;
        }

        // Otherwise advance to features stage
        advanceChunk(holder);
    }

    private void checkAndScheduleFeatures(StatusHolder holder) {
        WorldServer world = holder.getWorld();
        int cx = holder.getPos().x;
        int cz = holder.getPos().z;

        // Best-effort neighbourhood: whatever already exists. We deliberately do NOT block on
        // neighbour CARVERS futures here - that barrier cost up to 3s per chunk and was the
        // source of the CARVERS orphan stalls. Missing neighbours are requested asynchronously
        // (to warm the carver cache) but decoration proceeds immediately.
        Map<Long, ProtoChunk> neighbourhood = new HashMap<>();
        neighbourhood.put(holder.getChunkKey(), holder.getProtoChunk());
        boolean allNeighboursReady = true;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = cx + dx;
                int nz = cz + dz;
                long nKey = ChunkPos.asLong(nx, nz);

                Chunk loaded = world.getChunkProvider().getLoadedChunk(nx, nz);
                if (loaded != null) {
                    continue;
                }
                ProtoChunk cached = this.carverCache.get(nKey);
                if (cached != null) {
                    neighbourhood.put(nKey, cached);
                    continue;
                }
                StatusHolder nHolder = this.inFlight.get(nKey);
                if (nHolder != null) {
                    if (nHolder.getCurrentStatus().isAtLeast(ChunkStatus.CARVERS)) {
                        neighbourhood.put(nKey, nHolder.getProtoChunk());
                    } else {
                        allNeighboursReady = false;
                        if (nHolder.markBaseGenScheduled()) {
                            GenerationWorkerPool.getInstance().executeBaseGen(nHolder, () -> executeBaseGeneration(nHolder));
                        }
                    }
                    continue;
                }
                // Neighbour terrain is required for bit-identical decoration (structures span
                // chunk borders). Request it and let the state machine retry - never block here.
                allNeighboursReady = false;
                requestCarversChunk(world, nx, nz);
            }
        }

        if (!allNeighboursReady) {
            // Watchdog retries within ~1s. Never block a worker on neighbour terrain.
            return;
        }
        if (holder.markFeaturesScheduled()) {
            GenerationWorkerPool.getInstance().executeFeatures(holder, () -> executeFeatures(holder, neighbourhood));
        }
    }

    private void executeFeatures(StatusHolder holder, Map<Long, ProtoChunk> neighbourhood) {
        if (IS_DECORATING.get()) {
            WorldFlow.LOGGER.warn("[WorldFlow] Detected recursive feature execution on thread {} for chunk [{}, {}], resubmitting",
                    Thread.currentThread().getName(), holder.getPos().x, holder.getPos().z);
            GenerationWorkerPool.getInstance().executeFeatures(holder, () -> executeFeatures(holder, neighbourhood));
            return;
        }
        IS_DECORATING.set(true);
        holder.setFeaturesRunning(true);

        WorldServer world = holder.getWorld();
        ChunkPos pos = holder.getPos();
        ProtoChunk proto = holder.getProtoChunk();
        holder.setLastWorkerName(Thread.currentThread().getName());

        com.cleanroommc.worldflow.pipeline.isolation.PopulationHighestBlocksCache.reset();
        long startNanos = System.nanoTime();
        try {
            ThreadLocalWorldView.enter(world, pos, proto, "Features");
        } catch (Throwable t) {
            // Never leak the running flags: a leaked flag makes the watchdog believe the chunk
            // is being decorated and it will never be retried (silent in-flight stall).
            WorldFlow.LOGGER.warn("[WorldFlow] Failed to enter generation context for chunk [{}, {}]", pos.x, pos.z, t);
            holder.setFeaturesRunning(false);
            IS_DECORATING.set(false);
            holder.resetFeaturesScheduled();
            return;
        }
        proto.setOwnerThread(Thread.currentThread());
        ThreadLocalWorldView.attachNeighbourhood(neighbourhood);
        try {
            ChunkProviderServer provider = world.getChunkProvider();
            IChunkGenerator generator = WorkerGeneratorContext.get().getChunkGenerator(world);
            if (generator == null && provider != null) {
                generator = provider.chunkGenerator;
            }
            if (generator == null) {
                WorldFlow.LOGGER.warn("[WorldFlow] ChunkGenerator is null for chunk [{}, {}], falling back to main-thread serial", pos.x, pos.z);
                fallbackToMainThreadSerial(holder);
                return;
            }
            Random workerRand = WorkerGeneratorContext.get().rand;

            // Ensure fillBiomeArray has run for all proto-chunks in neighbourhood and structure search radius
            if (generator.getClass().getName().startsWith("com.pg85.otg.")) {
                com.pg85.otg.forge.world.ForgeWorld forgeWorld = WorkerGeneratorContext.get().getForgeWorld(world, generator);
                if (forgeWorld != null) {
                    for (ProtoChunk p : neighbourhood.values()) {
                        if (p != null) {
                            p.ensureBiomesFilled(forgeWorld);
                        }
                    }
                    int cx = pos.x;
                    int cz = pos.z;
                    for (int r = 2; r <= 8; r++) {
                        for (int dx = -r; dx <= r; dx++) {
                            for (int dz = -r; dz <= r; dz++) {
                                if (Math.abs(dx) == r || Math.abs(dz) == r) {
                                    ProtoChunk p = getProtoChunkAny(ChunkPos.asLong(cx + dx, cz + dz));
                                    if (p != null) {
                                        p.ensureBiomesFilled(forgeWorld);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 1. PopulateChunkEvent.Pre
            try {
                MinecraftForge.EVENT_BUS.post(new PopulateChunkEvent.Pre(generator, world, workerRand, pos.x, pos.z, false));
            } catch (Throwable t) {
                WorldFlow.LOGGER.debug("[WorldFlow] Exception in PopulateChunkEvent.Pre listener", t);
            }

            // 2. Standard population (ores, flora, structures) with bounded lock wait
            boolean isOtg = generator.getClass().getName().startsWith("com.pg85.otg.");
            long populateStart = System.nanoTime();
            GeneratorSafetyLevel genSafety = CompatRegistry.INSTANCE.getSafetyLevel(generator);
            if (isOtg || genSafety == GeneratorSafetyLevel.SERIALIZE) {
                this.serializedCount.incrementAndGet();
                recordSerializedGenerator(generator.getClass().getName());
                java.util.concurrent.locks.ReentrantLock lock = OTGParallelContext.getStructureLock(pos.x, pos.z);
                boolean acquired = false;
                try {
                    acquired = lock.tryLock(15, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (!acquired) {
                    WorldFlow.LOGGER.warn("[WorldFlow] Timed out waiting for structure lock for chunk [{}, {}], falling back to main-thread serial", pos.x, pos.z);
                    fallbackToMainThreadSerial(holder);
                    return;
                }
                try {
                    generator.populate(pos.x, pos.z);
                } finally {
                    lock.unlock();
                }
            } else {
                this.parallelCount.incrementAndGet();
                generator.populate(pos.x, pos.z);
            }

            if (isOtg) {
                this.totalOtgPopulateNanos.addAndGet(System.nanoTime() - populateStart);
                this.otgPopulateCount.incrementAndGet();
            }

            // 3. Forge GameRegistry world generators (thread-safe and serialized per-generator)
            long forgeStart = System.nanoTime();
            generateModWorldIsolated(pos.x, pos.z, world, generator, provider, workerRand);
            this.totalForgeGenNanos.addAndGet(System.nanoTime() - forgeStart);
            this.forgeGenCount.incrementAndGet();

            proto.setTerrainPopulated(true);
            // Apply staged structure blocks here, on the worker, while the chunk is still
            // private. Doing it in publishChunk cost ~10-36ms of main-thread time per chunk.
            try {
                com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer.INSTANCE
                        .flushChunkBlocksOnly(world, proto.getChunk());
            } catch (Throwable flushErr) {
                WorldFlow.LOGGER.debug("[WorldFlow] Off-thread block flush failed for chunk [{}, {}]", pos.x, pos.z, flushErr);
            }
            holder.setCurrentStatus(ChunkStatus.LIGHT);
            holder.getWorkerDoneFuture().complete(proto.getChunk());
        } catch (Throwable t) {
            WorldFlow.LOGGER.warn("[WorldFlow] Off-thread error decorating chunk [{}, {}], falling back to main-thread finalize", pos.x, pos.z, t);
            fallbackToMainThreadSerial(holder);
            return;
        } finally {
            ThreadLocalWorldView.exit();
            this.totalFeaturesNanos.addAndGet(System.nanoTime() - startNanos);
            this.totalFeaturesCount.incrementAndGet();
            checkLogMetrics();
            IS_DECORATING.set(false);
            holder.setFeaturesRunning(false);
            proto.setOwnerThread(null);
        }

        // Complete this writer and publish any chunks whose pending writers have reached 0
        completeWriter(holder);
    }

    private void retryBaseGenOnMainThread(StatusHolder holder) {
        WorldServer world = holder.getWorld();
        ChunkPos pos = holder.getPos();
        Runnable action = () -> {
            if (holder.isDone()) {
                return;
            }
            try {
                executeBaseGeneration(holder, false);
            } catch (Throwable t) {
                WorldFlow.LOGGER.error("[WorldFlow] Main-thread base generation retry failed for chunk [{}, {}]", pos.x, pos.z, t);
                fallbackToMainThreadSerial(holder);
            }
        };
        if (world.isCallingFromMinecraftThread()) {
            action.run();
        } else {
            world.addScheduledTask(action);
        }
    }

    private void fallbackToMainThreadSerial(StatusHolder holder) {
        WorldServer world = holder.getWorld();
        ChunkPos pos = holder.getPos();
        Runnable action = () -> {
            try {
                Chunk loaded = world.getChunkProvider().getLoadedChunk(pos.x, pos.z);
                if (loaded != null) {
                    holder.setCurrentStatus(ChunkStatus.FULL);
                    holder.setOwnDecorationDone(true);
                    holder.getCarversFuture().complete(loaded);
                    holder.getWorkerDoneFuture().complete(loaded);
                    holder.getFuture().complete(loaded);
                    removeInFlight(holder.getChunkKey());
                    notifyNeighboursOfCompletion(pos.x, pos.z);
                    return;
                }
                if (world.getChunkProvider().chunkLoader != null && world.getChunkProvider().chunkLoader.isChunkGeneratedAt(pos.x, pos.z)) {
                    Chunk diskChunk = world.getChunkProvider().loadChunk(pos.x, pos.z);
                    if (diskChunk != null) {
                        holder.getProtoChunk().setUnderlyingChunk(diskChunk);
                        holder.setCurrentStatus(ChunkStatus.FULL);
                        holder.setOwnDecorationDone(true);
                        holder.getCarversFuture().complete(diskChunk);
                        holder.getWorkerDoneFuture().complete(diskChunk);
                        holder.getFuture().complete(diskChunk);
                        PublishManager.INSTANCE.publishChunk(world, holder);
                        removeInFlight(holder.getChunkKey());
                        notifyNeighboursOfCompletion(pos.x, pos.z);
                        return;
                    }
                }
                Chunk chunk = holder.getProtoChunk().getChunk();
                if (chunk == null) {
                    chunk = new Chunk(world, pos.x, pos.z);
                    holder.getProtoChunk().setUnderlyingChunk(chunk);
                }
                holder.getProtoChunk().setTerrainPopulated(true);
                holder.setCurrentStatus(ChunkStatus.FULL);
                holder.setOwnDecorationDone(true);
                holder.getCarversFuture().complete(chunk);
                holder.getWorkerDoneFuture().complete(chunk);
                PublishManager.INSTANCE.publishChunk(world, holder);
                notifyNeighboursOfCompletion(pos.x, pos.z);
            } catch (Throwable fallbackErr) {
                WorldFlow.LOGGER.error("[WorldFlow] Main-thread serial fallback failed for chunk [{}, {}]", pos.x, pos.z, fallbackErr);
                Chunk fallbackChunk = holder.getProtoChunk().getChunk();
                if (fallbackChunk == null) {
                    fallbackChunk = new Chunk(world, pos.x, pos.z);
                }
                holder.setOwnDecorationDone(true);
                holder.getCarversFuture().complete(fallbackChunk);
                holder.getWorkerDoneFuture().complete(fallbackChunk);
                holder.getFuture().complete(fallbackChunk);
                removeInFlight(holder.getChunkKey());
                notifyNeighboursOfCompletion(pos.x, pos.z);
            }
        };

        if (world.isCallingFromMinecraftThread()) {
            action.run();
        } else {
            world.addScheduledTask(action);
        }
    }

    public final java.util.concurrent.atomic.AtomicLong totalOtgPopulateNanos = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong otgPopulateCount = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong totalForgeGenNanos = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong forgeGenCount = new java.util.concurrent.atomic.AtomicLong();

    public final java.util.concurrent.atomic.AtomicLong generatorLockWaitNanos = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong generatorLockWaits = new java.util.concurrent.atomic.AtomicLong();

    private final java.util.Map<String, java.util.concurrent.atomic.AtomicLong> genNanos = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, java.util.concurrent.atomic.AtomicLong> genCalls = new java.util.concurrent.ConcurrentHashMap<>();

    private void recordGeneratorTime(String name, long nanos) {
        this.genNanos.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicLong()).addAndGet(nanos);
        this.genCalls.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
    }

    private static volatile List<IWorldGenerator> cachedSortedGenerators;

    private void generateModWorldIsolated(int chunkX, int chunkZ, WorldServer world, IChunkGenerator chunkGenerator, ChunkProviderServer chunkProvider, Random rand) {
        if (cachedSortedGenerators == null) {
            try {
                java.lang.reflect.Field field = GameRegistry.class.getDeclaredField("sortedGeneratorList");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<IWorldGenerator> list = (List<IWorldGenerator>) field.get(null);
                if (list == null) {
                    java.lang.reflect.Method m = GameRegistry.class.getDeclaredMethod("computeSortedGeneratorList");
                    m.setAccessible(true);
                    m.invoke(null);
                    list = (List<IWorldGenerator>) field.get(null);
                }
                cachedSortedGenerators = list;
            } catch (Throwable t) {
                GameRegistry.generateWorld(chunkX, chunkZ, world, chunkGenerator, chunkProvider);
                return;
            }
        }

        long worldSeed = world.getSeed();
        Random fmlRandom = new Random(worldSeed);
        long xSeed = fmlRandom.nextLong() >> 2 + 1L;
        long zSeed = fmlRandom.nextLong() >> 2 + 1L;
        long chunkSeed = (xSeed * chunkX + zSeed * chunkZ) ^ worldSeed;

        for (IWorldGenerator generator : cachedSortedGenerators) {
            if (generator == null) {
                continue;
            }
            fmlRandom.setSeed(chunkSeed);
            GeneratorSafetyLevel safety = CompatRegistry.INSTANCE.getSafetyLevel(generator);
            if (safety == GeneratorSafetyLevel.SERIALIZE) {
                this.serializedCount.incrementAndGet();
                recordSerializedGenerator(generator.getClass().getName());
                ReentrantLock lock = this.generatorLocks.computeIfAbsent(generator, g -> new ReentrantLock());
                boolean acquired = false;
                long lockWaitStart = System.nanoTime();
                try {
                    acquired = lock.tryLock(10, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    this.generatorLockWaitNanos.addAndGet(System.nanoTime() - lockWaitStart);
                    this.generatorLockWaits.incrementAndGet();
                }
                if (acquired) {
                    long genStart = System.nanoTime();
                    try {
                        // Shared with OTG's default-structure pass: structure placement and
                        // structure-map iteration must never overlap across threads.
                        com.cleanroommc.worldflow.pipeline.compat.WorldGenSafety.UNSAFE_WORLDGEN_LOCK.lock();
                        try {
                            generator.generate(fmlRandom, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
                        } finally {
                            com.cleanroommc.worldflow.pipeline.compat.WorldGenSafety.UNSAFE_WORLDGEN_LOCK.unlock();
                        }
                    } finally {
                        lock.unlock();
                        recordGeneratorTime(generator.getClass().getSimpleName(), System.nanoTime() - genStart);
                    }
                } else {
                    WorldFlow.LOGGER.warn("[WorldFlow] Timed out acquiring lock for generator {} in chunk [{}, {}]", generator.getClass().getName(), chunkX, chunkZ);
                }
            } else if (safety == GeneratorSafetyLevel.PARALLEL_SAFE) {
                this.parallelCount.incrementAndGet();
                long genStart = System.nanoTime();
                try {
                    generator.generate(fmlRandom, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
                } finally {
                    recordGeneratorTime(generator.getClass().getSimpleName(), System.nanoTime() - genStart);
                }
            } else if (safety == GeneratorSafetyLevel.UNSAFE) {
                this.serializedCount.incrementAndGet();
                recordSerializedGenerator(generator.getClass().getName());
                try {
                    GenerationWorkerPool.getInstance().submitSerializer(() -> {
                        com.cleanroommc.worldflow.pipeline.compat.WorldGenSafety.UNSAFE_WORLDGEN_LOCK.lock();
                        try {
                            generator.generate(fmlRandom, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
                        } finally {
                            com.cleanroommc.worldflow.pipeline.compat.WorldGenSafety.UNSAFE_WORLDGEN_LOCK.unlock();
                        }
                    }).get(10, TimeUnit.SECONDS);
                } catch (Throwable t) {
                    WorldFlow.LOGGER.warn("[WorldFlow] UNSAFE generator {} timed out or failed in chunk [{}, {}]", generator.getClass().getName(), chunkX, chunkZ, t);
                }
            }
        }
        checkLogMetrics();
    }

    private void runWatchdogCheck() {
        long published = PublishManager.INSTANCE.totalChunksPublished.get();
        long now = System.currentTimeMillis();

        if (published > this.watchdogLastPublished.get()) {
            this.watchdogLastPublished.set(published);
            this.watchdogLastProgressTime.set(now);
            this.watchdogTriggered.set(false);
        }

        // 1. Drive every in-flight chunk forward. A chunk must never sit in a non-terminal
        //    stage without a task queued: this is the single guarantee that removes orphans.
        for (StatusHolder h : this.inFlight.values()) {
            if (h == null || h.isDone()) {
                continue;
            }
            try {
                pump(h);
            } catch (Throwable t) {
                WorldFlow.LOGGER.warn("[WorldFlow] Watchdog failed to advance chunk [{}, {}]", h.getPos().x, h.getPos().z, t);
            }
        }

        // 2. Nothing in flight: promote queued requests.
        if (this.inFlight.isEmpty() && !this.pendingRequests.isEmpty()) {
            drainPendingRequests();
        }

        // 3. Diagnose only a real stall (no publish for 5s), and only once per stall window.
        long stallDuration = now - this.watchdogLastProgressTime.get();
        if (stallDuration >= 5000L && !this.inFlight.isEmpty() && this.watchdogTriggered.compareAndSet(false, true)) {
            int activeWorkers = GenerationWorkerPool.getInstance().getActiveWorkerCount();
            int queueSize = GenerationWorkerPool.getInstance().getQueueSize();
            for (StatusHolder h : this.inFlight.values()) {
                long ageMs = (System.nanoTime() - h.getSubmissionTimeNanos()) / 1_000_000L;
                if (ageMs > 5000L && !h.isDone()) {
                    WorldFlow.LOGGER.warn(String.format(
                            "[WorldFlow StallDetector] Stuck chunk [%d,%d] stage=%s worker=%s ownDone=%b pendingWriters=%d futureDone=%b (workerDone=%b, carversDone=%b, featScheduled=%b) active=%d queue=%d owed by: %s",
                            h.getPos().x, h.getPos().z, h.getCurrentStatus(), h.getLastWorkerName(),
                            h.isOwnDecorationDone(), h.getPendingWriters(), h.getFuture().isDone(),
                            h.isWorkerDone(), h.getCarversFuture().isDone(), h.isFeaturesScheduled(),
                            activeWorkers, queueSize, getOwedBy(h)
                    ));
                }
            }
        }
    }

    private void putCarverCache(long key, ProtoChunk proto) {
        if (this.carverCache.size() > 8192) {
            Iterator<Long> it = this.carverCache.keySet().iterator();
            int evicted = 0;
            while (it.hasNext() && evicted < 1024) {
                it.next();
                it.remove();
                evicted++;
            }
        }
        this.carverCache.put(key, proto);
    }

    public void invalidateCarverCache(long key) {
        this.carverCache.remove(key);
    }

    public void checkLogMetrics() {
        long now = System.currentTimeMillis();
        long last = this.lastMetricsLog.get();
        if (now - last > 30000L && this.lastMetricsLog.compareAndSet(last, now)) {
            logMetricsNow();
        }
    }

    public void forceLogMetrics() {
        this.lastMetricsLog.set(System.currentTimeMillis());
        logMetricsNow();
    }

    private void logMetricsNow() {
        long now = System.currentTimeMillis();
        long totalPublished = PublishManager.INSTANCE.totalChunksPublished.get();
        long baseCount = this.totalBaseGenCount.get();
        long featCount = this.totalFeaturesCount.get();
        long pipeCount = this.totalChunksPipeline.get();
        long inlineCount = this.totalChunksInline.get();
        long totalGen = totalPublished + inlineCount;

        if (totalPublished == 0 && baseCount == 0 && featCount == 0 && pipeCount == 0 && inlineCount == 0 && this.inFlight.isEmpty()) {
            return;
        }

        long winStart = this.windowStartTimeMs.get();
        long startGen = this.windowStartGen.get();
        double chunksPerSec = 0.0;
        if (winStart > 0) {
            long elapsedMs = now - winStart;
            long deltaGen = totalGen - startGen;
            if (elapsedMs >= 500 && deltaGen >= 0) {
                chunksPerSec = (deltaGen * 1000.0) / elapsedMs;
            }
        }

        this.windowStartTimeMs.set(now);
        this.windowStartGen.set(totalGen);

        double chunksPerMin = chunksPerSec * 60.0;
        double avgBaseMs = baseCount > 0 ? (this.totalBaseGenNanos.get() / 1_000_000.0) / baseCount : 0.0;
        double avgFeatMs = featCount > 0 ? (this.totalFeaturesNanos.get() / 1_000_000.0) / featCount : 0.0;
        int queueDepth = PublishManager.INSTANCE.getPendingCount();
        long otgN = this.otgPopulateCount.get();
        long forgeN = this.forgeGenCount.get();
        long lightN = PublishManager.INSTANCE.totalLightCount.get();
        double avgOtgMs = otgN > 0 ? (this.totalOtgPopulateNanos.get() / 1_000_000.0) / otgN : 0.0;
        double avgForgeMs = forgeN > 0 ? (this.totalForgeGenNanos.get() / 1_000_000.0) / forgeN : 0.0;
        double avgLightMs = lightN > 0 ? (PublishManager.INSTANCE.totalLightNanos.get() / 1_000_000.0) / lightN : 0.0;
        long plotN = com.cleanroommc.worldflow.pipeline.otg.OTGStructureService.CALL_COUNT.get();
        double avgPlotWorkMs = plotN > 0 ? (com.cleanroommc.worldflow.pipeline.otg.OTGStructureService.TOTAL_WORK_NANOS.get() / 1_000_000.0) / plotN : 0.0;
        double avgPlotWaitMs = plotN > 0 ? (com.cleanroommc.worldflow.pipeline.otg.OTGStructureService.TOTAL_WAIT_NANOS.get() / 1_000_000.0) / plotN : 0.0;
        long pubN = PublishManager.INSTANCE.publishCount.get();
        long pubDiv = Math.max(1, pubN);
        double pubIns = PublishManager.INSTANCE.nanosInsert.get() / 1_000_000.0 / pubDiv;
        double pubFlush = PublishManager.INSTANCE.nanosFlush.get() / 1_000_000.0 / pubDiv;
        double pubSan = PublishManager.INSTANCE.nanosSanitize.get() / 1_000_000.0 / pubDiv;
        double pubLoad = PublishManager.INSTANCE.nanosOnLoad.get() / 1_000_000.0 / pubDiv;
        double pubEv = PublishManager.INSTANCE.nanosEvents.get() / 1_000_000.0 / pubDiv;
        double pubPl = PublishManager.INSTANCE.nanosPlayer.get() / 1_000_000.0 / pubDiv;
        double otgBefore = com.cleanroommc.worldflow.pipeline.otg.OTGPhaseProfiler.avgMs(0);
        double otgBo4 = com.cleanroommc.worldflow.pipeline.otg.OTGPhaseProfiler.avgMs(1);
        double otgAfter = com.cleanroommc.worldflow.pipeline.otg.OTGPhaseProfiler.avgMs(2);
        long lockWaits = this.generatorLockWaits.get();
        double avgGenLockWaitMs = lockWaits > 0 ? (this.generatorLockWaitNanos.get() / 1_000_000.0) / lockWaits : 0.0;

        if (!this.genCalls.isEmpty() && (now % 5 == 0)) {
            this.genCalls.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                    .limit(5)
                    .forEach(e -> {
                        long calls = e.getValue().get();
                        long nanos = this.genNanos.getOrDefault(e.getKey(), new java.util.concurrent.atomic.AtomicLong()).get();
                        WorldFlow.LOGGER.info("[WorldFlow GenProf] {} calls={} avg={}ms", e.getKey(), calls, calls > 0 ? nanos / 1_000_000L / calls : 0);
                    });
        }

        WorldFlow.LOGGER.info("[WorldFlow ResProf] {}", com.cleanroommc.worldflow.pipeline.otg.OTGPhaseProfiler.topResources(5));

        WorldFlow.LOGGER.info("[WorldFlow Counts] base={} features={} published={} inflight={}",
                this.totalBaseGenCount.get(), this.totalFeaturesCount.get(),
                PublishManager.INSTANCE.totalChunksPublished.get(), this.inFlight.size());

        WorldFlow.LOGGER.info("[WorldFlow Pool] active base={} feat={} queue base={} feat={} workers base={} feat={}",
                GenerationWorkerPool.getInstance().getBaseGenActiveCount(),
                GenerationWorkerPool.getInstance().getFeaturesActiveCount(),
                GenerationWorkerPool.getInstance().getBaseGenQueueSize(),
                GenerationWorkerPool.getInstance().getFeaturesQueueSize(),
                GenerationWorkerPool.getInstance().getBaseGenWorkerCount(),
                GenerationWorkerPool.getInstance().getFeaturesWorkerCount());

        WorldFlow.LOGGER.info(String.format(
                "[WorldFlow Metrics] Rate: %.1f chunks/s (%.0f chunks/min) | Published: %d | path=pipeline:%d / path=inline:%d | Queue Depth: %d | In-Flight: %d | Avg BaseGen: %.1fms | Avg Features: %.1fms | OTG: %.1fms | ForgeGen: %.1fms | Light: %.1fms | Plot: %.1fms (wait %.1fms) | GenLockWait: %.1fms | Pub: flush %.1f load %.1f ev %.1f | OTG: res %.1f bo4 %.1f res2 %.1f | Serialized: %d | Parallel: %d | Guards blocked: %d | Workers: base=%d feat=%d (auto)",
                chunksPerSec,
                chunksPerMin,
                totalPublished,
                pipeCount,
                inlineCount,
                queueDepth,
                this.inFlight.size(),
                avgBaseMs,
                avgFeatMs,
                avgOtgMs,
                avgForgeMs,
                avgLightMs,
                avgPlotWorkMs,
                avgPlotWaitMs,
                avgGenLockWaitMs,
                pubFlush, pubLoad, pubEv,
                otgBefore, otgBo4, otgAfter,
                this.serializedCount.get(),
                this.parallelCount.get(),
                this.guardsBlockedCount.get(),
                GenerationWorkerPool.getInstance().getBaseGenWorkerCount(),
                GenerationWorkerPool.getInstance().getFeaturesWorkerCount()
        ));
    }

    @Nullable
    public Chunk awaitOrGenerateSync(WorldServer world, int x, int z, long timeoutMs) {
        // 1. Check live loaded chunks
        Chunk loaded = world.getChunkProvider().getLoadedChunk(x, z);
        if (loaded != null) {
            return loaded;
        }

        // 2. DISK-EXISTENCE GUARD: Check if chunk is saved on disk — NEVER overwrite existing world data!
        if (world.getChunkProvider().chunkLoader != null && world.getChunkProvider().chunkLoader.isChunkGeneratedAt(x, z)) {
            Chunk diskChunk = world.getChunkProvider().loadChunk(x, z);
            if (diskChunk != null) {
                return diskChunk;
            }
        }

        // 3. Check if chunk is already in flight in the parallel pipeline
        long key = ChunkPos.asLong(x, z);
        StatusHolder holder = this.inFlight.get(key);
        if (holder != null) {
            holder.bumpPriority(GenerationPriority.IMMEDIATE);
            boolean isServerThread = world.getMinecraftServer() != null && world.getMinecraftServer().isCallingFromMinecraftThread();
            if (isServerThread) {
                if (holder.isDone()) {
                    return holder.getProtoChunk().getChunk();
                }
                if (holder.isWorkerDone() || holder.getCurrentStatus().isAtLeast(ChunkStatus.LIGHT)) {
                    PublishManager.INSTANCE.publishChunk(world, holder);
                    return holder.getProtoChunk().getChunk();
                }
                try {
                    holder.getWorkerDoneFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
                    if (holder.getCurrentStatus().isAtLeast(ChunkStatus.LIGHT)) {
                        PublishManager.INSTANCE.publishChunk(world, holder);
                        return holder.getProtoChunk().getChunk();
                    }
                } catch (Throwable ignored) {
                }
            } else {
                try {
                    return holder.getFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (Throwable ignored) {
                }
            }
            if (holder.getProtoChunk() != null && holder.getProtoChunk().getChunk() != null) {
                if (isServerThread) {
                    PublishManager.INSTANCE.publishChunk(world, holder);
                }
                return holder.getProtoChunk().getChunk();
            }
        }

        // 4. Synchronous caller fallback: take vanilla inline path
        return world.getChunkProvider().provideChunk(x, z);
    }

    @Nullable
    public StatusHolder getInFlight(long key) {
        return this.inFlight.get(key);
    }

    @Nullable
    public StatusHolder getPendingRequest(long key) {
        return this.pendingRequests.get(key);
    }

    @Nullable
    public ProtoChunk getProtoChunkAny(long key) {
        StatusHolder holder = this.inFlight.get(key);
        if (holder != null) {
            return holder.getProtoChunk();
        }
        return this.carverCache.get(key);
    }

    public void removeInFlight(long key) {
        StatusHolder removed = this.inFlight.remove(key);
        if (removed != null && !removed.isDone()) {
            // Every removal path is supposed to complete the holder's futures first.
            // If one doesn't, callers waiting on getFuture() would hang forever, so
            // complete them here as a last-resort safety net.
            Chunk fallback = removed.getProtoChunk() != null ? removed.getProtoChunk().getChunk() : null;
            WorldFlow.LOGGER.warn("[WorldFlow] removeInFlight dropped an incomplete holder [{}, {}] stage={} ownDone={} workerDone={} carversDone={}; completing futures",
                    removed.getPos().x, removed.getPos().z, removed.getCurrentStatus(),
                    removed.isOwnDecorationDone(), removed.isWorkerDone(), removed.getCarversFuture().isDone(),
                    new Throwable("removeInFlight caller"));
            removed.getCarversFuture().complete(fallback);
            removed.getWorkerDoneFuture().complete(fallback);
            removed.getFuture().complete(fallback);
        }
        drainPendingRequests();
    }

    /** Complete {@code dropped}'s futures with {@code driver}'s results, so no caller is orphaned. */
    private static void chainFutures(StatusHolder driver, StatusHolder dropped) {
        if (driver == null || dropped == null || driver == dropped) {
            return;
        }
        driver.getFuture().whenComplete((c, t) -> {
            if (t != null) dropped.getFuture().completeExceptionally(t);
            else dropped.getFuture().complete(c);
        });
        driver.getCarversFuture().whenComplete((c, t) -> {
            if (t != null) dropped.getCarversFuture().completeExceptionally(t);
            else dropped.getCarversFuture().complete(c);
        });
        driver.getWorkerDoneFuture().whenComplete((c, t) -> {
            if (t != null) dropped.getWorkerDoneFuture().completeExceptionally(t);
            else dropped.getWorkerDoneFuture().complete(c);
        });
    }

    private void drainPendingRequests() {
        int maxInFlight = ModConfig.CONCURRENCY != null ? ModConfig.CONCURRENCY.maxInFlightGenerations : 32;
        while (this.inFlight.size() < maxInFlight && !this.pendingRequestQueue.isEmpty()) {
            StatusHolder holder = this.pendingRequestQueue.poll();
            if (holder == null) break;
            long key = holder.getChunkKey();
            this.pendingRequests.remove(key);
            if (holder.isDone()) continue;

            StatusHolder prev = this.inFlight.putIfAbsent(key, holder);
            if (prev == null) {
                registerPendingWriters(holder);
                advanceChunk(holder);
            } else if (prev != holder) {
                // A different holder already drives this chunk; chain so the queued
                // caller's future still completes instead of being silently dropped.
                chainFutures(prev, holder);
            }
        }
    }

    public void quiesceAndDrain(WorldServer world, long timeoutMs) {
        drainPendingRequests();
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean isServerThread = world.getMinecraftServer() != null && world.getMinecraftServer().isCallingFromMinecraftThread();

        if (isServerThread) {
            // Main server tick thread: pump PublishManager to publish ready chunks while waiting for workers
            while (System.currentTimeMillis() < deadline) {
                PublishManager.INSTANCE.tick(world, Integer.MAX_VALUE);
                boolean hasUnfinished = false;
                for (StatusHolder holder : this.inFlight.values()) {
                    if (holder.getWorld() == world && !holder.isDone()) {
                        if (holder.isWorkerDone() || holder.getCurrentStatus().isAtLeast(ChunkStatus.LIGHT)) {
                            PublishManager.INSTANCE.publishChunk(world, holder);
                        } else {
                            hasUnfinished = true;
                        }
                    }
                }
                if (!hasUnfinished && this.pendingRequestQueue.isEmpty()) {
                    break;
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    break;
                }
            }
            PublishManager.INSTANCE.tick(world, Integer.MAX_VALUE);
        } else {
            // Off-thread caller: await in-flight chunks using getFuture().get(remaining)
            for (StatusHolder holder : this.inFlight.values()) {
                if (holder.getWorld() == world && !holder.isDone()) {
                    long remaining = Math.max(10, deadline - System.currentTimeMillis());
                    try {
                        holder.getFuture().get(remaining, TimeUnit.MILLISECONDS);
                    } catch (Throwable ignored) {
                        Chunk chunk = holder.getProtoChunk().getChunk();
                        if (chunk != null) {
                            world.addScheduledTask(() -> PublishManager.INSTANCE.publishChunk(world, holder));
                        }
                    }
                }
            }
        }

        // 2. Drain serializer queue
        try {
            long remaining = Math.max(50, deadline - System.currentTimeMillis());
            GenerationWorkerPool.getInstance().submitSerializer(() -> {}).get(remaining, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }

        // 3. Flush all staged blocks for this dimension
        com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer.INSTANCE.flushAll(world);

        // 4. Force emit final generation metrics
        forceLogMetrics();
    }

    public int getInFlightCount() {
        return this.inFlight.size();
    }

    public long getParallelCount() {
        return this.parallelCount.get();
    }

    public long getSerializedCount() {
        return this.serializedCount.get();
    }

    public long getTotalBaseGenCount() {
        return this.totalBaseGenCount.get();
    }

    public long getTotalBaseGenNanos() {
        return this.totalBaseGenNanos.get();
    }

    public long getTotalFeaturesCount() {
        return this.totalFeaturesCount.get();
    }

    public long getTotalFeaturesNanos() {
        return this.totalFeaturesNanos.get();
    }
}
