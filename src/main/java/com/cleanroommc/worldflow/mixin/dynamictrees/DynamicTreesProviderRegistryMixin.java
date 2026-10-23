package com.cleanroommc.worldflow.mixin.dynamictrees;

import com.ferreusveritas.dynamictrees.api.worldgen.IPoissonDiscProvider;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

/**
 * {@code getProvider} uses {@code HashMap.computeIfAbsent}, so two feature workers can
 * construct {@link com.ferreusveritas.dynamictrees.event.PoissonDiscProviderCreateEvent}
 * concurrently. That event constructor appends to a shared Forge {@code ListenerList}
 * child list, which then throws {@code ArrayIndexOutOfBoundsException} and aborts
 * DynamicTrees for the chunk.
 *
 * <p>Provider creation happens once per dimension; serializing it removes the race
 * with no hot-path cost.</p>
 */
@Mixin(targets = "com.ferreusveritas.dynamictrees.systems.poissondisc.PoissonDiscProviderUniversal", remap = false)
public class DynamicTreesProviderRegistryMixin {

    @Shadow
    Map<Integer, IPoissonDiscProvider> providerMap;

    @Shadow
    protected IPoissonDiscProvider createCircleProvider(World world) {
        throw new AbstractMethodError();
    }

    /**
     * @author WorldFlow
     * @reason provider map is a plain HashMap and provider construction is not thread-safe
     */
    @Overwrite(remap = false)
    public IPoissonDiscProvider getProvider(World world) {
        synchronized (this.providerMap) {
            return this.providerMap.computeIfAbsent(world.provider.getDimension(),
                    d -> this.createCircleProvider(world));
        }
    }
}
