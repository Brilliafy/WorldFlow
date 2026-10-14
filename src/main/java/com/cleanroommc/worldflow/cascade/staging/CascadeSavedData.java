package com.cleanroommc.worldflow.cascade.staging;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CascadeSavedData extends WorldSavedData {
    public static final String DATA_NAME = "cascade_staging";

    private final Map<ChunkPos, List<StagedBlock>> stagedBlocks = new ConcurrentHashMap<>();

    public CascadeSavedData() {
        super(DATA_NAME);
    }

    public CascadeSavedData(String name) {
        super(name);
    }

    public static CascadeSavedData get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        CascadeSavedData instance = (CascadeSavedData) storage.getOrLoadData(CascadeSavedData.class, DATA_NAME);
        if (instance == null) {
            instance = new CascadeSavedData();
            storage.setData(DATA_NAME, instance);
        }
        return instance;
    }

    public Map<ChunkPos, List<StagedBlock>> getStagedBlocks() {
        return this.stagedBlocks;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        this.stagedBlocks.clear();
        NBTTagList chunkList = nbt.getTagList("Chunks", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < chunkList.tagCount(); i++) {
            NBTTagCompound chunkTag = chunkList.getCompoundTagAt(i);
            int chunkX = chunkTag.getInteger("cx");
            int chunkZ = chunkTag.getInteger("cz");
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);

            NBTTagList blockList = chunkTag.getTagList("b", Constants.NBT.TAG_COMPOUND);
            List<StagedBlock> list = new ArrayList<>(blockList.tagCount());
            for (int j = 0; j < blockList.tagCount(); j++) {
                StagedBlock block = StagedBlock.readFromNBT(blockList.getCompoundTagAt(j));
                if (block != null) {
                    list.add(block);
                }
            }
            if (!list.isEmpty()) {
                this.stagedBlocks.put(pos, list);
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        NBTTagList chunkList = new NBTTagList();
        for (Map.Entry<ChunkPos, List<StagedBlock>> entry : this.stagedBlocks.entrySet()) {
            ChunkPos pos = entry.getKey();
            List<StagedBlock> blocks = entry.getValue();
            if (blocks == null || blocks.isEmpty()) continue;

            NBTTagCompound chunkTag = new NBTTagCompound();
            chunkTag.setInteger("cx", pos.x);
            chunkTag.setInteger("cz", pos.z);

            NBTTagList blockList = new NBTTagList();
            for (StagedBlock b : blocks) {
                blockList.appendTag(b.writeToNBT());
            }
            chunkTag.setTag("b", blockList);
            chunkList.appendTag(chunkTag);
        }
        compound.setTag("Chunks", chunkList);
        return compound;
    }
}
