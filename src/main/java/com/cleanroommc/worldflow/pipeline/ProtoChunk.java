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

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicReference;

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

    private final Object chunkLock = new Object();

    public Chunk getChunk() {
        synchronized (this.chunkLock) {
            return this.underlyingChunk;
        }
    }

    public void setUnderlyingChunk(Chunk chunk) {
        if (chunk != null) {
            synchronized (this.chunkLock) {
                this.underlyingChunk = chunk;
            }
        }
    }

    public IBlockState getBlockState(BlockPos pos) {
        if (pos.getY() < 0 || pos.getY() >= 256) {
            return Blocks.AIR.getDefaultState();
        }
        synchronized (this.chunkLock) {
            Chunk chunk = this.underlyingChunk;
            if (chunk == null) {
                return Blocks.AIR.getDefaultState();
            }
            return chunk.getBlockState(pos);
        }
    }

    public boolean setBlockState(BlockPos pos, IBlockState state) {
        if (pos.getY() < 0 || pos.getY() >= 256 || state == null) {
            return false;
        }
        synchronized (this.chunkLock) {
            Chunk chunk = this.underlyingChunk;
            if (chunk == null) {
                return false;
            }
            return chunk.setBlockState(pos, state) != null;
        }
    }

    public Biome getBiome(BlockPos pos, BiomeProvider provider) {
        synchronized (this.chunkLock) {
            Chunk chunk = this.underlyingChunk;
            if (chunk != null) {
                return chunk.getBiome(pos, provider);
            }
            return provider.getBiome(pos);
        }
    }

    public int getHeight(BlockPos pos) {
        synchronized (this.chunkLock) {
            Chunk chunk = this.underlyingChunk;
            if (chunk != null) {
                return chunk.getHeight(pos);
            }
            return 64;
        }
    }

    @Nullable
    public TileEntity getTileEntity(BlockPos pos) {
        synchronized (this.chunkLock) {
            Chunk chunk = this.underlyingChunk;
            if (chunk != null) {
                return chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);
            }
            return null;
        }
    }

    public void addTileEntity(BlockPos pos, TileEntity tileEntity) {
        if (tileEntity != null) {
            synchronized (this.chunkLock) {
                Chunk chunk = this.underlyingChunk;
                if (chunk != null) {
                    chunk.addTileEntity(pos, tileEntity);
                }
            }
        }
    }

    public void addEntity(Entity entity) {
        if (entity != null) {
            synchronized (this.chunkLock) {
                Chunk chunk = this.underlyingChunk;
                if (chunk != null) {
                    chunk.addEntity(entity);
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
