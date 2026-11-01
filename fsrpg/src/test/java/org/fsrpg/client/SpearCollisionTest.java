package org.fsrpg.client;

import java.lang.reflect.Method;
import java.util.Random;
import org.joml.Vector3f;

/** Preserve all slab decisions when replacing dynamically indexed vectors in the hot path. */
public final class SpearCollisionTest {
    public static void main(String[] args) throws Exception {
        Method slab=SpearConstraints.Space.class.getDeclaredMethod("crossesBox",Vector3f.class,Vector3f.class,float.class,float.class,float.class,float.class);
        slab.setAccessible(true);Random random=new Random(0x5fea172);int cases=0;
        for(int n=0;n<100_000;n++) {
            Vector3f a=point(random),b=point(random);float radius=n%3==0?3:3.5f;
            if(n%4==0)b.setComponent(n%3,a.get(n%3));
            if(n%7==0)b.set(a).add(n%2==0?.0000009f:.0000011f,0,0);
            if(n%11==0)a.setComponent(n%3,Math.nextUp(n%3==1?-radius:25+radius));
            for(float height:new float[]{50,75}) {
                float halfZ=height==50?25:12.5f;
                boolean expected=reference(a,b,25,height,halfZ,radius);
                boolean actual=(boolean)slab.invoke(null,a,b,25f,height,halfZ,radius);
                if(expected!=actual)throw new AssertionError("Slab decision changed: "+a+" -> "+b);
                cases++;
            }
        }
        System.out.println("SpearCollisionTest passed: "+cases+" exact slab decisions including parallel and boundary cases");
    }
    private static Vector3f point(Random r){return new Vector3f(r.nextFloat()*400-200,r.nextFloat()*300-100,r.nextFloat()*400-200);}
    private static boolean reference(Vector3f a,Vector3f to,float halfX,float highY,float halfZ,float radius) {
        var d=new Vector3f(to).sub(a);float low=0,high=1;
        for(int axis=0;axis<3;axis++) {
            float minimum=axis==1?-radius:-(axis==0?halfX:halfZ)-radius;
            float maximum=(axis==0?halfX:axis==1?highY:halfZ)+radius;
            float origin=a.get(axis),delta=d.get(axis);
            if(Math.abs(delta)<.000001f){if(origin<minimum||origin>maximum)return false;}
            else {
                float first=(minimum-origin)/delta,last=(maximum-origin)/delta;
                if(first>last){float swap=first;first=last;last=swap;}
                low=Math.max(low,first);high=Math.min(high,last);if(low>high)return false;
            }
        }
        return true;
    }
}
