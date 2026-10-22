package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.common.LocalBiome;
import com.pg85.otg.forge.world.ForgeWorld;
import com.pg85.otg.util.ChunkCoordinate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * OTG keeps a single 32x32 biome population cache on the shared {@code ForgeWorld}.
 * Every worker overwrites it at the start of {@code ObjectSpawner.populate}, so parallel
 * population would decorate chunks with another chunk's biomes.
 *
 * <p>Moving the cache to the calling thread makes the population stage independent of
 * the shared world state; the per-worker {@code OTGChunkGenerator}/{@code ObjectSpawner}
 * already owns its RNG and cascade flags.</p>
 */
@Mixin(targets = "com.pg85.otg.forge.world.ForgeWorld", remap = false)
public abstract class ForgeWorldBiomeCacheMixin {

    @Unique
    private static final ThreadLocal<LocalBiome[][]> WORLD_FLOW_BIOMES = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<Boolean> WORLD_FLOW_VALID = ThreadLocal.withInitial(() -> Boolean.FALSE);
    @Unique
    private static final java.util.concurrent.ConcurrentHashMap<Long, LocalBiome> WORLD_FLOW_POINT_BIOMES = new java.util.concurrent.ConcurrentHashMap<>();

    @Shadow
    public abstract LocalBiome getBiome(int x, int z);

    @Shadow
    public abstract com.pg85.otg.generator.biome.BiomeGenerator getBiomeGenerator();

    @Shadow
    public abstract LocalBiome getBiomeByOTGIdOrNull(int id);

    @Inject(method = "getCalculatedBiome", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$cachedCalculatedBiome(int x, int z, CallbackInfoReturnable<LocalBiome> cir) {
        long key = (((long) x) << 32) | (((long) z) & 0xFFFFFFFFL);
        LocalBiome cached = WORLD_FLOW_POINT_BIOMES.get(key);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getCalculatedBiome", at = @At("RETURN"), remap = false)
    private void worldflow$recordCalculatedBiome(int x, int z, CallbackInfoReturnable<LocalBiome> cir) {
        LocalBiome b = cir.getReturnValue();
        if (b != null) {
            long key = (((long) x) << 32) | (((long) z) & 0xFFFFFFFFL);
            WORLD_FLOW_POINT_BIOMES.putIfAbsent(key, b);
        }
    }

    @Inject(method = "cacheBiomesForPopulation", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$threadLocalCache(ChunkCoordinate chunkCoord, CallbackInfo ci) {
        ci.cancel();
        LocalBiome[][] biomes = new LocalBiome[32][32];
        int baseX = chunkCoord.getBlockX();
        int baseZ = chunkCoord.getBlockZ();
        com.pg85.otg.generator.biome.BiomeGenerator gen = this.getBiomeGenerator();
        if (gen != null) {
            try {
                int[] biomeIds = gen.getBiomes(null, baseX, baseZ, 32, 32, com.pg85.otg.generator.biome.OutputType.FULL);
                if (biomeIds != null && biomeIds.length >= 1024) {
                    for (int x = 0; x < 32; x++) {
                        for (int z = 0; z < 32; z++) {
                            biomes[x][z] = this.getBiomeByOTGIdOrNull(biomeIds[z * 32 + x]);
                        }
                    }
                    WORLD_FLOW_BIOMES.set(biomes);
                    WORLD_FLOW_VALID.set(Boolean.TRUE);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                biomes[x][z] = this.getBiome(baseX + x, baseZ + z);
            }
        }
        WORLD_FLOW_BIOMES.set(biomes);
        WORLD_FLOW_VALID.set(Boolean.TRUE);
    }

    @Inject(method = "getBiomeForPopulation", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$threadLocalGet(int worldX, int worldZ, ChunkCoordinate chunkBeingPopulated,
                                          CallbackInfoReturnable<LocalBiome> cir) {
        LocalBiome[][] biomes = WORLD_FLOW_BIOMES.get();
        if (Boolean.TRUE.equals(WORLD_FLOW_VALID.get()) && biomes != null) {
            int dx = worldX - chunkBeingPopulated.getBlockX();
            int dz = worldZ - chunkBeingPopulated.getBlockZ();
            if (dx >= 0 && dx < 32 && dz >= 0 && dz < 32) {
                cir.setReturnValue(biomes[dx][dz]);
                return;
            }
        }
        // No cache for this thread/chunk: resolve directly instead of touching the
        // shared fields that another worker may be rebuilding.
        cir.setReturnValue(this.getBiome(worldX, worldZ));
    }

    @Inject(method = "invalidatePopulationBiomeCache", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$threadLocalInvalidate(CallbackInfo ci) {
        WORLD_FLOW_BIOMES.remove();
        WORLD_FLOW_VALID.set(Boolean.FALSE);
        ci.cancel();
    }
}
