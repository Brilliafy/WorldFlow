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
    private final AtomicInteger priorityScore = new AtomicInteger(0);
    private final long submissionTimeNanos;
    private final GenerationPriority initialPriority;
    private volatile boolean skipGhostCleanup = false;

    private final AtomicInteger pendingWriters = new AtomicInteger(1);
    private final java.util.concurrent.atomic.AtomicBoolean ownDecorationDone = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean publishEnqueued = new java.util.concurrent.atomic.AtomicBoolean(false);

    public StatusHolder(WorldServer world, int x, int z, ChunkStatus targetStatus, GenerationPriority priority) {
        this.world = world;
        this.pos = new ChunkPos(x, z);
        this.chunkKey = ChunkPos.asLong(x, z);
        this.targetStatus = targetStatus;
        this.initialPriority = priority != null ? priority : GenerationPriority.BACKGROUND;
        this.protoChunk = new ProtoChunk(world, x, z);
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

    public ChunkStatus getTargetStatus() {
        return this.targetStatus;
    }

    public void setTargetStatus(ChunkStatus status) {
        if (status.isAtLeast(this.targetStatus)) {
            this.targetStatus = status;
        }
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
        return this.pendingWriters.get();
    }

    public int incrementPendingWriters() {
        return this.pendingWriters.incrementAndGet();
    }

    public int decrementPendingWriters() {
        return this.pendingWriters.decrementAndGet();
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
