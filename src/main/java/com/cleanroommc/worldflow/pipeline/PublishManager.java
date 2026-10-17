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

        return publishedCount;
    }

    public void publishChunk(WorldServer world, StatusHolder holder) {
        ProtoChunk proto = holder.getProtoChunk();
        Chunk chunk = proto.getChunk();
        int cx = holder.getPos().x;
        int cz = holder.getPos().z;
        long key = holder.getChunkKey();

        // 1. Insert into live loadedChunks map
        world.getChunkProvider().loadedChunks.put(key, chunk);
        chunk.onLoad();

        // 2. Flush any staged blocks that were waiting for this chunk
        CascadeBlockBuffer.INSTANCE.flushChunk(world, chunk);

        // 3. Tick-thread lighting calculation
        try {
            chunk.checkLight();
        } catch (Throwable t) {
            WorldFlow.LOGGER.debug("[WorldFlow] Light check exception on chunk [{}, {}]", cx, cz, t);
        }

        // 4. Fire Forge ChunkEvent.Load on the server tick thread
        MinecraftForge.EVENT_BUS.post(new ChunkEvent.Load(chunk));

        // 5. Update PlayerChunkMapEntry and send packet to waiting players
        PlayerChunkMapEntry entry = world.getPlayerChunkMap().getEntry(cx, cz);
        if (entry != null) {
            if (entry instanceof IPlayerChunkMapEntry) {
                ((IPlayerChunkMapEntry) entry).worldflow$setChunk(chunk);
            }
            entry.sendToPlayers();
        }

        // 6. Complete status and future
        holder.setCurrentStatus(ChunkStatus.FULL);
        holder.getFuture().complete(chunk);
        this.totalChunksPublished.incrementAndGet();

        // 7. Cleanup from in-flight tracker
        GenerationScheduler.getInstance().removeInFlight(key);
    }

    public int getPendingCount() {
        return this.pendingPublishQueue.size();
    }
}
