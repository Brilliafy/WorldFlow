package com.cleanroommc.worldflow.pipeline.structure;

import com.cleanroommc.worldflow.WorldFlow;
import net.minecraft.world.gen.structure.MapGenStructureData;
import net.minecraft.world.gen.structure.StructureStart;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Worker threads only enqueue a StructureStart reference. The NBT tree is built
 * and written to MapGenStructureData on the Stage 4 tick thread, so no worker can
 * touch the shared structure data map.
 */
public final class StructureNbtStager {

    private static final Queue<Entry> QUEUE = new ConcurrentLinkedQueue<>();

    private static final class Entry {
        final MapGenStructureData data;
        final StructureStart start;
        final int chunkX;
        final int chunkZ;

        Entry(MapGenStructureData data, StructureStart start, int chunkX, int chunkZ) {
            this.data = data;
            this.start = start;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    private StructureNbtStager() {
    }

    public static void stage(MapGenStructureData data, StructureStart start, int chunkX, int chunkZ) {
        if (data != null && start != null) {
            QUEUE.add(new Entry(data, start, chunkX, chunkZ));
        }
    }

    /** Stage 4 drain. Runs only on the server thread. */
    public static int drain(int budget) {
        int done = 0;
        Entry entry;
        while (done < budget && (entry = QUEUE.poll()) != null) {
            try {
                entry.data.writeInstance(
                        entry.start.writeStructureComponentsToNBT(entry.chunkX, entry.chunkZ),
                        entry.chunkX, entry.chunkZ);
            } catch (Throwable t) {
                WorldFlow.LOGGER.warn("[WorldFlow] Deferred structure NBT write failed at [{}, {}]",
                        entry.chunkX, entry.chunkZ, t);
            }
            done++;
        }
        return done;
    }

    public static int pending() {
        return QUEUE.size();
    }
}
