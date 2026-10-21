package org.fsrpg.archery;

import java.util.ArrayList;
import java.util.List;
import static org.fsrpg.archery.ArcheryBallistics.*;

/**
 * Real collision-shape exits, connected walls, oblique shots, partial shapes, the fourth face and
 * default vanilla flight; no Minecraft runtime required.
 */
public final class ArcheryBallisticsTest {
    private static int checks;
    private static void check(boolean value,String label) { checks++;if(!value)throw new AssertionError(label); }
    private static void near(double actual,double expected,String label) { check(Math.abs(actual-expected)<1e-8,label+": "+actual); }
    private static boolean contains(Box b,Vector p) { return p.x()>=b.minX()&&p.x()<b.maxX()&&p.y()>=b.minY()&&p.y()<b.maxY()&&p.z()>=b.minZ()&&p.z()<b.maxZ(); }
    private static Penetration wall(int thickness,int budget,double slow) {
        List<Box> boxes=new ArrayList<>();
        for(int x=0;x<thickness;x++)boxes.add(new Box(x,0,0,x+1,1,1));
        var first=new Obstacle(0,new Vector(0,.5,.5),List.of(boxes.getFirst()));
        return penetrate(first,new Vector(1,0,0),budget,slow,p->{
            for(int i=0;i<boxes.size();i++)if(contains(boxes.get(i),p))return new Obstacle(i,p,List.of(boxes.get(i)));
            return null;
        });
    }
    /** Blocks connected only by a shared corner still form a real staircase a diagonal arrow crosses. */
    private static Penetration staircase(int length,int budget) {
        List<Box> boxes=new ArrayList<>();
        for(int i=0;i<length;i++)boxes.add(new Box(i,i,i,i+1,i+1,i+1));
        var first=new Obstacle(0,new Vector(0,0,0),List.of(boxes.getFirst()));
        return penetrate(first,new Vector(1,1,1).normalized(),budget,.2,p->{
            for(int i=0;i<boxes.size();i++)if(contains(boxes.get(i),p))return new Obstacle(i,p,List.of(boxes.get(i)));
            return null;
        });
    }
    public static int run() {
        checks=0;
        // --- real box exits, including reverse hits and shape-dependent distances ---
        var cube=List.of(new Box(0,0,0,1,1,1));
        near(exitDistance(new Vector(0,.5,.5),new Vector(1,0,0),cube),1,"axis-aligned cube exit");
        near(exitDistance(new Vector(1,.5,.5),new Vector(-1,0,0),cube),1,"reverse cube exit");
        near(exitDistance(new Vector(.5,.5,1),new Vector(0,0,-1),cube),1,"reverse axis on another face");
        near(exitDistance(new Vector(0,0,0),new Vector(1,1,1).normalized(),cube),Math.sqrt(3),"diagonal traverses more than the old 1.05");
        check(Double.isNaN(exitDistance(new Vector(0,2,.5),new Vector(1,0,0),cube)),"parallel ray outside shape is not a wall");
        check(Double.isNaN(exitDistance(new Vector(2,.5,.5),new Vector(1,0,0),cube)),"shape behind the ray is not a wall");
        check(Double.isNaN(exitDistance(new Vector(.5,.5,.5),new Vector(1,0,0),List.of())),"air has no exit");
        var slab=List.of(new Box(0,0,0,1,.5,1));
        near(exitDistance(new Vector(.5,0,.5),new Vector(0,1,0),slab),.5,"slab uses its actual half-height shape");
        near(exitDistance(new Vector(.5,.5,.5),new Vector(0,-1,0),slab),.5,"downward ray exits the real slab bottom");
        var multipart=List.of(new Box(0,0,0,.2,1,1),new Box(.8,0,0,1,1,1));
        near(exitDistance(new Vector(0,.5,.5),new Vector(1,0,0),multipart),1,"one block with multiple boxes counted once");
        var stair=List.of(new Box(0,0,0,1,.5,1),new Box(0,.5,0,.5,1,1));
        near(exitDistance(new Vector(0,.25,.5),new Vector(1,0,0),stair),1,"stairs exit the sub-box the ray actually meets");
        near(exitDistance(new Vector(0,.75,.5),new Vector(1,0,0),stair),.5,"upper stair step uses its own shape");
        // --- connected walls consume one charge per real block, the fourth always stops ---
        for(int thickness=1;thickness<=8;thickness++) {
            var result=wall(thickness,3,.2);
            check(result.blocks()==Math.min(3,thickness),"each physical wall block consumes one charge "+thickness);
            near(result.speedFactor(),Math.pow(.8,Math.min(3,thickness)),"speed scales once per block "+thickness);
            check((result.stoppedAt()==null)==(thickness<=3),"three blocks pass; fourth blocks the arrow "+thickness);
            near(result.position().x(),Math.min(thickness,3)+EPSILON,"position is the real far face or fourth block "+thickness);
            if(thickness>3)check(result.stoppedAt().identity().equals(3),"terminal impact retains fourth block identity");
        }
        check(wall(1,0,.2).blocks()==0&&wall(1,0,.2).stoppedAt()!=null,"ordinary arrows remain at the first wall");
        check(wall(2,1,.2).blocks()==1&&wall(2,1,.2).stoppedAt()!=null,"a single charge cannot cross a two-block wall");
        near(wall(2,1,.2).position().x(),1+EPSILON,"single charge stops on the second block face");
        check(wall(4,0,0).stoppedAt()!=null&&wall(4,0,0).speedFactor()==1,"zero budget never penetrates");
        // --- diagonal staircase: corner-connected shapes are real contacts ---
        var diagonal=staircase(8,3);
        check(diagonal.blocks()==3,"diagonal staircase consumes three real blocks");
        check(diagonal.stoppedAt()!=null&&diagonal.stoppedAt().identity().equals(3),"diagonal staircase stops on the fourth");
        near(diagonal.speedFactor(),Math.pow(.8,3),"diagonal staircase slows once per block");
        check(!contains(new Box(2,2,2,3,3,3),diagonal.position()),"diagonal crossing fully leaves the triply-charged block");
        // --- a shape that cannot be exited must stop instead of tunnelling ---
        var graze=new Obstacle("graze",new Vector(0,.5,.5),List.of(new Box(0,0,0,0,1,1)));
        var grazeResult=penetrate(graze,new Vector(1,0,0),3,.2,p->{throw new AssertionError("degenerate shape must not probe");});
        check(grazeResult.blocks()==0&&grazeResult.stoppedAt()==graze,"zero-thickness shape stops the arrow");
        var empty=new Obstacle("empty",new Vector(0,.5,.5),List.of());
        check(penetrate(empty,new Vector(1,0,0),3,.2,p->null).blocks()==0,"interaction-shape hit without collision boxes stops");
        // --- argument validation ---
        var valid=new Obstacle("cube",new Vector(0,.5,.5),cube);
        for(int bad:new int[]{-1,MAX_BLOCKS+1})
            try { penetrate(valid,new Vector(1,0,0),bad,0,p->null);throw new AssertionError("budget "+bad+" accepted"); }
            catch(IllegalArgumentException expected) { checks++; }
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-.1,1.1})
            try { penetrate(valid,new Vector(1,0,0),1,bad,p->null);throw new AssertionError("slow "+bad+" accepted"); }
            catch(IllegalArgumentException expected) { checks++; }
        try { penetrate(null,new Vector(1,0,0),1,0,p->null);throw new AssertionError("null obstacle accepted"); }
        catch(IllegalArgumentException expected) { checks++; }
        try { new Vector(0,0,0).normalized();throw new AssertionError("zero direction accepted"); }
        catch(IllegalArgumentException expected) { checks++; }
        try { new Vector(Double.NaN,0,0);throw new AssertionError("nonfinite vector accepted"); }
        catch(IllegalArgumentException expected) { checks++; }
        try { new Box(1,0,0,0,1,1);throw new AssertionError("inverted box accepted"); }
        catch(IllegalArgumentException expected) { checks++; }
        // --- vanilla per-tick order: move, float inertia, gravity ---
        var velocity=new Vector(3,1,-2);
        var air=nextVelocity(velocity,false);
        var water=nextVelocity(velocity,true);
        near(air.x(),3*(double).99f,"uses vanilla float air inertia");
        near(air.y(),(double).99f-.05,"gravity follows inertia");
        near(water.x(),3*(double).6f,"water uses vanilla sixty-percent inertia");
        near(water.y(),(double).6f-.05,"water gravity after drag");
        // --- sweep every octant and many shallow/steep rays ---
        for(int sx:new int[]{-1,1})for(int sy:new int[]{-1,1})for(int sz:new int[]{-1,1})
            for(int a=1;a<=20;a++)for(int b=1;b<=10;b++) {
                var ray=new Vector(sx,sy*a/10.0,sz*b/10.0).normalized();
                var entry=new Vector(.5,.5,.5);
                var result=penetrate(new Obstacle("cube",entry,cube),ray,3,.2,p->null);
                check(!contains(cube.getFirst(),result.position()),"oblique crossing never starts the next tick inside the same block");
                check(result.blocks()==1&&result.stoppedAt()==null,"oblique crossing consumes one block");
                check(result.speedFactor()>0&&result.speedFactor()<=1,"speed factor stays a fraction");
            }
        // A cube's box is a valid contain test for the strict lower bound and exclusive upper bound.
        var unit=new Box(0,0,0,1,1,1);
        check(unit.contains(new Vector(0,0,0))&&unit.contains(new Vector(.999,0,0)),"box contains its inclusive lower bound");
        check(!unit.contains(new Vector(1,.5,.5))&&!unit.contains(new Vector(-1e-6,.5,.5)),"box excludes its far face");
        return checks;
    }
    public static void main(String[] args) {System.out.println("ArcheryBallisticsTest: "+run()+" checks passed");}
}
