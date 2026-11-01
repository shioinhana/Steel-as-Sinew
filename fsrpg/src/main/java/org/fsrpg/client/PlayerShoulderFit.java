package org.fsrpg.client;

import java.util.HashMap;
import java.util.Map;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.animation.FsvoxAnimation.V;
import org.fsvoxel.render.ModelDocument;

/** Small shoulder-root fit; complete arm, hand and official item chains move together. */
public final class PlayerShoulderFit {
    public static final float DUAL_OUTSET_CM=1.5625f;
    private PlayerShoulderFit() {}

    public static Map<String,Pose> apply(ModelDocument model,Map<String,Pose> source,boolean slim) {
        boolean dual=model.rig().id().contains("dual_blade");
        if(!slim&&!dual)return source;
        return fit(source,slim,dual);
    }
    public static Map<String,Pose> dualFirstPerson(Map<String,Pose> source){return fit(source,false,true);}
    private static Map<String,Pose> fit(Map<String,Pose> source,boolean slim,boolean dual) {
        var result=new HashMap<>(source);
        for(String side:new String[]{"right","left"}) {
            String id=side+"_arm";Pose old=source.get(id);if(old==null)continue;
            float sign=side.equals("right")?-1:1;
            float offset=(slim?-sign*3.125f:0)+(dual?sign*DUAL_OUTSET_CM:0);
            var at=old.position();result.put(id,new Pose(old.rotation(),new V(at.x()+offset,at.y(),at.z()),old.scale()));
        }
        return Map.copyOf(result);
    }
}
