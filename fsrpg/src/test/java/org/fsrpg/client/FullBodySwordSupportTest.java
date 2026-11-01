package org.fsrpg.client;

import java.nio.file.Path;
import org.fsvoxel.render.ModelLoader;
import org.joml.Vector3f;

/** Real exported rig sampling, collision receipts, and actual native skin mapping. */
public final class FullBodySwordSupportTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void near(double a,double b,double tolerance,String message){check(Math.abs(a-b)<=tolerance,message+" actual="+a+" expected="+b);}
    private static double smooth(double t){t=Math.clamp(t,0,1);return t*t*(3-2*t);}
    private static double travel(double t){return t<.26?.2*smooth(t/.26):t<.86?.2+.65*smooth((t-.26)/.60):.85+.15*smooth((t-.86)/.14);}
    public static void main(String[] args)throws Exception {
        var model=ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/rapier_fullbody.fsvoxel"));
        for(int stage=1;stage<=5;stage++)for(double obstruction:new double[]{1,.3,0}){
            var clip=model.rig().clips().get("legacy_rapier_attack"+stage+"_upper");
            double distance=new double[]{45,32,40,25,85}[stage-1];var support=new FullBodySwordSupport.State();
            for(int n=0;n<=120;n++){
                double phase=n/120.,at=phase*clip.duration(),expected=distance*travel(phase),actual=Math.min(expected,distance*obstruction);
                var pose=LegacyPlayback.sample(model,clip,at);
                var corrected=support.apply(model,pose,n/6.,stage,expected,actual,expected<.001?1:actual/expected);
                check(corrected==support.apply(model,pose,n/6.,stage,expected,actual,0),"Same timestamp never advances support twice");
                var w=model.worldMatrices(corrected);var waist=w.get("body").transformPosition(new Vector3f(0,-31.25f,0));
                var hips=w.get("right_leg").getTranslation(new Vector3f()).add(w.get("left_leg").getTranslation(new Vector3f())).mul(.5f);
                check(waist.distance(hips)<.01,"Waist remains connected after clipping stage="+stage+" n="+n+" obstruction="+obstruction+" gap="+waist.distance(hips));
                for(String side:new String[]{"right","left"}){
                    var hip=w.get(side+"_leg").getTranslation(new Vector3f());var knee=w.get(side+"_foreleg").getTranslation(new Vector3f());
                    var foot=w.get(side+"_foot").getTranslation(new Vector3f());
                    near(hip.distance(knee),37.5,.002,"Thigh length");near(knee.distance(foot),25,.002,"Shin length");
                    check(foot.y>=12.49,"No foot penetrates floor");
                    var skin=new AuthoredSwordLegDeformation(w.get(side+"_leg"),w.get(side+"_foreleg"),w.get(side+"_foot"),w.get("body"),side);
                    var sole=skin.point(0,12,0);
                    near(sole.y,1.5-(foot.y-12.5)/100,.0001,"6/4/2 native skin sole follows actual exported foot");
                    near(skin.point(-2,6,0).distance(skin.point(2,6,0)),.25,.00001,"Knee cross-section width is preserved");
                    for(float x:new float[]{-2,2})for(float z:new float[]{-2,2}){
                        var target=w.get("body").transformPosition(new Vector3f((side.equals("right")?-12.5f:12.5f)+x*6.25f,-31.25f,-z*6.25f));
                        var nativeTarget=new Vector3f(target.x/100,(150-target.y)/100,-target.z/100);
                        near(skin.point(x,0,z).distance(nativeTarget),0,.00001,"Actual skin waist corner meets torso");
                    }
                    var boot=skin.point(0,12.25f,0);check(boot.isFinite(),"Boot dilation remains finite");
                }
            }
            if(obstruction==0)check(support.supportScale()<.001,"Fully blocked movement settles to a planted stance");
        }
        var guard=model.rig().clips().get("rapier_guard");
        var source=LegacyPlayback.sample(model,guard,0);var support=new FullBodySwordSupport.State();
        check(support.apply(model,source,0,1,Double.NaN,0,0)==source,"Invalid receipt cannot corrupt pose");
        support.reset();near(support.supportScale(),1,0,"Reset restores free movement");
        // Advance normally first, then hit a wall while the trailing foot is
        // planted. Warm observations must preserve its real pre-impact anchor.
        var clip=model.rig().clips().get("legacy_rapier_attack1_upper");
        var tracked=new FullBodySwordSupport.State();double wall=45*travel(.30);double anchor=Double.NaN;
        for(int n=0;n<=58;n++){
            double phase=n/120.,expected=45*travel(phase),actual=Math.min(expected,wall),tick=n/6.;
            var pose=LegacyPlayback.sample(model,clip,phase*clip.duration());
            if(phase<=.30){
                tracked.observe(model,pose,tick,81,expected,actual);
                var raw=model.worldMatrices(pose).get("left_foot").getTranslation(new Vector3f());
                if(n==0)anchor=raw.z+actual;
                near(raw.z+actual,anchor,.12,"Unmodified interpolated approach keeps trailing support within 1.2 mm");
            }else{
                var corrected=tracked.apply(model,pose,tick,81,expected,actual,actual/expected);
                var foot=model.worldMatrices(corrected).get("left_foot").getTranslation(new Vector3f());
                near(foot.z+actual,anchor,.02,"First clipped frame uses pre-collision planted anchor");
                tracked.observe(model,pose,tick,81,expected,actual);
                check(corrected==tracked.apply(model,pose,tick,81,expected,actual,0),"Observe after apply cannot replace cached corrected frame");
            }
        }
        // Same-frame observe then apply must run the correction, not return the
        // observed source object by mistake.
        tracked.reset();var pose=LegacyPlayback.sample(model,clip,0);
        tracked.observe(model,pose,0,82,0,0);
        var corrected=tracked.apply(model,pose,0,82,0,0,0);
        check(corrected!=pose,"Observe cache never suppresses same-frame correction");
        check(corrected==tracked.apply(model,pose,0,82,0,0,1),"Same-frame correction result remains idempotent");
        System.out.println("FullBodySwordSupportTest: "+checks+" checks passed");
    }
}
