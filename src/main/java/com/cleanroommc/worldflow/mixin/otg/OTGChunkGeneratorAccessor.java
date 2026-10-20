package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.forge.generator.ForgeChunkBuffer;
import com.pg85.otg.forge.generator.OTGChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = OTGChunkGenerator.class, remap = false)
public interface OTGChunkGeneratorAccessor {
    @Accessor("chunkBuffer")
    ForgeChunkBuffer worldflow$getChunkBuffer();
}
