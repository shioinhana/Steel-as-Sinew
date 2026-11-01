package org.fsrpg.client;

import java.nio.file.Path;
import java.util.*;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsvoxel.render.ModelLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Both skins retain bone lengths and real hand/item chains while only the dual shoulder roots move outward. */
public final class PlayerShoulderFitTest {
    private static int checks,samples;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        var model=ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/legacy_dual_blade.fsvoxel"));
        for(boolean slim:new boolean[]{false,true})for(var clip:model.rig().clips().values())for(double fraction:new double[]{0,.13,.39,.71,1}) {
            var original=model.rig().sampleLocal(List.of(clip),clip.duration()*fraction,Map.of());var baseline=new HashMap<>(original);
            if(slim)for(String side:List.of("right","left")){String id=side+"_arm";Pose old=baseline.get(id);V at=old.position();baseline.put(id,new Pose(old.rotation(),new V(at.x()+(side.equals("right")?3.125f:-3.125f),at.y(),at.z()),old.scale()));}
            var before=model.worldMatrices(baseline);var fitted=PlayerShoulderFit.apply(model,original,slim);var after=model.worldMatrices(fitted);
            for(String part:List.of("body","head","right_leg","left_leg"))check(before.get(part).equals(after.get(part),.0001f),"Shoulder fit moves unrelated torso/head/leg "+part);
            for(String side:List.of("right","left")) {
                Vector3f delta=before.get("body").transformDirection(new Vector3f((side.equals("right")?-1:1)*PlayerShoulderFit.DUAL_OUTSET_CM,0,0));
                for(String suffix:List.of("_arm","_forearm","_hand","_item")){
                    String part=side+suffix;Vector3f measured=after.get(part).getTranslation(new Vector3f()).sub(before.get(part).getTranslation(new Vector3f()));
                    check(measured.distance(delta)<.002,"Whole shoulder-to-official-item chain must translate by one outward root delta: "+part);
                    check(fitted.get(part).rotation().equals(original.get(part).rotation()),"Shoulder fit changes source rotations "+part);
                    check(fitted.get(part).scale().equals(original.get(part).scale()),"Shoulder fit changes source scales "+part);
                }
                Matrix4f oldGrip=new Matrix4f(before.get(side+"_hand")).invert().mul(before.get(side+"_item"));
                Matrix4f newGrip=new Matrix4f(after.get(side+"_hand")).invert().mul(after.get(side+"_item"));
                check(oldGrip.equals(newGrip,.002f),"Real official item hand-local grip changes after shoulder fit");
            }
            samples++;
        }
        var sword=ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/legacy_rapier.fsvoxel"));
        var pose=sword.rig().sampleLocal(List.of(sword.rig().clips().get("rapier_idle")),.4,Map.of());
        check(PlayerShoulderFit.apply(sword,pose,false)==pose,"Single sword must not receive dual shoulder widening");
        System.out.println("PlayerShoulderFitTest passed: "+samples+" actual dual source samples / "+checks+" checks (standard/slim, unchanged source curves, attached wrists and official items)");
    }
}
