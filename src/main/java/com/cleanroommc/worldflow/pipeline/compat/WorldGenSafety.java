package com.cleanroommc.worldflow.pipeline.compat;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Process-wide mutex for mod code that mutates shared structure maps during world
 * generation and is not thread-safe.
 *
 * <p>Recurrent Complex's {@code MapGenStructureHook} iterates a plain
 * {@code Long2ObjectOpenHashMap} of structure starts while OTG's default structure
 * pass can insert into it from another base-gen worker. That races fastutil's
 * iterator ({@code wrapped == null}) and used to throw tens of thousands of times
 * per session, silently falling back to undecorated chunks.</p>
 *
 * <p>The same lock is taken around serialized feature-stage generators, because
 * structure placement can mutate the same maps while base generation reads them.</p>
 */
public final class WorldGenSafety {

    public static final ReentrantLock UNSAFE_WORLDGEN_LOCK = new ReentrantLock();

    private WorldGenSafety() {
    }
}
