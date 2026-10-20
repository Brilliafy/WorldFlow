package com.cleanroommc.worldflow.command;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer;
import com.cleanroommc.worldflow.cascade.staging.CascadeSavedData;
import com.cleanroommc.worldflow.cascade.staging.StagedBlock;
import com.cleanroommc.worldflow.config.ModConfig;
import com.cleanroommc.worldflow.noise.VectorNoiseEngine;
import com.cleanroommc.worldflow.pregen.PregeneratorEngine;
import com.pg85.otg.OTG;
import com.pg85.otg.customobjects.structures.CustomStructure;
import com.pg85.otg.customobjects.structures.CustomStructureCache;
import com.pg85.otg.forge.world.ForgeWorld;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CommandWorldFlow extends CommandBase {

    @Override
    public String getName() {
        return "worldflow";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/worldflow <noise|cascade|pregen|pipeline|dumpchunk|diffarea|bench|hashchunk|unloadarea|otgstats> ...";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: " + getUsage(sender)));
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "noise":
                executeNoise(sender, rest);
                return;
            case "cascade":
                executeCascade(server, sender, rest);
                return;
            case "pregen":
            case "pregenerator":
                executePregen(sender, rest);
                return;
            case "pipeline":
            case "parallel":
                executePipeline(sender, rest);
                return;
            case "dumpchunk":
                executeDumpChunk(server, sender, rest);
                return;
            case "diffarea":
                executeDiffArea(server, sender, rest);
                return;
            case "bench":
            case "benchmark":
                executeBench(server, sender, rest);
                return;
            case "hashchunk":
                executeHashChunk(sender, rest);
                return;
            case "unloadarea":
                executeUnloadArea(sender, rest);
                return;
            case "otgstats":
                executeOtgStats(server, sender);
                return;
            default:
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: " + getUsage(sender)));
        }
    }

    private void executeOtgStats(MinecraftServer server, ICommandSender sender) {
        try {
            net.minecraft.world.World world = server.worlds[0];
            String name = world.getWorldInfo().getWorldName();
            Object local = OTG.getWorld(name);
            if (!(local instanceof ForgeWorld)) {
                local = OTG.getWorld("overworld");
            }
            if (!(local instanceof ForgeWorld)) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Not an OTG ForgeWorld: " + name));
                return;
            }
            CustomStructureCache cache = ((ForgeWorld) local).getStructureCache();
            Map<?, ?> worldInfo = (Map<?, ?>) getField(cache, "worldInfoChunks");
            Set<Object> unique = Collections.newSetFromMap(new IdentityHashMap<>());
            long slots = 0L, filled = 0L;
            for (Object regionObj : worldInfo.values()) {
                CustomStructure[][] arr = (CustomStructure[][]) getField(regionObj, "structures");
                for (CustomStructure[] row : arr) {
                    for (CustomStructure structure : row) {
                        slots++;
                        if (structure != null) {
                            filled++;
                            unique.add(structure);
                        }
                    }
                }
            }
            Object plotter = getField(cache, "plotter");
            Map<?, ?> bo4 = (Map<?, ?>) getField(plotter, "bo4StructureCache");
            Set<Object> uniqueBo4 = Collections.newSetFromMap(new IdentityHashMap<>());
            long bo4Slots = 0L, bo4Filled = 0L;
            for (Object regionObj : bo4.values()) {
                Object[][] arr = (Object[][]) regionObj;
                for (Object[] row : arr) {
                    for (Object structure : row) {
                        bo4Slots++;
                        if (structure != null) {
                            bo4Filled++;
                            uniqueBo4.add(structure);
                        }
                    }
                }
            }
            Map<?, ?> byName = (Map<?, ?>) getField(plotter, "spawnedStructuresByName");
            long byNameEntries = 0L;
            for (Object value : byName.values()) byNameEntries += ((java.util.List<?>) value).size();
            Map<?, ?> byGroup = (Map<?, ?>) getField(plotter, "spawnedStructuresByGroup");
            long groupEntries = 0L;
            for (Object value : byGroup.values()) groupEntries += ((Map<?, ?>) value).size();
            Map<?, ?> plotted = (Map<?, ?>) getField(plotter, "plottedChunks");
            Map<?, ?> fast = (Map<?, ?>) getField(plotter, "plottedChunksFastCache");
            long staged = CascadeBlockBuffer.INSTANCE.stagedBlockCount();
            long stagedChunks = CascadeBlockBuffer.INSTANCE.stagedChunkCount();
            long savedStaged = 0L;
            try {
                for (java.util.List<StagedBlock> list : CascadeSavedData.get(world).getStagedBlocks().values()) {
                    savedStaged += list.size();
                }
            } catch (Throwable ignored) {
            }
            Runtime rt = Runtime.getRuntime();
            long heap = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
            sender.sendMessage(new TextComponentString(TextFormatting.GOLD + String.format(
                    "OTGStats wInfo regions=%d slots=%d filled=%d unique=%d | plotter regions=%d slots=%d filled=%d unique=%d | byName %d keys/%d entries | byGroup %d keys/%d entries | plotted=%d fast=%d | staged %d blocks/%d chunks | savedStaged %d | heap=%dMB",
                    worldInfo.size(), slots, filled, unique.size(),
                    bo4.size(), bo4Slots, bo4Filled, uniqueBo4.size(),
                    byName.size(), byNameEntries, byGroup.size(), groupEntries,
                    plotted.size(), fast.size(), staged, stagedChunks, savedStaged, heap)));
        } catch (Throwable t) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "otgstats failed: " + t));
            WorldFlow.LOGGER.warn("[WorldFlow] otgstats failed", t);
        }
    }

    private static Object getField(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " on " + target.getClass().getName());
    }

    private void executeUnloadArea(ICommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: /worldflow unloadarea <centerX> <centerZ> <radius>"));
            return;
        }
        int cx;
        int cz;
        int radius;
        try {
            cx = Integer.parseInt(args[0]);
            cz = Integer.parseInt(args[1]);
            radius = Math.max(0, Math.min(256, Integer.parseInt(args[2])));
        } catch (NumberFormatException e) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Invalid arguments."));
            return;
        }
        if (!(sender.getEntityWorld() instanceof WorldServer)) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Not a server world."));
            return;
        }
        WorldServer world = (WorldServer) sender.getEntityWorld();
        int queued = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Chunk chunk = world.getChunkProvider().getLoadedChunk(cx + dx, cz + dz);
                if (chunk != null) {
                    world.getChunkProvider().queueUnload(chunk);
                    queued++;
                }
            }
        }
        CascadeBlockBuffer.INSTANCE.discardArea(world, cx, cz, radius);
        CascadeBlockBuffer.INSTANCE.trim(2_000_000);
        sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Queued unload for " + queued + " chunks around ["
                + cx + ", " + cz + "] radius " + radius + " (staged data discarded)"));
    }

    /**
     * Canonical SHA-256 of a fully generated chunk: all block states, biomes,
     * heightmap and tile entities. Used to prove decoration determinism between two
     * independent generations of the same seed/area.
     */
    private void executeHashChunk(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: /worldflow hashchunk <chunkX> <chunkZ>"));
            return;
        }
        int x;
        int z;
        try {
            x = Integer.parseInt(args[0]);
            z = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Invalid chunk coordinates."));
            return;
        }
        if (!(sender.getEntityWorld() instanceof WorldServer)) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Not a server world."));
            return;
        }
        WorldServer world = (WorldServer) sender.getEntityWorld();
        if (args.length >= 3 && "terrain".equalsIgnoreCase(args[2])) {
            try {
                net.minecraft.util.math.ChunkPos pos = new net.minecraft.util.math.ChunkPos(x, z);
                com.cleanroommc.worldflow.pipeline.ProtoChunk proto =
                        new com.cleanroommc.worldflow.pipeline.ProtoChunk(world, x, z);
                com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.enter(world, pos, proto, "HashTerrain");
                try {
                    net.minecraft.world.gen.IChunkGenerator gen =
                            com.cleanroommc.worldflow.pipeline.isolation.WorkerGeneratorContext.get().getChunkGenerator(world);
                    if (gen != null && gen.getClass().getName().startsWith("com.pg85.otg.")) {
                        try {
                            ((com.pg85.otg.forge.generator.OTGChunkGenerator) gen).clearChunkCache();
                        } catch (Throwable ignored) {
                        }
                    }
                    Chunk generated = gen.generateChunk(x, z);
                    sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "TERRAIN " + x + " " + z + " " + hashTerrain(generated)));
                } finally {
                    com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView.exit();
                }
            } catch (Throwable t) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "terrain hash failed: " + t));
            }
            return;
        }
        try {
            Chunk chunk = world.getChunkProvider().provideChunk(x, z);
            String hash = hashChunk(chunk);
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "HASH " + x + " " + z + " " + hash));
        } catch (Throwable t) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "hashchunk failed: " + t));
        }
    }

    private static String hashChunk(Chunk chunk) throws Exception {
        java.security.MessageDigest blocks = java.security.MessageDigest.getInstance("SHA-256");
        java.security.MessageDigest biomes = java.security.MessageDigest.getInstance("SHA-256");
        java.security.MessageDigest height = java.security.MessageDigest.getInstance("SHA-256");
        java.security.MessageDigest tes = java.security.MessageDigest.getInstance("SHA-256");
        net.minecraft.world.chunk.storage.ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        for (net.minecraft.world.chunk.storage.ExtendedBlockStorage ebs : sections) {
            if (ebs == null) {
                blocks.update((byte) 0);
                continue;
            }
            blocks.update((byte) 1);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        // Canonical textual state: immune to registry state-id ordering.
                        blocks.update(ebs.get(x, y, z).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        blocks.update((byte) 0);
                    }
                }
            }
        }
        biomes.update(chunk.getBiomeArray());
        for (int h : chunk.getHeightMap()) {
            height.update((byte) (h >>> 24));
            height.update((byte) (h >>> 16));
            height.update((byte) (h >>> 8));
            height.update((byte) h);
        }
        java.util.List<net.minecraft.tileentity.TileEntity> tileEntities =
                new java.util.ArrayList<>(chunk.getTileEntityMap().values());
        tileEntities.sort(java.util.Comparator.comparingLong(te ->
                te.getPos() == null ? 0L : te.getPos().toLong()));
        for (net.minecraft.tileentity.TileEntity te : tileEntities) {
            net.minecraft.nbt.NBTTagCompound nbt = new net.minecraft.nbt.NBTTagCompound();
            te.writeToNBT(nbt);
            tes.update(nbt.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            tes.update((byte) 0);
        }
        return "B:" + hex(blocks.digest()) + " BI:" + hex(biomes.digest())
                + " HM:" + hex(height.digest()) + " TE:" + hex(tes.digest());
    }

    private static String hashTerrain(Chunk chunk) throws Exception {
        java.security.MessageDigest blocks = java.security.MessageDigest.getInstance("SHA-256");
        java.security.MessageDigest biomes = java.security.MessageDigest.getInstance("SHA-256");
        java.security.MessageDigest height = java.security.MessageDigest.getInstance("SHA-256");
        for (net.minecraft.world.chunk.storage.ExtendedBlockStorage ebs : chunk.getBlockStorageArray()) {
            if (ebs == null) {
                blocks.update((byte) 0);
                continue;
            }
            blocks.update((byte) 1);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        blocks.update(ebs.get(x, y, z).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        blocks.update((byte) 0);
                    }
                }
            }
        }
        biomes.update(chunk.getBiomeArray());
        for (int h : chunk.getHeightMap()) {
            height.update((byte) (h >>> 24));
            height.update((byte) (h >>> 16));
            height.update((byte) (h >>> 8));
            height.update((byte) h);
        }
        return "B:" + hex(blocks.digest()) + " BI:" + hex(biomes.digest()) + " HM:" + hex(height.digest());
    }

    private static String hex(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void executePipeline(ICommandSender sender, String[] args) {
        sender.sendMessage(new TextComponentString(TextFormatting.GOLD + "=== Parallel Chunk Generation Pipeline ==="));
        sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Status: " +
                (ModConfig.CONCURRENCY.enableParallelGeneration ? TextFormatting.GREEN + "ACTIVE" : TextFormatting.RED + "DISABLED")));
        sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "Worker Threads: " +
                TextFormatting.WHITE + com.cleanroommc.worldflow.pipeline.GenerationWorkerPool.effectiveThreads() +
                TextFormatting.GRAY + " (active: " + com.cleanroommc.worldflow.pipeline.GenerationWorkerPool.getInstance().getActiveWorkerCount() +
                ", queued: " + com.cleanroommc.worldflow.pipeline.GenerationWorkerPool.getInstance().getQueueSize() + ")"));
        sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "In-Flight Chunks: " +
                TextFormatting.WHITE + com.cleanroommc.worldflow.pipeline.GenerationScheduler.getInstance().getInFlightCount() +
                TextFormatting.GRAY + " | Ready to Publish: " +
                TextFormatting.WHITE + com.cleanroommc.worldflow.pipeline.PublishManager.INSTANCE.getPendingCount()));
        sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "Total Requested: " +
                com.cleanroommc.worldflow.pipeline.GenerationScheduler.getInstance().totalGenerationsRequested.get() +
                " | Total Published: " +
                com.cleanroommc.worldflow.pipeline.PublishManager.INSTANCE.totalChunksPublished.get()));
    }

    private void executeNoise(ICommandSender sender, String[] args) {
        if (!sender.canUseCommand(2, getName())) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "You do not have permission to use the noise command."));
            return;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("stats")) {
            long total = VectorNoiseEngine.TOTAL_NOISE_EVALUATIONS.get();
            long arrays = VectorNoiseEngine.ARRAYS_POPULATED.get();

            sender.sendMessage(new TextComponentString(TextFormatting.GOLD + "=== Vector Noise Statistics ==="));
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Status: " +
                    (ModConfig.NOISE.enabled ? TextFormatting.GREEN + "ACTIVE" : TextFormatting.RED + "DISABLED")));
            sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "Total Gradient Noise Evaluations: " + TextFormatting.WHITE + total));
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Terrain Noise Octave Arrays Vectorized: " + TextFormatting.WHITE + arrays));
        } else if (args[0].equalsIgnoreCase("clear")) {
            VectorNoiseEngine.clearStats();
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Vector Noise statistics reset."));
        } else if (args[0].equalsIgnoreCase("toggle")) {
            ModConfig.NOISE.enabled = !ModConfig.NOISE.enabled;
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Vector Noise optimization now: " +
                    (ModConfig.NOISE.enabled ? TextFormatting.GREEN + "ENABLED" : TextFormatting.RED + "DISABLED")));
        } else {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: /worldflow noise [stats|clear|toggle]"));
        }
    }

    private void executeCascade(MinecraftServer server, ICommandSender sender, String[] args) {
        if (args.length == 0 || "status".equalsIgnoreCase(args[0]) || "info".equalsIgnoreCase(args[0])) {
            sender.sendMessage(new TextComponentString(TextFormatting.GOLD + "=== OTG Cascade Fix Status ==="));
            boolean enabled = ModConfig.GENERAL.enableCascadeFix;
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "Engine: " +
                    (enabled ? TextFormatting.GREEN + "ENABLED" : TextFormatting.RED + "DISABLED") +
                    TextFormatting.GRAY + " | OTG Staging: " +
                    (ModConfig.GENERAL.stageOTGBlocks ? TextFormatting.GREEN + "ON" : TextFormatting.RED + "OFF") +
                    TextFormatting.GRAY + " | Forge Decorator Staging: " +
                    (ModConfig.GENERAL.stageForgeDecorators ? TextFormatting.GREEN + "ON" : TextFormatting.RED + "OFF")));

            sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "Cascades Prevented: " +
                    TextFormatting.YELLOW + CascadeBlockBuffer.INSTANCE.totalCascadesPrevented.get()));
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "Blocks Staged: " +
                    TextFormatting.WHITE + CascadeBlockBuffer.INSTANCE.totalBlocksStaged.get() +
                    TextFormatting.GRAY + " | Blocks Flushed: " +
                    TextFormatting.WHITE + CascadeBlockBuffer.INSTANCE.totalBlocksFlushed.get() +
                    TextFormatting.GRAY + " | Currently Pending: " +
                    TextFormatting.YELLOW + CascadeBlockBuffer.INSTANCE.getCurrentlyStagedCount()));
            return;
        }

        if ("flush".equalsIgnoreCase(args[0])) {
            if (!sender.canUseCommand(2, getName())) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "You do not have permission to flush cascades."));
                return;
            }

            int totalFlushed = 0;
            for (WorldServer world : server.worlds) {
                if (world != null) {
                    // Snapshot first: flushing can trigger block updates that load chunks and mutate the
                    // live collection returned by getLoadedChunks(), which would cause a CME.
                    for (Chunk chunk : new java.util.ArrayList<>(world.getChunkProvider().getLoadedChunks())) {
                        totalFlushed += CascadeBlockBuffer.INSTANCE.flushChunk(world, chunk);
                    }
                }
            }
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Flushed " + totalFlushed + " staged blocks across loaded chunks."));
            return;
        }

        if ("clear".equalsIgnoreCase(args[0])) {
            if (!sender.canUseCommand(2, getName())) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "You do not have permission to clear the staging buffer."));
                return;
            }
            CascadeBlockBuffer.INSTANCE.clear();
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Staged block buffer cleared."));
            return;
        }

        if ("toggle".equalsIgnoreCase(args[0])) {
            if (!sender.canUseCommand(2, getName())) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "You do not have permission to toggle cascade fix."));
                return;
            }
            ModConfig.GENERAL.enableCascadeFix = !ModConfig.GENERAL.enableCascadeFix;
            ConfigManager.sync(WorldFlow.MOD_ID, Config.Type.INSTANCE);
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Cascade fix is now: " +
                    (ModConfig.GENERAL.enableCascadeFix ? TextFormatting.GREEN + "ENABLED" : TextFormatting.RED + "DISABLED")));
            return;
        }

        if ("reload".equalsIgnoreCase(args[0])) {
            if (!sender.canUseCommand(2, getName())) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "You do not have permission to reload the configuration."));
                return;
            }
            ConfigManager.sync(WorldFlow.MOD_ID, Config.Type.INSTANCE);
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Cascade Fix configuration reloaded."));
            return;
        }

        sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: /worldflow cascade [status|flush|clear|toggle|reload]"));
    }

    private void executePregen(ICommandSender sender, String[] args) {
        if (!sender.canUseCommand(2, getName())) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "You do not have permission to use the pregenerator command."));
            return;
        }

        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "status": {
                sender.sendMessage(new TextComponentString(TextFormatting.GOLD + "=== Smart Pregenerator ==="));
                sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Status: " +
                        (ModConfig.PREGENERATOR.enabled ? TextFormatting.GREEN + "ACTIVE" : TextFormatting.RED + "STOPPED")));
                sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "Ring: inner " + ModConfig.PREGENERATOR.innerRadiusChunks +
                        " -> radius " + ModConfig.PREGENERATOR.radiusChunks + " chunks, one chunk per " + ModConfig.PREGENERATOR.intervalTicks + " ticks"));
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Chunks pre-generated: " +
                        TextFormatting.WHITE + PregeneratorEngine.generatedTotal));
                sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "Cursor: " +
                        PregeneratorEngine.getSpiralIndex() + "/" + PregeneratorEngine.getSpiralLength() +
                        " | cooldown: " + PregeneratorEngine.getCooldownTicks() +
                        " | idle: " + PregeneratorEngine.getIdleTicksCounter() + " ticks"));
                sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "Last server tick: " +
                        String.format("%.1f", PregeneratorEngine.lastTickMsSnapshot) + " ms (skip above " + ModConfig.PREGENERATOR.maxTickTimeMs + " ms)"));
                return;
            }
            case "start":
            case "stop":
            case "toggle": {
                if (sub.equals("start")) {
                    ModConfig.PREGENERATOR.enabled = true;
                } else if (sub.equals("stop")) {
                    ModConfig.PREGENERATOR.enabled = false;
                } else {
                    ModConfig.PREGENERATOR.enabled = !ModConfig.PREGENERATOR.enabled;
                }
                ConfigManager.sync(WorldFlow.MOD_ID, Config.Type.INSTANCE);
                sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Smart Pregenerator: " +
                        (ModConfig.PREGENERATOR.enabled ? TextFormatting.GREEN + "ACTIVE" : TextFormatting.RED + "STOPPED")));
                return;
            }
            case "radius":
            case "inner":
            case "rate": {
                if (args.length < 2) {
                    sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: /worldflow pregen " + sub + " <number>"));
                    return;
                }
                int value;
                try {
                    value = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(new TextComponentString(TextFormatting.RED + "Not a number: " + args[1]));
                    return;
                }
                if (sub.equals("radius")) {
                    ModConfig.PREGENERATOR.radiusChunks = Math.max(1, value);
                } else if (sub.equals("inner")) {
                    ModConfig.PREGENERATOR.innerRadiusChunks = Math.max(0, value);
                } else {
                    ModConfig.PREGENERATOR.intervalTicks = Math.max(1, value);
                }
                ConfigManager.sync(WorldFlow.MOD_ID, Config.Type.INSTANCE);
                PregeneratorEngine.reset();
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Updated: inner " + ModConfig.PREGENERATOR.innerRadiusChunks +
                        ", radius " + ModConfig.PREGENERATOR.radiusChunks + ", interval " + ModConfig.PREGENERATOR.intervalTicks + " ticks. Cursor reset."));
                return;
            }
            case "reset": {
                PregeneratorEngine.reset();
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Smart Pregenerator cursor and counters reset."));
                return;
            }
            default:
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: /worldflow pregen [status|start|stop|toggle|radius <n>|inner <n>|rate <ticks>|reset]"));
        }
    }

    private void executeDumpChunk(MinecraftServer server, ICommandSender sender, String[] args) {
        if (!sender.canUseCommand(2, getName())) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "You do not have permission to use the dumpchunk command."));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: /worldflow dumpchunk <x> <z> [inline|pipeline|both]"));
            return;
        }
        int x, z;
        try {
            x = Integer.parseInt(args[0]);
            z = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Invalid chunk coordinates: " + args[0] + ", " + args[1]));
            return;
        }
        String mode = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "both";
        WorldServer world = (WorldServer) sender.getEntityWorld();
        if (world == null && server.worlds.length > 0) {
            world = server.worlds[0];
        }
        if (world == null) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "WorldServer not available."));
            return;
        }
        sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Executing dumpchunk for [" + x + ", " + z + "] (mode: " + mode + ")..."));
        com.cleanroommc.worldflow.debug.ChunkDumper.executeDump(world, x, z, mode, sender);
    }

    private void executeDiffArea(MinecraftServer server, ICommandSender sender, String[] args) {
        if (!sender.canUseCommand(2, getName())) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "You do not have permission to use the diffarea command."));
            return;
        }
        int radius = 16;
        int sampleStep = 1;
        if (args.length >= 1) {
            try {
                radius = Math.max(1, Integer.parseInt(args[0]));
            } catch (NumberFormatException e) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Invalid radius: " + args[0]));
                return;
            }
        }
        if (args.length >= 2) {
            try {
                sampleStep = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException e) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Invalid sampleEveryN: " + args[1]));
                return;
            }
        }
        WorldServer world = (WorldServer) sender.getEntityWorld();
        if (world == null && server.worlds.length > 0) {
            world = server.worlds[0];
        }
        if (world == null) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "WorldServer not available."));
            return;
        }
        int cx = sender.getPosition().getX() >> 4;
        int cz = sender.getPosition().getZ() >> 4;
        if (args.length >= 4) {
            try {
                cx = Integer.parseInt(args[2]);
                cz = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Invalid center chunk coordinates: " + args[2] + ", " + args[3]));
                return;
            }
        }
        com.cleanroommc.worldflow.debug.ChunkDumper.executeDiffArea(server, world, cx, cz, radius, sampleStep, sender);
    }

    private void executeBench(MinecraftServer server, ICommandSender sender, String[] args) {
        if (!sender.canUseCommand(2, getName())) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "You do not have permission to use the bench command."));
            return;
        }
        int radius = 16;
        int workers = 0;
        if (args.length >= 1) {
            try {
                radius = Math.max(1, Integer.parseInt(args[0]));
            } catch (NumberFormatException e) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Invalid radius: " + args[0]));
                return;
            }
        }
        if (args.length >= 2) {
            try {
                workers = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException e) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Invalid workers count: " + args[1]));
                return;
            }
        }
        WorldServer world = (WorldServer) sender.getEntityWorld();
        if (world == null && server.worlds.length > 0) {
            world = server.worlds[0];
        }
        if (world == null) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "WorldServer not available."));
            return;
        }
        int cx = sender.getPosition().getX() >> 4;
        int cz = sender.getPosition().getZ() >> 4;
        if (args.length >= 4) {
            try {
                cx = Integer.parseInt(args[2]);
                cz = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Invalid center chunk coordinates: " + args[2] + ", " + args[3]));
                return;
            }
        }
        int baseThreads = 0;
        if (args.length >= 5) {
            try {
                baseThreads = Math.max(0, Integer.parseInt(args[4]));
            } catch (NumberFormatException e) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Invalid base thread count: " + args[4]));
                return;
            }
        }
        com.cleanroommc.worldflow.debug.BenchmarkRunner.executeBenchmark(server, world, cx, cz, radius, workers, baseThreads, sender);
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "noise", "cascade", "pregen", "pipeline", "dumpchunk", "diffarea", "bench");
        }
        if (args.length == 2 && "cascade".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "status", "info", "flush", "clear", "toggle", "reload");
        }
        if (args.length == 4 && "dumpchunk".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "inline", "pipeline", "both");
        }
        return Collections.emptyList();
    }
}
