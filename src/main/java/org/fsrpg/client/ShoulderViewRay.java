package org.fsrpg.client;

import org.joml.Matrix4d;
import org.joml.Matrix4fc;
import org.joml.Vector4d;

/** Inverts the actual world projection, including native view bob and hurt tilt. */
public final class ShoulderViewRay {
    public record Point(double x, double y, double z) {
        public boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    }
    public record Projection(double x, double y, double depth, boolean inFront) {}
    public static final class Frame {
        private final Point anchor, origin, direction;
        private final Matrix4d clipFromRelative;
        private Frame(Point anchor, Point origin, Point direction, Matrix4d matrix) {
            this.anchor=anchor; this.origin=origin; this.direction=direction; this.clipFromRelative=matrix;
        }
        public Point origin() { return origin; }
        public Point direction() { return direction; }
        public Projection project(Point world) {
            var clip=clipFromRelative.transform(new Vector4d(world.x-anchor.x,world.y-anchor.y,world.z-anchor.z,1));
            if (!finite(clip) || Math.abs(clip.w)<1e-12) return new Projection(0,0,0,false);
            return new Projection(clip.x/clip.w,clip.y/clip.w,clip.z/clip.w,clip.w>0);
        }
    }
    private ShoulderViewRay() {}

    /** Matrices are copied: no live renderer state is mutated or retained. */
    public static Frame from(Point camera, Matrix4fc view, Matrix4fc projection) {
        if (!camera.finite()) return null;
        var combined=new Matrix4d(projection).mul(new Matrix4d(view));
        if (!combined.isFinite() || !Double.isFinite(combined.determinant()) || Math.abs(combined.determinant())<1e-15) return null;
        var inverse=new Matrix4d(combined).invert();
        // The perspective eye maps to homogeneous (0,0,z,0). This also recovers
        // the translated eye when bobbing is included in the projection matrix.
        var eye=inverse.transform(new Vector4d(0,0,1,0));
        var near=inverse.transform(new Vector4d(0,0,-1,1));
        if (!finite(eye)||!finite(near)||Math.abs(eye.w)<1e-12||Math.abs(near.w)<1e-12) return null;
        eye.div(eye.w); near.div(near.w);
        double x=near.x-eye.x,y=near.y-eye.y,z=near.z-eye.z;
        double length=Math.sqrt(x*x+y*y+z*z);
        if (!Double.isFinite(length)||length<1e-12) return null;
        Point origin=new Point(camera.x+eye.x,camera.y+eye.y,camera.z+eye.z);
        Point direction=new Point(x/length,y/length,z/length);
        return origin.finite()&&direction.finite()?new Frame(camera,origin,direction,combined):null;
    }
    private static boolean finite(Vector4d v) {
        return Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z)&&Double.isFinite(v.w);
    }
}
