package org.fsrpg.client;

import java.util.Random;

/** A rejected partial score must lose under every sampled nonnegative completion. */
public final class SpearRankingBoundsTest {
    public static void main(String[] args)throws Exception {
        var scoreType=Class.forName("org.fsrpg.client.SpearAimPose$GripScore");
        var constructor=scoreType.getDeclaredConstructor(float.class,float.class,boolean.class,boolean.class);constructor.setAccessible(true);
        var bound=SpearAimPose.class.getDeclaredMethod("mayBeatRemaining",float.class,float.class,boolean.class,boolean.class,scoreType,boolean.class,float.class);bound.setAccessible(true);
        var ranking=SpearAimPose.class.getDeclaredMethod("betterGrip",scoreType,scoreType,boolean.class,float.class);ranking.setAccessible(true);
        float[] angles={0,10,34.999f,35,35.001f,70,88.999f,89,89.001f,89.49f,89.5f,89.51f,100,150,180};
        var random=new Random(0x577541);int rejected=0,checks=0;
        for(int trial=0;trial<100000;trial++) {
            float bestPeak=angles[random.nextInt(angles.length)],peak=angles[random.nextInt(angles.length)];
            float bestCost=random.nextInt(10000),cost=random.nextInt(10000),epsilon=trial%2==0?0:.001f;
            boolean bestClear=random.nextBoolean(),clear=random.nextBoolean(),bestTucked=random.nextBoolean(),tucked=random.nextBoolean(),actual=random.nextBoolean();
            var best=constructor.newInstance(bestPeak,bestCost,bestClear,bestTucked);
            if((boolean)bound.invoke(null,peak,cost,clear,tucked,best,actual,epsilon))continue;
            rejected++;
            for(float extraPeak:angles)for(float extraCost:new float[]{0,.001f,1,100,10000}) {
                var completed=constructor.newInstance(Math.max(peak,extraPeak),cost+extraCost,clear,tucked);
                if((boolean)ranking.invoke(null,completed,best,actual,epsilon))
                    throw new AssertionError("Invalid lower-bound rejection: best="+best+" partial="+peak+","+cost+","+clear+","+tucked+" completed="+completed+" actual="+actual);
                checks++;
            }
        }
        System.out.println("SpearRankingBoundsTest passed: "+checks+" completed candidate rankings across "+rejected+" rejected partial scores");
    }
}
