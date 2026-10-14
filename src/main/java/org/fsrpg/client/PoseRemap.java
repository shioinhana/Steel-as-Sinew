package org.fsrpg.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Clip;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.Rig;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Server ticks to authored clip time for the imported legacy strokes.
 *
 * The imported 0.1.1 attacks finish their whole swing inside the first fifth of the clip and
 * then hold a static end pose. Stretching that clip linearly across windup + active + recovery
 * therefore parks the blade before the contact window opens and leaves that window motionless.
 * This maps the authored motion onto the authoritative windows instead: hold and pull back
 * during windup, sweep through contact, hold the finish through recovery.
 */
public final class PoseRemap {
    /** Authored motion window of one clip, in clip seconds. */
    public record Knots(double start, double peak, double end) {
        public double length() { return Math.max(1e-4, end - start); }
    }
    /** Authored clip time plus how far to pull the pose back toward the carry grip. */
    public record Attack(double clipTime, float anticipation) {}

    private static final int SAMPLES = 240;
    private static final double MOTION_FLOOR = .05;
    private static final float ANTICIPATION_STRENGTH = .55f;
    /** Clips are immutable, so a window costs one analysis per clip rather than one per actor. */
    private static final Map<String, Knots> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private PoseRemap() {}

    public static Knots knots(Rig rig, Clip clip) {
        return CACHE.computeIfAbsent(rig.id() + "/" + clip.id(), id -> analyse(rig, clip));
    }

    /** Track the point that carries the weapon, so the window follows the blade and not the elbow. */
    private static Knots analyse(Rig rig, Clip clip) {
        if (!clip.tracks().containsKey("body")) throw new IllegalArgumentException("Clip has no torso: " + clip.id());
        List<String> probe = probe(rig);
        double duration = Math.max(1e-4, clip.duration());
        var point = new Vector3f[SAMPLES + 1];
        double[] speed = new double[SAMPLES + 1];
        for (int n = 0; n <= SAMPLES; n++) {
            point[n] = tracked(rig, rig.sampleLocal(List.of(clip), duration * n / SAMPLES, Map.of()), probe);
            if (n > 0) speed[n] = point[n].distance(point[n - 1]) * SAMPLES / duration;
        }
        double peakSpeed = 0;
        for (double s : speed) peakSpeed = Math.max(peakSpeed, s);
        if (peakSpeed <= 0) return whole(clip);
        double floor = peakSpeed * MOTION_FLOOR;
        int first = 0, last = SAMPLES, peakIndex = 1;
        for (int n = 1; n <= SAMPLES; n++) if (speed[n] > floor) { first = n; break; }
        for (int n = SAMPLES; n >= 1; n--) if (speed[n] > floor) { last = n; break; }
        for (int n = 1; n <= SAMPLES; n++) if (speed[n] > speed[peakIndex]) peakIndex = n;
        // A window that never really moves the blade carries no authored stroke worth preserving.
        if (point[first].distance(point[last]) * 6.25f < 1.5) return whole(clip);
        double start = duration * first / SAMPLES, peak = duration * peakIndex / SAMPLES, end = duration * last / SAMPLES;
        return new Knots(Math.max(0, start), Math.min(duration, Math.max(peak, start + 1e-3)),
                Math.min(duration, Math.max(end, peak + 1e-3)));
    }

    private static Knots whole(Clip clip) { return new Knots(0, clip.duration() * .5, clip.duration()); }

    private static List<String> probe(Rig rig) {
        return rig.parts().containsKey("right_item")
                ? List.of("right_item", "left_item", "right_hand", "left_hand", "body")
                : List.of("right_hand", "left_hand", "body");
    }

    private static Vector3f tracked(Rig rig, Map<String, Pose> pose, List<String> probe) {
        var world = rig.worldMatrices(pose);
        var sum = new Vector3f();
        int used = 0;
        for (String id : probe) if (world.containsKey(id)) { sum.add(world.get(id).getTranslation(new Vector3f())); used++; }
        return used == 0 ? sum : sum.div(used);
    }

    /**
     * Authoritative tick offset to authored clip time.
     *
     * The whole authored stroke is placed inside the contact window, so a hit always lands on a
     * moving blade. Windup holds the blade at the authored start and adds a counter-move that
     * decays to nothing by the moment contact begins; recovery keeps the finish. Both windup and
     * contact phases ease, so the bridge between them carries no velocity step.
     */
    public static Attack plan(Knots knots, Clip clip, double tick, double windup, double active, double recovery) {
        if (tick < windup) {
            double t = windup <= 0 ? 1 : Math.clamp(tick / windup, 0, 1);
            return new Attack(knots.start(), (float) Math.pow(1 - t, 2) * ANTICIPATION_STRENGTH);
        }
        if (tick < windup + active) {
            // Contact time is normalised over the authored motion window, so the blade reaches its
            // fastest authored moment where the clip says it is fastest. Only the approach to that
            // moment is eased; everything after it advances with the clip, so a cut does not arrive
            // early and then stand still for the remaining contact frames.
            double t = active <= 0 ? 1 : Math.clamp((tick - windup) / active, 0, 1);
            double peakFraction = Math.clamp((knots.peak() - knots.start()) / knots.length(), .25, .75);
            double progress = knots.start() + (knots.end() - knots.start()) * t;
            double before = knots.start() + (knots.peak() - knots.start()) * ease(t / peakFraction);
            if (t >= peakFraction) return new Attack(progress, 0);
            return new Attack(Math.min(before, progress), 0);
        }
        return new Attack(knots.end(), 0);
    }

    private static double ease(double t) { t = Math.clamp(t, 0, 1); return t * t * (3 - 2 * t); }

    /** Deepest pullback the windup can ask for, exposed so callers can bound it. */
    public static float holdWeight() { return ANTICIPATION_STRENGTH; }

    /** Joint speed in degrees per second at which the clip clock has halved its pace. */
    private static final double SPEED_CAP = 600;
    private static final int WARP_SAMPLES = 480;
    private static final Map<String, Warp> WARPS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Monotone map between clip time and the share of the action elapsed. The authored dual
     * strokes cross about two hundred degrees in four render frames, so the blade is a smear
     * rather than a motion. The clip clock is slowed in proportion to the blade's own speed:
     * the faster the joints turn the slower the clock runs, slow stretches keep their pace,
     * and because the slowing follows the speed curve there is no threshold and no velocity step.
     */
    public static final class Warp {
        private final double duration;
        private final double[] cumulative;
        private Warp(double duration, double[] cumulative) { this.duration = duration; this.cumulative = cumulative; }

        /** Clip time reached when {@code fraction} of the whole action has elapsed. */
        public double clipTime(double fraction) {
            double target = Math.clamp(fraction, 0, 1) * cumulative[WARP_SAMPLES];
            int low = 0, high = WARP_SAMPLES;
            while (high - low > 1) {
                int middle = (low + high) >>> 1;
                if (cumulative[middle] <= target) low = middle; else high = middle;
            }
            double span = cumulative[high] - cumulative[low];
            double within = span <= 1e-12 ? 0 : (target - cumulative[low]) / span;
            return duration * (low + within) / WARP_SAMPLES;
        }

        /** Share of the whole action that has elapsed when the clip reaches {@code seconds}. */
        public double fraction(double seconds) {
            double position = Math.clamp(seconds / duration, 0, 1) * WARP_SAMPLES;
            int low = (int) Math.min(WARP_SAMPLES - 1, Math.floor(position));
            double within = position - low;
            return (cumulative[low] + (cumulative[low + 1] - cumulative[low]) * within) / cumulative[WARP_SAMPLES];
        }
    }

    public static Warp warp(Rig rig, Clip clip) {
        return WARPS.computeIfAbsent(rig.id() + "/" + clip.id(), id -> analyseWarp(rig, clip));
    }

    private static Warp analyseWarp(Rig rig, Clip clip) {
        double duration = Math.max(1e-4, clip.duration());
        var bones = clip.tracks().keySet();
        var poses = new java.util.ArrayList<Map<String, Pose>>();
        for (int n = 0; n <= WARP_SAMPLES; n++) poses.add(rig.sampleLocal(List.of(clip), duration * n / WARP_SAMPLES, Map.of()));
        double[] raw = new double[WARP_SAMPLES + 1];
        for (int n = 1; n <= WARP_SAMPLES; n++) {
            double worst = 0;
            for (String bone : bones) {
                Pose a = poses.get(n - 1).get(bone), b = poses.get(n).get(bone);
                if (a == null || b == null) continue;
                double dot = Math.abs(new Quaternionf(a.fsQuaternion()).normalize().dot(new Quaternionf(b.fsQuaternion()).normalize()));
                worst = Math.max(worst, Math.toDegrees(2 * Math.acos(Math.min(1, dot))));
            }
            raw[n] = worst;
        }
        raw[0] = raw[1];
        double[] cumulative = new double[WARP_SAMPLES + 1];
        double step = duration / WARP_SAMPLES;
        for (int n = 1; n <= WARP_SAMPLES; n++) {
            // Joint speed per clip second. Dividing by the cap makes the clip clock run slower the
            // faster the joints turn, so speed saturates instead of growing without bound, and an
            // authored one-key jump is spread over time instead of landing in a single frame.
            double rate = (raw[n - 1] + raw[n]) * .5 / step;
            cumulative[n] = cumulative[n - 1] + 1 + rate / SPEED_CAP;
        }        return new Warp(duration, cumulative);
    }
    /**
     * Blend the stroke pose toward a hold pose, which supplies the windup's counter-move. The
     * imported strokes start already extended, so without this the windup has nothing to show.
     */
    public static Map<String, Pose> anticipate(Map<String, Pose> stroke, Map<String, Pose> hold, float weight) {
        if (weight <= 0 || hold == null) return stroke;
        var result = new HashMap<String, Pose>();
        for (var entry : stroke.entrySet()) {
            Pose target = hold.getOrDefault(entry.getKey(), entry.getValue());
            result.put(entry.getKey(), entry.getValue().mix(target, Math.clamp(weight, 0, 1), true));
        }
        return Map.copyOf(result);
    }

    /** The carried grip the windup pulls back toward, from the same clip family's idle. */
    public static Map<String, Pose> holdPose(Rig rig, Clip clip) {
        String id = rig.id().contains("dual_blade") ? "legacy_dual_blade_static_idle"
                : rig.id().contains("bow") ? "legacy_bow_static_idle" : "legacy_rapier_static_idle";
        Clip hold = rig.clips().get(id);
        return hold == null ? null : rig.sampleLocal(List.of(hold), .5, Map.of());
    }

    /** Rotation an authored step applies over a clip interval, for follow-through diagnostics. */
    public static float authoredStep(Rig rig, Clip clip, String bone, double from, double to) {
        if (!clip.tracks().containsKey(bone)) return 0;
        var a = rig.sampleLocal(List.of(clip), from, Map.of()).get(bone);
        var b = rig.sampleLocal(List.of(clip), to, Map.of()).get(bone);
        if (a == null || b == null) return 0;
        return (float) Math.toDegrees(2 * Math.acos(Math.clamp(Math.abs(a.fsQuaternion().normalize()
                .dot(new Quaternionf(b.fsQuaternion()).normalize())), -1, 1)));
    }

    @SuppressWarnings("unused")
    private static void unusedTypes(Matrix4f matrix) { }
}
