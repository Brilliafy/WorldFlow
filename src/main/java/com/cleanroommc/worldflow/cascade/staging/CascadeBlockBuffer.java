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

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;

public class CascadeBlockBuffer {
    public static final CascadeBlockBuffer INSTANCE = new CascadeBlockBuffer();

    public static class StagedTick {
        public final BlockPos pos;
        public final Block block;
        public final int delay;
        public final int priority;

        public StagedTick(BlockPos pos, Block block, int delay, int priority) {
            this.pos = pos;
            this.block = block;
            this.delay = delay;
            this.priority = priority;
        }
    }

    private final Map<Integer, Map<ChunkPos, List<StagedBlock>>> memoryStaging = new ConcurrentHashMap<>();
    private final Map<Integer, Map<ChunkPos, List<StagedTick>>> stagedTicks = new ConcurrentHashMap<>();
    private final Map<Integer, Map<ChunkPos, List<Entity>>> stagedEntities = new ConcurrentHashMap<>();
    public final AtomicLong totalCascadesPrevented = new AtomicLong(0);
    public final AtomicLong totalBlocksStaged = new AtomicLong(0);
    public final AtomicLong totalBlocksFlushed = new AtomicLong(0);
    public final AtomicLong totalEntitiesStaged = new AtomicLong(0);
    public final AtomicLong totalEntitiesFlushed = new AtomicLong(0);

    public void stageEntity(WorldServer world, Entity entity) {
        if (world == null || entity == null) return;
        int dim = world.provider.getDimension();
        int chunkX = MathHelper.floor(entity.posX / 16.0);
        int chunkZ = MathHelper.floor(entity.posZ / 16.0);
        ChunkPos cp = new ChunkPos(chunkX, chunkZ);
        Map<ChunkPos, List<Entity>> dimMap = this.stagedEntities.computeIfAbsent(dim, d -> new ConcurrentHashMap<>());
        List<Entity> list = dimMap.computeIfAbsent(cp, c -> Collections.synchronizedList(new ArrayList<>()));
        list.add(entity);
        totalEntitiesStaged.incrementAndGet();
    }

    public void stageTickUpdate(WorldServer world, BlockPos pos, Block block, int delay, int priority) {
        int dim = world.provider.getDimension();
        ChunkPos cp = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        Map<ChunkPos, List<StagedTick>> dimMap = this.stagedTicks.computeIfAbsent(dim, d -> new ConcurrentHashMap<>());
        List<StagedTick> list = dimMap.computeIfAbsent(cp, c -> Collections.synchronizedList(new ArrayList<>()));
        list.add(new StagedTick(pos, block, delay, priority));
    }

    public void stageBlock(World world, BlockPos pos, IBlockState state, @Nullable NBTTagCompound teNbt, String sourceName) {
        int dim = world.provider.getDimension();
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;

        if (world.getChunkProvider() instanceof net.minecraft.world.gen.ChunkProviderServer) {
            Chunk loaded = ((net.minecraft.world.gen.ChunkProviderServer) world.getChunkProvider()).getLoadedChunk(chunkX, chunkZ);
            if (loaded != null) {
                synchronized (loaded) {
                    IBlockState old = loaded.setBlockState(pos, state);
                    if (old != null) {
                        if (teNbt != null) {
                            TileEntity te = world.getTileEntity(pos);
                            if (te != null) {
                                te.readFromNBT(teNbt);
                                te.markDirty();
                            }
                        }
                        loaded.markDirty();
                        return;
                    }
                }
            }
        }

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

        // Flush staged ticks for this chunk
        Map<ChunkPos, List<StagedTick>> tickDimMap = this.stagedTicks.get(dim);
        if (tickDimMap != null) {
            List<StagedTick> ticks = tickDimMap.remove(chunkPos);
            if (ticks != null && !ticks.isEmpty()) {
                for (StagedTick t : ticks) {
                    world.scheduleBlockUpdate(t.pos, t.block, t.delay, t.priority);
                }
            }
        }

        // Flush staged entities for this chunk
        Map<ChunkPos, List<Entity>> entityDimMap = this.stagedEntities.get(dim);
        if (entityDimMap != null) {
            List<Entity> entities = entityDimMap.remove(chunkPos);
            if (entities != null && !entities.isEmpty()) {
                for (Entity entity : entities) {
                    try {
                        world.spawnEntity(entity);
                        totalEntitiesFlushed.incrementAndGet();
                    } catch (Throwable t) {
                        WorldFlow.LOGGER.debug("[WorldFlow] Error spawning staged entity in chunk [{}, {}]", chunkPos.x, chunkPos.z, t);
                    }
                }
            }
        }

        chunk.markDirty();
        return count;
    }

    public void flushStagedEntitiesForLoadedChunks(WorldServer world) {
        if (world == null) return;
        int dim = world.provider.getDimension();
        Map<ChunkPos, List<Entity>> entityDimMap = this.stagedEntities.get(dim);
        if (entityDimMap == null || entityDimMap.isEmpty()) return;

        Iterator<Map.Entry<ChunkPos, List<Entity>>> it = entityDimMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkPos, List<Entity>> entry = it.next();
            ChunkPos pos = entry.getKey();
            if (world.getChunkProvider().getLoadedChunk(pos.x, pos.z) != null) {
                it.remove();
                List<Entity> entities = entry.getValue();
                if (entities != null) {
                    for (Entity entity : entities) {
                        try {
                            world.spawnEntity(entity);
                            totalEntitiesFlushed.incrementAndGet();
                        } catch (Throwable t) {
                            WorldFlow.LOGGER.debug("[WorldFlow] Error spawning staged entity in loaded chunk [{}, {}]", pos.x, pos.z, t);
                        }
                    }
                }
            }
        }
    }

    public void flushStagedTicksForLoadedChunks(WorldServer world) {
        if (world == null) return;
        int dim = world.provider.getDimension();
        Map<ChunkPos, List<StagedTick>> tickDimMap = this.stagedTicks.get(dim);
        if (tickDimMap == null || tickDimMap.isEmpty()) return;

        Iterator<Map.Entry<ChunkPos, List<StagedTick>>> it = tickDimMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkPos, List<StagedTick>> entry = it.next();
            ChunkPos pos = entry.getKey();
            if (world.getChunkProvider().getLoadedChunk(pos.x, pos.z) != null) {
                it.remove();
                List<StagedTick> ticks = entry.getValue();
                if (ticks != null) {
                    for (StagedTick t : ticks) {
                        try {
                            world.scheduleBlockUpdate(t.pos, t.block, t.delay, t.priority);
                        } catch (Throwable ex) {
                            WorldFlow.LOGGER.debug("[WorldFlow] Error applying staged tick in loaded chunk [{}, {}]", pos.x, pos.z, ex);
                        }
                    }
                }
            }
        }
    }

    public int flushAll(WorldServer world) {
        if (world == null) return 0;
        int dim = world.provider.getDimension();
        Map<ChunkPos, List<StagedBlock>> dimMap = memoryStaging.get(dim);
        if (dimMap == null || dimMap.isEmpty()) {
            flushStagedTicksForLoadedChunks(world);
            flushStagedEntitiesForLoadedChunks(world);
            return 0;
        }
        int totalFlushed = 0;
        for (ChunkPos cp : new ArrayList<>(dimMap.keySet())) {
            Chunk loaded = world.getChunkProvider().getLoadedChunk(cp.x, cp.z);
            if (loaded != null) {
                totalFlushed += flushChunk(world, loaded);
            }
        }
        flushStagedTicksForLoadedChunks(world);
        flushStagedEntitiesForLoadedChunks(world);
        return totalFlushed;
    }

    public void clear() {
        this.memoryStaging.clear();
        this.stagedTicks.clear();
        this.stagedEntities.clear();
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
