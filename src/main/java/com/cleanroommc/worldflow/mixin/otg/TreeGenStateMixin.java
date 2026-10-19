package com.cleanroommc.worldflow.mixin.otg;

import com.cleanroommc.worldflow.pipeline.otg.WorldFlowTreeState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.atomic.AtomicBoolean;

/** TreeGen's only mutable state is the one-time {@code loadTrees} fill; after that the
 *  arrays are read-only and spawns only use the caller's Random. */
@Mixin(targets = "com.pg85.otg.generator.resource.TreeGen", remap = false)
public abstract class TreeGenStateMixin implements WorldFlowTreeState {

    @Unique
    private final AtomicBoolean worldflow$ready = new AtomicBoolean(false);

    @Override
    public boolean worldflow$ready() {
        return this.worldflow$ready.get();
    }

    @Override
    public void worldflow$markReady() {
        this.worldflow$ready.set(true);
    }
}
