package com.cleanroommc.worldflow.command;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.cascade.staging.CascadeBlockBuffer;
import com.cleanroommc.worldflow.config.ModConfig;
import com.cleanroommc.worldflow.noise.VectorNoiseEngine;
import com.cleanroommc.worldflow.pregen.PregeneratorEngine;
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
import java.util.Locale;

public class CommandWorldFlow extends CommandBase {

    @Override
    public String getName() {
        return "worldflow";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/worldflow <noise|cascade|pregen|pipeline> ...";
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
            default:
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: " + getUsage(sender)));
        }
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

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "noise", "cascade", "pregen", "pipeline");
        }
        if (args.length == 2 && "cascade".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "status", "info", "flush", "clear", "toggle", "reload");
        }
        return Collections.emptyList();
    }
}
