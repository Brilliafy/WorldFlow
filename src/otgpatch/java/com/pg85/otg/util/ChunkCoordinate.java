/*
 * Decompiled with CFR 0.152.
 */
package com.pg85.otg.util;

import com.pg85.otg.util.helpers.MathHelper;

public class ChunkCoordinate {
    public static final int CHUNK_SIZE = 16;
    public static final int CHUNK_Y_SIZE = 256;
    private static final int CHUNK_POPULATION_OFFSET_X = 7;
    private static final int CHUNK_POPULATION_OFFSET_Z = 7;
    private final int chunkX;
    private final int chunkZ;

    private ChunkCoordinate(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public int getChunkX() {
        return this.chunkX;
    }

    public int getChunkZ() {
        return this.chunkZ;
    }

    public int hashCode() {
        int result = 1;
        result = 31 * result + this.chunkX;
        result = 31 * result + this.chunkZ;
        return result;
    }

    public boolean equals(Object otherObject) {
        if (otherObject == this) {
            return true;
        }
        if (otherObject == null) {
            return false;
        }
        if (!(otherObject instanceof ChunkCoordinate)) {
            return false;
        }
        ChunkCoordinate otherChunkCoordinate = (ChunkCoordinate)otherObject;
        if (otherChunkCoordinate.chunkX != this.chunkX) {
            return false;
        }
        return otherChunkCoordinate.chunkZ == this.chunkZ;
    }

    public static ChunkCoordinate getPopulatingChunk(int blockX, int blockZ) {
        return new ChunkCoordinate(blockX - 7 >> 4, blockZ - 7 >> 4);
    }

    private static final int CACHE_SIZE = 256;

    private static final class Cache {
        final long[] keys = new long[CACHE_SIZE];
        final ChunkCoordinate[] values = new ChunkCoordinate[CACHE_SIZE];
        int used;
    }

    private static final ThreadLocal<Cache> WORLDFLOW_CACHE = ThreadLocal.withInitial(Cache::new);
    private static final long EMPTY_KEY = Long.MIN_VALUE;

    /*
     * WorldFlow: BO3 spawning calls this for every block (thousands per object) and the
     * result immediately becomes a HashSet key. A small per-thread canonical cache turns
     * the bulk of those into hits and removes the dominant allocation in the feature
     * stage without changing equality semantics (the class is immutable).
     */
    public static ChunkCoordinate fromBlockCoords(int blockX, int blockZ) {
        int cx = blockX >> 4;
        int cz = blockZ >> 4;
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        Cache cache = WORLDFLOW_CACHE.get();
        int slot = (int) ((key ^ (key >>> 29)) & (CACHE_SIZE - 1));
        if (cache.keys[slot] == key && cache.values[slot] != null) {
            return cache.values[slot];
        }
        ChunkCoordinate created = new ChunkCoordinate(cx, cz);
        cache.keys[slot] = key;
        cache.values[slot] = created;
        return created;
    }

    public static ChunkCoordinate fromChunkCoords(int chunkX, int chunkZ) {
        return new ChunkCoordinate(chunkX, chunkZ);
    }

    public ChunkCoordinate toRegionCoord() {
        return ChunkCoordinate.fromChunkCoords(MathHelper.floor((double)this.getChunkX() / 100.0), MathHelper.floor((double)this.getChunkZ() / 100.0));
    }

    public int getRegionInternalX() {
        return MathHelper.mod(this.getChunkX(), 100);
    }

    public int getRegionInternalZ() {
        return MathHelper.mod(this.getChunkZ(), 100);
    }

    public String toString() {
        return this.chunkX + "," + this.chunkZ;
    }

    public int getBlockXCenter() {
        return this.chunkX * 16 + 7;
    }

    public int getBlockZCenter() {
        return this.chunkZ * 16 + 7;
    }

    public int getBlockX() {
        return this.chunkX * 16;
    }

    public int getBlockZ() {
        return this.chunkZ * 16;
    }

    public boolean coordsMatch(int chunkX, int chunkZ) {
        return this.chunkX == chunkX && this.chunkZ == chunkZ;
    }
}

