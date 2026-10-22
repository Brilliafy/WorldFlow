package com.cleanroommc.worldflow.mixin.otg;

import com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView;
import com.cleanroommc.worldflow.pipeline.isolation.WorkerGeneratorContext;
import com.cleanroommc.worldflow.pipeline.otg.OTGParallelContext;
import com.pg85.otg.common.LocalBiome;
import com.pg85.otg.forge.biomes.ForgeBiome;
import com.pg85.otg.forge.generator.ForgeChunkBuffer;
import com.pg85.otg.forge.world.ForgeWorld;
import com.pg85.otg.generator.ChunkBuffer;
import com.pg85.otg.generator.biome.BiomeGenerator;
import com.pg85.otg.network.ConfigProvider;
import com.pg85.otg.util.ChunkCoordinate;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.MapGenBase;
import net.minecraft.world.gen.MapGenCaves;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;

@Mixin(value = ForgeWorld.class, remap = false)
public abstract class ForgeWorldMixin {

    @Shadow
    private MapGenBase cavesGen;

    @Shadow
    private World world;

    @Shadow
    private BiomeGenerator biomeGenerator;

    @Shadow
    public HashMap<String, LocalBiome> biomeNames;

    @Shadow
    public abstract LocalBiome getBiome(int x, int z);

    @Shadow
    public abstract LocalBiome getFirstBiomeOrNull();

    @Shadow
    public abstract LocalBiome getBiomeByNameOrNull(String name);

    @Shadow
    public abstract ConfigProvider getConfigs();

    @Shadow
    public abstract ForgeBiome getBiomeByOTGIdOrNull(int id);


    private LocalBiome worldflow$getFallbackBiome(ForgeWorld self) {
        if (self == null) return null;
        try {
            ConfigProvider cfg = this.getConfigs();
            if (cfg != null && cfg.getWorldConfig() != null) {
                String defName = cfg.getWorldConfig().imageFillBiome;
                if (defName != null && !defName.trim().isEmpty()) {
                    LocalBiome b = self.getBiomeByNameOrNull(defName);
                    if (b != null) return b;
                }
                defName = cfg.getWorldConfig().defaultOceanBiome;
                if (defName != null && !defName.trim().isEmpty()) {
                    LocalBiome b = self.getBiomeByNameOrNull(defName);
                    if (b != null) return b;
                }
                if (cfg.getWorldConfig().worldBiomes != null && !cfg.getWorldConfig().worldBiomes.isEmpty()) {
                    for (String name : cfg.getWorldConfig().worldBiomes) {
                        LocalBiome b = self.getBiomeByNameOrNull(name);
                        if (b != null) return b;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            LocalBiome first = self.getFirstBiomeOrNull();
            if (first != null) return first;
        } catch (Throwable ignored) {
        }
        try {
            if (this.biomeNames != null && !this.biomeNames.isEmpty()) {
                for (LocalBiome b : this.biomeNames.values()) {
                    if (b != null) return b;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static final ThreadLocal<java.util.HashMap<Long, LocalBiome>> WORLD_FLOW_BIOME_CACHE =
            ThreadLocal.withInitial(java.util.HashMap::new);
    private static final int WORLD_FLOW_BIOME_CACHE_MAX = 16384;

    @Inject(method = "getCalculatedBiome", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$onGetCalculatedBiome(int x, int z, CallbackInfoReturnable<LocalBiome> cir) {
        ForgeWorld self = (ForgeWorld) (Object) this;
        short[] chunkBiomes = OTGParallelContext.getChunkOTGBiomes(self, x >> 4, z >> 4);
        if (chunkBiomes != null) {
            int otgId = chunkBiomes[(z & 15) * 16 + (x & 15)] & 0xFFFF;
            LocalBiome b = self.getBiomeByOTGIdOrNull(otgId);
            if (b != null) {
                cir.setReturnValue(b);
                return;
            }
        }

        LocalBiome fallback = worldflow$getFallbackBiome(self);
        if (fallback != null) {
            cir.setReturnValue(fallback);
        }
    }

    @Inject(method = "cacheBiomesForPopulation", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$onCacheBiomesForPopulation(ChunkCoordinate chunkCoord, CallbackInfo ci) {
        if (chunkCoord == null) {
            ci.cancel();
            return;
        }
        ForgeWorld self = (ForgeWorld) (Object) this;
        com.cleanroommc.worldflow.pipeline.isolation.PopulationBiomeCache cache =
                com.cleanroommc.worldflow.pipeline.isolation.PopulationBiomeCache.get();
        cache.chunkCoord = chunkCoord;
        int cx = chunkCoord.getChunkX();
        int cz = chunkCoord.getChunkZ();

        short[] arr0 = OTGParallelContext.getChunkOTGBiomes(self, cx, cz);
        short[] arr1 = OTGParallelContext.getChunkOTGBiomes(self, cx + 1, cz);
        short[] arr2 = OTGParallelContext.getChunkOTGBiomes(self, cx, cz + 1);
        short[] arr3 = OTGParallelContext.getChunkOTGBiomes(self, cx + 1, cz + 1);

        LocalBiome fallback = null;
        for (int subZ = 0; subZ < 16; subZ++) {
            for (int subX = 0; subX < 16; subX++) {
                int idx = subZ * 16 + subX;
                LocalBiome b0 = arr0 != null ? self.getBiomeByOTGIdOrNull(arr0[idx] & 0xFFFF) : null;
                LocalBiome b1 = arr1 != null ? self.getBiomeByOTGIdOrNull(arr1[idx] & 0xFFFF) : null;
                LocalBiome b2 = arr2 != null ? self.getBiomeByOTGIdOrNull(arr2[idx] & 0xFFFF) : null;
                LocalBiome b3 = arr3 != null ? self.getBiomeByOTGIdOrNull(arr3[idx] & 0xFFFF) : null;

                if (b0 == null || b1 == null || b2 == null || b3 == null) {
                    if (fallback == null) fallback = worldflow$getFallbackBiome(self);
                    if (b0 == null) b0 = fallback;
                    if (b1 == null) b1 = fallback;
                    if (b2 == null) b2 = fallback;
                    if (b3 == null) b3 = fallback;
                }

                cache.biomes[subX][subZ] = b0;
                cache.biomes[subX + 16][subZ] = b1;
                cache.biomes[subX][subZ + 16] = b2;
                cache.biomes[subX + 16][subZ + 16] = b3;
            }
        }
        cache.valid = true;
        ci.cancel();
    }

    @Inject(method = "invalidatePopulationBiomeCache", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$onInvalidatePopulationBiomeCache(CallbackInfo ci) {
        com.cleanroommc.worldflow.pipeline.isolation.PopulationBiomeCache.get().invalidate();
        ci.cancel();
    }

    @Inject(method = "getBiomeForPopulation", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$onGetBiomeForPopulation(int worldX, int worldZ, ChunkCoordinate chunkBeingPopulated, CallbackInfoReturnable<LocalBiome> cir) {
        ForgeWorld self = (ForgeWorld) (Object) this;
        if (chunkBeingPopulated != null) {
            com.cleanroommc.worldflow.pipeline.isolation.PopulationBiomeCache cache =
                    com.cleanroommc.worldflow.pipeline.isolation.PopulationBiomeCache.get();
            if (cache.valid && cache.chunkCoord != null && cache.chunkCoord.equals(chunkBeingPopulated)) {
                int dx = worldX - chunkBeingPopulated.getBlockX();
                int dz = worldZ - chunkBeingPopulated.getBlockZ();
                if (dx >= 0 && dx < 32 && dz >= 0 && dz < 32) {
                    LocalBiome b = cache.biomes[dx][dz];
                    if (b != null) {
                        cir.setReturnValue(b);
                        return;
                    }
                }
            }
        }
        LocalBiome b = self.getBiome(worldX, worldZ);
        if (b == null) {
            b = worldflow$getFallbackBiome(self);
        }
        cir.setReturnValue(b);
    }

    @Inject(method = "generateModdedCaveGen(IILcom/pg85/otg/generator/ChunkBuffer;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void worldflow$onGenerateModdedCaveGen(int x, int z, ChunkBuffer chunkBuffer, CallbackInfoReturnable<Boolean> cir) {
        if (this.cavesGen == null || this.cavesGen.getClass() == MapGenCaves.class) {
            cir.setReturnValue(false);
            return;
        }

        ChunkPrimer primer = ((ForgeChunkBuffer) chunkBuffer).getChunkPrimer();

        if (ThreadLocalWorldView.isGenerating()) {
            MapGenBase workerCaves = WorkerGeneratorContext.get().getWorkerCavesGen((ForgeWorld) (Object) this, this.cavesGen);
            if (workerCaves != null) {
                workerCaves.generate(this.world, x, z, primer);
                cir.setReturnValue(true);
                return;
            }
        }

        // Main thread or fallback: synchronize on cavesGen to ensure complete thread-safety with inline generation
        synchronized (this.cavesGen) {
            this.cavesGen.generate(this.world, x, z, primer);
        }
        cir.setReturnValue(true);
    }
}
