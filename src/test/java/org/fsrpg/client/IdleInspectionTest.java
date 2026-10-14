package org.fsrpg.client;

import java.nio.charset.StandardCharsets;
import org.fsvoxel.render.ModelLoader;
import org.joml.Vector3f;

public final class IdleInspectionTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        for (int seed = 0; seed < 40; seed++) {
            var idle = new IdleInspection(seed);
            idle.update(0,true,10,30,id -> 5);
            double deadline = idle.deadline();
            check(deadline >= 200 && deadline <= 600,"random delay stays in 10–30 seconds");
            for (int t=1;t<deadline;t++) idle.update(t,true,10,30,id -> 5);
            check(idle.action().isEmpty(),"no early inspection");
            double start = Math.ceil(deadline);
            idle.update(start,true,10,30,id -> 5);
            String first=idle.action(); check(!first.isEmpty(),"stationary deadline starts inspection");
            idle.update(start,true,10,30,id -> 5);
            check(idle.started()==start,"multiple render passes do not restart the animation");
            for(int t=1;t<=100;t++) idle.update(start+t,true,10,30,id -> 5);
            check(idle.action().isEmpty() && idle.deadline()>=start+300,"completion schedules a fresh full idle delay");
            double second=Math.ceil(idle.deadline());
            for(double t=start+101;t<=second;t++) idle.update(t,true,10,30,id -> 5);
            check(!idle.action().isEmpty() && !idle.action().equals(first),"two inspection variants alternate");
            idle.interrupt(second);
            idle.update(second,true,10,30,id -> 5);
            check(idle.action().isEmpty() && Double.isNaN(idle.deadline()),"input immediately cancels the cosmetic action");
            idle.update(second+3,true,10,30,id -> 5);
            check(idle.deadline()>=second+203,"input cannot resume an old countdown");
            idle.update(second+4,false,10,30,id -> 5);
            check(Double.isNaN(idle.deadline()),"movement clears stationary time");
        }
        var idle=new IdleInspection(1);
        idle.update(0,true,30,10,id -> 5);
        check(idle.deadline()==600,"inverted configuration bounds clamp to the minimum");
        idle.update(500,true,30,10,id -> 5);
        check(idle.deadline()==1100,"offscreen clock gaps cannot trigger an instant inspection");
        idle.update(501,true,1,1,id -> 0);
        for(int t=502;t<=522;t++) idle.update(t,true,1,1,id -> 0);
        check(idle.action().isEmpty(),"custom models without inspection clips remain usable");
        try(var in=IdleInspectionTest.class.getResourceAsStream("/assets/fsrpg/fsvoxel/models/rapier.fsvoxel")) {
            var model=ModelLoader.load(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            var stance=new PoseLayers.Playback(model.rig(),"rapier_idle").sample(0,0,0,0,0,.25f,55).pose();
            for(String id:new String[]{IdleInspection.BLADE,IdleInspection.FLOURISH}) {
                var clip=model.rig().clips().get(id);
                check(clip.events().isEmpty() && clip.layer().equals("upper"),"inspection carries no combat events and leaves legs free");
                var playback=new PoseLayers.Playback(model.rig(),id);
                for(double t:new double[]{0,clip.duration()}) {
                    var pose=playback.sample(t,0,t*20,0,0,.25f,55).pose();
                    for(String bone:stance.keySet()) {
                        var a=model.worldMatrices(stance).get(bone);var b=model.worldMatrices(pose).get(bone);
                        check(a.equals(b,.001f),"inspection starts and finishes at neutral stance: "+bone);
                    }
                }
                var previous=playback.sample(0,0,0,0,0,.25f,55).pose();
                for(int i=1;i<=Math.round(clip.duration()*60);i++) {
                    var next=playback.sample(i/60.0,0,i/3.0,0,0,.25f,55).pose();
                    for(String bone:next.keySet()) {
                        double dot=Math.abs(previous.get(bone).fsQuaternion().dot(next.get(bone).fsQuaternion()));
                        if(dot<Math.cos(Math.toRadians(25)/2))throw new AssertionError("joint snap "+id+" "+bone+" at "+i+" degrees="+Math.toDegrees(2*Math.acos(Math.min(1,dot)))+" from="+previous.get(bone).rotation()+" to="+next.get(bone).rotation());
                    }
                    var world=model.worldMatrices(next);
                    var wrist=world.get("right_hand").getTranslation(new Vector3f());
                    check(wrist.z>25,"inspection keeps wrist in front of torso");
                    previous=next;
                }
                var held=model.worldMatrices(playback.sample(1.9,0,38,0,0,.25f,55).pose());
                if(id.equals(IdleInspection.BLADE))check(held.get("right_hand").transformDirection(HeldItemTransform.rapierBladeAxis()).y>.98,"inspection presents the official fencing-grip sword vertically");
                for(String target:new String[]{"rapier_idle","rapier_run","rapier_guard","rapier_guard_heavy","rapier_thrust","rapier_rise"}) {
                    var from=playback.sample(2,0,40,0,0,.25f,55).pose();
                    var to=new PoseLayers.Playback(model.rig(),target).sample(.2,0,40,0,0,.25f,55).pose();
                    check(PresentationMotion.blend(from,to,0)==from && PresentationMotion.blend(from,to,1)==to,
                            "interrupt transition preserves exact departure and target: "+target);
                }
            }
        }
        System.out.println("IdleInspectionTest passed: "+checks+" checks");
    }
    private static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message);checks++; }
}
