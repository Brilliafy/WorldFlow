package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.generator.biome.ArraysCache;
import com.pg85.otg.generator.biome.ArraysCacheManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ArraysCacheManager.class, remap = false)
public abstract class ArraysCacheManagerMixin {

    private static final ThreadLocal<ArraysCache> worldflow$localCache = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> worldflow$inUse = new ThreadLocal<>();

    @Inject(method = "getCache", at = @At("HEAD"), cancellable = true, remap = false)
    private static void worldflow$onGetCache(CallbackInfoReturnable<ArraysCache> cir) {
        if (Boolean.TRUE.equals(worldflow$inUse.get())) {
            cir.setReturnValue(new ArraysCache());
            return;
        }
        ArraysCache cache = worldflow$localCache.get();
        if (cache == null) {
            cache = new ArraysCache();
            worldflow$localCache.set(cache);
        }
        worldflow$inUse.set(Boolean.TRUE);
        cir.setReturnValue(cache);
    }

    @Inject(method = "releaseCache", at = @At("HEAD"), remap = false)
    private static void worldflow$onReleaseCache(ArraysCache cache, CallbackInfo ci) {
        if (cache == worldflow$localCache.get()) {
            worldflow$inUse.set(Boolean.FALSE);
        }
    }
}
