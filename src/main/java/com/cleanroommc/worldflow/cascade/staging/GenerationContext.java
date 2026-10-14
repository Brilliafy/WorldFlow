package com.cleanroommc.worldflow.cascade.staging;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.Deque;

public class GenerationContext {

    private static final ThreadLocal<Deque<ChunkPos>> POPULATING_STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Deque<String>> GENERATOR_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    public static void enterPopulate(int chunkX, int chunkZ, String generatorName) {
        POPULATING_STACK.get().push(new ChunkPos(chunkX, chunkZ));
        GENERATOR_STACK.get().push(generatorName != null ? generatorName : "Unknown");
    }

    public static void exitPopulate() {
        Deque<ChunkPos> posStack = POPULATING_STACK.get();
        if (!posStack.isEmpty()) {
            posStack.pop();
        }
        Deque<String> genStack = GENERATOR_STACK.get();
        if (!genStack.isEmpty()) {
            genStack.pop();
        }
    }

    public static boolean isPopulating() {
        return !POPULATING_STACK.get().isEmpty();
    }

    public static String getGeneratorName() {
        Deque<String> stack = GENERATOR_STACK.get();
        return stack.isEmpty() ? "Unknown" : stack.peek();
    }

    /**
     * Checks if placing a block in targetChunk during population of current chunk constitutes a cascade.
     * In Minecraft Forge, a decorator populating (X, Z) is legally allowed to modify blocks in:
     * [X, X + 1] x [Z, Z + 1] (due to standard 8-block decoration offset).
     * Any placement outside this 2x2 area into an UNLOADED chunk triggers a recursive chunk load/generate.
     */
    public static boolean isCascading(World world, int targetChunkX, int targetChunkZ) {
        Deque<ChunkPos> stack = POPULATING_STACK.get();
        if (stack.isEmpty()) {
            return false;
        }

        ChunkPos current = stack.peek();
        if (current == null) {
            return false;
        }

        // If target chunk is already in memory, it will not trigger a disk or generation cascade
        if (world.getChunkProvider().getLoadedChunk(targetChunkX, targetChunkZ) != null) {
            return false;
        }

        // Check if within legitimate 2x2 decoration window
        boolean inWindow = (targetChunkX == current.x || targetChunkX == current.x + 1) &&
                           (targetChunkZ == current.z || targetChunkZ == current.z + 1);

        return !inWindow;
    }
}
