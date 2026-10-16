package org.fsrpg.combat;

import java.util.*;

/** Exact authoritative 20 Hz boundaries, physical LEFT intent and one-swing damage ownership. */
public final class PerfectGuardCounterTest {
    private static int checks;
    private static final SkillDefinition LEFT = skill("left", SkillInput.LEFT, 0, 1, 0, 0, 5);
    private static final SkillDefinition RIGHT = skill("right", SkillInput.RIGHT, 0, 1, 0, 0, 5);

    public static void main(String[] args) {
        perfectGuardBoundaries(); counterBoundaries(); physicalInputRoutes(); failedInputAndSingleUse();
        interruptCleanup(); attackerStunBoundaries(); staleGuardRenewal();
        System.out.println("PerfectGuardCounterTest passed: " + checks + " checks");
    }

    private static void perfectGuardBoundaries() {
        for (int elapsed = 0; elapsed <= 3; elapsed++) {
            CombatActor defender = ready(style("rapier", false)); defender.setGuarding(true); ticks(defender, elapsed);
            var result = incoming(defender);
            equal(elapsed < 2 ? HitOutcome.PARRIED : HitOutcome.BLOCKED, result.outcome(), "perfect guard elapsed tick " + elapsed);
            equal(elapsed < 2 ? 4 : 0, defender.counterRemainingTicks(), "only perfect contact grants left counter");
        }
        CombatActor defender = ready(style("rapier", false)); defender.setGuarding(true); ticks(defender, 1);
        defender.setGuarding(true); ticks(defender, 1);
        check(!defender.inPerfectGuardWindow(), "held guard renewals cannot extend initial two ticks");
        defender.setGuarding(false); defender.setGuarding(true);
        check(defender.inPerfectGuardWindow(), "a real release followed by a new guard starts a new window");
        defender = ready(style("rapier", false)); defender.setGuarding(true, true); ticks(defender, 1);
        defender.setGuarding(true, false); ticks(defender, 1);
        check(!defender.inPerfectGuardWindow(), "heavy/light switch does not renew original guard start");
        defender = ready(style("rapier", false)); defender.setGuarding(true, true);
        equal(HitOutcome.BLOCKED, incoming(defender).outcome(), "existing disabled heavy perfect window remains disabled");
        equal(0, defender.counterRemainingTicks(), "ordinary heavy block grants no counter");
    }

    private static void counterBoundaries() {
        for (int elapsed = 0; elapsed <= 4; elapsed++) {
            CombatActor defender = parried(style("rapier", false)); ticks(defender, elapsed);
            equal(Math.max(0, 4 - elapsed), defender.counterRemainingTicks(), "counter deadline at elapsed " + elapsed);
            check(defender.aimedInput(SkillInput.LEFT, Map.of("left", LEFT, "right", RIGHT), AttackAim.at(0,0)).started(), "physical left attack starts");
            near(elapsed < 4 ? 1.5 : 1, defender.attackDamageMultiplier(), "T through T+3 counter, T+4 ordinary");
            CombatActor target = ready(style("victim", false));
            HitResult hit = CombatResolver.resolveHit(defender, target, 1, 0, 0);
            near(elapsed < 4 ? 12 : 8, hit.damage(), "base damage multiplier applies at contact");
            equal(0, defender.counterRemainingTicks(), "successful counter consumes its single opportunity");
        }
    }

    private static void physicalInputRoutes() {
        for (String weapon : List.of("rapier", "spear", "dual")) {
            var style = style(weapon, weapon.equals("dual"));
            CombatActor actor = parried(style);
            if (weapon.equals("spear")) equal(ActionResult.ACCEPTED, actor.aimedLeftSkill(LEFT, AttackAim.at(0,0)), "spear cursor's left intent starts");
            else check(actor.aimedInput(SkillInput.LEFT, catalog(), AttackAim.at(0,0)).started(), weapon + " left route starts");
            near(1.5, actor.attackDamageMultiplier(), weapon + " physical left receives counter bonus");
        }
        CombatActor actor = parried(style("dual", true));
        check(actor.aimedInput(SkillInput.RIGHT, catalog(), AttackAim.at(0,0)).started(), "dual physical right starts its configured cut");
        near(1, actor.attackDamageMultiplier(), "physical right cannot receive a left counter");
        equal(4, actor.counterRemainingTicks(), "physical right does not consume left-only opportunity");
        actor.tick(); actor.aimedInput(SkillInput.LEFT, catalog(), AttackAim.at(0,0));
        near(1.5, actor.attackDamageMultiplier(), "subsequent real left within four ticks receives bonus");
        actor = parried(style("rapier", false)); actor.aimedSkill(LEFT, AttackAim.at(0,0));
        near(1, actor.attackDamageMultiplier(), "named skill route is not a physical left intent");
        equal(4, actor.counterRemainingTicks(), "named skill leaves left opportunity untouched");
        actor = parried(style("rapier", false)); actor.aimedLeftSkill(LEFT, AttackAim.at(0,0));
        actor.queueHitStun(2);
        near(1.5,actor.attackDamageMultiplier(),"queued ordinary hit preserves this tick's committed outgoing trade");
        actor.tick();near(1,actor.attackDamageMultiplier(),"next-tick stun clears the traded swing's multiplier");
    }

    private static void failedInputAndSingleUse() {
        CombatActor actor = parried(style("rapier", false)); actor.setResources(0, 100);
        var result = actor.aimedInput(SkillInput.LEFT, Map.of("left",LEFT,"right",RIGHT), AttackAim.at(0,0));
        equal(ActionResult.INSUFFICIENT_STAMINA, result.rejection(), "insufficient resources reject counter attack");
        equal(4, actor.counterRemainingTicks(), "failed input cannot consume counter window");
        near(1, actor.attackDamageMultiplier(), "failed attack has no active damage bonus");
        actor.setResources(100, 100);
        check(actor.aimedInput(SkillInput.LEFT, Map.of("left",LEFT,"right",RIGHT), AttackAim.at(0,0)).started(), "resource repair can retry within window");
        near(1.5, actor.attackDamageMultiplier(), "retried accepted left consumes counter");
        actor.aimedInput(SkillInput.LEFT, Map.of("left",LEFT,"right",RIGHT), AttackAim.at(0,0));
        actor.tick();
        near(1, actor.attackDamageMultiplier(), "buffered next attack does not inherit previous swing's bonus");
        actor.tick();
        near(1, actor.attackDamageMultiplier(), "finished swing clears bonus");
        check(actor.aimedInput(SkillInput.LEFT, Map.of("left",LEFT,"right",RIGHT), AttackAim.at(0,0)).started(), "later ordinary attack starts");
        near(1, actor.attackDamageMultiplier(), "one perfect guard cannot enhance another attack");
        actor=ready(style("rapier",false));
        var cooling=skill("left",SkillInput.LEFT,0,1,0,20,5);actor.requestAttack(cooling);actor.tick();actor.setGuarding(true);incoming(actor);
        equal(ActionResult.COOLDOWN,actor.aimedInput(SkillInput.LEFT,Map.of("left",cooling,"right",RIGHT),AttackAim.at(0,0)).rejection(),"cooldown rejects attempted counter");
        equal(4,actor.counterRemainingTicks(),"cooldown failure preserves counter opportunity");
        // A distinct existing right-click riposte remains governed by its own technique window.
        var style = style("rapier", false);
        var withRiposte = new WeaponStyleDefinition(style.id(),style.label(),style.weaponGroup(),true,style.leftCombo(),style.rightSkill(),style.guard(),style.heavyGuard(),
                new WeaponStyleDefinition.Techniques("riposte",20,"",0),style.cancels(),style.controls());
        actor = parried(withRiposte);
        equal("riposte", actor.techniqueSnapshot().skillId(), "old right-click riposte is independently available");
        equal(4, actor.counterRemainingTicks(), "new left window is exactly four ticks beside old twenty-tick riposte");
        var riposte=skill("riposte",SkillInput.RIGHT,0,1,0,0,5);
        check(actor.aimedInput(SkillInput.RIGHT,Map.of("left",LEFT,"right",RIGHT,"riposte",riposte),AttackAim.at(0,0)).started(), "old right-click riposte remains usable");
        near(1,actor.attackDamageMultiplier(),"right-click riposte does not receive new left-only bonus");
    }

    private static void interruptCleanup() {
        CombatActor actor = parried(style("rapier", false)); actor.stun(1);
        equal(0, actor.counterRemainingTicks(), "forced stun clears unused counter");
        actor = parried(style("rapier", false)); actor.queueHitStun(1);
        equal(0, actor.counterRemainingTicks(), "damage interruption clears unused counter immediately");
        actor = parried(style("rapier", false)); actor.requestDodge(true);
        equal(0, actor.counterRemainingTicks(), "accepted dodge clears unused counter");
        actor = parried(style("rapier", false)); actor.forceSheathed();
        equal(0, actor.counterRemainingTicks(), "death/equipment removal clears unused counter");
        actor = parried(style("rapier", false)); actor.setGuarding(false); actor.setStyle(style("other",false));
        equal(0, actor.counterRemainingTicks(), "changing weapon style clears unused counter");
        actor = parried(style("rapier", false)); actor.aimedLeftSkill(LEFT,AttackAim.at(0,0)); actor.stun(1);
        near(1,actor.attackDamageMultiplier(),"interrupting enhanced attack clears its multiplier");
        actor.tick(); actor.aimedLeftSkill(LEFT,AttackAim.at(0,0));
        near(1,actor.attackDamageMultiplier(),"interrupted enhancement cannot reappear on another swing");
    }

    private static void attackerStunBoundaries() {
        CombatActor defender = ready(style("rapier",false)), attacker = ready(style("enemy",false));
        defender.setGuarding(true); attacker.aimedLeftSkill(LEFT,AttackAim.at(0,0));
        equal(HitOutcome.PARRIED,CombatResolver.resolveHit(attacker,defender,1,0,0).outcome(),"real incoming attack is perfectly blocked");
        for(int elapsed=0;elapsed<4;elapsed++) {
            equal(CombatPhase.STUNNED,attacker.phase(),"attacker is stunned at elapsed "+elapsed);
            equal(ActionResult.BUSY,attacker.requestAttack(LEFT),"stunned attacker cannot attack");
            equal(4-elapsed,attacker.snapshot().phaseTicksRemaining(),"exact enemy stun remaining");
            attacker.tick();
        }
        equal(CombatPhase.READY,attacker.phase(),"attacker resumes at exact fourth tick");
        equal(ActionResult.ACCEPTED,attacker.requestAttack(LEFT),"attacker can act after four complete stun ticks");
    }

    private static void staleGuardRenewal() {
        CombatActor actor=parried(style("rapier",false));
        var longer=skill("left",SkillInput.LEFT,2,2,3,0,5);
        actor.aimedLeftSkill(longer,AttackAim.at(0,0));
        equal(CombatPhase.WINDUP,actor.phase(),"enhanced attack enters preparation");
        equal(ActionResult.ACCEPTED,actor.setGuarding(true),"late held renewal is idempotent during counter windup");
        check(actor.currentSkill()==longer&&actor.phase()==CombatPhase.WINDUP,"late held packet cannot cancel preparation");
        near(1.5,actor.attackDamageMultiplier(),"late held packet retains current enhancement");
        ticks(actor,2);actor.setGuarding(true,true);
        check(actor.currentSkill()==longer&&actor.phase()==CombatPhase.ACTIVE,"held mode renewal cannot cancel contact");
        ticks(actor,2);equal(CombatPhase.RECOVERY,actor.phase(),"enhanced attack reaches recovery");
        actor.setGuarding(true);
        check(actor.currentSkill()==longer&&actor.phase()==CombatPhase.RECOVERY,"late held packet cannot cancel counter recovery");
        actor.setGuarding(false);
        equal(ActionResult.ACCEPTED,actor.setGuarding(true),"real release then re-press retains intentional recovery cancel");
        equal(CombatPhase.GUARDING,actor.phase(),"new guard press actually starts guarding");
        near(1,actor.attackDamageMultiplier(),"intentional guard cancellation clears enhanced swing");
    }

    private static CombatActor ready(WeaponStyleDefinition style) { CombatActor actor=new CombatActor(UUID.randomUUID(),CombatRules.defaults());actor.setStyle(style);actor.requestDrawn();ticks(actor,actor.rules().drawTicks());return actor; }
    private static CombatActor parried(WeaponStyleDefinition style) { CombatActor actor=ready(style);actor.setGuarding(true);equal(HitOutcome.PARRIED,incoming(actor).outcome(),"perfect contact");return actor; }
    private static HitResult incoming(CombatActor actor) {return CombatResolver.receiveIncoming(null,actor,0,8,0,true,true,false);}
    private static WeaponStyleDefinition style(String id,boolean dual) {
        var guard=new WeaponStyleDefinition.GuardRules(120,.25,1,2,1,4,10,12,1,true,true);
        var heavy=new WeaponStyleDefinition.GuardRules(120,.2,1,0,1,4,10,12,1,true,true);
        return new WeaponStyleDefinition(id,id,"sword",true,List.of(new WeaponStyleDefinition.ComboStage("left")),dual?"":"right",guard,heavy,
                WeaponStyleDefinition.Techniques.none(),WeaponStyleDefinition.CancelRules.defaults(),dual?new WeaponControls("dual","left","left_other","right","right_other","",.18):WeaponControls.defaults());
    }
    private static Map<String,SkillDefinition> catalog() {return Map.of("left",LEFT,"right",RIGHT,"left_other",skill("left_other",SkillInput.LEFT,0,1,0,0,5),"right_other",skill("right_other",SkillInput.RIGHT,0,1,0,0,5));}
    private static SkillDefinition skill(String id,SkillInput input,int windup,int active,int recovery,int cooldown,double stamina) { return new SkillDefinition(id,id,"sword",input,windup,active,recovery,cooldown,stamina,0,8,3,90,0,true,true,false,"",""); }
    private static void ticks(CombatActor actor,int count) {for(int i=0;i<count;i++)actor.tick();}
    private static void near(double expected,double actual,String message) {check(Math.abs(expected-actual)<1e-8,message+": "+expected+" != "+actual);}
    private static void equal(Object expected,Object actual,String message) {check(expected.equals(actual),message+": "+expected+" != "+actual);}
    private static void check(boolean value,String message) {checks++;if(!value)throw new AssertionError(message);}
}
