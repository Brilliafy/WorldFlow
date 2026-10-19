package com.cleanroommc.worldflow.mixin.otg;

import com.cleanroommc.worldflow.pipeline.otg.WorldFlowLockable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Every biome Resource instance gets its own lock. Resources with lazy caches
 * ({@code TreeGen.treesLoaded}, {@code CustomObjectGen.objects}) and any other mutable
 * state are then safe per instance, while different resources (and different biomes)
 * still run in parallel.
 */
@Mixin(targets = "com.pg85.otg.generator.resource.Resource", remap = false)
public abstract class ResourceLockMixin implements WorldFlowLockable {

    @Unique
    private final ReentrantLock worldflow$lock = new ReentrantLock();

    @Override
    public ReentrantLock worldflow$lock() {
        return this.worldflow$lock;
    }
}
