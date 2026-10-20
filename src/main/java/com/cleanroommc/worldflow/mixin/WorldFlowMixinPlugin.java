package com.cleanroommc.worldflow.mixin;

import net.minecraftforge.fml.common.Loader;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Optional integrations are skipped when their mod is absent. The mixin classes for
 * RC/BOP/Roguelike/NC/OTG reference those mods' types, so they must not even be loaded
 * on a bare Forge/Cleanroom server. Core vanilla mixins always apply.
 */
public class WorldFlowMixinPlugin implements IMixinConfigPlugin {

    private static boolean modLoaded(String modId) {
        try {
            return Loader.isModLoaded(modId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        switch (simple) {
            case "RCSavedDataMixin":
                return modLoaded("reccomplex");
            case "BOPGrassStateMixin":
            case "BOPPlantStateMixin":
                return modLoaded("biomesoplenty");
            case "WorldEditor1_12LootMixin":
                return modLoaded("roguelike");
            case "RadiationHelperMixin":
                return modLoaded("nuclearcraft");
            default:
                if (mixinClassName.startsWith("com.cleanroommc.worldflow.mixin.otg.")) {
                    return modLoaded("openterraingenerator");
                }
                return true;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }
}
