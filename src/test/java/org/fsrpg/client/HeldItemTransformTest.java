package org.fsrpg.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Verifies the carrier against Minecraft's actual handheld.json and the authored grip socket. */
public final class HeldItemTransformTest {
    private static int checks;
    private static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);checks++;}
    private static Vector3f vector(JsonObject object,String key) {
        var array=object.getAsJsonArray(key);return new Vector3f(array.get(0).getAsFloat(),array.get(1).getAsFloat(),array.get(2).getAsFloat());
    }
    private static Matrix4f officialDisplay(boolean left) throws Exception {
        try(var jar=new ZipFile("build/moddev/artifacts/neoforge-21.11.45-client-extra-aka-minecraft-resources.jar");
            var input=jar.getInputStream(jar.getEntry("assets/minecraft/models/item/handheld.json"))) {
            JsonObject right=JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("display").getAsJsonObject(left?"thirdperson_lefthand":"thirdperson_righthand");
            var r=vector(right,"rotation").mul((float)Math.PI/180);var t=vector(right,"translation").div(16);var scale=vector(right,"scale");
            if(left){r.y=-r.y;r.z=-r.z;t.x=-t.x;}
            return new Matrix4f().translation(t).rotateXYZ(r.x,r.y,r.z).scale(scale).translate(-.5f,-.5f,-.5f);
        }
    }
    public static void main(String[] arguments) throws Exception {
        Matrix4f display=officialDisplay(false);
        float deg=(float)(Math.PI/180);
        Matrix4f spearDisplay=new Matrix4f().translation(0,2f/16,2f/16).rotateXYZ(5*deg,270*deg,-40*deg).scale(1.7f,1.7f,.85f).translate(-.5f,-.5f,-.5f);
        check(display.equals(HeldItemTransform.standardDisplay(),.000001f),"carrier calibration matches official handheld display");
        check(officialDisplay(true).equals(display,.000001f),"official left-hand mirrored display shares the neutral carrier");
        Vector3f fixedDualAxis=new Vector3f(0,-(float)Math.cos(Math.toRadians(50)),(float)Math.sin(Math.toRadians(50)));
        check(HeldItemTransform.dualBladeAxis().distance(fixedDualAxis)<.000001f,"dual public axis defines the fixed 50 degree grip independently of the carrier");
        HeldItemTransform.dualBladeAxis().zero();
        check(HeldItemTransform.dualBladeAxis().distance(fixedDualAxis)<.000001f,"each dual axis lookup returns an independent mutable vector");
        JsonObject socket;
        try(var lines=Files.lines(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/rapier.fsvoxel"))) {
            socket=lines.filter(line->line.startsWith("socket\t")&&line.contains("\"weapon_grip\""))
                    .map(line->JsonParser.parseString(line.substring(line.indexOf('\t')+1)).getAsJsonObject()).findFirst().orElseThrow();
        }
        check(socket.get("part").getAsString().equals("right_hand"),"authored grip belongs to wrist chain");
        Vector3f grip=vector(socket,"position");
        Matrix4f nativeSpace=new Matrix4f().translation(0,1.5f,0).scale(.01f,-.01f,-.01f);
        for(float x:new float[]{-37.5f,-34.375f,0,83})for(float yaw:new float[]{0,90,180,-90})
            for(float pitch:new float[]{-180,-90,0,90,175})for(float roll:new float[]{-160,-55,0,55,160}) {
                Matrix4f wrist=new Matrix4f().translation(x,87.5f,83).rotateXYZ((float)Math.toRadians(pitch),(float)Math.toRadians(yaw),(float)Math.toRadians(roll));
                Matrix4f unchanged=new Matrix4f(wrist), carrier=HeldItemTransform.rightHand(wrist), posed=new Matrix4f(carrier).mul(display);
                Vector3f expected=nativeSpace.transformPosition(wrist.transformPosition(new Vector3f(grip)));
                Vector3f actual=posed.transformPosition(new Vector3f(3.5f/16,3.5f/16,.5f));
                check(actual.distance(expected)<.00001f,"official pixel grip aligns with authored palm");
                Vector3f expectedAxis=nativeSpace.transformDirection(wrist.transformDirection(HeldItemTransform.rapierBladeAxis())).normalize();
                Vector3f actualAxis=posed.transformDirection(new Vector3f(1,1,0)).normalize();
                check(actualAxis.distance(expectedAxis)<.00001f,"official rapier follows its fixed 50 degree fencing grip");
                check(Math.abs(carrier.determinant()-1)<.00001f,"official item keeps its original meter scale and winding");
                check(wrist.equals(unchanged),"carrier does not mutate animation wrist matrix");
                check(Math.abs(posed.transformDirection(new Vector3f(0,0,1)).length()-.85)<.00001f,"official handheld model scale preserved");
                Matrix4f dualCarrier=HeldItemTransform.dualCarrier(wrist);
                Matrix4f dualPosed=new Matrix4f(dualCarrier).mul(display);
                Vector3f dualGrip=dualPosed.transformPosition(new Vector3f(3.5f/16,3.5f/16,.5f));
                Vector3f fistAxis=nativeSpace.transformDirection(wrist.transformDirection(new Vector3f(0,-1,0))).normalize();
                Vector3f acrossFist=nativeSpace.transformDirection(wrist.transformDirection(new Vector3f(0,0,1))).normalize();
                Vector3f dualAxis=nativeSpace.transformDirection(wrist.transformDirection(HeldItemTransform.dualBladeAxis())).normalize();
                Vector3f hiltAxis=dualPosed.transformDirection(new Vector3f(1,1,0)).normalize();
                check(dualGrip.distance(expected)<.00001f,"dual official handle crosses the stock/slim palm center without grip drift");
                check(hiltAxis.distance(dualAxis)<.00001f,"actual official dual sword follows its fixed 50 degree closed grip");
                Vector3f bladeFace=dualPosed.transformDirection(new Vector3f(0,0,1)).normalize();
                Vector3f expectedFace=nativeSpace.transformDirection(wrist.transformDirection(new Vector3f(1,0,0))).normalize();
                check(bladeFace.distance(expectedFace)<.00001f,"dual blade face normal stays on palm +X throughout arm rotations");
                check(Math.abs(hiltAxis.dot(fistAxis)-Math.cos(Math.toRadians(50)))<.00001f,"dual handle forms 50 degrees with the closed hand without bending its wrist");
                check(Math.abs(dualCarrier.determinant()-1)<.00001f && Math.abs(dualPosed.transformDirection(new Vector3f(0,0,1)).length()-.85)<.00001f,
                        "dual grip preserves official model scale and winding");
                check(wrist.equals(unchanged),"dual carrier never changes the authored wrist matrix");
                Matrix4f spear=HeldItemTransform.spearCarrier(wrist).mul(spearDisplay);
                check(spear.transformPosition(new Vector3f(.81f,.19f,.5f)).distance(expected)<.00001f,"official spear rear grip remains in the stock/slim palm");
                check(spear.transformDirection(new Vector3f(-1,1,0)).normalize().distance(acrossFist)<.00001f,"official spear pole follows transverse fist +Z");
                check(Math.abs(spear.transformDirection(new Vector3f(-1,1,0)).normalize().dot(fistAxis))<.00001f,"official pole passes across the fist at 90 degrees");
                check(Math.abs(spear.transformDirection(new Vector3f(0,0,1)).length()-.85f)<.00001f,"official spear thickness and item display scale are preserved");
                check(wrist.equals(unchanged),"spear carrier does not mutate the authored wrist");
            }
        System.out.println("HeldItemTransformTest passed: "+checks+" checks (official display, palm/socket, blade axis, scale, winding, nonmutation)");
    }
}
