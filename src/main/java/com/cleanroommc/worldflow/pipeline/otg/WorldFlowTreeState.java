package com.cleanroommc.worldflow.pipeline.otg;

/** Readiness flag for OTG resources whose only mutable state is a one-time lazy load. */
public interface WorldFlowTreeState {
    boolean worldflow$ready();

    void worldflow$markReady();
}
