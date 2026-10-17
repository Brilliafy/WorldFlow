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
}
