package org.fsrpg.client;

public final class ShoulderCameraMotionTest {
    private static int checks;
    private static void near(double expected,double actual,String why){checks++;if(Math.abs(expected-actual)>1e-9)throw new AssertionError(why+": "+actual);}
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args){
        var motion=new ShoulderCameraMotion();var goal=new ShoulderCameraMotion.Offset(.65,.12,-3);
        near(0,motion.sample(true,0,goal,.5,.12).z(),"Draw starts exactly at the eyes");
        near(-1.5,motion.sample(true,.25,goal,.5,.12).z(),"Quarter second reaches half shoulder travel");
        near(-3,motion.sample(true,.5,goal,.5,.12).z(),"Shoulder entry completes in half a second");
        near(-3,motion.sample(false,1,goal,.5,.12).z(),"Sheathing starts without a positional jump");
        near(-1.5,motion.sample(false,1.25,goal,.5,.12).z(),"Exit traverses the same smooth envelope");
        near(0,motion.sample(false,1.5,goal,.5,.12).z(),"Exit finishes at eyes before first-person restoration");
        check(motion.settledOff(),"Completed exit permits restoring the previous camera");
        motion.reset();motion.sample(true,0,goal,.5,.12);motion.sample(true,.25,goal,.5,.12);
        near(-1.5,motion.sample(false,.25,goal,.5,.12).z(),"Mid-entry cancellation does not jump");
        near(0,motion.sample(false,.75,goal,.5,.12).z(),"Cancelled entry still returns smoothly");
        motion.reset();motion.sample(true,0,goal,.5,.12);motion.sample(true,.5,goal,.5,.12);
        var side=new ShoulderCameraMotion.Offset(-.65,.12,-1);
        var after=motion.sample(true,.51,side,.5,.12);
        check(after.x()<.65&&after.x()>-.65&&after.z()>-3&&after.z()<-1,"Shoulder switches and collision distance changes are smoothed without overshoot");
        for(int i=2;i<100;i++){after=motion.sample(true,.5+i*.01,side,.5,.12);check(after.x()>=-.65&&after.x()<=.65,"Side transition stays bounded");}
        for(int rate:new int[]{30,60,144}){
            var retained=new ShoulderCameraMotion();retained.sample(true,0,goal,.5,.12);retained.sample(true,.5,goal,.5,.12);
            var turned=new ShoulderCameraMotion.Offset(3,.12,.65);
            var sample=goal;
            for(int i=1;i<=rate;i++)sample=retained.sample(true,.5+i/(double)rate,turned,.5,.12);
            near(turned.x()+(goal.x()-turned.x())*Math.exp(-1/.12),sample.x(),"Original 0.12-second follow is frame-rate independent at "+rate+" Hz");
            check(sample.x()<turned.x(),"Removing free-cursor aiming retains finite position lag after a turn");
        }
        System.out.println("ShoulderCameraMotionTest passed: "+checks+" checks");
    }
}
