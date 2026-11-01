package org.fsrpg.client;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Actual native torso geometry, all imported dual/bow clips, both shoulder widths. */
public final class LegacyWaistFitTest {
    private static int checks, samples;
    private static double maximumBeforePixels, maximumAfterPixels, maximumChainErrorCm;

    public static void main(String[] args) throws Exception {
        for (String style : List.of("dual_blade", "bow")) {
            ModelDocument model = load("legacy_" + style);
            for (var clip : model.rig().clips().values()) for (boolean slim : List.of(false, true))
                for (double fraction : new double[]{0, .17, .41, .73, 1}) {
                    Map<String, Pose> pose = LegacyPlayback.sample(model, clip, clip.duration() * fraction);
                    pose = PlayerShoulderFit.apply(model, pose, slim);
                    verify(model, pose, clip.id() + "/" + fraction + "/slim=" + slim);
                    // The runtime side-on bow replaces root/hip yaw after clip sampling.
                    if (style.equals("bow")) verify(model, bowRoot(pose), clip.id() + "/side-on");
                }
            Map<String, Pose> idle = LegacyPlayback.sample(model, model.rig().clips().get("legacy_" + style + "_static_idle"), .31);
            for (float scale : new float[]{.75f, 1.125f, 1.4f}) {
                var scaled = new HashMap<>(idle);
                Pose body = scaled.get("body");
                scaled.put("body", new Pose(body.rotation(), body.position(), new FsvoxAnimation.V(scale, scale, scale)));
                verify(model, scaled, style + "/scaled-body=" + scale);
                // Parent scale must be inverted when placing the shared world delta.
                scaled = new HashMap<>(idle);
                Pose hip = scaled.get("hip");
                scaled.put("hip", new Pose(hip.rotation(), hip.position(), new FsvoxAnimation.V(scale, scale, scale)));
                verify(model, scaled, style + "/scaled-parent=" + scale);
            }
        }
        for (String name : List.of("legacy_rapier", "spear", "rapier", "legacy_dual_blade_fp", "legacy_bow_fp")) {
            ModelDocument model = load(name);
            Map<String, Pose> rest = new HashMap<>();
            model.rig().parts().forEach((id, part) -> rest.put(id, part.rest()));
            check(LegacyWaistFit.apply(model, rest) == rest, "other rigs retain their exact input object: " + name);
        }
        ModelDocument dual = load("legacy_dual_blade");
        Map<String, Pose> rest = new HashMap<>();
        dual.rig().parts().forEach((id, part) -> rest.put(id, part.rest()));
        check(LegacyWaistFit.apply(dual, rest) == rest, "zero correction returns the original map");
        check(maximumBeforePixels > 1, "fixtures include the existing visible source seam");
        System.out.printf("LegacyWaistFitTest passed: %d checks / %d samples, native seam before=%.6f px after=%.8f px, common-chain residual=%.7f cm%n",
                checks, samples, maximumBeforePixels, maximumAfterPixels, maximumChainErrorCm);
    }

    private static Map<String, Pose> bowRoot(Map<String, Pose> source) {
        var pose = new HashMap<>(source);
        Pose root = pose.get("root"), hip = pose.get("hip");
        pose.put("root", new Pose(FsvoxAnimation.fromQuaternion(new Quaternionf().rotationY((float) -Math.PI / 2)),
                root.position(), root.scale()));
        pose.put("hip", new Pose(new FsvoxAnimation.V(0, 0, 0), hip.position(), hip.scale()));
        return pose;
    }

    private static void verify(ModelDocument model, Map<String, Pose> source, String label) {
        Map<String, Matrix4f> before = model.worldMatrices(source);
        Vector3f hips = before.get("right_leg").getTranslation(new Vector3f())
                .add(before.get("left_leg").getTranslation(new Vector3f())).mul(.5f);
        Vector3f correction = new Vector3f(hips).sub(nativeWaistIndependent(before.get("body")));
        maximumBeforePixels = Math.max(maximumBeforePixels, correction.length() / 6.25);
        Map<String, Pose> fit = LegacyWaistFit.apply(model, source);
        Map<String, Matrix4f> after = model.worldMatrices(fit);
        double errorPixels = nativeWaistIndependent(after.get("body")).distance(hips) / 6.25;
        maximumAfterPixels = Math.max(maximumAfterPixels, errorPixels);
        check(errorPixels < .001, "actual unscaled native waist joins hips: " + label + " error=" + errorPixels);
        check(LegacyWaistFit.apply(model, fit) == fit, "fit is idempotent and never accumulates offsets: " + label);
        var upper = PoseLayers.masks(model.rig()).get("upper");
        for (String part : source.keySet()) {
            Pose a = source.get(part), b = fit.get(part);
            check(a.rotation().equals(b.rotation()) && a.scale().equals(b.scale()), "all authored rotations/scales stay exact: " + part);
            if (!upper.contains(part)) {
                check(a == b, "all lower/root/hip poses preserve object identity: " + part);
                check(before.get(part).equals(after.get(part)), "all lower world transforms stay exact: " + part);
            } else {
                Vector3f actual = after.get(part).getTranslation(new Vector3f()).sub(before.get(part).getTranslation(new Vector3f()));
                double residual = actual.distance(correction);
                maximumChainErrorCm = Math.max(maximumChainErrorCm, residual);
                check(residual < .001, "head/arm/hand/official-item share one translation: " + part + " " + label);
                Quaternionf oldRotation = before.get(part).getUnnormalizedRotation(new Quaternionf()).normalize();
                Quaternionf newRotation = after.get(part).getUnnormalizedRotation(new Quaternionf()).normalize();
                check(Math.abs(oldRotation.dot(newRotation)) > .999999, "world orientations are unchanged: " + part);
                String parent = model.rig().parts().get(part).parent();
                if (upper.contains(parent)) {
                    check(a == b, "relative upper chains and item sockets preserve object identity: " + part);
                    Matrix4f oldLocal = new Matrix4f(before.get(parent)).invert().mul(before.get(part));
                    Matrix4f newLocal = new Matrix4f(after.get(parent)).invert().mul(after.get(part));
                    check(oldLocal.equals(newLocal, .001f), "relative chain matrices unchanged: " + part);
                }
            }
        }
        samples++;
    }

    /** Independent reproduction of ModelPart's head-origin placement and twelve-pixel geometry. */
    private static Vector3f nativeWaistIndependent(Matrix4f body) {
        Vector3f top = body.transformPosition(new Vector3f(0, 43.75f, 0));
        Quaternionf q = body.getUnnormalizedRotation(new Quaternionf()).normalize();
        Quaternionf nativeQ = new Quaternionf(q.x, -q.y, -q.z, q.w);
        Vector3f nativeTop = new Vector3f(top.x / 6.25f, (150 - top.y) / 6.25f, -top.z / 6.25f);
        Vector3f nativeBottom = nativeQ.transform(new Vector3f(0, 12, 0)).add(nativeTop);
        return new Vector3f(nativeBottom.x * 6.25f, 150 - nativeBottom.y * 6.25f, -nativeBottom.z * 6.25f);
    }

    private static ModelDocument load(String name) throws Exception {
        return ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/" + name + ".fsvoxel"));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
