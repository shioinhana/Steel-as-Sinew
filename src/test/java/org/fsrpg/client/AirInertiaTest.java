package org.fsrpg.client;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelLoader;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Actual-rig attachment/grip invariants and simulation-clock independence. */
public final class AirInertiaTest {
    private static int checks;
    private static double maximumWaistErrorCm, maximumGripErrorCm, maximumRateDifference;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void near(double expected, double actual, double tolerance, String message) {
        check(Math.abs(expected - actual) <= tolerance, message + ": " + expected + " / " + actual);
    }
    public static void main(String[] args) throws Exception {
        clocks(); lifecycle(); rigs(); spearGrips();
        System.out.printf("AirInertiaTest passed: %d checks, 30/60/120 Hz deviation=%.9f degrees, waist error=%.7f cm, grip residual=%.7f cm%n",
                checks, maximumRateDifference, maximumWaistErrorCm, maximumGripErrorCm);
    }
    private static void clocks() {
        Map<Integer, Map<Integer, AirInertia.Offset>> rates = new HashMap<>();
        for (int hz : new int[]{30, 60, 120}) {
            var state = new AirInertia.State();
            Map<Integer, AirInertia.Offset> common = new HashMap<>();
            double x = 0, y = 0, z = 0, vy = .42;
            int observed = -1;
            for (int frame = 0; frame < hz * 3; frame++) {
                double time = frame * 20.0 / hz;
                int tick = (int)Math.floor(time + 1e-9);
                while (observed < tick) {
                    observed++;
                    double dx = observed < 20 ? .18 : -.12;
                    double dz = observed < 30 ? .26 : .09;
                    if (observed > 0) { x += dx; y += vy; z += dz; vy = (vy - .08) * .98; }
                    state.observeMotion(observed, x, y, z, dx, vy, dz);
                    // Same-tick engine render fields cannot replace the observation.
                    state.observeMotion(observed, x + 1000, y - 1000, z, 0, -99, 0);
                }
                float fraction = (float)(time - tick);
                AirInertia.Offset at = state.sample("spear|stock", time, fraction, true, "READY", 17, 1);
                check(at.equals(state.sample("spear|stock", time, fraction, true, "READY", 17, 1)), "repeat frame is stable");
                check(Math.abs(at.pitchDegrees()) <= 3 && Math.abs(at.rollDegrees()) <= 1.6
                        && Math.abs(at.legPitchDegrees()) <= 4 && Math.abs(at.legRollDegrees()) <= 1.5, "all angles bounded");
                if (frame % (hz / 30) == 0) common.put((int)Math.round(time * 1_000), at);
            }
            rates.put(hz, common);
        }
        for (var sample : rates.get(30).entrySet()) for (int hz : new int[]{60, 120}) {
            AirInertia.Offset a = sample.getValue(), b = rates.get(hz).get(sample.getKey());
            check(b != null, "common sample exists");
            for (double error : new double[]{a.pitchDegrees() - b.pitchDegrees(), a.rollDegrees() - b.rollDegrees(),
                    a.legPitchDegrees() - b.legPitchDegrees(), a.legRollDegrees() - b.legRollDegrees()}) {
                maximumRateDifference = Math.max(maximumRateDifference, Math.abs(error));
                near(0, error, .00001, "render rate cannot change response");
            }
        }
        AirInertia.Offset rising = state(.24, .4, .18), falling = state(.24, -.4, .18), staticAir = state(0, 0, 0);
        check(rising.pitchDegrees() < falling.pitchDegrees() - 1, "up/down inertia changes the held stance");
        check(rising.legPitchDegrees() > falling.legPitchDegrees() + 2, "legs release through the apex");
        check(!staticAir.active(), "no invented oscillator at zero velocity/acceleration");
        // Analytic apex continuity: the target varies through zero without an ascent/fall switch.
        var state = new AirInertia.State(); double y = 0;
        AirInertia.Offset previous = null;
        for (int tick = 0; tick < 20; tick++) {
            y += .20 - tick * .025; state.observeMotion(tick, .1 * tick, y, 0, .1, .2, 0);
            for (int i = 0; i < 12; i++) {
                double time = tick + i / 12.0;
                var offset = state.sample("bow", time, i / 12f, true, "READY", 0, 1);
                if (previous != null) check(Math.abs(offset.pitchDegrees() - previous.pitchDegrees()) < .14, "no apex step");
                previous = offset;
            }
        }
    }
    private static AirInertia.Offset state(double vx, double vy, double vz) {
        var state = new AirInertia.State();
        for (int i = 0; i <= 25; i++) {
            state.observeMotion(i, i * vx, i * vy, i * vz, vx, vy, vz);
            state.sample("weapon", i, 0, true, "READY", 0, 1);
        }
        return state.sample("weapon", 25.5, .5f, true, "READY", 0, 1);
    }
    private static void lifecycle() {
        check(!new AirInertia.Offset(Float.NaN, Float.POSITIVE_INFINITY, 0, 0).active(), "invalid external angles disabled");
        check(new AirInertia.Offset(999, -999, 999, -999).equals(new AirInertia.Offset(3, -1.6f, 4, -1.5f)), "external angles respect hard maxima");
        var state = new AirInertia.State();
        state.observeMotion(1, 0, 0, 0, 0, .4, 0);
        state.sample("rapier", 1, 0, true, "READY", 0, 1);
        state.observeMotion(4, 0, .9, 0, 0, 0, 0);
        check(state.sample("rapier", 4.2, .2f, true, "READY", 0, 1).active(), "air settles into inertia");
        check(!state.sample("rapier", 4.2, .2f, true, "READY", 0, 0).active(), "zero option wins even during a repeated frame");
        double forcedTick = 4.3;
        for (String phase : List.of("GUARDING", "STUNNED", "DODGING", "DRAWING", "SHEATHING")) {
            check(!state.sample("rapier", forcedTick, .3f, true, phase, 0, 1).active(), "forced action has priority: " + phase);
            forcedTick += .1;
        }
        check(!state.sample("rapier", 5, 0, false, "READY", 0, 1).active(), "landing clears effect immediately");
        check(!state.sample("bow", 6, 0, true, "READY", 0, 1).active(), "equipment change starts neutral");
        check(!state.sample("bow", 6.1, .1f, true, "READY", 0, 0).active(), "zero strength exactly disables");
        check(!state.sample("bow", 6.2, .2f, true, "READY", 0, Float.NaN).active(), "non-finite strength disabled");
        state.clear();
        check(!state.sample("bow", 8, 0, true, "READY", 0, 1).active(), "world/death clear loses old velocity");
        state.observeMotion(9, 0, 0, 0, 0, .4, 0); state.sample("bow", 9, 0, true, "READY", 0, 1);
        state.observeMotion(10, 300, 300, 0, 0, .4, 0);
        check(!state.sample("bow", 10, 0, true, "READY", 0, 1).active(), "teleport does not fling pose");
        var ready = new AirInertia.State(); var attack = new AirInertia.State();
        for (int i = 0; i <= 20; i++) {
            ready.observeMotion(i, .1 * i, -.2 * i, .2 * i, .1, -.2, .2);
            attack.observeMotion(i, .1 * i, -.2 * i, .2 * i, .1, -.2, .2);
            ready.sample("weapon", i, 0, true, "READY", 0, 1);
            attack.sample("weapon", i, 0, true, "ACTIVE", 0, 1);
        }
        var a = ready.sample("weapon", 20.5, .5f, true, "READY", 0, 1);
        var b = attack.sample("weapon", 20.5, .5f, true, "ACTIVE", 0, 1);
        near(a.pitchDegrees() * .25, b.pitchDegrees(), .000001, "actual attack retains authored pose priority");
        near(a.legPitchDegrees() * .25, b.legPitchDegrees(), .000001, "actual attack reduces lower cosmetic motion too");
    }
    private static void rigs() throws Exception {
        for (String file : List.of("spear", "legacy_rapier", "legacy_dual_blade", "legacy_bow")) {
            var model = ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/" + file + ".fsvoxel"));
            for (boolean slim : List.of(false, true)) for (var clip : model.rig().clips().values()) {
                if (clip.id().contains("inspect")) continue;
                Map<String, Pose> base = LegacyMotion.applies(model) ? LegacyPlayback.sample(model, clip, clip.duration() * .41) : rest(model);
                if (SwordMotion.applies(model)) base = SwordMotion.waist(model, base);
                base = LegacyWaistFit.apply(model, PlayerShoulderFit.apply(model, base, slim));
                Map<String, Pose> original = Map.copyOf(base);
                var before = model.worldMatrices(base);
                Locomotion.LowerBody lower = file.equals("spear") || file.equals("legacy_rapier") ? lower() : null;
                var zero = AirInertia.apply(model, base, before, lower, AirInertia.Offset.ZERO);
                check(zero.local() == base && zero.world() == before && zero.lower() == lower, "zero effect exact input identity");
                for (float sign : new float[]{-1, 1}) {
                    var offset = new AirInertia.Offset(sign * 3, sign * 1.6f, sign * 4, sign * 1.5f);
                    var result = AirInertia.apply(model, base, before, lower, offset);
                    check(base.equals(original), "input poses remain immutable");
                    check(error(before.get("body"), model.worldMatrices(original).get("body")) == 0, "input matrices remain immutable");
                    Vector3f oldWaist = file.equals("legacy_bow") || file.equals("legacy_dual_blade")
                            ? LegacyWaistFit.nativeWaist(before.get("body")) : before.get("body").getTranslation(new Vector3f());
                    Vector3f newWaist = file.equals("legacy_bow") || file.equals("legacy_dual_blade")
                            ? LegacyWaistFit.nativeWaist(result.world().get("body")) : result.world().get("body").getTranslation(new Vector3f());
                    double waistError = oldWaist.distance(newWaist); maximumWaistErrorCm = Math.max(maximumWaistErrorCm, waistError);
                    check(waistError < .0001, "actual waist pivot stays attached: " + file + "/" + clip.id());
                    Matrix4f oldBodyInverse = new Matrix4f(before.get("body")).invert();
                    Matrix4f newBodyInverse = new Matrix4f(result.world().get("body")).invert();
                    for (String part : List.of("right_arm", "right_forearm", "right_hand", "right_item", "left_arm", "left_forearm", "left_hand", "left_item", "rapier")) {
                        if (!before.containsKey(part)) continue;
                        double gripError = error(new Matrix4f(oldBodyInverse).mul(before.get(part)), new Matrix4f(newBodyInverse).mul(result.world().get(part)));
                        maximumGripErrorCm = Math.max(maximumGripErrorCm, gripError);
                        check(gripError < .0002, "two-hand / official item chain exact relative to chest: " + file + "/" + part);
                    }
                    for (String side : List.of("right_leg", "left_leg")) {
                        check(before.get(side).getTranslation(new Vector3f()).distance(result.world().get(side).getTranslation(new Vector3f())) < .0001, "hip is fixed");
                        check(base.get(side).scale().equals(result.local().get(side).scale()), "leg scale unchanged");
                    }
                    if (lower != null) {
                        check(lower.right().pose().x() == result.lower().right().pose().x() && lower.right().pose().y() == result.lower().right().pose().y()
                                && lower.left().pose().z() == result.lower().left().pose().z(), "native pelvis/waist unchanged");
                        near(lower.right().knee(), result.lower().right().knee(), 0, "existing knee shape preserved");
                        var beforeTorso = new TorsoDeformation(before.get("body"), lower);
                        var afterTorso = new TorsoDeformation(result.world().get("body"), result.lower());
                        near(0, beforeTorso.point(0, 12, 0).distance(afterTorso.point(0, 12, 0)), .00001, "deformed native waist unchanged");
                        for (var leg : List.of(result.lower().right(), result.lower().left())) {
                            var p = leg.pose();
                            var mesh = new LegDeformation(p.x(), p.y(), p.z(), p.xRot(), p.yRot(), p.zRot(), leg.knee(), true, result.lower().pelvisYaw());
                            for (float x : new float[]{-2, 2}) for (float z : new float[]{-2, 2}) {
                                Vector3f expected = new Quaternionf().rotationY(result.lower().pelvisYaw()).transform(new Vector3f(x, 0, z)).add(p.x(), p.y(), p.z());
                                near(0, mesh.point(x, 0, z).mul(16).distance(expected), .00002, "actual hip cap retains waistband connection");
                            }
                        }
                    }
                    var again = AirInertia.apply(model, base, before, lower, offset);
                    check(error(result.world().get("body"), again.world().get("body")) == 0, "unmodified frozen base gives repeatable output without drift");
                    for (Matrix4f matrix : result.world().values()) check(matrix.isFinite(), "finite matrices");
                }
            }
        }
    }
    private static void spearGrips() throws Exception {
        var model = ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/spear.fsvoxel"));
        for (boolean slim : List.of(false, true)) for (float yaw : new float[]{-40, 0, 40}) for (float pitch : new float[]{-35, 0, 35}) {
            Map<String, Pose> source = new PoseLayers.Playback(model.rig(), "spear_idle").sample(.3, .3, 6, 0, 0, .25f, 55).pose();
            source = PlayerShoulderFit.apply(model, source, slim);
            var raw = SpearMotion.readySource(model.worldMatrices(source), -12, 36, 50);
            var reference = SpearAimPose.apply(model, source, new org.fsrpg.combat.AttackAim.Point(yaw, pitch, 10), 0, 1.62f,
                    raw.get("right_hand").transformPosition(new Vector3f(0, -6.25f, 0)), null, null,
                    new SpearConstraints.Limits(135, 120, 1.5f, 85, 18, true));
            var before = model.worldMatrices(reference);
            for (float sign : new float[]{-1, 1}) {
                var result = AirInertia.apply(model, reference, before, lower(), new AirInertia.Offset(sign * 3, sign * 1.6f, sign * 4, sign * 1.5f));
                check(SpearAimPose.hasClearShaft(result.world()), "airborne common inertia retains whole pole clearance slim="+slim+" yaw="+yaw+" pitch="+pitch+" sign="+sign+" originalClear="+SpearAimPose.hasClearShaft(before));
                double oldGrip = SpearRunCarry.leftShaftDistance(before), newGrip = SpearRunCarry.leftShaftDistance(result.world());
                near(oldGrip, newGrip, .0001, "airborne two-hand exact shaft relation");
                for (String side : List.of("right", "left")) {
                    var oldPalm = SpearRunCarry.palm(before, side); var oldElbow = before.get(side + "_forearm").getTranslation(new Vector3f());
                    var newPalm = SpearRunCarry.palm(result.world(), side); var newElbow = result.world().get(side + "_forearm").getTranslation(new Vector3f());
                    near(oldPalm.distance(oldElbow), newPalm.distance(newElbow), .0001, "arm length and wrist attachment exact");
                }
            }
        }
    }
    private static Map<String, Pose> rest(org.fsvoxel.render.ModelDocument model) {
        var result = new HashMap<String, Pose>(); model.rig().parts().forEach((id, part) -> result.put(id, part.rest())); return result;
    }
    private static Locomotion.LowerBody lower() {
        return new Locomotion.LowerBody(
                new Locomotion.SupportedLeg(new Locomotion.PartPose(-1.9f, 12, 0, .3f, .1f, -.03f), .2f, .8f, new Vector3f(-2, 23, 1)),
                new Locomotion.SupportedLeg(new Locomotion.PartPose(1.9f, 12, 0, -.2f, -.1f, .04f), .1f, .9f, new Vector3f(2, 23, -1)), 0, .2f, .8f, .3f, .1f);
    }
    private static double error(Matrix4f a, Matrix4f b) {
        float[] x = a.get(new float[16]), y = b.get(new float[16]); double max = 0;
        for (int i = 0; i < 16; i++) max = Math.max(max, Math.abs(x[i] - y[i])); return max;
    }
}
