/*
 * Decompiled with CFR 0.152.
 */
package com.pg85.otg.generator.biome.layers;

import com.pg85.otg.common.LocalWorld;
import com.pg85.otg.generator.biome.ArraysCache;

/* WorldFlow: the biome layer chain is shared by every generation worker. The stock
 * implementation keeps the scrambled chunk/group/world seeds in instance fields, so
 * concurrent getInts() calls corrupt each other. Keep that scratch state per thread;
 * everything else in the chain is read-only after construction. */

public abstract class Layer {
    private long baseSeed;
    protected long scrambledWorldSeed;

    private static final class Scratch {
        long worldSeed;
        long chunkSeed;
        long groupSeed;
    }

    private static final ThreadLocal<Scratch> WORLDFLOW_SCRATCH = ThreadLocal.withInitial(Scratch::new);
    protected Layer child;
    protected static final int Entropy = 10000;
    protected static final int BiomeBits = 2047;
    protected static final int BiomeBitsAreSetBit = 0x800000;
    protected static final int LandBit = 2048;
    protected static final int IslandBit = 4096;
    protected static final int IceBit = 8192;
    protected static final int BiomeGroupShift = 14;
    protected static final int BiomeGroupBits = 2080768;
    private static final int RiverShift = 21;
    protected static final int RiverBits = 0x600000;
    protected static final int RiverBitOne = 0x200000;
    protected static final int RiverBitTwo = 0x400000;
    protected int defaultOceanId;

    private static long getScrambledBaseSeed(long baseSeed) {
        long scrambledBaseSeed = baseSeed;
        scrambledBaseSeed *= scrambledBaseSeed * 6364136223846793005L + 1442695040888963407L;
        scrambledBaseSeed += baseSeed;
        scrambledBaseSeed *= scrambledBaseSeed * 6364136223846793005L + 1442695040888963407L;
        scrambledBaseSeed += baseSeed;
        scrambledBaseSeed *= scrambledBaseSeed * 6364136223846793005L + 1442695040888963407L;
        return scrambledBaseSeed += baseSeed;
    }

    protected static long getScrambledWorldSeed(long baseSeed, long worldSeed) {
        long scrambledBaseSeed = Layer.getScrambledBaseSeed(baseSeed);
        long scrambledWorldSeed = worldSeed;
        scrambledWorldSeed *= scrambledWorldSeed * 6364136223846793005L + 1442695040888963407L;
        scrambledWorldSeed += scrambledBaseSeed;
        scrambledWorldSeed *= scrambledWorldSeed * 6364136223846793005L + 1442695040888963407L;
        scrambledWorldSeed += scrambledBaseSeed;
        scrambledWorldSeed *= scrambledWorldSeed * 6364136223846793005L + 1442695040888963407L;
        return scrambledWorldSeed += scrambledBaseSeed;
    }

    protected Layer(long seed, int defaultOceanId) {
        this.baseSeed = seed;
        this.defaultOceanId = defaultOceanId;
    }

    public Layer() {
    }

    public void initWorldGenSeed(long worldSeed) {
        if (this.child != null) {
            this.child.initWorldGenSeed(worldSeed);
        }
        this.scrambledWorldSeed = Layer.getScrambledWorldSeed(this.baseSeed, worldSeed);
    }

    protected void initChunkSeed(long x, long z) {
        initChunkSeed(x, z, this.scrambledWorldSeed);
    }

    /** Variant used by layers that need a per-feature world seed (biome isles). */
    protected void initChunkSeed(long x, long z, long worldSeed) {
        Scratch scratch = WORLDFLOW_SCRATCH.get();
        scratch.worldSeed = worldSeed;
        scratch.chunkSeed = worldSeed;
        scratch.chunkSeed *= scratch.chunkSeed * 6364136223846793005L + 1442695040888963407L;
        scratch.chunkSeed += x;
        scratch.chunkSeed *= scratch.chunkSeed * 6364136223846793005L + 1442695040888963407L;
        scratch.chunkSeed += z;
        scratch.chunkSeed *= scratch.chunkSeed * 6364136223846793005L + 1442695040888963407L;
        scratch.chunkSeed += x;
        scratch.chunkSeed *= scratch.chunkSeed * 6364136223846793005L + 1442695040888963407L;
        scratch.chunkSeed += z;
    }

    protected void initGroupSeed(long x, long z) {
        Scratch scratch = WORLDFLOW_SCRATCH.get();
        scratch.groupSeed = scratch.chunkSeed;
        scratch.groupSeed *= scratch.groupSeed * 6364136223846793005L + 1442695040888963407L;
        scratch.groupSeed += x;
        scratch.groupSeed *= scratch.groupSeed * 6364136223846793005L + 1442695040888963407L;
        scratch.groupSeed += z;
        scratch.groupSeed *= scratch.groupSeed * 6364136223846793005L + 1442695040888963407L;
        scratch.groupSeed += x;
        scratch.groupSeed *= scratch.groupSeed * 6364136223846793005L + 1442695040888963407L;
        scratch.groupSeed += z;
    }

    protected int nextInt(int x) {
        Scratch scratch = WORLDFLOW_SCRATCH.get();
        int i = (int)((scratch.chunkSeed >> 24) % (long)x);
        if (i < 0) {
            i += x;
        }
        scratch.chunkSeed *= scratch.chunkSeed * 6364136223846793005L + 1442695040888963407L;
        scratch.chunkSeed += scratch.worldSeed;
        return i;
    }

    protected int nextGroupInt(int x) {
        Scratch scratch = WORLDFLOW_SCRATCH.get();
        int i = (int)((scratch.groupSeed >> 24) % (long)x);
        if (i < 0) {
            i += x;
        }
        scratch.groupSeed *= scratch.groupSeed * 6364136223846793005L + 1442695040888963407L;
        scratch.groupSeed += scratch.chunkSeed;
        return i;
    }

    public abstract int[] getInts(LocalWorld var1, ArraysCache var2, int var3, int var4, int var5, int var6);

    protected int getRandomOf4(int a, int b, int c, int d) {
        if (b == c && c == d) {
            return b;
        }
        if (a == b && a == c) {
            return a;
        }
        if (a == b && a == d) {
            return a;
        }
        if (a == c && a == d) {
            return a;
        }
        if (a == b && c != d) {
            return a;
        }
        if (a == c && b != d) {
            return a;
        }
        if (a == d && b != c) {
            return a;
        }
        if (b == c && a != d) {
            return b;
        }
        if (b == d && a != c) {
            return b;
        }
        if (c == d && a != b) {
            return c;
        }
        switch (this.nextInt(4)) {
            case 0: {
                return a;
            }
            case 1: {
                return b;
            }
            case 2: {
                return c;
            }
        }
        return d;
    }

    protected int getBiomeFromLayer(int selection) {
        return (selection & 0x800) != 0 && (selection & 0x800000) != 0 ? selection & 0x7FF : this.defaultOceanId;
    }
}

