package com.cleanroommc.worldflow.mixin;

import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.locks.ReentrantLock;

@Mixin(MapStorage.class)
public abstract class MapStorageMixin {

    private final ReentrantLock worldflow$mapStorageLock = new ReentrantLock();

    @Inject(method = "getOrLoadData", at = @At("HEAD"))
    private void worldflow$lockGetOrLoad(Class<? extends WorldSavedData> clazz, String dataIdentifier, CallbackInfoReturnable<WorldSavedData> cir) {
        this.worldflow$mapStorageLock.lock();
    }

    @Inject(method = "getOrLoadData", at = @At("RETURN"))
    private void worldflow$unlockGetOrLoad(Class<? extends WorldSavedData> clazz, String dataIdentifier, CallbackInfoReturnable<WorldSavedData> cir) {
        this.worldflow$mapStorageLock.unlock();
    }

    @Inject(method = "setData", at = @At("HEAD"))
    private void worldflow$lockSetData(String dataIdentifier, WorldSavedData data, CallbackInfo ci) {
        this.worldflow$mapStorageLock.lock();
    }

    @Inject(method = "setData", at = @At("RETURN"))
    private void worldflow$unlockSetData(String dataIdentifier, WorldSavedData data, CallbackInfo ci) {
        this.worldflow$mapStorageLock.unlock();
    }

    @Inject(method = "getUniqueDataId", at = @At("HEAD"))
    private void worldflow$lockUniqueId(String key, CallbackInfoReturnable<Integer> cir) {
        this.worldflow$mapStorageLock.lock();
    }

    @Inject(method = "getUniqueDataId", at = @At("RETURN"))
    private void worldflow$unlockUniqueId(String key, CallbackInfoReturnable<Integer> cir) {
        this.worldflow$mapStorageLock.unlock();
    }
}
