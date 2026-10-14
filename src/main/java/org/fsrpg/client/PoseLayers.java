package org.fsrpg.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.fsvoxel.animation.AnimationMixer;
import org.fsvoxel.animation.FsvoxAnimation;
import org.fsvoxel.animation.FsvoxAnimation.Clip;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.Rig;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.fsrpg.combat.AttackAim;

/** FS mixer masks and continuous render clocks, independent of Minecraft's server tick loop. */
public final class PoseLayers {
    public static final String DEFAULT_STANCE = "rapier_stance";
    public record Sample(Map<String, Pose> pose, float movingWeight, float lowerBodyWeight, String layer) {}
    private PoseLayers() {}

    /** Project blended elbows back onto anatomical flexion plus forearm pronation. */
    public static Map<String, Pose> dualElbows(Map<String, Pose> source) {
        var result = new HashMap<>(source);
        for (String side : ListHolder.SIDES) {
            String id=side+"_forearm"; Pose p=source.get(id); if(p==null)continue;
            Quaternionf q=p.fsQuaternion().normalize();
            float bend=(float)Math.IEEEremainder(2*Math.atan2(q.x,q.w),Math.PI*2);
            float turn=(float)Math.IEEEremainder(2*Math.atan2(q.y,q.w),Math.PI*2);
            Quaternionf constrained=new Quaternionf().rotationX(Math.clamp(bend,(float)Math.toRadians(-140),0))
                    .rotateY(Math.clamp(turn,(float)Math.toRadians(-35),(float)Math.toRadians(35)));
            result.put(id,new Pose(FsvoxAnimation.fromQuaternion(constrained),p.position(),p.scale()));
        }
        return Map.copyOf(result);
    }
    private static final class ListHolder { static final String[] SIDES={"right","left"}; }

    public static boolean breathingAction(String action) {
        return action.equals("rapier_idle") || action.startsWith("dual_idle_") || action.equals("spear_idle");
    }

    /** Each weapon owns its carry; an absent clip keeps that resource's idle pose. */
    public static String runAction(Rig rig, String idleAction) {
        String name = idleAction.startsWith("dual_idle_") ? "dual_run"
                : idleAction.equals("spear_idle") ? "spear_run" : "rapier_run";
        return rig.clips().containsKey(name) ? name : null;
    }

    /** Finish attack entry early enough to expose anticipation before the active marker. */
    public static double transitionTicks(Clip clip, double minimumSeconds, int windupTicks, boolean attacking) {
        double ticks = Math.max(minimumSeconds, clip.transition()) * 20;
        return attacking ? Math.min(ticks, Math.max(0, windupTicks) * .4) : ticks;
    }
    /** Persistent dual hand positions need a longer entry than a fixed ready stance. */
    public static double transitionTicks(Clip clip, double minimumSeconds, int windupTicks, boolean attacking, boolean persistentHands) {
        double ticks = transitionTicks(clip, minimumSeconds, windupTicks, attacking);
        return attacking && persistentHands ? Math.max(ticks, Math.max(0, windupTicks) * .8) : ticks;
    }

    /** Client timer deltas advance once per rendered frame, independent of server world-time corrections. */
    public static final class RenderClock {
        private double tick;
        public double sample() { return tick; }
        public void reset() { tick = 0; }
        public void advance(double frameTicks, boolean running) {
            if (running && Double.isFinite(frameTicks) && frameTicks > 0) tick += frameTicks;
        }
    }

    /** Snapshot time is a measurement, not permission to teleport the rendered action. */
    public static final class AttackClock {
        private double elapsed, previousTick, measuredElapsed, measuredTick;
        private final double activeBegin, activeEnd;
        public AttackClock(double elapsed, double renderTick) {
            this(elapsed, renderTick, Double.POSITIVE_INFINITY, 0);
        }
        public AttackClock(double elapsed, double renderTick, double windup, double active) {
            this.elapsed = measuredElapsed = Math.max(0, elapsed);
            previousTick = measuredTick = renderTick;
            activeBegin = windup; activeEnd = windup + active;
        }
        public void accept(double elapsed, double renderTick) {
            sample(renderTick);
            measuredElapsed = Math.max(0, elapsed);
            measuredTick = Math.max(previousTick, renderTick);
        }
        public double sample(double renderTick) {
            double dt = Math.max(0, renderTick - previousTick);
            double predicted = elapsed + dt;
            double measured = measuredElapsed + Math.max(0, renderTick - measuredTick);
            // Packet latency and partial-tick arrival vary. Catch up over subsequent frames;
            // a new ACTIVE/RECOVERY packet must never rewind or skip the middle of a cut.
            // The authored active arc already uses the configured hit window. Changing its
            // speed to correct network jitter can exceed its anatomical frame-step limit.
            double activeOverlap = Math.max(0, Math.min(predicted, activeEnd) - Math.max(elapsed, activeBegin));
            double correction = Math.max(0, dt - activeOverlap) * .25;
            elapsed = Math.max(elapsed, predicted + Math.clamp(measured - predicted, -correction, correction));
            previousTick = Math.max(previousTick, renderTick);
            return elapsed;
        }
    }

    public static double attackElapsed(String phase, int remaining, int windup, int active, int recovery) {
        return switch (phase) {
            case "WINDUP" -> windup - remaining;
            case "ACTIVE" -> windup + active - remaining;
            case "RECOVERY" -> windup + active + recovery - remaining;
            default -> -1;
        };
    }

    /** Network revisions advance at each combat phase; they do not identify a new swing. */
    public static boolean continuesAction(String previousAction, String previousPhase, long previousRevision,
                                           String nextAction, String nextPhase, long nextRevision) {
        if (!previousAction.equals(nextAction) || nextRevision < previousRevision) return false;
        if (nextRevision == previousRevision && previousPhase.equals(nextPhase)) return true;
        int previous = attackPhase(previousPhase), next = attackPhase(nextPhase);
        // WINDUP -> ACTIVE -> RECOVERY retains the same clock and entry pose. A phase
        // reset (including a second WINDUP with a new revision) starts a fresh attack.
        return previous >= 0 && next > previous;
    }

    private static int attackPhase(String phase) {
        return switch (phase) { case "WINDUP" -> 0; case "ACTIVE" -> 1; case "RECOVERY" -> 2; default -> -1; };
    }

    /** A packet may finish contact before the continuous visual clock reaches its last active sample. */
    public static boolean protectedActive(double presentedElapsed, double projectedElapsed, int windup, int active) {
        double end = windup + active;
        return active > 0 && (projectedElapsed >= windup && projectedElapsed < end
                || presentedElapsed >= windup && presentedElapsed < end);
    }

    public static boolean protectedActive(String previousPhase, double presentedElapsed, double projectedElapsed, int windup, int active) {
        return (previousPhase.equals("ACTIVE") || previousPhase.equals("RECOVERY"))
                && protectedActive(presentedElapsed, projectedElapsed, windup, active);
    }

    public static boolean ordinaryHandoff(String phase) {
        return Set.of("WINDUP", "ACTIVE", "RECOVERY", "READY").contains(phase);
    }

    /** The new action catches up during anticipation; it cannot blend away its own contact window. */
    public static double entryWindupTicks(double requestedTicks, double elapsedTicks, int windup) {
        return Math.min(Math.max(0, requestedTicks), Math.max(0, windup - Math.max(0, elapsedTicks)));
    }

    /** Latest authoritative intent waiting for a single uninterrupted visual contact window. */
    public static final class PendingAction<T> {
        private T value;
        private long sequence;
        private String action, phase;
        private int remaining;
        private double receivedTick, phaseReceivedTick;
        private final double activeEnd;
        private boolean endpointPresented;
        private double endpointTick;

        public PendingAction(T value, long sequence, String action, String phase, int remaining,
                             double receivedTick, double activeEnd) {
            this.value = value; this.sequence = sequence; this.action = action; this.phase = phase;
            this.remaining = remaining; this.receivedTick = this.phaseReceivedTick = receivedTick;
            this.activeEnd = activeEnd;
        }

        public boolean offer(T next, long nextSequence, String nextAction, String nextPhase, int nextRemaining, double tick) {
            if (nextSequence < sequence || nextSequence == sequence && nextAction.equals(action)
                    && nextPhase.equals(phase) && nextRemaining > remaining
                    || nextSequence == sequence && phaseOrder(nextPhase) < phaseOrder(phase)) return false;
            boolean measured = nextSequence != sequence || !nextAction.equals(action)
                    || !nextPhase.equals(phase) || nextRemaining != remaining;
            value = next; sequence = nextSequence; action = nextAction; phase = nextPhase; remaining = nextRemaining;
            receivedTick = tick;
            if (measured) phaseReceivedTick = tick;
            return true;
        }

        private static int phaseOrder(String phase) {
            return switch (phase) { case "WINDUP" -> 0; case "ACTIVE" -> 1; case "RECOVERY" -> 2; case "READY" -> 3; default -> -1; };
        }

        public T value() { return value; }
        public long sequence() { return sequence; }
        public double receivedTick() { return receivedTick; }
        public double phaseReceivedTick() { return phaseReceivedTick; }
        public double elapsed(double renderTick, int windup, int active, int recovery) {
            double elapsed = attackElapsed(phase, remaining, windup, active, recovery);
            return elapsed < 0 ? -1 : elapsed + Math.max(0, renderTick - phaseReceivedTick);
        }
        public double holdElapsed(double elapsed, double renderTick) {
            if (!endpointPresented && elapsed >= activeEnd) {
                endpointPresented = true; endpointTick = renderTick;
            }
            return Math.min(elapsed, activeEnd);
        }
        public boolean ready(double renderTick) { return endpointPresented && renderTick > endpointTick; }
    }

    /** A bridge follows its own continuous render clock, even if a phase packet arrives late. */
    public static float transitionWeight(double renderTick, double started, double duration) {
        double weight = duration <= 0 ? 1 : Math.clamp((renderTick - started) / duration, 0, 1);
        return (float) (weight * weight * (3 - 2 * weight));
    }

    /** Quintic weight: zero slope and zero acceleration at both ends, unlike a smoothstep. */
    public static float smootherWeight(float weight) {
        float t = Math.clamp(weight, 0, 1);
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /**
     * Offset decay (inertialization) between two clips that do not agree on a handoff pose.
     *
     * Both imported clips define their own first pose, so a weighted mix of two absolute poses
     * still contains the step between them: measured 17-19 degree single-frame jumps on the
     * blade arms at every dual-blade handoff. Instead the step itself becomes a correction that
     * starts at that value and decays to zero over the bridge, so the new action is followed
     * smoothly rather than crossfaded into:
     *   p(t) = offset(t) * start * authored(t),  offset(t) = exp((1 - t) * log(start / last))
     * The outgoing velocity is carried in the same term, which is what makes a fast stroke blend
     * into the next action instead of stopping at the switch frame.
     */
    public static final class OffsetDecay {
        /** How much of the outgoing one-frame velocity the correction may also carry. */
        private static final float VELOCITY_LIMIT = 1f;
        /** Multiple of the measured pose step the correction may reach, so it always converges. */
        private static final float MAXIMUM_CORRECTION = 1.6f;
        private final Map<String, Quaternionf> rotationOffset;
        private final Map<String, Vector3f> translationOffset;

        private OffsetDecay(Map<String, Quaternionf> rotationOffset, Map<String, Vector3f> translationOffset) {
            this.rotationOffset = rotationOffset;
            this.translationOffset = translationOffset;
        }

        /**
         * Correction that removes the pose step between the last presented pose and the first
         * frame of the new action, plus the outgoing velocity that must carry into it.
         */
        public static OffsetDecay of(Map<String, Pose> last, Map<String, Pose> first, Map<String, Pose> previous) {
            var rotations = new HashMap<String, Quaternionf>();
            var translations = new HashMap<String, Vector3f>();
            if (last != null) for (var entry : last.entrySet()) {
                Pose start = first == null ? null : first.get(entry.getKey());
                if (start == null) continue;
                Quaternionf step = new Quaternionf(entry.getValue().fsQuaternion()).normalize()
                        .mul(new Quaternionf(start.fsQuaternion()).normalize().invert()).normalize();
                Quaternionf decay = log(step);
                // The correction only has to bridge the step the two clips actually disagree on;
                // anything larger would need to be unwound inside the same window.
                float cap = magnitude(decay) * MAXIMUM_CORRECTION;
                if (magnitude(decay) > cap) decay.mul(cap / magnitude(decay));
                Pose older = previous == null ? null : previous.get(entry.getKey());
                if (older != null && decay != null) {
                    Quaternionf velocity = log(new Quaternionf(entry.getValue().fsQuaternion()).normalize()
                            .mul(new Quaternionf(older.fsQuaternion()).normalize().invert()).normalize());
                    // Carry the outgoing speed, but never let one fast frame dominate the correction.
                    if (velocity != null) {
                        float limit = magnitude(decay) * VELOCITY_LIMIT, speed = magnitude(velocity);
                        decay.add(speed > limit && speed > 1e-6f ? velocity.mul(limit / speed) : velocity);
                    }
                }
                rotations.put(entry.getKey(), decay);
                Vector3f travel = new Vector3f(entry.getValue().position().vector()).sub(start.position().vector());
                if (older != null) {
                    Vector3f travelStep = new Vector3f(entry.getValue().position().vector()).sub(older.position().vector());
                    float limit = travel.length() * VELOCITY_LIMIT, speed = travelStep.length();
                    travel.add(speed > limit && speed > 1e-6f ? travelStep.mul(limit / speed) : travelStep);
                }
                translations.put(entry.getKey(), travel);
            }
            return new OffsetDecay(Map.copyOf(rotations), Map.copyOf(translations));
        }

        public Map<String, Pose> blend(Map<String, Pose> authored, float weight) {
            float t = Math.clamp(weight, 0, 1);
            if (t >= 1) return authored;
            float decay = 1 - smootherWeight(t);
            var result = new HashMap<String, Pose>();
            for (var entry : authored.entrySet()) {
                Pose pose = entry.getValue();
                Quaternionf rotation = new Quaternionf(pose.fsQuaternion()).normalize();
                Quaternionf step = rotationOffset.get(entry.getKey());
                if (step != null) rotation = exp(new Quaternionf(step).mul(decay)).mul(rotation).normalize();
                Vector3f position = pose.position().vector();
                Vector3f offset = translationOffset.get(entry.getKey());
                if (offset != null) position = new Vector3f(position).add(new Vector3f(offset).mul(decay));
                result.put(entry.getKey(), new Pose(FsvoxAnimation.fromQuaternion(rotation),
                        new V(position.x, position.y, position.z), pose.scale()));
            }
            return Map.copyOf(result);
        }
    }

    /** Length of the rotation vector a logarithm quaternion carries. */
    private static float magnitude(Quaternionf logarithm) {
        return (float) Math.sqrt(logarithm.x * logarithm.x + logarithm.y * logarithm.y + logarithm.z * logarithm.z);
    }

    private static Quaternionf log(Quaternionf rotation) {
        float w = Math.clamp(rotation.w, -1, 1);
        float length = (float) Math.sqrt(rotation.x * rotation.x + rotation.y * rotation.y + rotation.z * rotation.z);
        if (length < 1e-7f) return new Quaternionf();
        float angle = (float) Math.atan2(length, w);
        float scale = angle / length;
        return new Quaternionf(rotation.x * scale, rotation.y * scale, rotation.z * scale, 0);
    }

    private static Quaternionf exp(Quaternionf logarithm) {
        float length = (float) Math.sqrt(logarithm.x * logarithm.x + logarithm.y * logarithm.y + logarithm.z * logarithm.z);
        if (length < 1e-7f) return new Quaternionf();
        float sine = (float) Math.sin(length);
        return new Quaternionf(logarithm.x / length * sine, logarithm.y / length * sine, logarithm.z / length * sine,
                (float) Math.cos(length)).normalize();
    }

    /** Contact preparation uses a nearly uniform turn, with short continuous acceleration ramps. */
    public static float windupAimWeight(double renderTick, double started, double duration) {
        double t = duration <= 0 ? 1 : Math.clamp((renderTick - started) / duration, 0, 1);
        double ramp = .08, velocity = 1 / (1 - ramp);
        if (t < ramp) return (float) (velocity * t * t / (2 * ramp));
        if (t > 1 - ramp) return (float) (1 - velocity * (1 - t) * (1 - t) / (2 * ramp));
        return (float) (velocity * (t - ramp / 2));
    }

    /** Settle before contact without concentrating a short windup's turn into its first
     * few rendered samples. Reserve half a native tick, or a sixth of a shorter window. */
    public static double windupAimTicks(double requestedTicks, double renderElapsedTicks, double authoritativeRemainingTicks) {
        double deadline = Math.max(0, renderElapsedTicks) + Math.max(0, authoritativeRemainingTicks);
        double settle = Math.min(.5, deadline / 6);
        return Math.min(Math.max(0, requestedTicks), deadline - settle);
    }

    public static double aimTransitionTicks(AttackAim.Point from, AttackAim.Point target, double configuredTicks) {
        double angle = Math.hypot(AttackAim.wrap(target.yaw() - from.yaw()), target.pitch() - from.pitch());
        return Math.max(0, configuredTicks) * Math.max(1, angle / 90);
    }

    /** Smoothstep's maximum slope is 1.5; include it in the ready-return angular speed budget. */
    public static double aimTransitionTicks(AttackAim.Point from, AttackAim.Point target, double configuredTicks,
                                            double returnDegreesPerSecond) {
        if (!Double.isFinite(returnDegreesPerSecond) || returnDegreesPerSecond <= 0)
            throw new IllegalArgumentException("Invalid aim return speed");
        double angle = Math.hypot(AttackAim.wrap(target.yaw() - from.yaw()), target.pitch() - from.pitch());
        return Math.max(Math.max(0, configuredTicks), 1.5 * angle / returnDegreesPerSecond * 20);
    }

    public static double aimFraction(String phase, double elapsed, int windup, int active) {
        if (phase.equals("WINDUP")) return 0;
        if (phase.equals("RECOVERY")) return 1;
        return Math.clamp((elapsed - windup) / Math.max(1, active), 0, 1);
    }

    /** Transport the last feasible two-hand grip by this frame's authored stroke. */
    public static Vector3f gripSeed(Vector3f source, Vector3f previousSource, Vector3f previousActual) {
        if (previousSource == null || previousActual == null) return new Vector3f(source);
        return new Vector3f(previousActual).add(source).sub(previousSource);
    }

    /** Retain the feasible chest branch while applying the full authored breathing/lean delta. */
    public static Quaternionf chestSeed(Quaternionf source, Quaternionf previousSource, Quaternionf previousActual) {
        if (previousSource == null || previousActual == null) return new Quaternionf(source);
        return new Quaternionf(source).mul(new Quaternionf(previousSource).invert()).mul(previousActual).normalize();
    }

    /** Relax the local yaw within the current feasible branch; the solver keeps it on that branch's boundary. */
    public static Quaternionf chestSeed(Quaternionf source, Quaternionf previousSource, Quaternionf previousActual,
                                        double elapsedSeconds, double returnDegreesPerSecond) {
        var transported = chestSeed(source, previousSource, previousActual);
        if (previousSource == null || previousActual == null || elapsedSeconds <= 0 || returnDegreesPerSecond <= 0
                || !Double.isFinite(elapsedSeconds) || !Double.isFinite(returnDegreesPerSecond)) return transported;
        var relative = new Quaternionf(source).invert().mul(transported).normalize();
        double yaw = 2 * Math.atan2(relative.y, relative.w);
        yaw = Math.atan2(Math.sin(yaw), Math.cos(yaw));
        // Cap long render gaps as well as high configured speeds so a single seed never
        // crosses an unreachable yaw interval and lands on a disconnected chest branch.
        double step = Math.toRadians(Math.min(10, returnDegreesPerSecond * Math.clamp(elapsedSeconds, 0, .1)));
        double remaining = yaw - Math.copySign(Math.min(Math.abs(yaw), step), yaw);
        return new Quaternionf(source).rotateY((float) remaining).normalize();
    }

    /** Blend unit directions along their shortest spherical arc before solving both grips.
     * An arc leaving the representable +/-80-degree pitch range uses the continuous
     * wrapped yaw/pitch path for the whole bridge, avoiding a yaw flip at a clamped pole. */
    public static AttackAim.Point blendAim(AttackAim.Point from, AttackAim.Point target, float weight) {
        if (from == null || weight >= 1) return target;
        if (weight <= 0) return from;
        if (!Float.isFinite(weight)) throw new IllegalArgumentException("Invalid aim blend weight");
        double fromYaw = Math.toRadians(from.yaw()), fromPitch = Math.toRadians(from.pitch());
        double targetYaw = Math.toRadians(target.yaw()), targetPitch = Math.toRadians(target.pitch());
        double ax = -Math.sin(fromYaw) * Math.cos(fromPitch), ay = -Math.sin(fromPitch), az = Math.cos(fromYaw) * Math.cos(fromPitch);
        double bx = -Math.sin(targetYaw) * Math.cos(targetPitch), by = -Math.sin(targetPitch), bz = Math.cos(targetYaw) * Math.cos(targetPitch);
        double dot = Math.clamp(ax * bx + ay * by + az * bz, -1, 1);
        double tx = bx - dot * ax, ty = by - dot * ay, tz = bz - dot * az;
        double tangentLength = Math.sqrt(tx * tx + ty * ty + tz * tz);
        double radians = Math.atan2(tangentLength, dot);
        boolean antipodal = dot < -1 + 1e-8;
        if (antipodal) {
            // At the antipode the shortest arc has no unique plane. A horizontal
            // tangent tied to the source yaw is deterministic and avoids the poles.
            tx = Math.cos(fromYaw); ty = 0; tz = Math.sin(fromYaw); radians = Math.PI;
        } else if (tangentLength > 1e-12) {
            tx /= tangentLength; ty /= tangentLength; tz /= tangentLength;
        }
        double range = from.distance() + (target.distance() - from.distance()) * weight;
        if (!aimArcFitsPitch(ay, ty, radians))
            return new AttackAim.Point(AttackAim.wrap(from.yaw() + AttackAim.wrap(target.yaw() - from.yaw()) * weight),
                    from.pitch() + (target.pitch() - from.pitch()) * weight, range);
        double x, y, z;
        if (dot > 1 - 1e-8) {
            x = ax + (bx - ax) * weight; y = ay + (by - ay) * weight; z = az + (bz - az) * weight;
        } else {
            double cosine = Math.cos(radians * weight), sine = Math.sin(radians * weight);
            x = ax * cosine + tx * sine; y = ay * cosine + ty * sine; z = az * cosine + tz * sine;
            if (antipodal) {
                // Tiny endpoint perturbations must not choose opposite arc planes.
                // Retain the deterministic arc while meeting the exact target continuously.
                double correction = weight * (double) weight;
                x += (bx + ax) * correction; y += (by + ay) * correction; z += (bz + az) * correction;
            }
        }
        double length = Math.sqrt(x * x + y * y + z * z); x /= length; y /= length; z /= length;
        // Only roundoff can exceed the pitch bound after the whole-arc test above.
        double pitch = Math.clamp(Math.toDegrees(-Math.asin(Math.clamp(y, -1, 1))), -80, 80);
        return new AttackAim.Point(AttackAim.wrap(Math.toDegrees(Math.atan2(-x, z))), pitch, range);
    }
    private static boolean aimArcFitsPitch(double fromY, double tangentY, double radians) {
        double limit = Math.sin(Math.toRadians(80)) + 1e-12;
        if (Math.abs(fromY) > limit || Math.abs(fromY * Math.cos(radians) + tangentY * Math.sin(radians)) > limit) return false;
        double extremum = Math.atan2(tangentY, fromY);
        for (int branch = -1; branch <= 2; branch++) {
            double at = extremum + branch * Math.PI;
            if (at >= 0 && at <= radians && Math.abs(fromY * Math.cos(at) + tangentY * Math.sin(at)) > limit) return false;
        }
        return true;
    }

    /** Upper includes all attachments descending from the torso; legs and root cannot leak into it. */
    public static Map<String, Set<String>> masks(Rig rig) {
        Set<String> upper = descendants(rig, "body");
        // Host rigs may put their head beside the body rather than beneath it.
        upper.addAll(descendants(rig, "head"));
        for (String side : Set.of("right", "left")) upper.addAll(descendants(rig, side + "_arm"));
        Set<String> lower = descendants(rig, "right_leg"); lower.addAll(descendants(rig, "left_leg"));
        upper.removeAll(lower); upper.remove("root"); lower.remove("root");
        return Map.of("upper", Set.copyOf(upper), "lower", Set.copyOf(lower), "full", Set.copyOf(rig.parts().keySet()));
    }

    private static Set<String> descendants(Rig rig, String parent) {
        Set<String> found = new HashSet<>();
        if (rig.parts().containsKey(parent)) found.add(parent);
        boolean added;
        do {
            added = false;
            for (var part : rig.parts().values()) if (found.contains(part.parent())) added |= found.add(part.id());
        } while (added);
        return found;
    }

    public static final class Playback {
        private final Rig rig;
        private final Clip action;
        private final AnimationMixer mixer;
        private final String track, stance;
        private final Set<String> lower;
        private double previousTick = Double.NaN;
        private float movement;

        public Playback(Rig rig, String actionId) {
            this.rig = rig; action = rig.clips().get(actionId);
            if (action == null) throw new IllegalArgumentException("Missing action " + actionId);
            numberTag(action, "foot_yaw_limit", 55, 0, 90);
            var masks = masks(rig); lower = masks.get("lower"); mixer = new AnimationMixer(rig, masks);
            track = mixer.play(actionId, "action", AnimationMixer.Options.defaults(action));
            String base = textTag(action, "stance_clip", DEFAULT_STANCE);
            Clip lowerClip = rig.clips().get(base);
            if (lowerClip != null && !base.equals(actionId)) {
                var defaults = AnimationMixer.Options.defaults(lowerClip);
                stance = mixer.play(base, "stance", new AnimationMixer.Options(0, 1, true,
                        1, 0, defaults.blend(), defaults.rotation(), lower));
                // Full clips own the complete lower pose. Equal-weight stance would average it away.
                if (fullBody()) mixer.setWeight(stance, 0, 0);
            } else stance = null;
        }

        public Clip clip() { return action; }
        public boolean fullBody() { return action.layer().equals("full") || !Set.of("upper", "lower").contains(action.layer()); }
        public void inheritMovement(Playback previous) {
            movement = previous.movement; previousTick = previous.previousTick;
        }

        public Sample sample(double actionSeconds, double stanceSeconds, double renderTick,
                             float walkSpeed, float aimYawDegrees, float defaultMovingLowerWeight, float defaultFootYawLimit) {
            float target = Locomotion.movementTarget(walkSpeed);
            if (Double.isNaN(previousTick)) movement = target;
            else movement = Locomotion.smoothMovement(movement, target, Math.max(0, renderTick - previousTick));
            previousTick = Math.max(Double.isNaN(previousTick) ? renderTick : previousTick, renderTick);
            if (stance != null) mixer.seek(stance, Math.max(0, stanceSeconds), false);
            Map<String, Pose> pose = mixer.seek(track, Math.max(0, actionSeconds), false);
            float movingWeight = movingLowerWeight(action, defaultMovingLowerWeight);
            float yawLimit = numberTag(action, "foot_yaw_limit", defaultFootYawLimit, 0, 90);
            pose = aim(rig, constrainFootYaw(pose, lower, yawLimit), aimYawDegrees, 1 - movement);
            return new Sample(pose, movement, Locomotion.lowerWeight(movement, fullBody(), movingWeight), action.layer());
        }
    }

    /** Aim rotates the upper root, while the stationary legs follow the view by a smooth weight. */
    public static Map<String, Pose> aim(Rig rig, Map<String, Pose> source, float yawDegrees, float lowerAimWeight) {
        if (Math.abs(yawDegrees) < .001f) return source;
        Map<String, Pose> pose = new HashMap<>(source);
        rotateTop(pose, rig, "body", yawDegrees);
        // The standard humanoid rig has these as root children, so no parent yaw gets counted twice.
        for (String id : Set.of("right_leg", "left_leg")) rotateTop(pose, rig, id, yawDegrees * lowerAimWeight);
        return Map.copyOf(pose);
    }

    private static void rotateTop(Map<String, Pose> pose, Rig rig, String id, float degrees) {
        Pose original = pose.get(id); var part = rig.parts().get(id);
        if (original == null || part == null || Math.abs(degrees) < .001f) return;
        // Mixer output is absolute local pose: position already contains the rest hip offset.
        // Adding part.rest() here would count the hip twice (only Clip.sample() returns deltas).
        Quaternionf yaw = new Quaternionf().rotationY((float) Math.toRadians(degrees));
        Vector3f position = yaw.transform(original.position().vector());
        pose.put(id, new Pose(FsvoxAnimation.fromQuaternion(yaw.mul(original.fsQuaternion())),
                new V(position.x, position.y, position.z), original.scale()));
    }

    public static Map<String, Pose> constrainFootYaw(Map<String, Pose> source, Set<String> lower, float maximum) {
        Map<String, Pose> pose = new HashMap<>(source);
        for (String id : Set.of("right_leg", "left_leg")) {
            Pose leg = pose.get(id); if (leg == null || !lower.contains(id)) continue;
            var r = leg.rotation();
            float yaw = (r.y() % 360 + 540) % 360 - 180;
            pose.put(id, new Pose(new V(r.x(), Math.clamp(yaw, -maximum, maximum), r.z()), leg.position(), leg.scale()));
        }
        return Map.copyOf(pose);
    }

    private static String textTag(Clip clip, String name, String fallback) {
        String prefix = name + "=";
        var found = clip.tags().stream().filter(tag -> tag.startsWith(prefix)).toList();
        if (found.size() > 1) throw new IllegalArgumentException("Duplicate action tag " + name);
        if (found.isEmpty()) return fallback;
        String value = found.getFirst().substring(prefix.length()).strip();
        if (value.isEmpty()) throw new IllegalArgumentException("Empty action tag " + name);
        return value;
    }

    public static float numberTag(Clip clip, String name, float fallback, float min, float max) {
        String value = textTag(clip, name, Float.toString(fallback));
        float number;
        try { number = Float.parseFloat(value); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid action tag " + name); }
        if (!Float.isFinite(number) || number < min || number > max) throw new IllegalArgumentException("Out-of-range action tag " + name);
        return number;
    }

    /** Workbench preserves event payloads; tags remain supported for text-authored older resources. */
    public static float movingLowerWeight(Clip clip, float fallback) {
        var events = clip.events().stream().filter(event -> event.name().equals("moving_lower_weight")).toList();
        if (!events.isEmpty()) {
            if (events.size() != 1) return .25f;
            var value = events.getFirst().payload().get("value");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return .25f;
            float weight = value.getAsFloat();
            return Float.isFinite(weight) && weight >= 0 && weight <= 1 ? weight : .25f;
        }
        try { return numberTag(clip, "moving_lower_weight", fallback, 0, 1); }
        catch (IllegalArgumentException invalid) { return .25f; }
    }

    /** Authoritative phase ticks map to editable clip markers, with sub-tick precision. */
    public static double phaseTime(Clip clip, double ticks, double windup, double active, double recovery) {
        double begin = clip.events().stream().filter(event -> event.name().equals("active_begin")).mapToDouble(event -> event.time()).findFirst().orElse(clip.duration() * .35);
        double end = clip.events().stream().filter(event -> event.name().equals("active_end")).mapToDouble(event -> event.time()).findFirst().orElse(clip.duration() * .55);
        if (windup > 0 && ticks < windup) return begin * ticks / windup;
        ticks -= windup;
        if (active > 0 && ticks <= active) return begin + (end - begin) * Math.max(0, ticks) / active;
        ticks -= active;
        return recovery > 0 ? Math.min(clip.duration(), end + (clip.duration() - end) * Math.max(0, ticks) / recovery) : clip.duration();
    }
}
