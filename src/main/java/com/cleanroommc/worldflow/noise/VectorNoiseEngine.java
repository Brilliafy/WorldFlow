package com.cleanroommc.worldflow.noise;

import com.cleanroommc.worldflow.config.ModConfig;

import java.util.concurrent.atomic.AtomicLong;

public class VectorNoiseEngine {

    public static final AtomicLong TOTAL_NOISE_EVALUATIONS = new AtomicLong(0);
    public static final AtomicLong ARRAYS_POPULATED = new AtomicLong(0);

    private static final double[] GRAD_X = {1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, -1.0, 0.0};
    private static final double[] GRAD_Y = {1.0, 1.0, -1.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 1.0, -1.0};
    private static final double[] GRAD_Z = {0.0, 0.0, 0.0, 0.0, 1.0, 1.0, -1.0, -1.0, 1.0, 1.0, -1.0, -1.0, 0.0, 1.0, 0.0, -1.0};
    private static final double[] GRAD_2X = {1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, -1.0, 0.0};
    private static final double[] GRAD_2Z = {0.0, 0.0, 0.0, 0.0, 1.0, 1.0, -1.0, -1.0, 1.0, 1.0, -1.0, -1.0, 0.0, 1.0, 0.0, -1.0};

    public static double fade(double t) {
        return t * t * t * (t * (t * 6.0D - 15.0D) + 10.0D);
    }

    public static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    public static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        return GRAD_X[h] * x + GRAD_Y[h] * y + GRAD_Z[h] * z;
    }

    public static double grad2(int hash, double x, double z) {
        int h = hash & 15;
        return GRAD_2X[h] * x + GRAD_2Z[h] * z;
    }

    public static void populateNoiseFast(int[] permutations, double xCoord, double yCoord, double zCoord,
                                         double[] noiseArray, double xOffset, double yOffset, double zOffset,
                                         int xSize, int ySize, int zSize,
                                         double xScale, double yScale, double zScale, double noiseScale) {
        long evaluations = 0L;
        ARRAYS_POPULATED.incrementAndGet();
        double invNoiseScale = 1.0D / noiseScale;

        if (ySize == 1) {
            int outIdx = 0;
            for (int ix = 0; ix < xSize; ++ix) {
                double xSample = xOffset + (double) ix * xScale + xCoord;
                int fx = (int) xSample;
                if (xSample < (double) fx) fx--;
                int xi = fx & 255;
                double xFrac = xSample - (double) fx;
                double u = fade(xFrac);

                int pX0 = permutations[xi];
                int pX1 = permutations[xi + 1];

                for (int iz = 0; iz < zSize; ++iz) {
                    double zSample = zOffset + (double) iz * zScale + zCoord;
                    int fz = (int) zSample;
                    if (zSample < (double) fz) fz--;
                    int zi = fz & 255;
                    double zFrac = zSample - (double) fz;
                    double w = fade(zFrac);

                    int p0 = permutations[pX0] + zi;
                    int p1 = permutations[pX1] + zi;

                    double d14 = lerp(u, grad2(permutations[p0], xFrac, zFrac), grad(permutations[p1], xFrac - 1.0D, 0.0D, zFrac));
                    double d15 = lerp(u, grad(permutations[p0 + 1], xFrac, 0.0D, zFrac - 1.0D), grad(permutations[p1 + 1], xFrac - 1.0D, 0.0D, zFrac - 1.0D));

                    double val = lerp(w, d14, d15);
                    noiseArray[outIdx++] += val * invNoiseScale;
                    evaluations++;
                }
            }
        } else {
            int outIdx = 0;
            int lastY = -1;
            int l = 0, i1 = 0, j1 = 0, k1 = 0, l1 = 0, i2 = 0;
            double d1 = 0.0D, d2 = 0.0D, d3 = 0.0D, d4 = 0.0D;

            for (int ix = 0; ix < xSize; ++ix) {
                double xSample = xOffset + (double) ix * xScale + xCoord;
                int fx = (int) xSample;
                if (xSample < (double) fx) fx--;
                int xi = fx & 255;
                double xFrac = xSample - (double) fx;
                double u = fade(xFrac);

                int pX0 = permutations[xi];
                int pX1 = permutations[xi + 1];

                for (int iz = 0; iz < zSize; ++iz) {
                    double zSample = zOffset + (double) iz * zScale + zCoord;
                    int fz = (int) zSample;
                    if (zSample < (double) fz) fz--;
                    int zi = fz & 255;
                    double zFrac = zSample - (double) fz;
                    double w = fade(zFrac);

                    for (int iy = 0; iy < ySize; ++iy) {
                        double ySample = yOffset + (double) iy * yScale + yCoord;
                        int fy = (int) ySample;
                        if (ySample < (double) fy) fy--;
                        int yi = fy & 255;
                        double yFrac = ySample - (double) fy;
                        double v = fade(yFrac);

                        if (iy == 0 || yi != lastY) {
                            lastY = yi;
                            l = pX0 + yi;
                            i1 = permutations[l] + zi;
                            j1 = permutations[l + 1] + zi;
                            k1 = pX1 + yi;
                            l1 = permutations[k1] + zi;
                            i2 = permutations[k1 + 1] + zi;

                            d1 = lerp(u, grad(permutations[i1], xFrac, yFrac, zFrac), grad(permutations[l1], xFrac - 1.0D, yFrac, zFrac));
                            d2 = lerp(u, grad(permutations[j1], xFrac, yFrac - 1.0D, zFrac), grad(permutations[i2], xFrac - 1.0D, yFrac - 1.0D, zFrac));
                            d3 = lerp(u, grad(permutations[i1 + 1], xFrac, yFrac, zFrac - 1.0D), grad(permutations[l1 + 1], xFrac - 1.0D, yFrac, zFrac - 1.0D));
                            d4 = lerp(u, grad(permutations[j1 + 1], xFrac, yFrac - 1.0D, zFrac - 1.0D), grad(permutations[i2 + 1], xFrac - 1.0D, yFrac - 1.0D, zFrac - 1.0D));
                        }

                        double d11 = lerp(v, d1, d2);
                        double d12 = lerp(v, d3, d4);
                        double val = lerp(w, d11, d12);

                        noiseArray[outIdx++] += val * invNoiseScale;
                        evaluations++;
                    }
                }
            }
        }

        // One atomic add per octave array instead of one per voxel.
        if (evaluations != 0L) {
            TOTAL_NOISE_EVALUATIONS.addAndGet(evaluations);
        }
    }

    public static void clearStats() {
        TOTAL_NOISE_EVALUATIONS.set(0);
        ARRAYS_POPULATED.set(0);
    }
}
