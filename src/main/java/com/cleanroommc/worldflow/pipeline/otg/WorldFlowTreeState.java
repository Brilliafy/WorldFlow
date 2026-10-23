package com.cleanroommc.worldflow.pipeline.otg;

/** Readiness flag for OTG resources whose only mutable state is a one-time lazy load. */
public interface WorldFlowTreeState {
    boolean worldflow$ready();

    void worldflow$markReady();

    /**
     * Forces the one-time load now (called once on the server thread before the
     * generation pools start). Safe to call concurrently; after the first call the
     * loaded collections are read-only.
     */
    void worldflow$warmLoad(String worldName);
}
