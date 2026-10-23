package com.cleanroommc.worldflow.compat;

import com.cleanroommc.worldflow.WorldFlow;
import net.minecraftforge.fml.common.Loader;

/**
 * NoiseThreader wraps OTG's {@code CachedBiomeGenerator.getBiomeCacheChunk} with a
 * global ReentrantLock when {@code multithreadBetterCavesNoise} is true. That lock
 * serialises every biome-cache chunk creation and dominates dungeon structure scans
 * (a single scan can request hundreds of chunks). WorldFlow's OTG patch keeps the
 * biome layer state thread-confined, so the lock is unnecessary and harmful.
 *
 * <p>Force the flag off in memory after Forge has loaded the config, instead of
 * asking users to edit {@code config/noisethreader.cfg}. Purely reflective and
 * failure-tolerant: if NoiseThreader is absent or its config shape changes, the
 * game continues with the stock behaviour.</p>
 */
public final class NoiseThreaderGuard {

    private NoiseThreaderGuard() {
    }

    public static void apply() {
        if (!Loader.isModLoaded("noisethreader")) {
            return;
        }
        try {
            Class<?> handler = Class.forName("noisethreader.handlers.ForgeConfigHandler");
            Object server = handler.getField("server").get(null);
            if (server == null) {
                return;
            }
            java.lang.reflect.Field flag = server.getClass().getField("multithreadBetterCavesNoise");
            if (flag.getBoolean(server)) {
                flag.setBoolean(server, false);
                WorldFlow.LOGGER.info("[WorldFlow] Detected NoiseThreader: Automatically neutralized intra-chunk thread pools to prevent thread oversubscription. Retaining BetterCaves scalar optimizations.");
            }
        } catch (Throwable t) {
            WorldFlow.LOGGER.warn("[WorldFlow] Could not disable NoiseThreader's OTG biome lock; if biome generation stalls, set 'Multithread BetterCaves Noise Generation'=false in config/noisethreader.cfg", t);
        }
    }
}
