package com.cleanroommc.worldflow.pipeline.otg;

import java.util.concurrent.atomic.AtomicLongArray;

/** OTG population phase timings (resources-before, BO4 spawn, resources-after). */
public final class OTGPhaseProfiler {
    public static final AtomicLongArray NANOS = new AtomicLongArray(5);
    public static final AtomicLongArray CALLS = new AtomicLongArray(5);
    private static final ThreadLocal<long[]> START = ThreadLocal.withInitial(() -> new long[5]);

    private OTGPhaseProfiler() {
    }

    public static void start(int i) {
        START.get()[i] = System.nanoTime();
    }

    public static void end(int i) {
        long s = START.get()[i];
        if (s != 0L) {
            NANOS.addAndGet(i, System.nanoTime() - s);
            CALLS.incrementAndGet(i);
            START.get()[i] = 0L;
        }
    }

    public static final java.util.concurrent.ConcurrentHashMap<String, long[]> RESOURCE =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void recordResource(String name, long nanos) {
        long[] slot = RESOURCE.computeIfAbsent(name, k -> new long[2]);
        synchronized (slot) {
            slot[0] += nanos;
            slot[1]++;
        }
    }

    public static String topResources(int n) {
        return RESOURCE.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(n)
                .map(e -> e.getKey() + "=" + (e.getValue()[0] / 1_000_000L) + "ms/" + e.getValue()[1])
                .collect(java.util.stream.Collectors.joining(", "));
    }

    public static double avgMs(int i) {
        long calls = CALLS.get(i);
        return calls == 0 ? 0.0 : (NANOS.get(i) / 1_000_000.0) / calls;
    }
}
