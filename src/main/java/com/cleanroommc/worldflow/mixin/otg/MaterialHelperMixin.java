package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.util.FifoMap;
import com.pg85.otg.util.materials.MaterialHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = MaterialHelper.class, remap = false)
public abstract class MaterialHelperMixin {

    @Redirect(method = "readMaterial", at = @At(value = "INVOKE", target = "Lcom/pg85/otg/util/FifoMap;get(Ljava/lang/Object;)Ljava/lang/Object;"), remap = false)
    private static Object worldflow$syncReadMaterialGet(FifoMap map, Object key) {
        synchronized (map) {
            return map.get(key);
        }
    }

    @Redirect(method = "readMaterial", at = @At(value = "INVOKE", target = "Lcom/pg85/otg/util/FifoMap;containsKey(Ljava/lang/Object;)Z"), remap = false)
    private static boolean worldflow$syncReadMaterialContainsKey(FifoMap map, Object key) {
        synchronized (map) {
            return map.containsKey(key);
        }
    }

    @Redirect(method = "readMaterial", at = @At(value = "INVOKE", target = "Lcom/pg85/otg/util/FifoMap;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), remap = false)
    private static Object worldflow$syncReadMaterialPut(FifoMap map, Object key, Object value) {
        synchronized (map) {
            return map.put(key, value);
        }
    }
}
