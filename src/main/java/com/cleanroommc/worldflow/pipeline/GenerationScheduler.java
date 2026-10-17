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
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates staged chunk generation dependencies, worker execution, and backpressure.
 */
public class GenerationScheduler {

    private static final GenerationScheduler INSTANCE = new GenerationScheduler();

    private final Map<Long, StatusHolder> inFlight = new ConcurrentHashMap<>();
    public final AtomicLong totalGenerationsRequested = new AtomicLong(0);
    private final long pipelineStartTimeMs = System.currentTimeMillis();
    private final AtomicLong totalBaseGenNanos = new AtomicLong(0);
    private final AtomicLong totalBaseGenCount = new AtomicLong(0);
    private final AtomicLong totalFeaturesNanos = new AtomicLong(0);
    private final AtomicLong totalFeaturesCount = new AtomicLong(0);

    public static GenerationScheduler getInstance() {
        return INSTANCE;
    }

    private GenerationScheduler() {
    }

    public CompletableFuture<Chunk> requestChunk(WorldServer world, int x, int z, GenerationPriority priority) {
        return requestChunk(world, x, z, ChunkStatus.FULL, priority);
    }

    public CompletableFuture<Chunk> requestChunk(WorldServer world, int x, int z, ChunkStatus targetStatus, GenerationPriority priority) {
        long key = ChunkPos.asLong(x, z);
        totalGenerationsRequested.incrementAndGet();

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
            existing.setTargetStatus(targetStatus);
            return existing.getFuture();
        }

        // 5. In-flight backpressure / memory guard: Cap maximum concurrent in-flight generations
        int maxInFlight = ModConfig.CONCURRENCY != null ? ModConfig.CONCURRENCY.maxInFlightGenerations : 64;
        if (this.inFlight.size() >= maxInFlight) {
            long waitStart = System.currentTimeMillis();
            while (this.inFlight.size() >= maxInFlight && (System.currentTimeMillis() - waitStart < 50)) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ignored) {}
            }
        }

        // 6. Register new StatusHolder
        StatusHolder holder = new StatusHolder(world, x, z, targetStatus, priority);
        StatusHolder prev = this.inFlight.putIfAbsent(key, holder);
        if (prev != null) {
            prev.bumpPriority(priority);
            prev.setTargetStatus(targetStatus);
            return prev.getFuture();
        }

        // 7. Begin staged pipeline execution
        advanceChunk(holder);
        return holder.getFuture();
    }

    public void advanceChunk(StatusHolder holder) {
        if (holder.isDone()) {
            return;
        }

        ChunkStatus current = holder.getCurrentStatus();

        if (current == ChunkStatus.EMPTY) {
            // Stage 0 -> CARVERS: generate base terrain (noise, surface, carvers)
            GenerationWorkerPool.getInstance().executeWorker(holder, () -> executeBaseGeneration(holder));
        } else if (current == ChunkStatus.CARVERS) {
            // Stage CARVERS -> FEATURES: requires 3x3 neighbours at least at CARVERS
            checkAndScheduleFeatures(holder);
        } else if (current == ChunkStatus.FEATURES || current == ChunkStatus.LIGHT) {
            // Hand off to publish manager
            PublishManager.INSTANCE.enqueueReady(holder);
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
            holder.setCurrentStatus(ChunkStatus.CARVERS);
        } catch (Throwable t) {
            WorldFlow.LOGGER.warn("[WorldFlow] Off-thread error in base terrain generation for chunk [{}, {}], falling back to main-thread serial", pos.x, pos.z, t);
            fallbackToMainThreadSerial(holder);
            return;
        } finally {
            ThreadLocalWorldView.exit();
            this.totalBaseGenNanos.addAndGet(System.nanoTime() - startNanos);
            this.totalBaseGenCount.incrementAndGet();
        }

        // If target was only CARVERS (e.g. neighbour dependency), complete future now
        if (holder.getTargetStatus() == ChunkStatus.CARVERS) {
            holder.getFuture().complete(proto.getChunk());
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

                Chunk loaded = world.getChunkProvider().getLoadedChunk(nx, nz);
                if (loaded != null) {
                    continue;
                }

                StatusHolder nHolder = this.inFlight.get(nKey);
                if (nHolder != null && nHolder.getCurrentStatus().isAtLeast(ChunkStatus.CARVERS)) {
                    neighbourhood.put(nKey, nHolder.getProtoChunk());
                } else {
                    allReady = false;
                    // Request neighbour to reach at least CARVERS
                    CompletableFuture<Chunk> nf = requestChunk(world, nx, nz, ChunkStatus.CARVERS, GenerationPriority.PLAYER);
                    neighbourFutures.add(nf);
                }
            }
        }

        IChunkGenerator generator = WorkerGeneratorContext.get().getChunkGenerator(world);
        boolean isSerialized = CompatRegistry.INSTANCE.getSafetyLevel(generator) == GeneratorSafetyLevel.SERIALIZE;

        if (allReady) {
            if (isSerialized) {
                GenerationWorkerPool.getInstance().executeSerializer(() -> executeFeatures(holder, neighbourhood));
            } else {
                GenerationWorkerPool.getInstance().executeWorker(holder, () -> executeFeatures(holder, neighbourhood));
            }
        } else {
            // Wait for all missing neighbours to reach CARVERS, then schedule features
            CompletableFuture.allOf(neighbourFutures.toArray(new CompletableFuture[0]))
                    .thenRunAsync(() -> {
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                long nKey = ChunkPos.asLong(cx + dx, cz + dz);
                                StatusHolder nHolder = this.inFlight.get(nKey);
                                if (nHolder != null) {
                                    neighbourhood.put(nKey, nHolder.getProtoChunk());
                                }
                            }
                        }
                        executeFeatures(holder, neighbourhood);
                    }, isSerialized ? GenerationWorkerPool.getInstance()::executeSerializer : r -> GenerationWorkerPool.getInstance().executeWorker(holder, r));
        }
    }

    private void executeFeatures(StatusHolder holder, Map<Long, ProtoChunk> neighbourhood) {
        WorldServer world = holder.getWorld();
        ChunkPos pos = holder.getPos();
        ProtoChunk proto = holder.getProtoChunk();

        long startNanos = System.nanoTime();
        ThreadLocalWorldView.enter(world, pos, proto, "Features");
        ThreadLocalWorldView.attachNeighbourhood(neighbourhood);
        try {
            ChunkProviderServer provider = world.getChunkProvider();
            IChunkGenerator generator = WorkerGeneratorContext.get().getChunkGenerator(world);
            Random workerRand = WorkerGeneratorContext.get().rand;

            // 1. PopulateChunkEvent.Pre (Unsynchronized to prevent bus deadlocks; wrapped safely)
            try {
                MinecraftForge.EVENT_BUS.post(new PopulateChunkEvent.Pre(generator, world, workerRand, pos.x, pos.z, false));
            } catch (Throwable t) {
                WorldFlow.LOGGER.debug("[WorldFlow] Exception in PopulateChunkEvent.Pre listener", t);
            }

            // 2. Standard population (ores, flora, structures)
            GeneratorSafetyLevel genSafety = CompatRegistry.INSTANCE.getSafetyLevel(generator);
            if (genSafety == GeneratorSafetyLevel.SERIALIZE) {
                this.serializedCount.incrementAndGet();
                OTGParallelContext.OTG_STRUCTURE_LOCK.lock();
                try {
                    generator.populate(pos.x, pos.z);
                } finally {
                    OTGParallelContext.OTG_STRUCTURE_LOCK.unlock();
                }
            } else {
                generator.populate(pos.x, pos.z);
            }

            // 3. Forge GameRegistry world generators (thread-safe and serialized per-generator)
            generateModWorldIsolated(pos.x, pos.z, world, generator, provider, workerRand);

            // Note: PopulateChunkEvent.Post is redispatched on the server tick thread in PublishManager!

            proto.setTerrainPopulated(true);
            holder.setCurrentStatus(ChunkStatus.LIGHT);
        } catch (Throwable t) {
            WorldFlow.LOGGER.warn("[WorldFlow] Off-thread error decorating chunk [{}, {}], falling back to main-thread finalize", pos.x, pos.z, t);
            fallbackToMainThreadSerial(holder);
            return;
        } finally {
            ThreadLocalWorldView.exit();
            this.totalFeaturesNanos.addAndGet(System.nanoTime() - startNanos);
            this.totalFeaturesCount.incrementAndGet();
        }

        // Ready for publish to main thread
        PublishManager.INSTANCE.enqueueReady(holder);
    }

    private void fallbackToMainThreadSerial(StatusHolder holder) {
        WorldServer world = holder.getWorld();
        ChunkPos pos = holder.getPos();
        world.addScheduledTask(() -> {
            try {
                // If already published/loaded, complete future and clean up
                Chunk loaded = world.getChunkProvider().getLoadedChunk(pos.x, pos.z);
                if (loaded != null) {
                    holder.setCurrentStatus(ChunkStatus.FULL);
                    holder.getFuture().complete(loaded);
                    removeInFlight(holder.getChunkKey());
                    return;
                }
                // Complete generation serially on the main server thread
                Chunk chunk = holder.getProtoChunk().getChunk();
                if (chunk == null || !holder.getCurrentStatus().isAtLeast(ChunkStatus.CARVERS)) {
                    chunk = world.getChunkProvider().chunkGenerator.generateChunk(pos.x, pos.z);
                    if (chunk == null) {
                        chunk = new Chunk(world, pos.x, pos.z);
                    }
                    holder.getProtoChunk().setUnderlyingChunk(chunk);
                }
                holder.getProtoChunk().setTerrainPopulated(true);
                PublishManager.INSTANCE.publishChunk(world, holder);
            } catch (Throwable fallbackErr) {
                WorldFlow.LOGGER.error("[WorldFlow] Main-thread serial fallback failed for chunk [{}, {}]", pos.x, pos.z, fallbackErr);
                Chunk fallbackChunk = holder.getProtoChunk().getChunk();
                if (fallbackChunk == null) {
                    fallbackChunk = new Chunk(world, pos.x, pos.z);
                }
                holder.getFuture().complete(fallbackChunk);
                removeInFlight(holder.getChunkKey());
            }
        });
    }

    private static volatile List<IWorldGenerator> cachedSortedGenerators;
    private final AtomicLong lastMetricsLog = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong parallelCount = new AtomicLong(0);
    private final AtomicLong serializedCount = new AtomicLong(0);

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
                // Fallback to standard GameRegistry.generateWorld if reflection fails
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
            fmlRandom.setSeed(chunkSeed);
            GeneratorSafetyLevel safety = CompatRegistry.INSTANCE.getSafetyLevel(generator);
            if (safety == GeneratorSafetyLevel.SERIALIZE) {
                this.serializedCount.incrementAndGet();
                synchronized (generator) {
                    generator.generate(fmlRandom, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
                }
            } else if (safety == GeneratorSafetyLevel.PARALLEL_SAFE) {
                this.parallelCount.incrementAndGet();
                generator.generate(fmlRandom, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
            } else if (safety == GeneratorSafetyLevel.UNSAFE) {
                this.serializedCount.incrementAndGet();
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

    private void checkLogMetrics() {
        long now = System.currentTimeMillis();
        long last = this.lastMetricsLog.get();
        if (now - last > 30000L && this.lastMetricsLog.compareAndSet(last, now)) {
            long totalPublished = PublishManager.INSTANCE.totalChunksPublished.get();
            double elapsedSec = Math.max(1.0, (now - this.pipelineStartTimeMs) / 1000.0);
            double chunksPerSec = totalPublished / elapsedSec;
            double chunksPerMin = chunksPerSec * 60.0;

            long baseCount = this.totalBaseGenCount.get();
            double avgBaseMs = baseCount > 0 ? (this.totalBaseGenNanos.get() / 1_000_000.0) / baseCount : 0.0;

            long featCount = this.totalFeaturesCount.get();
            double avgFeatMs = featCount > 0 ? (this.totalFeaturesNanos.get() / 1_000_000.0) / featCount : 0.0;

            WorldFlow.LOGGER.info(String.format(
                    "[WorldFlow Metrics] Rate: %.1f chunks/s (%.0f chunks/min) | Published: %d | Avg BaseGen: %.1fms | Avg Features: %.1fms | In-Flight: %d | Parallel: %d | Serialized: %d | Active Workers: %d",
                    chunksPerSec,
                    chunksPerMin,
                    totalPublished,
                    avgBaseMs,
                    avgFeatMs,
                    this.inFlight.size(),
                    this.parallelCount.get(),
                    this.serializedCount.get(),
                    GenerationWorkerPool.getInstance().getActiveWorkerCount()
            ));
        }
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
            try {
                return holder.getFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
            }
            if (holder.getProtoChunk() != null && holder.getProtoChunk().getChunk() != null) {
                PublishManager.INSTANCE.publishChunk(world, holder);
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

    public void removeInFlight(long key) {
        this.inFlight.remove(key);
    }

    public void quiesceAndDrain(WorldServer world, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        // 1. Await or complete in-flight chunks belonging to this world
        for (Map.Entry<Long, StatusHolder> entry : this.inFlight.entrySet()) {
            StatusHolder holder = entry.getValue();
            if (holder != null && holder.getWorld() == world && !holder.isDone()) {
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
        // 2. Drain serializer queue
        try {
            long remaining = Math.max(50, deadline - System.currentTimeMillis());
            GenerationWorkerPool.getInstance().submitSerializer(() -> {}).get(remaining, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
        // 3. Flush all staged blocks for this dimension
        com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer.INSTANCE.flushAll(world);
    }

    public int getInFlightCount() {
        return this.inFlight.size();
    }
}
