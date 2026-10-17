package com.cleanroommc.worldflow.pipeline;

public interface IWorldFlowChunk {

    enum GenPath {
        UNKNOWN,
        INLINE,
        PIPELINE
    }

    GenPath worldflow$getGenPath();

    void worldflow$setGenPath(GenPath path);
}
