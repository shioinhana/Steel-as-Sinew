package org.fsrpg.client;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelLoader;

/** Long-running gameplay must keep moving after every authored cycle, rather than hold its final key. */
public final class LegacyLoopTest {
    private static int checks;
    private static void check(boolean value,String message) {checks++;if(!value)throw new AssertionError(message);}
    private static float difference(Map<String,Pose> a,Map<String,Pose> b,String part) {
        var p=a.get(part);var q=b.get(part);
        float dot=Math.abs(p.fsQuaternion().dot(q.fsQuaternion()));
        return p.position().vector().distance(q.position().vector())+2*(float)Math.acos(Math.clamp(dot,-1,1));
    }
    public static void main(String[] args)throws Exception {
        Path assets=Path.of("src/main/resources/assets/fsrpg/fsvoxel/models");
        for(String kind:List.of("dual_blade","rapier","bow"))for(boolean fp:new boolean[]{false,true}) {
            var model=ModelLoader.load(assets.resolve("legacy_"+kind+(fp?"_fp":"")+".fsvoxel"));
            int looping=0;
            for(var clip:model.rig().clips().values()) {
                if(clip.loop()) {
                    for(double phase:new double[]{.07,.23,.57,.81}) {
                        var expected=model.rig().sampleLocal(List.of(clip),phase*clip.duration(),Map.of());
                        for(int cycles:new int[]{4,50,1000}) {
                            var actual=LegacyPlayback.sample(model,clip,(cycles+phase)*clip.duration());
                            for(String part:expected.keySet())check(difference(expected,actual,part)<.002,"Imported loop stops after multiple cycles: "+clip.id()+" / "+part);
                        }
                    }
                    looping++;
                } else {
                    var end=model.rig().sampleLocal(List.of(clip),clip.duration(),Map.of());
                    var held=LegacyPlayback.sample(model,clip,clip.duration()*100+10);
                    for(String part:end.keySet())check(difference(end,held,part)<.002,"One-shot attack/draw must hold its own endpoint: "+clip.id());
                }
            }
            check(looping>0,"Expected original looping clips in "+model.rig().id());
            if(!fp)for(String gait:List.of("walk_hold","run_hold")) {
                var clip=model.rig().clips().get("legacy_"+kind+"_"+gait);
                double age=clip.duration()*1000;
                var a=LegacyPlayback.sample(model,clip,age+clip.duration()*.13);
                var b=LegacyPlayback.sample(model,clip,age+clip.duration()*.61);
                float motion=Math.max(difference(a,b,"right_leg"),difference(a,b,"left_leg"));
                check(motion>.05,"Moving leg bones frozen at long play time: "+kind+" / "+gait);
            }
        }
        for(String phase:List.of("READY","GUARDING"))check(SpearStrokePose.retraction(phase,-1,1,1,2)==1,"Default spear is withdrawn");
        check(SpearStrokePose.retraction("WINDUP",0,1,1,2)==1,"Thrust starts withdrawn");
        check(SpearStrokePose.retraction("ACTIVE",2,1,1,2)==0,"Thrust/held spear remains extended");
        check(SpearStrokePose.retraction("RECOVERY",2,1,1,2)==0,"Recovery starts extended");
        check(SpearStrokePose.retraction("RECOVERY",4,1,1,2)==1,"Recovery withdraws completely");
        System.out.println("LegacyLoopTest passed: "+checks+" checks (actual imported locomotion at 4/50/1000 cycles, live leg motion, one-shot endpoints, spear withdrawal)");
    }
}
