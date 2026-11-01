package org.fsrpg.avatar;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fsvoxel.render.Geometry;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Source-to-native landmarks and independent joint carriers, using the shipped editable example. */
public final class AvatarPoseTest {
    private static int checks;
    private static void require(boolean condition,String message) {checks++;if(!condition)throw new AssertionError(message);}
    private static void near(float actual,float expected,String message) {require(Math.abs(actual-expected)<1e-6f,message+": "+actual+" != "+expected);}
    private static void point(Vector3f actual,Vector3f expected,String message) {
        near(actual.x,expected.x,message+" X");near(actual.y,expected.y,message+" Y");near(actual.z,expected.z,message+" Z");
    }
    private static int bone(AvatarDocument document,String name) {
        for(int i=0;i<document.bones().size();i++)if(document.bones().get(i).name().equals(name))return i;
        throw new AssertionError("Missing test bone "+name);
    }
    private static Vector3f origin(Matrix4f matrix) {return matrix.getTranslation(new Vector3f());}
    private static Vector3f transform(Matrix4f matrix,float x,float y,float z) {return matrix.transformPosition(new Vector3f(x,y,z));}
    private static void same(Matrix4f actual,Matrix4f expected,String message) {
        float[] a=actual.get(new float[16]),b=expected.get(new float[16]);
        for(int i=0;i<16;i++)near(a[i],b[i],message+"["+i+"]");
    }
    public static void main(String[] args)throws Exception {
        Path source=Path.of("config/fsrpg/avatars/examples/player.bbmodel");
        AvatarDocument document=BlockbenchAvatar.parse(source);AvatarPose.validate(document);
        require(document.bones().size()==6,"example has the six standard independently bound bones");
        var rest=document.restWorld();var nativeRest=AvatarPose.sample(document,Map.of(),Map.of());
        int right=bone(document,"right_arm"),left=bone(document,"left_arm");
        point(origin(nativeRest.get(right)),new Vector3f(-5f/16,2f/16,0),"native right shoulder");
        point(origin(nativeRest.get(left)),new Vector3f(5f/16,2f/16,0),"native left shoulder");
        point(origin(nativeRest.get(bone(document,"head"))),new Vector3f(),"native head pivot");
        point(origin(nativeRest.get(bone(document,"right_leg"))),new Vector3f(-1.9f/16,12f/16,0),"native right leg pivot");
        Vector3f minimum=new Vector3f(Float.POSITIVE_INFINITY),maximum=new Vector3f(Float.NEGATIVE_INFINITY);
        for(int i=0;i<document.bones().size();i++)for(var mesh:document.bones().get(i).meshes().values())for(var face:mesh.faces())for(var vertex:face.vertices()) {
            Vector3f actual=transform(nativeRest.get(i),vertex.x(),vertex.y(),vertex.z());
            Vector3f sourcePoint=transform(rest.get(i),vertex.x(),vertex.y(),vertex.z());
            point(actual,new Vector3f(-sourcePoint.x,1.5f-sourcePoint.y,sourcePoint.z),"source/native vertex");
            minimum.min(actual);maximum.max(actual);
        }
        near(minimum.x,-.5f,"native left/right full width minimum");near(maximum.x,.5f,"native left/right full width maximum");
        near(minimum.y,-.5f,"native head top");near(maximum.y,1.5f,"native soles");
        near(minimum.z,-.25f,"native front");near(maximum.z,.25f,"native back");
        var north=document.bones().get(bone(document,"head")).meshes().get(0).faces().stream().filter(f->f.material().equals("north")).findFirst().orElseThrow();
        Geometry.Vertex front=north.vertices().getFirst();
        point(nativeRest.get(bone(document,"head")).transformDirection(new Vector3f(front.nx(),front.ny(),front.nz())),new Vector3f(0,0,-1),"north/front normal");

        var bones=new ArrayList<>(document.bones());int forearm=bones.size();
        bones.add(new AvatarDocument.Bone("right_forearm",right,new Matrix4f().translation(0,-6f/16,0),Map.of()));
        int decoration=bones.size();bones.add(new AvatarDocument.Bone("ribbon",forearm,new Matrix4f().translation(.1f,-.2f,.05f),Map.of()));
        var articulated=new AvatarDocument(bones,document.textures());var articulatedRest=articulated.restWorld();
        Vector3f shoulder=origin(nativeRest.get(right));
        var nativeRight=new Matrix4f().translation(shoulder).rotateX(.7f).translate(new Vector3f(shoulder).negate());
        var posed=AvatarPose.sample(articulated,Map.of("right_arm",nativeRight),Map.of());
        same(posed.get(right),new Matrix4f(nativeRight).mul(nativeRest.get(right)),"native right action");
        same(posed.get(left),nativeRest.get(left),"right action does not affect left arm");
        same(posed.get(forearm),new Matrix4f(posed.get(right)).mul(bones.get(forearm).local()),"unkeyed forearm follows upper arm");
        same(posed.get(decoration),new Matrix4f(posed.get(forearm)).mul(bones.get(decoration).local()),"decoration inherits forearm carrier");

        Vector3f elbow=origin(articulatedRest.get(forearm));
        var sourceRight=new Matrix4f().translation(elbow).rotateZ(-1.1f).translate(new Vector3f(elbow).negate());
        var solved=AvatarPose.sample(articulated,Map.of("right_arm",nativeRight),Map.of("right_forearm",sourceRight));
        var expectedForearm=new Matrix4f(AvatarPose.TO_NATIVE).mul(sourceRight).mul(articulatedRest.get(forearm));
        same(solved.get(forearm),expectedForearm,"source solved forearm replaces inherited motion");
        same(solved.get(decoration),new Matrix4f(expectedForearm).mul(bones.get(decoration).local()),"decoration follows solved joint");
        same(solved.get(left),nativeRest.get(left),"right forearm solve leaves left arm unchanged");
        require(AvatarPose.binding("Right Upper Arm").equals("right_arm"),"upper-arm alias");
        require(AvatarPose.binding("LEFT_FOREARM").equals("left_forearm"),"forearm alias");
        require(AvatarPose.binding("hair-tip").isEmpty(),"decorative bones use parent inheritance");
        var missing=new ArrayList<>(document.bones());missing.remove(left);
        try {AvatarPose.validate(new AvatarDocument(missing,document.textures()));throw new AssertionError("Missing left arm accepted");}
        catch(IllegalArgumentException expected){checks++;}
        var nonhuman=new AvatarDocument(List.of(new AvatarDocument.Bone("root",-1,new Matrix4f(),Map.of())),document.textures());
        try {AvatarPose.validate(nonhuman);throw new AssertionError("Nonhuman avatar accepted");}
        catch(IllegalArgumentException expected){checks++;}
        for(int i=0;i<document.bones().size();i++)same(document.bones().get(i).local(),rest.get(i),"source rest not mutated");
        System.out.println("AvatarPoseTest passed: "+checks+" checks; source axes, face normals, feet/head landmarks, child inheritance and independent joint carriers");
    }
}
