package com.actioncombat.client;

import com.actioncombat.CombatConstants;

/** FOV punch state for hit feedback; read by the camera-angles event handler each frame. */
public final class FovPunch {
    private static long startNanos;
    private static final float DECAY_NANOS = CombatConstants.FOV_PUNCH_DECAY_SECONDS * 1.0E9F;

    public static void trigger() {
        startNanos = System.nanoTime();
    }

    /** 0..1 envelope of the punch, 0 when idle. */
    public static float envelope() {
        long elapsed = System.nanoTime() - startNanos;
        if (elapsed >= DECAY_NANOS) return 0.0F;
        float t = elapsed / DECAY_NANOS;
        return 1.0F - t * t;
    }

    private FovPunch() {}
}
