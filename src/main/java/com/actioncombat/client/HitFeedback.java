package com.actioncombat.client;

import com.actioncombat.CombatConstants;
import net.minecraft.util.Mth;

/**
 * Impact feel for a confirmed hit: hitstop plus camera shake.
 * <p>
 * Hitstop is the single biggest contributor to weight in action games — on impact the
 * attacker's animation is held for a few frames so the blow reads as having met
 * resistance. GeckoLib advances animations from the animatable's age, so rather than
 * trying to pause the clip we hold the dummy's animation clock still: see
 * {@link ClientCombat#hitstopScale()}, which the dummy's per-frame sync consults.
 * <p>
 * The shake is a decaying oscillation applied to the camera angles. It is seeded from
 * the swing direction so a left swing kicks the camera the other way, which reads as
 * recoil rather than a generic rumble.
 */
public final class HitFeedback {
    private static long hitstopStartNanos;
    private static float hitstopDurationNanos;

    private static long shakeStartNanos;
    private static float shakeYawAmplitude;
    private static float shakePitchAmplitude;
    private static float shakeRollAmplitude;

    /** Begin hitstop and shake for a confirmed hit. */
    public static void trigger(float directionSign) {
        long now = System.nanoTime();

        hitstopStartNanos = now;
        hitstopDurationNanos = CombatConstants.HITSTOP_SECONDS * 1.0E9F;

        shakeStartNanos = now;
        shakeYawAmplitude = CombatConstants.SHAKE_YAW_DEGREES * -directionSign;
        shakePitchAmplitude = CombatConstants.SHAKE_PITCH_DEGREES;
        shakeRollAmplitude = CombatConstants.SHAKE_ROLL_DEGREES * -directionSign;
    }

    /** True while the impact freeze is still holding the animation clock. */
    public static boolean isHitstopActive() {
        return System.nanoTime() - hitstopStartNanos < hitstopDurationNanos;
    }

    /**
     * Animation time multiplier: 0 while frozen, 1 otherwise. Applied to the dummy's
     * per-frame clock so the pose holds without desyncing the combat state machine,
     * which stays on real time.
     */
    public static float animationTimeScale() {
        return isHitstopActive() ? 0.0F : 1.0F;
    }

    /** 0..1 decaying envelope of the shake. */
    private static float shakeEnvelope() {
        float duration = CombatConstants.SHAKE_SECONDS * 1.0E9F;
        long elapsed = System.nanoTime() - shakeStartNanos;
        if (elapsed >= duration) return 0.0F;
        float t = elapsed / duration;
        return (1.0F - t) * (1.0F - t);
    }

    /** Oscillation phase in radians for the current time. */
    private static float shakePhase() {
        long elapsed = System.nanoTime() - shakeStartNanos;
        return elapsed / 1.0E9F * CombatConstants.SHAKE_FREQUENCY_HZ * Mth.TWO_PI;
    }

    public static float shakeYaw() {
        float env = shakeEnvelope();
        return env == 0.0F ? 0.0F : shakeYawAmplitude * env * Mth.sin(shakePhase());
    }

    public static float shakePitch() {
        float env = shakeEnvelope();
        // half-frequency on pitch so the two axes don't trace a straight line
        return env == 0.0F ? 0.0F : shakePitchAmplitude * env * Mth.sin(shakePhase() * 0.5F);
    }

    public static float shakeRoll() {
        float env = shakeEnvelope();
        return env == 0.0F ? 0.0F : shakeRollAmplitude * env * Mth.cos(shakePhase());
    }

    public static void reset() {
        hitstopStartNanos = 0L;
        hitstopDurationNanos = 0.0F;
        shakeStartNanos = 0L;
    }

    private HitFeedback() {}
}
