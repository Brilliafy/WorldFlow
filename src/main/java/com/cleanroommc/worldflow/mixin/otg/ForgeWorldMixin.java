package com.cleanroommc.worldflow.mixin.otg;

import com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView;
import com.cleanroommc.worldflow.pipeline.isolation.WorkerGeneratorContext;
import com.pg85.otg.forge.generator.ForgeChunkBuffer;
import com.pg85.otg.forge.world.ForgeWorld;
import com.pg85.otg.generator.ChunkBuffer;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.MapGenBase;
import net.minecraft.world.gen.MapGenCaves;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ForgeWorld.class, remap = false)
public abstract class ForgeWorldMixin {

    @Shadow
    private MapGenBase cavesGen;

    @Shadow
    private World world;

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
