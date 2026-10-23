package com.cleanroommc.worldflow.pipeline;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks a chunk in-flight through the parallel generation pipeline,
 * including its dependencies, futures, priority, and ProtoChunk state.
 */
public class StatusHolder implements Comparable<StatusHolder> {

    private final WorldServer world;
    private final ChunkPos pos;
    private final long chunkKey;
    private final ProtoChunk protoChunk;
    private final CompletableFuture<Chunk> future = new CompletableFuture<>();
    private final CompletableFuture<Chunk> carversFuture = new CompletableFuture<>();
    private final CompletableFuture<Chunk> workerDoneFuture = new CompletableFuture<>();

    private volatile ChunkStatus targetStatus;
    private boolean carverOnlyFinished;
    private final AtomicInteger priorityScore = new AtomicInteger(0);
    private final long submissionTimeNanos;
    private final GenerationPriority initialPriority;
    private volatile boolean skipGhostCleanup = false;

    private final java.util.concurrent.atomic.AtomicBoolean ownDecorationDone = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean publishEnqueued = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean baseGenScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean baseGenRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long baseGenScheduledAtNanos = 0L;
    private final java.util.concurrent.atomic.AtomicBoolean featuresScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean featuresRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long featuresScheduledAtNanos = 0L;
    private volatile String lastWorkerName = "none";

    /** 3x3 stencil snapshot used by the feature stage; kept so a main-thread retry
     *  can re-run the exact same population instead of publishing a partial chunk. */
    private volatile java.util.Map<Long, ProtoChunk> featureNeighbourhood;

    /** First time the 3x3 phase-dep gate blocked this chunk (advisory timeout). */
    private volatile long phaseDepWaitStartNanos = 0L;

    /** Guards the one allowed main-thread regeneration retry. */
    private final java.util.concurrent.atomic.AtomicBoolean mainThreadRetry =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 4-phase parity: ((x & 1) << 1) | (z & 1). */
    private final int phase;
    /** Earlier-phase target neighbours in this chunk's 3x3 stencil. */
    private final AtomicInteger pendingPhaseDeps = new AtomicInteger(0);
    /** Target chunks in this chunk's 3x3 stencil whose Stage 3/4 results may read it. */
    private final AtomicInteger stencilTargetRefs = new AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicBoolean stencilReleased = new java.util.concurrent.atomic.AtomicBoolean(false);

    public StatusHolder(WorldServer world, int x, int z, ChunkStatus targetStatus, GenerationPriority priority) {
        this.world = world;
        this.pos = new ChunkPos(x, z);
        this.chunkKey = ChunkPos.asLong(x, z);
        this.targetStatus = targetStatus;
        this.initialPriority = priority != null ? priority : GenerationPriority.BACKGROUND;
        this.protoChunk = new ProtoChunk(world, x, z);
        this.phase = ((x & 1) << 1) | (z & 1);
        this.submissionTimeNanos = System.nanoTime();
        this.priorityScore.set(this.initialPriority.getWeight() * 10000);
        if (this.initialPriority == GenerationPriority.IMMEDIATE || this.initialPriority == GenerationPriority.PREGENERATOR) {
            this.skipGhostCleanup = true;
        }
    }

    public WorldServer getWorld() {
        return this.world;
    }

    public ChunkPos getPos() {
        return this.pos;
    }

    public long getChunkKey() {
        return this.chunkKey;
    }

    public ProtoChunk getProtoChunk() {
        return this.protoChunk;
    }

    public java.util.Map<Long, ProtoChunk> getFeatureNeighbourhood() {
        return this.featureNeighbourhood;
    }

    public void setFeatureNeighbourhood(java.util.Map<Long, ProtoChunk> neighbourhood) {
        this.featureNeighbourhood = neighbourhood;
    }

    public long getPhaseDepWaitStartNanos() {
        return this.phaseDepWaitStartNanos;
    }

    public void markPhaseDepWaitStart() {
        if (this.phaseDepWaitStartNanos == 0L) {
            this.phaseDepWaitStartNanos = System.nanoTime();
        }
    }

    /** @return true only for the first caller, i.e. the one retry we allow. */
    public boolean markMainThreadRetry() {
        return this.mainThreadRetry.compareAndSet(false, true);
    }

    public CompletableFuture<Chunk> getFuture() {
        return this.future;
    }

    public CompletableFuture<Chunk> getCarversFuture() {
        return this.carversFuture;
    }

    public CompletableFuture<Chunk> getWorkerDoneFuture() {
        return this.workerDoneFuture;
    }

    public boolean isWorkerDone() {
        return this.workerDoneFuture.isDone();
    }

    public boolean isCarversDone() {
        return this.carversFuture.isDone();
    }

    public GenerationPriority getInitialPriority() {
        return this.initialPriority;
    }

    public boolean isSkipGhostCleanup() {
        return this.skipGhostCleanup;
    }

    public void setSkipGhostCleanup(boolean skip) {
        this.skipGhostCleanup = skip;
    }

    public boolean isPlayerRequested() {
        return this.initialPriority == GenerationPriority.PLAYER;
    }

    public ChunkStatus getCurrentStatus() {
        return this.protoChunk.getStatus();
    }

    public void setCurrentStatus(ChunkStatus status) {
        this.protoChunk.setStatus(status);
    }

    public synchronized ChunkStatus getTargetStatus() {
        return this.targetStatus;
    }

    public synchronized void setTargetStatus(ChunkStatus status) {
        if (status.isAtLeast(this.targetStatus)) {
            this.targetStatus = status;
        }
    }

    /**
     * Atomically decides whether this holder is a CARVERS-only neighbour request.
     * If a FULL request upgrades the target first, this returns false and base gen
     * continues to features instead of discarding the holder. A late upgrader sees
     * {@link #isCarverOnlyFinished()} and creates a fresh FULL holder.
     */
    public synchronized boolean tryFinishCarverOnly() {
        if (this.targetStatus == ChunkStatus.CARVERS) {
            this.carverOnlyFinished = true;
            return true;
        }
        return false;
    }

    public synchronized boolean isCarverOnlyFinished() {
        return this.carverOnlyFinished;
    }

    public int getPriorityScore() {
        return this.priorityScore.get();
    }

    public void bumpPriority(GenerationPriority priority) {
        int score = priority.getWeight() * 10000;
        this.priorityScore.accumulateAndGet(score, Math::max);
    }

    public void adjustPriorityScore(int scoreDelta) {
        this.priorityScore.addAndGet(scoreDelta);
    }

    public long getSubmissionTimeNanos() {
        return this.submissionTimeNanos;
    }

    public boolean isDone() {
        return this.future.isDone() || this.getCurrentStatus() == ChunkStatus.FULL;
    }

    public int getPendingWriters() {
        return GenerationScheduler.getInstance().getPendingWritersCount(this);
    }

    public int incrementPendingWriters() {
        return getPendingWriters();
    }

    public int decrementPendingWriters() {
        return getPendingWriters();
    }

    public boolean isOwnDecorationDone() {
        return this.ownDecorationDone.get();
    }

    public void setOwnDecorationDone(boolean done) {
        this.ownDecorationDone.set(done);
    }

    public boolean markPublishEnqueued() {
        return this.publishEnqueued.compareAndSet(false, true);
    }

    public boolean isPublishEnqueued() {
        return this.publishEnqueued.get();
    }

    public void resetPublishEnqueued() {
        this.publishEnqueued.set(false);
    }

    /** True exactly once: guards against duplicate BaseGen submissions. */
    public boolean markBaseGenScheduled() {
        boolean ok = this.baseGenScheduled.compareAndSet(false, true);
        if (ok) {
            this.baseGenScheduledAtNanos = System.nanoTime();
        }
        return ok;
    }

    public void resetBaseGenScheduled() {
        this.baseGenScheduled.set(false);
    }

    public boolean isBaseGenScheduled() {
        return this.baseGenScheduled.get();
    }

    public void setBaseGenRunning(boolean running) {
        this.baseGenRunning.set(running);
    }

    public boolean isBaseGenRunning() {
        return this.baseGenRunning.get();
    }

    public long getBaseGenScheduledAtNanos() {
        return this.baseGenScheduledAtNanos;
    }

    /** True exactly once: guards against duplicate feature submissions. */
    public boolean markFeaturesScheduled() {
        boolean ok = this.featuresScheduled.compareAndSet(false, true);
        if (ok) {
            this.featuresScheduledAtNanos = System.nanoTime();
        }
        return ok;
    }

    public boolean isFeaturesScheduled() {
        return this.featuresScheduled.get();
    }

    public long getFeaturesScheduledAtNanos() {
        return this.featuresScheduledAtNanos;
    }

    /** Allow a retry after a wedged/stuck feature task. */
    public void resetFeaturesScheduled() {
        this.featuresScheduled.set(false);
    }

    /** Set while a feature task is actually executing on a worker. */
    public void setFeaturesRunning(boolean running) {
        this.featuresRunning.set(running);
    }

    public boolean isFeaturesRunning() {
        return this.featuresRunning.get();
    }

    public int getPhase() {
        return this.phase;
    }

    public int getPendingPhaseDeps() {
        return this.pendingPhaseDeps.get();
    }

    public void initPendingPhaseDeps(int count) {
        this.pendingPhaseDeps.set(Math.max(0, count));
    }

    public int decrementPhaseDeps() {
        return this.pendingPhaseDeps.decrementAndGet();
    }

    public int getStencilTargetRefs() {
        return this.stencilTargetRefs.get();
    }

    public void initStencilTargetRefs(int count) {
        this.stencilTargetRefs.set(Math.max(0, count));
    }

    public int decrementStencilTargetRefs() {
        return this.stencilTargetRefs.decrementAndGet();
    }

    public boolean markStencilReleased() {
        return this.stencilReleased.compareAndSet(false, true);
    }

    public boolean isStencilReleased() {
        return this.stencilReleased.get();
    }

    public String getLastWorkerName() {
        return this.lastWorkerName;
    }

    public void setLastWorkerName(String lastWorkerName) {
        this.lastWorkerName = lastWorkerName;
    }

    @Override
    public int compareTo(StatusHolder o) {
        int p = Integer.compare(o.priorityScore.get(), this.priorityScore.get());
        if (p != 0) {
            return p;
        }
        return Long.compare(this.submissionTimeNanos, o.submissionTimeNanos);
    }

    @Override
    public String toString() {
        return "StatusHolder{" +
                "pos=" + this.pos +
                ", status=" + this.getCurrentStatus() +
                ", target=" + this.targetStatus +
                ", priority=" + this.priorityScore.get() +
                '}';
    }
}
