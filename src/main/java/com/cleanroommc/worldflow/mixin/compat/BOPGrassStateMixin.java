package com.cleanroommc.worldflow.mixin.compat;

import biomesoplenty.common.block.BlockBOPGrass;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.EnumPlantType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A BOP grass state without the "variant" property can reach this query (staged /
 * cross-chunk material conversion). getValue throws and the whole chunk falls back
 * to main-thread finalize. Treat it as non-sustaining instead.
 */
@Mixin(targets = "biomesoplenty.common.block.BlockBOPGrass", remap = false)
public abstract class BOPGrassStateMixin {

    @Inject(method = "canSustainPlantType", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$guardVariant(IBlockAccess world, BlockPos pos, EnumPlantType type,
                                        CallbackInfoReturnable<Boolean> cir) {
        IBlockState state = world.getBlockState(pos);
        if (!state.getPropertyKeys().contains(BlockBOPGrass.VARIANT)) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }
}
