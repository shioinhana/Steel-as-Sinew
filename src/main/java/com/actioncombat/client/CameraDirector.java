package com.actioncombat.client;

import com.actioncombat.CombatConstants;

/**
 * Genshin/Zelda-style camera rig chooser.
 * <p>
 * Replaces assigning the camera distance directly each frame, which made every state
 * change a hard cut. Each combat state declares a rig (distance, lateral offset, height
 * and FOV bias) and the live values are eased toward it by a critically damped spring,
 * so the camera settles without overshoot and without a fixed transition time —
 * interrupting a transition simply retargets it instead of restarting a tween.
 */
public final class CameraDirector {

    /** A camera placement. Distance and offsets are in blocks, fovBias in degrees. */
    public record Rig(float distance, float sideOffset, float heightOffset, float fovBias) {}

    public enum State {
        /** Sheathed: vanilla owns the camera. */
        SHEATHED,
        /**
         * Drawn Genshin-style third-person: camera sits behind the player with
         * no shoulder offset. The mouse always orbits; attacking never steals
         * the camera or glues it to the body.
         */
        FREE,
        /** Unused leftover; same framing as {@link #FREE}. */
        COMBAT,
        /** Mid-swing: same Genshin framing (no over-shoulder push-in). */
        ATTACKING,
        /** Guarding: same Genshin framing so walk+parry does not cut the camera. */
        GUARDING,
        /** Sprinting: same framing as {@link #FREE}; speed never moves the camera. */
        SPRINTING,
        /** Inspecting the weapon: slightly closer, still centered. */
        INSPECTING,
    }

    /**
     * 拔刀模式 keeps one constant framing: attacking, guarding and speeding up must
     * not zoom the camera. Only inspecting pushes in, and it never touches the FOV.
     */
    private static Rig rigFor(State state) {
        return switch (state) {
            case SHEATHED, FREE, COMBAT, ATTACKING, GUARDING, SPRINTING ->
                    new Rig(CombatConstants.THIRD_PERSON_DISTANCE, 0.0F, 0.15F, 0.0F);
            case INSPECTING -> new Rig(3.2F, 0.0F, 0.25F, 0.0F);
        };
    }

    // live values, eased toward the active rig
    private float distance;
    private float sideOffset;
    private float heightOffset;
    private float fovBias;
    /** Spring velocities. */
    private float distanceVel;
    private float sideVel;
    private float heightVel;
    private float fovVel;

    private boolean seeded;

    /** Ease toward the rig for {@code state}. Call once per frame. */
    public void update(State state, float deltaSeconds) {
        Rig target = rigFor(state);

        if (!seeded) {
            distance = target.distance();
            sideOffset = target.sideOffset();
            heightOffset = target.heightOffset();
            fovBias = target.fovBias();
            seeded = true;
            return;
        }

        float stiffness = CombatConstants.CAMERA_SPRING_STIFFNESS;
        distance = spring(distance, target.distance(), stiffness, deltaSeconds, v -> distanceVel = v, distanceVel);
        sideOffset = spring(sideOffset, target.sideOffset(), stiffness, deltaSeconds, v -> sideVel = v, sideVel);
        heightOffset = spring(heightOffset, target.heightOffset(), stiffness, deltaSeconds, v -> heightVel = v, heightVel);
        fovBias = spring(fovBias, target.fovBias(), stiffness, deltaSeconds, v -> fovVel = v, fovVel);
    }

    /**
     * One step of a critically damped spring. Critical damping is the point where the
     * value converges as fast as possible without overshooting, which is what keeps a
     * combat camera from bouncing when the state flips rapidly.
     */
    private static float spring(float current, float target, float stiffness,
                                float dt, java.util.function.Consumer<Float> storeVel,
                                float velocity) {
        float damping = 2.0F * (float) Math.sqrt(stiffness);
        float accel = stiffness * (target - current) - damping * velocity;
        float newVel = velocity + accel * dt;
        storeVel.accept(newVel);
        return current + newVel * dt;
    }

    public float distance() {
        return distance;
    }

    public float sideOffset() {
        return sideOffset;
    }

    public float heightOffset() {
        return heightOffset;
    }

    public float fovBias() {
        return fovBias;
    }

    /** Snap to a state's rig, dropping any easing (used when entering/leaving combat). */
    public void snapTo(State state) {
        Rig rig = rigFor(state);
        distance = rig.distance();
        sideOffset = rig.sideOffset();
        heightOffset = rig.heightOffset();
        fovBias = rig.fovBias();
        distanceVel = sideVel = heightVel = fovVel = 0.0F;
        seeded = true;
    }

    public void reset() {
        seeded = false;
        distanceVel = sideVel = heightVel = fovVel = 0.0F;
    }
}
