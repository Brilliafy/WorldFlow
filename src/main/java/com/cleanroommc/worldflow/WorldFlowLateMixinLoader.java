package com.cleanroommc.worldflow;

import net.minecraftforge.fml.common.Loader;
import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.ArrayList;
import java.util.List;

public class WorldFlowLateMixinLoader implements ILateMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        List<String> configs = new ArrayList<>();
        configs.add("mixins.worldflow.json");
        if (Loader.isModLoaded("openterraingenerator")) {
            configs.add("mixins.worldflow.otg.json");
        }
        if (Loader.isModLoaded("dynamictrees")) {
            configs.add("mixins.worldflow.dynamictrees.json");
        }
        if (Loader.isModLoaded("nuclearcraft")) {
            configs.add("mixins.worldflow.nc.json");
        }
        if (Loader.isModLoaded("rltweaker") && Loader.isModLoaded("reccomplex")) {
            configs.add("mixins.worldflow.rltweaker.json");
        }
        if (Loader.isModLoaded("reccomplex")) {
            configs.add("mixins.worldflow.reccomplex.json");
        }
        return configs;
    }
}
