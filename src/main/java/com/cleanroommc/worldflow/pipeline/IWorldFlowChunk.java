package com.cleanroommc.worldflow.pipeline;

public interface IWorldFlowChunk {

    enum GenPath {
        UNKNOWN,
        INLINE,
        PIPELINE
    }

    GenPath worldflow$getGenPath();

    void worldflow$setGenPath(GenPath path);

    boolean worldflow$isPublished();

    void worldflow$setPublished(boolean published);

    /** One lock per Chunk instance, shared by every ProtoChunk wrapper around it. */
    java.util.concurrent.locks.ReentrantLock worldflow$chunkLock();
}
