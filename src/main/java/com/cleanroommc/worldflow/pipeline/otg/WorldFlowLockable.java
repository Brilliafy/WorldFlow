package com.cleanroommc.worldflow.pipeline.otg;

import java.util.concurrent.locks.ReentrantLock;

/** Implemented by OTG Resource instances so each one can guard its own mutable state. */
public interface WorldFlowLockable {
    ReentrantLock worldflow$lock();
}
