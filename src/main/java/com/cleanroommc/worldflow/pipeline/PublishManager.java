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
    public final AtomicLong totalChunksPublished = new AtomicLong(0);

    private PublishManager() {
    }

    public void enqueueReady(StatusHolder holder) {
        if (holder != null) {
            this.pendingPublishQueue.add(holder);
        }
    }

    /**
     * Called on the server tick thread at the beginning of each tick for each WorldServer.
     * Processes ready chunks up to maxBudget per tick to guarantee 0 tick lag.
     */
    public int tick(WorldServer world, int maxBudget) {
        CascadeBlockBuffer.INSTANCE.flushStagedEntitiesForLoadedChunks(world);
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

        CascadeBlockBuffer.INSTANCE.flushStagedTicksForLoadedChunks(world);
        CascadeBlockBuffer.INSTANCE.flushStagedEntitiesForLoadedChunks(world);

        return publishedCount;
    }

    public void publishChunk(WorldServer world, StatusHolder holder) {
        if (holder == null || holder.getWorld() != world) {
            return;
        }

        long key = holder.getChunkKey();
        int cx = holder.getPos().x;
        int cz = holder.getPos().z;

        // 1. Invalidation / Supersede Check: Check if already loaded in RAM
        Chunk existing = world.getChunkProvider().getLoadedChunk(cx, cz);
        if (existing != null) {
            // Live chunk already present — drop stale proto to prevent ghost chunks / overwrite
            holder.setCurrentStatus(ChunkStatus.FULL);
            holder.getCarversFuture().complete(existing);
            holder.getFuture().complete(existing);
            GenerationScheduler.getInstance().removeInFlight(key);
            return;
        }

        ProtoChunk proto = holder.getProtoChunk();
        Chunk chunk = proto.getChunk();

        // 2. Insert into live loadedChunks map
        world.getChunkProvider().loadedChunks.put(key, chunk);
        chunk.onLoad();

        // 3. Flush any staged blocks that were waiting for this chunk
        CascadeBlockBuffer.INSTANCE.flushChunk(world, chunk);

        // 4. Tick-thread lighting calculation + neighbour border relight to prevent black seams
        try {
            chunk.checkLight();
            for (int[] offset : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                Chunk neighbour = world.getChunkProvider().getLoadedChunk(cx + offset[0], cz + offset[1]);
                if (neighbour != null) {
                    neighbour.checkLight();
                }
            }
        } catch (Throwable t) {
            WorldFlow.LOGGER.debug("[WorldFlow] Light check exception on chunk [{}, {}]", cx, cz, t);
        }

        // 5. Fire Forge events on the server tick thread
        try {
            MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.terraingen.PopulateChunkEvent.Post(world.getChunkProvider().chunkGenerator, world, world.rand, cx, cz, false));
        } catch (Throwable t) {
            WorldFlow.LOGGER.debug("[WorldFlow] Handled exception in PopulateChunkEvent.Post", t);
        }
        MinecraftForge.EVENT_BUS.post(new ChunkEvent.Load(chunk));

        // 6. Update PlayerChunkMapEntry and send packet to waiting players
        PlayerChunkMapEntry entry = world.getPlayerChunkMap().getEntry(cx, cz);
        if (entry != null) {
            if (entry instanceof IPlayerChunkMapEntry) {
                ((IPlayerChunkMapEntry) entry).worldflow$setChunk(chunk);
            }
            if (entry.getChunk() != null) {
                entry.sendToPlayers();
            }
        } else if (holder.isPlayerRequested() && !holder.isSkipGhostCleanup()) {
            // Player disconnected or teleported away while chunk was generating:
            // Queue for unload only if it was originally player-requested and does not have a persistent Forge ticket
            boolean hasForgeTicket = net.minecraftforge.common.ForgeChunkManager.getPersistentChunksFor(world).containsKey(holder.getPos());
            if (!hasForgeTicket) {
                world.getChunkProvider().queueUnload(chunk);
            }
        }

        // 7. Complete status and future
        if (chunk instanceof IWorldFlowChunk) {
            ((IWorldFlowChunk) chunk).worldflow$setGenPath(IWorldFlowChunk.GenPath.PIPELINE);
            ((IWorldFlowChunk) chunk).worldflow$setPublished(true);
        }
        GenerationScheduler.getInstance().totalChunksPipeline.incrementAndGet();
        holder.setCurrentStatus(ChunkStatus.FULL);
        holder.getCarversFuture().complete(chunk);
        holder.getWorkerDoneFuture().complete(chunk);
        holder.getFuture().complete(chunk);
        this.totalChunksPublished.incrementAndGet();
        GenerationScheduler.getInstance().invalidateCarverCache(key);
        GenerationScheduler.getInstance().checkLogMetrics();

        // 8. Cleanup from in-flight tracker
        GenerationScheduler.getInstance().removeInFlight(key);
        GenerationScheduler.getInstance().notifyNeighboursOfCompletion(cx, cz);
    }

    public int getPendingCount() {
        return this.pendingPublishQueue.size();
    }
}
