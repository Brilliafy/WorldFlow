package com.cleanroommc.worldflow.mixin.otg;

import com.cleanroommc.worldflow.pipeline.otg.WorldFlowTreeState;
import com.pg85.otg.OTG;
import com.pg85.otg.customobjects.CustomObject;
import com.pg85.otg.logging.LogMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TreeGen's only mutable state is the one-time {@code loadTrees} fill. The stock
 * implementation sets {@code treesLoaded = true} before it fills the arrays, so a
 * second worker can observe {@code treeObjects == null} (or half-filled) and throw.
 *
 * <p>We replace it with a double-checked one-time init that builds into local arrays
 * and publishes them through a volatile flag. After that the arrays are read-only and
 * every worker uses them lock-free. {@code worldflow$warmLoad} lets the server thread
 * perform the load before any generation worker starts.</p>
 */
@Mixin(targets = "com.pg85.otg.generator.resource.TreeGen", remap = false)
public abstract class TreeGenStateMixin implements WorldFlowTreeState {

    @Shadow
    private List<String> treeNames;
    @Shadow
    private CustomObject[] treeObjects;
    @Shadow
    private int[] treeObjectMinChances;
    @Shadow
    private int[] treeObjectMaxChances;
    @Shadow
    private boolean treesLoaded;

    @Unique
    private final AtomicBoolean worldflow$ready = new AtomicBoolean(false);

    @Override
    public boolean worldflow$ready() {
        return this.worldflow$ready.get();
    }

    @Override
    public void worldflow$markReady() {
        this.worldflow$ready.set(true);
    }

    @Override
    public void worldflow$warmLoad(String worldName) {
        this.loadTrees(worldName);
    }

    /**
     * @author WorldFlow
     * @reason publish the tree arrays only after they are fully populated
     */
    @Overwrite(remap = false)
    private void loadTrees(String worldName) {
        if (this.worldflow$ready.get()) {
            return;
        }
        synchronized (this) {
            if (this.worldflow$ready.get()) {
                return;
            }

            int count = this.treeNames.size();
            CustomObject[] objects = new CustomObject[count];
            int[] minChances = new int[count];
            int[] maxChances = new int[count];

            for (int treeNumber = 0; treeNumber < count; treeNumber++) {
                String treeName = this.treeNames.get(treeNumber);
                int minHeight = -1;
                int maxHeight = -1;
                minChances[treeNumber] = minHeight;
                maxChances[treeNumber] = maxHeight;

                CustomObject tree;
                if (treeName.contains("(")) {
                    String[] params = treeName.replace(")", "").split("\\(");
                    treeName = params[0];
                    tree = OTG.getCustomObjectManager().getGlobalObjects().getObjectByName(treeName, worldName);
                    objects[treeNumber] = tree;
                    if (tree == null) {
                        if (OTG.getPluginConfig().spawnLog) {
                            OTG.log(LogMarker.WARN, "Error: Could not find BO3 for Tree, BO3: " + this.treeNames.get(treeNumber));
                        }
                        continue;
                    }
                    params = params[1].split(";");
                    String sMinHeight = params[0].toLowerCase().replace("minheight=", "");
                    String sMaxHeight = params[1].toLowerCase().replace("maxheight=", "");
                    try {
                        minChances[treeNumber] = Integer.parseInt(sMinHeight);
                        maxChances[treeNumber] = Integer.parseInt(sMaxHeight);
                    } catch (NumberFormatException ignored) {
                    }
                } else {
                    tree = OTG.getCustomObjectManager().getGlobalObjects().getObjectByName(treeName, worldName);
                    objects[treeNumber] = tree;
                    if (tree == null && OTG.getPluginConfig().spawnLog) {
                        OTG.log(LogMarker.WARN, "Error: Could not find BO3 for Tree, BO3: " + this.treeNames.get(treeNumber));
                    }
                }
            }

            // Publish everything before flipping the volatile readiness flag.
            this.treeObjects = objects;
            this.treeObjectMinChances = minChances;
            this.treeObjectMaxChances = maxChances;
            this.treesLoaded = true;
            this.worldflow$ready.set(true);
        }
    }
}
