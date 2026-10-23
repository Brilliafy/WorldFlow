package com.pg85.otg.generator.biome;

import com.pg85.otg.util.ChunkCoordinate;
import com.pg85.otg.util.FifoMap;

/*
 * WorldFlow: this generator instance is shared by every generation worker, but the
 * stock FIFO cache is not thread-safe and the biome layer chain used to keep mutable
 * seed scratch. Layers are now thread-confined, so give each worker its own cached
 * chunk map instead of serializing the whole biome path.
 */
class CachedBiomeGenerator
extends BiomeGenerator {
    private static final int CACHE_SIZE = 1024;

    private final ThreadLocal<FifoMap<ChunkCoordinate, Chunk>> cacheMap =
            ThreadLocal.withInitial(() -> new FifoMap<>(CACHE_SIZE));
    private final BiomeGenerator generator;

    public CachedBiomeGenerator(BiomeGenerator generator) {
        super(generator.world);
        this.generator = generator;
    }

    static BiomeGenerator makeCached(BiomeGenerator generator) {
        if (generator.isCached()) {
            return generator;
        }
        return new CachedBiomeGenerator(generator);
    }

    @Override
    public void cleanupCache() {
        this.cacheMap.remove();
    }

    @Override
    public int getBiome(int x, int z) {
        ChunkCoordinate chunkCoord = ChunkCoordinate.fromBlockCoords(x, z);
        Chunk cacheBlock = this.getBiomeCacheChunk(chunkCoord);
        return cacheBlock.getCalculatedBiomeId(x, z);
    }

    private Chunk getBiomeCacheChunk(ChunkCoordinate chunkCoord) {
        FifoMap<ChunkCoordinate, Chunk> map = this.cacheMap.get();
        Chunk chunk = map.get(chunkCoord);
        if (chunk == null) {
            chunk = new Chunk(this.generator, chunkCoord);
            map.put(chunkCoord, chunk);
        }
        return chunk;
    }

    @Override
    public int[] getBiomes(int[] biomeArray, int x, int z, int xSize, int zSize, OutputType type) {
        if (xSize == 16 && zSize == 16 && (x & 0xF) == 0 && (z & 0xF) == 0) {
            if (biomeArray == null || biomeArray.length < xSize * zSize) {
                biomeArray = new int[xSize * zSize];
            }
            int[] cachedBiomes = this.getCachedBiomes(ChunkCoordinate.fromBlockCoords(x, z));
            System.arraycopy(cachedBiomes, 0, biomeArray, 0, xSize * zSize);
            return biomeArray;
        }
        return this.generator.getBiomes(biomeArray, x, z, xSize, zSize, type);
    }

    @Override
    public int[] getBiomesUnZoomed(int[] biomeArray, int x, int z, int xSize, int zSize, OutputType type) {
        return this.generator.getBiomesUnZoomed(biomeArray, x, z, xSize, zSize, type);
    }

    @Override
    public boolean canGenerateUnZoomed() {
        return this.generator.canGenerateUnZoomed();
    }

    private int[] getCachedBiomes(ChunkCoordinate chunkCoord) {
        return this.getBiomeCacheChunk(chunkCoord).biomes;
    }

    @Override
    public boolean isCached() {
        return true;
    }

    @Override
    public BiomeGenerator unwrap() {
        return this.generator.unwrap();
    }

    private static class Chunk {
        private int[] biomes = new int[256];

        Chunk(BiomeGenerator generator, ChunkCoordinate chunkCoord) {
            this.biomes = generator.getBiomes(this.biomes, chunkCoord.getBlockX(), chunkCoord.getBlockZ(), 16, 16, OutputType.DEFAULT_FOR_WORLD);
        }

        int getCalculatedBiomeId(int blockX, int blockZ) {
            return this.biomes[blockX & 0xF | (blockZ & 0xF) << 4];
        }
    }
}
