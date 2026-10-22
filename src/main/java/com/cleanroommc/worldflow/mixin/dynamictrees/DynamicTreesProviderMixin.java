package com.cleanroommc.worldflow.mixin.dynamictrees;

import com.ferreusveritas.dynamictrees.api.worldgen.IPoissonDiscProvider;
import com.ferreusveritas.dynamictrees.systems.poissondisc.PoissonDiscProviderUniversal;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.HashMap;
import java.util.Map;

/**
 * DynamicTrees' provider owns a mutable HashMap of chunk discs, scratch disc lists
 * and a RandomXOR. The world-level provider is therefore not safe to share between
 * feature workers. Give every worker its own provider; the per-chunk disc RNG is
 * seeded from the chunk coordinate, so this does not change placement.
 */
@Mixin(targets = "com.ferreusveritas.dynamictrees.systems.poissondisc.PoissonDiscProviderUniversal", remap = false)
public abstract class DynamicTreesProviderMixin {

    @Shadow
    protected abstract IPoissonDiscProvider createCircleProvider(World world);

    @Unique
    private final ThreadLocal<Map<Integer, IPoissonDiscProvider>> worldflow$threadProviders =
            ThreadLocal.withInitial(HashMap::new);

    /**
     * @author WorldFlow
     * @reason Provider state is mutable and must not be shared by feature workers.
     */
    @Overwrite
    public IPoissonDiscProvider getProvider(World world) {
        int dim = world.provider.getDimension();
        return this.worldflow$threadProviders.get()
                .computeIfAbsent(dim, ignored -> this.createCircleProvider(world));
    }
}
