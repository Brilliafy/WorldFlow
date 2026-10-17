package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.config.ModConfig;
import com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView;
import com.cleanroommc.worldflow.pipeline.isolation.WorkerGeneratorContext;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import net.minecraft.world.gen.MapGenBase;
import net.minecraft.world.gen.structure.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChunkGeneratorOverworld.class)
public abstract class ChunkGeneratorOverworldMixin {

    @Shadow
    private MapGenBase caveGenerator;

    @Shadow
    private MapGenBase ravineGenerator;

    @Shadow
    private MapGenMineshaft mineshaftGenerator;

    @Shadow
    private MapGenVillage villageGenerator;

    @Shadow
    private MapGenStronghold strongholdGenerator;

    @Shadow
    private MapGenScatteredFeature scatteredFeatureGenerator;

    @Shadow
    private StructureOceanMonument oceanMonumentGenerator;

    @Shadow
    private WoodlandMansion woodlandMansionGenerator;

    @Redirect(
            method = "setBlocksInChunk",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/gen/ChunkGeneratorOverworld;caveGenerator:Lnet/minecraft/world/gen/MapGenBase;")
    )
    private MapGenBase worldflow$redirectCaveGen(ChunkGeneratorOverworld instance) {
        if (ModConfig.CONCURRENCY.enableThreadLocalWorldView && ThreadLocalWorldView.isGenerating()) {
            return WorkerGeneratorContext.get().caves;
        }
        return this.caveGenerator;
    }

    @Redirect(
            method = "setBlocksInChunk",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/gen/ChunkGeneratorOverworld;ravineGenerator:Lnet/minecraft/world/gen/MapGenBase;")
    )
    private MapGenBase worldflow$redirectRavineGen(ChunkGeneratorOverworld instance) {
        if (ModConfig.CONCURRENCY.enableThreadLocalWorldView && ThreadLocalWorldView.isGenerating()) {
            return WorkerGeneratorContext.get().ravines;
        }
        return this.ravineGenerator;
    }

    @Redirect(
            method = {"generateChunk", "populate"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/gen/ChunkGeneratorOverworld;mineshaftGenerator:Lnet/minecraft/world/gen/structure/MapGenMineshaft;")
    )
    private MapGenMineshaft worldflow$redirectMineshaft(ChunkGeneratorOverworld instance) {
        if (ModConfig.CONCURRENCY.enableThreadLocalWorldView && ThreadLocalWorldView.isGenerating()) {
            return WorkerGeneratorContext.get().mineshaft;
        }
        return this.mineshaftGenerator;
    }

    @Redirect(
            method = {"generateChunk", "populate"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/gen/ChunkGeneratorOverworld;villageGenerator:Lnet/minecraft/world/gen/structure/MapGenVillage;")
    )
    private MapGenVillage worldflow$redirectVillage(ChunkGeneratorOverworld instance) {
        if (ModConfig.CONCURRENCY.enableThreadLocalWorldView && ThreadLocalWorldView.isGenerating()) {
            return WorkerGeneratorContext.get().village;
        }
        return this.villageGenerator;
    }

    @Redirect(
            method = {"generateChunk", "populate"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/gen/ChunkGeneratorOverworld;strongholdGenerator:Lnet/minecraft/world/gen/structure/MapGenStronghold;")
    )
    private MapGenStronghold worldflow$redirectStronghold(ChunkGeneratorOverworld instance) {
        if (ModConfig.CONCURRENCY.enableThreadLocalWorldView && ThreadLocalWorldView.isGenerating()) {
            return WorkerGeneratorContext.get().stronghold;
        }
        return this.strongholdGenerator;
    }

    @Redirect(
            method = {"generateChunk", "populate"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/gen/ChunkGeneratorOverworld;scatteredFeatureGenerator:Lnet/minecraft/world/gen/structure/MapGenScatteredFeature;")
    )
    private MapGenScatteredFeature worldflow$redirectScattered(ChunkGeneratorOverworld instance) {
        if (ModConfig.CONCURRENCY.enableThreadLocalWorldView && ThreadLocalWorldView.isGenerating()) {
            return WorkerGeneratorContext.get().scatteredFeature;
        }
        return this.scatteredFeatureGenerator;
    }

    @Redirect(
            method = {"generateChunk", "populate"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/gen/ChunkGeneratorOverworld;oceanMonumentGenerator:Lnet/minecraft/world/gen/structure/StructureOceanMonument;")
    )
    private StructureOceanMonument worldflow$redirectOceanMonument(ChunkGeneratorOverworld instance) {
        if (ModConfig.CONCURRENCY.enableThreadLocalWorldView && ThreadLocalWorldView.isGenerating()) {
            return WorkerGeneratorContext.get().oceanMonument;
        }
        return this.oceanMonumentGenerator;
    }

    @Redirect(
            method = {"generateChunk", "populate"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/gen/ChunkGeneratorOverworld;woodlandMansionGenerator:Lnet/minecraft/world/gen/structure/WoodlandMansion;")
    )
    private WoodlandMansion worldflow$redirectWoodlandMansion(ChunkGeneratorOverworld instance) {
        if (ModConfig.CONCURRENCY.enableThreadLocalWorldView && ThreadLocalWorldView.isGenerating()) {
            return WorkerGeneratorContext.get().getWoodlandMansion(instance);
        }
        return this.woodlandMansionGenerator;
    }
}
