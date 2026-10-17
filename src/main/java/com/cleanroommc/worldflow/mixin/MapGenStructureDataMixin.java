package com.cleanroommc.worldflow.mixin;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.gen.structure.MapGenStructureData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(MapGenStructureData.class)
public abstract class MapGenStructureDataMixin {

    @Shadow
    private NBTTagCompound tagCompound;

    /**
     * @author WorldFlow
     * @reason Synchronize structure tag writes across worker threads.
     */
    @Overwrite
    public synchronized void writeInstance(NBTTagCompound tagCompoundIn, int chunkX, int chunkZ) {
        this.tagCompound.setTag(MapGenStructureData.formatChunkCoords(chunkX, chunkZ), tagCompoundIn);
    }
}
