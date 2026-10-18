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

    public boolean canPublish(StatusHolder holder) {
        if (holder == null || !holder.isOwnDecorationDone()) {
            return false;
        }
        int cx = holder.getPos().x;
        int cz = holder.getPos().z;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long nKey = ChunkPos.asLong(cx + dx, cz + dz);
                StatusHolder nHolder = this.inFlight.get(nKey);
                if (nHolder != null) {
                    if (nHolder.getTargetStatus() == ChunkStatus.FULL && !nHolder.isOwnDecorationDone() && !nHolder.isDone()) {
                        return false;
                    }
                    continue;
                }
                StatusHolder pending = this.pendingRequests.get(nKey);
                if (pending != null && pending.getTargetStatus() == ChunkStatus.FULL && !pending.isOwnDecorationDone() && !pending.isDone()) {
                    return false;
                }
            }
        }
        return true;
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
            if (holder.markPublishEnqueued()) {
                PublishManager.INSTANCE.enqueueReady(holder);
            }
        }
        notifyNeighboursOfCompletion(holder.getPos().x, holder.getPos().z);
    }

    public void notifyNeighboursOfCompletion(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long nKey = ChunkPos.asLong(cx + dx, cz + dz);
                StatusHolder nHolder = this.inFlight.get(nKey);
                if (nHolder != null && nHolder.isOwnDecorationDone() && !nHolder.isDone()) {
                    if (canPublish(nHolder)) {
                        if (nHolder.markPublishEnqueued()) {
                            PublishManager.INSTANCE.enqueueReady(nHolder);
                        }
                    }
                }
            }
        }
    }

    private GenerationScheduler() {
        // Start background Deadlock Watchdog daemon
        GenerationWorkerPool.getInstance().scheduleWithFixedDelay(this::runWatchdogCheck, 5, 5, TimeUnit.SECONDS);
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

        // 3. Synchronous / Immediate priority caller: bypass staged pipeline and take vanilla inline path!
        if (priority == GenerationPriority.IMMEDIATE) {
            Chunk chunk = world.getChunkProvider().provideChunk(x, z);
            return CompletableFuture.completedFuture(chunk);
        }

        // 4. Already tracked in pipeline?
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

        if (this.inFlight.size() >= maxInFlight && !isWorkerThread) {
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
            this.inFlight.put(key, pending);
            registerPendingWriters(pending);
            advanceChunk(pending);
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

    public void advanceChunk(StatusHolder holder) {
        if (holder.isDone()) {
            return;
        }

        ChunkStatus current = holder.getCurrentStatus();

        if (current == ChunkStatus.EMPTY) {
            // Stage 0 -> CARVERS: generate base terrain on dedicated BaseGen pool (pure compute, zero dependencies)
            GenerationWorkerPool.getInstance().executeBaseGen(holder, () -> executeBaseGeneration(holder));
        } else if (current == ChunkStatus.CARVERS) {
            // Stage CARVERS -> FEATURES: requires 3x3 neighbours at least at CARVERS
            checkAndScheduleFeatures(holder);
        } else if (current == ChunkStatus.FEATURES || current == ChunkStatus.LIGHT) {
            // Hand off to completeWriter to publish only when no pending writers remain
            completeWriter(holder);
        }
    }

    private void executeBaseGeneration(StatusHolder holder) {
        WorldServer world = holder.getWorld();
        ChunkPos pos = holder.getPos();
        ProtoChunk proto = holder.getProtoChunk();

        long startNanos = System.nanoTime();
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
            WorldFlow.LOGGER.warn("[WorldFlow] Off-thread error in base terrain generation for chunk [{}, {}], falling back to main-thread serial", pos.x, pos.z, t);
            holder.getCarversFuture().complete(proto.getChunk());
            fallbackToMainThreadSerial(holder);
            return;
        } finally {
            ThreadLocalWorldView.exit();
            this.totalBaseGenNanos.addAndGet(System.nanoTime() - startNanos);
            this.totalBaseGenCount.incrementAndGet();
            this.parallelCount.incrementAndGet();
            checkLogMetrics();
        }

        // Cache completed CARVERS ProtoChunk for fast neighbour reuse
        putCarverCache(holder.getChunkKey(), proto);

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

        List<CompletableFuture<Chunk>> neighbourFutures = new ArrayList<>(8);
        Map<Long, ProtoChunk> neighbourhood = new HashMap<>();
        neighbourhood.put(holder.getChunkKey(), holder.getProtoChunk());

        boolean allReady = true;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = cx + dx;
                int nz = cz + dz;
                long nKey = ChunkPos.asLong(nx, nz);

                // 1. Is it loaded in live world?
                Chunk loaded = world.getChunkProvider().getLoadedChunk(nx, nz);
                if (loaded != null) {
                    continue;
                }

                // 2. Is it in carverCache?
                ProtoChunk cached = this.carverCache.get(nKey);
                if (cached != null) {
                    neighbourhood.put(nKey, cached);
                    continue;
                }

                // 3. Is it in flight?
                StatusHolder nHolder = this.inFlight.get(nKey);
                if (nHolder != null) {
                    if (nHolder.getCurrentStatus().isAtLeast(ChunkStatus.CARVERS)) {
                        neighbourhood.put(nKey, nHolder.getProtoChunk());
                    } else {
                        allReady = false;
                        neighbourFutures.add(nHolder.getCarversFuture());
                    }
                    continue;
                }

                // 4. Was it queued in pendingRequests? Promote immediately!
                StatusHolder pending = this.pendingRequests.remove(nKey);
                if (pending != null) {
                    this.inFlight.put(nKey, pending);
                    registerPendingWriters(pending);
                    advanceChunk(pending);
                    allReady = false;
                    neighbourFutures.add(pending.getCarversFuture());
                    continue;
                }

                // 5. Request neighbour to generate to CARVERS
                allReady = false;
                CompletableFuture<Chunk> carverFut = requestCarversChunk(world, nx, nz);
                neighbourFutures.add(carverFut);
            }
        }

        if (allReady) {
            GenerationWorkerPool.getInstance().executeFeatures(holder, () -> executeFeatures(holder, neighbourhood));
        } else {
            // Wait for all missing neighbours to reach CARVERS with a 15-second safety timeout
            CompletableFuture<?> allFutures = CompletableFuture.allOf(neighbourFutures.toArray(new CompletableFuture[0]));
            GenerationWorkerPool.getInstance().scheduleTimeout(allFutures, 15, TimeUnit.SECONDS);

            allFutures.whenCompleteAsync((v, ex) -> {
                if (ex != null) {
                    WorldFlow.LOGGER.warn("[WorldFlow] Neighbour CARVERS wait timed out or failed for chunk [{}, {}], proceeding with best-effort neighbourhood", cx, cz);
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long nKey = ChunkPos.asLong(cx + dx, cz + dz);
                        Chunk loaded = world.getChunkProvider().getLoadedChunk(cx + dx, cz + dz);
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
                            neighbourhood.put(nKey, nHolder.getProtoChunk());
                        }
                    }
                }
                executeFeatures(holder, neighbourhood);
            }, r -> GenerationWorkerPool.getInstance().executeFeatures(holder, r));
        }
    }

    private void executeFeatures(StatusHolder holder, Map<Long, ProtoChunk> neighbourhood) {
        if (IS_DECORATING.get()) {
            WorldFlow.LOGGER.warn("[WorldFlow] Detected recursive feature execution on thread {} for chunk [{}, {}], aborting re-entry",
                    Thread.currentThread().getName(), holder.getPos().x, holder.getPos().z);
            return;
        }
        IS_DECORATING.set(true);

        WorldServer world = holder.getWorld();
        ChunkPos pos = holder.getPos();
        ProtoChunk proto = holder.getProtoChunk();

        com.cleanroommc.worldflow.pipeline.isolation.PopulationHighestBlocksCache.reset();
        long startNanos = System.nanoTime();
        ThreadLocalWorldView.enter(world, pos, proto, "Features");
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

            // 3. Forge GameRegistry world generators (thread-safe and serialized per-generator)
            generateModWorldIsolated(pos.x, pos.z, world, generator, provider, workerRand);

            proto.setTerrainPopulated(true);
            holder.setCurrentStatus(ChunkStatus.LIGHT);
            holder.getWorkerDoneFuture().complete(proto.getChunk());
        } catch (Throwable t) {
            WorldFlow.LOGGER.warn("[WorldFlow] Off-thread error decorating chunk [{}, {}], falling back to main-thread finalize: {}", pos.x, pos.z, t.toString());
            fallbackToMainThreadSerial(holder);
            return;
        } finally {
            ThreadLocalWorldView.exit();
            this.totalFeaturesNanos.addAndGet(System.nanoTime() - startNanos);
            this.totalFeaturesCount.incrementAndGet();
            checkLogMetrics();
            IS_DECORATING.set(false);
        }

        // Complete this writer and publish any chunks whose pending writers have reached 0
        completeWriter(holder);
    }

    private void fallbackToMainThreadSerial(StatusHolder holder) {
        WorldServer world = holder.getWorld();
        ChunkPos pos = holder.getPos();
        world.addScheduledTask(() -> {
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
                Chunk chunk = world.getChunkProvider().provideChunk(pos.x, pos.z);
                holder.getProtoChunk().setUnderlyingChunk(chunk);
                holder.getProtoChunk().setTerrainPopulated(true);
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
        });
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
                try {
                    acquired = lock.tryLock(10, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (acquired) {
                    try {
                        generator.generate(fmlRandom, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
                    } finally {
                        lock.unlock();
                    }
                } else {
                    WorldFlow.LOGGER.warn("[WorldFlow] Timed out acquiring lock for generator {} in chunk [{}, {}]", generator.getClass().getName(), chunkX, chunkZ);
                }
            } else if (safety == GeneratorSafetyLevel.PARALLEL_SAFE) {
                this.parallelCount.incrementAndGet();
                generator.generate(fmlRandom, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
            } else if (safety == GeneratorSafetyLevel.UNSAFE) {
                this.serializedCount.incrementAndGet();
                recordSerializedGenerator(generator.getClass().getName());
                try {
                    GenerationWorkerPool.getInstance().submitSerializer(() -> {
                        generator.generate(fmlRandom, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
                    }).get(10, TimeUnit.SECONDS);
                } catch (Throwable t) {
                    WorldFlow.LOGGER.warn("[WorldFlow] UNSAFE generator {} timed out or failed in chunk [{}, {}]", generator.getClass().getName(), chunkX, chunkZ, t);
                }
            }
        }
        checkLogMetrics();
    }

    private void runWatchdogCheck() {
        int inFlightCount = this.inFlight.size();
        long published = PublishManager.INSTANCE.totalChunksPublished.get();
        long now = System.currentTimeMillis();

        if (published > this.watchdogLastPublished.get()) {
            this.watchdogLastPublished.set(published);
            this.watchdogLastProgressTime.set(now);
            this.watchdogTriggered.set(false);
            return;
        }

        long stallDuration = now - this.watchdogLastProgressTime.get();
        if (stallDuration >= 5000L) {
            if (inFlightCount > 0) {
                for (StatusHolder h : this.inFlight.values()) {
                    long ageMs = (System.nanoTime() - h.getSubmissionTimeNanos()) / 1_000_000L;
                    if (ageMs > 5000L && !h.isDone()) {
                        WorldFlow.LOGGER.warn(String.format(
                                "[WorldFlow StallDetector] Stuck chunk [%d,%d] ownDone=%b pendingWriters=%d owed by: %s",
                                h.getPos().x, h.getPos().z, h.isOwnDecorationDone(), h.getPendingWriters(), getOwedBy(h)
                        ));
                        if (h.isOwnDecorationDone()) {
                            if (h.markPublishEnqueued()) {
                                PublishManager.INSTANCE.enqueueReady(h);
                            }
                        } else {
                            fallbackToMainThreadSerial(h);
                        }
                    }
                }
            } else if (!this.pendingRequests.isEmpty()) {
                drainPendingRequests();
            }
        }
    }

    private void putCarverCache(long key, ProtoChunk proto) {
        if (this.carverCache.size() > 512) {
            Iterator<Long> it = this.carverCache.keySet().iterator();
            int evicted = 0;
            while (it.hasNext() && evicted < 64) {
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

        WorldFlow.LOGGER.info(String.format(
                "[WorldFlow Metrics] Rate: %.1f chunks/s (%.0f chunks/min) | Published: %d | path=pipeline:%d / path=inline:%d | Queue Depth: %d | In-Flight: %d | Avg BaseGen: %.1fms | Avg Features: %.1fms | Serialized: %d | Parallel: %d | Guards blocked: %d | Active Workers: %d",
                chunksPerSec,
                chunksPerMin,
                totalPublished,
                pipeCount,
                inlineCount,
                queueDepth,
                this.inFlight.size(),
                avgBaseMs,
                avgFeatMs,
                this.serializedCount.get(),
                this.parallelCount.get(),
                this.guardsBlockedCount.get(),
                GenerationWorkerPool.getInstance().getActiveWorkerCount()
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
    public ProtoChunk getProtoChunkAny(long key) {
        StatusHolder holder = this.inFlight.get(key);
        if (holder != null) {
            return holder.getProtoChunk();
        }
        return this.carverCache.get(key);
    }

    public void removeInFlight(long key) {
        this.inFlight.remove(key);
        drainPendingRequests();
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
                            PublishManager.INSTANCE.publishChunk(world, holder);
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
