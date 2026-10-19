package org.fsrpg.combat;

import java.util.List;

/** World-direction evidence from actual ray paths and outcome tests for grounded/airborne knockback. */
public final class SpearSwipeTest {
    private static int checks;
    public static void main(String[] args){
        realPathTangents();directionAndStrength();downwardGroundAndAir();diagonalAndStationary();
        System.out.println("SpearSwipeTest passed: "+checks+" checks");
    }
    private static void realPathTangents(){
        for(var path:List.of(path(-40,0,40,0),path(40,0,-40,0),path(170,-20,-170,30),
                path(-170,30,170,-20),path(-25,-35,30,25),path(0,-60,0,60))){
            var from=path.points().getFirst();var to=path.points().getLast();
            for(int i=0;i<=100;i++){
                double fraction=i/100.0;var at=path.sample(fraction);var tangent=SpearSwipe.tangent(from,to,at);
                var before=direction(path.sample(Math.max(0,fraction-1e-5)));var after=direction(path.sample(Math.min(1,fraction+1e-5)));
                var numeric=new SpearSwipe.Vector(after.x()-before.x(),after.y()-before.y(),after.z()-before.z()).normalized();
                check(tangent.swept()&&Double.isFinite(tangent.length()),"forward/reverse/wrapped source rays have a finite tangent");
                check(dot(tangent,numeric)>.99999999,"tangent follows the actual advancing shaft instead of a body-forward push");
                near(0,dot(tangent,direction(at)),"tangent is perpendicular to the shaft");
                var reverse=SpearSwipe.tangent(to,from,at);near(-1,dot(tangent,reverse),"reversing cursor path reverses knockback at the same shaft");
            }
        }
    }
    private static void directionAndStrength(){
        var tangent=SpearSwipe.tangent(new AttackAim.Point(-20,0),new AttackAim.Point(20,0),new AttackAim.Point(0,0));
        vector(new SpearSwipe.Vector(-1,0,0),tangent,"increasing yaw at forward shaft swipes toward world negative X");
        var half=SpearSwipe.apply(tangent,.8,.5,0,true,SpearSwipe.Vector.ZERO);
        vector(new SpearSwipe.Vector(-.4,0,0),half.velocity(),"half multiplier preserves the actual lateral direction");
        check(half.applied()&&!half.downwardSlam(),"ground lateral contact applies without adding a jump");
        var full=SpearSwipe.apply(tangent,.8,1,0,true,SpearSwipe.Vector.ZERO);
        near(.8,full.velocity().length(),"full configured multiplier uses the original knockback magnitude");
        var resisted=SpearSwipe.apply(tangent,.8,.5,.75,true,SpearSwipe.Vector.ZERO);
        vector(new SpearSwipe.Vector(-.1,0,0),resisted.velocity(),"vanilla resistance attenuates only the swipe impulse");
        var old=new SpearSwipe.Vector(.8,.25,-.4);
        for(double multiplier:List.of(0.0,.5)){
            var disabled=SpearSwipe.apply(tangent,.8,multiplier,multiplier==0?0:1,true,old);
            check(!disabled.applied()&&disabled.velocity().equals(old),"zero strength or full resistance never brakes the old velocity");
        }
    }
    private static void downwardGroundAndAir(){
        var down=SpearSwipe.tangent(new AttackAim.Point(0,-35),new AttackAim.Point(0,35),new AttackAim.Point(0,0));
        vector(new SpearSwipe.Vector(0,-1,0),down,"increasing pitch is a true downward shaft swipe");
        var old=new SpearSwipe.Vector(.8,.42,-.6);
        var grounded=SpearSwipe.apply(down,.6,.5,0,true,old);
        check(!grounded.applied()&&!grounded.downwardSlam(),"pure downward swipe has no extra effect on a grounded target");
        check(grounded.velocity().equals(old),"ground rejection preserves existing horizontal velocity and jump impulse exactly");
        var airborne=SpearSwipe.apply(down,.6,.5,0,false,old);
        check(airborne.applied()&&airborne.downwardSlam(),"airborne downward swipe creates an actual slam");
        vector(new SpearSwipe.Vector(.4,-.3,-.3),airborne.velocity(),"slam immediately replaces ascent with downward velocity");
        var falling=SpearSwipe.apply(down,.6,.5,0,false,new SpearSwipe.Vector(0,-.2,0));
        near(-.5,falling.velocity().y(),"a falling target gains the actual downward impulse");
    }
    private static void diagonalAndStationary(){
        var mixed=SpearSwipe.tangent(new AttackAim.Point(-15,-20),new AttackAim.Point(15,20),new AttackAim.Point(0,0));
        var air=SpearSwipe.apply(mixed,.8,.5,.5,false,SpearSwipe.Vector.ZERO);
        vector(new SpearSwipe.Vector(mixed.x()*.2,mixed.y()*.2,mixed.z()*.2),air.velocity(),"diagonal air swipe retains all tangential components");
        var ground=SpearSwipe.apply(mixed,.8,.5,.5,true,SpearSwipe.Vector.ZERO);
        near(mixed.x()*.2,ground.velocity().x(),"ground diagonal keeps admitted lateral component");
        near(0,ground.velocity().y(),"ground diagonal clips downwards instead of adding vanilla bounce");
        near(mixed.z()*.2,ground.velocity().z(),"ground diagonal invents no forward component");
        check(ground.applied()&&!ground.downwardSlam(),"mixed ground contact can push sideways without a slam");
        var point=new AttackAim.Point(45,20);var stationary=SpearSwipe.tangent(point,point,point);
        check(!stationary.swept(),"stationary aim is not a swipe");
        var old=new SpearSwipe.Vector(.5,-.1,.6);var none=SpearSwipe.apply(stationary,1,.5,0,false,old);
        check(!none.applied()&&none.velocity().equals(old),"stationary spear neither pushes nor brakes velocity");
        var depthOnly=SpearSwipe.tangent(new AttackAim.Point(45,20,2),new AttackAim.Point(45,20,8),point);
        check(!depthOnly.swept(),"changing aim depth alone cannot manufacture tangential knockback");
    }
    private static AttackAim path(double a,double b,double c,double d){return new AttackAim(List.of(new AttackAim.Point(a,b),new AttackAim.Point(c,d)));}
    private static SpearSwipe.Vector direction(AttackAim.Point point){double yaw=Math.toRadians(point.yaw()),pitch=Math.toRadians(point.pitch());return new SpearSwipe.Vector(-Math.sin(yaw)*Math.cos(pitch),-Math.sin(pitch),Math.cos(yaw)*Math.cos(pitch));}
    private static double dot(SpearSwipe.Vector a,SpearSwipe.Vector b){return a.x()*b.x()+a.y()*b.y()+a.z()*b.z();}
    private static void vector(SpearSwipe.Vector expected,SpearSwipe.Vector actual,String why){near(expected.x(),actual.x(),why+" X");near(expected.y(),actual.y(),why+" Y");near(expected.z(),actual.z(),why+" Z");}
    private static void near(double expected,double actual,String why){check(Math.abs(expected-actual)<1e-8,why+": "+expected+" != "+actual);}
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}
