package com.cleanroommc.worldflow.mixin.dynamictrees;

import com.ferreusveritas.dynamictrees.util.RandomXOR;
import com.ferreusveritas.dynamictrees.worldgen.TreeGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.objectweb.asm.Opcodes;

/**
 * TreeGenerator.makeTree re-seeds its RandomXOR from the tree position. Sharing that
 * field lets two workers overwrite each other's stream. Redirect the field read to a
 * per-worker RandomXOR; the generator itself and its biome database stay shared/read-only.
 */
@Mixin(targets = "com.ferreusveritas.dynamictrees.worldgen.TreeGenerator", remap = false)
public abstract class DynamicTreesRandomMixin {

    @Unique
    private static final ThreadLocal<RandomXOR> WORLDFLOW_RANDOM = ThreadLocal.withInitial(RandomXOR::new);

    @Redirect(
            method = "makeTree",
            at = @At(
                    value = "FIELD",
                    target = "Lcom/ferreusveritas/dynamictrees/worldgen/TreeGenerator;random:Lcom/ferreusveritas/dynamictrees/util/RandomXOR;",
                    opcode = Opcodes.GETFIELD
            ),
            remap = false)
    private RandomXOR worldflow$threadRandom(TreeGenerator self) {
        return WORLDFLOW_RANDOM.get();
    }
}
