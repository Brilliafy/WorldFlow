package com.cleanroommc.worldflow.cascade.staging;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.config.ModConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class CascadeBlockBuffer {
    public static final CascadeBlockBuffer INSTANCE = new CascadeBlockBuffer();

    private final Map<Integer, Map<ChunkPos, List<StagedBlock>>> memoryStaging = new ConcurrentHashMap<>();
    public final AtomicLong totalCascadesPrevented = new AtomicLong(0);
    public final AtomicLong totalBlocksStaged = new AtomicLong(0);
    public final AtomicLong totalBlocksFlushed = new AtomicLong(0);

    public void stageBlock(World world, BlockPos pos, IBlockState state, @Nullable NBTTagCompound teNbt, String sourceName) {
        int dim = world.provider.getDimension();
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);

        Map<ChunkPos, List<StagedBlock>> dimMap = memoryStaging.computeIfAbsent(dim, d -> new ConcurrentHashMap<>());
        List<StagedBlock> list = dimMap.computeIfAbsent(chunkPos, cp -> Collections.synchronizedList(new ArrayList<>()));

        if (list.size() < ModConfig.GENERAL.maxStagedBlocksPerChunk) {
            list.add(new StagedBlock(pos.getX(), pos.getY(), pos.getZ(), state, teNbt));
            totalBlocksStaged.incrementAndGet();
            totalCascadesPrevented.incrementAndGet();

            if (ModConfig.GENERAL.logPreventedCascades) {
                WorldFlow.LOGGER.info("Prevented chunk cascade from [{}] -> staged block at {} (Chunk [{}, {}] in Dim {})",
                        sourceName, pos, chunkX, chunkZ, dim);
            }
        }
    }

    public boolean hasStagedBlocks(World world, int chunkX, int chunkZ) {
        int dim = world.provider.getDimension();
        Map<ChunkPos, List<StagedBlock>> dimMap = memoryStaging.get(dim);
        if (dimMap == null) return false;
        List<StagedBlock> list = dimMap.get(new ChunkPos(chunkX, chunkZ));
        return list != null && !list.isEmpty();
    }

    public int flushChunk(WorldServer world, Chunk chunk) {
        int dim = world.provider.getDimension();
        ChunkPos chunkPos = chunk.getPos();

        // 1. Check in-memory staging
        Map<ChunkPos, List<StagedBlock>> dimMap = memoryStaging.get(dim);
        List<StagedBlock> blocksToPlace = null;
        if (dimMap != null) {
            blocksToPlace = dimMap.remove(chunkPos);
        }

        // 2. Check saved data staging if persistence is on
        if (ModConfig.GENERAL.persistStagedBlocks) {
            CascadeSavedData savedData = CascadeSavedData.get(world);
            List<StagedBlock> savedList = savedData.getStagedBlocks().remove(chunkPos);
            if (savedList != null && !savedList.isEmpty()) {
                if (blocksToPlace == null) {
                    blocksToPlace = savedList;
                } else {
                    blocksToPlace.addAll(savedList);
                }
                savedData.markDirty();
            }
        }

        if (blocksToPlace == null || blocksToPlace.isEmpty()) {
            return 0;
        }

        int count = 0;
        boolean oldCapture = world.captureBlockSnapshots;
        world.captureBlockSnapshots = false;

        try {
            for (StagedBlock staged : blocksToPlace) {
                BlockPos pos = staged.getPos();
                IBlockState oldState = chunk.getBlockState(pos);
                IBlockState result = chunk.setBlockState(pos, staged.state);

                if (result != null) {
                    count++;
                    totalBlocksFlushed.incrementAndGet();

                    if (staged.tileEntityNbt != null) {
                        TileEntity te = world.getTileEntity(pos);
                        if (te != null) {
                            te.readFromNBT(staged.tileEntityNbt);
                            te.markDirty();
                        }
                    }

                    world.markAndNotifyBlock(pos, chunk, oldState, staged.state, 18);
                }
            }
        } finally {
            world.captureBlockSnapshots = oldCapture;
        }

        chunk.markDirty();
        return count;
    }

    public void clear() {
        memoryStaging.clear();
    }

    public int getCurrentlyStagedCount() {
        int count = 0;
        for (Map<ChunkPos, List<StagedBlock>> dimMap : memoryStaging.values()) {
            for (List<StagedBlock> list : dimMap.values()) {
                count += list.size();
            }
        }
        return count;
    }
}
