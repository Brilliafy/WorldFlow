package com.cleanroommc.worldflow.mixin.compat;

import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * BOP paged plants have a per-instance variantProperty. A world state can come from
 * another page/instance, and func_176200_f (isReplaceable) then calls getValue on a
 * property the state does not own. Treat that as non-replaceable instead of throwing.
 */
@Mixin(targets = "biomesoplenty.common.block.BlockBOPPlant", remap = false)
public abstract class BOPPlantStateMixin {

    @Shadow
    public IProperty variantProperty;

    @Inject(method = "func_176200_f", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$guardVariant(IBlockAccess world, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        IBlockState state = world.getBlockState(pos);
        if (!state.getPropertyKeys().contains(this.variantProperty)) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }
}
