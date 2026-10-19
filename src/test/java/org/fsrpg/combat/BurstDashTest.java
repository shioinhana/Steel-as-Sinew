package org.fsrpg.combat;

import java.util.UUID;

/** Server-only input edges, fixed direction, exact curved distance and independent attack state. */
public final class BurstDashTest {
    private static int checks;
    public static void main(String[] args) {
        curveAndTap();heldDirectionAndLease();attackIndependence();gatesAndFreshEdges();interruptions();
        oldDodgeAndValidation();lockedAim();customDiscreteCurves();
        System.out.println("BurstDashTest passed: "+checks+" checks");
    }
    private static CombatActor actor(){var actor=new CombatActor(UUID.randomUUID(),rules());actor.requestDrawn();return actor;}
    private static CombatRules rules(){var d=CombatRules.defaults();return new CombatRules(100,100,0,0,20,0,0,d.guardArcDegrees(),d.guardDamageMultiplier(),d.guardStaminaCost(),
            d.perfectGuardTicks(),d.perfectGuardStaminaCost(),d.parryStunTicks(),d.guardBreakStunTicks(),d.hitStunTicks(),d.clashFacingArcDegrees(),
            d.dodgeDurationTicks(),d.dodgeInvulnerabilityStartTicks(),d.dodgeInvulnerabilityTicks(),d.dodgeCooldownTicks(),d.dodgeStaminaCost(),d.dodgeDistance(),
            d.dodgeCancelsRecovery(),d.allowAirDodge(),d.rapierTargetLock(),d.perfectGuardCounter(),d.staminaLevelScaling(),d.staminaCombatRules(),d.burstDash());}
    private static SkillDefinition skill(int windup,int active,int recovery){return new SkillDefinition("cut","cut","sword",SkillInput.LEFT,windup,active,recovery,0,10,0,7,3,90,0,true,true,false,"","");}
    private static void curveAndTap(){
        var actor=actor();equal(ActionResult.ACCEPTED,actor.requestBurstDash(true,1,0),"fresh Shift starts a burst");
        near(95,actor.stamina(),"one burst costs five");check(actor.staminaCombatState(),"real movement cost enters combat");
        actor.requestBurstDash(false,0,0);double distance=0,first=0,last=0,maximum=0;
        for(int tick=0;tick<10;tick++){
            var move=actor.tick().burstDashStep();check(move.bursting()&&!move.running(),"ten paid burst frames survive a tap release");
            check(move.x()>0&&move.z()==0,"curve always moves forward");if(tick==0)first=move.x();last=move.x();maximum=Math.max(maximum,move.x());distance+=move.x();
            check(!actor.invulnerable(),"Shift never borrows Alt invulnerability");
        }
        near(3,distance,"ten steps integrate to exactly three unobstructed blocks");
        check(first<maximum*.25,"burst accelerates naturally from a slow first step");
        near(5.6/20,last,"actual final 20 Hz burst step matches the following held run exactly");
        check(!actor.tick().burstDashStep().moving(),"tap does not continue running");
        near(95,actor.stamina(),"tap completion cannot refund a real movement cost");
    }
    private static void heldDirectionAndLease(){
        var actor=actor();actor.requestBurstDash(true,3,4);double x=0,z=0;
        for(int tick=0;tick<10;tick++){
            actor.requestBurstDash(true,-9,0);var step=actor.tick().burstDashStep();x+=step.x();z+=step.z();
            near(95,actor.stamina(),"held packets never pay again");
        }
        near(1.8,x,"world direction is normalized and frozen X");near(2.4,z,"world direction is normalized and frozen Z");
        var run=actor.tick().burstDashStep();check(run.running()&&!run.bursting(),"held Shift seamlessly becomes running");
        near(.168,run.x(),"run keeps initial X direction");near(.224,run.z(),"run keeps initial Z direction");
        for(int i=0;i<13;i++)actor.tick();check(!actor.tick().burstDashStep().moving(),"lost input lease stops running");
        actor.requestBurstDash(true,0,-1);check(actor.tick().burstDashStep().running(),"renewal after stale lease resumes only the same run");
        near(95,actor.stamina(),"lease expiry cannot rearm a paid press");near(.6,actor.burstDashSnapshot().directionX(),"renewal never redirects a run");
        actor.requestBurstDash(false,0,0);check(!actor.tick().burstDashStep().moving(),"release ends continued running");
    }
    private static void attackIndependence(){
        for(int at:new int[]{0,2,7}){
            var actor=actor();actor.requestAttack(skill(2,5,4));for(int i=0;i<at;i++)actor.tick();
            long sequence=actor.snapshot().swingSequence();var phase=actor.phase();var current=actor.currentSkill();
            equal(ActionResult.ACCEPTED,actor.requestBurstDash(true,1,0),"Shift accepts every attack phase without a timing gate");
            equal(phase,actor.phase(),"dash leaves attack phase intact");check(actor.currentSkill()==current,"dash retains current attack and contact interval");
            equal(sequence,actor.snapshot().swingSequence(),"dash creates no extra attack sequence");near(at>=7?90:85,actor.stamina(),"dash has independent five-point cost after any existing miss refund");
        }
    }
    private static void gatesAndFreshEdges(){
        var actor=new CombatActor(UUID.randomUUID(),rules());
        equal(ActionResult.NOT_DRAWN,actor.requestBurstDash(true,1,0),"sheathed item cannot dash");actor.requestDrawn();
        actor.requestBurstDash(true,1,0);check(!actor.tick().burstDashStep().moving(),"a held rejected press does not auto-fire on draw");
        actor.requestBurstDash(false,0,0);equal(ActionResult.ACCEPTED,actor.requestBurstDash(true,1,0),"release permits a real fresh press");
        actor=actor();actor.setResources(4,100);equal(ActionResult.INSUFFICIENT_STAMINA,actor.requestBurstDash(true,1,0),"full five-point budget required");
        actor.restoreResources(10,0);actor.requestBurstDash(true,1,0);check(!actor.tick().burstDashStep().moving(),"resource recovery cannot auto-start held input");
        near(14,actor.stamina(),"failed and renewed requests never partly debit");
        actor=actor();actor.setGuarding(true);equal(ActionResult.BUSY,actor.requestBurstDash(true,1,0),"Shift plus actual guard cannot start movement");
        actor.setGuarding(false);actor.requestBurstDash(true,1,0);check(!actor.tick().burstDashStep().moving(),"leaving guard while Shift remains down does not invent an edge");
        actor=actor();actor.requestDodge(true);equal(ActionResult.BUSY,actor.requestBurstDash(true,1,0),"Alt dodge cannot stack with Shift");
    }
    private static void interruptions(){
        for(boolean sheath:new boolean[]{false,true}){
            var actor=actor();actor.requestBurstDash(true,1,0);actor.tick();if(sheath)actor.forceSheathed();else actor.stun(2);
            check(!actor.tick().burstDashStep().moving(),"authoritative interruption stops burst movement");
            if(sheath)actor.requestDrawn();else actor.tick();
            actor.requestBurstDash(true,1,0);check(!actor.tick().burstDashStep().moving(),"interrupted held input cannot restart and charge again");
            near(95,actor.stamina(),"interruption has no hidden repeat fee");
        }
    }
    private static void oldDodgeAndValidation(){
        var actor=actor();equal(ActionResult.ACCEPTED,actor.requestDodge(true),"old Alt dodge remains available");
        double distance=0;for(int i=0;i<actor.rules().dodgeDurationTicks();i++)distance+=actor.tick().dodgeDistanceThisTick();
        near(actor.rules().dodgeDistance(),distance,"old custom-distance path remains independent");near(94,actor.stamina(),"old default Alt cost remains six");
        rejects(()->new BurstDashRules(3,0,5,10,5.6,12),"zero duration rejected");
        rejects(()->new BurstDashRules(3,10,Double.NaN,10,5.6,12),"NaN cost rejected");
        rejects(()->new BurstDashRules(.25,100,5,10,12,12),"non-monotone terminal curve rejected");
        rejects(()->new BurstDashRules(3,10,5,10,5.6,1),"unsafe lease rejected");
        var state=new BurstDashState();rejects(()->state.begin(0,0,0,BurstDashRules.defaults()),"zero direction rejected");
        near(0,BurstDashRules.defaults().displacement(0),"curve starts at zero");near(3,BurstDashRules.defaults().displacement(1),"curve ends at requested distance");
    }
    private static void lockedAim(){
        var actor=actor();var style=new WeaponStyleDefinition("dual_blade","dual","dual_blade",true,
                java.util.List.of(new WeaponStyleDefinition.ComboStage("cut")),"",WeaponStyleDefinition.GuardRules.from(actor.rules()));
        actor.setStyle(style);actor.requestAttack(skill(0,3,1));var aim=AttackAim.at(40,10);actor.trackLockedAim(aim);
        equal(aim,actor.attackAim(),"non-spear lock can update another weapon's active aim");
        actor.trackRapierAim(AttackAim.at(0,0));equal(aim,actor.attackAim(),"compatibility rapier API retains its old style filter");
    }
    private static void customDiscreteCurves(){
        for(int ticks=1;ticks<=100;ticks++)for(double distance:new double[]{.25,3,32}){
            double maximum=Math.min(12,3*distance*20/ticks);
            for(double run:new double[]{0,maximum*.25,maximum*.75,maximum}){
                var rules=new BurstDashRules(distance,ticks,5,10,run,12);
                double total=0,previous=0,last=0;
                for(int tick=1;tick<=ticks;tick++){
                    double now=rules.displacement(tick/(double)ticks);last=now-previous;
                    check(Double.isFinite(last)&&last>=-1e-12,"every legal custom curve keeps all discrete movement forward and finite");
                    total+=last;previous=now;
                }
                near(distance,total,"every legal custom duration retains its exact integrated burst distance");
                if(ticks>=2&&run/20<=distance)near(run/20,last,"every feasible custom terminal step matches its held run");
            }
        }
        var defaults=BurstDashRules.defaults();double previous=0,maximum=0;int peak=0;double[] steps=new double[10];
        for(int i=0;i<10;i++){double next=defaults.displacement((i+1)/10.0);steps[i]=next-previous;previous=next;if(steps[i]>maximum){maximum=steps[i];peak=i;}}
        check(peak>0&&peak<9,"default curve has a real acceleration and braking interval");
        for(int i=1;i<=peak;i++)check(steps[i]>=steps[i-1],"default burst accelerates without an early velocity reversal");
        for(int i=peak+1;i<10;i++)check(steps[i]<=steps[i-1],"default burst brakes continuously toward its exact running step");
    }
    private static void near(double expected,double actual,String message){check(Math.abs(expected-actual)<1e-8,message+": "+expected+" != "+actual);}
    private static void equal(Object expected,Object actual,String message){check(expected.equals(actual),message+": "+expected+" != "+actual);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){checks++;try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){}}
}
