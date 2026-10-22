package com.cleanroommc.worldflow.mixin.otg;

import com.pg85.otg.customobjects.CustomObject;
import com.pg85.otg.customobjects.CustomObjectCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CustomObjectCollection.getObjectByName takes a global monitor for every lookup.
 * BO4 branch traversal performs the same name lookups on every chunk and convoyed
 * all feature workers. Cache resolved names in a concurrent map; only a first miss
 * enters the original synchronized resolver.
 */
@Mixin(targets = "com.pg85.otg.customobjects.CustomObjectCollection", remap = false)
public abstract class CustomObjectCollectionCacheMixin {

    @Unique
    private static final Object WORLDFLOW_NULL = new Object();

    @Unique
    private static final ConcurrentHashMap<String, Object> WORLDFLOW_CACHE = new ConcurrentHashMap<>();

    @Shadow
    public abstract CustomObject getObjectByName(String name, String worldName, boolean searchGlobalObjects);

    @Overwrite
    public CustomObject getObjectByName(String name, String worldName) {
        String key = (worldName == null ? "" : worldName) + '\u0000' + name.toLowerCase(Locale.ROOT);
        Object cached = WORLDFLOW_CACHE.get(key);
        if (cached != null) {
            return cached == WORLDFLOW_NULL ? null : (CustomObject) cached;
        }
        CustomObject resolved = this.getObjectByName(name, worldName, true);
        WORLDFLOW_CACHE.putIfAbsent(key, resolved == null ? WORLDFLOW_NULL : resolved);
        return resolved;
    }
}
