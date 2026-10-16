package org.fsrpg.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fsrpg.combat.AttackAim;
import org.fsrpg.combat.SpearAimField;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Rendered geometry inside the admitted forward field, with the production motion history. */
public final class SpearForwardFieldPoseTest {
    private static final int HZ = 60;
    private static final boolean UNDERARM = Boolean.getBoolean("fsrpg.underarmTest");
    private static final double FIELD_YAW = Double.parseDouble(System.getProperty("fsrpg.forwardTestYaw", Double.toString(SpearAimField.MAX_YAW_DEGREES)));
    private static final double FIELD_PITCH = Double.parseDouble(System.getProperty("fsrpg.forwardTestPitch", Double.toString(SpearAimField.MAX_PITCH_DEGREES)));
    private static final List<String> SIDES = List.of("right", "left");
    private static final Map<String, JsonObject> GROUPS = new LinkedHashMap<>();
    private static final JsonArray FAILURES = new JsonArray();
    private static int poses, checks, failures;
    private static void check(boolean ok, String group, int frame, String message) {
        checks++;
        if (!ok) {
            failures++;
            JsonObject summary = GROUPS.get(group);
            summary.addProperty("failures", summary.has("failures") ? summary.get("failures").getAsInt() + 1 : 1);
            if (FAILURES.size() < 80) {
                JsonObject error = new JsonObject(); error.addProperty("group", group); error.addProperty("frame", frame);
                error.addProperty("error", message); FAILURES.add(error);
            }
        }
    }
    private static void maximum(JsonObject group, String key, double value) {
        if (!group.has(key) || group.get(key).getAsDouble() < value) group.addProperty(key, value);
    }
    private static void minimum(JsonObject group, String key, double value) {
        if (!group.has(key) || group.get(key).getAsDouble() > value) group.addProperty(key, value);
    }
    private static Vector3f palm(Map<String, Matrix4f> world, String side) {
        return world.get(side + "_hand").transformPosition(new Vector3f(0, -6.25f, 0));
    }
    private static Vector3f direction(Matrix4f matrix, float x, float y, float z) {
        return matrix.transformDirection(new Vector3f(x, y, z)).normalize();
    }
    private static Map<String, Pose> slim(Map<String, Pose> source, boolean narrow) {
        if (!narrow) return source;
        Map<String, Pose> changed = new HashMap<>(source);
        for (String side : SIDES) {
            Pose old = source.get(side + "_arm"); V p = old.position();
            changed.put(side + "_arm", new Pose(old.rotation(),
                    new V(p.x() + (side.equals("right") ? 3.125f : -3.125f), p.y(), p.z()), old.scale()));
        }
        return Map.copyOf(changed);
    }
    private static AttackAim.Point aim(double bodyYaw, double relativeYaw, double pitch, double depth) {
        double distance = Math.min(96, depth / (Math.cos(Math.toRadians(relativeYaw)) * Math.cos(Math.toRadians(pitch))));
        return new AttackAim.Point(AttackAim.wrap(bodyYaw + relativeYaw), pitch, distance);
    }

    private static final class Track {
        final ModelDocument model;
        final String group;
        final SpearMotion.State history = new SpearMotion.State();
        Map<String, Matrix4f> previous;
        int count;
        Track(ModelDocument model, String group) {
            this.model = model; this.group = group;
            JsonObject summary = new JsonObject(); summary.addProperty("sampleHz", HZ); GROUPS.put(group, summary);
        }
        void sample(Map<String, Pose> source, AttackAim.Point aim, double tick, float bodyYaw, boolean ready) {
            int frame = count++;
            JsonObject metrics = GROUPS.get(group);
            double yaw = Math.toRadians(AttackAim.wrap(aim.yaw() - bodyYaw));
            double pitch = Math.toRadians(aim.pitch());
            Vector3f target = new Vector3f((float) (-Math.sin(yaw) * Math.cos(pitch) * aim.distance() * 100),
                    (float) (162 - Math.sin(pitch) * aim.distance() * 100),
                    (float) (Math.cos(yaw) * Math.cos(pitch) * aim.distance() * 100));
            // The independent plane check uses the final finite eye-to-target displacement.
            check(Math.abs(Math.toDegrees(yaw)) <= FIELD_YAW + .00001 && Math.abs(aim.pitch()) <= FIELD_PITCH + .00001,
                    group, frame, "Fixture leaves the permitted direction field");
            check(target.z >= 450 - .001, group, frame, "Finite target crosses the minimum 4.5m forward plane");
            Map<String, Matrix4f> raw = model.worldMatrices(source);
            if(UNDERARM)raw=ready?SpearMotion.readySource(raw,-12,22,50):SpearMotion.underarmSource(raw);
            var seed = history.prepare(raw, tick, ready ? 90 : 0, ready ? SpearAimPose.authoredFidelity(aim, bodyYaw) : 1);
            Map<String, Pose> posed;
            try {
                posed = SpearAimPose.apply(model, source, aim, bodyYaw, 1.62f, seed.rear(), seed.chest(),
                        seed.actual(), seed.continuity(), seed.authoredMotion(), UNDERARM ? new SpearConstraints.Limits(135,120,1.5f,85,18,true).withCarry(ready) : SpearConstraints.Limits.DEFAULT);
            } catch (RuntimeException error) {
                check(false, group, frame, "Solver throws: " + error); return;
            }
            Map<String, Matrix4f> world = model.worldMatrices(posed); poses++;
            if(Boolean.getBoolean("fsrpg.tuckTrace")&&group.equals("sprint-field-perimeter-slim")&&frame>=14&&frame<=17) {
                JsonObject trace=new JsonObject();trace.addProperty("frame",frame);trace.add("aim",new GsonBuilder().create().toJsonTree(aim));
                for(var entry:Map.of("raw",raw,"world",world,"previous",previous).entrySet()) {
                    var matrices=new JsonObject();entry.getValue().forEach((name,matrix)->matrices.add(name,new GsonBuilder().create().toJsonTree(matrix.get(new float[16]))));
                    trace.add(entry.getKey(),matrices);
                }
                try {Files.writeString(Path.of("verification/spear-underarm-turn-20261014/trace-"+frame+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(trace));}catch(Exception error){throw new RuntimeException(error);}
            }
            history.commit(world, tick);
            Vector3f chestForward = direction(world.get("body"), 0, 0, 1);
            if(UNDERARM) {
                var inverse=new Matrix4f(world.get("body")).invert();
                var elbow=inverse.transformPosition(world.get("right_forearm").getTranslation(new Vector3f()));
                var shoulder=inverse.transformPosition(world.get("right_arm").getTranslation(new Vector3f()));
                var rear=inverse.transformPosition(palm(world,"right"));
                maximum(metrics,"maximumRearPalmHeightFromShoulderCm",rear.y-shoulder.y);
                maximum(metrics,"maximumRightElbowForwardFromShoulderCm",elbow.z-shoulder.z);
                minimum(metrics,"minimumRightElbowForwardFromShoulderCm",elbow.z-shoulder.z);
                var shaft=direction(world.get("right_hand"),0,0,1);
                var space=new SpearConstraints.Space(world,new SpearConstraints.Limits(135,120,1.5f,85,18,true));
                check(space.shaftClear(palm(world,"right"),shaft),group,frame,"Complete spear shaft intersects inflated rendered torso/head box");
            }
            double heading = Math.toDegrees(Math.atan2(chestForward.x, chestForward.z));
            maximum(metrics, "maximumAbsoluteChestHeadingDegrees", Math.abs(heading));
            minimum(metrics, "minimumChestHeadingDegrees", heading); maximum(metrics, "maximumChestHeadingDegrees", heading);
            // Leave a full degree before the rear half-plane, including the tilted authored source.
            double constrainedHeading=UNDERARM?AttackAim.wrap(heading-Math.toDegrees(Math.atan2(target.x,target.z))):heading;
            check(Math.abs(constrainedHeading) <= 89.03, group, frame,
                    "Rendered torso exceeds the forward89deg chest boundary: heading=" + heading);
            Vector3f rawUp = direction(raw.get("body"), 0, 1, 0), solvedUp = direction(world.get("body"), 0, 1, 0);
            double upDifference = Math.toDegrees(Math.acos(Math.clamp(rawUp.dot(solvedUp), -1, 1)));
            maximum(metrics, "maximumAuthoredChestUpDifferenceDegrees", upDifference);
            double permittedBank=UNDERARM?Math.min(18,Math.max(0,Math.abs(aim.pitch())-20)*.9):0;
            check(upDifference<=permittedBank+.05, group, frame, "Forward chest discards authored pitch/bank beyond the configured aim bank: " + upDifference);
            Vector3f rear = palm(world, "right"), front = palm(world, "left");
            Vector3f shaft = direction(world.get("right_hand"), 0, 0, 1);
            if(UNDERARM)check(chestForward.x*shaft.x+chestForward.z*shaft.z>0,group,frame,"Chest turns away from the actual finite pole axis");
            Vector3f separation = new Vector3f(front).sub(rear);
            double spacingError = Math.abs(separation.length() - 58);
            double poleError = new Vector3f(separation).cross(shaft).length();
            double targetError = new Vector3f(target).sub(rear).cross(shaft).length();
            maximum(metrics, "maximumGripSpacingErrorCm", spacingError);
            maximum(metrics, "maximumGripPoleErrorCm", poleError);
            maximum(metrics, "maximumFiniteTargetRayErrorCm", targetError);
            check(spacingError <= .03, group, frame, "Palms lose exact 58cm grip spacing: " + spacingError);
            check(poleError <= .03 && separation.dot(shaft) > 0, group, frame, "Front hand loses the actual pole line: " + poleError);
            check(targetError <= .04 && new Vector3f(target).sub(rear).dot(shaft) > 0, group, frame,
                    "Actual pole does not aim at the finite clicked target: " + targetError);
            for (String side : SIDES) {
                Matrix4f shoulder = world.get(side + "_arm"), elbow = world.get(side + "_forearm"), hand = world.get(side + "_hand");
                Vector3f a = shoulder.getTranslation(new Vector3f()), b = elbow.getTranslation(new Vector3f()), c = hand.getTranslation(new Vector3f());
                Vector3f upper = new Vector3f(b).sub(a), fore = new Vector3f(c).sub(b);
                check(Math.abs(upper.length() - 37.5) <= .01 && Math.abs(fore.length() - 25) <= .01,
                        group, frame, "Actual arm segment lengths change: " + side);
                upper.normalize(); fore.normalize();
                // Signed flexion from world link geometry; a reversed hinge has a negative cross product.
                double bend = Math.toDegrees(Math.atan2(new Vector3f(upper).cross(fore).dot(direction(shoulder, 1, 0, 0)), upper.dot(fore)));
                minimum(metrics, "minimum" + side + "ElbowDegrees", bend); maximum(metrics, "maximum" + side + "ElbowDegrees", bend);
                check(bend >= 9.97 && bend <= 135.03, group, frame, "Actual elbow is reversed/out of 10..135deg: " + side + "=" + bend);
                Vector3f fingers = direction(hand, 0, -1, 0), palmPole = direction(hand, 0, 0, 1);
                check(fore.dot(fingers) >= .99999, group, frame, "Wrist folds instead of continuing the forearm: " + side);
                check(Math.abs(fingers.dot(palmPole)) <= .0001 && (UNDERARM&&side.equals("left")||palmPole.dot(shaft) >= .99999),
                        group, frame, "Rear fist controls the pole axis; front guide stays neutral on the same pole: " + side);
                Vector3f foreZ = direction(elbow, 0, 0, 1), foreY = direction(elbow, 0, 1, 0);
                double twist = Math.toDegrees(Math.atan2(new Vector3f(foreZ).cross(palmPole).dot(foreY), foreZ.dot(palmPole)));
                maximum(metrics, "maximum" + side + "WristPronationDegrees", Math.abs(twist));
                check(Math.abs(twist) <= 150.03, group, frame, "Actual wrist twist exceeds hard150deg: " + side + "=" + twist);
            }
            if (previous != null) {
                for (String bone : List.of("body", "head", "right_arm", "right_forearm", "right_hand", "left_arm", "left_forearm", "left_hand")) {
                    Quaternionf old = previous.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize();
                    Quaternionf current = world.get(bone).getUnnormalizedRotation(new Quaternionf()).normalize();
                    double step = Math.toDegrees(2 * Math.acos(Math.clamp(Math.abs(old.dot(current)), 0, 1)));
                    maximum(metrics, "maximumActualJointStepDegrees", step);
                    check(step < 35, group, frame, "Continuous legal aim/motion creates a >=35deg joint jump: " + bone + "=" + step);
                }
            }
            previous = world; metrics.addProperty("poses", count);
        }
    }

    private static void grid(ModelDocument model, boolean slim) {
        for (String action : List.of("spear_idle", "spear_thrust", "spear_sweep")) {
            double seconds = action.equals("spear_idle") ? 0 : action.equals("spear_thrust") ? .405 : .47;
            Map<String, Pose> source = slim(new PoseLayers.Playback(model.rig(), action).sample(seconds, 0, 0, 0, 0, .25f, 55).pose(), slim);
            for (double depth : new double[]{4.5, 10, 60}) {
                String group = "grid-" + action + "-depth" + depth + (slim ? "-slim" : "-stock");
                Track track = new Track(model, group);
                for (double yaw : new double[]{-FIELD_YAW, -FIELD_YAW / 2, 0, FIELD_YAW / 2, FIELD_YAW})
                    for (double pitch : new double[]{-FIELD_PITCH, -FIELD_PITCH / 2, 0, FIELD_PITCH / 2, FIELD_PITCH}) {
                    // Each discrete corner/interior pose starts independently; sequential invariants belong to trajectories below.
                    track.previous = null;
                    track.sample(source, aim(0, yaw, pitch, depth), -track.count, 0, action.equals("spear_idle"));
                }
            }
        }
    }

    private static void motion(ModelDocument model, boolean slim, String kind) {
        motion(model, slim, kind, false);
        motion(model, slim, kind, true);
    }
    private static void motion(ModelDocument model, boolean slim, String kind, boolean perimeter) {
        Track track = new Track(model, kind + (perimeter ? "-field-perimeter" : "-field-interior") + (slim ? "-slim" : "-stock"));
        PresentationMotion movement = new PresentationMotion();
        PoseLayers.Playback idle = new PoseLayers.Playback(model.rig(), "spear_idle");
        PoseLayers.Playback run = new PoseLayers.Playback(model.rig(), "spear_run");
        double vx = kind.equals("left") ? .13 : kind.equals("right") ? -.13 : 0;
        double vz = kind.equals("idle") || vx != 0 ? 0 : kind.equals("back") ? -.13 : kind.equals("sprint") ? .28 : .13;
        for (int frame = 0; frame <= HZ * 3; frame++) {
            double tick = frame / 3.; long simulationTick = frame / 3; double seconds = frame / (double) HZ;
            float speed = vx == 0 && vz == 0 ? 0 : .65f;
            movement.observePosition(simulationTick, vx * simulationTick, vz * simulationTick, vx, vz);
            var sampled = idle.sample(seconds, seconds, tick, speed, 0, .25f, 55);
            movement.advance(tick, vx, vz, 0, kind.equals("sprint"), true, sampled.movingWeight(), 1.4f);
            Map<String, Pose> source = sampled.pose();
            if (kind.equals("sprint")) source = PresentationMotion.blend(source,
                    run.sample(seconds, seconds, tick, speed, 0, .25f, 55).pose(), movement.runWeight());
            source = movementStack(model, source, sampled.movingWeight(), sampled.lowerBodyWeight(), movement, tick, speed, 1);
            double yaw = FIELD_YAW * Math.sin(seconds * Math.PI * 2 / 3), pitch = FIELD_PITCH * Math.sin(seconds * Math.PI * 4 / 3);
            double depth = 4.5 + 20 * (.5 - .5 * Math.cos(seconds * Math.PI * 2 / 3));
            if (perimeter) {
                int segment = frame / 45 % 4;
                double u = (frame % 45) / 45.; u = u * u * (3 - 2 * u);
                double[] cornerYaw = {-FIELD_YAW, FIELD_YAW, FIELD_YAW, -FIELD_YAW};
                double[] cornerPitch = {-FIELD_PITCH, -FIELD_PITCH, FIELD_PITCH, FIELD_PITCH};
                yaw = cornerYaw[segment] + (cornerYaw[(segment + 1) % 4] - cornerYaw[segment]) * u;
                pitch = cornerPitch[segment] + (cornerPitch[(segment + 1) % 4] - cornerPitch[segment]) * u;
                depth = 4.5;
            }
            track.sample(slim(source, slim), aim(0, yaw, pitch, depth), tick, 0, true);
        }
    }

    /** Full source clips sampled at60Hz, including boundary corners that a sine path
     * does not visit simultaneously. Every pose begins with an independent IK history. */
    private static void clipPhases(ModelDocument model, boolean slim) {
        for (String action : List.of("spear_idle", "spear_run", "spear_thrust", "spear_sweep")) {
            var clip = model.rig().clips().get(action);
            Track track = new Track(model, "full-phase-corners-" + action + (slim ? "-slim" : "-stock"));
            int samples = (int) Math.ceil(clip.duration() * HZ);
            for (int n = 0; n <= samples; n++) {
                double seconds = Math.min(clip.duration(), n / (double) HZ);
                Map<String, Pose> source = slim(new PoseLayers.Playback(model.rig(), action)
                        .sample(seconds, seconds, 0, 0, 0, .25f, 55).pose(), slim);
                for (double depth : new double[]{4.5, 10, 60})
                    for (double yaw : new double[]{-FIELD_YAW, FIELD_YAW})
                        for (double pitch : new double[]{-FIELD_PITCH, FIELD_PITCH}) {
                            track.previous = null;
                            track.sample(source, aim(0, yaw, pitch, depth), -track.count, 0,
                                    action.equals("spear_idle") || action.equals("spear_run"));
                        }
            }
        }
    }

    private static Map<String, Pose> movementStack(ModelDocument model, Map<String, Pose> source, float moving, float lowerWeight,
                                                   PresentationMotion movement, double tick, float speed, float strength) {
        float phase = movement.gaitPhase(tick, moving, lowerWeight, .72f);
        source = PresentationMotion.stride(source, phase, moving, strength == 1 ? 1 : .25f);
        source = movement.applyLean(source, 4, strength, 1);
        var balance = movement.balance(6, strength);
        var hips = PresentationMotion.withAuthoredHipShift(source, model.rig().parts().get("body").rest().position(), balance);
        source = PresentationMotion.shiftTorso(source, balance);
        var lower = model.worldMatrices(source);
        var supported = Locomotion.supported(lower.get("right_leg"), lower.get("left_leg"), lowerWeight,
                phase, speed, moving, movement.lateralLean(), movement.forwardLean(), .72f, 48, hips,
                source.get("body").position().y() - model.rig().parts().get("body").rest().position().y(), movement.gaitLane());
        return PresentationMotion.shiftTorso(source, new PresentationMotion.Balance(0, -supported.pelvisDropCm(), 0));
    }

    private static void attacks(ModelDocument model, boolean slim) {
        Track track = new Track(model, "moving-action-handoffs" + (slim ? "-slim" : "-stock"));
        PresentationMotion movement = new PresentationMotion();
        PoseLayers.Playback playback = null;
        String previousAction = "";
        Map<String, Pose> previousSource = null, transitionFrom = null;
        double transitionStarted = 0, transitionTicks = 0;
        for (int frame = 0; frame <= HZ * 4; frame++) {
            double tick = frame / 3.; int cycle = frame % 120;
            String action = cycle < 18 || cycle >= 96 ? "spear_idle" : frame < 120 ? "spear_thrust" : "spear_sweep";
            boolean ready = action.equals("spear_idle");
            double elapsed = ready ? -1 : (cycle - 18) / 3.;
            int windup = action.equals("spear_sweep") ? 6 : 5;
            int active = action.equals("spear_sweep") ? 10 : 4;
            int recovery = action.equals("spear_sweep") ? 10 : 17;
            if (!action.equals(previousAction)) {
                PoseLayers.Playback next = new PoseLayers.Playback(model.rig(), action);
                if (playback != null) next.inheritMovement(playback);
                playback = next; transitionFrom = previousSource; transitionStarted = tick;
                transitionTicks = PoseLayers.transitionTicks(playback.clip(), .12, windup, !ready);
                previousAction = action;
            }
            double time = ready ? tick / 20 : PoseLayers.phaseTime(playback.clip(), elapsed, windup, active, recovery);
            var sampled = playback.sample(time, tick / 20, tick, .65f, 0, .25f, 55);
            Map<String, Pose> source = sampled.pose();
            if (transitionFrom != null) source = PresentationMotion.blend(transitionFrom, source,
                    PoseLayers.transitionWeight(tick, transitionStarted, transitionTicks));
            previousSource = source;
            movement.observePosition(frame / 3, 0, frame / 3 * .13, 0, .13);
            movement.advance(tick, 0, .13, 0, false, true, sampled.movingWeight(), 1.4f);
            source = movementStack(model, source, sampled.movingWeight(), sampled.lowerBodyWeight(), movement, tick, .65f, ready ? 1 : .65f);
            double seconds = frame / (double) HZ;
            double yaw = FIELD_YAW * Math.sin(seconds * Math.PI / 2), pitch = FIELD_PITCH * Math.sin(seconds * Math.PI);
            track.sample(slim(source, slim), aim(0, yaw, pitch, 4.5), tick, 0, ready);
        }
    }

    private static String sha(Class<?> type) throws Exception {
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }
    private static void bridges(ModelDocument model, boolean slim) {
        for (double pitch : new double[]{-FIELD_PITCH, FIELD_PITCH}) {
            String group = "spherical-field-edge-" + pitch + (slim ? "-slim" : "-stock");
            Track track = new Track(model, group);
            var playback = new PoseLayers.Playback(model.rig(), "spear_idle");
            AttackAim.Point from = aim(0, -FIELD_YAW, pitch, 4.5), to = aim(0, FIELD_YAW, pitch, 4.5);
            boolean escaped = false;
            for (int frame = 0; frame <= HZ; frame++) {
                float weight = frame / (float) HZ;
                AttackAim.Point spherical = PoseLayers.blendAim(from, to, weight);
                escaped |= Math.abs(spherical.pitch()) > FIELD_PITCH + .001;
                AttackAim.Point constrained = SpearAimField.constrain(spherical, 0);
                check(SpearAimField.contains(constrained, 0), group, frame, "Spherical entry bridge leaves the displayed legal field");
                if (frame == 0 || frame == HZ) {
                    AttackAim.Point endpoint = frame == 0 ? from : to;
                    check(Math.abs(constrained.yaw() - endpoint.yaw()) < .000001 &&
                            Math.abs(constrained.pitch() - endpoint.pitch()) < .000001 &&
                            Math.abs(constrained.distance() - endpoint.distance()) < .000001,
                            group, frame, "Field constraint changes an already legal bridge endpoint");
                }
                Map<String, Pose> source = slim(playback.sample(frame / (double) HZ, 0, frame / 3., 0, 0, .25f, 55).pose(), slim);
                track.sample(source, constrained, frame / 3., 0, true);
            }
            check(escaped, group, HZ, "Regression fixture must expose the original spherical pitch bulge");
        }
    }
    public static void main(String[] args) throws Exception {
        ModelDocument model = ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/spear.fsvoxel"));
        for (boolean slim : List.of(false, true)) {
            if(!Boolean.getBoolean("fsrpg.motionOnly")) {
                grid(model, slim);
                clipPhases(model, slim);
            }
            for (String kind : List.of("idle", "walk", "left", "right", "back", "sprint")) {
                if(!Boolean.getBoolean("fsrpg.tuckTrace")||(kind.equals("sprint")&&slim))motion(model, slim, kind);
            }
            if(!Boolean.getBoolean("fsrpg.tuckTrace")){attacks(model, slim);bridges(model, slim);}
        }
        JsonObject report = new JsonObject();
        report.addProperty("scope", "Only admitted forward direction field and minimum finite forward depth4.5m; ordinary/slim static grid, READY idle/walk/sideways/back/sprint and moving thrust/sweep action handoffs at60Hz with production SpearMotion history");
        report.addProperty("testedMaximumYawDegrees", FIELD_YAW); report.addProperty("testedMaximumPitchDegrees", FIELD_PITCH);
        report.addProperty("maximumPacketDistanceMeters", 96);
        report.addProperty("maximumPermittedChestHeadingDegrees", 89);
        report.addProperty("chestHeadingReference",UNDERARM?"actual finite aim direction; pelvis and legs follow the diagonal guard":"actor forward basis");
        report.addProperty("underarmGuard",UNDERARM);
        report.addProperty("diagnosticFieldOverride", System.getProperty("fsrpg.forwardTestYaw") != null || System.getProperty("fsrpg.forwardTestPitch") != null);
        report.addProperty("independentGeometry", "Chest signed heading and forward half-plane; world-link signed elbow bend; wrist pronation measured from forearm/palm transverse axes; real grip spacing, pole line, finite target and physical link lengths");
        report.addProperty("limitation", "Focused offline sampling does not replace required DISPLAY5 real-client acceptance");
        report.addProperty("poses", poses); report.addProperty("checks", checks); report.addProperty("failures", failures);
        if(!Boolean.getBoolean("fsrpg.realtimeLegacy"))report.addProperty("continuationOuterSolves",SpearAimPose.realtimeFallbackCalls());
        report.addProperty("loadedSolverBytecodeSha256", sha(SpearAimPose.class));
        report.addProperty("loadedMotionBytecodeSha256", sha(SpearMotion.class));
        report.addProperty("loadedMotionStateBytecodeSha256", sha(SpearMotion.State.class));
        JsonObject groups = new JsonObject(); GROUPS.forEach(groups::add); report.add("groups", groups); report.add("failureExamples", FAILURES);
        Path output = Path.of(args.length > 0 ? args[0] : "verification/action-limits-20261013/forward-field-poses.json");
        Files.createDirectories(output.toAbsolutePath().getParent()); Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("SpearForwardFieldPoseTest: " + poses + " poses / " + checks + " checks / " + failures + " failures; " + output.toAbsolutePath());
        if (failures > 0) throw new AssertionError("Permitted forward-field geometry failures: " + failures);
    }
}
