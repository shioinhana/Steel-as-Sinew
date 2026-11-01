package org.fsrpg.client;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsvoxel.render.ModelLoader;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A retained lower pose may not leave a gap below an advancing source attack. */
public final class AirPoseJoinTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static double error(Matrix4f a,Matrix4f b){float[] x=a.get(new float[16]),y=b.get(new float[16]);double max=0;for(int i=0;i<16;i++)max=Math.max(max,Math.abs(x[i]-y[i]));return max;}
    public static void main(String[] args)throws Exception {
        Path models=Path.of("src/main/resources/assets/fsrpg/fsvoxel/models");
        for(String file:List.of("spear.fsvoxel","legacy_rapier.fsvoxel","legacy_dual_blade.fsvoxel","legacy_bow.fsvoxel")){
            var model=ModelLoader.load(models.resolve(file));var rest=new HashMap<String,Pose>();for(var part:model.rig().parts().values())rest.put(part.id(),part.rest());
            if(SwordMotion.applies(model))rest=new HashMap<>(SwordMotion.waist(model,rest));
            var takeoff=PresentationMotion.shiftTorso(rest,new PresentationMotion.Balance(3,-7,-2));var takeoffWorld=model.worldMatrices(takeoff);
            for(int frame=0;frame<60;frame++){
                var current=new HashMap<>(rest);Pose body=current.get("body");V at=body.position();
                current.put("body",new Pose(org.fsvoxel.animation.FsvoxAnimation.fromQuaternion(new Quaternionf(body.fsQuaternion()).rotateY(frame*.013f).rotateX(frame*.004f)),new V(at.x()+frame*.7f,at.y()+frame*.4f,at.z()-frame*.25f),body.scale()));
                Pose forearm=current.get("right_forearm");if(forearm!=null)current.put("right_forearm",new Pose(org.fsvoxel.animation.FsvoxAnimation.fromQuaternion(new Quaternionf(forearm.fsQuaternion()).rotateX(-frame*.01f)),forearm.position(),forearm.scale()));
                var original=model.worldMatrices(current);var joined=AirPoseJoin.align(model,current,original,takeoffWorld);var world=model.worldMatrices(joined);
                check(AirPoseJoin.waist(model,world).distance(AirPoseJoin.waist(model,takeoffWorld))<.0001,"Advancing airborne action detached from retained pelvis: "+file);
                Matrix4f beforeBody=new Matrix4f(original.get("body")).invert(),afterBody=new Matrix4f(world.get("body")).invert();
                for(String id:List.of("right_arm","right_forearm","right_hand","right_item","left_arm","left_forearm","left_hand","left_item"))if(original.containsKey(id))check(error(new Matrix4f(beforeBody).mul(original.get(id)),new Matrix4f(afterBody).mul(world.get(id)))<.0002,"Pelvis alignment changed authored upper/grip relative motion: "+file+" / "+id);
                for(String id:current.keySet())check(current.get(id).rotation().equals(joined.get(id).rotation())&&current.get(id).scale().equals(joined.get(id).scale()),"Pelvis alignment changed source rotation or scale: "+id);
                for(String id:PoseLayers.masks(model.rig()).get("lower"))check(current.get(id).equals(joined.get(id)),"Upper alignment mutated retained lower input: "+id);
                check(error(original.get("body"),world.get("body"))>1,"Fixture does not exercise a meaningful missing support/root translation");
            }
        }
        System.out.println("AirPoseJoinTest passed: "+checks+" checks (four rigs, support drop, animated root/torso translation and rotation, exact waist attachment and unchanged authored grip chains)");
    }
}
