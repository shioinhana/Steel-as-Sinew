package org.fsrpg.combat;

import java.util.ArrayList;
import java.util.List;

/** Finite ray cover of the whole shaft sweep, with a conservative, explicit interpolation error. */
public final class SweptSpearContact {
    public static final int MAX_RAYS = 2048;
    public record Cover(List<AttackAim.Point> points, double padding, double coverageMargin) {
        public Cover { points = List.copyOf(points); }
    }
    public record MotionCover(Cover angular,int originIntervals,double translationMargin,int rayCount) {}
    private record Segment(AttackAim.Point from,AttackAim.Point to,double radians) {}
    private SweptSpearContact() {}

    /**
     * Intersect every ray with the victim's box expanded by padding, then apply the normal block occlusion check.
     * Every intermediate shaft lies within coverageMargin of a sampled ray: the spherical speed of linear
     * yaw/pitch interpolation is bounded by hypot(yawDelta, pitchDelta). At normal weapon reaches the extra
     * conservative margin is at most 1 cm; unusually large configured reaches retain coverage within MAX_RAYS.
     */
    public static Cover cover(AttackAim path, double reach, double radius) {
        if(path==null)throw new IllegalArgumentException("missing spear sweep");
        return cover(List.of(path),reach,radius,MAX_RAYS);
    }

    /** All independently legal segments share one budget, including their endpoint shafts. */
    public static Cover cover(List<AttackAim> paths,double reach,double radius,int maxRays) {
        if (paths == null || paths.isEmpty() || paths.size()>256 || !Double.isFinite(reach) || reach <= 0 || reach > 128
                || !Double.isFinite(radius) || radius < .02 || radius > .6)
            throw new IllegalArgumentException("invalid spear sweep geometry");
        if(maxRays<1||maxRays>8192)throw new IllegalArgumentException("invalid spear ray budget");
        var segments=new ArrayList<Segment>();
        double total = 0;
        for(var path:paths)for(int i=1;i<path.points().size();i++) {
            var a=path.points().get(i-1);var b=path.points().get(i);
            double length=Math.toRadians(Math.hypot(AttackAim.wrap(b.yaw()-a.yaw()),b.pitch()-a.pitch()));
            if(length<1e-12)continue;
            segments.add(new Segment(a,b,length));total+=length;
        }
        int minimum=paths.size()+segments.size();
        if(maxRays<minimum)throw new IllegalArgumentException("ray budget cannot preserve all source segment endpoints");
        int[] counts=new int[segments.size()];
        double requestedMargin = Math.min(.01, radius * .1);
        double step = 2 * requestedMargin / reach;
        long requested=0;
        for(int i=0;i<counts.length;i++){counts[i]=Math.max(1,(int)Math.ceil(segments.get(i).radians()/step));requested+=counts[i];}
        int intervalBudget=maxRays-paths.size();
        if(requested>intervalBudget) {
            int additional=intervalBudget-segments.size();
            for(int i=0;i<counts.length;i++)counts[i]=1+(int)Math.floor(additional*segments.get(i).radians()/total);
        }
        var result = new ArrayList<AttackAim.Point>();
        for(var path:paths)result.add(path.points().getFirst());
        double margin = 0;
        for (int i = 0; i < segments.size(); i++) {
            var segment=segments.get(i);var a=segment.from();var b=segment.to();int count=counts[i];
            margin = Math.max(margin, reach * segment.radians() / (2 * count));
            for (int j = 1; j <= count; j++) {
                double t = j / (double)count;
                result.add(new AttackAim.Point(AttackAim.wrap(a.yaw() + AttackAim.wrap(b.yaw() - a.yaw()) * t),
                        a.pitch() + (b.pitch() - a.pitch()) * t, a.distance() + (b.distance() - a.distance()) * t));
            }
        }
        if (result.size() > maxRays) throw new IllegalStateException("spear ray budget exceeded");
        return new Cover(result, radius + margin, margin);
    }

    /** Joint angular/translation budget. Fewer origin samples increase the explicit conservative translation error. */
    public static MotionCover coverMotion(List<AttackAim> paths,double reach,double radius,double originTravel,int maxRays) {
        if(!Double.isFinite(originTravel)||originTravel<0||originTravel>2)throw new IllegalArgumentException("invalid spear origin travel");
        int minimum=0;
        if(paths==null||paths.isEmpty())throw new IllegalArgumentException("missing spear sweep");
        for(var path:paths){
            minimum++;
            for(int i=1;i<path.points().size();i++) {
                var a=path.points().get(i-1);var b=path.points().get(i);
                if(Math.toRadians(Math.hypot(AttackAim.wrap(b.yaw()-a.yaw()),b.pitch()-a.pitch()))>=1e-12)minimum++;
            }
        }
        if(maxRays<minimum)throw new IllegalArgumentException("motion budget cannot preserve all source endpoints");
        int desired=originTravel<1e-12?0:Math.max(1,Math.min(32,(int)Math.ceil(originTravel/Math.max(.02,radius))));
        int origins=Math.min(desired,maxRays/minimum-1);
        var angular=cover(paths,reach,radius,maxRays/(origins+1));
        double translation=origins==0?originTravel:originTravel/(2*origins);
        return new MotionCover(angular,origins,translation,angular.points().size()*(origins+1));
    }
}
