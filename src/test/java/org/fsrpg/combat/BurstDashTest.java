package org.fsrpg.combat;

import java.util.UUID;

/** Ground-only input edges, one velocity grant and independent ordinary physics/attack state. */
public final class BurstDashTest {
    private static int checks;
    public static void main(String[] args) {
        curveAndTap();heldDirectionAndLease();attackIndependence();gatesAndFreshEdges();interruptions();
        oldDodgeAndValidation();lockedAim();customInitialSpeeds();
        System.out.println("BurstDashTest passed: "+checks+" checks");
    }
    private static CombatActor actor(){var actor=new CombatActor(UUID.randomUUID(),rules());actor.requestDrawn();return actor;}
    private static CombatRules rules(){var d=CombatRules.defaults();return new CombatRules(100,100,0,0,20,0,0,d.guardArcDegrees(),d.guardDamageMultiplier(),d.guardStaminaCost(),
            d.perfectGuardTicks(),d.perfectGuardStaminaCost(),d.parryStunTicks(),d.guardBreakStunTicks(),d.hitStunTicks(),d.clashFacingArcDegrees(),
            d.dodgeDurationTicks(),d.dodgeInvulnerabilityStartTicks(),d.dodgeInvulnerabilityTicks(),d.dodgeCooldownTicks(),d.dodgeStaminaCost(),d.dodgeDistance(),
            d.dodgeCancelsRecovery(),d.allowAirDodge(),d.rapierTargetLock(),d.perfectGuardCounter(),d.staminaLevelScaling(),d.staminaCombatRules(),d.burstDash());}
    private static SkillDefinition skill(int windup,int active,int recovery){return new SkillDefinition("cut","cut","sword",SkillInput.LEFT,windup,active,recovery,0,10,0,7,3,90,0,true,true,false,"","");}
    private static void curveAndTap(){
        var actor=actor();equal(ActionResult.ACCEPTED,actor.requestBurstDash(true,1,0,true),"a fresh grounded press authorizes one impulse");
        near(95,actor.stamina(),"one impulse costs five");check(actor.staminaCombatState(),"paid motion enters combat");
        var impulse=actor.consumeBurstDashImpulse();near(1.35,impulse.x(),"initial velocity is 27 blocks per second / 20");near(0,impulse.z(),"requested axis preserved");
        check(!actor.consumeBurstDashImpulse().moving(),"same authorization is consumed exactly once");
        actor.requestBurstDash(false,0,0,true);
        for(int tick=0;tick<100;tick++){check(!actor.tick().burstDashStep().moving(),"tick never writes a position or another velocity");check(!actor.invulnerable(),"Shift supplies no invulnerability");}
        near(95,actor.stamina(),"a tap never refunds or repeats its fixed movement fee");
        // This describes vanilla standard-ground friction, not a custom movement curve.
        double speed=impulse.x(),distance=0;for(int tick=0;tick<40;tick++){distance+=speed;speed*=.6*.91;}
        check(distance>2.9&&distance<3.1,"a standard-ground no-input coast is about three blocks, not a prescribed duration");
    }
    private static void heldDirectionAndLease(){
        var actor=actor();actor.requestBurstDash(true,3,4,true);var impulse=actor.consumeBurstDashImpulse();
        near(.81,impulse.x(),"the initial direction is normalized X");near(1.08,impulse.z(),"the initial direction is normalized Z");
        for(int i=0;i<40;i++){actor.requestBurstDash(true,-1,0,true);check(!actor.consumeBurstDashImpulse().moving(),"held renewals cannot overwrite current vanilla velocity");check(!actor.tick().burstDashStep().moving(),"held running manufactures no motion step");}
        near(95,actor.stamina(),"renewals never charge again");check(actor.burstDashSnapshot().running(),"a successful held input grants ordinary sprint permission");
        for(int i=0;i<14;i++)actor.tick();check(!actor.burstDashSnapshot().running(),"lost input lease ends sprint permission");
        actor.requestBurstDash(true,0,-1,true);check(actor.burstDashSnapshot().running(),"a renewal may resume sprint permission without a new impulse");
        actor.requestBurstDash(false,0,0,true);check(!actor.burstDashSnapshot().running(),"up ends held sprint permission");
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
        var actor=actor();equal(ActionResult.AIR_BURST_DISABLED,actor.requestBurstDash(true,1,0,false),"airborne fresh press is rejected");
        near(100,actor.stamina(),"air rejection spends no stamina");check(!actor.staminaCombatState(),"air rejection does not change regeneration state");
        actor.requestBurstDash(true,1,0,true);check(!actor.consumeBurstDashImpulse().moving(),"holding Shift through landing cannot manufacture a press");near(100,actor.stamina(),"landing with a rejected hold is free");
        actor.requestBurstDash(false,0,0,true);equal(ActionResult.ACCEPTED,actor.requestBurstDash(true,1,0,true),"release and a new grounded press may start");
        check(actor.consumeBurstDashImpulse().moving(),"real second press produces the first impulse");near(95,actor.stamina(),"only the accepted second press pays");
        actor=new CombatActor(UUID.randomUUID(),rules());equal(ActionResult.NOT_DRAWN,actor.requestBurstDash(true,1,0,true),"sheathed cannot dash");actor.requestDrawn();
        actor.requestBurstDash(true,1,0,true);check(!actor.consumeBurstDashImpulse().moving(),"drawing while held does not invent a press");
        actor=actor();actor.setResources(4,100);equal(ActionResult.INSUFFICIENT_STAMINA,actor.requestBurstDash(true,1,0,true),"full five-point budget required");
        actor.restoreResources(10,0);actor.requestBurstDash(true,1,0,true);check(!actor.consumeBurstDashImpulse().moving(),"resource recovery cannot trigger a held request");near(14,actor.stamina(),"failed starts do not partly debit");
        actor=actor();actor.setGuarding(true);equal(ActionResult.BUSY,actor.requestBurstDash(true,1,0,true),"Shift+C guard gets priority");
        actor.setGuarding(false);actor.requestBurstDash(true,1,0,true);check(!actor.consumeBurstDashImpulse().moving(),"releasing C while Shift remains down does not trigger");
        actor=actor();actor.requestDodge(true);equal(ActionResult.BUSY,actor.requestBurstDash(true,1,0,true),"Alt dodge cannot stack with Shift");
    }
    private static void interruptions(){
        for(boolean sheath:new boolean[]{false,true}){
            var actor=actor();actor.requestBurstDash(true,1,0,true);check(actor.consumeBurstDashImpulse().moving(),"accepted impulse delivered before interruption");
            if(sheath)actor.forceSheathed();else actor.stun(2);
            check(!actor.tick().burstDashStep().moving()&&!actor.burstDashSnapshot().running(),"interruptions end sprint permission without an artificial braking step");
            if(sheath)actor.requestDrawn();else actor.tick();
            actor.requestBurstDash(true,1,0,true);check(!actor.consumeBurstDashImpulse().moving(),"interrupted held input never repeats the impulse");near(95,actor.stamina(),"interruption has no repeat fee");
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
    private static void customInitialSpeeds(){
        for(double speed:new double[]{.1,1,27,40,80})for(double[] direction:new double[][]{{1,0},{0,-1},{3,4},{-12,5}}){
            var state=new BurstDashState();var rules=new BurstDashRules(3,10,5,10,5.6,12,speed);state.input(true,0);state.begin(0,direction[0],direction[1],rules);
            var impulse=state.consumeImpulse();near(speed/20,Math.hypot(impulse.x(),impulse.z()),"configured speed sets one horizontal velocity magnitude");
            for(int i=0;i<30;i++)check(!state.tick(i).moving()&&!state.consumeImpulse().moving(),"all following ticks remain owned by external physics");
        }
        rejects(()->new BurstDashRules(3,10,5,10,5.6,12,Double.NaN),"NaN initial speed rejected");
        rejects(()->new BurstDashRules(3,10,5,10,5.6,12,0.0),"zero initial speed rejected");
        rejects(()->new BurstDashRules(3,10,5,10,5.6,12,80.01),"unsafe initial speed rejected");
        near(27,new BurstDashRules(3,10,5,10,5.6,12).initialSpeed(),"old constructor gains a derived speed without changing old fields");
    }
    private static void near(double expected,double actual,String message){check(Math.abs(expected-actual)<1e-8,message+": "+expected+" != "+actual);}
    private static void equal(Object expected,Object actual,String message){check(expected.equals(actual),message+": "+expected+" != "+actual);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){checks++;try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){}}
}
