package org.fsrpg.client;
import java.nio.file.Path;
import org.fsvoxel.render.ModelLoader;
import org.fsrpg.combat.AttackAim;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import java.util.HashMap;
import java.util.List;
/** The user's ordinary carrying posture is independent of solver hard-geometry tests. */
public final class SpearUnderarmCarryTest {
    public static void main(String[] args)throws Exception {
        var model=ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/spear.fsvoxel"));
        int checks=0;
        for(boolean slim:List.of(false,true)) {
            var state=new SpearMotion.State();
            var playback=new PoseLayers.Playback(model.rig(),"spear_idle");
            for(int frame=0;frame<240;frame++) {
                var source=playback.sample(frame/60.,frame/60.,frame/3.,0,0,.25f,55).pose();
                if(slim) {
                    source=new HashMap<>(source);
                    for(String side:List.of("right","left")) {
                        Pose old=source.get(side+"_arm");V at=old.position();
                        source.put(side+"_arm",new Pose(old.rotation(),new V(at.x()+(side.equals("right")?3.125f:-3.125f),at.y(),at.z()),old.scale()));
                    }
                }
                var raw=SpearMotion.readySource(model.worldMatrices(source),-12,22,50);
                var seed=state.prepare(raw,frame/3.,90);
                var posed=SpearAimPose.apply(model,source,new AttackAim.Point(0,0,10),0,1.62f,seed.rear(),seed.chest(),seed.actual(),seed.continuity(),seed.authoredMotion(),new SpearConstraints.Limits(135,120,1.5f,85,18,true));
                var world=model.worldMatrices(posed);state.commit(world,frame/3.);
                var inverse=new Matrix4f(world.get("body")).invert();
                var rear=inverse.transformPosition(world.get("right_hand").transformPosition(new Vector3f(0,-6.25f,0)));
                var elbow=inverse.transformPosition(world.get("right_forearm").getTranslation(new Vector3f()));
                var shoulder=inverse.transformPosition(world.get("right_arm").getTranslation(new Vector3f()));
                if(elbow.z-shoulder.z>-8)throw new AssertionError("Rear elbow fails to tuck behind shoulder: slim="+slim+" frame="+frame+" elbow="+elbow);
                if(shoulder.z-elbow.z<(shoulder.y-elbow.y)*1.15f)throw new AssertionError("Rear upper arm primarily hangs down instead of pointing back: "+elbow+" shoulder="+shoulder+" rear="+rear+" bodyForward="+world.get("body").transformDirection(new Vector3f(0,0,1))+" shaft="+inverse.transformDirection(world.get("right_hand").transformDirection(new Vector3f(0,0,1)))+" frame="+frame);
                if(rear.y-shoulder.y>-10||rear.y-shoulder.y<-42)throw new AssertionError("Shaft misses underarm height: "+rear);
                if(rear.x>-42||rear.x<-72)throw new AssertionError("Rear grip is not beside the ribs: "+rear);
                var shaft=world.get("right_hand").transformDirection(new Vector3f(0,0,1)).normalize();
                if(!new SpearConstraints.Space(world,new SpearConstraints.Limits(135,120,1.5f,85,18,true)).shaftClear(world.get("right_hand").transformPosition(new Vector3f(0,-6.25f,0)),shaft))throw new AssertionError("Underarm pole crosses body");
                checks+=5;
            }
        }
        System.out.println("SpearUnderarmCarryTest passed: "+checks+" checks, stock/slim 4 seconds at 60Hz, rear elbow >=8cm behind shoulder, shaft beneath armpit");
    }
}
