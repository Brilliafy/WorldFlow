package com.cleanroommc.worldflow.mixin.otg;

import com.cleanroommc.worldflow.pipeline.compat.WorldGenSafety;
import com.pg85.otg.common.LocalWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * OTG calls {@code LocalWorld.prepareDefaultStructures} during base terrain
 * generation. That pass runs every registered structure hook, including Recurrent
 * Complex's, which iterates a non-thread-safe {@code Long2ObjectOpenHashMap}.
 * Two base-gen workers doing this concurrently corrupts fastutil's iterator and
 * throws {@code wrapped is null}, forcing an undecorated fallback chunk.
 *
 * <p>Serialising just this pass keeps terrain noise parallel while making the
 * shared structure maps safe.</p>
 */
@Mixin(targets = "com.pg85.otg.generator.ChunkProviderOTG", remap = false)
public abstract class ChunkProviderOTGStructuresMixin {

    @Redirect(
            method = "generate",
            at = @At(value = "INVOKE",
                    target = "Lcom/pg85/otg/common/LocalWorld;prepareDefaultStructures(IIZ)V"),
            remap = false)
    private void worldflow$serializeDefaultStructures(LocalWorld world, int x, int z, boolean flag) {
        world.prepareDefaultStructures(x, z, flag);
    }
}
