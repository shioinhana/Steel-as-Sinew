package org.fsrpg.combat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Selection and live attack facing without Minecraft or client state. */
public final class RapierTargetingTest {
    private static int checks;
    private static final RapierTargeting.Rules RULES = RapierTargeting.Rules.defaults();
    public static void main(String[] args) {
        priorityAndStability();
        validityAndRange();
        directionAndActiveTracking();
        dodgeIntentBounds();
        System.out.println("RapierTargetingTest passed: " + checks + " checks");
    }
    private static void dodgeIntentBounds() {
        var direction=RapierTargeting.dodgeDirection(.3,.4);
        near(direction.x(),.6,"bounded dodge X normalized");
        near(direction.z(),.8,"bounded dodge Z normalized");
        near(Math.hypot(direction.x(),direction.z()),1,"client magnitude cannot change travel distance");
        near(RapierTargeting.dodgeDirection(-1,0).x(),-1,"negative world axis accepted");
        near(RapierTargeting.dodgeDirection(0,1).z(),1,"positive world axis accepted");
        near(Math.hypot(RapierTargeting.dodgeDirection(1,.1).x(),RapierTargeting.dodgeDirection(1,.1).z()),1,"1.005 vector inside tolerance normalized");
        rejects(()->RapierTargeting.dodgeDirection(0,0),"zero dodge intent rejected");
        rejects(()->RapierTargeting.dodgeDirection(1,1),"oversize diagonal rejected");
        rejects(()->RapierTargeting.dodgeDirection(1.001,0),"component above one rejected despite length tolerance");
        rejects(()->RapierTargeting.dodgeDirection(-1.001,0),"component below minus one rejected");
        rejects(()->RapierTargeting.dodgeDirection(Double.NaN,0),"NaN dodge X rejected");
        rejects(()->RapierTargeting.dodgeDirection(0,Double.NaN),"NaN dodge Z rejected");
        rejects(()->RapierTargeting.dodgeDirection(Double.POSITIVE_INFINITY,0),"infinite dodge X rejected");
        rejects(()->RapierTargeting.dodgeDirection(0,Double.NEGATIVE_INFINITY),"infinite dodge Z rejected");
    }
    private static RapierTargeting.Candidate target(int id, double distance, boolean hostile) {
        return new RapierTargeting.Candidate(new UUID(0,id), id, distance*distance, hostile, true,true,false,true);
    }
    private static void priorityAndStability() {
        var sheep=target(1,1,false); var zombie=target(2,9,true); var nearer=target(3,2,true);
        check(RapierTargeting.select(null,List.of(sheep,zombie),RULES)==zombie,"hostile wins over nearer passive");
        check(RapierTargeting.select(sheep.uuid(),List.of(sheep,zombie),RULES)==zombie,"new hostile takes passive lock");
        check(RapierTargeting.select(zombie.uuid(),List.of(zombie,nearer),RULES)==zombie,"same-priority lock does not jitter toward nearer enemy");
        check(RapierTargeting.select(null,List.of(zombie,nearer),RULES)==nearer,"new hostile lock uses nearest");
        check(RapierTargeting.select(sheep.uuid(),List.of(sheep,target(4,.5,false)),RULES)==sheep,"passive lock is also stable");
        check(RapierTargeting.select(null,List.of(sheep),RULES)==sheep,"passive fallback enabled by default");
        check(RapierTargeting.select(null,List.of(sheep),new RapierTargeting.Rules(true,10,false,true))==null,"passive fallback can be disabled");
        check(RapierTargeting.select(null,List.of(zombie),new RapierTargeting.Rules(false,10,true,true))==null,"feature can be disabled");
        var tiedA=target(10,3,true); var tiedB=target(11,3,true);
        check(RapierTargeting.select(null,List.of(tiedB,tiedA),RULES)==tiedA,"distance tie has stable UUID order");
        check(RapierTargeting.select(null,List.of(tiedA,tiedB),RULES)==tiedA,"entity iteration order cannot choose tie");
    }
    private static void validityAndRange() {
        var inside=target(1,9.99,true); var edge=target(2,10,true); var outside=target(3,10.01,true);
        check(inside.eligible(RULES),"9.99 blocks inside");
        check(edge.eligible(RULES),"10-block boundary included");
        check(!outside.eligible(RULES),"10.01 blocks outside");
        check(RapierTargeting.select(outside.uuid(),List.of(outside,inside),RULES)==inside,"out-of-range lock reacquires valid target");
        for(int flag=0;flag<4;flag++) {
            var invalid=new RapierTargeting.Candidate(new UUID(0,99),99,1,true,flag!=0,flag!=1,flag==2,flag!=3);
            check(!invalid.eligible(RULES),"dead/unattackable/friendly/occluded target excluded "+flag);
            check(RapierTargeting.select(invalid.uuid(),List.of(invalid),RULES)==null,"invalid held target unlocks "+flag);
        }
        var occluded=new RapierTargeting.Candidate(new UUID(0,99),99,1,true,true,true,false,false);
        check(occluded.eligible(new RapierTargeting.Rules(true,10,true,false)),"line-of-sight requirement configurable");
        check(RapierTargeting.select(null,List.of(),RULES)==null,"empty world gives controlled no-target fallback");
        rejects(()->new RapierTargeting.Rules(true,Double.NaN,true,true),"NaN range rejected");
        rejects(()->new RapierTargeting.Rules(true,.49,true,true),"sub-minimum range rejected");
        rejects(()->new RapierTargeting.Rules(true,64.01,true,true),"excessive range rejected");
        rejects(()->new RapierTargeting.Candidate(UUID.randomUUID(),1,Double.POSITIVE_INFINITY,true,true,true,false,true),"nonfinite candidate rejected");
    }
    private static void directionAndActiveTracking() {
        near(RapierTargeting.direction(0,0,1,0).yaw(),0,"south yaw");
        near(RapierTargeting.direction(1,0,0,0).yaw(),-90,"east yaw");
        near(RapierTargeting.direction(0,0,-1,0).yaw(),-180,"north yaw wraps");
        near(RapierTargeting.direction(-1,0,0,0).yaw(),90,"west yaw");
        near(RapierTargeting.direction(0,20,0,37).yaw(),37,"vertical target retains finite yaw");
        near(RapierTargeting.direction(0,20,0,37).pitch(),-80,"upward pitch bounded");
        near(RapierTargeting.direction(0,-20,0,37).pitch(),80,"downward pitch bounded");
        rejects(()->RapierTargeting.direction(Double.NaN,0,0,0),"nonfinite displacement rejected");
        var skill = new SkillDefinition("left","Left","sword",SkillInput.LEFT,1,3,2,0,0,0,5,3,90,0,true,true,true,"model","attack");
        var style = new WeaponStyleDefinition("rapier","Rapier","sword",true,List.of(new WeaponStyleDefinition.ComboStage("left")),"",WeaponStyleDefinition.GuardRules.from(CombatRules.defaults()));
        var actor = new CombatActor(UUID.randomUUID(),CombatRules.defaults());
        actor.setStyle(style); actor.requestDrawn(); for(int i=0;i<8;i++)actor.tick();
        actor.aimedInput(SkillInput.LEFT,Map.of("left",skill),AttackAim.at(170,0));
        actor.trackRapierAim(AttackAim.at(-90,10));
        near(actor.attackAim().sample(0).yaw(),-90,"server lock updates preparation aim");
        actor.tick();check(actor.phase()==CombatPhase.ACTIVE,"test enters contact window");
        actor.trackRapierAim(AttackAim.at(90,-10));
        near(actor.attackAim().sample(0).yaw(),90,"server lock updates active aim rather than camera");
        near(actor.attackAim().sample(0).pitch(),-10,"server pitch follows validated target");
        var nonRapier = new CombatActor(UUID.randomUUID(),CombatRules.defaults());
        nonRapier.setStyle(new WeaponStyleDefinition("other","Other","other",true,
                List.of(new WeaponStyleDefinition.ComboStage("left")),"",WeaponStyleDefinition.GuardRules.from(CombatRules.defaults())));
        nonRapier.trackRapierAim(AttackAim.at(90,0));
        check(nonRapier.attackAim()==null,"tracking does not alter non-rapier styles");
    }
    private static void near(double actual,double expected,String message) {check(Math.abs(actual-expected)<1e-9,message+" "+actual);}
    private static void rejects(Runnable operation,String message) {try{operation.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}}
    private static void check(boolean value,String message) {checks++;if(!value)throw new AssertionError(message);}
}
