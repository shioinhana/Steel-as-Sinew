package org.fsrpg.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.fsvoxel.render.Geometry;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Original torso UVs: rigid chest, gradual lumbar twist, attached horizontal waist. */
public final class TorsoDeformation {
    public static final float[] SECTION_PLANES={6,6.5f,7,7.5f,8,8.5f,9,9.5f,10,10.5f,11,11.5f,12};
    private record Section(Vector3f center,Quaternionf rotation) {}
    private final Matrix4f body;
    private final Quaternionf chest,pelvisRotation;
    private final Vector3f pelvis;
    private final Map<Float,Section> sections=new HashMap<>();
    public TorsoDeformation(Matrix4f body,Locomotion.LowerBody lower) {
        this.body=new Matrix4f(body);
        var q=body.getUnnormalizedRotation(new Quaternionf()).normalize();
        chest=new Quaternionf(q.x,-q.y,-q.z,q.w);
        pelvisRotation=new Quaternionf().rotationY(lower.pelvisYaw());
        var r=lower.right().pose();var l=lower.left().pose();
        pelvis=new Vector3f((r.x()+l.x())*.5f,(r.y()+l.y())*.5f,(r.z()+l.z())*.5f);
    }
    private Section section(float y) {
        return sections.computeIfAbsent(y,v->{
            float t=Math.clamp((v-6)/6,0,1);t=t*t*(3-2*t);
            var p=body.transformPosition(new Vector3f(0,75-v*6.25f,0));
            var center=new Vector3f(p.x/6.25f,(150-p.y)/6.25f,-p.z/6.25f);
            center.lerp(new Vector3f(pelvis).add(0,v-12,0),t);
            return new Section(center,new Quaternionf(chest).slerp(pelvisRotation,t));
        });
    }
    public Vector3f point(float x,float y,float z) {
        var s=section(y);return s.rotation().transform(new Vector3f(x,0,z)).add(s.center()).div(16);
    }
    public Vector3f normal(float x,float y,float z,float nx,float ny,float nz) {
        var q=section(y).rotation();
        var dx=q.transform(new Vector3f(1,0,0));var dz=q.transform(new Vector3f(0,0,1));
        var dy=point(x,y+.002f,z).sub(point(x,y-.002f,z)).mul(16/.004f);
        return new Vector3f(dy).cross(dz).mul(nx)
                .add(new Vector3f(dz).cross(dx).mul(ny)).add(new Vector3f(dx).cross(dy).mul(nz)).normalize();
    }
    public Geometry.Mesh deform(Geometry.Mesh source) {
        var faces=new ArrayList<Geometry.Face>(source.faces().size());
        for(var face:source.faces()) {
            var vertices=new ArrayList<Geometry.Vertex>(face.vertices().size());
            for(var v:face.vertices()) {
                var p=point(v.x(),v.y(),v.z());var n=normal(v.x(),v.y(),v.z(),v.nx(),v.ny(),v.nz());
                vertices.add(new Geometry.Vertex(p.x,p.y,p.z,v.u(),v.v(),n.x,n.y,n.z));
            }
            faces.add(new Geometry.Face(face.material(),vertices));
        }
        return new Geometry.Mesh(faces);
    }
}
