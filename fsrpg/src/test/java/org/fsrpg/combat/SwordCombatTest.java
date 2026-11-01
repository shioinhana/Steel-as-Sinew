package org.fsrpg.combat;

import java.util.*;
import org.fsrpg.config.RpgConfig;

/** The sword triangle is authoritative and does not alter the legacy/spear resolver. */
public final class SwordCombatTest {
    private static int checks;
    private static final SkillDefinition CUT=skill("sword_left",SkillInput.LEFT,6),THRUST=skill("sword_thrust",SkillInput.RIGHT,9),RETURN=skill("sword_parry_return",SkillInput.LEFT,12);
    private static final Map<String,SkillDefinition> SKILLS=Map.of(CUT.id(),CUT,THRUST.id(),THRUST,RETURN.id(),RETURN);
    public static void main(String[] args){parryBoundaries();triangle();charge();projectileClassification();parryBlocksBurst();returnOwnership();isolation();clashClasses();motion();System.out.println("SwordCombatTest passed: "+checks+" checks");}
    private static CombatRules rules(){var json=RpgConfig.JSON.toJsonTree(CombatRules.defaults()).getAsJsonObject();json.add("swordCombat",RpgConfig.JSON.toJsonTree(SwordCombatRules.defaults()));return RpgConfig.JSON.fromJson(json,CombatRules.class);}
    private static CombatActor actor(String group){var a=new CombatActor(UUID.randomUUID(),rules());a.setStyle(new WeaponStyleDefinition(group,group,group,true,List.of(new WeaponStyleDefinition.ComboStage(CUT.id(),0,0,0,0)),THRUST.id(),WeaponStyleDefinition.GuardRules.from(a.rules())));a.requestDrawn();ticks(a,a.rules().drawTicks());return a;}
    private static SkillDefinition skill(String id,SkillInput input,double damage){return new SkillDefinition(id,id,"sword",input,3,3,3,0,5,0,damage,4,120,0,true,true,true,"rapier_fullbody.fsvoxel",id);}
    private static HitResult incoming(CombatActor a,boolean heavy){return CombatResolver.receiveSwordIncoming(null,a,0,8,0,true,false,heavy,"");}
    private static void parryBoundaries(){
        for(int n=0;n<=16;n++){var a=actor("sword");check(a.requestSwordParry()==ActionResult.ACCEPTED,"X accepted");ticks(a,n);check(a.swordParryWindow()==(n>=2&&n<7),"exact parry boundary "+n);check(incoming(a,true).outcome()==(n>=2&&n<7?HitOutcome.PARRIED:HitOutcome.HIT),"heavy result "+n);}
        var a=actor("sword");a.requestSwordParry();double paid=a.stamina();check(a.requestSwordParry()==ActionResult.COOLDOWN,"repeat X cannot restart");check(a.stamina()==paid,"repeat does not debit");ticks(a,16);check(a.phase()==CombatPhase.READY,"whiff recovery returns ready");check(a.requestSwordParry()==ActionResult.COOLDOWN,"whiff cooldown persists");ticks(a,4);check(a.requestSwordParry()==ActionResult.ACCEPTED,"cooldown ends");
    }
    private static void triangle(){
        var a=actor("sword");a.setGuarding(true);check(!a.guardPerfectAgainst(true),"C cannot become X through legacy cue");check(incoming(a,false).outcome()==HitOutcome.BLOCKED,"C blocks normal");check(a.swordReturnRemainingTicks()==0,"ordinary block grants no return");
        a=actor("sword");a.setGuarding(true);check(incoming(a,true).outcome()==HitOutcome.GUARD_BROKEN,"heavy breaks C");
        a=actor("sword");a.requestSwordParry();ticks(a,2);check(incoming(a,false).outcome()==HitOutcome.HIT,"normal beats active X");a.tick();check(a.phase()==CombatPhase.STUNNED,"normal punishes X");
        a=actor("sword");a.requestSwordParry();ticks(a,2);check(CombatResolver.receiveSwordIncoming(null,a,0,8,0,true,true,true,"").outcome()==HitOutcome.HIT,"X never reflects projectiles");
        a=actor("sword");a.requestSwordParry();ticks(a,2);check(CombatResolver.receiveSwordIncoming(null,a,180,8,0,true,false,true,"").outcome()==HitOutcome.HIT,"X has facing limits");
    }
    private static void charge(){
        var a=actor("sword");check(a.beginChargedAttack(THRUST,AttackAim.at(0,0),true,true,16,200)==ActionResult.ACCEPTED,"charge starts");ticks(a,5);check(a.receiveChargedParry()==0,"no old automatic immunity");check(a.swordSuperArmor(),"charge armor exists");check(incoming(a,false).damage()==8,"armor receives all damage");a.tick();check(a.phase()==CombatPhase.WINDUP,"armor prevents ordinary hit stun");a.releaseChargedAttack();check(!a.swordHeavyAttack(),"early release ordinary");check(a.attackDamageMultiplier()==1,"early release no bonus");
        a=actor("sword");a.beginChargedAttack(THRUST,AttackAim.at(0,0),true,true,16,200);ticks(a,16);a.releaseChargedAttack();check(a.swordHeavyAttack(),"full release heavy");check(a.attackDamageMultiplier()==2,"full charge multiplier once");a.releaseChargedAttack();check(a.attackDamageMultiplier()==2,"repeat release cannot multiply");a.stun(2);check(!a.swordSuperArmor()&&!a.swordHeavyAttack(),"forced stun clears armor");
    }
    private static void projectileClassification(){
        var owner=actor("sword");owner.beginChargedAttack(THRUST,null,true,false,16,200);ticks(owner,16);owner.releaseChargedAttack();
        check(owner.swordHeavyAttack(),"projectile owner is concurrently executing a charged sword strike");
        var defender=actor("sword");defender.setGuarding(true);double before=defender.stamina();
        var arrow=CombatResolver.receiveIncoming(owner,defender,0,8,0,true,true,true);
        check(arrow.outcome()==HitOutcome.BLOCKED,"older arrow does not inherit its owner's heavy guard break");
        near(8*defender.guardRules().damageMultiplier(),arrow.damage(),"arrow retains ordinary block chip damage");
        near(before-defender.guardRules().staminaCost(),defender.stamina(),"arrow charges only ordinary block stamina");
        defender=actor("sword");defender.setGuarding(true);
        check(CombatResolver.receiveIncoming(owner,defender,0,8,0,true,true,false).outcome()==HitOutcome.GUARD_BROKEN,"the same owner's actual charged melee still breaks C");
        var spear=actor("spear");spear.setGuarding(true);
        check(CombatResolver.receiveIncoming(owner,spear,0,8,0,true,true,true).outcome()==HitOutcome.PARRIED,"legacy spear projectile defense stays unchanged");
    }
    private static void parryBlocksBurst(){
        for(int elapsed:new int[]{0,2,7,15}){
            var a=actor("sword");a.requestSwordParry();ticks(a,elapsed);double paid=a.stamina();
            check(a.requestBurstDash(true,0,1,true)==ActionResult.BUSY,"Shift cannot escape X windup/active/recovery at "+elapsed);
            near(paid,a.stamina(),"rejected parry Shift has no debit at "+elapsed);
            check(!a.consumeBurstDashImpulse().moving(),"rejected parry Shift cannot emit an impulse at "+elapsed);
        }
        var a=actor("sword");a.requestSwordParry();ticks(a,16);
        check(a.requestBurstDash(true,0,1,true)==ActionResult.ACCEPTED&&a.consumeBurstDashImpulse().moving(),"Shift resumes after complete X recovery");
    }
    private static void returnOwnership(){
        var a=actor("sword");check(a.aimedLeftSkill(RETURN,null)==ActionResult.TECHNIQUE_NOT_READY,"cannot invent return");a.requestSwordParry();ticks(a,2);incoming(a,true);check(a.swordReturnRemainingTicks()==24,"success grants full return window");check(a.aimedSkill(RETURN,null)==ActionResult.TECHNIQUE_NOT_READY,"named skill cannot consume physical LEFT");a.setResources(0,100);check(a.aimedInput(SkillInput.LEFT,SKILLS,null).rejection()==ActionResult.INSUFFICIENT_STAMINA,"failed return preserves opportunity");check(a.swordReturnRemainingTicks()==24,"failed input not consumed");a.setResources(50,100);check(a.aimedInput(SkillInput.LEFT,SKILLS,null).started(),"left starts return");check(a.currentSkill().id().equals(RETURN.id()),"special return chosen");check(a.swordReturnRemainingTicks()==0,"return consumed once");ticks(a,9);check(a.aimedInput(SkillInput.LEFT,SKILLS,null).started()&&a.currentSkill().id().equals(CUT.id()),"next left normal");
    }
    private static void isolation(){var a=actor("spear");check(!a.swordCombatEnabled(),"spear isolated");check(a.requestSwordParry()==ActionResult.UNSUPPORTED_INPUT,"spear no X");a.setGuarding(true);check(a.guardPerfectAgainst(null),"legacy perfect unchanged");check(CombatResolver.receiveIncoming(null,a,0,8,0,true,true,false).outcome()==HitOutcome.PARRIED,"spear perfect unchanged");}
    private static void clashClasses(){
        var a=actor("sword");var b=actor("sword");a.requestAttack(CUT);b.requestAttack(CUT);ticks(a,3);ticks(b,3);check(CombatResolver.canClash(a,b,1,0,0),"ordinary swords keep contact clash");check(CombatResolver.resolveHit(a,b,1,0,0).outcome()==HitOutcome.CLASH,"ordinary mutual hit resolves clash");
        a=actor("sword");b=actor("sword");a.beginChargedAttack(THRUST,null,true,false,16,200);ticks(a,16);a.releaseChargedAttack();b.requestAttack(CUT);ticks(b,3);check(!CombatResolver.canClash(a,b,1,0,0),"heavy cannot be canceled by ordinary clash");
    }
    private static void motion(){
        var plan=new SwordMotionPlan(1,0,.85,.2);double x=0,z=0;
        for(int i=1;i<=9;i++){var d=plan.advance(SwordMotionPlan.progress(i,CUT),0,0);x+=d.x();z+=d.z();}near(0,x,"straight x");near(.85,z,"total travel");near(.85,plan.expectedAdvance(),"planned cumulative displacement");plan.recordActual(0,.2);plan.recordActual(0,0);near(.2,plan.actualAdvance(),"blocked step adds no real advance");plan.recordActual(0,10);near(.85,plan.actualAdvance(),"actual progress bounded by action budget");var duplicate=plan.advance(1,1,1);near(0,Math.hypot(duplicate.x(),duplicate.z()),"duplicate progress no travel");
        plan=new SwordMotionPlan(2,0,.85,.2);var steered=plan.advance(1,1000,0);near(.85,Math.hypot(steered.x(),steered.z()),"steering cannot add distance");check(steered.z()>0&&steered.x()/steered.z()<=.200001,"bounded correction cone");
        boolean rejected=false;try{new SwordMotionPlan(1,0,Double.NaN,.2);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"nonfinite motion rejected");
    }
    private static void ticks(CombatActor a,int n){for(int i=0;i<n;i++)a.tick();}
    private static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    private static void near(double a,double b,String text){check(Math.abs(a-b)<1e-9,text+": "+a+" vs "+b);}
}
