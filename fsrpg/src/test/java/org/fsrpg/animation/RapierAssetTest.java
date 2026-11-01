package org.fsrpg.animation;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.fsvoxel.animation.AnimationMixer;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Checks authored geometry and poses with the real FSVOXEL evaluator. */
public final class RapierAssetTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        ModelDocument model;
        try (var input = RapierAssetTest.class.getResourceAsStream("/assets/fsrpg/fsvoxel/models/rapier.fsvoxel")) {
            if (input == null) throw new AssertionError("missing packaged rapier asset");
            model = ModelLoader.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        check(model.rig().clips().size() == 16, "breathing, running, two guards, conditional techniques and layered attacks exist");
        check(model.rig().parts().size() == 12 && model.rig().parts().containsKey("right_forearm") && model.rig().parts().containsKey("left_hand"), "articulated elbow and wrist rig");
        check(model.rig().sockets().containsKey("weapon_tip"), "weapon tip socket");
        check(model.nodes().values().stream().filter(n -> n.id().equals("rapier") || n.id().startsWith("rapier_")).allMatch(n -> n.mesh().faces().isEmpty()), "no authored replacement item geometry");
        var rest = model.worldMatrices(Map.of());
        check(rest.get("right_arm").getTranslation(new Vector3f()).distance(new Vector3f(-37.5f,150,0))<.001, "stock right shoulder center and height");
        check(rest.get("head").getTranslation(new Vector3f()).y==150, "stock head has no neck gap");
        check(rest.get("right_forearm").getTranslation(new Vector3f()).distance(rest.get("right_hand").getTranslation(new Vector3f()))==25, "wrist is at source y8, not at fingertips");
        // The retained 0.4 model owns its five-cut timing; LegacyAssetTest validates the imported 0.1.1 tree.
        var skills = com.google.gson.JsonParser.parseString(new String(RapierAssetTest.class.getResourceAsStream("/defaults/legacy-0.4/skills.json").readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
        var style = com.google.gson.JsonParser.parseString(new String(RapierAssetTest.class.getResourceAsStream("/defaults/legacy-0.4/styles.json").readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray().get(0).getAsJsonObject();
        var gates = style.getAsJsonArray("leftCombo");
        double comboTicks = 0;
        double[] chainTimes = new double[4];
        for (int i=0;i<5;i++) {
            var skill = skills.get(i).getAsJsonObject(); var gate = gates.get(i).getAsJsonObject();
            int windup=skill.get("windupTicks").getAsInt(), active=skill.get("activeTicks").getAsInt(), recovery=skill.get("recoveryTicks").getAsInt();
            int chain=i<4?windup+active:0;
            comboTicks += chain == 0 ? windup+active+recovery : chain;
            if(i<4) chainTimes[i]=.55+.45*(chain-windup-active)/recovery;
        }
        var idle = sample(model, "rapier_idle", 0);
        Vector3f blade = direction(idle.get("rapier"), new Vector3f(0, 1, 0));
        check(blade.dot(new Vector3f(0, 0, 1)) > .99f, "idle blade aims at enemy");
        Vector3f torsoForward = direction(idle.get("body"), new Vector3f(0, 0, 1));
        check(torsoForward.dot(new Vector3f(0, 0, 1)) < .5f, "torso is side-on");
        check(direction(idle.get("head"), new Vector3f(0, 0, 1)).z > .99f, "head looks at enemy");
        Vector3f left = idle.get("left_hand").getTranslation(new Vector3f());
        new Matrix4f(idle.get("body")).invert().transformPosition(left);
        check(left.z < -14, "left hand rests behind back");
        check(Math.abs(idle.get("right_leg").transformPosition(new Vector3f(0,-75,0)).y)<.001 && Math.abs(idle.get("left_leg").transformPosition(new Vector3f(0,-75,0)).y)<.001, "both stance soles remain on the floor");
        check(direction(idle.get("right_leg"),new Vector3f(0,0,1)).z>.99 && direction(idle.get("left_leg"),new Vector3f(0,0,1)).x>.7, "lead foot faces enemy and rear foot opens sideways");
        check(model.rig().clips().get("rapier_stance").layer().equals("lower") && model.rig().clips().get("rapier_stance").tracks().keySet().equals(java.util.Set.of("right_leg","left_leg")), "lower stance affects only legs");
        check(model.rig().clips().get("rapier_thrust").layer().equals("full") && model.rig().clips().get("rapier_thrust").events().stream().anyMatch(e -> e.name().equals("moving_lower_weight") && e.payload().get("value").getAsFloat()==.25f), "full thrust exposes editable moving lower-body weight");
        var charge = sample(model, "rapier_thrust", .23);
        var extension = sample(model, "rapier_thrust", .55);
        Vector3f chargeBlade = direction(charge.get("rapier"), new Vector3f(0, 1, 0));
        Vector3f chargeArm = direction(charge.get("right_forearm"), new Vector3f(0, -1, 0));
        check(Math.abs(chargeBlade.dot(chargeArm)) < .30, "charge approaches a right angle without a folded wrist: dot="+chargeBlade.dot(chargeArm));
        Vector3f chargePalm=charge.get("right_hand").transformPosition(new Vector3f(0,-6.25f,0));
        Vector3f chargeLocal=new Matrix4f(charge.get("body")).invert().transformPosition(new Vector3f(chargePalm));
        check(chargeLocal.x < -25 || chargeLocal.z > 25, "charge palm remains outside torso");
        Vector3f extensionBlade = direction(extension.get("rapier"), new Vector3f(0, 1, 0));
        Vector3f extensionArm = direction(extension.get("right_forearm"), new Vector3f(0, -1, 0));
        check(extensionBlade.dot(extensionArm) > .9, "extended forearm and blade almost collinear");
        check(extension.get("right_hand").transformPosition(new Vector3f(0,-6.25f,0)).z > 90, "thrust palm extends toward enemy");
        Vector3f palm=extension.get("right_hand").transformPosition(new Vector3f(0,-6.25f,0));
        Vector3f hilt=extension.get("rapier").getTranslation(new Vector3f());
        check(palm.distance(hilt)<.001, "virtual item attachment anchor coincides with palm");
        check(idle.get("right_hand").transformPosition(new Vector3f(0,-6.25f,0)).z-chargePalm.z>25, "charging clearly retracts the hand");
        check(Math.abs(model.rig().clips().get("rapier_combo").duration() - comboTicks/20) < .00001, "five-stage review follows configured chain timing");
        String[] combo={"rapier_rise","rapier_down","rapier_cross","rapier_light_thrust","rapier_wrist_rise"};
        for(int stage=1;stage<combo.length;stage++){
            var previous=sample(model,combo[stage-1],chainTimes[stage-1]);
            var next=sample(model,combo[stage],0);
            for(String bone:previous.keySet()) {
                Matrix4f a=previous.get(bone),b=next.get(bone);
                if(a.getTranslation(new Vector3f()).distance(b.getTranslation(new Vector3f()))>.02f
                        ||Math.abs(a.getUnnormalizedRotation(new org.joml.Quaternionf()).dot(b.getUnnormalizedRotation(new org.joml.Quaternionf())))<.99999f)
                    throw new AssertionError("combo must begin at preceding chain pose: "+combo[stage]+" "+bone);
            }
            checks++;
        }
        var motionViolations=new java.util.ArrayList<String>();
        for (var clip : model.rig().clips().values()) {
            var previous = local(model,clip.id(),0);
            var previousWorld=model.worldMatrices(previous);
            double maxActualWorldStep=0;
            com.google.gson.JsonObject timing = null;
            for(var definition:skills)if(definition.getAsJsonObject().get("action").getAsString().equals(clip.id()))timing=definition.getAsJsonObject();
            double duration=timing==null?clip.duration():(timing.get("windupTicks").getAsInt()+timing.get("activeTicks").getAsInt()+timing.get("recoveryTicks").getAsInt())/20.0;
            for(int frame=1;frame<=Math.round(duration*60);frame++) {
                // Validate at the real 60 fps combat clock, not the normalized one-second editor timeline.
                double time=timing==null?frame/60.0:org.fsrpg.client.PoseLayers.phaseTime(clip,frame/3.0,
                        timing.get("windupTicks").getAsInt(),timing.get("activeTicks").getAsInt(),timing.get("recoveryTicks").getAsInt());
                var pose=local(model,clip.id(),time);
                var world=model.worldMatrices(pose);
                for(String side:new String[]{"right","left"}) {
                    var forearm=pose.get(side+"_forearm");
                    if(forearm==null)continue;
                    var hinge=new org.joml.Quaternionf(forearm.fsQuaternion()).rotateY((float)Math.toRadians(-forearm.rotation().y())).transform(new Vector3f(1,0,0));
                    if(hinge.x<.9999f)throw new AssertionError("elbow has non-hinge lateral bending "+clip.id()+" "+side+" frame="+frame);
                    if(Math.abs(forearm.rotation().x())>140.05f||Math.abs(forearm.rotation().y())>60.05f)throw new AssertionError("elbow flexion/pronation exceeds anatomical authoring bound "+clip.id());
                    var hand=pose.get(side+"_hand").fsQuaternion();
                    double flex=Math.toDegrees(Math.acos(Math.clamp(hand.transform(new Vector3f(0,-1,0)).y*-1,-1,1)));
                    check(flex<=26.05,"wrist stays within 26 degrees flexion: "+clip.id()+" "+side+" "+flex);
                    if(side.equals("right")) {
                        var twist=new org.joml.Quaternionf(0,hand.y,0,hand.w).normalize();
                        double wristPronation=Math.toDegrees(2*Math.atan2(twist.y,twist.w));
                        check(Math.abs(wristPronation)<=60.05 && Math.abs(wristPronation+forearm.rotation().y())<=120.05,"pronation is shared continuously, never hidden as a large wrist turn");
                    }
                    for(String bone:new String[]{side+"_arm",side+"_forearm",side+"_hand"}) {
                        double dot=Math.abs(previous.get(bone).fsQuaternion().dot(pose.get(bone).fsQuaternion()));
                        if(dot<Math.cos(Math.toRadians(45)/2))motionViolations.add("arm rotation jump "+clip.id()+" "+bone+" frame="+frame+" degrees="+Math.toDegrees(2*Math.acos(Math.min(1,dot))));
                        double worldDot=Math.abs(previousWorld.get(bone).getUnnormalizedRotation(new org.joml.Quaternionf()).normalize().dot(world.get(bone).getUnnormalizedRotation(new org.joml.Quaternionf()).normalize()));
                        double degrees=Math.toDegrees(2*Math.acos(Math.min(1,worldDot)));maxActualWorldStep=Math.max(maxActualWorldStep,degrees);
                        if(degrees>45.05)motionViolations.add("actual configured 60Hz world step "+clip.id()+" "+bone+" frame="+frame+" degrees="+degrees);
                    }
                }
                previous=pose;
                previousWorld=world;
            }
            checks++;
            System.out.println("Rapier actual combat 60Hz "+clip.id()+" max world joint step="+maxActualWorldStep);
            for(var keys:clip.tracks().values()) {
                for(int key=1;key<keys.size();key++)
                    if(keys.get(key).time()-keys.get(key-1).time()>1.0/60+0.000002)
                        throw new AssertionError("action sample gap exceeds 60 Hz: "+clip.id());
            }
            check(clip.interpolation().equals("linear") && clip.rotationMode().equals("quaternion"), "continuous quaternion sampling " + clip.id());
            if(clip.layer().equals("upper"))check(!clip.tracks().containsKey("right_leg") && !clip.tracks().containsKey("left_leg"), "upper attacks do not overwrite locomotion "+clip.id());
            for (int frame = 0; frame <= 40; frame++) {
                var matrices = sample(model, clip.id(), clip.duration() * frame / 40);
                for (Matrix4f matrix : matrices.values()) {
                    for (float number : matrix.get(new float[16])) if (!Float.isFinite(number)) throw new AssertionError("non-finite matrix " + clip.id());
                }
                if (model.draws(local(model, clip.id(), clip.duration() * frame / 40)).isEmpty()) throw new AssertionError("empty geometry");
            }
            checks++;
        }
        if(!motionViolations.isEmpty())throw new AssertionError(String.join("\n",motionViolations));
        System.out.println("RapierAssetTest passed: " + checks + " checks, " + model.rig().clips().size() * 41 + " sampled frames");
    }
    private static Map<String, org.fsvoxel.animation.FsvoxAnimation.Pose> local(ModelDocument model, String action, double time) {
        AnimationMixer mixer = new AnimationMixer(model.rig());
        if(model.rig().clips().get(action).layer().equals("upper"))mixer.play("rapier_stance");
        String track = mixer.play(action);
        return mixer.seek(track, time, false);
    }
    private static Map<String, Matrix4f> sample(ModelDocument model, String action, double time) { return model.worldMatrices(local(model, action, time)); }
    private static Vector3f direction(Matrix4f matrix, Vector3f axis) { return matrix.transformDirection(axis).normalize(); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
