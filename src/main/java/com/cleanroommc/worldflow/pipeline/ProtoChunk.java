package com.cleanroommc.worldflow.pipeline;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.chunk.Chunk;

import com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer;

import javax.annotation.Nullable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An off-world chunk container that represents a chunk progressing through
 * the generation pipeline stages before it is published to the live World.
 */
public class ProtoChunk {

    private final WorldServer world;
    private final int x;
    private final int z;
    private final ChunkPos pos;
    private final long chunkKey;

    private final AtomicReference<ChunkStatus> status = new AtomicReference<>(ChunkStatus.EMPTY);
    private volatile Chunk underlyingChunk;

    public ProtoChunk(WorldServer world, int x, int z) {
        this.world = world;
        this.x = x;
        this.z = z;
        this.pos = new ChunkPos(x, z);
        this.chunkKey = ChunkPos.asLong(x, z);
        this.underlyingChunk = new Chunk(world, x, z);
    }

    public WorldServer getWorld() {
        return this.world;
    }

    public int getX() {
        return this.x;
    }

    public int getZ() {
        return this.z;
    }

    public ChunkPos getChunkPos() {
        return this.pos;
    }

    public long getChunkKey() {
        return this.chunkKey;
    }

    public ChunkStatus getStatus() {
        return this.status.get();
    }

    public void setStatus(ChunkStatus newStatus) {
        this.status.set(newStatus);
    }

    public boolean isAtLeast(ChunkStatus required) {
        return this.status.get().isAtLeast(required);
    }

    /** Thread currently decorating this chunk: its own reads/writes need no locking. */
    private volatile Thread ownerThread;

    public void setOwnerThread(Thread thread) {
        this.ownerThread = thread;
    }

    private boolean ownedByCurrentThread() {
        return this.ownerThread == Thread.currentThread();
    }

    private final ReentrantLock chunkLock = new ReentrantLock();
    private volatile boolean biomesFilled = false;

    public boolean isBiomesFilled() {
        return this.biomesFilled;
    }

    public void setBiomesFilled(boolean filled) {
        this.biomesFilled = filled;
    }

    public void ensureBiomesFilled(com.pg85.otg.forge.world.ForgeWorld world) {
        if (!this.biomesFilled && world != null) {
            boolean acquired = false;
            try {
                acquired = this.chunkLock.tryLock(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (acquired) {
                try {
                    if (!this.biomesFilled && this.underlyingChunk != null) {
                        com.cleanroommc.worldflow.pipeline.otg.OTGParallelContext.fillBiomeArray(world, this.underlyingChunk);
                        this.biomesFilled = true;
                    }
                } finally {
                    this.chunkLock.unlock();
                }
            } else {
                if (!this.biomesFilled && this.underlyingChunk != null) {
                    com.cleanroommc.worldflow.pipeline.otg.OTGParallelContext.fillBiomeArray(world, this.underlyingChunk);
                    this.biomesFilled = true;
                }
            }
        }
    }

    public Chunk getChunk() {
        return this.underlyingChunk;
    }

    public void setUnderlyingChunk(Chunk chunk) {
        if (chunk != null) {
            this.chunkLock.lock();
            try {
                this.underlyingChunk = chunk;
            } finally {
                this.chunkLock.unlock();
            }
        }
    }

    public IBlockState getBlockState(BlockPos pos) {
        return getBlockStateFast(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Allocation-free read path used by the worker world view. Skips the extra
     * BlockPos-based {@code Chunk.getBlockState} hop, which was the hottest self
     * frame in the feature stage profile.
     */
    public IBlockState getBlockStateFast(int worldX, int y, int worldZ) {
        if (y < 0 || y >= 256) {
            return Blocks.AIR.getDefaultState();
        }
        Chunk chunk = this.underlyingChunk;
        if (chunk == null) {
            return Blocks.AIR.getDefaultState();
        }
        if (ownedByCurrentThread()) {
            return chunk.getBlockState(worldX & 15, y, worldZ & 15);
        }
        boolean acquired = this.chunkLock.tryLock();
        try {
            chunk = this.underlyingChunk;
            return chunk != null ? chunk.getBlockState(worldX & 15, y, worldZ & 15) : Blocks.AIR.getDefaultState();
        } finally {
            if (acquired) {
                this.chunkLock.unlock();
            }
        }
    }

    public boolean setBlockState(BlockPos pos, IBlockState state) {
        if (pos.getY() < 0 || pos.getY() >= 256 || state == null) {
            return false;
        }
        boolean acquired = false;
        try {
            acquired = this.chunkLock.tryLock(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (acquired) {
            try {
                Chunk chunk = this.underlyingChunk;
                if (chunk == null) {
                    return false;
                }
                IBlockState oldState = chunk.setBlockState(pos, state);
                if (oldState != null) {
                    updateTileEntity(chunk, pos, oldState, state);
                }
                return oldState != null;
            } finally {
                this.chunkLock.unlock();
            }
        } else {
            // Deadlock prevention (e.g. cross-chunk cycle between adjacent workers):
            // Fall back to staging in CascadeBlockBuffer to be flushed deterministically at publish time.
            // Create the TileEntity now: Roguelike places a chest and immediately calls setLootTable;
            // a null TE here silently dropped the loot table.
            net.minecraft.nbt.NBTTagCompound teNbt = null;
            if (state.getBlock().hasTileEntity(state)) {
                net.minecraft.tileentity.TileEntity te = state.getBlock().createTileEntity(this.world, state);
                if (te != null) {
                    te.setWorld(this.world);
                    te.setPos(pos);
                    CascadeBlockBuffer.INSTANCE.stageTileEntity(this.world, pos, te);
                    teNbt = te.writeToNBT(new net.minecraft.nbt.NBTTagCompound());
                } else {
                    CascadeBlockBuffer.INSTANCE.stageTileEntity(this.world, pos, null);
                }
            } else {
                CascadeBlockBuffer.INSTANCE.stageTileEntity(this.world, pos, null);
            }
            CascadeBlockBuffer.INSTANCE.stageBlock(this.world, pos, state, teNbt, "ContendedCrossChunkLock");
            return true;
        }
    }

    public Biome getBiome(BlockPos pos, BiomeProvider provider) {
        boolean acquired = false;
        try {
            acquired = this.chunkLock.tryLock(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (acquired) {
            try {
                Chunk chunk = this.underlyingChunk;
                if (chunk != null) {
                    Biome b = chunk.getBiome(pos, provider);
                    if (b != null) return b;
                }
                Biome b = provider != null ? provider.getBiome(pos) : null;
                return b != null ? b : net.minecraft.init.Biomes.PLAINS;
            } finally {
                this.chunkLock.unlock();
            }
        } else {
            Chunk chunk = this.underlyingChunk;
            if (chunk != null) {
                Biome b = chunk.getBiome(pos, provider);
                if (b != null) return b;
            }
            Biome b = provider != null ? provider.getBiome(pos) : null;
            return b != null ? b : net.minecraft.init.Biomes.PLAINS;
        }
    }

    public int getHeight(BlockPos pos) {
        boolean acquired = false;
        try {
            acquired = this.chunkLock.tryLock(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (acquired) {
            try {
                Chunk chunk = this.underlyingChunk;
                if (chunk != null) {
                    return chunk.getHeight(pos);
                }
                return 64;
            } finally {
                this.chunkLock.unlock();
            }
        } else {
            Chunk chunk = this.underlyingChunk;
            return chunk != null ? chunk.getHeight(pos) : 64;
        }
    }

    /**
     * Emulates the tile-entity side of {@code World.setBlockState} on the private proto
     * chunk. Vanilla {@code Chunk.setBlockState} only swaps the block state; without
     * this, generators that place a chest and immediately call {@code world.getTileEntity}
     * (Roguelike Dungeons loot tables) get null and throw.
     */
    private void updateTileEntity(Chunk chunk, BlockPos pos, IBlockState oldState, IBlockState newState) {
        boolean hadTe = oldState.getBlock().hasTileEntity(oldState);
        boolean hasTe = newState.getBlock().hasTileEntity(newState);
        if (hadTe && (!hasTe || oldState.getBlock() != newState.getBlock())) {
            chunk.removeTileEntity(pos);
        }
        if (hasTe && (!hadTe || oldState.getBlock() != newState.getBlock())) {
            TileEntity tileEntity = newState.getBlock().createTileEntity(this.world, newState);
            if (tileEntity != null) {
                tileEntity.setWorld(this.world);
                tileEntity.setPos(pos);
                chunk.addTileEntity(pos, tileEntity);
            }
        }
    }

    public TileEntity getTileEntity(BlockPos pos) {
        boolean acquired = false;
        try {
            acquired = this.chunkLock.tryLock(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (acquired) {
            try {
                Chunk chunk = this.underlyingChunk;
                if (chunk != null) {
                    return chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);
                }
                return null;
            } finally {
                this.chunkLock.unlock();
            }
        } else {
            Chunk chunk = this.underlyingChunk;
            return chunk != null ? chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK) : null;
        }
    }

    public void addTileEntity(BlockPos pos, TileEntity tileEntity) {
        if (tileEntity != null) {
            IBlockState state = getBlockState(pos);
            if (state == null || !state.getBlock().hasTileEntity(state)) {
                com.cleanroommc.worldflow.WorldFlow.LOGGER.warn("[WorldFlow] ProtoChunk TE Atomicity Guard: Rejected {} at {} because block is {}",
                        tileEntity.getClass().getName(), pos, state != null ? state.getBlock().getRegistryName() : "null");
                com.cleanroommc.worldflow.pipeline.GenerationScheduler.getInstance().guardsBlockedCount.incrementAndGet();
                return;
            }
            boolean acquired = false;
            try {
                acquired = this.chunkLock.tryLock(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (acquired) {
                try {
                    Chunk chunk = this.underlyingChunk;
                    if (chunk != null) {
                        chunk.addTileEntity(pos, tileEntity);
                    }
                } finally {
                    this.chunkLock.unlock();
                }
            } else {
                net.minecraft.nbt.NBTTagCompound nbt = new net.minecraft.nbt.NBTTagCompound();
                tileEntity.writeToNBT(nbt);
                CascadeBlockBuffer.INSTANCE.stageBlock(this.world, pos, state, nbt, "ContendedTileEntityLock");
            }
        }
    }

    public void addEntity(Entity entity) {
        if (entity != null) {
            boolean acquired = false;
            try {
                acquired = this.chunkLock.tryLock(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (acquired) {
                try {
                    Chunk chunk = this.underlyingChunk;
                    if (chunk != null) {
                        chunk.addEntity(entity);
                    }
                } finally {
                    this.chunkLock.unlock();
                }
            }
        }
    }

    public boolean isTerrainPopulated() {
        Chunk chunk = this.underlyingChunk;
        return chunk != null && chunk.isTerrainPopulated();
    }

    public void setTerrainPopulated(boolean populated) {
        Chunk chunk = this.underlyingChunk;
        if (chunk != null) {
            chunk.setTerrainPopulated(populated);
        }
    }

    public boolean isLightPopulated() {
        Chunk chunk = this.underlyingChunk;
        return chunk != null && chunk.isLightPopulated();
    }

    public void setLightPopulated(boolean lightPopulated) {
        Chunk chunk = this.underlyingChunk;
        if (chunk != null) {
            chunk.setLightPopulated(lightPopulated);
        }
    }
}
