package com.cleanroommc.worldflow.mixin;

import com.cleanroommc.worldflow.config.ModConfig;
import com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer;
import com.cleanroommc.worldflow.cascade.staging.GenerationContext;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Chunk.class)
public abstract class ChunkMixin {

    @Shadow
    @Final
    public int x;

    @Shadow
    @Final
    public int z;

    @Shadow
    @Final
    private World world;

    @Inject(method = "populate(Lnet/minecraft/world/gen/IChunkGenerator;)V", at = @At("HEAD"))
    private void cascadeFix$onChunkPopulateHead(IChunkGenerator generator, CallbackInfo ci) {
        if (!com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
            return;
        }
        if (ModConfig.GENERAL.enableCascadeFix && ModConfig.GENERAL.stageForgeDecorators) {
            String genName = generator != null ? generator.getClass().getSimpleName() : "VanillaChunkGen";
            GenerationContext.enterPopulate(this.x, this.z, genName);
        }
    }

    @Inject(method = "populate(Lnet/minecraft/world/gen/IChunkGenerator;)V", at = @At("RETURN"))
    private void cascadeFix$onChunkPopulateReturn(IChunkGenerator generator, CallbackInfo ci) {
        if (!com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
            return;
        }
        if (ModConfig.GENERAL.enableCascadeFix && ModConfig.GENERAL.stageForgeDecorators) {
            GenerationContext.exitPopulate();
            if (this.world instanceof WorldServer) {
                CascadeBlockBuffer.INSTANCE.flushChunk((WorldServer) this.world, (Chunk) (Object) this);
            }
        }
    }
}
