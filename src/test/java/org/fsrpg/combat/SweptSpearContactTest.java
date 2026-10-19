package org.fsrpg.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class SweptSpearContactTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static double[] direction(AttackAim.Point point) {
        double yaw = Math.toRadians(point.yaw()), pitch = Math.toRadians(point.pitch());
        return new double[]{-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
    }
    private static double tipDistance(AttackAim.Point a, AttackAim.Point b, double reach) {
        var x = direction(a); var y = direction(b);
        return reach * Math.sqrt(Math.pow(x[0] - y[0], 2) + Math.pow(x[1] - y[1], 2) + Math.pow(x[2] - y[2], 2));
    }
    public static void main(String[] args) {
        var stationary = SweptSpearContact.cover(AttackAim.at(0, 0), 4.5, .18);
        check(stationary.points().size() == 1 && stationary.padding() == .18 && stationary.coverageMargin() == 0,
                "Stationary spear preserves its original shaft radius");
        check(stationary.swipes().size()==1&&!stationary.swipes().getFirst().swept(),"Stationary ray has corresponding zero swipe metadata");
        var across = new AttackAim(List.of(new AttackAim.Point(-45, -40), new AttackAim.Point(45, 40)));
        var wide = SweptSpearContact.cover(across, 4.5, .02);
        check(wide.points().size() > 17 && wide.points().size() <= SweptSpearContact.MAX_RAYS, "Wide sweep is adaptively covered within a finite budget");
        check(wide.coverageMargin() <= .010000001, "Normal reach retains a sub-centimeter conservative coverage margin");
        check(wide.points().getFirst().equals(across.points().getFirst()) && wide.points().getLast().equals(across.points().getLast()),
                "Both endpoint shafts are covered");
        for (var sample : wide.points()) check(SpearAimField.contains(sample, 0), "Forward rectangle stays convex in yaw and pitch");
        check(wide.swipes().size()==wide.points().size(),"Every sampled shaft has exactly one tangential direction");
        for(int i=0;i<wide.points().size();i++)checkSwipe(wide.swipes().get(i),SpearSwipe.tangent(across.points().getFirst(),across.points().getLast(),wide.points().get(i)),"one-segment ray retains its source path tangent");
        checkMetadataOrder();

        var random = new Random(7707);
        for (int scenario = 0; scenario < 80; scenario++) {
            var a = new AttackAim.Point(random.nextDouble() * 90 - 45, random.nextDouble() * 80 - 40);
            var b = new AttackAim.Point(random.nextDouble() * 90 - 45, random.nextDouble() * 80 - 40);
            var path = new AttackAim(List.of(a, b));
            double reach = scenario % 8 == 0 ? 128 : .1 + random.nextDouble() * 8;
            double radius = .02 + random.nextDouble() * .58;
            var cover = SweptSpearContact.cover(path, reach, radius);
            check(cover.points().size() <= SweptSpearContact.MAX_RAYS && Double.isFinite(cover.padding()), "Custom geometry remains bounded and finite");
            int intervals = cover.points().size() - 1;
            for (int i = 0; i < 401; i++) {
                double fraction = i / 400.0;
                int nearest = (int)Math.round(fraction * intervals);
                var actual = path.sample(fraction);
                var sampled = cover.points().get(nearest);
                check(tipDistance(actual, sampled, reach) <= cover.coverageMargin() + 1e-9,
                        "Every intermediate shaft lies inside the reported conservative ray cover");
            }
        }
        var path = new ArrayList<AttackAim.Point>();
        for (int i = 0; i < 32; i++) path.add(new AttackAim.Point(i * 7.7 - 120, 0));
        var extreme = SweptSpearContact.cover(new AttackAim(path), 128, .02);
        check(extreme.points().size() <= SweptSpearContact.MAX_RAYS && extreme.coverageMargin() > .002,
                "Extreme configured reach uses a larger explicit conservative margin instead of leaving holes");
        var pending=new ArrayList<AttackAim>();
        var first=new AttackAim.Point(-.47,0);pending.add(new AttackAim(List.of(first)));
        for(int i=1;i<256;i++) {
            var next=new AttackAim.Point(i%2==0?-.47:.47,0);
            pending.add(new AttackAim(List.of(first,next)));first=next;
        }
        checkMotionCoverage(pending,4096);
        checkMotionCoverage(pending,511); // A single sampled origin must widen its error instead of dropping movement.
        var unmoving=SweptSpearContact.coverMotion(pending,128,.02,0,4096);
        check(unmoving.originIntervals()==0&&unmoving.translationMargin()==0&&unmoving.rayCount()<=4096,
                "A stationary actor has one origin, with a shared global angular budget");
        var wrapping = new AttackAim(List.of(new AttackAim.Point(170, 0), new AttackAim.Point(-170, 0)));
        var wrapped = SweptSpearContact.cover(wrapping, 4.5, .18);
        for (var point : wrapped.points()) check(Math.abs(AttackAim.wrap(point.yaw() - 180)) <= 10.000001, "Yaw wrap follows the short front arc");
        for (double reach : new double[]{0, -1, 129, Double.NaN}) {
            boolean rejected = false;
            try { SweptSpearContact.cover(AttackAim.at(0, 0), reach, .18); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Invalid reach is rejected");
        }
        System.out.println("SweptSpearContactTest passed: " + checks + " checks");
    }
    private static void checkMotionCoverage(List<AttackAim> paths,int budget) {
        var motion=SweptSpearContact.coverMotion(paths,128,.02,2,budget);
        var rays=motion.angular().points().stream().map(SweptSpearContactTest::direction).toList();
        check(motion.rayCount()==rays.size()*(motion.originIntervals()+1)&&motion.rayCount()<=budget,
                "Hundreds of independent source segments and every translated origin share one finite ray budget");
        for(int segment=0;segment<paths.size();segment++)for(int sample=0;sample<=8;sample++) {
            var actualDirection=direction(paths.get(segment).sample(sample/8.0));
            double nearest=Double.POSITIVE_INFINITY;double[] nearestDirection=null;
            for(var candidate:rays) {
                double distance=Math.pow(actualDirection[0]-candidate[0],2)+Math.pow(actualDirection[1]-candidate[1],2)+Math.pow(actualDirection[2]-candidate[2],2);
                if(distance<nearest){nearest=distance;nearestDirection=candidate;}
            }
            double progress=(segment*.173+sample*.127)%1;
            int count=motion.originIntervals();
            double sampledProgress=count==0?1:Math.round(progress*count)/(double)count;
            for(double radial: new double[]{0,.25,.75,1}) {
                // The origin traverses a real 2-block diagonal, independently of the entire cursor path.
                double x=(actualDirection[0]-nearestDirection[0])*128*radial+(progress-sampledProgress)*1.2;
                double y=(actualDirection[1]-nearestDirection[1])*128*radial+(progress-sampledProgress)*1.6;
                double z=(actualDirection[2]-nearestDirection[2])*128*radial;
                double distance=Math.sqrt(x*x+y*y+z*z);
                check(distance<=motion.angular().coverageMargin()+motion.translationMargin()+1e-9,
                        "Every actual intermediate shaft point and origin lies inside the joint conservative cover");
            }
        }
    }
    private static void checkMetadataOrder(){
        var a=new AttackAim.Point(-30,0);var b=new AttackAim.Point(-10,0);
        var c=new AttackAim.Point(20,-15);var d=new AttackAim.Point(20,15);
        var cover=SweptSpearContact.cover(List.of(new AttackAim(List.of(a,b)),new AttackAim(List.of(c,d))),4.5,.18,12);
        check(cover.swipes().size()==cover.points().size(),"multi-path metadata inventory equals ray inventory");
        check(cover.points().get(0).equals(a)&&cover.points().get(1).equals(c),"initial ray order retains the two independent path origins");
        checkSwipe(cover.swipes().get(0),SpearSwipe.tangent(a,b,a),"first origin uses first path tangent");
        checkSwipe(cover.swipes().get(1),SpearSwipe.tangent(c,d,c),"second origin uses second path tangent");
        int segment=0;
        for(int i=2;i<cover.points().size();i++){
            var sample=cover.points().get(i);check(segment<2,"only admitted source segments produce metadata");
            checkSwipe(cover.swipes().get(i),segment==0?SpearSwipe.tangent(a,b,sample):SpearSwipe.tangent(c,d,sample),"sample metadata remains paired with its own ordered source segment");
            if(sample.equals(segment==0?b:d))segment++;
        }
        check(segment==2,"both source endpoint rays and their tangent metadata are present");
    }
    private static void checkSwipe(SpearSwipe.Vector actual,SpearSwipe.Vector expected,String message){
        check(Math.abs(actual.x()-expected.x())<1e-10&&Math.abs(actual.y()-expected.y())<1e-10&&Math.abs(actual.z()-expected.z())<1e-10,message);
    }
}
