package com.cleanroommc.worldflow.pregen;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.config.ModConfig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gentle, budgeted background pre-generation.
 *
 * <p>Design goals, in order: never hurt TPS, never fight the players' own chunk loading, and only
 * then make terrain exist before players reach it.</p>
 *
 * <ul>
 *   <li>Runs at the END of the server tick, at most one chunk per {@code intervalTicks}.</li>
 *   <li>Skips entirely while the previous tick exceeded {@code maxTickTimeMs} (automatic back-off).</li>
 *   <li>Only generates in an annulus {@code [innerRadius, radius]} around a player, so it never
 *       competes with the player's own active chunk request area.</li>
 *   <li>Never touches a loaded chunk, and uses {@code AnvilChunkLoader.isChunkGeneratedAt} (an
 *       in-memory flag read) to skip chunks already saved, so it cannot re-generate terrain.</li>
 *   <li>Optionally waits until no player has moved for a while before doing anything.</li>
 *   <li>After scanning a full ring without finding work, sleeps for a long cooldown.</li>
 * </ul>
 *
 * Everything runs on the server thread through the normal generation path, so there is no threading
 * risk and cross-chunk structures/population behave exactly as in vanilla.
 */
public class PregeneratorEngine {

    private static long tickStartNanos;
    private static double lastTickMs;

    private static int ticksSinceGen;
    private static int cooldownTicks;
    private static int spiralIndex;
    private static int worldPass;

    private static int[] spiralDx = new int[0];
    private static int[] spiralDz = new int[0];
    private static int builtRadius = -1;
    private static int builtInner = -1;

    private static int idleTicksCounter;
    private static final Map<UUID, double[]> lastPos = new HashMap<>();

    // stats
    public static volatile long generatedTotal;
    public static volatile long lastGeneratedWorldTime = -1L;
    public static volatile double lastTickMsSnapshot;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            tickStartNanos = System.nanoTime();
            return;
        }

        lastTickMs = (System.nanoTime() - tickStartNanos) / 1_000_000.0;
        lastTickMsSnapshot = lastTickMs;

        try {
            process();
        } catch (Throwable t) {
            WorldFlow.LOGGER.error("[SmartPregenerator] Error during pre-generation tick; backing off.", t);
            cooldownTicks = Math.max(cooldownTicks, 200);
        }
    }

    private static void process() {
        if (!ModConfig.PREGENERATOR.enabled) {
            return;
        }

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || server.worlds == null) {
            return;
        }

        updateIdleTracking(server);

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        // Automatic back-off: if the server is already struggling, do nothing this tick.
        if (lastTickMs > ModConfig.PREGENERATOR.maxTickTimeMs) {
            return;
        }

        if (ModConfig.PREGENERATOR.onlyWhenIdle && idleTicksCounter < ModConfig.PREGENERATOR.idleTicks) {
            return;
        }

        List<WorldServer> worlds = new ArrayList<>();
        for (WorldServer world : server.worlds) {
            if (world == null) {
                continue;
            }
            if (ModConfig.PREGENERATOR.overworldOnly && world.provider.getDimension() != 0) {
                continue;
            }
            worlds.add(world);
        }
        if (worlds.isEmpty()) {
            return;
        }

        ticksSinceGen++;
        if (ticksSinceGen < ModConfig.PREGENERATOR.intervalTicks) {
            return;
        }

        WorldServer world = worlds.get(Math.floorMod(worldPass, worlds.size()));
        List<EntityPlayer> players = world.playerEntities;
        if (players == null || players.isEmpty()) {
            return;
        }

        EntityPlayer anchor = players.get(Math.floorMod(worldPass, players.size()));
        int centerX = MathHelper.floor(anchor.posX) >> 4;
        int centerZ = MathHelper.floor(anchor.posZ) >> 4;

        ensureSpiral();

        int checks = Math.max(1, ModConfig.PREGENERATOR.maxChecksPerTick);
        while (checks-- > 0) {
            if (spiralIndex >= spiralDx.length) {
                // Finished a full ring pass without necessarily finding work; rest and re-centre later.
                spiralIndex = 0;
                worldPass++;
                cooldownTicks = Math.max(0, ModConfig.PREGENERATOR.emptyPassCooldownTicks);
                return;
            }

            int x = centerX + spiralDx[spiralIndex];
            int z = centerZ + spiralDz[spiralIndex];
            spiralIndex++;

            if (tryGenerate(world, x, z)) {
                ticksSinceGen = 0;
                return;
            }
        }
    }

    private static boolean tryGenerate(WorldServer world, int x, int z) {
        ChunkProviderServer provider = world.getChunkProvider();

        // Never touch a chunk that is loaded / in use.
        if (provider.getLoadedChunk(x, z) != null) {
            return false;
        }

        // Never re-generate a chunk that already exists on disk (or is queued to be saved).
        if (provider.chunkLoader instanceof AnvilChunkLoader
                && ((AnvilChunkLoader) provider.chunkLoader).isChunkGeneratedAt(x, z)) {
            return false;
        }

        Chunk chunk = provider.provideChunk(x, z); // normal server-thread generation + population
        if (chunk == null) {
            return false;
        }

        // Let the chunk leave memory again; it is saved by the normal unload path, so it will be a
        // cheap (and now parallel, see FastChunkIO) disk load when a player arrives later.
        provider.queueUnload(chunk);

        generatedTotal++;
        lastGeneratedWorldTime = world.getTotalWorldTime();

        if (ModConfig.PREGENERATOR.debugLogging) {
            WorldFlow.LOGGER.info("[SmartPregenerator] Pre-generated chunk [{}, {}] in dimension {} (tick took {} ms).",
                    x, z, world.provider.getDimension(), String.format("%.1f", lastTickMs));
        }
        return true;
    }

    private static void updateIdleTracking(MinecraftServer server) {
        boolean moved = false;
        for (WorldServer world : server.worlds) {
            if (world == null) {
                continue;
            }
            for (EntityPlayer player : world.playerEntities) {
                double[] prev = lastPos.get(player.getUniqueID());
                if (prev != null) {
                    double dx = player.posX - prev[0];
                    double dy = player.posY - prev[1];
                    double dz = player.posZ - prev[2];
                    if (dx * dx + dy * dy + dz * dz > ModConfig.PREGENERATOR.idleMovementThreshold * ModConfig.PREGENERATOR.idleMovementThreshold) {
                        moved = true;
                    }
                }
                lastPos.put(player.getUniqueID(), new double[]{player.posX, player.posY, player.posZ});
            }
        }
        if (moved) {
            idleTicksCounter = 0;
        } else if (idleTicksCounter < Integer.MAX_VALUE) {
            idleTicksCounter++;
        }
    }

    private static void ensureSpiral() {
        if (spiralDx.length > 0 && builtRadius == ModConfig.PREGENERATOR.radiusChunks && builtInner == ModConfig.PREGENERATOR.innerRadiusChunks) {
            return;
        }

        int radius = Math.max(1, ModConfig.PREGENERATOR.radiusChunks);
        int inner = Math.max(0, Math.min(ModConfig.PREGENERATOR.innerRadiusChunks, radius - 1));
        int innerSq = inner * inner;
        int radiusSq = radius * radius;

        List<int[]> offsets = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 >= innerSq && d2 <= radiusSq) {
                    offsets.add(new int[]{dx, dz, d2});
                }
            }
        }
        offsets.sort(Comparator.comparingInt(o -> o[2]));

        spiralDx = new int[offsets.size()];
        spiralDz = new int[offsets.size()];
        for (int i = 0; i < offsets.size(); i++) {
            spiralDx[i] = offsets.get(i)[0];
            spiralDz[i] = offsets.get(i)[1];
        }

        spiralIndex = 0;
        builtRadius = ModConfig.PREGENERATOR.radiusChunks;
        builtInner = ModConfig.PREGENERATOR.innerRadiusChunks;

        WorldFlow.LOGGER.info("[SmartPregenerator] Pre-generation ring built: {} candidate chunks, radius {} (inner {}).",
                offsets.size(), radius, inner);
    }

    public static void reset() {
        spiralIndex = 0;
        worldPass = 0;
        ticksSinceGen = 0;
        cooldownTicks = 0;
        generatedTotal = 0;
        lastGeneratedWorldTime = -1L;
        lastPos.clear();
        idleTicksCounter = 0;
        builtRadius = -1;
        builtInner = -1;
        spiralDx = new int[0];
        spiralDz = new int[0];
    }

    public static int getSpiralIndex() {
        return spiralIndex;
    }

    public static int getSpiralLength() {
        return spiralDx.length;
    }

    public static int getCooldownTicks() {
        return cooldownTicks;
    }

    public static int getIdleTicksCounter() {
        return idleTicksCounter;
    }
}
