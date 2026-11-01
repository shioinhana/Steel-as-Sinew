package org.fsrpg.combat;

import java.util.List;

/** Bounded world-space directions, never client supplied victims or damage. */
public record AttackAim(List<Point> points) {
    public record Point(double yaw, double pitch, double distance) {
        public Point(double yaw,double pitch) {this(yaw,pitch,4.5);}
        public Point {
            SkillDefinition.checkNumber(yaw,"aim yaw",-360,360);
            SkillDefinition.checkNumber(pitch,"aim pitch",-80,80);
            SkillDefinition.checkNumber(distance,"aim distance",.5,96);
        }
    }
    public AttackAim {
        if (points == null || points.isEmpty() || points.size() > 32) throw new IllegalArgumentException("aim requires 1..32 points");
        points = List.copyOf(points);
        double length=0;
        for(int i=1;i<points.size();i++) length+=Math.hypot(wrap(points.get(i).yaw-points.get(i-1).yaw),points.get(i).pitch-points.get(i-1).pitch);
        if(length>240) throw new IllegalArgumentException("aim path exceeds 240 degrees");
    }
    public static AttackAim at(double yaw,double pitch) { return new AttackAim(List.of(new Point(wrap(yaw),Math.clamp(pitch,-80,80)))); }
    public static double wrap(double degrees) { return (degrees%360+540)%360-180; }
    public Point sample(double fraction) {
        double index=Math.clamp(fraction,0,1)*(points.size()-1); int a=(int)index,b=Math.min(a+1,points.size()-1);
        return new Point(wrap(points.get(a).yaw+wrap(points.get(b).yaw-points.get(a).yaw)*(index-a)),
                points.get(a).pitch+(points.get(b).pitch-points.get(a).pitch)*(index-a),
                points.get(a).distance+(points.get(b).distance-points.get(a).distance)*(index-a));
    }
}
