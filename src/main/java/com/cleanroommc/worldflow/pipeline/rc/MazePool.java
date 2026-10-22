package com.cleanroommc.worldflow.pipeline.rc;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Long-lived pool for RecurrentComplex maze generation.
 *
 * The original code creates a new single-thread executor for every maze, then
 * shutdown()s it. Under parallel chunk generation that means constant thread
 * creation/destruction plus one live thread per in-flight structure. A shared
 * bounded pool removes that churn and lets every maze run concurrently.
 */
public final class MazePool {

    private static final int THREADS = Math.max(4, Integer.getInteger(
            "worldflow.mazeThreads", Runtime.getRuntime().availableProcessors()));

    public static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(THREADS, r -> {
        Thread t = new Thread(r, "RC-Maze");
        t.setDaemon(true);
        return t;
    });

    private MazePool() {
    }
}
