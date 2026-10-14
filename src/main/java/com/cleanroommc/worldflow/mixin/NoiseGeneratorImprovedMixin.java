package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.config.ModConfig;
import com.cleanroommc.worldflow.noise.VectorNoiseEngine;
import net.minecraft.world.gen.NoiseGeneratorImproved;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseGeneratorImproved.class)
public abstract class NoiseGeneratorImprovedMixin {

    @Shadow
    private int[] permutations;

    @Shadow
    public double xCoord;

    @Shadow
    public double yCoord;

    @Shadow
    public double zCoord;

    @Inject(method = "populateNoiseArray", at = @At("HEAD"), cancellable = true)
    private void vectorNoise$onPopulateNoiseArray(double[] noiseArray, double xOffset, double yOffset, double zOffset,
                                                 int xSize, int ySize, int zSize,
                                                 double xScale, double yScale, double zScale, double noiseScale,
                                                 CallbackInfo ci) {
        if (!ModConfig.NOISE.enabled) {
            return;
        }

        VectorNoiseEngine.populateNoiseFast(this.permutations, this.xCoord, this.yCoord, this.zCoord,
                noiseArray, xOffset, yOffset, zOffset, xSize, ySize, zSize, xScale, yScale, zScale, noiseScale);
        ci.cancel();
    }
}
