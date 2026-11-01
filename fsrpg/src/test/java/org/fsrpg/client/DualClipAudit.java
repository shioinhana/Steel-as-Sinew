package org.fsrpg.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import org.fsvoxel.animation.FsvoxAnimation.Clip;
import org.fsvoxel.render.ModelLoader;

/** Lists the largest authored joint steps between neighbouring keys of every dual-blade clip. */
public final class DualClipAudit {
    record Step(String clip, String bone, double from, double to, double degrees) {}

    public static void main(String[] args) throws Exception {
        var dual = ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/legacy_dual_blade.fsvoxel"));
        String filter = args.length > 0 ? args[0] : "attack";
        for (Clip clip : dual.rig().clips().values()) {
            if (!clip.id().contains(filter)) continue;
            var times = new TreeSet<Double>();
            clip.tracks().values().forEach(keys -> keys.forEach(key -> times.add(key.time())));
            var ordered = new ArrayList<>(times);
            var steps = new ArrayList<Step>();
            for (int index = 1; index < ordered.size(); index++) {
                double a = ordered.get(index - 1), b = ordered.get(index);
                var from = clip.sample(a);
                var to = clip.sample(b);
                for (var bone : from.keySet()) {
                    if (!to.containsKey(bone)) continue;
                    double degrees = Math.toDegrees(from.get(bone).fsQuaternion().normalize()
                            .difference(to.get(bone).fsQuaternion().normalize(), new org.joml.Quaternionf()).angle());
                    if (degrees > 180) degrees = 360 - degrees;
                    steps.add(new Step(clip.id(), bone, a, b, degrees));
                }
            }
            steps.sort(Comparator.comparingDouble(Step::degrees).reversed());
            System.out.printf("== %s duration=%.4f keys=%d interp=%s%n", clip.id(), clip.duration(), ordered.size(), clip.interpolation());
            System.out.println("   key times: " + ordered);
            for (int i = 0; i < Math.min(8, steps.size()); i++) {
                var s = steps.get(i);
                System.out.printf("   %-14s %.4f -> %.4f  %6.1f deg  (%.1f deg/frame@60)%n", s.bone(), s.from(), s.to(), s.degrees(),
                        s.degrees() / Math.max(1e-6, (s.to() - s.from()) * 60));
            }
        }
    }
}
