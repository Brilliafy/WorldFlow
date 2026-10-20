package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.customobjects.structures.bo4.smoothing.SmoothingAreaLine;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The smoothing-area line collection can contain a null entry (OTG data/save edge
 * case). mergeAndSpawnSmoothingAreas then dereferences finalDestinationPointX and
 * throws, which makes WorldFlow fall back to main-thread finalize and drop the
 * whole chunk's decoration. Treat a null line as a non-matching endpoint so the
 * loop skips it.
 */
@Mixin(targets = "com.pg85.otg.customobjects.structures.bo4.smoothing.SmoothingAreaGenerator", remap = false)
public abstract class SmoothingAreaNullGuardMixin {

    @Redirect(method = "mergeAndSpawnSmoothingAreas",
              at = @At(value = "FIELD",
                       target = "Lcom/pg85/otg/customobjects/structures/bo4/smoothing/SmoothingAreaLine;finalDestinationPointX:I",
                       opcode = Opcodes.GETFIELD),
              remap = false)
    private int worldflow$destinationX(SmoothingAreaLine line) {
        return line == null ? Integer.MIN_VALUE : line.finalDestinationPointX;
    }
}
