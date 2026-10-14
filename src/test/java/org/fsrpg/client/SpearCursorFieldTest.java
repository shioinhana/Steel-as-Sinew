package org.fsrpg.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fsrpg.combat.AttackAim;
import org.fsrpg.combat.SpearAimField;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Independent screen-ray audit over FOV, aspect, orbit pitch, yaw seam and near parallax. */
public final class SpearCursorFieldTest {
    private static int checks, frames, rayTargets, resizeChecks;
    private static double maximumAbsoluteYaw,maximumAbsolutePitch;
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        check(SpearAimField.MAX_YAW_DEGREES==45 && SpearAimField.MAX_PITCH_DEGREES==40 && SpearAimField.CAMERA_PITCH_DEGREES==24,"requested expanded field is exactly yaw45/pitch40/camera24");
        expandedScreenAllowances();
        for(double yaw:new double[]{-179,0,179})for(double pitch:new double[]{-SpearAimField.CAMERA_PITCH_DEGREES,-SpearAimField.CAMERA_PITCH_DEGREES/2,0,SpearAimField.CAMERA_PITCH_DEGREES/2,SpearAimField.CAMERA_PITCH_DEGREES})
            for(double fov:new double[]{50,70,110})for(double aspect:new double[]{1,16./9,32./9})
                for(float distance:new float[]{0,1,4})for(float offset:new float[]{0,.65f})
                    for(double[] requested:new double[][]{{.56,.5},{.9,.85},{.2,.2}}) {
                    double height=720,width=height*aspect;
                    var camera=new Quaternionf().rotationYXZ((float)Math.toRadians(180-yaw),(float)Math.toRadians(-pitch),0);
                    var origin=new Vector3f(offset,0,distance).rotate(camera);
                    double effectiveWidth=SpearCursorField.expandedWidth(requested[0]),effectiveHeight=SpearCursorField.expandedHeight(requested[1]);
                    var frame=SpearCursorField.frame(width,height,fov,effectiveWidth,effectiveHeight,camera,origin,yaw);frames++;
                    check(frame.left()<0 && frame.right()>0 && frame.top()<0 && frame.bottom()>0,"nonempty legal cursor field");
                    check(frame.left()>=-width*effectiveWidth/2 && frame.right()<=width*effectiveWidth/2 && frame.top()>=-height*effectiveHeight/2 && frame.bottom()<=height*effectiveHeight/2,"expanded maximum retains original configured preference "+requested[0]+"/"+requested[1]);
                    check(frame.left()>=-width*.48 && frame.right()<=width*.48 && frame.top()>=-height*.48 && frame.bottom()<=height*.48,"effective cursor field never exceeds96 percent of viewport");
                    var resizedActual=SpearCursorField.frame(width,height,110,SpearCursorField.expandedWidth(.2),SpearCursorField.expandedHeight(.2),camera,origin,yaw);
                    var corrected=SpearCursorField.move(resizedActual,frame.right()+100,frame.top()-100,0,0);
                    check(corrected.overflowX()==0 && corrected.overflowY()==0,"actual FOV/configuration shrink cannot create turn without input");
                    check(corrected.x()==resizedActual.right() && corrected.y()==resizedActual.top(),"resize repositions stale cursor inside the new field");resizeChecks++;
                    for(int ix=0;ix<=8;ix++)for(int iy=0;iy<=8;iy++) {
                        double x=frame.left()+(frame.right()-frame.left())*ix/8,y=frame.top()+(frame.bottom()-frame.top())*iy/8;
                        var ray=SpearCursorField.ray(x,y,height,fov,camera);
                        double minimum=SpearCursorField.minimumRayDistance(origin,ray,yaw);
                        for(double depth:new double[]{minimum,(minimum+64)/2,64}) {
                            var target=new Vector3f(ray).mul((float)depth).add(origin);
                            var direction=new Vector3f(target).normalize();
                            var point=new AttackAim.Point(AttackAim.wrap(Math.toDegrees(Math.atan2(-direction.x,direction.z))),-Math.toDegrees(Math.asin(direction.y)),target.length());
                            maximumAbsoluteYaw=Math.max(maximumAbsoluteYaw,Math.abs(AttackAim.wrap(point.yaw()-yaw)));
                            maximumAbsolutePitch=Math.max(maximumAbsolutePitch,Math.abs(point.pitch()));rayTargets++;
                            check(SpearAimField.contains(point,yaw),"visible field and finite target are in the server field: yaw="+yaw+" pitch="+pitch+" point="+point);
                            var local=new Vector3f(target).rotateY((float)Math.toRadians(yaw));
                            check(local.z>=SpearAimField.MIN_FORWARD_DISTANCE-.0001,"target remains ahead of the actor");
                        }
                    }
                }
        for(double yaw:new double[]{-179,0,179}) {
            check(SpearAimField.contains(new AttackAim.Point(AttackAim.wrap(yaw+SpearAimField.MAX_YAW_DEGREES),SpearAimField.MAX_PITCH_DEGREES),yaw),"inclusive server boundary");
            check(!SpearAimField.contains(new AttackAim.Point(AttackAim.wrap(yaw+SpearAimField.MAX_YAW_DEGREES+.01),0),yaw),"server rejects wide yaw");
            check(!SpearAimField.contains(new AttackAim.Point(yaw,SpearAimField.MAX_PITCH_DEGREES+.01),yaw),"server rejects steep pitch");
            check(!SpearAimField.contains(new AttackAim.Point(AttackAim.wrap(yaw+180),0),yaw),"server rejects opposite side");
            for(int horizontal:new int[]{-1,1})for(int vertical:new int[]{-1,1})
                check(SpearAimField.contains(new AttackAim.Point(AttackAim.wrap(yaw+horizontal*SpearAimField.MAX_YAW_DEGREES),vertical*SpearAimField.MAX_PITCH_DEGREES),yaw),"server includes every exact expanded corner across yaw seam");
            check(!SpearAimField.contains(new AttackAim.Point(AttackAim.wrap(yaw-SpearAimField.MAX_YAW_DEGREES-.01),0),yaw),"server rejects negative wide yaw");
            check(!SpearAimField.contains(new AttackAim.Point(yaw,-SpearAimField.MAX_PITCH_DEGREES-.01),yaw),"server rejects negative steep pitch");
        }
        var resized=new SpearCursorField.Frame(-25,25,-10,10);
        var zero=SpearCursorField.move(resized,100,-100,0,0);
        check(zero.x()==25 && zero.y()==-10 && zero.overflowX()==0 && zero.overflowY()==0,"FOV resize never turns without mouse movement");
        var inside=SpearCursorField.move(resized,100,-100,-5,4);
        check(inside.x()==20 && inside.y()==-6 && inside.overflowX()==0 && inside.overflowY()==0,"new in-box input after shrink never turns");
        var edge=SpearCursorField.move(resized,25,0,8,0);
        check(edge.x()==25 && edge.overflowX()==8,"only fresh overflow turns the actor");
        for(double z:new double[]{.5,1,2,4})for(double yaw:new double[]{1-SpearAimField.MAX_YAW_DEGREES,0,SpearAimField.MAX_YAW_DEGREES-1})for(double pitch:new double[]{1-SpearAimField.MAX_PITCH_DEGREES,0,SpearAimField.MAX_PITCH_DEGREES-1}) {
            double x=-z*Math.tan(Math.toRadians(yaw)),y=-Math.hypot(x,z)*Math.tan(Math.toRadians(pitch));
            var near=SpearAimField.target(x,y,z,0);
            check(near!=null && SpearAimField.contains(near,0),"valid near enemy remains selectable");
            check(Math.abs(AttackAim.wrap(near.yaw()-yaw))<.000001 && Math.abs(near.pitch()-pitch)<.000001,"near-target attack direction remains exactly the clicked enemy");
            check(near.distance()*Math.cos(Math.toRadians(near.yaw()))*Math.cos(Math.toRadians(near.pitch()))>=4.5-.000001,"near-target animation stays on verified forward plane");
        }
        check(SpearAimField.target(0,0,-1,0)==null,"camera-side target cannot aim backwards");
        check(SpearAimField.target(0,1,.01,0)==null,"vertical camera hit is ignored without constructing an illegal packet");
        for(double yaw:new double[]{-179,0,179})for(double relative:new double[]{-SpearAimField.MAX_YAW_DEGREES-20,-SpearAimField.MAX_YAW_DEGREES,0,SpearAimField.MAX_YAW_DEGREES,SpearAimField.MAX_YAW_DEGREES+20})for(double pitch:new double[]{-SpearAimField.MAX_PITCH_DEGREES-20,-SpearAimField.MAX_PITCH_DEGREES,0,SpearAimField.MAX_PITCH_DEGREES,SpearAimField.MAX_PITCH_DEGREES+20}) {
            var bridge=SpearAimField.constrain(new AttackAim.Point(AttackAim.wrap(yaw+relative),pitch,1),yaw);
            check(SpearAimField.contains(bridge,yaw),"entry/return bridge remains inside shared frame");
            check(bridge.distance()*Math.cos(Math.toRadians(AttackAim.wrap(bridge.yaw()-yaw)))*Math.cos(Math.toRadians(bridge.pitch()))>=4.5-.000001,"entry/return bridge retains safe finite depth");
        }
        sphericalBridges();
        exactConfiguredDefault();
        JsonObject report=new JsonObject();report.addProperty("passed",true);report.addProperty("checks",checks);
        report.addProperty("screenFrames",frames);report.addProperty("finiteRayTargets",rayTargets);report.addProperty("actualResizeZeroInputCases",resizeChecks);
        report.addProperty("maximumYawDegrees",SpearAimField.MAX_YAW_DEGREES);report.addProperty("maximumPitchDegrees",SpearAimField.MAX_PITCH_DEGREES);
        report.addProperty("maximumCameraPitchDegrees",SpearAimField.CAMERA_PITCH_DEGREES);
        report.addProperty("maximumObservedAbsoluteYawDegrees",maximumAbsoluteYaw);report.addProperty("maximumObservedAbsolutePitchDegrees",maximumAbsolutePitch);
        report.addProperty("defaultRequestedScreenWidthFraction",.56);report.addProperty("defaultRequestedScreenHeightFraction",.5);
        report.addProperty("defaultEffectiveScreenWidthFraction",SpearCursorField.expandedWidth(.56));report.addProperty("defaultEffectiveScreenHeightFraction",SpearCursorField.expandedHeight(.5));
        report.addProperty("screenAllowanceViewportCap",.96);
        report.addProperty("matrix","yaw -179/0/179; camera pitch negative limit/half/0/half/limit; FOV50/70/110; aspect1/16:9/32:9; camera distance0/1/4m; shoulder0/.65m; configured .56/.5, .9/.85 and .2/.2 expanded by3x/2x then cappedat.96; each81 screen points at near/intermediate/far depth");
        report.add("pixelExpansionExample",pixelExpansionExample());
        Path path=Path.of(args.length>0?args[0]:"verification/action-limits-20261013/cursor-field-expanded.json");
        Files.createDirectories(path.toAbsolutePath().getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("SpearCursorFieldTest passed: "+checks+" checks / "+frames+" screen frames / "+rayTargets+" finite targets; yaw+/-"+SpearAimField.MAX_YAW_DEGREES+" pitch+/-"+SpearAimField.MAX_PITCH_DEGREES+" camera+/-"+SpearAimField.CAMERA_PITCH_DEGREES+"; "+path.toAbsolutePath());
    }

    private static void expandedScreenAllowances() {
        for(double[] value:new double[][]{{.56,.5,.96,.96},{.9,.85,.96,.96},{.2,.2,.6,.4}}) {
            check(Math.abs(SpearCursorField.expandedWidth(value[0])-value[2])<.000001,"configured width expands3x up to viewportcap");
            check(Math.abs(SpearCursorField.expandedHeight(value[1])-value[3])<.000001,"configured height expands2x up to viewportcap");
        }
        for(double value:new double[]{.2,.32,.48,.56,.85,.9,1}) {
            check(SpearCursorField.expandedWidth(value)<=.96 && SpearCursorField.expandedHeight(value)<=.96,"viewportcap applies to all existing valid client preferences");
        }
    }

    private static void sphericalBridges() {
        for(double anchor:new double[]{-179,0,179})for(double pitch:new double[]{-SpearAimField.MAX_PITCH_DEGREES,SpearAimField.MAX_PITCH_DEGREES}) {
            double distance=SpearAimField.MIN_FORWARD_DISTANCE/(Math.cos(Math.toRadians(SpearAimField.MAX_YAW_DEGREES))*Math.cos(Math.toRadians(pitch)));
            var from=new AttackAim.Point(AttackAim.wrap(anchor-SpearAimField.MAX_YAW_DEGREES),pitch,distance);
            var to=new AttackAim.Point(AttackAim.wrap(anchor+SpearAimField.MAX_YAW_DEGREES),pitch,distance);
            boolean bulged=false;
            for(int frame=0;frame<=60;frame++) {
                var spherical=PoseLayers.blendAim(from,to,frame/60f);bulged|=Math.abs(spherical.pitch())>SpearAimField.MAX_PITCH_DEGREES+.01;
                var bridge=SpearAimField.constrain(spherical,anchor);
                check(SpearAimField.contains(bridge,anchor),"actual spherical edge bridge is clamped to shared expanded limits");
                double depth=bridge.distance()*Math.cos(Math.toRadians(AttackAim.wrap(bridge.yaw()-anchor)))*Math.cos(Math.toRadians(bridge.pitch()));
                check(depth>=SpearAimField.MIN_FORWARD_DISTANCE-.000001,"spherical bridge keeps forward plane");
                if(frame==0||frame==60) {
                    var expected=frame==0?from:to;
                    check(Math.abs(AttackAim.wrap(bridge.yaw()-expected.yaw()))<.000001 && Math.abs(bridge.pitch()-expected.pitch())<.000001 && Math.abs(bridge.distance()-expected.distance())<.000001,"constraining spherical bridge retains valid endpoints exactly");
                }
            }
            check(bulged,"edge bridge must expose spherical pitch overshoot before the shared clamp");
        }
    }
    private static void exactConfiguredDefault() {
        double width=720,height=720;
        var camera=new Quaternionf().rotationY((float)Math.PI);
        double expandedWidth=SpearCursorField.expandedWidth(.56),expandedHeight=SpearCursorField.expandedHeight(.5);
        var frame=SpearCursorField.frame(width,height,50,expandedWidth,expandedHeight,camera,new Vector3f(),0);
        // At this FOV all four configured edges fit the angular field. Only the intentional .02px safety margin is removed.
        check(Math.abs(frame.left()+width*expandedWidth/2-.02)<.000001 && Math.abs(frame.right()-width*expandedWidth/2+.02)<.000001,"expanded defaultwidth allowance is retained when legal");
        check(Math.abs(frame.top()+height*expandedHeight/2-.02)<.000001 && Math.abs(frame.bottom()-height*expandedHeight/2+.02)<.000001,"expanded defaultheight allowance is retained when legal");
    }
    private static JsonObject pixelExpansionExample() {
        double width=1280,height=720,fov=65,cameraDistance=4;
        double projection=height/(2*Math.tan(Math.toRadians(fov/2)));
        // An independent closed form for a centered level camera: eye-plane slopes are shrunk by4.5/(4.5+cameraDistance).
        double parallax=SpearAimField.MIN_FORWARD_DISTANCE/(SpearAimField.MIN_FORWARD_DISTANCE+cameraDistance);
        double oldWidth=2*Math.min(width*.56/2,projection*parallax*Math.tan(Math.toRadians(15)))-.04;
        double oldHeight=2*Math.min(height*.5/2,projection*parallax*Math.tan(Math.toRadians(20)))-.04;
        var camera=new Quaternionf().rotationY((float)Math.PI);var origin=new Vector3f(0,0,(float)cameraDistance).rotate(camera);
        var frame=SpearCursorField.frame(width,height,fov,SpearCursorField.expandedWidth(.56),SpearCursorField.expandedHeight(.5),camera,origin,0);
        double newWidth=frame.right()-frame.left(),newHeight=frame.bottom()-frame.top();
        var value=new JsonObject();value.addProperty("viewportWidthPixels",width);value.addProperty("viewportHeightPixels",height);
        value.addProperty("verticalFovDegrees",fov);value.addProperty("cameraDistanceMeters",cameraDistance);
        value.addProperty("oldYawLimitDegrees",15);value.addProperty("oldPitchLimitDegrees",20);
        value.addProperty("oldWidthPixelsClosedForm",oldWidth);value.addProperty("oldHeightPixelsClosedForm",oldHeight);
        value.addProperty("newWidthPixels",newWidth);value.addProperty("newHeightPixels",newHeight);
        value.addProperty("screenWidthRatio",newWidth/oldWidth);value.addProperty("screenHeightRatio",newHeight/oldHeight);
        value.addProperty("angularWidthRatio",SpearAimField.MAX_YAW_DEGREES/15);value.addProperty("angularHeightRatio",SpearAimField.MAX_PITCH_DEGREES/20);
        value.addProperty("interpretation","Saved .56/.5 preferences are retained and expanded3x/2x for current screen allowance, cappedat96percent viewport. Shared angular range45/40 and projection tangent, near parallax, FOV/aspect and camera pitch determine visible pixels; pixel ratios are not fixed3x/2x.");
        return value;
    }
}
