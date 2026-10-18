package com.cleanroommc.worldflow.pipeline.isolation;

import com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer;
import com.cleanroommc.worldflow.pipeline.GenerationScheduler;
import com.cleanroommc.worldflow.pipeline.ProtoChunk;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.chunk.Chunk;

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
        public final Chunk emptyChunk;
        public final String generatorName;

        public GenerationWorkerContext(WorldServer world, ChunkPos centerPos, ProtoChunk centerProto, String generatorName) {
            this.world = world;
            this.centerPos = centerPos;
            this.centerProto = centerProto;
            this.generatorName = generatorName;
            this.emptyChunk = new Chunk(world, centerPos.x, centerPos.z);
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

        ProtoChunk anyProto = GenerationScheduler.getInstance().getProtoChunkAny(key);
        if (anyProto != null) {
            return anyProto.getBlockState(pos);
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

        ProtoChunk anyProto = GenerationScheduler.getInstance().getProtoChunkAny(key);
        if (anyProto != null && anyProto.getChunk() != null) {
            return anyProto.getChunk();
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

        BiomeProvider provider = WorkerGeneratorContext.get().getBiomeProvider(world);

        ProtoChunk proto = ctx.neighbourhood.get(key);
        if (proto != null) {
            Biome b = proto.getBiome(pos, provider);
            if (b != null) return b;
        }

        ProtoChunk anyProto = GenerationScheduler.getInstance().getProtoChunkAny(key);
        if (anyProto != null) {
            Biome b = anyProto.getBiome(pos, provider);
            if (b != null) return b;
        }

        Chunk loaded = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        if (loaded != null) {
            Biome b = loaded.getBiome(pos, provider);
            if (b != null) return b;
        }

        if (provider != null) {
            try {
                Biome b = provider.getBiome(pos);
                if (b != null) return b;
            } catch (Throwable ignored) {
            }
        }

        return Biomes.PLAINS;
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

    public static int getLight(World world, BlockPos pos, boolean checkNeighbors) {
        GenerationWorkerContext ctx = CURRENT_CONTEXT.get();
        if (ctx == null) {
            return 15;
        }
        if (pos.getY() < 0) return 0;
        if (pos.getY() >= 256) return 15;

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long key = ChunkPos.asLong(chunkX, chunkZ);

        ProtoChunk proto = ctx.neighbourhood.get(key);
        if (proto != null) {
            return getChunkSafeLight(proto.getChunk(), pos);
        }

        Chunk loaded = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        if (loaded != null) {
            return getChunkSafeLight(loaded, pos);
        }

        Integer height = getHeight(world, pos);
        return (height != null && pos.getY() >= height) ? 15 : 0;
    }

    public static int getLightFor(World world, net.minecraft.world.EnumSkyBlock type, BlockPos pos) {
        if (pos.getY() < 0) return 0;
        if (pos.getY() >= 256) return type == net.minecraft.world.EnumSkyBlock.SKY ? 15 : 0;

        GenerationWorkerContext ctx = CURRENT_CONTEXT.get();
        if (ctx == null) {
            return type == net.minecraft.world.EnumSkyBlock.SKY ? 15 : 0;
        }

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long key = ChunkPos.asLong(chunkX, chunkZ);

        ProtoChunk proto = ctx.neighbourhood.get(key);
        if (proto != null) {
            return getChunkSafeLightFor(proto.getChunk(), type, pos);
        }

        Chunk loaded = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        if (loaded != null) {
            return getChunkSafeLightFor(loaded, type, pos);
        }

        Integer height = getHeight(world, pos);
        if (type == net.minecraft.world.EnumSkyBlock.SKY) {
            return (height != null && pos.getY() >= height) ? 15 : 0;
        }
        return 0;
    }

    public static int getCombinedLight(World world, BlockPos pos, int lightValue) {
        int sky = getLightFor(world, net.minecraft.world.EnumSkyBlock.SKY, pos);
        int block = getLightFor(world, net.minecraft.world.EnumSkyBlock.BLOCK, pos);
        if (block < lightValue) {
            block = lightValue;
        }
        return sky << 20 | block << 4;
    }

    public static int getChunkSafeLight(Chunk chunk, BlockPos pos) {
        if (chunk == null) return pos.getY() >= 64 ? 15 : 0;
        int y = pos.getY();
        if (y < 0) return 0;
        if (y >= 256) return 15;

        net.minecraft.world.chunk.storage.ExtendedBlockStorage[] storage = chunk.getBlockStorageArray();
        int sec = y >> 4;
        int lx = pos.getX() & 15;
        int ly = y & 15;
        int lz = pos.getZ() & 15;

        if (storage != null && sec >= 0 && sec < storage.length && storage[sec] != null) {
            int blockLight = storage[sec].getBlockLight(lx, ly, lz);
            int skyLight = storage[sec].getSkyLight(lx, ly, lz);
            if (skyLight == 0 && chunk.getHeightValue(lx, lz) <= y) {
                skyLight = 15;
            }
            return Math.max(blockLight, skyLight);
        }

        int topY = chunk.getHeightValue(lx, lz);
        return y >= topY ? 15 : 0;
    }

    public static int getChunkSafeLightFor(Chunk chunk, net.minecraft.world.EnumSkyBlock type, BlockPos pos) {
        if (chunk == null) return type == net.minecraft.world.EnumSkyBlock.SKY ? (pos.getY() >= 64 ? 15 : 0) : 0;
        int y = pos.getY();
        if (y < 0) return 0;
        if (y >= 256) return type == net.minecraft.world.EnumSkyBlock.SKY ? 15 : 0;

        net.minecraft.world.chunk.storage.ExtendedBlockStorage[] storage = chunk.getBlockStorageArray();
        int sec = y >> 4;
        int lx = pos.getX() & 15;
        int ly = y & 15;
        int lz = pos.getZ() & 15;

        if (storage != null && sec >= 0 && sec < storage.length && storage[sec] != null) {
            if (type == net.minecraft.world.EnumSkyBlock.SKY) {
                int sky = storage[sec].getSkyLight(lx, ly, lz);
                if (sky == 0 && chunk.getHeightValue(lx, lz) <= y) {
                    sky = 15;
                }
                return sky;
            } else {
                return storage[sec].getBlockLight(lx, ly, lz);
            }
        }

        if (type == net.minecraft.world.EnumSkyBlock.SKY) {
            int topY = chunk.getHeightValue(lx, lz);
            return y >= topY ? 15 : 0;
        }
        return 0;
    }
}
