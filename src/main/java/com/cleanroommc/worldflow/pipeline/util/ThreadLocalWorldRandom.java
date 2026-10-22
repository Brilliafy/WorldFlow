package com.cleanroommc.worldflow.pipeline.util;

import java.util.Random;

/**
 * Drop-in Random for World.rand. Vanilla World.setRandomSeed uses the single
 * world Random and Java's Random.setSeed is synchronized; parallel structure
 * workers then convoy on that monitor. This keeps independent seed state per
 * calling thread while preserving the Random API.
 */
public final class ThreadLocalWorldRandom extends Random {

    private static final ThreadLocal<Delegate> DELEGATE = ThreadLocal.withInitial(Delegate::new);

    private static final class Delegate extends Random {
        int nextBits(int bits) {
            return next(bits);
        }
    }

    @Override
    public void setSeed(long seed) {
        DELEGATE.get().setSeed(seed);
    }

    @Override
    protected int next(int bits) {
        return DELEGATE.get().nextBits(bits);
    }
}
