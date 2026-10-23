package com.cleanroommc.worldflow.pipeline;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkEvent;

import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages thread handoff from generation worker threads to the main server tick thread.
 *
 * <p>Inserts completed chunks into {@code ChunkProviderServer.loadedChunks}, runs lighting,
 * flushes staged blocks from {@link CascadeBlockBuffer}, fires {@link ChunkEvent.Load},
 * and dispatches packets to waiting players via {@link PlayerChunkMapEntry}.</p>
 */
public class PublishManager {

    public static final PublishManager INSTANCE = new PublishManager();

    private final Queue<StatusHolder> pendingPublishQueue = new ConcurrentLinkedQueue<>();
    private final Queue<TePurge> tePurgeQueue = new ConcurrentLinkedQueue<>();
    public final AtomicLong totalChunksPublished = new AtomicLong(0);
    public final java.util.concurrent.atomic.AtomicLong nanosInsert = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong nanosFlush = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong nanosSanitize = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong nanosOnLoad = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong nanosEvents = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong nanosPlayer = new java.util.concurrent.atomic.AtomicLong();
    public final java.util.concurrent.atomic.AtomicLong publishCount = new java.util.concurrent.atomic.AtomicLong();
    public final AtomicLong totalLightNanos = new AtomicLong(0);
    public final AtomicLong totalLightCount = new AtomicLong(0);

    private static final class TePurge {
        final WorldServer world;
        final net.minecraft.util.math.BlockPos pos;

        TePurge(WorldServer world, net.minecraft.util.math.BlockPos pos) {
            this.world = world;
            this.pos = pos;
        }
    }

    /** Worker threads cannot touch World.loadedTileEntityList; queue the removal for the tick thread. */
    public void enqueueTileEntityPurge(WorldServer world, net.minecraft.util.math.BlockPos pos) {
        if (world != null && pos != null && this.tePurgeQueue.size() < 4096) {
            this.tePurgeQueue.add(new TePurge(world, pos));
        }
    }

    private void drainTileEntityPurges() {
        TePurge purge;
        int budget = 256;
        while (budget-- > 0 && (purge = this.tePurgeQueue.poll()) != null) {
            final TePurge p = purge;
            try {
                p.world.loadedTileEntityList.removeIf(te -> te != null && p.pos.equals(te.getPos()));
            } catch (Throwable ignored) {
            }
        }
    }

    private PublishManager() {
    }

    public void enqueueReady(StatusHolder holder) {
        if (holder != null) {
            this.pendingPublishQueue.add(holder);
        }
    }

    public boolean isPending(StatusHolder holder) {
        return this.pendingPublishQueue.contains(holder);
    }

    /**
     * Called on the server tick thread at the beginning of each tick for each WorldServer.
     * Processes ready chunks up to maxBudget per tick to guarantee 0 tick lag.
     */
    public int tick(WorldServer world, int maxBudget) {
        CascadeBlockBuffer.INSTANCE.flushStagedEntitiesForLoadedChunks(world);
        drainTileEntityPurges();
        com.cleanroommc.worldflow.pipeline.structure.StructureNbtStager.drain(128);
        tileEntityHygiene(world);
        GenerationScheduler.getInstance().sweepPublishableIfDirty();
        if (this.pendingPublishQueue.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        Iterator<StatusHolder> iterator = this.pendingPublishQueue.iterator();

        while (iterator.hasNext() && publishedCount < maxBudget) {
            StatusHolder holder = iterator.next();
            if (holder.getWorld() != world) {
                continue;
            }

            iterator.remove();
            publishChunk(world, holder);
            publishedCount++;
        }

        // Cross-chunk writes aimed at already-published chunks are staged; apply them
        // here so the live chunk converges within a tick instead of at save time.
        CascadeBlockBuffer.INSTANCE.flushAll(world);
        CascadeBlockBuffer.INSTANCE.flushStagedTicksForLoadedChunks(world);
        CascadeBlockBuffer.INSTANCE.flushStagedEntitiesForLoadedChunks(world);

        // ChunkProviderServer.getLoadedChunk() clears unloadQueued. Feature neighbours
        // touch published chunks constantly, so a publish-time queueUnload is not enough:
        // the server cache grew to 3,322 loaded chunks during the 4,225 run. Re-queue
        // chunks outside every live 3x3 feature window once per second.
        if ((++this.unloadSweepTicks % 20) == 0
                && world.playerEntities.isEmpty()
                && GenerationScheduler.getInstance().getInFlightCount() > 0) {
            java.util.Set<Long> protectedKeys = GenerationScheduler.getInstance().getProtectedChunkKeys();
            for (Chunk loaded : world.getChunkProvider().loadedChunks.values()) {
                if (loaded == null) continue;
                long key = ChunkPos.asLong(loaded.x, loaded.z);
                if (protectedKeys.contains(key)) continue;
                if (net.minecraftforge.common.ForgeChunkManager.getPersistentChunksFor(world)
                        .containsKey(new ChunkPos(loaded.x, loaded.z))) continue;
                world.getChunkProvider().queueUnload(loaded);
            }
        }

        return publishedCount;
    }

    public void publishChunk(WorldServer world, StatusHolder holder) {
        if (holder == null || holder.getWorld() != world) {
            return;
        }

        // Strictly enforce main-thread execution for all world mutations and event dispatches
        if (!world.isCallingFromMinecraftThread()) {
            enqueueReady(holder);
            world.addScheduledTask(() -> publishChunk(world, holder));
            return;
        }

        long key = holder.getChunkKey();
        int cx = holder.getPos().x;
        int cz = holder.getPos().z;
        holder.resetPublishEnqueued();

        // 1. Invalidation / Supersede Check: Check if already loaded in RAM
        Chunk existing = world.getChunkProvider().getLoadedChunk(cx, cz);
        if (existing != null) {
            // Live chunk already present — drop stale proto to prevent ghost chunks / overwrite
            holder.setCurrentStatus(ChunkStatus.FULL);
            holder.setOwnDecorationDone(true);
            holder.getCarversFuture().complete(existing);
            holder.getWorkerDoneFuture().complete(existing);
            holder.getFuture().complete(existing);
            GenerationScheduler.getInstance().onChunkPublished(world, holder);
            GenerationScheduler.getInstance().removeInFlight(key);
            GenerationScheduler.getInstance().notifyNeighboursOfCompletion(cx, cz);
            return;
        }

        ProtoChunk proto = holder.getProtoChunk();
        Chunk chunk = proto.getChunk();
        if (chunk == null) {
            chunk = new Chunk(world, cx, cz);
            proto.setUnderlyingChunk(chunk);
        }
        // Close the write window before the main thread touches the chunk: from this
        // point workers must stage cross-chunk writes instead of mutating the palette.
        if (chunk instanceof IWorldFlowChunk) {
            ((IWorldFlowChunk) chunk).worldflow$setPublished(true);
        }

        try {
            long tPhase = System.nanoTime();
            // 2. Insert into live loadedChunks map with synchronization
            synchronized (world.getChunkProvider().loadedChunks) {
                world.getChunkProvider().loadedChunks.put(key, chunk);
            }
            this.nanosInsert.addAndGet(System.nanoTime() - tPhase);

            // 3. Flush any staged blocks that were waiting for this chunk. Use the
            //    ProtoChunk lock: a worker may have entered its write path just before
            //    the published flag flipped.
            tPhase = System.nanoTime();
            proto.flushStagedBlocks(world);
            this.nanosFlush.addAndGet(System.nanoTime() - tPhase);

            // 3b. Tile-entity atomicity: a TE whose block does not support one (or was replaced
            //     by a structure write, e.g. bedrock) crashes mods on the next tick
            //     (rustic cabinets on bedrock + nuclearcraft radiation scan). Drop them before
            //     onLoad() registers the chunk's TEs into World.loadedTileEntityList.
            tPhase = System.nanoTime();
            sanitizeTileEntities(world, chunk);
            this.nanosSanitize.addAndGet(System.nanoTime() - tPhase);

            tPhase = System.nanoTime();
            chunk.onLoad();
            this.nanosOnLoad.addAndGet(System.nanoTime() - tPhase);

            // 4. Tick-thread lighting for the new chunk (measurable, optional).
            boolean skipLight = com.cleanroommc.worldflow.config.ModConfig.CONCURRENCY != null
                    && com.cleanroommc.worldflow.config.ModConfig.CONCURRENCY.skipPublishLighting;
            if (!skipLight) {
                long lightStart = System.nanoTime();
                try {
                    chunk.checkLight();
                } catch (Throwable t) {
                    WorldFlow.LOGGER.debug("[WorldFlow] Light check exception on chunk [{}, {}]", cx, cz, t);
                } finally {
                    this.totalLightNanos.addAndGet(System.nanoTime() - lightStart);
                    this.totalLightCount.incrementAndGet();
                }
            }

            // 5. Fire Forge events on the server tick thread (only ~10 mods listen; measurable)
            boolean fireEvents = com.cleanroommc.worldflow.config.ModConfig.CONCURRENCY == null
                    || com.cleanroommc.worldflow.config.ModConfig.CONCURRENCY.fireChunkEventsOnPublish;
            tPhase = System.nanoTime();
            if (fireEvents) {
                try {
                    MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.terraingen.PopulateChunkEvent.Post(world.getChunkProvider().chunkGenerator, world, world.rand, cx, cz, false));
                } catch (Throwable t) {
                    WorldFlow.LOGGER.debug("[WorldFlow] Handled exception in PopulateChunkEvent.Post", t);
                }
                try {
                    MinecraftForge.EVENT_BUS.post(new ChunkEvent.Load(chunk));
                } catch (Throwable t) {
                    WorldFlow.LOGGER.debug("[WorldFlow] Handled exception in ChunkEvent.Load", t);
                }
            }
            this.nanosEvents.addAndGet(System.nanoTime() - tPhase);

            tPhase = System.nanoTime();
            // 6. Update PlayerChunkMapEntry and send packet to waiting players
            PlayerChunkMapEntry entry = world.getPlayerChunkMap().getEntry(cx, cz);
            if (entry != null) {
                if (entry instanceof IPlayerChunkMapEntry) {
                    ((IPlayerChunkMapEntry) entry).worldflow$setChunk(chunk);
                }
                if (entry.getChunk() != null) {
                    entry.sendToPlayers();
                }
            } else if (!holder.isPlayerRequested()) {
                // Batch/pregen publish: immediately release the live chunk after its
                // payload is committed. This keeps loadedChunks and entity lists bounded.
                boolean hasForgeTicket = net.minecraftforge.common.ForgeChunkManager.getPersistentChunksFor(world).containsKey(holder.getPos());
                if (!hasForgeTicket) {
                    world.getChunkProvider().queueUnload(chunk);
                }
            } else if (holder.isPlayerRequested() && !holder.isSkipGhostCleanup()) {
                boolean hasForgeTicket = net.minecraftforge.common.ForgeChunkManager.getPersistentChunksFor(world).containsKey(holder.getPos());
                if (!hasForgeTicket) {
                    world.getChunkProvider().queueUnload(chunk);
                }
            }
            this.nanosPlayer.addAndGet(System.nanoTime() - tPhase);
        } catch (Throwable t) {
            WorldFlow.LOGGER.error("[WorldFlow] Error publishing chunk [{}, {}]", cx, cz, t);
        } finally {
            // 7. Complete status and future guaranteed
            if (chunk instanceof IWorldFlowChunk) {
                ((IWorldFlowChunk) chunk).worldflow$setGenPath(IWorldFlowChunk.GenPath.PIPELINE);
                ((IWorldFlowChunk) chunk).worldflow$setPublished(true);
            }
            GenerationScheduler.getInstance().totalChunksPipeline.incrementAndGet();
            holder.setCurrentStatus(ChunkStatus.FULL);
            holder.setOwnDecorationDone(true);
            holder.getCarversFuture().complete(chunk);
            holder.getWorkerDoneFuture().complete(chunk);
            holder.getFuture().complete(chunk);
            this.totalChunksPublished.incrementAndGet();
            this.publishCount.incrementAndGet();
            // Keep the carver cache entry: neighbour chunks still need this terrain for their
            // decoration. Evicting it on publish forced neighbours to regenerate base terrain.
            GenerationScheduler.getInstance().checkLogMetrics();

            // 8. Release the target-stencil reference before removing the holder.
            GenerationScheduler.getInstance().onChunkPublished(world, holder);
            GenerationScheduler.getInstance().removeInFlight(key);
            GenerationScheduler.getInstance().notifyNeighboursOfCompletion(cx, cz);
        }
    }

    /**
     * Removes tile entities that cannot legally exist at their position.
     * A TE must never outlive the block that owns it: mods iterate these maps during tick
     * (nuclearcraft radiation, hoppers, capabilities) and throw if the block state changed.
     */
    private void sanitizeTileEntities(WorldServer world, Chunk chunk) {
        try {
            java.util.Iterator<java.util.Map.Entry<net.minecraft.util.math.BlockPos, net.minecraft.tileentity.TileEntity>> it =
                    chunk.getTileEntityMap().entrySet().iterator();
            int removed = 0;
            while (it.hasNext()) {
                java.util.Map.Entry<net.minecraft.util.math.BlockPos, net.minecraft.tileentity.TileEntity> e = it.next();
                if (e.getValue() == null) {
                    it.remove();
                    continue;
                }
                net.minecraft.block.state.IBlockState state = chunk.getBlockState(e.getKey());
                if (!state.getBlock().hasTileEntity(state)) {
                    it.remove();
                    final net.minecraft.util.math.BlockPos badPos = e.getKey();
                    world.loadedTileEntityList.removeIf(te -> te != null && badPos.equals(te.getPos()));
                    removed++;
                }
            }
            if (removed > 0) {
                WorldFlow.LOGGER.warn("[WorldFlow] Dropped {} orphaned tile entit{} from chunk [{}, {}] (block no longer supports one)",
                        removed, removed == 1 ? "y" : "ies", chunk.x, chunk.z);
            }
        } catch (Throwable t) {
            WorldFlow.LOGGER.debug("[WorldFlow] TE sanitize failed for chunk [{}, {}]", chunk.x, chunk.z, t);
        }
    }

    /** Called from the world-tick START hook (highest priority) before any mod scans TEs. */
    public void maintainTileEntities(WorldServer world) {
        drainTileEntityPurges();
        tileEntityHygiene(world);
    }

    private int hygieneTickCounter = 0;
    private int unloadSweepTicks = 0;

    /**
     * Safety net for tile entities that end up on impossible blocks through paths we do not
     * intercept (mods calling Chunk.addTileEntity directly, staged structure writes, plugins).
     * Runs on the main thread every 5s; mods like nuclearcraft iterate this exact list and crash.
     */
    private void tileEntityHygiene(WorldServer world) {
        // Per-chunk sanitisation at publish handles the common case. The global sweep is a
        // safety net for mods that write TEs directly, and it is O(all loaded TEs): running
        // it every tick (or on every quiesce-drain iteration) stalls saves on large pregens.
        if (GenerationScheduler.getInstance().isQuiescing()) {
            return;
        }
        if ((++this.hygieneTickCounter % 100) != 0) {
            return;
        }
        try {
            int removed = 0;
            java.util.Iterator<net.minecraft.tileentity.TileEntity> it = world.loadedTileEntityList.iterator();
            while (it.hasNext()) {
                net.minecraft.tileentity.TileEntity te = it.next();
                if (te == null || te.isInvalid()) {
                    it.remove();
                    continue;
                }
                net.minecraft.util.math.BlockPos pos = te.getPos();
                if (!world.isBlockLoaded(pos)) {
                    continue;
                }
                net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
                if (state != null && !state.getBlock().hasTileEntity(state)) {
                    it.remove();
                    removed++;
                }
            }
            if (removed > 0) {
                WorldFlow.LOGGER.warn("[WorldFlow] TE hygiene: dropped {} tile entities sitting on unsupported blocks", removed);
            }
        } catch (Throwable t) {
            WorldFlow.LOGGER.debug("[WorldFlow] TE hygiene pass failed", t);
        }
    }

    /** Drops queued work when the world shuts down (singleplayer world reload). */
    public void reset() {
        this.pendingPublishQueue.clear();
        this.tePurgeQueue.clear();
    }

    public int getPendingCount() {
        return this.pendingPublishQueue.size();
    }
}
