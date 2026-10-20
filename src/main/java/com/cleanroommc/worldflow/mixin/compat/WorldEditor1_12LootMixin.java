package com.cleanroommc.worldflow.mixin.compat;

import com.github.fnar.minecraft.WorldEditor1_12;
import com.github.fnar.minecraft.world.BlockPosMapper1_12;
import greymerk.roguelike.worldgen.Coord;
import greymerk.roguelike.worldgen.WorldEditor;
import net.minecraft.init.Blocks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A dungeon can call setLootTable on a position whose chest block/TE was lost (a
 * rejected same-state write or a cross-chunk stage). Recreate the chest and TE at
 * that position, then set the table; if that is impossible, skip instead of NPEing.
 */
@Mixin(value = WorldEditor1_12.class, remap = false)
public abstract class WorldEditor1_12LootMixin {

    @Shadow private World world;

    @Inject(method = "setLootTable", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$safeLootTable(Coord coord, String lootTable, CallbackInfo ci) {
        TileEntity te = ((WorldEditor1_12) (Object) this).getTileEntity(coord);
        if (te != null) {
            return;
        }
        BlockPos pos = BlockPosMapper1_12.map(coord);
        IBlockState state = this.world.getBlockState(pos);
        if (state == null || !state.getBlock().hasTileEntity(state)) {
            this.world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 3);
        }
        te = this.world.getTileEntity(pos);
        if (te instanceof TileEntityChest) {
            ((TileEntityChest) te).setLootTable(new ResourceLocation(lootTable), ((WorldEditor) (Object) this).getSeed(coord));
        }
        ci.cancel();
    }
}
