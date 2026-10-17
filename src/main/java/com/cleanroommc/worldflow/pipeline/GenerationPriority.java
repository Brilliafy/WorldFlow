package com.cleanroommc.worldflow.pipeline;

public enum GenerationPriority {

    /**
     * Requested directly by synchronous code (commands, teleports, immediate queries).
     */
    IMMEDIATE(3),

    /**
     * Requested for a nearby player by PlayerChunkMap.
     */
    PLAYER(2),

    /**
     * Batch generation triggered by /worldflow pregen.
     */
    PREGENERATOR(1),

    /**
     * Speculative or background chunk preparation.
     */
    BACKGROUND(0);

    private final int weight;

    GenerationPriority(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return this.weight;
    }
}
