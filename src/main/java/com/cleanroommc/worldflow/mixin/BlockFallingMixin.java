package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.config.ModConfig;
import com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView;
import net.minecraft.block.BlockFalling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BlockFalling.class)
public abstract class BlockFallingMixin {

    @Redirect(
            method = "checkFallable",
            at = @At(value = "FIELD", target = "Lnet/minecraft/block/BlockFalling;fallInstantly:Z")
    )
    private boolean worldflow$forceInstantFallingDuringGen() {
        boolean forceInstant = ModConfig.CONCURRENCY == null || ModConfig.CONCURRENCY.forceInstantFallingDuringGen;
        return BlockFalling.fallInstantly || (forceInstant && ThreadLocalWorldView.isGenerating());
    }
}
