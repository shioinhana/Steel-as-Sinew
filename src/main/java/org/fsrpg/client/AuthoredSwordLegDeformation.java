package org.fsrpg.client;

import java.util.ArrayList;
import org.fsvoxel.render.Geometry;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Native skin/boots follow exported thigh, shin and foot matrices directly.
 * Original texture coordinates remain unchanged: 0..6 px is the 37.5 cm thigh,
 * 6..10 px is the 25 cm shin, and 10..12 px is the 12.5 cm ankle/foot section.
 * Output is the same player-local render space as LegDeformation (blocks).
 */
public final class AuthoredSwordLegDeformation {
    public static final float[] SECTION_PLANES={.25f,.5f,1,1.5f,5.5f,5.75f,6,6.25f,6.5f,9.5f,9.75f,10,10.25f,10.5f};
    private final Matrix4f thigh,shin,foot,waist;
    private final Quaternionf thighRotation,shinRotation,footRotation,waistRotation;
    public AuthoredSwordLegDeformation(Matrix4f thigh,Matrix4f shin,Matrix4f foot) {
        this(thigh,shin,foot,null,"");
    }
    /** The optional actual torso closes the native skin/armor waist surface,
     * not merely its centre, before blending into the authored hip in 1.5 px.
     */
    public AuthoredSwordLegDeformation(Matrix4f thigh,Matrix4f shin,Matrix4f foot,Matrix4f body,String side) {
        this.thigh=new Matrix4f(thigh);this.shin=new Matrix4f(shin);this.foot=new Matrix4f(foot);
        if(body!=null&&!side.equals("right")&&!side.equals("left"))throw new IllegalArgumentException("Unknown leg side: "+side);
        waist=body==null?new Matrix4f(thigh):new Matrix4f(body).translate(side.equals("right")?-12.5f:12.5f,-31.25f,0);
        thighRotation=thigh.getUnnormalizedRotation(new Quaternionf()).normalize();
        shinRotation=shin.getUnnormalizedRotation(new Quaternionf()).normalize();
        footRotation=foot.getUnnormalizedRotation(new Quaternionf()).normalize();
        waistRotation=waist.getUnnormalizedRotation(new Quaternionf()).normalize();
    }
    /** native local X/Y/Z pixels -> FSVOXEL X/Y/Z cm; axes match nativePoint. */
    private static Vector3f sectionPoint(Matrix4f transform,float x,float y,float z,float originY) {
        return transform.transformPosition(new Vector3f(x*6.25f,-(y-originY)*6.25f,-z*6.25f));
    }
    private static float blend(float t){t=Math.clamp(t,0,1);return t*t*(3-2*t);}
    private static Vector3f bandPoint(Matrix4f a,Matrix4f b,Quaternionf qa,Quaternionf qb,
                                     float x,float y,float z,float ay,float by,float weight) {
        var centre=sectionPoint(a,0,y,0,ay).lerp(sectionPoint(b,0,y,0,by),weight);
        return new Quaternionf(qa).slerp(qb,weight).transform(new Vector3f(x*6.25f,0,-z*6.25f)).add(centre);
    }
    private Vector3f modelPoint(float x,float y,float z) {
        if(y<1.5f)return bandPoint(waist,thigh,waistRotation,thighRotation,x,y,z,0,0,blend(y/1.5f));
        if(y<5.5f)return sectionPoint(thigh,x,y,z,0);
        if(y<6.5f)return bandPoint(thigh,shin,thighRotation,shinRotation,x,y,z,0,6,blend(y-5.5f));
        if(y<9.5f)return sectionPoint(shin,x,y,z,6);
        if(y<10.5f)return bandPoint(shin,foot,shinRotation,footRotation,x,y,z,6,10,blend(y-9.5f));
        return sectionPoint(foot,x,y,z,10);
    }
    private Vector3f modelNormal(float nx,float ny,float nz,float y) {
        var n=new Vector3f(nx,-ny,-nz);
        if(y<1.5f)return new Quaternionf(waistRotation).slerp(thighRotation,blend(y/1.5f)).transform(n).normalize();
        if(y<5.5f)return thigh.transformDirection(n).normalize();
        if(y<6.5f)return new Quaternionf(thighRotation).slerp(shinRotation,blend(y-5.5f)).transform(n).normalize();
        if(y<9.5f)return shin.transformDirection(n).normalize();
        if(y<10.5f)return new Quaternionf(shinRotation).slerp(footRotation,blend(y-9.5f)).transform(n).normalize();
        return foot.transformDirection(n).normalize();
    }
    public Vector3f point(float x,float y,float z) {
        var p=modelPoint(x,y,z);
        return new Vector3f(p.x/100f,(150-p.y)/100f,-p.z/100f);
    }
    public Geometry.Mesh deform(Geometry.Mesh mesh) {
        var faces=new ArrayList<Geometry.Face>();
        for(var face:mesh.faces()){
            var vertices=new ArrayList<Geometry.Vertex>();
            for(var v:face.vertices()){
                var p=point(v.x(),v.y(),v.z());var n=modelNormal(v.nx(),v.ny(),v.nz(),v.y());
                vertices.add(new Geometry.Vertex(p.x,p.y,p.z,v.u(),v.v(),n.x,-n.y,-n.z));
            }
            faces.add(new Geometry.Face(face.material(),vertices));
        }
        return new Geometry.Mesh(faces);
    }
}
