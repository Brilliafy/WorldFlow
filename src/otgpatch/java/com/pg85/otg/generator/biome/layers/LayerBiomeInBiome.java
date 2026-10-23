/*
 * Decompiled with CFR 0.152.
 */
package com.pg85.otg.generator.biome.layers;

import com.pg85.otg.common.LocalBiome;
import com.pg85.otg.common.LocalWorld;
import com.pg85.otg.generator.biome.ArraysCache;
import com.pg85.otg.generator.biome.layers.Layer;
import com.pg85.otg.util.minecraft.defaults.DefaultBiome;
import java.util.ArrayList;
import java.util.List;

public class LayerBiomeInBiome
extends Layer {
    private final long worldSeed;
    private List<Isle> isles = new ArrayList<Isle>();

    LayerBiomeInBiome(Layer childLayer, long worldSeed, int defaultOceanId) {
        this.defaultOceanId = defaultOceanId;
        this.worldSeed = worldSeed;
        this.child = childLayer;
    }

    void addIsle(LocalBiome biome, int chance, boolean[] biomeCanSpawnIn, boolean inOcean) {
        Isle isle = new Isle();
        isle.biomeId = (short)biome.getIds().getOTGBiomeId();
        isle.chance = chance;
        isle.canSpawnIn = biomeCanSpawnIn;
        isle.inOcean = inOcean;
        short rngSeed = isle.biomeId;
        if (DefaultBiome.getId(biome.getName()) != null) {
            rngSeed = (short)biome.getIds().getSavedId();
        }
        isle.scrambledWorldSeed = LayerBiomeInBiome.getScrambledWorldSeed(4000 + rngSeed, this.worldSeed);
        this.isles.add(isle);
    }

    @Override
    public int[] getInts(LocalWorld world, ArraysCache cache, int x, int z, int xSize, int zSize) {
        int x0 = x - 1;
        int z0 = z - 1;
        int xSize0 = xSize + 2;
        int zSize0 = zSize + 2;
        int[] childInts = this.child.getInts(world, cache, x0, z0, xSize0, zSize0);
        int[] thisInts = cache.getArray(xSize * zSize);
        for (int zi = 0; zi < zSize; ++zi) {
            for (int xi = 0; xi < xSize; ++xi) {
                int selection = childInts[xi + 1 + (zi + 1) * xSize0];
                for (Isle isle : this.isles) {
                    int seCheck;
                    int swCheck;
                    int neCheck;
                    int nwCheck;
                    this.initChunkSeed(xi + x, zi + z, isle.scrambledWorldSeed);
                    boolean alreadySpawned = false;
                    if (isle.inOcean) {
                        nwCheck = childInts[xi + 0 + zi * xSize0] & 0x800;
                        neCheck = childInts[xi + 2 + zi * xSize0] & 0x800;
                        swCheck = childInts[xi + 0 + (zi + 2) * xSize0] & 0x800;
                        seCheck = childInts[xi + 2 + (zi + 2) * xSize0] & 0x800;
                        if ((selection & 0x800) == 0 && nwCheck == 0 && neCheck == 0 && swCheck == 0 && seCheck == 0 && this.nextInt(isle.chance) == 0) {
                            selection = selection & 0x2000 | selection & 0x600000 | 0x800 | isle.biomeId | 0x1000 | 0x800000;
                            alreadySpawned = true;
                        }
                    }
                    if (alreadySpawned) continue;
                    nwCheck = this.getBiomeFromLayer(childInts[xi + 0 + zi * xSize0]);
                    neCheck = this.getBiomeFromLayer(childInts[xi + 2 + zi * xSize0]);
                    swCheck = this.getBiomeFromLayer(childInts[xi + 0 + (zi + 2) * xSize0]);
                    seCheck = this.getBiomeFromLayer(childInts[xi + 2 + (zi + 2) * xSize0]);
                    if (!isle.canSpawnIn[this.getBiomeFromLayer(selection)] || !isle.canSpawnIn[nwCheck] || !isle.canSpawnIn[neCheck] || !isle.canSpawnIn[swCheck] || !isle.canSpawnIn[seCheck] || this.nextInt(isle.chance) != 0) continue;
                    selection = selection & 0x800 | selection & 0x2000 | selection & 0x600000 | isle.biomeId | 0x1000 | 0x800000;
                }
                thisInts[xi + zi * xSize] = selection;
            }
        }
        return thisInts;
    }

    private static class Isle {
        short biomeId;
        int chance = 10;
        boolean[] canSpawnIn = new boolean[2048];
        long scrambledWorldSeed;
        boolean inOcean = false;

        private Isle() {
        }
    }
}

