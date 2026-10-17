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

    private volatile ChunkStatus targetStatus;
    private final AtomicInteger priorityScore = new AtomicInteger(0);
    private final long submissionTimeNanos;

    public StatusHolder(WorldServer world, int x, int z, ChunkStatus targetStatus, GenerationPriority priority) {
        this.world = world;
        this.pos = new ChunkPos(x, z);
        this.chunkKey = ChunkPos.asLong(x, z);
        this.targetStatus = targetStatus;
        this.protoChunk = new ProtoChunk(world, x, z);
        this.submissionTimeNanos = System.nanoTime();
        this.priorityScore.set(priority.getWeight() * 10000);
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
