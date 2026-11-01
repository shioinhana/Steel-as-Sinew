package org.fsrpg.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fsvoxel.animation.FsvoxAnimation;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsvoxel.render.ModelDocument;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Cosmetic, render-clock inspection choreography. No entities, inputs, packets, damage,
 * resource state or synthetic weapon geometry are involved. Item locator poses are untouched.
 *
 * Pass a captured, fully solved READY pose in actor/model space, after sword remapping or
 * spear aiming. Do not pass the previous inspection result back as the next base.
 */
public final class WeaponInspections {
    public static final String FILE = "weapon_inspections.json";
    private static final String RESOURCE = "/assets/fsrpg/animations/" + FILE;
    private static final float DEG = (float) (Math.PI / 180);
    private static volatile Map<String, Action> catalog = defaults();
    private record Key(double time, float[] body, float[] right, float[] left, float[] pole) {}
    private record Action(String id, String style, String title, double duration, double enter,
                          double exit, List<Key> keys) {}
    private WeaponInspections() {}

    public static List<String> actions(String style) {
        String kind = style == null ? "" : style.replace("legacy_", "");
        if (kind.equals("bow_rapid") || kind.equals("bow_snipe")) kind = "bow";
        final String selected = kind;
        return catalog.values().stream().filter(a -> a.style.equals(selected)).map(Action::id).toList();
    }
    public static double duration(String id) { var a = catalog.get(id); return a == null ? 0 : a.duration; }
    public static String title(String id) { var a = catalog.get(id); return a == null ? id : a.title; }
    public static boolean spear(String id) { var a = catalog.get(id); return a != null && a.style.equals("spear"); }

    /** Validate the complete file before replacing the live immutable catalog. */
    public static synchronized void reload(Path configurationDirectory) throws IOException {
        Path path = configurationDirectory.resolve(FILE);
        if (!Files.exists(path)) {
            Files.createDirectories(configurationDirectory);
            try (var input = WeaponInspections.class.getResourceAsStream(RESOURCE)) {
                if (input == null) throw new IOException("Missing bundled " + FILE);
                Files.write(path, input.readAllBytes());
            }
        }
        Map<String, Action> next = parse(Files.readString(path, StandardCharsets.UTF_8));
        catalog = next;
    }

    /** Exact READY endpoints; arbitrary render sampling frequency, independent of tick frequency. */
    public static Map<String, Pose> sample(ModelDocument model, Map<String, Pose> base, String id, double seconds) {
        Action action = catalog.get(id);
        if (action == null || !Double.isFinite(seconds) || seconds <= 0 || seconds >= action.duration) return base;
        for (String part : List.of("body", "right_arm", "right_forearm", "right_hand",
                "left_arm", "left_forearm", "left_hand")) if (!base.containsKey(part)) return base;
        float weight = smooth((float) Math.min(seconds / action.enter, (action.duration - seconds) / action.exit));
        Key key = interpolate(action, seconds / action.duration);
        Map<String, Pose> pose = new HashMap<>(base);
        Map<String, Matrix4f> original = model.worldMatrices(base);
        choreographBody(model, pose, original, key.body, weight, action.style.equals("spear"));
        Map<String, Matrix4f> world = model.worldMatrices(pose);
        if (action.style.equals("spear")) pole(model, pose, base, original, world, key.pole, weight);
        else {
            hand(model, pose, base, original, world, "right", key.right, weight, action.style);
            hand(model, pose, base, original, world, "left", key.left, weight, action.style);
        }
        return Map.copyOf(pose);
    }

    private static void choreographBody(ModelDocument model, Map<String, Pose> pose,
                                       Map<String, Matrix4f> original, float[] key, float weight, boolean spear) {
        // Rotate the chest about the actual hip midpoint, including the old torso-center rigs.
        // A little opposing head motion and lower-body yaw keep the gesture supported.
        Vector3f pivot = AirPoseJoin.waist(model, original);
        Quaternionf turn = new Quaternionf().rotationYXZ(key[0] * weight * DEG, key[1] * weight * DEG, key[2] * weight * DEG);
        if (spear) {
            Quaternionf old = original.get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
            Quaternionf goal = new Quaternionf().rotationYXZ(key[0] * DEG, key[1] * DEG, key[2] * DEG);
            turn = new Quaternionf(old).slerp(goal, weight).mul(new Quaternionf(old).invert());
        }
        Matrix4f body = new Matrix4f().translation(pivot).rotate(turn).translate(new Vector3f(pivot).negate())
                .mul(original.get("body"));
        localWorld(model, pose, original, "body", body);
        Pose head = pose.get("head");
        if (head != null) {
            Quaternionf q = new Quaternionf(head.fsQuaternion()).rotateY(-key[0] * weight * DEG * .65f)
                    .rotateX(key[3] * weight * DEG);
            put(pose, "head", q);
        }
        // Pelvis/feet carry a small share of each presentation turn. Leg lengths and
        // their shared waist location are preserved; the host retains its knee/sole support.
        for (String side : List.of("right", "left")) {
            Pose leg = pose.get(side + "_leg");
            if (leg != null) put(pose, side + "_leg",
                    new Quaternionf().rotationY(key[0] * weight * DEG * .28f).mul(leg.fsQuaternion()));
        }
    }

    private static void hand(ModelDocument model, Map<String, Pose> pose, Map<String, Pose> base,
                             Map<String, Matrix4f> original, Map<String, Matrix4f> world,
                             String side, float[] key, float weight, String style) {
        Matrix4f hand = original.get(side + "_hand");
        Vector3f authored = new Vector3f(key[0], key[1], key[2]);
        // The bow's source controller rotates its entire rig ninety degrees.
        // Its inspection workspace rotates with that established side-on stance.
        if (style.equals("bow")) original.get("root").transformPosition(authored);
        Vector3f target = hand.getTranslation(new Vector3f()).lerp(authored, weight);
        Quaternionf desired;
        boolean blade = style.equals("dual_blade") || style.equals("rapier") && side.equals("right");
        if (blade) {
            Quaternionf localWeapon = weaponFrame(original, side, style);
            desired = new Quaternionf().rotationYXZ(key[3] * DEG, -key[4] * DEG, key[5] * DEG).mul(localWeapon.invert());
        } else {
            desired = new Quaternionf().rotationYXZ(key[3] * DEG, key[4] * DEG, key[5] * DEG);
            if (style.equals("bow"))
                desired = original.get("root").getUnnormalizedRotation(new Quaternionf()).normalize().mul(desired);
        }
        Quaternionf rotation = hand.getUnnormalizedRotation(new Quaternionf()).normalize().slerp(desired, weight);
        arm(model, pose, original, world, side, target, rotation, weight);
    }

    /** The blade's actual official item-space +X/+Y diagonal, transported through its unchanged locator. */
    private static Quaternionf weaponFrame(Map<String, Matrix4f> world, String side, String style) {
        Matrix4f relative = new Matrix4f(world.get(side + "_hand")).invert();
        Matrix4f item = world.get(side + "_item");
        if (item != null) {
            relative.mul(item).scale(-1, 1, -1);
            if (style.equals("rapier")) relative.mul(HeldItemTransform.legacyRapierItem());
            else relative.mul(HeldItemTransform.legacyDualItem()).mul(HeldItemTransform.standardDisplay());
        } else {
            Vector3f axis = HeldItemTransform.rapierBladeAxis();
            return basis(axis, new Vector3f(1, 0, 0));
        }
        Vector3f axis = relative.transformDirection(new Vector3f(1, 1, 0)).normalize();
        Vector3f face = relative.transformDirection(new Vector3f(0, 0, 1)).normalize();
        return basis(axis, face);
    }

    // In a weapon frame +Z is blade/shaft, +X is the blade face normal.
    private static Quaternionf basis(Vector3f axis, Vector3f normal) {
        Vector3f z = new Vector3f(axis).normalize();
        Vector3f x = new Vector3f(normal).fma(-normal.dot(z), z).normalize();
        Vector3f y = new Vector3f(z).cross(x).normalize();
        return new Quaternionf().setFromNormalized(new Matrix3f(x, y, z));
    }

    private static void pole(ModelDocument model, Map<String, Pose> pose, Map<String, Pose> base,
                             Map<String, Matrix4f> original, Map<String, Matrix4f> world, float[] key, float weight) {
        Vector3f oldRear = original.get("right_hand").transformPosition(new Vector3f(0, -6.25f, 0));
        Vector3f oldFront = original.get("left_hand").transformPosition(new Vector3f(0, -6.25f, 0));
        Vector3f oldAxis = original.get("right_hand").transformDirection(new Vector3f(0, 0, 1)).normalize();
        float oldSpacing = Math.clamp(new Vector3f(oldFront).sub(oldRear).dot(oldAxis), 20, 75);
        Quaternionf targetRotation = new Quaternionf().rotationYXZ(key[3] * DEG, -key[4] * DEG, key[5] * DEG);
        Quaternionf rotation = original.get("right_hand").getUnnormalizedRotation(new Quaternionf()).normalize()
                .slerp(targetRotation, weight);
        Vector3f axis = rotation.transform(new Vector3f(0, 0, 1)).normalize();
        float spacing = oldSpacing + (key[6] - oldSpacing) * weight;
        Vector3f oldCenter = new Vector3f(oldRear).fma(oldSpacing * .5f, oldAxis);
        Vector3f center = oldCenter.lerp(new Vector3f(key[0], key[1], key[2]), weight);
        // Bring the long pole around the outside of the original side carry,
        // rather than carrying its full rear segment diagonally through the ribs.
        center.x -= 25 * (float) Math.sin(Math.PI * weight);
        // A relaxed supporting palm follows the same pole but retains its READY roll.
        Quaternionf leftOld = original.get("left_hand").getUnnormalizedRotation(new Quaternionf()).normalize();
        Quaternionf leftTarget = new Quaternionf(rotation);
        Quaternionf leftRotation = leftOld.slerp(leftTarget, weight);
        Vector3f rightOffset = new Vector3f(axis).mul(-spacing * .5f)
                .sub(rotation.transform(new Vector3f(0, -6.25f, 0)));
        Vector3f leftOffset = new Vector3f(axis).mul(spacing * .5f)
                .sub(leftRotation.transform(new Vector3f(0, -6.25f, 0)));
        // Project the common rigid pole center, never either palm independently.
        // This also accommodates a slim shoulder fit and modest user-authored targets.
        for (int i = 0; i < 16; i++) {
            reachCenter(original, world, "right", center, rightOffset);
            reachCenter(original, world, "left", center, leftOffset);
        }
        Vector3f wristRight = new Vector3f(center).add(rightOffset);
        Vector3f wristLeft = new Vector3f(center).add(leftOffset);
        arm(model, pose, original, world, "right", wristRight, rotation, weight);
        arm(model, pose, original, world, "left", wristLeft, leftRotation, weight);
    }

    private static void reachCenter(Map<String, Matrix4f> original, Map<String, Matrix4f> world,
                                    String side, Vector3f center, Vector3f offset) {
        Vector3f shoulder = world.get(side + "_arm").getTranslation(new Vector3f());
        Vector3f oldShoulder = original.get(side + "_arm").getTranslation(new Vector3f());
        Vector3f oldElbow = original.get(side + "_forearm").getTranslation(new Vector3f());
        Vector3f oldHand = original.get(side + "_hand").getTranslation(new Vector3f());
        float reach = oldShoulder.distance(oldElbow) + oldElbow.distance(oldHand) - .2f;
        Vector3f delta = new Vector3f(center).add(offset).sub(shoulder);
        if (delta.lengthSquared() > reach * reach) center.add(delta.mul(reach / delta.length() - 1));
    }

    /** Two segment analytical IK with a transported READY elbow plane; no searches or aim overrides. */
    private static void arm(ModelDocument model, Map<String, Pose> pose, Map<String, Matrix4f> original,
                            Map<String, Matrix4f> world, String side, Vector3f requested,
                            Quaternionf handRotation, float weight) {
        String upperId = side + "_arm", foreId = side + "_forearm", handId = side + "_hand";
        Vector3f shoulder = world.get(upperId).getTranslation(new Vector3f());
        Vector3f oldShoulder = original.get(upperId).getTranslation(new Vector3f());
        Vector3f oldElbow = original.get(foreId).getTranslation(new Vector3f());
        Vector3f oldWrist = original.get(handId).getTranslation(new Vector3f());
        float a = oldShoulder.distance(oldElbow), b = oldElbow.distance(oldWrist);
        Vector3f delta = new Vector3f(requested).sub(shoulder);
        float actual = delta.length(), distance = Math.clamp(actual, Math.abs(a - b) + .05f, a + b - .05f);
        if (actual < .0001f) return;
        Vector3f axis = delta.div(actual);
        Vector3f wrist = new Vector3f(shoulder).fma(distance, axis);
        float along = (a * a - b * b + distance * distance) / (2 * distance);
        float radius = (float) Math.sqrt(Math.max(0, a * a - along * along));
        Vector3f hint = new Vector3f(oldElbow).sub(oldShoulder);
        // Transporting the elbow pole from the source reach preserves the original branch.
        Vector3f oldAxis = new Vector3f(oldWrist).sub(oldShoulder).normalize();
        new Quaternionf().rotationTo(oldAxis, axis).transform(hint);
        hint.fma(-hint.dot(axis), axis);
        if (hint.lengthSquared() < .0001f)
            hint.set(side.equals("right") ? -1 : 1, -.3f, -.35f).fma(-hint.dot(axis), axis);
        hint.normalize();
        Vector3f elbow = new Vector3f(shoulder).fma(along, axis).fma(radius, hint);
        Vector3f oldUpper = new Vector3f(oldElbow).sub(oldShoulder).normalize();
        Vector3f oldFore = new Vector3f(oldWrist).sub(oldElbow).normalize();
        Vector3f oldHinge = new Vector3f(oldUpper).cross(oldFore);
        if (oldHinge.lengthSquared() < .0001f)
            original.get(upperId).transformDirection(oldHinge.set(1, 0, 0));
        oldHinge.normalize();
        Vector3f hinge = new Vector3f(axis).cross(hint).negate().normalize();
        // A common transported elbow frame avoids independent shortest-arc swings
        // spinning the forearm as its direction passes opposite the READY direction.
        Quaternionf upper = transport(original.get(upperId), oldUpper, oldHinge,
                new Vector3f(elbow).sub(shoulder).normalize(), hinge);
        Quaternionf fore = transport(original.get(foreId), oldFore, oldHinge,
                new Vector3f(wrist).sub(elbow).normalize(), hinge);
        Quaternionf parent = world.get(model.rig().parts().get(upperId).parent())
                .getUnnormalizedRotation(new Quaternionf()).normalize();
        put(pose, upperId, new Quaternionf(parent).invert().mul(upper));
        put(pose, foreId, new Quaternionf(upper).invert().mul(fore));
        put(pose, handId, new Quaternionf(fore).invert().mul(handRotation));
    }

    private static Quaternionf transport(Matrix4f old, Vector3f from, Vector3f oldHinge, Vector3f to, Vector3f hinge) {
        Quaternionf residual = armFrame(from, oldHinge).invert()
                .mul(old.getUnnormalizedRotation(new Quaternionf()).normalize());
        return armFrame(to, hinge).mul(residual).normalize();
    }
    private static Quaternionf armFrame(Vector3f direction, Vector3f hinge) {
        Vector3f y = new Vector3f(direction).negate(), x = new Vector3f(hinge), z = new Vector3f(x).cross(y).normalize();
        return new Quaternionf().setFromNormalized(new Matrix3f(x, y, z));
    }
    private static void localWorld(ModelDocument model, Map<String, Pose> pose, Map<String, Matrix4f> world,
                                   String id, Matrix4f target) {
        String parent = model.rig().parts().get(id).parent();
        Matrix4f local = parent == null || parent.isEmpty() ? target : new Matrix4f(world.get(parent)).invert().mul(target);
        Vector3f at = local.getTranslation(new Vector3f());
        Pose old = pose.get(id);
        pose.put(id, new Pose(FsvoxAnimation.fromQuaternion(local.getUnnormalizedRotation(new Quaternionf()).normalize()),
                new V(at.x, at.y, at.z), old.scale()));
    }
    private static void put(Map<String, Pose> pose, String id, Quaternionf q) {
        Pose old = pose.get(id);
        pose.put(id, new Pose(FsvoxAnimation.fromQuaternion(q.normalize()), old.position(), old.scale()));
    }
    private static float smooth(float value) {
        float t = Math.clamp(value, 0, 1); return t * t * t * (10 + t * (-15 + 6 * t));
    }
    private static Key interpolate(Action action, double time) {
        List<Key> keys = action.keys;
        int i = 0;
        while (i < keys.size() - 2 && time > keys.get(i + 1).time) i++;
        Key a = keys.get(i), b = keys.get(i + 1);
        float t = smooth((float) Math.clamp((time - a.time) / (b.time - a.time), 0, 1));
        return new Key(time, mix(a.body, b.body, t), mix(a.right, b.right, t),
                mix(a.left, b.left, t), mix(a.pole, b.pole, t));
    }
    private static float[] mix(float[] a, float[] b, float t) {
        float[] out = new float[a.length];
        for (int i = 0; i < out.length; i++) out[i] = a[i] + (b[i] - a[i]) * t;
        return out;
    }
    private static Map<String, Action> defaults() {
        try (var input = WeaponInspections.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Missing " + RESOURCE);
            return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    static Map<String, Action> parse(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        fields(root, Set.of("schemaVersion", "actions"), "catalog");
        if (root.get("schemaVersion").getAsInt() != 1) throw new IllegalArgumentException("Unsupported inspection schema");
        Map<String, Action> result = new LinkedHashMap<>();
        for (var entry : root.getAsJsonArray("actions")) {
            JsonObject o = entry.getAsJsonObject();
            fields(o, Set.of("id", "style", "title", "duration", "enter", "exit", "keys"), "action");
            String id = o.get("id").getAsString(), style = o.get("style").getAsString();
            if (!id.matches("[a-z][a-z0-9_]{2,63}") || !Set.of("rapier", "dual_blade", "spear", "bow").contains(style))
                throw new IllegalArgumentException("Invalid inspection id/style " + id);
            String title = o.get("title").getAsString();
            if (title.isBlank() || title.length() > 80) throw new IllegalArgumentException("Invalid inspection title " + id);
            double duration = number(o, "duration", 2, 15), enter = number(o, "enter", .3, 3), exit = number(o, "exit", .3, 3);
            if (enter + exit >= duration) throw new IllegalArgumentException("Inspection has no action interval: " + id);
            List<Key> keys = new ArrayList<>();
            double previous = -1;
            for (var value : o.getAsJsonArray("keys")) {
                JsonObject k = value.getAsJsonObject();
                fields(k, Set.of("t", "body", "right", "left", "pole"), id);
                double at = number(k, "t", 0, 1);
                if (at <= previous) throw new IllegalArgumentException("Unordered inspection keys " + id);
                previous = at;
                float[] body = vector(k, "body", 4, 45), right = vector(k, "right", 6, 720),
                        left = vector(k, "left", 6, 720), pole = vector(k, "pole", 7, 720);
                for (float[] hand : List.of(right, left, pole)) {
                    if (Math.abs(hand[0]) > 95 || hand[1] < 30 || hand[1] > 190 || hand[2] < -35 || hand[2] > 100)
                        throw new IllegalArgumentException("Hand target outside actor workspace: " + id);
                }
                if (pole[6] < 20 || pole[6] > 65) throw new IllegalArgumentException("Invalid inspection grip spacing " + id);
                keys.add(new Key(at, body, right, left, pole));
            }
            if (keys.size() < 3 || keys.size() > 100 || keys.getFirst().time != 0 || keys.getLast().time != 1)
                throw new IllegalArgumentException("Inspection needs 3–100 keys including 0/1: " + id);
            Action action = new Action(id, style, title, duration, enter, exit, List.copyOf(keys));
            if (result.putIfAbsent(id, action) != null) throw new IllegalArgumentException("Duplicate inspection " + id);
        }
        if (result.isEmpty() || result.size() > 64) throw new IllegalArgumentException("Invalid inspection action count");
        return java.util.Collections.unmodifiableMap(result);
    }
    private static float[] vector(JsonObject object, String name, int count, float bound) {
        JsonArray values = object.getAsJsonArray(name);
        if (values == null || values.size() != count) throw new IllegalArgumentException("Invalid inspection " + name);
        float[] result = new float[count];
        for (int i = 0; i < count; i++) {
            result[i] = values.get(i).getAsFloat();
            if (!Float.isFinite(result[i]) || Math.abs(result[i]) > bound) throw new IllegalArgumentException("Invalid inspection number " + name);
        }
        return result;
    }
    private static double number(JsonObject object, String name, double low, double high) {
        double value = object.get(name).getAsDouble();
        if (!Double.isFinite(value) || value < low || value > high) throw new IllegalArgumentException("Invalid inspection " + name);
        return value;
    }
    private static void fields(JsonObject object, Set<String> allowed, String context) {
        for (String key : object.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown " + context + " field " + key);
    }
}
