package com.cleanroommc.worldflow.mixin.charm;

import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Charm tracks villages in static {@code HashMap}/{@code ArrayList} fields that are
 * written from {@code PopulateChunkEvent.Populate} and read from
 * {@code DecorateBiomeEvent.Decorate}. With parallel feature workers that is an
 * unsynchronized read/write pair: the decoration pass throws
 * {@link java.util.ConcurrentModificationException} and aborts structure generation.
 *
 * <p>Swap the containers for weakly-consistent ones once, so the mod's own logic
 * stays unchanged and no lock is needed on the hot path.</p>
 */
@Mixin(targets = "svenhjol.charm.world.feature.VillageDecorations", remap = false)
public class CharmVillageDecorationsMixin {

    @Shadow
    public static Map<Integer, Map<ChunkPos, Long>> villageChunks;
    @Shadow
    public static java.util.List<ChunkPos> villageInfested;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void worldflow$concurrentContainers(CallbackInfo ci) {
        villageChunks = new ConcurrentHashMap<>(villageChunks);
        villageInfested = Collections.synchronizedList(new ArrayList<>(villageInfested));
    }

    /** Ensure the per-dimension map exists and is concurrent before Charm fills it. */
    @Inject(method = "onPopulate", at = @At("HEAD"))
    private static void worldflow$concurrentDimensionMap(PopulateChunkEvent.Populate event, CallbackInfo ci) {
        if (event == null || event.getWorld() == null || event.getWorld().provider == null) {
            return;
        }
        int dim = event.getWorld().provider.getDimension();
        Map<ChunkPos, Long> map = villageChunks.get(dim);
        if (map == null) {
            villageChunks.putIfAbsent(dim, new ConcurrentHashMap<>());
        } else if (!(map instanceof ConcurrentHashMap)) {
            villageChunks.put(dim, new ConcurrentHashMap<>(map));
        }
    }
}
