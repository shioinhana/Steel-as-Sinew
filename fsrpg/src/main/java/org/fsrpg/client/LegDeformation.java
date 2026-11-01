package org.fsrpg.client;

import java.util.ArrayList;
import org.fsvoxel.render.Geometry;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Six-pixel thigh/shin, using the original leg/boot texture and a volume-preserving knee band. */
public final class LegDeformation {
    public static final float[] SECTION_PLANES = {5.5f, 5.75f, 6, 6.25f, 6.5f, 10.5f, 11, 11.5f};
    private final Quaternionf thigh, shin, foot;
    private final boolean flatSole;
    private final Quaternionf pelvis;
    private final Vector3f hip, knee;
    public LegDeformation(float x, float y, float z, float xRot, float yRot, float zRot, float flexion) {
        this(x,y,z,xRot,yRot,zRot,flexion,false);
    }
    public LegDeformation(float x, float y, float z, float xRot, float yRot, float zRot, float flexion, boolean flatSole) {
        this(x,y,z,xRot,yRot,zRot,flexion,flatSole,Float.NaN);
    }
    public LegDeformation(float x,float y,float z,float xRot,float yRot,float zRot,float flexion,boolean flatSole,float pelvisYaw) {
        this.flatSole = flatSole;
        pelvis=Float.isFinite(pelvisYaw)?new Quaternionf().rotationY(pelvisYaw):null;
        hip = new Vector3f(x,y,z);
        thigh = new Quaternionf().rotationZYX(zRot,yRot,xRot-flexion*.5f);
        shin = new Quaternionf(thigh).rotateX(flexion);
        var ahead = shin.transform(new Vector3f(0,0,1));
        foot = new Quaternionf().rotationY((float) Math.atan2(ahead.x, ahead.z));
        knee = thigh.transform(new Vector3f(0,6,0)).add(hip);
    }
    private Quaternionf section(float y) {
        if(pelvis!=null&&y<1.5f) {float t=Math.clamp(y/1.5f,0,1);return new Quaternionf(pelvis).slerp(thigh,t*t*(3-2*t));}
        if (flatSole && y > 10.5f) return new Quaternionf(shin).slerp(foot, Math.clamp((y-10.5f)/1.5f,0,1));
        return new Quaternionf(thigh).slerp(shin,Math.clamp(y-5.5f,0,1));
    }
    public Vector3f point(float x,float y,float z) {
        if(flatSole && y > 10.5f) return shin.transform(new Vector3f(0,Math.min(y,12)-6,0))
                .add(section(y).transform(new Vector3f(x,0,z))).add(knee).div(16);
        if(y<5.5f) return thigh.transform(new Vector3f(0,y,0)).add(section(y).transform(new Vector3f(x,0,z))).add(hip).div(16);
        return section(y).transform(new Vector3f(x,y-6,z)).add(knee).div(16);
    }
    public Geometry.Mesh deform(Geometry.Mesh mesh) {
        var faces=new ArrayList<Geometry.Face>();
        for(var face:mesh.faces()) {
            var vertices=new ArrayList<Geometry.Vertex>();
            for(var v:face.vertices()) {
                var p=point(v.x(),v.y(),v.z());
                var n=section(v.y()).transform(new Vector3f(v.nx(),v.ny(),v.nz())).normalize();
                vertices.add(new Geometry.Vertex(p.x,p.y,p.z,v.u(),v.v(),n.x,n.y,n.z));
            }
            faces.add(new Geometry.Face(face.material(),vertices));
        }
        return new Geometry.Mesh(faces);
    }
    public static float knee(float walkPhase,float moving,float maxDegrees,boolean left) {
        float swing=Math.max(0,(float)Math.sin(walkPhase*.6662f)*(left?-1:1));
        return (float)Math.toRadians(maxDegrees)*Math.clamp(moving,0,1)*swing*swing;
    }
}
