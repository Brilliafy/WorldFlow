package com.cleanroommc.worldflow.pipeline.compat;

public enum GeneratorSafetyLevel {

    /**
     * Stateless or per-worker isolated generators that can safely run concurrently on
     * any worker thread (vanilla ore distribution, simple decorators).
     */
    PARALLEL_SAFE,

    /**
     * Complex multi-chunk structure generators or generators that maintain global mutable state
     * (OTG custom structures, Recurrent Complex, BattleTowers, Roguelike Dungeons).
     * Serialized through a dedicated off-tick generator lever thread with ThreadLocalWorldView
     * isolation, so they never race or trigger cascading chunk loads.
     */
    SERIALIZE,

    /**
     * Generators strictly incompatible with off-thread execution.
     * Defer execution to the main server tick thread during chunk publication.
     */
    UNSAFE
}
