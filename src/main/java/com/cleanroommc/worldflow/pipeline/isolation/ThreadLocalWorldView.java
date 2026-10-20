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
        public final long centerKey;
        public final Map<Long, ProtoChunk> neighbourhood = new ConcurrentHashMap<>();
        public final Chunk emptyChunk;
        public final String generatorName;
        private final Map<Long, Chunk> emptyChunks = new ConcurrentHashMap<>();

        // Direct-mapped cache for neighbourhood lookups: avoids boxing a Long key on
        // every block read (JFR: HashMap.getNode was a top feature-stage frame).
        private final long[] cacheKeys = new long[8];
        private final ProtoChunk[] cacheProtos = new ProtoChunk[8];

        public GenerationWorkerContext(WorldServer world, ChunkPos centerPos, ProtoChunk centerProto, String generatorName) {
            this.world = world;
            this.centerPos = centerPos;
            this.centerProto = centerProto;
            this.centerKey = ChunkPos.asLong(centerPos.x, centerPos.z);
            this.generatorName = generatorName;
            this.emptyChunk = new Chunk(world, centerPos.x, centerPos.z);
            if (centerProto != null) {
                this.neighbourhood.put(this.centerKey, centerProto);
            }
        }

        /** Fast, allocation-free neighbourhood lookup. */
        public ProtoChunk lookup(long key) {
            if (key == this.centerKey) {
                return this.centerProto;
            }
            int slot = (int) (key ^ (key >>> 32)) & 7;
            if (this.cacheKeys[slot] == key) {
                ProtoChunk cached = this.cacheProtos[slot];
                if (cached != null) {
                    return cached;
                }
            }
            ProtoChunk proto = this.neighbourhood.get(key);
            if (proto != null) {
                this.cacheKeys[slot] = key;
                this.cacheProtos[slot] = proto;
            }
            return proto;
        }

        public Chunk getOrCreateEmptyChunk(int cx, int cz) {
            long key = ChunkPos.asLong(cx, cz);
            return this.emptyChunks.computeIfAbsent(key, k -> new Chunk(this.world, cx, cz));
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
        return getBlockState(world, pos.getX(), pos.getY(), pos.getZ());
    }

    @Nullable
    public static IBlockState getBlockState(World world, int x, int y, int z) {
        GenerationWorkerContext ctx = CURRENT_CONTEXT.get();
        if (ctx == null) {
            return null; // Not in generating thread context
        }
        if (y < 0 || y >= 256) {
            return Blocks.AIR.getDefaultState();
        }

        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        long key = ChunkPos.asLong(chunkX, chunkZ);

        ProtoChunk proto = ctx.lookup(key);
        if (proto != null) {
            return proto.getBlockStateFast(x, y, z);
        }

        ProtoChunk anyProto = GenerationScheduler.getInstance().getProtoChunkAny(key);
        if (anyProto != null) {
            return anyProto.getBlockStateFast(x, y, z);
        }

        // Check if loaded in live world
        Chunk loaded = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        if (loaded != null) {
            return loaded.getBlockState(x & 15, y, z & 15);
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

        // Check if loaded in live world (e.g. post-publish late write)
        Chunk loaded = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        if (loaded != null) {
            synchronized (loaded) {
                IBlockState res = loaded.setBlockState(pos, state);
                if (res != null) {
                    loaded.markDirty();
                }
                // Chunk.setBlockState bypasses World.setBlockState's TileEntity creation.
                // Create the TE here so chests placed by generators have one immediately.
                if (state.getBlock().hasTileEntity(state)) {
                    TileEntity te = loaded.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);
                    if (te == null) {
                        te = state.getBlock().createTileEntity(world, state);
                        if (te != null) {
                            te.setWorld(world);
                            te.setPos(pos);
                            loaded.addTileEntity(pos, te);
                        }
                    }
                }
                return res != null;
            }
        }

        // Outside neighbourhood and not loaded: STAGE in CascadeBlockBuffer to avoid cascading generation.
        // Create the tile entity now so generators that place a chest and immediately query it
        // (Roguelike loot tables) do not receive null.
        net.minecraft.nbt.NBTTagCompound teNbt = null;
        if (state.getBlock().hasTileEntity(state)) {
            TileEntity stagedTe = state.getBlock().createTileEntity(world, state);
            if (stagedTe != null) {
                stagedTe.setWorld(world);
                stagedTe.setPos(pos);
                CascadeBlockBuffer.INSTANCE.stageTileEntity(world, pos, stagedTe);
                teNbt = stagedTe.writeToNBT(new net.minecraft.nbt.NBTTagCompound());
            } else {
                CascadeBlockBuffer.INSTANCE.stageTileEntity(world, pos, null);
            }
        } else {
            CascadeBlockBuffer.INSTANCE.stageTileEntity(world, pos, null);
        }
        CascadeBlockBuffer.INSTANCE.stageBlock(
                world,
                pos,
                state,
                teNbt,
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
        return ctx.getOrCreateEmptyChunk(chunkX, chunkZ);
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
    private static TileEntity createMissingTileEntity(World world, BlockPos pos, IBlockState state) {
        if (state == null || !state.getBlock().hasTileEntity(state)) {
            return null;
        }
        TileEntity te = state.getBlock().createTileEntity(world, state);
        if (te == null) {
            return null;
        }
        te.setWorld(world);
        te.setPos(pos);
        setTileEntity(world, pos, te);
        return te;
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

        ProtoChunk proto = ctx.lookup(key);
        if (proto != null) {
            TileEntity te = proto.getTileEntity(pos);
            if (te != null) {
                return te;
            }
            TileEntity stagedInProto = CascadeBlockBuffer.INSTANCE.getStagedTileEntity(world, pos);
            if (stagedInProto != null) {
                return stagedInProto;
            }
            return createMissingTileEntity(world, pos, proto.getBlockState(pos));
        }

        Chunk loaded = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        if (loaded != null) {
            TileEntity te = loaded.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);
            if (te != null) {
                return te;
            }
            return createMissingTileEntity(world, pos, loaded.getBlockState(pos));
        }

        // Staged cross-chunk block: return the live TE instance so generators can
        // configure it before the block is flushed into the real chunk.
        return CascadeBlockBuffer.INSTANCE.getStagedTileEntity(world, pos);
    }

    public static boolean setTileEntity(World world, BlockPos pos, TileEntity tileEntity) {
        GenerationWorkerContext ctx = CURRENT_CONTEXT.get();
        if (ctx == null || tileEntity == null) {
            return false;
        }

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long key = ChunkPos.asLong(chunkX, chunkZ);

        // ATOMICITY GUARD: Verify the block at this position actually supports a TileEntity!
        IBlockState currentBlock = getBlockState(world, pos);
        if (currentBlock == null || !currentBlock.getBlock().hasTileEntity(currentBlock)) {
            com.cleanroommc.worldflow.WorldFlow.LOGGER.warn("[WorldFlow] TileEntity Atomicity Guard: Rejected TileEntity {} at {} because block is {}",
                    tileEntity.getClass().getName(), pos,
                    currentBlock != null ? currentBlock.getBlock().getRegistryName() : "null");
            com.cleanroommc.worldflow.pipeline.GenerationScheduler.getInstance().guardsBlockedCount.incrementAndGet();
            return false;
        }

        tileEntity.setWorld(world);
        tileEntity.setPos(pos);

        ProtoChunk proto = ctx.neighbourhood.get(key);
        if (proto != null) {
            proto.addTileEntity(pos, tileEntity);
            return true;
        }

        Chunk loaded = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        if (loaded != null) {
            synchronized (loaded) {
                IBlockState loadedState = loaded.getBlockState(pos);
                if (loadedState == null || !loadedState.getBlock().hasTileEntity(loadedState)) {
                    com.cleanroommc.worldflow.pipeline.GenerationScheduler.getInstance().guardsBlockedCount.incrementAndGet();
                    return false;
                }
                loaded.addTileEntity(pos, tileEntity);
                loaded.markDirty();
            }
            if (world instanceof WorldServer) {
                ((WorldServer) world).addScheduledTask(() -> {
                    if (world.getChunkProvider().getLoadedChunk(chunkX, chunkZ) != null) {
                        world.addTileEntity(tileEntity);
                    }
                });
            }
            return true;
        }

        net.minecraft.nbt.NBTTagCompound nbt = new net.minecraft.nbt.NBTTagCompound();
        try {
            tileEntity.writeToNBT(nbt);
        } catch (Throwable t) {
            com.cleanroommc.worldflow.WorldFlow.LOGGER.debug("[WorldFlow] Failed to serialize staged TileEntity NBT", t);
        }
        CascadeBlockBuffer.INSTANCE.stageBlock(
                world,
                pos,
                currentBlock,
                nbt,
                ctx.generatorName != null ? ctx.generatorName : "ParallelGenWorker"
        );
        return true;
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
