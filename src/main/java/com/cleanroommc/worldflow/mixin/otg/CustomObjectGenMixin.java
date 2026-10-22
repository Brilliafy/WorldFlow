package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.OTG;
import com.pg85.otg.customobjects.CustomObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * OTG's {@code CustomObjectGen#getObjects} lazily fills a plain {@link java.util.ArrayList}
 * on first use. Biome resource instances are shared by every worker, so two chunks can
 * iterate the list while another thread is still adding to it, throwing
 * {@code ConcurrentModificationException} and dropping the chunk's decoration.
 *
 * <p>Populating under the resource instance's monitor makes the one-time fill atomic;
 * after that the list is immutable in practice and reads are cheap.</p>
 */
@Mixin(targets = "com.pg85.otg.generator.resource.CustomObjectGen", remap = false)
public abstract class CustomObjectGenMixin {

    @Shadow
    private List<CustomObject> objects;

    @Unique
    private final ThreadLocal<List<CustomObject>> worldflow$localObjects = new ThreadLocal<>();

    @Shadow
    private List<String> objectNames;

    @Inject(method = "getObjects", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldflow$threadLocalObjects(String worldName, CallbackInfoReturnable<List<CustomObject>> cir) {
        // Resource instances are shared by all biome workers. Build the resolved object
        // list once per worker instead of synchronizing the shared ArrayList.
        List<CustomObject> local = this.worldflow$localObjects.get();
        if (local == null) {
            local = new ArrayList<>(this.objectNames.size());
            for (int i = 0; i < this.objectNames.size(); i++) {
                local.add(OTG.getCustomObjectManager().getGlobalObjects()
                        .getObjectByName(this.objectNames.get(i), worldName));
            }
            this.worldflow$localObjects.set(local);
        }
        cir.setReturnValue(local);
    }
}
