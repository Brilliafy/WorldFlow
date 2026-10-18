package com.cleanroommc.worldflow.debug;

import com.cleanroommc.worldflow.WorldFlow;
import com.cleanroommc.worldflow.pipeline.ProtoChunk;
import com.cleanroommc.worldflow.pipeline.isolation.ThreadLocalWorldView;
import com.cleanroommc.worldflow.pipeline.isolation.WorkerGeneratorContext;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.IChunkGenerator;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Arrays;

public class ChunkDumper {

    public static void executeDump(WorldServer world, int x, int z, String mode, ICommandSender sender) {
        mode = mode.toLowerCase();
        File dumpsDir = new File("dumps");
        if (!dumpsDir.exists()) {
            dumpsDir.mkdirs();
        }

        Chunk inlineChunk = null;
        Chunk pipelineChunk = null;

        if ("inline".equals(mode) || "both".equals(mode)) {
            try {
                // Ensure ThreadLocalWorldView is NOT generating so native inline path is taken
                boolean wasGenerating = ThreadLocalWorldView.isGenerating();
                if (wasGenerating) {
                    ThreadLocalWorldView.exit();
                }
                IChunkGenerator inlineGen = world.getChunkProvider().chunkGenerator;
                inlineChunk = inlineGen.generateChunk(x, z);
                File inlineFile = new File(dumpsDir, String.format("chunk_%d_%d_inline.txt", x, z));
                writeChunkDump(inlineChunk, inlineFile, "INLINE");
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Dumped inline chunk to " + inlineFile.getPath()));
            } catch (Throwable t) {
                WorldFlow.LOGGER.error("[WorldFlow] Failed to generate inline chunk [{}, {}]", x, z, t);
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Error generating inline chunk: " + t.getMessage()));
            }
        }

        if ("pipeline".equals(mode) || "both".equals(mode)) {
            try {
                ChunkPos pos = new ChunkPos(x, z);
                ProtoChunk proto = new ProtoChunk(world, x, z);
                ThreadLocalWorldView.enter(world, pos, proto, "DumpPipeline");
                try {
                    IChunkGenerator pipeGen = WorkerGeneratorContext.get().getChunkGenerator(world);
                    pipelineChunk = pipeGen.generateChunk(x, z);
                } finally {
                    ThreadLocalWorldView.exit();
                }
                File pipeFile = new File(dumpsDir, String.format("chunk_%d_%d_pipeline.txt", x, z));
                writeChunkDump(pipelineChunk, pipeFile, "PIPELINE");
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Dumped pipeline chunk to " + pipeFile.getPath()));
            } catch (Throwable t) {
                WorldFlow.LOGGER.error("[WorldFlow] Failed to generate pipeline chunk [{}, {}]", x, z, t);
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Error generating pipeline chunk: " + t.getMessage()));
            }
        }

        if ("both".equals(mode) && inlineChunk != null && pipelineChunk != null) {
            compareAndReport(inlineChunk, pipelineChunk, sender);
        }
    }

    private static void compareAndReport(Chunk a, Chunk b, ICommandSender sender) {
        int x = a.x;
        int z = a.z;

        // 1. Biomes comparison
        byte[] biomesA = a.getBiomeArray();
        byte[] biomesB = b.getBiomeArray();
        int biomeDiffs = 0;
        int minBiomeLen = Math.min(biomesA.length, biomesB.length);
        for (int i = 0; i < minBiomeLen; i++) {
            if (biomesA[i] != biomesB[i]) {
                biomeDiffs++;
            }
        }
        biomeDiffs += Math.abs(biomesA.length - biomesB.length);

        // 2. HeightMap comparison
        int[] hmA = a.getHeightMap();
        int[] hmB = b.getHeightMap();
        int hmDiffs = 0;
        int minHmLen = Math.min(hmA.length, hmB.length);
        for (int i = 0; i < minHmLen; i++) {
            if (hmA[i] != hmB[i]) {
                hmDiffs++;
            }
        }
        hmDiffs += Math.abs(hmA.length - hmB.length);

        // 3. Block sections comparison
        int blockDiffs = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int by = 0; by < 256; by++) {
            for (int bx = 0; bx < 16; bx++) {
                for (int bz = 0; bz < 16; bz++) {
                    pos.setPos(bx, by, bz);
                    IBlockState stateA = a.getBlockState(pos);
                    IBlockState stateB = b.getBlockState(pos);
                    if (stateA != stateB) {
                        blockDiffs++;
                    }
                }
            }
        }

        String summary = String.format(
                "[WorldFlow Diff] Chunk [%d, %d]: Biomes: %s (%d diffs) | HeightMap: %s (%d diffs) | Sections: %s (%d diffs)",
                x, z,
                biomeDiffs == 0 ? "MATCH" : "DIFF", biomeDiffs,
                hmDiffs == 0 ? "MATCH" : "DIFF", hmDiffs,
                blockDiffs == 0 ? "MATCH" : "DIFF", blockDiffs
        );

        WorldFlow.LOGGER.info(summary);
        sender.sendMessage(new TextComponentString(
                (biomeDiffs == 0 && hmDiffs == 0 && blockDiffs == 0 ? TextFormatting.GREEN : TextFormatting.YELLOW) + summary
        ));
    }

    private static void writeChunkDump(Chunk chunk, File targetFile, String label) {
        if (chunk == null) return;
        try (PrintWriter pw = new PrintWriter(new FileWriter(targetFile))) {
            pw.printf("=== Chunk Dump: [%d, %d] Path: %s ===%n", chunk.x, chunk.z, label);
            pw.printf("IsPopulated: %b | IsLightPopulated: %b%n", chunk.isTerrainPopulated(), chunk.isLightPopulated());

            // Biomes
            byte[] biomes = chunk.getBiomeArray();
            pw.println("--- Biomes (256 bytes) ---");
            for (int r = 0; r < 16; r++) {
                StringBuilder sb = new StringBuilder();
                for (int c = 0; c < 16; c++) {
                    sb.append(String.format("%3d ", biomes[r * 16 + c] & 0xFF));
                }
                pw.println(sb.toString().trim());
            }

            // HeightMap
            int[] hm = chunk.getHeightMap();
            pw.println("--- HeightMap (256 ints) ---");
            for (int r = 0; r < 16; r++) {
                StringBuilder sb = new StringBuilder();
                for (int c = 0; c < 16; c++) {
                    sb.append(String.format("%3d ", hm[r * 16 + c]));
                }
                pw.println(sb.toString().trim());
            }

            // Sections
            pw.println("--- Block Sections ---");
            ExtendedBlockStorage[] storage = chunk.getBlockStorageArray();
            for (int sec = 0; sec < storage.length; sec++) {
                if (storage[sec] != null && !storage[sec].isEmpty()) {
                    pw.printf("Section Y=%d (yOffset=%d):%n", sec, storage[sec].getYLocation());
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                IBlockState state = storage[sec].get(x, y, z);
                                if (!state.getBlock().isAir(state, null, null)) {
                                    pw.printf("  [%d, %d, %d] %s%n", x, storage[sec].getYLocation() + y, z, state.toString());
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            WorldFlow.LOGGER.error("[WorldFlow] Failed to write chunk dump to {}", targetFile, t);
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean DIFFING = new java.util.concurrent.atomic.AtomicBoolean(false);

    public static void executeDiffArea(net.minecraft.server.MinecraftServer server, WorldServer world, int centerX, int centerZ, int radius, int sampleStep, ICommandSender sender) {
        if (!DIFFING.compareAndSet(false, true)) {
            send(server, sender, TextFormatting.RED + "[WorldFlow DiffArea] An area diff is already running! Please wait for it to complete.");
            return;
        }

        int step = Math.max(1, sampleStep);
        java.util.List<ChunkPos> targets = new java.util.ArrayList<>();
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                targets.add(new ChunkPos(centerX + dx, centerZ + dz));
            }
        }

        send(server, sender, TextFormatting.GOLD + String.format(
                "[WorldFlow DiffArea] Checking area: radius=%d, sampleEveryN=%d (%d sample chunks) centered at [%d, %d]...",
                radius, step, targets.size(), centerX, centerZ
        ));

        DiffAreaRunner runner = new DiffAreaRunner(server, world, centerX, centerZ, radius, step, targets, sender);
        if (server != null) {
            server.addScheduledTask(runner);
        } else {
            while (!runner.isComplete()) {
                runner.run();
            }
        }
    }

    private static class DiffAreaRunner implements Runnable {
        private final net.minecraft.server.MinecraftServer server;
        private final WorldServer world;
        private final int centerX;
        private final int centerZ;
        private final int radius;
        private final int step;
        private final java.util.List<ChunkPos> targets;
        private final ICommandSender sender;
        private final File dumpsDir;

        private int currentIndex = 0;
        private int checked = 0;
        private int biomeMatches = 0;
        private int biomeDiffs = 0;
        private int hmMatches = 0;
        private int hmDiffs = 0;
        private int secMatches = 0;
        private int secDiffs = 0;
        private final java.util.List<String> mismatchSamples = new java.util.ArrayList<>();
        private final long startTime;
        private long lastLogTime;

        public DiffAreaRunner(net.minecraft.server.MinecraftServer server, WorldServer world, int centerX, int centerZ, int radius, int step, java.util.List<ChunkPos> targets, ICommandSender sender) {
            this.server = server;
            this.world = world;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            this.step = step;
            this.targets = targets;
            this.sender = sender;
            this.dumpsDir = new File("dumps");
            if (!this.dumpsDir.exists()) {
                this.dumpsDir.mkdirs();
            }
            this.startTime = System.currentTimeMillis();
            this.lastLogTime = System.currentTimeMillis();
        }

        public boolean isComplete() {
            return currentIndex >= targets.size();
        }

        @Override
        public void run() {
            try {
                long tickStart = System.currentTimeMillis();
                int batchCount = 0;

                while (currentIndex < targets.size() && batchCount < 16) {
                    ChunkPos pos = targets.get(currentIndex++);
                    batchCount++;
                    int x = pos.x;
                    int z = pos.z;

                    Chunk inlineChunk = null;
                    Chunk pipeChunk = null;

                    try {
                        // Reset caches to prevent stale cross-chunk contamination
                        net.minecraft.world.gen.layer.IntCache.resetIntCache();
                        net.minecraft.world.gen.IChunkGenerator mainGen = world.getChunkProvider().chunkGenerator;
                        if (mainGen != null && "com.pg85.otg.forge.generator.OTGChunkGenerator".equals(mainGen.getClass().getName())) {
                            try {
                                ((com.pg85.otg.forge.generator.OTGChunkGenerator) mainGen).clearChunkCache();
                            } catch (Throwable ignored) {}
                        }

                        // Inline generation on main server thread
                        boolean wasGen = ThreadLocalWorldView.isGenerating();
                        if (wasGen) ThreadLocalWorldView.exit();
                        inlineChunk = world.getChunkProvider().chunkGenerator.generateChunk(x, z);

                        // Pipeline generation
                        net.minecraft.world.gen.layer.IntCache.resetIntCache();
                        ProtoChunk proto = new ProtoChunk(world, x, z);
                        ThreadLocalWorldView.enter(world, pos, proto, "DiffArea");
                        try {
                            net.minecraft.world.gen.IChunkGenerator pipeGen = WorkerGeneratorContext.get().getChunkGenerator(world);
                            if (pipeGen != null && "com.pg85.otg.forge.generator.OTGChunkGenerator".equals(pipeGen.getClass().getName())) {
                                try {
                                    ((com.pg85.otg.forge.generator.OTGChunkGenerator) pipeGen).clearChunkCache();
                                } catch (Throwable ignored) {}
                            }
                            pipeChunk = pipeGen.generateChunk(x, z);
                        } finally {
                            ThreadLocalWorldView.exit();
                        }
                    } catch (Throwable t) {
                        WorldFlow.LOGGER.error("[WorldFlow] Error generating chunks during diffarea at [{}, {}]", x, z, t);
                        continue;
                    }

                    if (inlineChunk == null || pipeChunk == null) {
                        continue;
                    }

                    // Compare biomes
                    byte[] bA = inlineChunk.getBiomeArray();
                    byte[] bB = pipeChunk.getBiomeArray();
                    int bDiff = 0;
                    int minB = Math.min(bA.length, bB.length);
                    for (int i = 0; i < minB; i++) {
                        if (bA[i] != bB[i]) bDiff++;
                    }
                    bDiff += Math.abs(bA.length - bB.length);
                    if (bDiff == 0) biomeMatches++; else biomeDiffs++;

                    // Compare heightmap
                    int[] hmA = inlineChunk.getHeightMap();
                    int[] hmB = pipeChunk.getHeightMap();
                    int hmDiff = 0;
                    int minHm = Math.min(hmA.length, hmB.length);
                    for (int i = 0; i < minHm; i++) {
                        if (hmA[i] != hmB[i]) hmDiff++;
                    }
                    hmDiff += Math.abs(hmA.length - hmB.length);
                    if (hmDiff == 0) hmMatches++; else hmDiffs++;

                    // Compare block states
                    int secDiff = 0;
                    BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
                    for (int by = 0; by < 256; by++) {
                        for (int bx = 0; bx < 16; bx++) {
                            for (int bz = 0; bz < 16; bz++) {
                                mpos.setPos(bx, by, bz);
                                IBlockState sA = inlineChunk.getBlockState(mpos);
                                IBlockState sB = pipeChunk.getBlockState(mpos);
                                if (sA != sB) {
                                    secDiff++;
                                }
                            }
                        }
                    }
                    if (secDiff == 0) secMatches++; else secDiffs++;

                    checked++;

                    if (bDiff > 0 || hmDiff > 0 || secDiff > 0) {
                        if (mismatchSamples.size() < 10) {
                            mismatchSamples.add(String.format("[%d, %d] -> Biomes: %d diffs, HM: %d diffs, Sections: %d diffs", x, z, bDiff, hmDiff, secDiff));
                        }
                        // Write mismatch dumps
                        writeChunkDump(inlineChunk, new File(dumpsDir, String.format("mismatch_%d_%d_inline.txt", x, z)), "INLINE");
                        writeChunkDump(pipeChunk, new File(dumpsDir, String.format("mismatch_%d_%d_pipeline.txt", x, z)), "PIPELINE");
                    }

                    // Yield if this tick has taken longer than 40ms to avoid hitching
                    if (batchCount >= 1 && (System.currentTimeMillis() - tickStart >= 40L)) {
                        break;
                    }
                }

                long now = System.currentTimeMillis();
                if (now - lastLogTime >= 5000L || currentIndex >= targets.size()) {
                    lastLogTime = now;
                    send(server, sender, TextFormatting.GRAY + String.format(
                            "[WorldFlow DiffArea] Progress: %d/%d (%.1f%%) | Biomes: %d/%d | HM: %d/%d | Sections: %d/%d",
                            checked, targets.size(), (checked * 100.0) / targets.size(),
                            biomeMatches, checked, hmMatches, checked, secMatches, checked
                    ));
                }

                if (currentIndex < targets.size()) {
                    if (server != null) {
                        server.addScheduledTask(this);
                    }
                } else {
                    finish();
                }
            } catch (Throwable t) {
                WorldFlow.LOGGER.error("[WorldFlow] DiffArea execution encountered an error", t);
                send(server, sender, TextFormatting.RED + "[WorldFlow DiffArea] Execution failed: " + t.getMessage());
                DIFFING.set(false);
            }
        }

        private void finish() {
            try {
                long elapsedSec = Math.max(1, (System.currentTimeMillis() - startTime) / 1000);
                double bPct = checked > 0 ? (biomeMatches * 100.0) / checked : 0.0;
                double hmPct = checked > 0 ? (hmMatches * 100.0) / checked : 0.0;
                double sPct = checked > 0 ? (secMatches * 100.0) / checked : 0.0;

                send(server, sender, TextFormatting.GOLD + "=== WorldFlow DiffArea Results ===");
                send(server, sender, TextFormatting.YELLOW + String.format(
                        "Checked %d chunks in %ds (radius: %d, sampleEveryN: %d)",
                        checked, elapsedSec, radius, step
                ));
                send(server, sender, (biomeDiffs == 0 ? TextFormatting.GREEN : TextFormatting.RED) + String.format(
                        "Biomes:    %d MATCH (%.1f%%) / %d DIFF",
                        biomeMatches, bPct, biomeDiffs
                ));
                send(server, sender, (hmDiffs == 0 ? TextFormatting.GREEN : TextFormatting.RED) + String.format(
                        "HeightMap: %d MATCH (%.1f%%) / %d DIFF",
                        hmMatches, hmPct, hmDiffs
                ));
                send(server, sender, (secDiffs == 0 ? TextFormatting.GREEN : TextFormatting.RED) + String.format(
                        "Sections:  %d MATCH (%.1f%%) / %d DIFF",
                        secMatches, sPct, secDiffs
                ));

                if (biomeDiffs == 0 && hmDiffs == 0 && secDiffs == 0) {
                    send(server, sender, TextFormatting.GREEN + "[WorldFlow DiffArea] SUCCESS: All " + checked + " sampled chunks are bit-identical between inline and pipeline!");
                } else {
                    send(server, sender, TextFormatting.RED + "[WorldFlow DiffArea] Found mismatches! First mismatching chunks:");
                    for (String sample : mismatchSamples) {
                        send(server, sender, TextFormatting.RED + "  - " + sample);
                    }
                }
            } finally {
                DIFFING.set(false);
            }
        }
    }

    private static void send(net.minecraft.server.MinecraftServer server, ICommandSender sender, String message) {
        WorldFlow.LOGGER.info(TextFormatting.getTextWithoutFormattingCodes(message));
        if (server != null && !server.isCallingFromMinecraftThread()) {
            server.addScheduledTask(() -> sender.sendMessage(new TextComponentString(message)));
        } else {
            sender.sendMessage(new TextComponentString(message));
        }
    }
}
