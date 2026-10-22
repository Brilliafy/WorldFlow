package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.pipeline.util.ConcurrentLong2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.gen.structure.MapGenStructure;
import net.minecraft.world.gen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Recurrent Complex wraps MapGenStructure and iterates its structure map from feature
 * workers. Replace the vanilla fastutil map with a concurrent map so reads, writes and
 * snapshots cannot corrupt each other. No process-wide lock is used.
 */
@Mixin(MapGenStructure.class)
public abstract class MapGenStructureConcurrencyMixin {

    @Shadow
    protected Long2ObjectMap<StructureStart> structureMap;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void worldflow$replaceStructureMap(CallbackInfo ci) {
        this.structureMap = new ConcurrentLong2ObjectMap<>();
    }
}
