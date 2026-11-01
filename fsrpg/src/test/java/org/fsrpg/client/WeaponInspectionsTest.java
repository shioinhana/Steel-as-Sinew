package org.fsrpg.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fsrpg.combat.AttackAim;
import org.fsvoxel.animation.FsvoxAnimation;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Dense FK checks of the actual independent overlays, including stock/slim solved spear READY. */
public final class WeaponInspectionsTest {
    private static int checks;
    private static double maximumStep, maximumGripError, maximumWaistError;
    public static void main(String[] args) throws Exception {
        for (String style : List.of("rapier", "dual_blade", "spear", "bow")) {
            check(WeaponInspections.actions(style).size() == 3, "three inspections per style");
            String asset = style.equals("spear") ? "spear" : "legacy_" + style;
            ModelDocument model = ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/" + asset + ".fsvoxel"));
            for (boolean slim : List.of(false, true)) {
                Map<String, Pose> base = ready(model, style, slim);
                Map<String, Matrix4f> baseWorld = model.worldMatrices(base);
                for (String id : WeaponInspections.actions(style)) {
                    double duration = WeaponInspections.duration(id);
                    check(WeaponInspections.sample(model, base, id, 0) == base, "exact initial captured READY");
                    check(WeaponInspections.sample(model, base, id, duration) == base, "exact final captured READY");
                    check(WeaponInspections.sample(model, base, id, Double.NaN) == base, "bad clock never contaminates a pose");
                    Map<String, Pose> previous = base;
                    double movement = 0, actionStep = 0, gripError = 0;
                    for (int i = 1; i <= Math.ceil(duration * 120); i++) {
                        double seconds = Math.min(duration, i / 120.0);
                        Map<String, Pose> pose = WeaponInspections.sample(model, base, id, seconds);
                        Map<String, Matrix4f> world = model.worldMatrices(pose);
                        for (String part : base.keySet()) {
                            Pose value = pose.get(part);
                            check(value != null && value.position().vector().isFinite() && value.fsQuaternion().isFinite(), "finite complete pose");
                            double dot = Math.min(1, Math.abs(previous.get(part).fsQuaternion().normalize().dot(value.fsQuaternion().normalize())));
                            double step = Math.toDegrees(2 * Math.acos(dot));
                            maximumStep = Math.max(maximumStep, step);
                            actionStep = Math.max(actionStep, step);
                            check(step < 16, "continuous 120 Hz joint motion " + id + "/" + part + " @ " + seconds + " step=" + step);
                            check(value.scale().equals(base.get(part).scale()), "no bone scale animation");
                            if (part.endsWith("_item")) check(value.equals(base.get(part)), "official item locator remains attached and unchanged");
                        }
                        for (String side : List.of("right", "left")) {
                            float a = world.get(side + "_arm").getTranslation(new Vector3f()).distance(world.get(side + "_forearm").getTranslation(new Vector3f()));
                            float b = world.get(side + "_forearm").getTranslation(new Vector3f()).distance(world.get(side + "_hand").getTranslation(new Vector3f()));
                            float a0 = baseWorld.get(side + "_arm").getTranslation(new Vector3f()).distance(baseWorld.get(side + "_forearm").getTranslation(new Vector3f()));
                            float b0 = baseWorld.get(side + "_forearm").getTranslation(new Vector3f()).distance(baseWorld.get(side + "_hand").getTranslation(new Vector3f()));
                            check(Math.abs(a - a0) < .005 && Math.abs(b - b0) < .005, "arm lengths preserved");
                        }
                        double waistError = AirPoseJoin.waist(model, world).distance(AirPoseJoin.waist(model, baseWorld));
                        maximumWaistError = Math.max(maximumWaistError, waistError);
                        check(waistError < .02, "waist stays joined " + id + " error=" + waistError);
                        movement = Math.max(movement, world.get("right_hand").getTranslation(new Vector3f()).distance(baseWorld.get("right_hand").getTranslation(new Vector3f())));
                        if (style.equals("spear")) {
                            Vector3f rear = world.get("right_hand").transformPosition(new Vector3f(0, -6.25f, 0));
                            Vector3f front = world.get("left_hand").transformPosition(new Vector3f(0, -6.25f, 0));
                            Vector3f axis = world.get("right_hand").transformDirection(new Vector3f(0, 0, 1)).normalize();
                            Vector3f between = new Vector3f(front).sub(rear);
                            double miss = new Vector3f(between).fma(-between.dot(axis), axis).length();
                            maximumGripError = Math.max(maximumGripError, miss); gripError = Math.max(gripError, miss);
                            check(miss < .08, "both palms share the official shaft " + id + " @ " + seconds + " miss=" + miss);
                            check(between.dot(axis) >= 19.9 && between.dot(axis) <= 75.1, "safe positive grip spacing");
                            check(new SpearConstraints.Space(world, new SpearConstraints.Limits(135, 120, 1.5f, 85, 18, true))
                                    .shaftClear(rear, axis), "complete pole clears torso/head " + id + " @ " + seconds);
                        }
                        if (id.equals("dual_inspect_scrape") && seconds >= duration * .20 && seconds <= duration * .62)
                            bladeContact(world);
                        previous = pose;
                    }
                    check(movement > 10, "each action has visible hand movement");
                    System.out.printf("%s slim=%s maximum 120Hz step=%.3f deg, shaft error=%.5f cm%n", id, slim, actionStep, gripError);
                }
            }
        }
        String catalog = Files.readString(Path.of("src/main/resources/assets/fsrpg/animations/weapon_inspections.json"));
        for (String broken : List.of(catalog.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 9"),
                catalog.replaceFirst("\"duration\": 5.8", "\"duration\": -1"),
                catalog.replaceFirst("\"enter\": 0.8", "\"enter\": 8"),
                catalog.replaceFirst("\"body\":", "\"damage\":"))) {
            boolean rejected = false;
            try { WeaponInspections.parse(broken); } catch (RuntimeException expected) { rejected = true; }
            check(rejected, "invalid and combat-like configuration fields rejected");
        }
        check(WeaponInspections.actions("unarmed").isEmpty(), "unarmed has no accidental action");
        check(WeaponInspections.actions("bow_rapid").equals(WeaponInspections.actions("bow"))
                && WeaponInspections.actions("bow_snipe").equals(WeaponInspections.actions("bow")), "both live bow style aliases use the same catalog");
        check(WeaponInspections.duration("missing") == 0, "unknown action has no clock");
        System.out.printf("WeaponInspectionsTest passed: %d checks, step=%.3f deg/120Hz, grip=%.5f cm, waist=%.5f cm%n",
                checks, maximumStep, maximumGripError, maximumWaistError);
    }

    private static void bladeContact(Map<String, Matrix4f> world) {
        Matrix4f right = new Matrix4f(world.get("right_item")).scale(-100, 100, -100)
                .mul(HeldItemTransform.legacyDualItem()).mul(HeldItemTransform.standardDisplay());
        Matrix4f left = new Matrix4f(world.get("left_item")).scale(-100, 100, -100)
                .mul(HeldItemTransform.legacyDualItem()).mul(HeldItemTransform.standardDisplay());
        Vector3f p = right.transformPosition(new Vector3f(3.5f / 16, 3.5f / 16, .5f));
        Vector3f q = left.transformPosition(new Vector3f(3.5f / 16, 3.5f / 16, .5f));
        Vector3f a = right.transformDirection(new Vector3f(1, 1, 0)).normalize();
        Vector3f b = left.transformDirection(new Vector3f(1, 1, 0)).normalize();
        Vector3f delta = new Vector3f(p).sub(q);
        float ab = a.dot(b), ad = a.dot(delta), bd = b.dot(delta);
        float u = (ab * bd - ad) / (1 - ab * ab), v = (bd - ab * ad) / (1 - ab * ab);
        check(u > 10 && u < 50 && v > 10 && v < 50, "scraping contact lies on both official blades");
        check(new Vector3f(p).fma(u, a).distance(new Vector3f(q).fma(v, b)) < .5,
                "the real official blade centerlines stay in contact while rubbing");
    }

    static Map<String, Pose> ready(ModelDocument model, String style, boolean slim) {
        String idle = style.equals("spear") ? "spear_idle" : "legacy_" + style + "_static_idle";
        Map<String, Pose> pose = LegacyPlayback.sample(model, model.rig().clips().get(idle), 0);
        if (style.equals("rapier")) {
            var copy = new HashMap<>(pose);
            Pose body = copy.get("body"), hip = copy.get("hip");
            float hipYaw = hip.fsQuaternion().getEulerAnglesZYX(new Vector3f()).y;
            var angles = body.fsQuaternion().getEulerAnglesZYX(new Vector3f()).add(0, -hipYaw + (float) Math.toRadians(65), 0);
            copy.put("body", new Pose(FsvoxAnimation.fromQuaternion(new Quaternionf().rotationZYX(angles.z, angles.y, angles.x)),
                    body.position(), body.scale()));
            pose = SwordMotion.waist(model, copy);
        } else if (style.equals("bow")) {
            var copy = new HashMap<>(pose);
            Pose root = copy.get("root"), hip = copy.get("hip");
            copy.put("root", new Pose(FsvoxAnimation.fromQuaternion(new Quaternionf().rotationY((float) -Math.PI / 2)), root.position(), root.scale()));
            copy.put("hip", new Pose(new FsvoxAnimation.V(0, 0, 0), hip.position(), hip.scale()));
            pose = copy;
        }
        if (slim) {
            var copy = new HashMap<>(pose);
            for (String side : List.of("right", "left")) {
                Pose old = copy.get(side + "_arm");
                copy.put(side + "_arm", new Pose(old.rotation(), new FsvoxAnimation.V(old.position().x() + (side.equals("right") ? 3.125f : -3.125f),
                        old.position().y(), old.position().z()), old.scale()));
            }
            pose = copy;
        }
        if (style.equals("spear")) {
            var raw = SpearMotion.readySource(model.worldMatrices(pose), -12, 36, 50);
            var seed = new SpearMotion.State().prepare(raw, 0, 90);
            pose = SpearAimPose.apply(model, pose, new AttackAim.Point(0, 0, 10), 0, 1.62f, seed.rear(), seed.chest(),
                    seed.actual(), seed.continuity(), seed.authoredMotion(), new SpearConstraints.Limits(135, 120, 1.5f, 85, 18, true));
        }
        return Map.copyOf(pose);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
