package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.config.ModConfig;
import com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer;
import com.cleanroommc.worldflow.cascade.staging.GenerationContext;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public abstract class WorldServerMixin {

    @Shadow
    public boolean isRemote;

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z", at = @At("HEAD"), cancellable = true)
    private void cascadeFix$onSetBlockState(BlockPos pos, IBlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        if (this.isRemote || !ModConfig.GENERAL.enableCascadeFix || !ModConfig.GENERAL.stageForgeDecorators) {
            return;
        }

        if (GenerationContext.isPopulating()) {
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;
            World world = (World) (Object) this;

            if (world instanceof WorldServer && GenerationContext.isCascading(world, chunkX, chunkZ)) {
                // Prevent recursive chunk load/generate cascade
                CascadeBlockBuffer.INSTANCE.stageBlock(world, pos, newState, null, GenerationContext.getGeneratorName());
                cir.setReturnValue(true);
            }
        }
    }
}
