package com.cleanroommc.worldflow.pipeline;

/**
 * Explicit stages for chunk generation inspired by 1.18+ / Paper / Folia architecture,
 * adapted specifically for Minecraft 1.12.2 and Cleanroom.
 *
 * Each stage declares a radius of neighbour chunk dependencies required before
 * it can execute.
 */
public enum ChunkStatus {

    /**
     * Freshly registered chunk position, memory structures uninitialized.
     */
    EMPTY(0, 0, "empty"),

    /**
     * Computes structure starting coordinates and seeds.
     */
    STRUCTURE_STARTS(1, 0, "structure_starts"),

    /**
     * Resolves references to structures originating in neighbouring chunks (radius 8).
     */
    STRUCTURE_REFS(2, 8, "structure_refs"),

    /**
     * Generates or fetches biome distribution for this chunk.
     */
    BIOMES(3, 0, "biomes"),

    /**
     * Generates 3D density noise and initial chunk block primer.
     */
    NOISE(4, 0, "noise"),

    /**
     * Applies surface replacements (grass, dirt, sand, gravel, bedrock floor/ceiling).
     */
    SURFACE(5, 0, "surface"),

    /**
     * Carves underground caves and ravines (MapGenCaves, MapGenRavine).
     */
    CARVERS(6, 0, "carvers"),

    /**
     * Feature decorators, ores, trees, structures, modded IWorldGenerator and PopulateChunkEvent.
     * Requires a 3x3 neighbourhood of neighbours to have completed CARVERS.
     */
    FEATURES(7, 1, "features"),

    /**
     * Skylight and block light propagation and calculation.
     */
    LIGHT(8, 1, "light"),

    /**
     * Full chunk, published to live ChunkProviderServer.loadedChunks on the main thread.
     */
    FULL(9, 0, "full");

    private final int ordinalIndex;
    private final int taskRadius;
    private final String name;

    ChunkStatus(int ordinalIndex, int taskRadius, String name) {
        this.ordinalIndex = ordinalIndex;
        this.taskRadius = taskRadius;
        this.name = name;
    }

    public int getOrdinalIndex() {
        return this.ordinalIndex;
    }

    public int getTaskRadius() {
        return this.taskRadius;
    }

    public String getName() {
        return this.name;
    }

    public boolean isAtLeast(ChunkStatus other) {
        return this.ordinalIndex >= other.ordinalIndex;
    }

    public ChunkStatus getNext() {
        ChunkStatus[] values = values();
        int nextIndex = this.ordinalIndex + 1;
        return nextIndex < values.length ? values[nextIndex] : FULL;
    }

    public static ChunkStatus byOrdinal(int ordinal) {
        ChunkStatus[] values = values();
        if (ordinal < 0) return EMPTY;
        if (ordinal >= values.length) return FULL;
        return values[ordinal];
    }
}
