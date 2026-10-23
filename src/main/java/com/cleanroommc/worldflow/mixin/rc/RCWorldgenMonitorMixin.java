package com.cleanroommc.worldflow.mixin.rc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * RecurrentComplex tracks cascading worldgen in a single static {@link ArrayDeque}
 * ({@code RCWorldgenMonitor.actions}). With parallel feature workers two threads
 * push/pop the same deque and {@code pop()} throws {@link java.util.NoSuchElementException},
 * aborting structure placement for the whole chunk.
 *
 * <p>The deque is only used for diagnostics, so give every thread its own stack.</p>
 */
@Mixin(targets = "ivorius.reccomplex.world.gen.feature.RCWorldgenMonitor", remap = false)
public class RCWorldgenMonitorMixin {

    @Unique
    private static final ThreadLocal<Deque<String>> WORLDFLOW_ACTIONS =
            ThreadLocal.withInitial(ArrayDeque::new);

    @Redirect(
            method = {"start", "stop", "lambda$create$0"},
            at = @At(value = "FIELD", opcode = org.objectweb.asm.Opcodes.GETSTATIC,
                    target = "Livorius/reccomplex/world/gen/feature/RCWorldgenMonitor;actions:Ljava/util/Deque;"),
            remap = false)
    private static Deque<String> worldflow$threadLocalActions() {
        return WORLDFLOW_ACTIONS.get();
    }
}
