package com.cleanroommc.worldflow.mixin.nc;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nuclearcraft walks World.loadedTileEntityList every tick and asks each tile entity for its
 * inventory. If a structure replaced the block under a tile entity (for example a rustic
 * cabinet whose block became bedrock), rustic's cabinet reads its "top" blockstate property
 * and throws IllegalArgumentException, killing the server.
 *
 * <p>Pipeline chunks can legitimately receive late structure writes, so instead of racing to
 * remove the tile entity first, refuse to hand its inventory to Nuclearcraft when the tile
 * entity no longer matches the block in the world.</p>
 */
@Mixin(targets = "nc.radiation.RadiationHelper", remap = false)
public abstract class RadiationHelperMixin {

    @Inject(method = "getTileInventory", at = @At("HEAD"), cancellable = true, remap = false)
    private static void worldflow$skipStaleTileEntity(ICapabilityProvider provider, EnumFacing side,
                                                      CallbackInfoReturnable<IItemHandler> cir) {
        if (!(provider instanceof TileEntity)) {
            return;
        }
        TileEntity tileEntity = (TileEntity) provider;
        World world = tileEntity.getWorld();
        if (world == null) {
            return;
        }
        IBlockState state = world.getBlockState(tileEntity.getPos());
        // NB: TileEntity.getBlockType() reflects the current block at the position, so it can
        // not be used to detect staleness. The block-state capability is the real test: bedrock
        // cannot own a tile entity, therefore a cabinet TE sitting on it is stale.
        if (state == null || !state.getBlock().hasTileEntity(state)) {
            cir.setReturnValue(null);
        }
    }
}
