package org.fsrpg.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.fsrpg.combat.AttackAim;
import org.fsvoxel.animation.FsvoxAnimation.Clip;
import org.fsvoxel.animation.FsvoxAnimation.Event;
import org.fsvoxel.animation.FsvoxAnimation.Key;
import org.fsvoxel.animation.FsvoxAnimation.Part;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.Rig;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.fsvoxel.render.ModelLoader;

/** Tests rig masks, aim isolation, native gait preservation, and genuine render-time interpolation. */
public final class PoseLayersTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        dualElbowTransitions(); masksAndStance(); nativeGaitAndFullBody(); aimIsolation(); continuousThirtyHzSampling();
        movementTransitions(); editableMetadataAndMarkers(); renderClockWorldCorrection(); packetClockContinuity(); phaseRevisionContinuity(); activeHandoffContinuity(); spearAimContinuity(); chestReturnCadence(); aimReturnCadence(); windupAimCadence(); shortWindupAimHandoff(); sphericalAimBlending(); configuredWeaponCadence();
        System.out.println("PoseLayersTest passed: " + checks + " checks");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void dualElbowTransitions() {
        Pose a=new Pose(new V(-26,0,0),new V(0,-37.5f,0));
        Pose b=new Pose(new V(-110,30,0),a.position());
        Quaternionf previous=null;
        for(int i=0;i<=120;i++) {
            Pose blended=a.mix(b,i/120f,true);
            Pose fixed=PoseLayers.dualElbows(Map.of("right_forearm",blended)).get("right_forearm");
            Quaternionf q=fixed.fsQuaternion();
            float turn=2*(float)Math.atan2(q.y,q.w);
            Quaternionf hinge=new Quaternionf(q).rotateY(-turn);
            check(Math.abs(hinge.y)<.00001&&Math.abs(hinge.z)<.00001,"non-coplanar action blend retains an anatomical elbow hinge");
            check(fixed.position().equals(a.position()),"elbow correction never changes bone lengths");
            if(previous!=null)check(Math.abs(previous.dot(q))>.999,"constrained transition remains continuous");
            if(i==0||i==120)check(Math.abs(q.dot((i==0?a:b).fsQuaternion()))>.99999,"valid authored endpoints remain unchanged");
            previous=q;
        }
    }
    private static void close(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < .0001, message + ": " + actual + " expected " + expected);
    }
    private static Clip clip(String id, String layer, Map<String, List<Key>> tracks, List<String> tags) {
        return new Clip(id, 1, false, "linear", tracks, layer, 1, "override", "quaternion", .08,
                "delta", "keep", "hold", List.of(new Event(.2, "active_begin", new JsonObject()),
                new Event(.6, "active_end", new JsonObject())), tags, List.of(), null);
    }
    private static List<Key> keys(V rotation, V position) {
        return List.of(new Key(0, rotation, position), new Key(1, rotation, position));
    }
    private static Rig rig() {
        Map<String, Part> parts = new HashMap<>();
        parts.put("root", new Part("root", null, Pose.ZERO));
        parts.put("body", new Part("body", "root", new Pose(V.ZERO, new V(0, 75, 0))));
        parts.put("head", new Part("head", "body", new Pose(V.ZERO, new V(0, 75, 0))));
        for (String side : List.of("right", "left")) {
            float sign = side.equals("right") ? -1 : 1;
            parts.put(side + "_arm", new Part(side + "_arm", "body", new Pose(V.ZERO, new V(sign * 37.5f, 75, 0))));
            parts.put(side + "_forearm", new Part(side + "_forearm", side + "_arm", new Pose(V.ZERO, new V(0, -37.5f, 0))));
            parts.put(side + "_hand", new Part(side + "_hand", side + "_forearm", new Pose(V.ZERO, new V(0, -25, 0))));
            parts.put(side + "_leg", new Part(side + "_leg", "root", new Pose(V.ZERO, new V(sign * 11.875f, 75, 0))));
        }
        parts.put("rapier", new Part("rapier", "right_hand", Pose.ZERO));
        Map<String, List<Key>> attack = Map.of("body", keys(new V(0, 65, 0), V.ZERO),
                "right_arm", List.of(new Key(0, V.ZERO, null), new Key(1, new V(90, 0, 0), null)),
                "right_leg", keys(new V(70, 90, 10), V.ZERO), "root", keys(new V(0, 80, 0), new V(50, 0, 0)));
        Clip upper = clip("attack", "upper", attack, List.of());
        Clip full = clip("thrust", "full", Map.of("body", keys(new V(0, 65, 0), V.ZERO),
                "right_leg", keys(new V(50, 20, 0), new V(0, 0, 8))), List.of("moving_lower_weight=0.25"));
        Clip stance = clip("rapier_stance", "lower", Map.of("right_leg", keys(V.ZERO, new V(6, 0, 16)),
                "left_leg", keys(new V(0, 55, 0), new V(-6, 0, -16)),
                "root", keys(new V(0, 90, 0), new V(50, 0, 0))), List.of());
        return new Rig("test", 1, Map.copyOf(parts), Map.of(), Map.of(upper.id(), upper, full.id(), full, stance.id(), stance));
    }
    private static void masksAndStance() {
        var rig = rig(); var masks = PoseLayers.masks(rig);
        check(masks.get("upper").contains("rapier"), "upper mask includes hand attachments");
        check(masks.get("upper").contains("left_hand"), "upper mask includes both wrist chains");
        check(!masks.get("upper").contains("right_leg"), "upper mask excludes legs");
        check(!masks.get("lower").contains("root"), "lower cannot turn the root");
        var sample = new PoseLayers.Playback(rig, "attack").sample(.5, .5, 0, 0, 0, .25f, 55);
        close(sample.pose().get("right_leg").rotation().x(), 0, "masked attack leg channel cannot leak");
        close(sample.pose().get("right_leg").position().z(), 16, "independent lower stance supplies front foot");
        close(sample.pose().get("left_leg").rotation().y(), 55, "rear foot retains authored angled stance");
        close(sample.pose().get("root").rotation().y(), 0, "neither upper nor stance rotates root");
        close(sample.pose().get("root").position().x(), 0, "masked root translation cannot leak");
        close(sample.pose().get("right_arm").rotation().x(), 45, "upper arm samples independently");
        close(sample.lowerBodyWeight(), 1, "standing selects complete stance");
    }
    private static void nativeGaitAndFullBody() {
        var nativeLeg = new Locomotion.PartPose(-1.9f, 12, 0, .8f, .005f, .005f);
        var stance = new Locomotion.PartPose(-.8f, 12, -2.68f, 0, -.95f, 0);
        var upper = new PoseLayers.Playback(rig(), "attack").sample(.5, .5, 0, .3f, 0, .25f, 55);
        close(upper.lowerBodyWeight(), 0, "moving upper action gives complete native legs");
        check(Locomotion.mix(nativeLeg, stance, upper.lowerBodyWeight()) == nativeLeg,
                "walking pitch, yaw, position and roll are untouched by upper attack");
        var full = new PoseLayers.Playback(rig(), "thrust").sample(.5, .5, 0, .3f, 0, .9f, 55);
        close(full.lowerBodyWeight(), .25, "full action metadata overrides client fallback");
        close(full.pose().get("right_leg").rotation().x(), 50, "full action is not averaged with lower stance");
        close(full.pose().get("right_leg").position().z(), 8, "full action retains joint lower translation");
        var composed = Locomotion.mix(nativeLeg, stance, .25f);
        close(composed.x(), -1.625, "only quarter of stance hip offset affects moving full action");
        Quaternionf expected = nativeLeg.rotation().slerp(stance.rotation(), .25f);
        check(Math.abs(composed.rotation().dot(expected)) > .99999, "rotations use quaternion mixture rather than Euler addition");
        check(Math.abs(composed.yRot()) < .4, "moving lunge cannot inherit the complete side-foot turn");
        check(Locomotion.mix(nativeLeg, stance, 1) == stance, "stationary pose uses authored stance exactly");
    }
    private static void aimIsolation() {
        var playback = new PoseLayers.Playback(rig(), "attack");
        var straight = playback.sample(.5, .5, 10, .3f, 0, .25f, 55);
        var aimed = playback.sample(.5, .5, 10, .3f, -40, .25f, 55);
        close(aimed.pose().get("body").rotation().y(), 25, "upper torso follows aim");
        check(aimed.pose().get("right_leg").equals(straight.pose().get("right_leg")), "moving front leg ignores aim yaw");
        check(aimed.pose().get("left_leg").equals(straight.pose().get("left_leg")), "moving rear leg ignores aim yaw");
        check(aimed.pose().get("root").equals(straight.pose().get("root")), "aim never mutates shared root");
        var standing = new PoseLayers.Playback(rig(), "attack").sample(.5, .5, 10, 0, -40, .25f, 55);
        close(standing.pose().get("right_leg").rotation().y(), -40, "stationary front leg follows the view");
        close(standing.pose().get("left_leg").rotation().y(), 15, "stationary rear foot preserves its side stance relative to view");
        var front = new PoseLayers.Playback(rig(), "attack").sample(.5, .5, 10, 0, 0, .25f, 55);
        var turned = new PoseLayers.Playback(rig(), "attack").sample(.5, .5, 10, 0, 35, .25f, 55);
        for (String id : List.of("right_leg", "left_leg")) {
            Vector3f expected = front.pose().get(id).position().vector().rotateY((float) Math.toRadians(35));
            check(expected.distance(turned.pose().get(id).position().vector()) < .00002f,
                    "35 degree stationary aim rotates the complete absolute hip position " + id);
        }
        close(front.pose().get("right_leg").position().x(), -5.875, "mixer position already includes rest hip plus stance delta");
    }
    private static void continuousThirtyHzSampling() {
        var playback = new PoseLayers.Playback(rig(), "attack");
        var values = new HashSet<Integer>(); float previous = -1;
        for (int frame = 0; frame <= 30; frame++) {
            double seconds = frame / 30.0;
            var sample = playback.sample(seconds, seconds, seconds * 20, .3f, 0, .25f, 55);
            float angle = sample.pose().get("right_arm").rotation().x();
            check(angle >= previous - .001f, "30 Hz arm interpolation advances monotonically");
            values.add(Math.round(angle * 100)); previous = angle;
        }
        check(values.size() == 31, "30 render samples per second remain distinct despite 20 Hz server clock");
        var clip = rig().clips().get("attack");
        double a = PoseLayers.phaseTime(clip, 1, 4, 3, 9), b = PoseLayers.phaseTime(clip, 1 + 2 / 3.0, 4, 3, 9);
        check(b > a && b < .2, "phase clock interpolates between server ticks before active marker");
    }
    private static void movementTransitions() {
        float thirty = 0, sixty = 0;
        for (int i = 0; i < 30; i++) thirty = Locomotion.smoothMovement(thirty, 1, 20 / 30.0);
        for (int i = 0; i < 60; i++) sixty = Locomotion.smoothMovement(sixty, 1, 20 / 60.0);
        close(thirty, sixty, "movement blend has the same response at 30 and 60 fps");
        var playback = new PoseLayers.Playback(rig(), "attack");
        var idle = playback.sample(.5, .5, 0, 0, 0, .25f, 55);
        var starting = playback.sample(.5, .5, .5, .3f, 0, .25f, 55);
        check(starting.lowerBodyWeight() < idle.lowerBodyWeight() && starting.lowerBodyWeight() > 0,
                "walk start transitions smoothly instead of popping");
        var repeat = playback.sample(.5, .5, .5, .3f, 0, .25f, 55);
        close(repeat.movingWeight(), starting.movingWeight(), "same render tick repeated for armor does not advance fade");
        var next = new PoseLayers.Playback(rig(), "thrust"); next.inheritMovement(playback);
        var inherited = next.sample(.5, .5, .5, .3f, 0, .25f, 55);
        close(inherited.movingWeight(), starting.movingWeight(), "new action retains lower-body transition state");
        close(Locomotion.movementTarget(0), 0, "idle target");
        close(Locomotion.movementTarget(.08f), 1, "normal gait target");
        check(Locomotion.movementTarget(.045f) > 0 && Locomotion.movementTarget(.045f) < 1, "slow movement is continuous");
    }
    private static void editableMetadataAndMarkers() {
        var base = rig(); var invalid = clip("bad", "full", Map.of(), List.of("moving_lower_weight=NaN"));
        var clips = new HashMap<>(base.clips()); clips.put("bad", invalid);
        var malformed = new Rig(base.id(), base.voxelCm(), base.parts(), base.sockets(), Map.copyOf(clips));
        close(new PoseLayers.Playback(malformed, "bad").sample(0, 0, 0, .3f, 0, .8f, 55).lowerBodyWeight(), .25,
                "invalid cosmetic weight metadata uses safe fallback instead of breaking render");
        boolean rejected = false;
        try { PoseLayers.numberTag(clip("bad", "full", Map.of(), List.of("moving_lower_weight=0.2", "moving_lower_weight=0.4")),
                "moving_lower_weight", .25f, 0, 1); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "duplicate metadata cannot silently change mixing");
        var payload = new JsonObject(); payload.addProperty("value", .4);
        var configured = new Clip("configured", 1, false, "linear", Map.of(), "full", 1, "override", "quaternion", .08,
                "delta", "ignore", "hold", List.of(new Event(0, "moving_lower_weight", payload)),
                List.of("moving_lower_weight=0.8"), List.of(), null);
        close(PoseLayers.movingLowerWeight(configured, .6f), .4, "Workbench event weight wins over legacy tags");
        payload.addProperty("value", 2);
        var invalidEvent = new Clip("configured", 1, false, "linear", Map.of(), "full", 1, "override", "quaternion", .08,
                "delta", "ignore", "hold", List.of(new Event(0, "moving_lower_weight", payload)), List.of(), List.of(), null);
        close(PoseLayers.movingLowerWeight(invalidEvent, .6f), .25, "out of range Workbench event safely falls back");
        close(PoseLayers.movingLowerWeight(clip("plain", "full", Map.of(), List.of()), .6f), .6,
                "client config is used when the action has no lower-weight override");
        var constrained = PoseLayers.constrainFootYaw(Map.of("left_leg", new Pose(new V(0, 75, 0), V.ZERO)),
                java.util.Set.of("left_leg"), 55);
        close(constrained.get("left_leg").rotation().y(), 55, "text-editable yaw limit clamps excessive rear-foot turn");
        var clip = base.clips().get("attack");
        close(PoseLayers.phaseTime(clip, 4, 4, 3, 9), .2, "windup endpoint uses active_begin");
        close(PoseLayers.phaseTime(clip, 7, 4, 3, 9), .6, "active endpoint uses active_end");
        close(PoseLayers.phaseTime(clip, 16, 4, 3, 9), 1, "recovery endpoint uses clip end");
        close(PoseLayers.phaseTime(clip, 0, 0, 3, 9), .2, "zero windup starts at active marker");
        close(PoseLayers.phaseTime(clip, 99, 4, 3, 9), 1, "late render clamps to authored endpoint");
        close(PoseLayers.transitionTicks(clip, .12, 4, true), 1.6, "short attack entry leaves most anticipation unblended");
        close(PoseLayers.transitionTicks(clip, .12, 0, true), 0, "zero-windup attack shows its active pose immediately");
        close(PoseLayers.transitionTicks(clip, .12, 4, false), 2.4, "idle return still uses the configured smooth transition");
        close(PoseLayers.transitionTicks(clip, .12, 4, true, true), 3.2, "persistent hand entry uses windup to avoid snapping through a neutral stance");
        close(PoseLayers.transitionTicks(clip, .12, 0, true, true), 0, "zero-windup dual still enters active immediately");
        close(PoseLayers.transitionTicks(clip, .12, 4, false, true), 2.4, "persistent hand idle return retains configured transition");
    }

    private static void renderClockWorldCorrection() {
        // World11 dual-front-armor frames 129..134. A server time correction rolls
        // getGameTime()+partial backward at frame 132 while native frame deltas keep advancing.
        double[] worldTicks = {8894.459995597601, 8894.799995601177, 8895.139995574951,
                8894.479995578527, 8894.799995541573, 8895.139995574951};
        double[] nativeDeltas = {.34, .34, .34, .32, .34};
        var nativeClock = new PoseLayers.RenderClock();
        var attack = new PoseLayers.AttackClock(5.243749966844916, nativeClock.sample(), 4, 3);
        var oldAttack = new PoseLayers.AttackClock(5.243749966844916, worldTicks[0], 4, 3);
        double previous = attack.sample(nativeClock.sample());
        double oldPrevious = oldAttack.sample(worldTicks[0]);
        int oldFrozenFrames = 0;
        for (int frame = 1; frame < worldTicks.length; frame++) {
            nativeClock.advance(nativeDeltas[frame - 1], true);
            double value = attack.sample(nativeClock.sample());
            close(value - previous, nativeDeltas[frame - 1], "World11 active cut advances exactly 1x through world-time correction frame=" + (129 + frame));
            close(attack.sample(nativeClock.sample()), value, "same-frame skin armor and item queries never consume extra delta");
            double oldValue = oldAttack.sample(worldTicks[frame]);
            if (Math.abs(oldValue - oldPrevious) < .0001) oldFrozenFrames++;
            previous = value; oldPrevious = oldValue;
        }
        check(oldFrozenFrames == 3, "negative control reproduces all three stalled render intervals from World11");
        close(previous, 6.923749966844916, "continuous clock exposes the full active arc instead of freezing for one server tick");
        double paused = nativeClock.sample();
        for (int i = 0; i < 600; i++) nativeClock.advance(1 / 3.0, false);
        close(nativeClock.sample(), paused, "pause and tick freeze do not advance animation or inspection time");
        nativeClock.advance(1 / 3.0, true);
        close(nativeClock.sample() - paused, 1 / 3.0, "resume consumes only the current frame, not accumulated pause time");
        nativeClock.reset();
        close(nativeClock.sample(), 0, "world replacement/logout discards the previous level's time base");

        var inspection = new IdleInspection(2031);
        inspection.update(nativeClock.sample(), true, 10, 30, action -> 3);
        double deadline = inspection.deadline();
        check(deadline >= 200 && deadline <= 600, "inspection keeps the configured actual 10–30 second interval");
        int frame = 0;
        while (nativeClock.sample() < deadline) {
            nativeClock.advance(1 / 3.0, true);
            inspection.update(nativeClock.sample(), true, 10, 30, action -> 3);
            check(inspection.deadline() == deadline, "independent render time never reschedules an idle deadline on a world-time packet");
            frame++;
        }
        check(!inspection.action().isEmpty() && frame >= 600 && frame <= 1801,
                "default idle inspection actually begins within 10–30 seconds at 60 fps");
    }

    private static void packetClockContinuity() {
        var clock = new PoseLayers.AttackClock(0, 100.8);
        close(clock.sample(103.8), 3, "render clock keeps sub-tick attack time");
        clock.accept(4, 103.8);
        close(clock.sample(103.8), 3, "WINDUP to ACTIVE snapshot cannot jump at receipt");
        double previous = 3;
        for (int i = 1; i <= 12; i++) {
            double value = clock.sample(103.8 + i / 3.0);
            check(value >= previous, "phase correction never rewinds the action");
            check(value - previous <= 1.25 / 3 + .0001, "phase correction cannot skip an active cut in one 60 Hz sample");
            previous = value;
        }
        close(clock.sample(107.8), 8, "small packet timing error converges without jumping");
        clock.accept(5, 107.8);
        close(clock.sample(107.8), 8, "a late heartbeat cannot rewind elapsed attack time");
        close(clock.sample(108.8), 8.75, "late heartbeat slows the clock within its bounded rate");
        close(clock.sample(108.1), 8.75, "partial-tick rollback cannot rewind visual time");
        close(clock.sample(108.8), 8.75, "armor and item samples on the same tick share one time");

        close(PoseLayers.attackElapsed("WINDUP", 1, 4, 3, 7), 3, "windup snapshot measures remaining ticks");
        close(PoseLayers.attackElapsed("ACTIVE", 3, 4, 3, 7), 4, "active starts at the same continuous endpoint");
        close(PoseLayers.attackElapsed("RECOVERY", 7, 4, 3, 7), 7, "recovery starts at the active endpoint");
        close(PoseLayers.attackElapsed("READY", 0, 0, 0, 0), -1, "idle has no attack clock");
        close(PoseLayers.transitionWeight(100, 100, 3.2), 0, "entry retains its previous pose on the receipt frame");
        check(PoseLayers.transitionWeight(100.3, 100, 3.2) < .03, "late authoritative phase cannot pull a new render bridge to full weight");
        close(PoseLayers.transitionWeight(103.2, 100, 3.2), 1, "bridge reaches its endpoint in continuous render time");
        var activeClock = new PoseLayers.AttackClock(4, 200, 4, 3);
        activeClock.accept(5, 200);
        close(activeClock.sample(201), 5, "packet correction cannot accelerate the active slash arc");
        activeClock.accept(4, 201);
        close(activeClock.sample(202), 6, "a lagged heartbeat cannot slow or rewind the active slash arc");
        close(activeClock.sample(203), 7, "active completes at the configured speed despite packet jitter");
    }

    private static void phaseRevisionContinuity() {
        check(PoseLayers.continuesAction("dual_rl", "WINDUP", 82, "dual_rl", "ACTIVE", 83),
                "a new server revision at contact must keep the current swing clock");
        check(PoseLayers.continuesAction("dual_rl", "ACTIVE", 83, "dual_rl", "RECOVERY", 84),
                "recovery is a phase measurement, not a pending second action");
        check(PoseLayers.continuesAction("dual_rl", "WINDUP", 82, "dual_rl", "RECOVERY", 84),
                "a skipped phase packet still advances the same swing continuously");
        check(PoseLayers.continuesAction("dual_rl", "ACTIVE", 83, "dual_rl", "ACTIVE", 83),
                "heartbeat updates retain the playback instance");
        check(!PoseLayers.continuesAction("dual_rl", "RECOVERY", 84, "dual_rl", "WINDUP", 85),
                "repeating the same attack restarts it and may skip recovery");
        check(!PoseLayers.continuesAction("dual_rl", "WINDUP", 82, "dual_rl", "WINDUP", 83),
                "a fresh same-action windup may cancel the preceding anticipation");
        check(!PoseLayers.continuesAction("dual_ll", "WINDUP", 82, "dual_rr", "WINDUP", 83),
                "a different attack still cancels windup");
        check(!PoseLayers.continuesAction("dual_rl", "ACTIVE", 83, "dual_rl", "ACTIVE", 86),
                "a new zero-windup swing is not mistaken for a heartbeat");
        check(!PoseLayers.continuesAction("dual_rl", "ACTIVE", 83, "dual_rl", "RECOVERY", 82),
                "an obsolete network revision never advances playback");
        check(!PoseLayers.continuesAction("dual_rl", "ACTIVE", 83, "dual_rl", "STUNNED", 84),
                "forced reactions retain immediate replacement");

        // World09 dual-cuts-oblique frames 227 -> 228: revision 82 -> 83 used to
        // rebuild at elapsed 5.0, moving the right palm 78.48 cm in 23.926 ms.
        // Packet callbacks can report a tick below the preceding rendered sample;
        // replay those exact times rather than idealized monotonically arriving data.
        double started = 9547.660030961037, beforeTick = 9550.940030932426;
        double received = 9550.260030984879, afterTick = 9551.260030984879;
        var clock = new PoseLayers.AttackClock(0, started, 4, 3);
        double before = clock.sample(beforeTick);
        close(before, 3.2799999713897705, "recorded pre-contact visual sample");
        if (!PoseLayers.continuesAction("dual_rl", "WINDUP", 82, "dual_rl", "ACTIVE", 83))
            clock = new PoseLayers.AttackClock(4, received, 4, 3);
        else clock.accept(4, received);
        close(clock.sample(beforeTick), before, "earlier callback time cannot move the already rendered pose");
        double after = clock.sample(afterTick);
        close(after, 3.6800000369548798, "recorded contact update advances by one bounded native frame instead of jumping to five ticks");
        check(after - before <= (afterTick - beforeTick) * 1.25 + .00001,
                "contact-phase revision respects the same continuous cadence as ordinary frames");
        clock.accept(7, afterTick + 2.8);
        double recoveryReceipt = clock.sample(afterTick + 2.8);
        check(recoveryReceipt < 7, "a recovery packet can arrive before the visual contact tail has finished");
        close(clock.sample(afterTick + 2.9) - recoveryReceipt, .1,
                "recovery receipt keeps the active tail at its original speed");
        check(PoseLayers.protectedActive("RECOVERY", recoveryReceipt, recoveryReceipt + .1, 4, 3),
                "a real following attack still waits for this preserved active tail");
    }

    private static void activeHandoffContinuity() {
        check(PoseLayers.protectedActive(6.36, 6.7, 4, 3), "new attack cannot truncate the actual 08 remaining active arc");
        check(PoseLayers.protectedActive(6.95, 7.29, 4, 3), "the active endpoint still needs a real rendered sample when the next frame crosses it");
        check(!PoseLayers.protectedActive(3.2, 3.6, 4, 3), "windup can still be canceled before entering contact");
        check(!PoseLayers.protectedActive("WINDUP", 4.1, 4.2, 4, 3), "a visually early contact cannot override the server's legal windup cancellation");
        check(PoseLayers.protectedActive("RECOVERY", 6.9, 6.95, 4, 3), "server recovery does not skip the still-presented contact tail");
        check(!PoseLayers.protectedActive(7, 7.3, 4, 3), "recovery is skippable immediately after its active endpoint was shown");
        check(!PoseLayers.protectedActive(4, 5, 4, 0), "an action with no active interval cannot block handoff");
        for (String forced : List.of("STUNNED", "SHEATHED", "SHEATHING", "DRAWING", "DODGING", "GUARDING"))
            check(!PoseLayers.ordinaryHandoff(forced), "forced state bypasses presentation protection: " + forced);
        for (String phase : List.of("WINDUP", "ACTIVE", "RECOVERY", "READY"))
            check(PoseLayers.ordinaryHandoff(phase), "ordinary attack state may await contact completion: " + phase);

        double start = 9512.060010790825, arrival = start + .34;
        var clock = new PoseLayers.AttackClock(6.36, start, 4, 3);
        var pending = new PoseLayers.PendingAction<>("new cut", 2, "dual_lr", "WINDUP", 4, arrival, 7);
        double elapsed = pending.holdElapsed(clock.sample(arrival), arrival);
        close(elapsed, 6.7, "previous active clock advances at one real tick per tick while next intent waits");
        check(!pending.ready(arrival), "pending cannot replace an unfinished contact pose");
        double endpointTick = arrival + .34;
        elapsed = pending.holdElapsed(clock.sample(endpointTick), endpointTick);
        close(elapsed, 7, "crossing frame renders the original active endpoint instead of discarding the tail");
        check(!pending.ready(endpointTick), "skin and armor sampling the endpoint tick cannot switch to the new cut");
        close(pending.holdElapsed(clock.sample(endpointTick), endpointTick), 7, "duplicate sampling cannot add a second visual clock step");
        double takeover = endpointTick + .34;
        check(pending.ready(takeover), "the following actual render frame may start the pending cut");
        close(pending.elapsed(takeover, 4, 3, 7), .68, "new cut begins at true latest-authority elapsed, rather than replaying expired anticipation");
        close(PoseLayers.entryWindupTicks(3.2, .68, 4), 3.2, "entry bridge keeps its full duration when remaining windup allows it");
        close(PoseLayers.entryWindupTicks(3.2, 2.4, 4), 1.6, "late handoff bridge ends by the existing server windup deadline");
        close(PoseLayers.entryWindupTicks(3.2, 4, 4), 0, "entry blending never conceals an already active authoritative action");

        check(!pending.offer("obsolete", 1, "dual_rr", "RECOVERY", 7, takeover), "old sequence cannot overwrite pending intent or old hand state");
        check(pending.value().equals("new cut"), "rejecting old sequence preserves the new intent");
        check(pending.offer("duplicate", 2, "dual_lr", "WINDUP", 4, takeover), "duplicate payload may refresh metadata");
        close(pending.phaseReceivedTick(), arrival, "duplicate remaining snapshot cannot restart pending authoritative time");
        close(pending.elapsed(takeover, 4, 3, 7), .68, "duplicate packet retains true receipt age");
        check(!pending.offer("stale remaining", 2, "dual_lr", "WINDUP", 5, takeover), "reordered remaining ticks cannot rewind pending anticipation");
        check(pending.offer("latest snapshot", 2, "dual_lr", "WINDUP", 3, takeover), "fresh remaining snapshot updates the latest measurement");
        close(pending.elapsed(takeover + .1, 4, 3, 7), 1.1, "updated snapshot advances from its actual receipt timestamp");
        check(pending.offer("latest cut", 3, "dual_ll", "WINDUP", 4, takeover + .1), "newest sequence replaces the one pending slot rather than appending a stale queue");
        check(pending.value().equals("latest cut") && pending.sequence() == 3, "takeover uses only the newest authoritative action");
        close(pending.elapsed(takeover + .44, 4, 3, 7), .34, "replaced intent uses its own receipt age");
        check(pending.offer("already active", 3, "dual_ll", "ACTIVE", 3, takeover + .44), "server phase advances while visual contact protection waits");
        close(pending.elapsed(takeover + .54, 4, 3, 7), 4.1, "pending ACTIVE state stays authoritative and is not replayed as windup");
        check(!pending.offer("obsolete phase", 3, "dual_ll", "WINDUP", 2, takeover + .54), "reordered earlier phase cannot replace the newest pending ACTIVE packet");
        close(PoseLayers.phaseTime(rig().clips().get("attack"), 7, 4, 3, 0), .6,
                "a zero-recovery action still renders its exact authored active endpoint before handoff");
    }

    private static void spearAimContinuity() {
        var from = new AttackAim.Point(170, -35, 4.5);
        var to = new AttackAim.Point(-174, 20, 7);
        check(PoseLayers.blendAim(from, to, 0) == from, "ready return begins at the actual last server aim");
        check(PoseLayers.blendAim(from, to, 1) == to, "aim bridge ends at the exact cursor target");
        var half = PoseLayers.blendAim(from, to, .5f);
        close(aimAngularDistance(from,half),aimAngularDistance(from,to)/2,"aim midpoint follows the shortest physical direction arc across the yaw seam");
        close(aimAngularDistance(half,to),aimAngularDistance(from,to)/2,"spherical midpoint has equal angular distance to both endpoints");
        close(half.distance(), 5.75, "aim range blends before two-hand inverse kinematics");
        var reverse = new AttackAim.Point(-26, 20, 7);
        double duration = PoseLayers.aimTransitionTicks(from, reverse, 2.4);
        check(duration > 2.4, "large sweep-to-cursor reset receives enough return time");
        var previous = from;
        for (int i = 1; i <= 60; i++) {
            double tick = i / 3.0;
            var value = PoseLayers.blendAim(from, reverse, PoseLayers.transitionWeight(tick, 0, duration));
            double step = aimAngularDistance(previous,value);
            check(step < 30, "spear READY target has no one-frame 144 degree reset");
            previous = value;
        }
        close(previous.yaw(), reverse.yaw(), "returned spear target settles exactly");
        var active = new AttackAim(List.of(from, to)).sample(.375);
        check(PoseLayers.blendAim(from, active, 1) == active, "ACTIVE bypass retains the exact authoritative contact point");
        close(PoseLayers.aimFraction("WINDUP", 12, 6, 10), 0, "a delayed WINDUP packet cannot preview and then rewind the sweep");
        close(PoseLayers.aimFraction("ACTIVE", 9.75, 6, 10), .375, "ACTIVE aim uses precise authoritative sub-tick contact time");
        close(PoseLayers.aimFraction("RECOVERY", 14, 6, 10), 1, "recovery holds the final sweep direction until its ready bridge");
        var sourceRear = new Vector3f(12, 116, 29);
        var previousSourceRear = new Vector3f(10, 118, 21);
        var previousActualRear = new Vector3f(15, 113, 25);
        var seed = PoseLayers.gripSeed(sourceRear, previousSourceRear, previousActualRear);
        check(seed.equals(new Vector3f(17, 111, 33)), "feasible grip follows authored stroke rather than snapping to another annulus branch");
        check(sourceRear.equals(new Vector3f(12, 116, 29)) && previousSourceRear.equals(new Vector3f(10, 118, 21))
                && previousActualRear.equals(new Vector3f(15, 113, 25)), "seed transport does not mutate render history");
        check(PoseLayers.gripSeed(sourceRear, null, previousActualRear).equals(sourceRear), "missing authored history begins at this frame's source grip");
        var initialSeed = PoseLayers.gripSeed(sourceRear, previousSourceRear, null);
        check(initialSeed.equals(sourceRear) && initialSeed != sourceRear, "missing feasible history returns a detached source grip");
        var oldSourceChest = new Quaternionf().rotationXYZ(.1f, -.8f, -.12f);
        var oldActualChest = new Quaternionf(oldSourceChest).rotateY(1.7f);
        var sourceChest = new Quaternionf().rotationXYZ(-.15f, -.63f, .21f);
        var sourceCopy = new Quaternionf(sourceChest); var previousSourceCopy = new Quaternionf(oldSourceChest);
        var previousActualCopy = new Quaternionf(oldActualChest);
        var chestSeed = PoseLayers.chestSeed(sourceChest, oldSourceChest, oldActualChest);
        var expectedChest = new Quaternionf(sourceChest).rotateY(1.7f);
        check(Math.abs(chestSeed.dot(expectedChest)) > .999999f, "chest seed retains the feasible local yaw while following current authored lean and breathing");
        check(sourceChest.equals(sourceCopy) && oldSourceChest.equals(previousSourceCopy) && oldActualChest.equals(previousActualCopy),
                "chest seed does not mutate actual or authored history");
        var initialChestSeed = PoseLayers.chestSeed(sourceChest, null, oldActualChest);
        check(initialChestSeed.equals(sourceChest) && initialChestSeed != sourceChest, "first chest sample uses the current source with no borrowed history");
        check(PoseLayers.chestSeed(sourceChest, oldSourceChest, null).equals(sourceChest), "missing actual chest history falls back to source");
    }

    private static void chestReturnCadence() {
        var oldSource = new Quaternionf().rotationXYZ(.12f, -.7f, -.19f);
        for (float sign : new float[]{-1, 1}) {
            var oldActual = new Quaternionf(oldSource).rotateY((float) Math.toRadians(sign * 120));
            // Near a yaw gimbal lock, quaternion local-Y recovery still retains the complete
            // current lean. Euler-component subtraction would lose or multiply this lean.
            var source = new Quaternionf().rotationXYZ(-.23f, (float) Math.toRadians(89.999), .31f);
            var returned = PoseLayers.chestSeed(source, oldSource, oldActual, 1 / 60.0, 90);
            var expected = new Quaternionf(source).rotateY((float) Math.toRadians(sign * 118.5));
            check(Math.abs(returned.dot(expected)) > .999999f, "real 60 Hz dt returns 1.5 degrees while preserving authored lean near gimbal lock");
            var negatedActual = new Quaternionf(-oldActual.x, -oldActual.y, -oldActual.z, -oldActual.w);
            check(Math.abs(returned.dot(PoseLayers.chestSeed(source, oldSource, negatedActual, 1 / 60.0, 90))) > .999999f,
                    "quaternion sign representation cannot reverse the chest return branch");
            var unchanged = PoseLayers.chestSeed(source, oldSource, oldActual, 0, 90);
            check(Math.abs(unchanged.dot(PoseLayers.chestSeed(source, oldSource, oldActual))) > .999999f, "second skin or armor sample on one render tick cannot advance chest return");
            check(Math.abs(PoseLayers.chestSeed(source, oldSource, oldActual, -.05, 90).dot(unchanged)) > .999999f, "render clock rollback cannot advance chest return");
            check(Math.abs(PoseLayers.chestSeed(source, oldSource, oldActual, .05, 0).dot(unchanged)) > .999999f, "ACTIVE with return speed zero preserves the exact transported chest branch");
            var stalled = PoseLayers.chestSeed(source, oldSource, oldActual, 5, 90);
            expected = new Quaternionf(source).rotateY((float) Math.toRadians(sign * 111));
            check(Math.abs(stalled.dot(expected)) > .999999f, "long frame stall consumes at most .1 seconds of default chest return");
            var fastest = PoseLayers.chestSeed(source, oldSource, oldActual, 5, 240);
            expected = new Quaternionf(source).rotateY((float) Math.toRadians(sign * 110));
            check(Math.abs(fastest.dot(expected)) > .999999f, "maximum configured speed still cannot cross a feasible branch in one large frame gap");
            var shortActual = new Quaternionf(oldSource).rotateY((float) Math.toRadians(sign * .4));
            check(Math.abs(PoseLayers.chestSeed(source, oldSource, shortActual, .05, 90).dot(source)) > .999999f,
                    "small chest correction reaches source without overshooting zero");
        }
        for (int hz : new int[]{30, 60, 120}) {
            var source = new Quaternionf(oldSource);
            var actual = new Quaternionf(source).rotateY((float) Math.toRadians(120));
            for (int i = 0; i < hz; i++) actual = PoseLayers.chestSeed(source, source, actual, 1.0 / hz, 90);
            var expected = new Quaternionf(source).rotateY((float) Math.toRadians(30));
            check(Math.abs(actual.dot(expected)) > .999999f, "chest return covers exactly 90 degrees per actual second at " + hz + " Hz");
        }
    }

    private static void aimReturnCadence() {
        var from = new AttackAim.Point(144.765769, -20, 4.5);
        var target = new AttackAim.Point(0, 15, 6);
        for (double speed : new double[]{90, 240, 720}) {
            double duration = PoseLayers.aimTransitionTicks(from, target, 2.4, speed);
            for (int hz : new int[]{30, 60, 120}) {
                var previous = from;
                for (int i = 1; i <= Math.ceil(duration / 20 * hz); i++) {
                    var aim = PoseLayers.blendAim(from, target, PoseLayers.transitionWeight(i * 20.0 / hz, 0, duration));
                    double distance = aimAngularDistance(previous,aim);
                    check(distance * hz <= speed + .001, "ready aim smoothstep peak angular speed stays within " + speed + " deg/s at " + hz + " Hz");
                    previous = aim;
                }
                check(previous == target, "ready aim reaches the exact current target when its speed-limited bridge ends");
            }
        }
        close(PoseLayers.aimTransitionTicks(new AttackAim.Point(179, 0), new AttackAim.Point(-179, 0), 2.4, 240), 2.4,
                "small seam-crossing return retains the configured minimum blend duration");
    }

    private static void windupAimCadence() {
        double duration = 3.036515069937993;
        var from = new AttackAim.Point(113.67654274655331, -6.623032213271799, 48.229309072204465);
        var target = new AttackAim.Point(0, 0, 4.5);
        var before = PoseLayers.blendAim(from, target, PoseLayers.windupAimWeight(1.3399999737739563, 0, duration));
        var after = PoseLayers.blendAim(from, target, PoseLayers.windupAimWeight(1.679999977350235, 0, duration));
        check(aimAngularDistance(before,after) < 14, "actual 07 windup pair no longer has a smoothstep 19 degree direction step");
        close(PoseLayers.windupAimWeight(0, 0, duration), 0, "windup target begins at the actual interrupted ready aim");
        close(PoseLayers.windupAimWeight(duration, 0, duration), 1, "windup reaches the exact server direction before the existing contact deadline");
        double peak = 1 / .92;
        for (int hz : new int[]{30, 60, 120}) {
            double previous = 0;
            for (int i = 1; i <= Math.ceil(duration / 20 * hz); i++) {
                double weight = PoseLayers.windupAimWeight(i * 20.0 / hz, 0, duration);
                check(weight >= previous, "windup target progresses monotonically at " + hz + " Hz");
                check((weight - previous) * hz <= peak * 20 / duration + .00001,
                        "contact preparation peak speed stays within 1.087 times its mean rather than smoothstep's 1.5");
                previous = weight;
            }
        }
        double epsilon = .00001;
        for (double knot : new double[]{.08, .92}) {
            double left = (PoseLayers.windupAimWeight(knot, 0, 1) - PoseLayers.windupAimWeight(knot - epsilon, 0, 1)) / epsilon;
            double right = (PoseLayers.windupAimWeight(knot + epsilon, 0, 1) - PoseLayers.windupAimWeight(knot, 0, 1)) / epsilon;
            check(Math.abs(left - right) < .01, "short ramp joins uniform turn with a continuous velocity");
        }
        check(PoseLayers.windupAimWeight(epsilon, 0, 1) / epsilon < .001, "windup aim starts with zero velocity");
        check((1 - PoseLayers.windupAimWeight(1 - epsilon, 0, 1)) / epsilon < .001, "windup aim settles with zero velocity before contact");
        close(PoseLayers.windupAimTicks(duration, 0, 5), duration, "actual 07 bridge retains its contact deadline");
        close(PoseLayers.windupAimTicks(5, 1.5, 2), 3, "first render waiting time is counted once while reserving half a settled contact tick");
        check(PoseLayers.windupAimWeight(1.5,0,PoseLayers.windupAimTicks(5,1.5,0))==1,
                "late first sample is already settled when the windup deadline has passed");
    }

    private static Vector3f aimDirection(AttackAim.Point point) {
        double yaw=Math.toRadians(point.yaw()),pitch=Math.toRadians(point.pitch());
        return new Vector3f((float)(-Math.sin(yaw)*Math.cos(pitch)),(float)-Math.sin(pitch),(float)(Math.cos(yaw)*Math.cos(pitch)));
    }
    private static double aimAngularDistance(AttackAim.Point from,AttackAim.Point to) {
        var a=aimDirection(from);var b=aimDirection(to);
        return Math.toDegrees(Math.atan2(new Vector3f(a).cross(b).length(),a.dot(b)));
    }
    /** Actual W18 moving frame419 ->420 crossed a 1.88-tick render gap: a 3-tick
     * bridge still had 40% of its turn left, followed immediately by the ACTIVE arc. */
    private static void shortWindupAimHandoff() {
        var from=new AttackAim.Point(-95,-60,3);
        var path=new AttackAim(List.of(new AttackAim.Point(75,48,3),new AttackAim.Point(110,60,3),new AttackAim.Point(145,72,3)));
        var contact=path.sample(0);
        double requested=PoseLayers.aimTransitionTicks(from,contact,2.4);
        double duration=PoseLayers.windupAimTicks(requested,0,3);
        close(duration,2.5,"3-tick anticipation retains turn time plus half a settled native tick before contact");
        close(PoseLayers.windupAimTicks(requested,.8,2.2),duration,"late first render preserves the same anticipation deadline");
        var before=PoseLayers.blendAim(from,contact,PoseLayers.windupAimWeight(1.8000000417232513,0,duration));
        var active=path.sample(PoseLayers.aimFraction("ACTIVE",4.879999995231628,3,7));
        double directionGap=aimAngularDistance(before,active);
        check(directionGap<=60,"captured W18 gap has a bounded spherical turn plus authoritative contact arc: "+directionGap);
        close(active.yaw(),93.79999995231628,"ACTIVE keeps the exact authoritative sweep yaw");
        close(active.pitch(),54.44571426936558,"ACTIVE keeps the exact authoritative sweep pitch");
        close(active.distance(),3,"ACTIVE keeps the exact authoritative sweep range");
        for(int hz:new int[]{30,60,120}) {
            AttackAim.Point finalWindup=from;
            for(int frame=0;frame*20.0/hz<3;frame++) {
                double tick=frame*20.0/hz;
                var aim=PoseLayers.blendAim(from,contact,PoseLayers.windupAimWeight(tick,0,duration));
                if(tick>=2.5)check(aim==contact,"short windup holds the exact contact direction before ACTIVE at "+hz+"Hz");
                finalWindup=aim;
            }
            check(finalWindup==contact,"last ordinary WINDUP frame has no residual turn at "+hz+"Hz");
            var firstActive=path.sample(PoseLayers.aimFraction("ACTIVE",3,3,7));
            check(aimDirection(finalWindup).distance(aimDirection(firstActive))<.00001,
                    "first ACTIVE direction joins the settled windup continuously at "+hz+"Hz");
            check(PoseLayers.blendAim(from,firstActive,PoseLayers.windupAimWeight(3,0,duration))==firstActive,
                    "first ACTIVE sample retains the server target exactly at "+hz+"Hz");
        }
        for(double windup:new double[]{1,2,3,5,6}) {
            double end=PoseLayers.windupAimTicks(20,0,windup);
            check(end>0&&end<windup,"short windups retain a turn interval plus stable anticipation: "+windup);
            close(windup-end,Math.min(.5,windup/6),"settled interval respects available windup time");
        }
    }

    private static void sphericalAimBlending() {
        var from=new AttackAim.Point(-95,-60,3);var contact=new AttackAim.Point(75,48,3);
        double duration=PoseLayers.windupAimTicks(PoseLayers.aimTransitionTicks(from,contact,2.4),0,3);
        // Trial23's bridge clock was .4 ->1.3; visual attack elapsed1.525 and
        // authoritative elapsed1.9 were different clocks, not blend sample times.
        float beforeWeight=PoseLayers.windupAimWeight(.4,0,duration),afterWeight=PoseLayers.windupAimWeight(1.3,0,duration);
        var before=PoseLayers.blendAim(from,contact,beforeWeight);var after=PoseLayers.blendAim(from,contact,afterWeight);
        double gap=aimAngularDistance(before,after);
        check(gap<=70,"trial23's .9-tick render gap no longer has a 92.564-degree yaw/pitch direction jump: "+gap);
        close(gap,aimAngularDistance(from,contact)*(afterWeight-beforeWeight),"captured samples advance uniformly on the physical shortest arc");
        for(var pair:List.of(
                List.of(new AttackAim.Point(179,5,2),new AttackAim.Point(-179,5,6)),
                List.of(new AttackAim.Point(12,79,2),new AttackAim.Point(18,78.9,6)),
                List.of(new AttackAim.Point(-95,-60,2),new AttackAim.Point(75,48,6)))) {
            var start=pair.getFirst();var end=pair.getLast();double total=aimAngularDistance(start,end);
            for(int i=0;i<=100;i++) {
                float weight=i/100f;var aim=PoseLayers.blendAim(start,end,weight);
                close(aimAngularDistance(start,aim),total*weight,"representable spherical arc retains constant direction phase");
                close(aimAngularDistance(aim,end),total*(1-weight),"representable spherical arc retains remaining direction phase");
                close(aim.distance(),start.distance()+(end.distance()-start.distance())*weight,"spherical direction retains independent linear range");
            }
        }
        for(var pair:List.of(
                List.of(new AttackAim.Point(33,10,2),new AttackAim.Point(33.0000001,10.0000001,6)),
                List.of(new AttackAim.Point(0,0,2),new AttackAim.Point(180,0,6)),
                List.of(new AttackAim.Point(0,0,2),new AttackAim.Point(179.999999,0.000001,6)),
                List.of(new AttackAim.Point(170,79.999,2),new AttackAim.Point(-10,-79.999,6)))) {
            var start=pair.getFirst();var end=pair.getLast();var previous=start;
            check(PoseLayers.blendAim(start,end,0)==start&&PoseLayers.blendAim(start,end,1)==end,"degenerate arcs preserve exact endpoint objects");
            for(int i=1;i<=100;i++) {
                float weight=i/100f;var aim=PoseLayers.blendAim(start,end,weight);
                check(Double.isFinite(aim.yaw())&&Double.isFinite(aim.pitch())&&Double.isFinite(aim.distance()),"parallel/antipodal direction interpolation stays finite");
                check(Math.abs(aim.pitch())<=80&&aim.yaw()>=-180&&aim.yaw()<=180,"degenerate direction remains representable with wrapped yaw");
                check(aimAngularDistance(previous,aim)<=2.1,"parallel/antipodal samples have no direction-plane flip");
                check(aim.equals(PoseLayers.blendAim(start,end,weight)),"antipodal direction choice is deterministic");
                previous=aim;
            }
        }
        var antipodalHalf=PoseLayers.blendAim(new AttackAim.Point(0,0),new AttackAim.Point(180,0),.5f);
        close(antipodalHalf.yaw(),-90,"antipodal tie-break follows the deterministic horizontal shortest arc");
        close(antipodalHalf.pitch(),0,"antipodal tie-break does not invent a polar excursion");
        // A genuine great circle between these valid endpoints crosses +/-90 degrees,
        // which Point cannot represent. Use one continuous bounded path for the full bridge.
        for(float pitch:new float[]{80,-80}) {
            var start=new AttackAim.Point(-90,pitch,2);var end=new AttackAim.Point(90,pitch,6);var previous=start;
            for(int i=1;i<=100;i++) {
                float weight=i/100f;var aim=PoseLayers.blendAim(start,end,weight);
                close(aim.pitch(),pitch,"polar fallback stays on the legal pitch boundary");
                close(AttackAim.wrap(aim.yaw()-(-90-180*weight)),0,"whole polar bridge uses continuous wrapped yaw");
                check(aimAngularDistance(previous,aim)<.5,"polar fallback has no clamped-pole yaw discontinuity");
                previous=aim;
            }
            check(previous==end,"polar fallback retains the exact final target");
        }
    }

    /** Source clip cadence alone does not cover acceleration through configured server windows. */
    private static void configuredWeaponCadence() throws Exception {
        var model = ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/dual_blade.fsvoxel"));
        // This model is the retained 0.4 rig; its authored cadence remains a separate regression fixture.
        for (var element : JsonParser.parseString(Files.readString(Path.of("src/main/resources/defaults/legacy-0.4/skills.json"))).getAsJsonArray()) {
            var skill = element.getAsJsonObject();
            String id = skill.get("id").getAsString();
            if (!List.of("dual_ll", "dual_lr", "dual_rl", "dual_rr").contains(id)) continue;
            double windup = skill.get("windupTicks").getAsDouble(), active = skill.get("activeTicks").getAsDouble(), recovery = skill.get("recoveryTicks").getAsDouble();
            var playback = new PoseLayers.Playback(model.rig(), id);
            String hand = (id.startsWith("dual_l") ? "left" : "right") + "_hand";
            Quaternionf previous = null;
            double maximum = 0, maximumPalm = 0; int at = 0;
            Vector3f previousPalm = null;
            for (int i = 0; i <= Math.ceil((windup + active + recovery) * 3); i++) {
                double tick = i / 3.0;
                var pose = playback.sample(PoseLayers.phaseTime(playback.clip(), tick, windup, active, recovery), 0, tick, 0, 0, .25f, 55).pose();
                var matrix = model.worldMatrices(pose).get(hand);
                var rotation = matrix.getUnnormalizedRotation(new Quaternionf()).normalize();
                var palm = matrix.transformPosition(new Vector3f(0, -6.25f, 0));
                if (previous != null) {
                    double degrees = Math.toDegrees(2 * Math.acos(Math.clamp(Math.abs(previous.dot(rotation)), 0, 1)));
                    if (degrees > maximum) { maximum = degrees; at = i; }
                }
                if (previousPalm != null) maximumPalm = Math.max(maximumPalm, previousPalm.distance(palm));
                previous = rotation;
                previousPalm = palm;
            }
            System.out.println("Configured 60Hz hand step " + id + ": " + maximum + " degrees at frame " + at + ", palm " + maximumPalm + " cm");
            check(maximum < 30, "configured active window must preserve the original 30 degree visible-step limit " + id + ": " + maximum);
            double maximumCorrected = 0;
            for (int offset = 0; offset < 12; offset++) {
                var clock = new PoseLayers.AttackClock(0, 0, windup, active);
                previous = null;
                for (int i = 0; i <= Math.ceil((windup + active + recovery) * 3); i++) {
                    double tick = i / 3.0 + offset / 36.0;
                    if (i == (int) (windup * 3)) clock.accept(windup + .8, tick);
                    var pose = playback.sample(PoseLayers.phaseTime(playback.clip(), clock.sample(tick), windup, active, recovery), 0, tick, 0, 0, .25f, 55).pose();
                    var rotation = model.worldMatrices(pose).get(hand).getUnnormalizedRotation(new Quaternionf()).normalize();
                    if (previous != null) maximumCorrected = Math.max(maximumCorrected,
                            Math.toDegrees(2 * Math.acos(Math.clamp(Math.abs(previous.dot(rotation)), 0, 1))));
                    previous = rotation;
                }
            }
            System.out.println("Corrected 60Hz hand step " + id + ": " + maximumCorrected + " degrees");
            check(maximumCorrected < 30, "packet correction must retain the 30 degree visible-step limit " + id + ": " + maximumCorrected);
        }
    }
}
