package com.cleanroommc.worldflow.mixin;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.gen.structure.MapGenStructureData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Structure starts are persisted through MapGenStructureData while feature workers run.
 * Keep the original loaded NBT map as the read snapshot (no copy/canonicalization on
 * every initialize) and stage new entries concurrently. The full NBT is materialized
 * only on save.
 */
@Mixin(MapGenStructureData.class)
public abstract class MapGenStructureDataMixin {

    @Shadow
    private NBTTagCompound tagCompound;

    @Unique
    private final ConcurrentHashMap<String, NBTTagCompound> worldflow$entries = new ConcurrentHashMap<>();

    @Overwrite
    public void readFromNBT(NBTTagCompound nbt) {
        this.tagCompound = nbt.getCompoundTag("Features");
        worldflow$entries.clear();
    }

    @Overwrite
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        NBTTagCompound features = this.tagCompound.copy();
        for (Map.Entry<String, NBTTagCompound> entry : worldflow$entries.entrySet()) {
            features.setTag(entry.getKey(), entry.getValue());
        }
        compound.setTag("Features", features);
        return compound;
    }

    @Overwrite
    public void writeInstance(NBTTagCompound tagCompoundIn, int chunkX, int chunkZ) {
        worldflow$entries.put(MapGenStructureData.formatChunkCoords(chunkX, chunkZ), tagCompoundIn);
    }

    @Overwrite
    public NBTTagCompound getTagCompound() {
        return this.tagCompound;
    }
}
