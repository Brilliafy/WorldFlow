package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.common.LocalWorld;
import com.pg85.otg.generator.biome.BiomeGenerator;
import com.pg85.otg.forge.world.ForgeWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Concurrency-safe OTG biome provider. The raw layered generator owns shared
 * scratch arrays and must not be called from multiple workers. Build one clone
 * per calling thread from the same implementation class and LocalWorld.
 */
@Mixin(targets = "com.pg85.otg.forge.world.ForgeWorld", remap = false)
public abstract class ForgeWorldBiomeGeneratorMixin {

    @Shadow
    private BiomeGenerator biomeGenerator;

    @Unique
    private final ThreadLocal<BiomeGenerator> worldflow$threadBiome = new ThreadLocal<>();

    /** One raw clone per worker, shared by all ForgeWorld mirrors on that worker. */
    @Unique
    private static final ThreadLocal<Map<BiomeGenerator, BiomeGenerator>> WORLDFLOW_CLONES =
            ThreadLocal.withInitial(IdentityHashMap::new);

    @Inject(method = "getBiomeGenerator", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$threadLocalBiomeGenerator(CallbackInfoReturnable<BiomeGenerator> cir) {
        BiomeGenerator local = this.worldflow$threadBiome.get();
        if (local == null) {
            local = worldflow$clone(this.biomeGenerator);
            if (local != null) {
                this.worldflow$threadBiome.set(local);
            }
        }
        if (local != null) {
            cir.setReturnValue(local);
        }
    }

    @Unique
    private static BiomeGenerator worldflow$clone(BiomeGenerator shared) {
        if (shared == null) {
            return null;
        }
        try {
            BiomeGenerator raw = shared;
            if (shared.getClass().getName().endsWith("CachedBiomeGenerator")) {
                Field rawField = shared.getClass().getDeclaredField("generator");
                rawField.setAccessible(true);
                raw = (BiomeGenerator) rawField.get(shared);
            }
            Field worldField = null;
            for (Class<?> c = raw.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    worldField = c.getDeclaredField("world");
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (worldField == null) {
                return null;
            }
            worldField.setAccessible(true);
            LocalWorld world = (LocalWorld) worldField.get(raw);
            // FromImageBiomeGenerator reads and parses the whole biome image in its
            // constructor. Rebuilding it on every getBiomeGenerator call (or for every
            // ForgeWorld mirror) made 32 workers spend seconds in LayerFactory. Cache the
            // raw clone once per worker; the per-chunk array cache lives in OTGParallelContext.
            return WORLDFLOW_CLONES.get().computeIfAbsent(raw, key -> {
                try {
                    Constructor<?> constructor = key.getClass().getDeclaredConstructor(LocalWorld.class);
                    constructor.setAccessible(true);
                    return (BiomeGenerator) constructor.newInstance(world);
                } catch (Throwable ignored) {
                    return null;
                }
            });
        } catch (Throwable ignored) {
            return null;
        }
    }
}
