package com.cleanroommc.worldflow.pipeline.isolation;

import com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer;
import com.cleanroommc.worldflow.pipeline.ProtoChunk;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-local isolation layer for parallel chunk generation worker threads.
 *
 * <p>When a worker thread generates or populates a chunk, all world queries
 * (getBlockState, setBlockState, getChunk, getBiome, getHeight, getTileEntity)
 * resolve strictly against the thread's local ProtoChunks or already-loaded chunks.</p>
 *
 * <p>Never invokes {@code ChunkProviderServer.provideChunk} or touches live world
 * unloaded state, eliminating cascading chunk generation and cross-thread concurrency races.</p>
 */
public class ThreadLocalWorldView {

    private static final ThreadLocal<GenerationWorkerContext> CURRENT_CONTEXT = new ThreadLocal<>();

    public static class GenerationWorkerContext {
        public final WorldServer world;
        public final ChunkPos centerPos;
        public final ProtoChunk centerProto;
        public final Map<Long, ProtoChunk> neighbourhood = new ConcurrentHashMap<>();
        public final EmptyChunk emptyChunk;
        public final String generatorName;

        public GenerationWorkerContext(WorldServer world, ChunkPos centerPos, ProtoChunk centerProto, String generatorName) {
            this.world = world;
            this.centerPos = centerPos;
            this.centerProto = centerProto;
            this.generatorName = generatorName;
            this.emptyChunk = new EmptyChunk(world, centerPos.x, centerPos.z);
            if (centerProto != null) {
                this.neighbourhood.put(ChunkPos.asLong(centerPos.x, centerPos.z), centerProto);
            }
        }

        public void addNeighbour(ProtoChunk protoChunk) {
            if (protoChunk != null) {
                this.neighbourhood.put(ChunkPos.asLong(protoChunk.getX(), protoChunk.getZ()), protoChunk);
            }
        }
    }

    public static void enter(WorldServer world, ChunkPos pos, ProtoChunk proto, String generatorName) {
        CURRENT_CONTEXT.set(new GenerationWorkerContext(world, pos, proto, generatorName));
    }

    public static void attachNeighbourhood(Map<Long, ProtoChunk> neighbours) {
        GenerationWorkerContext ctx = CURRENT_CONTEXT.get();
        if (ctx != null && neighbours != null) {
            ctx.neighbourhood.putAll(neighbours);
        }
    }

    public static void exit() {
        CURRENT_CONTEXT.remove();
    }

    public static boolean isGenerating() {
        return CURRENT_CONTEXT.get() != null;
    }

    @Nullable
    public static GenerationWorkerContext getContext() {
        return CURRENT_CONTEXT.get();
    }

    @Nullable
    public static IBlockState getBlockState(World world, BlockPos pos) {
        GenerationWorkerContext ctx = CURRENT_CONTEXT.get();
        if (ctx == null) {
            return null; // Not in generating thread context
        }
        if (pos.getY() < 0 || pos.getY() >= 256) {
            return Blocks.AIR.getDefaultState();
        }

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long key = ChunkPos.asLong(chunkX, chunkZ);

        ProtoChunk proto = ctx.neighbourhood.get(key);
        if (proto != null) {
            return proto.getBlockState(pos);
        }

        // Check if loaded in live world
        Chunk loaded = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        if (loaded != null) {
            return loaded.getBlockState(pos);
        }

        // Unloaded chunk: Return AIR immediately without triggering provideChunk
        return Blocks.AIR.getDefaultState();
    }

    @Nullable
    public static Boolean setBlockState(World world, BlockPos pos, IBlockState state, int flags) {
        GenerationWorkerContext ctx = CURRENT_CONTEXT.get();
        if (ctx == null) {
            return null;
        }
        if (pos.getY() < 0 || pos.getY() >= 256 || state == null) {
            return false;
        }

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long key = ChunkPos.asLong(chunkX, chunkZ);

        ProtoChunk proto = ctx.neighbourhood.get(key);
        if (proto != null) {
            return proto.setBlockState(pos, state);
        }

        // Check if loaded in live world
        Chunk loaded = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        if (loaded != null) {
            return loaded.setBlockState(pos, state) != null;
        }

        // Outside neighbourhood and not loaded: STAGE in CascadeBlockBuffer to avoid cascading generation
        CascadeBlockBuffer.INSTANCE.stageBlock(
                world,
                pos,
                state,
                null,
                ctx.generatorName != null ? ctx.generatorName : "ParallelGenWorker"
        );
        return true;
    }

    @Nullable
    public static Chunk getChunk(World world, int chunkX, int chunkZ) {
        GenerationWorkerContext ctx = CURRENT_CONTEXT.get();
        if (ctx == null) {
            return null;
        }

        long key = ChunkPos.asLong(chunkX, chunkZ);
        ProtoChunk proto = ctx.neighbourhood.get(key);
        if (proto != null) {
            return proto.getChunk();
        }

        Chunk loaded = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        if (loaded != null) {
            return loaded;
        }

        // Return empty chunk so queries don't NPE, but NEVER trigger synchronous disk load or generateChunk
        return ctx.emptyChunk;
    }

    @Nullable
    public static Biome getBiome(World world, BlockPos pos) {
        GenerationWorkerContext ctx = CURRENT_CONTEXT.get();
        if (ctx == null) {
            return null;
        }

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long key = ChunkPos.asLong(chunkX, chunkZ);

        ProtoChunk proto = ctx.neighbourhood.get(key);
        if (proto != null) {
            return proto.getBiome(pos, world.getBiomeProvider());
        }

        Chunk loaded = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        if (loaded != null) {
            return loaded.getBiome(pos, world.getBiomeProvider());
        }

        return world.getBiomeProvider().getBiome(pos);
    }

    @Nullable
    public static Integer getHeight(World world, BlockPos pos) {
        GenerationWorkerContext ctx = CURRENT_CONTEXT.get();
        if (ctx == null) {
            return null;
        }

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long key = ChunkPos.asLong(chunkX, chunkZ);

        ProtoChunk proto = ctx.neighbourhood.get(key);
        if (proto != null) {
            return proto.getHeight(pos);
        }

        Chunk loaded = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        if (loaded != null) {
            return loaded.getHeight(pos);
        }

        return 64;
    }

    @Nullable
    public static TileEntity getTileEntity(World world, BlockPos pos) {
        GenerationWorkerContext ctx = CURRENT_CONTEXT.get();
        if (ctx == null) {
            return null;
        }

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long key = ChunkPos.asLong(chunkX, chunkZ);

        ProtoChunk proto = ctx.neighbourhood.get(key);
        if (proto != null) {
            return proto.getTileEntity(pos);
        }

        Chunk loaded = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        if (loaded != null) {
            return loaded.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);
        }

        return null;
    }

    public static boolean setTileEntity(World world, BlockPos pos, TileEntity tileEntity) {
        GenerationWorkerContext ctx = CURRENT_CONTEXT.get();
        if (ctx == null) {
            return false;
        }

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long key = ChunkPos.asLong(chunkX, chunkZ);

        ProtoChunk proto = ctx.neighbourhood.get(key);
        if (proto != null) {
            proto.addTileEntity(pos, tileEntity);
            return true;
        }

        Chunk loaded = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        if (loaded != null) {
            loaded.addTileEntity(pos, tileEntity);
            return true;
        }

        return false;
    }

    @Nullable
    public static Boolean isBlockLoaded(World world, BlockPos pos) {
        GenerationWorkerContext ctx = CURRENT_CONTEXT.get();
        if (ctx == null) {
            return null;
        }
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long key = ChunkPos.asLong(chunkX, chunkZ);
        if (ctx.neighbourhood.containsKey(key)) {
            return true;
        }
        return world.getChunkProvider().getLoadedChunk(chunkX, chunkZ) != null;
    }
}
