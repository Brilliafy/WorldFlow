package com.cleanroommc.worldflow.mixin.compat;

import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import ivorius.reccomplex.world.gen.feature.WorldStructureGenerationData;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recurrent Complex saves this data on the server thread while structure-gen workers
 * mutate it. Its HashMaps/HashMultimaps throw ConcurrentModificationException during
 * MapStorage.saveAll. Concurrent backing collections make iteration weakly consistent;
 * a save may miss an entry added in the same tick, and the next save picks it up.
 */
@Mixin(value = WorldStructureGenerationData.class, remap = false)
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class RCSavedDataMixin {

    @Shadow @Final @Mutable protected Set<ChunkPos> checkedChunks;
    @Shadow @Final @Mutable protected Set<ChunkPos> checkedChunksFinal;
    @Shadow @Final @Mutable protected Map<UUID, ?> entryMap;
    @Shadow @Final @Mutable protected SetMultimap<ChunkPos, ?> chunkMap;
    @Shadow @Final @Mutable protected SetMultimap<String, ?> instanceMap;

    @Inject(method = "<init>(Ljava/lang/String;)V", at = @At("RETURN"), remap = false)
    private void worldflow$concurrentCollections(CallbackInfo ci) {
        this.checkedChunks = ConcurrentHashMap.newKeySet();
        this.checkedChunksFinal = ConcurrentHashMap.newKeySet();
        this.entryMap = new ConcurrentHashMap<>();
        this.chunkMap = Multimaps.newSetMultimap(new ConcurrentHashMap<>(), ConcurrentHashMap::newKeySet);
        this.instanceMap = Multimaps.newSetMultimap(new ConcurrentHashMap<>(), ConcurrentHashMap::newKeySet);
    }
}
