package org.fsrpg.client;

import com.google.gson.JsonObject;
import java.nio.file.*;
import org.fsrpg.config.RpgConfig;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Behavioral steering audit across frame rates, reversals, long turns and shoulder parallax. */
public final class SpearCursorSteeringTest {
    private static final SpearCursorSteering.Settings SETTINGS=new SpearCursorSteering.Settings(31.5,28,28,240,.65,.06);
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void near(double a,double b,double tolerance,String message){check(Double.isFinite(b)&&Math.abs(a-b)<=tolerance,message+" expected="+a+" actual="+b);}
    private static double run(int fps){
        var s=new SpearCursorSteering();s.advance(0,0,24,SETTINGS);s.move(500,0,1000,680);double yaw=0;
        for(int i=1;i<=fps*5;i++){double before=s.x();var step=s.advance(i/(double)fps,0,24,SETTINGS);yaw+=step.yaw();check(step.yaw()>=0&&s.x()>=0&&s.x()<=before,"Cursor contracts monotonically without an opposite turn");}
        near(31.5,yaw+s.x()*31.5,.000001,"Rotation spends cursor deflection without losing the user's requested angle");return yaw;
    }
    public static void main(String[] args)throws Exception {
        double a=run(30),b=run(60),c=run(144);near(a,b,.03,"30/60 Hz turn agrees");near(a,c,.03,"30/144 Hz turn agrees");
        check(SpearCursorSteering.speed(.95,SETTINGS)>SpearCursorSteering.speed(.5,SETTINGS)*10,"Near-limit cursor strongly accelerates turn compared with central aiming");
        double last=0;for(int i=0;i<=1000;i++){double speed=SpearCursorSteering.speed(i/1000.,SETTINGS);check(speed>=last,"Turn speed grows continuously with deflection");check(speed-last<2,"No threshold snaps the camera rate");last=speed;}
        var s=new SpearCursorSteering();s.advance(0,0,24,SETTINGS);s.move(500,0,1000,680);
        check(s.advance(.005,0,24,SETTINGS).yaw()<.2,"First frame accelerates instead of immediately snapping the camera");
        s.move(-1000,0,1000,680);check(s.advance(.02,0,24,SETTINGS).yaw()<0,"Mouse reversal immediately cancels stale opposite angular velocity");
        s.reset();s.advance(0,0,24,SETTINGS);double turn=0;
        for(int i=1;i<=360;i++){s.move(100,0,1000,680);turn+=s.advance(i/60.,0,24,SETTINGS).yaw();}
        check(turn>720,"Sustained edge motion permits repeated complete turns rather than stopping at a joint limit");
        s.reset();s.advance(0,0,24,SETTINGS);s.move(0,680,1000,680);double pitch=0;
        for(int i=1;i<=300;i++)pitch+=s.advance(i/60.,pitch,24,SETTINGS).pitch();near(24,pitch,.000001,"Camera pitch stops at its permitted elevation");check(s.y()>0,"Remaining cursor still supplies vertical spear pose at camera elevation limit");
        s.reset();near(0,s.x()+s.y(),0,"Focus/GUI reset clears stored input");near(0,s.advance(100,0,24,SETTINGS).yaw(),0,"Resume establishes a fresh clock without catch-up");
        s.move(500,0,1000,680);near(0,s.advance(99,0,24,SETTINGS).yaw(),0,"Backward clock cannot rotate");near(0,s.advance(99,0,24,SETTINGS).yaw(),0,"Repeated clock cannot rotate twice");check(s.advance(200,0,24,SETTINGS).yaw()<15,"Large gaps have bounded catch-up");
        for(double yaw:new double[]{-179,0,179})for(double cameraPitch:new double[]{-24,0,24})for(double roll:new double[]{-6,2,6})for(double fov:new double[]{50,70,110})for(double aspect:new double[]{1,16./9,32./9}) {
            double height=720,width=height*aspect;var q=new Quaternionf().rotationYXZ((float)Math.toRadians(180-yaw),(float)Math.toRadians(-cameraPitch),(float)Math.toRadians(-roll));
            var origin=new Vector3f(.65f,.12f,3).rotate(q);
            check(origin.dot(new Vector3f(1,0,0).rotate(q))>.64,"Positive shoulder offset lies on camera right");check(origin.dot(new Vector3f(0,0,-1).rotate(q))<-2.99,"Camera lies behind the eyes");
            for(double ny:new double[]{-.98,-.5,0,.5,.98}){
                var frame=SpearCursorField.steeringFrame(width,height,fov,q,origin,yaw,.7,ny);double y=ny<0?-ny*frame.top():ny*frame.bottom();
                if(ny==0)check(frame.left()<-10&&frame.right()>10,"Camera roll does not collapse the usable horizontal cursor span");
                for(double nx:new double[]{-1,-.5,0,.5,1}){double x=nx<0?-nx*frame.left():nx*frame.right();var ray=SpearCursorField.ray(x,y,height,fov,q);
                    double minimum=SpearCursorField.minimumRayDistance(origin,ray,yaw);
                    for(double distance:new double[]{minimum,64}){var point=new Vector3f(ray).mul((float)distance).add(origin).rotateY((float)Math.toRadians(yaw));check(point.z>0&&Math.abs(point.x)<=point.z*Math.tan(Math.toRadians(31.5))+.0001&&Math.abs(point.y)<=point.z*Math.tan(Math.toRadians(28))+.0001,"Visible cursor ray stays inside the reduced physical range at near and far depths");}
                }
            }
        }
        var report=new JsonObject();report.addProperty("passed",true);report.addProperty("checks",checks);report.addProperty("sustainedTurnDegrees",turn);report.addProperty("framesPerSecondCompared","30/60/144");
        Path path=Path.of(args.length>0?args[0]:"verification/spear-orbit-20261021/cursor-steering.json");Files.createDirectories(path.toAbsolutePath().getParent());Files.writeString(path,RpgConfig.JSON.toJson(report));System.out.println("SpearCursorSteeringTest passed "+checks+" checks; "+path.toAbsolutePath());
    }
}
