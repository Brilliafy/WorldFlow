package com.cleanroommc.worldflow.cascade.staging;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

public class StagedBlock {
    public final int x;
    public final int y;
    public final int z;
    public final IBlockState state;
    @Nullable
    public final NBTTagCompound tileEntityNbt;

    public StagedBlock(int x, int y, int z, IBlockState state, @Nullable NBTTagCompound tileEntityNbt) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.state = state;
        this.tileEntityNbt = tileEntityNbt;
    }

    public BlockPos getPos() {
        return new BlockPos(this.x, this.y, this.z);
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("x", this.x);
        tag.setInteger("y", this.y);
        tag.setInteger("z", this.z);

        ResourceLocation blockId = Block.REGISTRY.getNameForObject(this.state.getBlock());
        tag.setString("b", blockId != null ? blockId.toString() : "minecraft:air");
        tag.setInteger("m", this.state.getBlock().getMetaFromState(this.state));

        if (this.tileEntityNbt != null) {
            tag.setTag("te", this.tileEntityNbt.copy());
        }
        return tag;
    }

    @Nullable
    public static StagedBlock readFromNBT(NBTTagCompound tag) {
        int x = tag.getInteger("x");
        int y = tag.getInteger("y");
        int z = tag.getInteger("z");

        String blockName = tag.getString("b");
        Block block = Block.REGISTRY.getObject(new ResourceLocation(blockName));
        if (block == null) return null;

        int meta = tag.getInteger("m");
        IBlockState state = block.getStateFromMeta(meta);

        NBTTagCompound teNbt = tag.hasKey("te") ? tag.getCompoundTag("te") : null;
        return new StagedBlock(x, y, z, state, teNbt);
    }
}
