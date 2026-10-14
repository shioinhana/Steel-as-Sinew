package org.fsrpg.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Authored motion transports the IK correction; history never becomes a stationary action target. */
public final class SpearMotion {
    private static final List<String> BONES = List.of("body", "head", "right_arm", "right_forearm", "right_hand",
            "left_arm", "left_forearm", "left_hand");
    private SpearMotion() {}

    /** Lower the carrying anchor beneath the armpit, preserving each authored stroke. */
    public static Map<String, Matrix4f> underarmSource(Map<String, Matrix4f> source) {
        return underarmSource(source,-12,22);
    }
    public static Map<String, Matrix4f> underarmSource(Map<String, Matrix4f> source,float downCm,float backCm) {
        return underarmSource(source,downCm,backCm,50);
    }
    public static Map<String, Matrix4f> underarmSource(Map<String, Matrix4f> source,float downCm,float backCm,float sideCm) {
        if(!Float.isFinite(downCm)||downCm<-20||downCm>30||!Float.isFinite(backCm)||backCm<0||backCm>80)
            throw new IllegalArgumentException("Invalid underarm carrying offset");
        if(!Float.isFinite(sideCm)||sideCm<0||sideCm>65)throw new IllegalArgumentException("Invalid underarm side offset");
        var result = new HashMap<>(source);
        var offset = source.get("body").transformDirection(new Vector3f(-sideCm,-downCm,-backCm));
        for(String side : List.of("right", "left")) {
            var hand = new Matrix4f(source.get(side+"_hand"));
            hand.setTranslation(hand.getTranslation(new Vector3f()).add(offset));
            result.put(side+"_hand",hand);
        }
        return Map.copyOf(result);
    }
    /** A ready spear stays at the ribs while the authored torso breathes and runs. */
    public static Map<String,Matrix4f> readySource(Map<String,Matrix4f> source,float downCm,float backCm,float sideCm) {
        // The offsets are calibrated from the neutral grip relative to its shoulder;
        // running arm lift must not promote the carried pole above the armpit.
        var inverse=new Matrix4f(source.get("body")).invert();
        var shoulder=inverse.transformPosition(source.get("right_arm").getTranslation(new Vector3f()));
        var goal=source.get("body").transformPosition(new Vector3f(shoulder.x+28.32f-sideCm,shoulder.y-27-downCm,shoulder.z+13.11f-backCm));
        var offset=goal.sub(palm(source.get("right_hand")));
        var result=new HashMap<>(source);
        for(String side:List.of("right","left")) {
            var hand=new Matrix4f(source.get(side+"_hand"));hand.setTranslation(hand.getTranslation(new Vector3f()).add(offset));result.put(side+"_hand",hand);
        }
        return Map.copyOf(result);
    }

    public record Seed(Vector3f rear, Quaternionf chest, Map<String, Matrix4f> continuity,
                       Map<String, Matrix4f> actual, float authoredMotion) {}

    private record Snapshot(Map<String, Matrix4f> source, Map<String, Matrix4f> solved, double tick) {}

    /** Same-frame re-entry always reads the last completed frame, including after an action handoff. */
    public static final class State {
        private double frameTick = Double.NaN;
        private Snapshot committed, previous, anchor;
        private Map<String, Matrix4f> preparedSource;

        public Seed prepare(Map<String, Matrix4f> source, double renderTick, double chestReturnDegreesPerSecond) {
            return prepare(source,renderTick,chestReturnDegreesPerSecond,1);
        }
        public Seed prepare(Map<String, Matrix4f> source, double renderTick, double chestReturnDegreesPerSecond,float fidelity) {
            if (!Double.isFinite(renderTick)) throw new IllegalArgumentException("Invalid spear render time");
            if(!Float.isFinite(fidelity)||fidelity<0||fidelity>1)throw new IllegalArgumentException("Invalid spear motion fidelity");
            if (Double.isNaN(frameTick) || renderTick != frameTick) {
                if (!Double.isNaN(frameTick) && renderTick < frameTick) { committed = null; anchor = null; }
                previous = committed;
                frameTick = renderTick;
            }
            preparedSource = copy(source);
            var sourceChest = rotation(preparedSource.get("body"));
            if (previous == null) return new Seed(palm(preparedSource.get("right_hand")), sourceChest, null, null, 0);

            var transported = new HashMap<String, Matrix4f>();
            for (String bone : BONES) {
                var current = preparedSource.get(bone);
                var oldSource = previous.source().get(bone);
                var oldSolved = previous.solved().get(bone);
                if (current != null && oldSource != null && oldSolved != null)
                    transported.put(bone, new Matrix4f(current).mul(new Matrix4f(oldSource).invert()).mul(oldSolved));
            }
            // Preserve the authored palm stroke and carry its fixed IK offset with
            // the chest. Rotating that offset with the wrist invents a large palm
            // orbit during pronation, even when the authored palm stays stationary.
            var correction = palm(anchor.solved().get("right_hand"))
                    .sub(palm(anchor.source().get("right_hand")));
            var chestDelta = new Matrix4f(preparedSource.get("body"))
                    .mul(new Matrix4f(anchor.source().get("body")).invert());
            var rear = palm(preparedSource.get("right_hand"))
                    .add(chestDelta.transformDirection(correction));
            // A forward stroke retains the original correction anchor. At extreme
            // aim, carry the last safe grip plus the authored palm displacement;
            // a forward anchor must not keep pulling the arm back through the head.
            var carried=palm(previous.solved().get("right_hand"))
                    .add(palm(preparedSource.get("right_hand")).sub(palm(previous.source().get("right_hand"))));
            rear.set(carried.lerp(rear,fidelity));
            var predictedChest = rotation(transported.get("body"));
            double elapsed = Math.max(0, renderTick - previous.tick()) / 20;
            var chest = PoseLayers.chestSeed(sourceChest, sourceChest, predictedChest, elapsed, chestReturnDegreesPerSecond);
            return new Seed(rear, chest, Map.copyOf(transported), copy(previous.solved()), authoredMotion(preparedSource, previous.source()));
        }

        /** Replaces this frame's result without promoting it into the frame's own prior history. */
        public void commit(Map<String, Matrix4f> solved, double renderTick) {
            if (preparedSource == null || renderTick != frameTick)
                throw new IllegalStateException("Prepare the spear frame before committing it");
            committed = new Snapshot(preparedSource, copy(solved), renderTick);
            if (anchor == null) anchor = committed;
        }
    }

    private static Map<String, Matrix4f> copy(Map<String, Matrix4f> source) {
        var result = new HashMap<String, Matrix4f>();
        source.forEach((bone, matrix) -> result.put(bone, new Matrix4f(matrix)));
        return Map.copyOf(result);
    }

    private static Vector3f palm(Matrix4f hand) { return hand.transformPosition(new Vector3f(0, -6.25f, 0)); }
    private static Quaternionf rotation(Matrix4f matrix) { return matrix.getUnnormalizedRotation(new Quaternionf()).normalize(); }

    /** Physical authored displacement in centimeters, with a 20cm lever for angular motion. */
    private static float authoredMotion(Map<String, Matrix4f> current, Map<String, Matrix4f> previous) {
        float distance = palm(current.get("right_hand")).distance(palm(previous.get("right_hand")));
        distance = Math.max(distance, palm(current.get("left_hand")).distance(palm(previous.get("left_hand"))));
        distance = Math.max(distance, current.get("body").getTranslation(new Vector3f())
                .distance(previous.get("body").getTranslation(new Vector3f())));
        float angle = 0;
        for (String bone : List.of("body", "right_hand", "left_hand")) {
            var a = rotation(current.get(bone));
            var b = rotation(previous.get(bone));
            var relative = new Quaternionf(a).invert().mul(b).normalize();
            double sine = Math.sqrt(relative.x * (double) relative.x + relative.y * (double) relative.y
                    + relative.z * (double) relative.z);
            angle = Math.max(angle, 2 * (float) Math.atan2(sine, Math.abs(relative.w)));
        }
        return distance + 20 * angle;
    }
}
