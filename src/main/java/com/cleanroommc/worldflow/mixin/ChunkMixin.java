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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.cleanroommc.worldflow.pipeline.IWorldFlowChunk;

@Mixin(value = Chunk.class, priority = 500)
public abstract class ChunkMixin implements IWorldFlowChunk {

    @Inject(method = "getLightSubtracted", at = @At("HEAD"), cancellable = true)
    private void worldflow$onGetLightSubtracted(net.minecraft.util.math.BlockPos pos, int amount, CallbackInfoReturnable<Integer> cir) {
        if (com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
            Chunk chunk = (Chunk) (Object) this;
            int light = com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.getChunkSafeLight(chunk, pos);
            cir.setReturnValue(Math.max(0, light - amount));
        }
    }

    @Inject(method = "getLightFor", at = @At("HEAD"), cancellable = true)
    private void worldflow$onGetLightFor(net.minecraft.world.EnumSkyBlock type, net.minecraft.util.math.BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.isGenerating()) {
            Chunk chunk = (Chunk) (Object) this;
            cir.setReturnValue(com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.getChunkSafeLightFor(chunk, type, pos));
        }
    }

    private IWorldFlowChunk.GenPath worldflow$genPath = IWorldFlowChunk.GenPath.UNKNOWN;

    @Override
    public IWorldFlowChunk.GenPath worldflow$getGenPath() {
        return this.worldflow$genPath;
    }

    @Override
    public void worldflow$setGenPath(IWorldFlowChunk.GenPath path) {
        this.worldflow$genPath = path;
    }

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
