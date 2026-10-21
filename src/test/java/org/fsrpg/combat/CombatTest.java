package org.fsrpg.combat;

import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Pure-Java regression checks; intentionally exercises behavior without Minecraft or a test framework. */
public final class CombatTest {
    private static int checks;

    public static void main(String[] args) {
        phasesAndDrawGate();
        resourcesAndCooldown();
        geometryAndVictimDeduplication();
        clashDirectionsAndDamageAdvantage();
        finalFrameExchangeAndClashPrepass();
        guardAndProjectileReflection();
        dodgeAndRecoveryCancel();
        invalidConfigurations();
        rapierFiveStageCombo();
        comboWindowsAndInterrupts();
        shortChargeAndStyleValidation();
        rapierGuardPressureAndHeavyHits();
        refinedGuardAndTechniques();
        System.out.println("CombatTest passed: " + checks + " checks");
    }

    private static void phasesAndDrawGate() {
        CombatActor actor = new CombatActor(UUID.randomUUID(), CombatRules.defaults());
        SkillDefinition skill = skill("left", SkillInput.LEFT, 2, 3, 2, 0);
        equal(actor.requestAttack(skill), ActionResult.NOT_DRAWN, "sheathed actor cannot attack");
        equal(actor.requestDodge(true), ActionResult.NOT_DRAWN, "sheathed actor cannot dodge");
        equal(actor.setGuarding(true), ActionResult.NOT_DRAWN, "sheathed actor cannot guard");
        equal(actor.toggleDrawn(), ActionResult.ACCEPTED, "drawing starts");
        equal(actor.phase(), CombatPhase.DRAWING, "drawing phase");
        tick(actor, 7);
        equal(actor.requestAttack(skill), ActionResult.NOT_DRAWN, "draw transition is locked");
        actor.tick();
        check(actor.drawn(), "draw complete after eight ticks");
        equal(actor.requestAttack(skill), ActionResult.ACCEPTED, "drawn actor can attack");
        equal(actor.phase(), CombatPhase.WINDUP, "attack begins windup");
        actor.tick();
        equal(actor.phase(), CombatPhase.WINDUP, "first windup tick");
        actor.tick();
        equal(actor.phase(), CombatPhase.ACTIVE, "active starts at boundary");
        tick(actor, 2);
        equal(actor.phase(), CombatPhase.ACTIVE, "last active tick remains active");
        actor.tick();
        equal(actor.phase(), CombatPhase.RECOVERY, "recovery starts at boundary");
        check(actor.activeSkill() == null, "recovery itself causes no damage");
        tick(actor, 2);
        equal(actor.phase(), CombatPhase.READY, "recovery completes exactly");
        actor.toggleDrawn();
        equal(actor.phase(), CombatPhase.SHEATHING, "sheath phase");
        equal(actor.requestAttack(skill), ActionResult.BUSY, "sheathing prevents attack");
        tick(actor, 8);
        check(!actor.drawn(), "sheath completes");
        equal(actor.phase(), CombatPhase.SHEATHED, "back to sheathed");
    }

    private static void resourcesAndCooldown() {
        CombatActor actor = ready(rules(0, 6, true, false, 1, 4, 50, 50));
        SkillDefinition costly = skill("costly", SkillInput.LEFT, 0, 1, 0, 5);
        actor.setResources(19, 100);
        equal(actor.requestAttack(costly), ActionResult.INSUFFICIENT_STAMINA, "insufficient stamina rejected");
        near(actor.stamina(), 19, "rejected actions do not charge");
        actor.setResources(100, 0);
        SkillDefinition spiritSkill = new SkillDefinition("spirit", "Spirit", "SWORD", SkillInput.SPECIAL,
                0, 1, 0, 0, 20, 10, 6, 3, 90, 0.3, true, true, false, "editor_rig.fsvoxel", "sword_thrust");
        equal(actor.requestAttack(spiritSkill), ActionResult.INSUFFICIENT_SPIRIT, "insufficient spirit rejected");
        near(actor.stamina(), 100, "failed spirit check does not charge stamina");
        actor.requestAttack(costly);
        near(actor.stamina(), 80, "attack costs charged on acceptance");
        actor.tick();
        equal(actor.requestAttack(costly), ActionResult.COOLDOWN, "cooldown survives action completion");
        tick(actor, 2);
        near(actor.stamina(), 90.15, "emitted miss costs half and recovery keeps running");
        actor.tick();
        near(actor.stamina(), 90.2, "idle regen per-second divided by 20 Hz");
        actor.tick();
        equal(actor.requestAttack(costly), ActionResult.ACCEPTED, "cooldown expires at timestamp");
        actor.setResources(90, 80);
        actor.setRules(new CombatRules(50, 40, 0, 0, 0, 0, 0, 120, 0.25, 50,
                4, 50, 8, 10, 2, 120, 12, 0, 6, 20, 30, 3, true, false));
        near(actor.stamina(), 50, "reload clamps existing stamina");
        near(actor.spirit(), 40, "reload clamps existing spirit");
    }

    private static void geometryAndVictimDeduplication() {
        CombatActor attacker = ready(baseRules());
        CombatActor defender = ready(baseRules());
        SkillDefinition skill = skill("left", SkillInput.LEFT, 0, 4, 2, 0);
        attacker.requestAttack(skill);
        equal(hit(attacker, defender, 4, 0, 0).outcome(), HitOutcome.OUT_OF_RANGE, "reach rejects distant target");
        equal(hit(attacker, defender, 2, 90, 0).outcome(), HitOutcome.OUTSIDE_ARC, "attacker arc rejects side target");
        HitResult first = hit(attacker, defender, 2, 0, 0);
        equal(first.outcome(), HitOutcome.HIT, "valid hit lands");
        near(first.damage(), 6, "skill damage passed through");
        equal(hit(attacker, defender, 2, 0, 0).outcome(), HitOutcome.ALREADY_HIT, "victim can be hit only once per swing");
        UUID mob = UUID.randomUUID();
        check(attacker.claimVictim(mob), "vanilla mob claimed once");
        check(!attacker.claimVictim(mob), "vanilla mob duplicate rejected");
        tick(attacker, 6);
        attacker.requestAttack(skill);
        check(attacker.claimVictim(mob), "new swing resets victim set");
        check(CombatResolver.withinArc(350, 90), "facing angles normalize across 360 degrees");
        check(!CombatResolver.withinArc(180, 120), "behind actor outside front arc");
    }

    private static void clashDirectionsAndDamageAdvantage() {
        SkillDefinition left = skill("left", SkillInput.LEFT, 0, 4, 4, 0);
        SkillDefinition right = skill("right", SkillInput.RIGHT, 0, 4, 4, 0);
        CombatActor a = ready(baseRules());
        CombatActor b = ready(baseRules());
        a.requestAttack(left);
        b.requestAttack(left);
        HitResult clash = hit(a, b, 2, 0, 0);
        equal(clash.outcome(), HitOutcome.CLASH, "mutual active melee contacts clash");
        near(clash.damage(), 0, "clash has no damage");
        near(a.stamina(), 80, "clash costs no additional stamina");
        equal(a.phase(), CombatPhase.READY, "clash adds no attacker stun or recovery");
        equal(b.phase(), CombatPhase.READY, "clash adds no defender stun or recovery");
        equal(a.requestAttack(left), ActionResult.ACCEPTED, "can attack immediately after zero-cooldown clash");
        a = ready(baseRules());
        b = ready(baseRules());
        a.requestAttack(left);
        b.requestAttack(right);
        equal(hit(a, b, 2, 0, 0).outcome(), HitOutcome.CLASH, "opposite swing directions also clash on real mutual contact");
        equal(hit(b, a, 2, 0, 0).outcome(), HitOutcome.INACTIVE, "resolved clash removes the reciprocal active hit");
        a.tick();
        b.tick();
        equal(a.phase(), CombatPhase.READY, "balanced opposite clash adds no delayed attacker stun");
        equal(b.phase(), CombatPhase.READY, "balanced opposite clash adds no delayed defender stun");
        check(a.counterRemainingTicks()==0&&b.counterRemainingTicks()==0,"balanced clash awards no damage-advantage counter");
        for(boolean reverse:List.of(false,true)) {
            a=ready(baseRules());b=ready(baseRules());
            var stronger=new SkillDefinition("stronger","Stronger","SWORD",SkillInput.SPECIAL,0,1,0,9,
                    20,0,10,3,90,.3,false,false,false,"editor_rig.fsvoxel","sword_thrust");
            a.requestAttack(stronger);b.requestAttack(right);
            var result=reverse?hit(b,a,2,0,0):hit(a,b,2,0,0);
            equal(result.outcome(),HitOutcome.CLASH,"SPECIAL and legacy clashable=false do not prohibit a real clash");
            near(result.damage(),reverse?0:1,"only the advantage beyond three becomes residual outgoing damage");
            equal(a.phase(),CombatPhase.READY,"stronger side recovers immediately");equal(b.phase(),CombatPhase.STUNNED,"weaker side takes configured parry stun");
            equal(a.counterRemainingTicks(),4,"stronger side earns the ordinary four-tick counter chance");equal(b.counterRemainingTicks(),0,"weaker side gains no counter chance");
            near(a.stamina(),80,"stronger clash retains paid attack cost without guard fees");near(b.stamina(),80,"weaker clash retains paid attack cost without guard fees");
            equal(a.cooldownRemaining("stronger"),9,"advantage clash preserves the accepted cooldown");
            check(a.staminaCombatState()&&b.staminaCombatState(),"both sides record real combat activity");
        }
        a = ready(baseRules());
        b = ready(baseRules());
        SkillDefinition cooldownLeft = skill("left_cd", SkillInput.LEFT, 0, 4, 4, 7);
        a.requestAttack(cooldownLeft);
        b.requestAttack(cooldownLeft);
        hit(a, b, 2, 0, 0);
        equal(a.cooldownRemaining("left_cd"), 7, "clash preserves original configured cooldown");
    }

    private static void guardAndProjectileReflection() {
        SkillDefinition skill = skill("left", SkillInput.LEFT, 0, 4, 2, 0);
        CombatActor attacker = ready(baseRules());
        CombatActor defender = ready(baseRules());
        attacker.requestAttack(skill);
        defender.setGuarding(true);
        HitResult parry = hit(attacker, defender, 2, 0, 0);
        equal(parry.outcome(), HitOutcome.PARRIED, "fresh front guard parries");
        near(defender.stamina(), 50, "perfect guard charges on successful contact");
        equal(attacker.phase(), CombatPhase.STUNNED, "parry interrupts attacker immediately");
        defender = ready(baseRules());
        defender.setGuarding(true);
        tick(defender, 2);
        defender.setGuarding(true);
        tick(defender, 2);
        check(!defender.inPerfectGuardWindow(), "repeated held guard does not reset perfect window");
        HitResult blocked = CombatResolver.receiveIncoming(null, defender, 0, 8, 0.5, true, true, true);
        equal(blocked.outcome(), HitOutcome.BLOCKED, "late guard reduces arrow damage");
        near(blocked.damage(), 2, "guard reduction configurable");
        check(!blocked.reflectProjectile(), "ordinary arrow guard does not reflect");
        defender = ready(baseRules());
        defender.setGuarding(true);
        HitResult reflected = CombatResolver.receiveIncoming(null, defender, 0, 8, 0.5, true, true, true);
        check(reflected.reflectProjectile(), "perfect guard requests projectile reflection");
        near(reflected.damage(), 0, "reflected projectile causes zero incoming damage");
        defender = ready(baseRules());
        defender.setGuarding(true);
        equal(CombatResolver.receiveIncoming(null, defender, 180, 8, 0.5, true, true, false).outcome(),
                HitOutcome.HIT, "rear hit bypasses guard");
        defender = ready(baseRules());
        defender.setResources(49, 100);
        defender.setGuarding(true);
        HitResult broken = CombatResolver.receiveIncoming(null, defender, 0, 8, 0.5, true, true, false);
        equal(broken.outcome(), HitOutcome.GUARD_BROKEN, "unaffordable guard breaks");
        near(broken.damage(), 8, "broken guard passes full damage");
        near(defender.stamina(), 49, "failed guard does not consume partial cost");
        defender = ready(baseRules());
        defender.setGuarding(true);
        equal(CombatResolver.receiveIncoming(null, defender, 0, 8, 0.5, false, false, false).outcome(),
                HitOutcome.HIT, "unblockable unparryable skill bypasses guard");
    }

    private static void finalFrameExchangeAndClashPrepass() {
        CombatActor a = ready(baseRules());
        CombatActor b = ready(baseRules());
        SkillDefinition left = skill("last_left", SkillInput.LEFT, 0, 1, 0, 0);
        SkillDefinition right = skill("last_right", SkillInput.RIGHT, 0, 1, 0, 0);
        a.requestAttack(left);
        b.requestAttack(right);
        check(CombatResolver.canClash(a, b, 2, 0, 0), "opposite input prepass accepts actual mutual last-frame contact");
        equal(a.phase(), CombatPhase.ACTIVE, "predicate preserves attacker last frame");
        equal(b.phase(), CombatPhase.ACTIVE, "predicate preserves defender last frame");
        check(!a.hasHit(b.id()) && !b.hasHit(a.id()), "predicate does not claim either victim");
        // Generic incoming events remain simultaneous trades when no paired melee contact was collected.
        HitResult outgoingA = CombatResolver.receiveIncoming(a,b,0,6,.3,true,true,false);
        HitResult outgoingB = CombatResolver.receiveIncoming(b,a,0,6,.3,true,true,false);
        check(outgoingA.dealsDamage() && outgoingB.dealsDamage(), "both last-frame intents exist before tick advancement");
        // The adapter applies the collected damage after this point, so a lethal first hit
        // cannot erase the counterattack intent. Each core actor advances only afterwards.
        a.tick(); b.tick();
        equal(a.phase(), CombatPhase.STUNNED, "last-frame attacker receives queued trade reaction");
        equal(b.phase(), CombatPhase.STUNNED, "last-frame defender receives queued trade reaction");
        a = ready(baseRules()); b = ready(baseRules());
        a.requestAttack(left); b.requestAttack(left);
        check(!CombatResolver.canClash(a, b, 2, 0, 180), "one actor facing away cannot clash");
        check(!a.hasHit(b.id()), "geometry-only prepass does not consume an ordinary hit");
        near(a.stamina(), 80, "prepass does not charge attacker");
        near(b.stamina(), 80, "prepass does not charge defender");
        check(CombatResolver.canClash(a, b, 2, 0, 0), "mutual front-facing active swings can clash");
        equal(a.phase(), CombatPhase.ACTIVE, "successful predicate is also side-effect-free");
        equal(hit(a, b, 2, 0, 0).outcome(), HitOutcome.CLASH, "predicate leaves actual clash resolution available");
        a = ready(baseRules()); b = ready(baseRules());
        a.requestAttack(left); b.requestAttack(left);
        a.markHit(b.id());
        check(!CombatResolver.canClash(a, b, 2, 0, 0), "already-consumed victim cannot clash again");
    }

    private static void dodgeAndRecoveryCancel() {
        CombatActor actor = ready(baseRules());
        SkillDefinition skill = skill("left", SkillInput.LEFT, 1, 1, 6, 0);
        actor.requestAttack(skill);
        equal(actor.requestDodge(true), ActionResult.ACCEPTED, "dodge cancels windup");
        actor=ready(baseRules()); actor.requestAttack(skill);
        actor.tick();
        equal(actor.requestDodge(true), ActionResult.BUSY, "dodge cannot cancel active frames");
        actor.tick();
        equal(actor.requestDodge(true), ActionResult.ACCEPTED, "configured dodge cancels recovery");
        near(actor.stamina(), 60, "emitted empty attack costs ten and dodge costs thirty");
        check(actor.invulnerable(), "dodge initial invulnerability");
        equal(CombatResolver.receiveIncoming(null, actor, 0, 8, 0.5, true, true, false).outcome(),
                HitOutcome.DODGED, "incoming attack blocked by dodge i-frames");
        double distance = 0;
        for (int i = 0; i < 6; i++) distance += actor.tick().dodgeDistanceThisTick();
        check(!actor.invulnerable(), "six configured i-frames end exactly");
        for (int i = 0; i < 6; i++) distance += actor.tick().dodgeDistanceThisTick();
        near(distance, 3, "movement increments integrate to configured dodge distance");
        equal(actor.phase(), CombatPhase.READY, "dodge duration complete");
        equal(actor.requestDodge(true), ActionResult.COOLDOWN, "dodge cooldown survives animation");
        actor = ready(rules(0, 6, false, false, 0, 20, 50, 50));
        actor.requestAttack(skill);
        tick(actor, 2);
        equal(actor.requestDodge(true), ActionResult.BUSY, "recovery cancellation can be disabled");
        actor = ready(baseRules());
        equal(actor.requestDodge(false), ActionResult.AIR_DODGE_DISABLED, "air dodge disabled by default");
        actor = ready(rules(2, 4, true, true, 0, 20, 50, 50));
        equal(actor.requestDodge(false), ActionResult.ACCEPTED, "config can enable air dodge");
        check(!actor.invulnerable(), "configured i-frame delay respected");
        tick(actor, 2);
        check(actor.invulnerable(), "delayed i-frames start at configured tick");
    }

    private static void invalidConfigurations() {
        rejects(() -> new SkillDefinition("bad", "Bad", "SWORD", SkillInput.LEFT,
                -1, 3, 3, 0, 20, 0, 6, 3, 90, 0.3, true, true, true, "editor_rig.fsvoxel", "left"), "negative timing rejected");
        rejects(() -> new SkillDefinition("bad", "Bad", "SWORD", SkillInput.LEFT,
                1, 0, 3, 0, 20, 0, 6, 3, 90, 0.3, true, true, true, "editor_rig.fsvoxel", "left"), "zero active duration rejected");
        rejects(() -> new SkillDefinition("bad", "Bad", "SWORD", SkillInput.LEFT,
                1, 3, 3, 0, 20, 0, Double.NaN, 3, 90, 0.3, true, true, true, "editor_rig.fsvoxel", "left"), "NaN damage rejected");
        rejects(() -> new SkillDefinition("bad", "Bad", "SWORD", SkillInput.LEFT,
                1, 3, 3, 0, 20, 0, 6, 3, 90, 0.3, true, true, true, "../outside.fsvoxel", "left"), "asset path traversal rejected");
        rejects(() -> rules(10, 6, true, false, 0, 20, 50, 50), "i-frames extending past dodge rejected");
        rejects(() -> rules(0, 6, true, false, Double.POSITIVE_INFINITY, 20, 50, 50), "infinite regeneration rejected");
        CombatActor actor = ready(baseRules());
        rejects(() -> actor.consumeStamina(-1), "negative stamina debit rejected");
        rejects(() -> CombatResolver.withinArc(Double.NaN, 120), "NaN angle rejected");
    }

    private static CombatRules baseRules() { return rules(0, 6, true, false, 0, 20, 50, 50); }

    private static void rapierFiveStageCombo() {
        var catalog=rapierSkills();var actor=rapierActor();
        check(actor.requestStyledAttack(SkillInput.LEFT,catalog).started(),"first click starts first stage");
        actor.drainStartedSkills();
        for(int stage=1;stage<=4;stage++) {
            var current=actor.currentSkill();
            equal(actor.requestStyledAttack(SkillInput.LEFT,catalog).status(),ComboInputStatus.BUFFERED,"tick-zero click buffers next stage");
            double stamina=actor.stamina();
            for(int repeat=0;repeat<12;repeat++) check(actor.requestStyledAttack(SkillInput.LEFT,catalog).accepted(),"repeated clicks are harmless accepted intent");
            near(actor.stamina(),stamina,"buffer costs no extra stamina");
            equal(actor.drainStartedSkills().size(),0,"buffer does not trigger quest progress");
            int active=0;
            for(int t=0;t<current.windupTicks()+current.activeTicks();t++) {
                if(actor.phase()==CombatPhase.ACTIVE) { active++; actor.markAttackContact(actor.snapshot().swingSequence()); }
                actor.tick();
            }
            equal(active,current.activeTicks(),"every active collision frame completes before chaining");
            equal(actor.comboSnapshot().stage(),stage+1,"next stage begins at recovery boundary");
            equal(actor.snapshot().skillElapsedTicks(),0,"next attack receives full windup");
            equal(actor.drainStartedSkills().size(),1,"one start event per accepted stage despite mashing");
        }
        near(actor.stamina(),0,"five stages charge exactly five costs");
        tick(actor,actor.currentSkill().durationTicks());
        equal(actor.phase(),CombatPhase.READY,"no follow-up permits normal final recovery");
    }

    private static void comboWindowsAndInterrupts() {
        var catalog=rapierSkills();
        // Every instant from first windup frame to final recovery frame accepts a follow-up.
        for(int inputTick=0;inputTick<16;inputTick++) {
            var actor=rapierActor();actor.requestStyledAttack(SkillInput.LEFT,catalog);actor.drainStartedSkills();
            tick(actor,inputTick);
            check(actor.requestStyledAttack(SkillInput.LEFT,catalog).accepted(),"no early/late input gate at tick "+inputTick);
            if(inputTick<7) {
                equal(actor.currentSkill().id(),"sword_left","buffer preserves current attack");
                tick(actor,7-inputTick);
            }
            equal(actor.currentSkill().id(),"rapier_downcut","follow-up skips recovery at tick "+inputTick);
            equal(actor.snapshot().skillElapsedTicks(),0,"follow-up starts from complete windup");
        }
        for(int at:new int[]{0,3,7,15}) {
            var actor=rapierActor();actor.requestStyledAttack(SkillInput.LEFT,catalog);tick(actor,at);
            equal(actor.setGuarding(true),ActionResult.ACCEPTED,"guard cancels windup or recovery "+at);
            check(actor.currentSkill()==null&&!actor.comboSnapshot().queued(),"guard removes pending damage and follow-up");
        }
        for(int at=4;at<7;at++) {
            var actor=rapierActor();actor.requestStyledAttack(SkillInput.LEFT,catalog);actor.drainStartedSkills();tick(actor,at);
            equal(actor.setGuarding(true),ActionResult.BUSY,"active cannot cancel into guard");
            equal(actor.requestDodge(true),ActionResult.BUSY,"active cannot cancel into dodge");
            check(actor.requestStyledAttack(SkillInput.RIGHT,catalog).accepted(),"active accepts alternate attack intent");
            equal(actor.currentSkill().id(),"sword_left","alternate input cannot truncate active phase");
            tick(actor,7-at);
            equal(actor.currentSkill().id(),"sword_thrust","alternate attack starts only after active finishes");
            equal(actor.drainStartedSkills().size(),1,"queued alternate emits exactly one start");
        }
        var actor=rapierActor();actor.requestStyledAttack(SkillInput.LEFT,catalog);actor.drainStartedSkills();
        actor.requestStyledAttack(SkillInput.LEFT,catalog);actor.setGuarding(true);tick(actor,20);
        equal(actor.drainStartedSkills().size(),0,"guard cancel removes stale follow-up intent");
        actor=rapierActor();actor.requestStyledAttack(SkillInput.LEFT,catalog);actor.drainStartedSkills();
        actor.requestStyledAttack(SkillInput.LEFT,catalog);tick(actor,6);actor.queueHitStun(2);actor.tick();
        equal(actor.phase(),CombatPhase.STUNNED,"hit reaction wins against queued follow-up");
        equal(actor.drainStartedSkills().size(),0,"forced interrupt cannot spend next skill cost");
        actor=rapierActor();actor.setResources(20,100);actor.requestStyledAttack(SkillInput.LEFT,catalog);actor.drainStartedSkills();
        actor.requestStyledAttack(SkillInput.LEFT,catalog);tick(actor,7);
        equal(actor.comboSnapshot().lastRejection(),"INSUFFICIENT_STAMINA","resource check occurs at actual start");
        equal(actor.currentSkill().id(),"sword_left","failed follow-up retains current recovery");
        actor.restoreResources(20,0);
        check(actor.requestStyledAttack(SkillInput.LEFT,catalog).started(),"any recovery frame can retry after resource restoration");
        var base=rapierStyle();
        var locked=new WeaponStyleDefinition(base.id(),base.label(),base.weaponGroup(),true,base.leftCombo(),base.rightSkill(),base.guard(),null,
                WeaponStyleDefinition.Techniques.none(),new WeaponStyleDefinition.CancelRules(false,false,false,false,false,false));
        actor=rapierActor();actor.setStyle(locked);actor.requestStyledAttack(SkillInput.LEFT,catalog);
        equal(actor.setGuarding(true),ActionResult.BUSY,"style can disable windup guard cancel");
        equal(actor.requestDodge(true),ActionResult.BUSY,"style can disable windup dodge cancel");
        equal(actor.requestStyledAttack(SkillInput.RIGHT,catalog).status(),ComboInputStatus.BUFFERED,"disabled attack cancel still accepts a follow-up without skipping the current attack");
        tick(actor,7);
        equal(actor.setGuarding(true),ActionResult.BUSY,"style can disable recovery guard cancel");
        equal(actor.requestStyledAttack(SkillInput.LEFT,catalog).status(),ComboInputStatus.BUFFERED,"disabled recovery cancel keeps the next click until full recovery");
    }

    private static void shortChargeAndStyleValidation() {
        var catalog=rapierSkills();var actor=rapierActor();
        check(actor.requestStyledAttack(SkillInput.RIGHT,catalog).started(),"right starts charge");
        tick(actor,9);check(actor.activeSkill()==null,"charge has no early hit");
        equal(actor.requestStyledAttack(SkillInput.LEFT,catalog).status(),ComboInputStatus.BUFFERED,"ordinary right attack accepts the next left click while preparing");
        tick(actor,1+actor.currentSkill().activeTicks());
        equal(actor.currentSkill().id(),"sword_left","left follows the complete right attack");
        actor=rapierActor();actor.requestStyledAttack(SkillInput.LEFT,catalog);
        equal(actor.requestStyledAttack(SkillInput.RIGHT,catalog).status(),ComboInputStatus.BUFFERED,"right click queues during left windup");
        tick(actor,actor.currentSkill().windupTicks()+actor.currentSkill().activeTicks());
        equal(actor.currentSkill().id(),"sword_thrust","following right attack starts from full preparation");
        tick(actor,10);equal(actor.phase(),CombatPhase.ACTIVE,"replacement receives full configured windup");
        var disabled=new WeaponStyleDefinition("spear","Reserved","spear",false,List.of(),"",rapierStyle().guard());
        equal(actor.setStyle(disabled),ActionResult.STYLE_DISABLED,"reserved styles remain disabled");
        rejects(()->new WeaponStyleDefinition.ComboStage("sword_left",-1,0,0,0),"legacy timing still validates numeric bounds");
        new WeaponStyleDefinition("legacy","Legacy","sword",true,List.of(new WeaponStyleDefinition.ComboStage("sword_left",12,1,1,20)),"",rapierStyle().guard()).validateSkills(catalog);
        checks++;
        rejects(()->new WeaponStyleDefinition("bad","Bad","sword",true,List.of(),"",rapierStyle().guard()),"empty enabled combo rejected");
        var missing=new WeaponStyleDefinition("missing","Missing","sword",true,List.of(new WeaponStyleDefinition.ComboStage("missing_skill")),"",rapierStyle().guard());
        rejects(()->missing.validateSkills(catalog),"unknown combo references rejected");
        rejects(()->new WeaponStyleDefinition.GuardRules(90,Double.NaN,10,3,10,8,7,12,1,true,true),"nonfinite guard multiplier rejected");
    }

    private static void rapierGuardPressureAndHeavyHits() {
        CombatActor defender = rapierActor();
        defender.setGuarding(true); tick(defender, 4);
        HitResult first = CombatResolver.receiveIncoming(null, defender, 0, 10, 0.5, true, true, false);
        equal(first.outcome(), HitOutcome.BLOCKED, "rapier can ordinarily block one light attack");
        near(first.damage(), 4.5, "rapier uses its style damage reduction");
        near(defender.stamina(), 90, "rapier style guard cost overrides global guard cost");
        HitResult second = CombatResolver.receiveIncoming(null, defender, 0, 10, 0.5, true, true, false);
        equal(second.outcome(), HitOutcome.GUARD_BROKEN, "second short-interval light hit breaks guard");
        near(second.damage(), 10, "pressure guard break receives full damage");
        near(defender.stamina(), 90, "failed pressure defense has no extra success cost");
        equal(defender.phase(), CombatPhase.STUNNED, "pressure guard break ends guarding immediately");
        defender = rapierActor(); defender.setGuarding(true);
        HitResult perfect = CombatResolver.receiveIncoming(null, defender, 0, 10, 0.5, true, true, false);
        equal(perfect.outcome(), HitOutcome.PARRIED, "rapier can perfectly guard first light hit");
        defender.setGuarding(false); defender.setGuarding(true);
        check(defender.inPerfectGuardWindow(), "E repress refreshes ordinary timing window");
        equal(CombatResolver.receiveIncoming(null, defender, 0, 10, 0.5, true, true, false).outcome(), HitOutcome.GUARD_BROKEN,
                "E repress cannot bypass rapid-defense pressure rule");
        defender = rapierActor(); defender.setGuarding(true); tick(defender, 4);
        CombatResolver.receiveIncoming(null, defender, 0, 10, 0.5, true, true, false); tick(defender, 12);
        equal(CombatResolver.receiveIncoming(null, defender, 0, 10, 0.5, true, true, false).outcome(), HitOutcome.BLOCKED,
                "repeat defense succeeds exactly when pressure window expires");
        defender = rapierActor(); defender.setGuarding(true);
        HitResult heavy = CombatResolver.receiveIncoming(null, defender, 0, 12, 0.8, false, true, false);
        equal(heavy.outcome(), HitOutcome.GUARD_BROKEN, "heavy blockable=false cannot bypass rapier guard via parryable=true");
        near(heavy.damage(), 12, "heavy strike causes full damage even during perfect window");
        near(defender.stamina(), 100, "heavy failed guard does not charge success cost");
        defender = rapierActor(); defender.setGuarding(true);
        equal(CombatResolver.receiveIncoming(null, defender, 60, 10, 0.5, true, true, false).outcome(), HitOutcome.HIT,
                "rapier guard applies narrower configured frontal arc");
        defender = ready(baseRules()); defender.setGuarding(true);
        equal(CombatResolver.receiveIncoming(null, defender, 0, 10, 0.5, false, true, false).outcome(), HitOutcome.PARRIED,
                "legacy sword perfect guard retains previous heavy parry behavior");
    }

    private static void refinedGuardAndTechniques() {
        var base = rapierStyle();
        var heavy = new WeaponStyleDefinition.GuardRules(120, .2, 70, 0, 70, 0, 18, 12, 1, true, true);
        var openings = new WeaponStyleDefinition.Techniques("riposte", 20, "reprise", 12);
        var style = new WeaponStyleDefinition(base.id(), base.label(), base.weaponGroup(), true,
                base.leftCombo(), base.rightSkill(), base.guard(), heavy, openings);
        var catalog = new LinkedHashMap<>(rapierSkills());
        for (String id : List.of("riposte", "reprise")) catalog.put(id, new SkillDefinition(id, id, "sword", SkillInput.RIGHT,
                3, 2, 8, 16, 18, 0, 7, 3.4, 35, .3, true, true, false, "rapier.fsvoxel", "rapier_" + id));
        style.validateSkills(catalog);
        CombatActor a = rapierActor(); a.setStyle(style);
        equal(a.setGuarding(true, true), ActionResult.ACCEPTED, "heavy guard accepted");
        check(a.heavyGuard() && !a.inPerfectGuardWindow(), "heavy guard has no light deflection window");
        var blocked = CombatResolver.receiveIncoming(null, a, 55, 10, .4, true, true, false);
        equal(blocked.outcome(), HitOutcome.BLOCKED, "heavy guard covers its wider angle");
        near(blocked.damage(), 2, "heavy guard has its own stronger reduction"); near(a.stamina(), 30, "heavy guard costs seventy stamina");
        equal(a.techniqueSnapshot().remainingTicks(), 0, "ordinary heavy block does not award riposte");
        tick(a, 4); a.setGuarding(true, false);
        check(!a.inPerfectGuardWindow(), "switching guard mode does not restart perfect window");
        equal(CombatResolver.receiveIncoming(null, a, 0, 10, .4, true, true, false).outcome(), HitOutcome.GUARD_BROKEN,
                "switching mode cannot evade pressure history");
        a = rapierActor(); a.setStyle(style); a.setGuarding(true, true);
        equal(CombatResolver.receiveIncoming(null, a, 0, 12, .4, false, false, false).outcome(), HitOutcome.GUARD_BROKEN,
                "rapier heavy guard remains vulnerable to heavy attacks");
        a = rapierActor(); a.setStyle(style);
        equal(a.requestAttack(catalog.get("riposte")), ActionResult.TECHNIQUE_NOT_READY, "named skill cannot bypass riposte prerequisite");
        equal(a.requestAttack(catalog.get("reprise")), ActionResult.TECHNIQUE_NOT_READY, "named skill cannot bypass dodge prerequisite");
        a.setGuarding(true);
        equal(CombatResolver.receiveIncoming(null, a, 0, 10, .3, true, true, false).outcome(), HitOutcome.PARRIED, "light deflection opens riposte");
        equal(a.techniqueSnapshot().skillId(), "riposte", "riposte selected for the right click");
        equal(a.techniqueSnapshot().remainingTicks(), 20, "riposte window starts on the successful contact");
        a.setResources(0, 100);
        equal(a.requestStyledAttack(SkillInput.RIGHT, catalog).rejection(), ActionResult.INSUFFICIENT_STAMINA, "conditional attack still pays costs");
        equal(a.techniqueSnapshot().remainingTicks(), 20, "rejected attack keeps opportunity");
        a.setResources(100, 100);
        check(a.requestStyledAttack(SkillInput.RIGHT, catalog).started(), "right click starts ready riposte");
        equal(a.currentSkill().id(), "riposte", "server substituted the conditional definition");
        equal(a.snapshot().phaseTicksRemaining(), 3, "riposte has configured short windup");
        equal(a.techniqueSnapshot().remainingTicks(), 0, "accepted riposte consumes window once");
        near(a.stamina(), 82, "riposte resource cost charged once");
        tick(a, 13);
        equal(a.requestAttack(catalog.get("riposte")), ActionResult.TECHNIQUE_NOT_READY, "conditional skill cannot repeat after recovery");
        a = rapierActor(); a.setStyle(style); a.setGuarding(true);
        CombatResolver.receiveIncoming(null, a, 0, 10, .3, true, true, false); tick(a, 20);
        equal(a.techniqueSnapshot().remainingTicks(), 0, "riposte expires on exact boundary");
        check(a.requestStyledAttack(SkillInput.RIGHT, catalog).started(), "expired right click uses normal thrust");
        equal(a.currentSkill().id(), "sword_thrust", "normal right action restored");
        a = rapierActor(); a.setStyle(style); a.requestDodge(true);
        equal(a.techniqueSnapshot().remainingTicks(), 0, "dodge start grants no early attack");
        tick(a, a.rules().dodgeDurationTicks());
        equal(a.techniqueSnapshot().skillId(), "reprise", "completed dodge opens reprise");
        equal(a.techniqueSnapshot().remainingTicks(), 12, "reprise timer starts at dodge completion");
        check(a.requestStyledAttack(SkillInput.RIGHT, catalog).started(), "right click consumes dodge follow-up");
        equal(a.currentSkill().id(), "reprise", "reprise definition selected");
        a = rapierActor(); a.setStyle(style); a.requestDodge(true); a.stun(1); tick(a, 20);
        equal(a.techniqueSnapshot().remainingTicks(), 0, "interrupted dodge cannot grant a follow-up later");
        a = rapierActor(); a.setStyle(style); a.requestDodge(true); tick(a, a.rules().dodgeDurationTicks());
        a.queueHitStun(0);
        equal(a.techniqueSnapshot().remainingTicks(), 0, "even a zero-stun hit closes the opening");
        a = rapierActor(); a.setStyle(style); a.requestDodge(true); tick(a, a.rules().dodgeDurationTicks());
        a.setStyle(base);
        equal(a.techniqueSnapshot().remainingTicks(), 0, "config reload cannot transfer an opening to changed technique definitions");
        rejects(() -> new WeaponStyleDefinition.Techniques("riposte", 0, "", 0), "enabled technique needs a positive window");
        rejects(() -> new WeaponStyleDefinition.Techniques("riposte", 20, "riposte", 12), "techniques cannot alias the same skill");
        var bad = new WeaponStyleDefinition(base.id(), base.label(), base.weaponGroup(), true, base.leftCombo(), base.rightSkill(), base.guard(), heavy,
                new WeaponStyleDefinition.Techniques("missing", 20, "", 0));
        rejects(() -> bad.validateSkills(catalog), "missing technique reference rejected");
    }

    private static CombatActor rapierActor() {
        CombatActor actor = ready(baseRules()); actor.setStyle(rapierStyle()); return actor;
    }

    private static WeaponStyleDefinition rapierStyle() {
        return new WeaponStyleDefinition("rapier", "Rapier", "sword", true, List.of(
                new WeaponStyleDefinition.ComboStage("sword_left", 5, 12, 12, 3),
                new WeaponStyleDefinition.ComboStage("rapier_downcut", 6, 13, 13, 3),
                new WeaponStyleDefinition.ComboStage("rapier_crosscut", 8, 14, 14, 3),
                new WeaponStyleDefinition.ComboStage("rapier_light_thrust", 4, 10, 10, 3),
                new WeaponStyleDefinition.ComboStage("rapier_wrist_rise", 0, 0, 0, 0)), "sword_thrust",
                new WeaponStyleDefinition.GuardRules(90, 0.45, 10, 3, 10, 8, 7, 12, 1, true, true));
    }

    private static Map<String, SkillDefinition> rapierSkills() {
        Map<String, SkillDefinition> result = new LinkedHashMap<>();
        result.put("sword_left", skill("sword_left", SkillInput.LEFT, 4, 3, 9, 0));
        result.put("rapier_downcut", skill("rapier_downcut", SkillInput.LEFT, 5, 3, 9, 0));
        result.put("rapier_crosscut", skill("rapier_crosscut", SkillInput.LEFT, 4, 6, 8, 0));
        result.put("rapier_light_thrust", skill("rapier_light_thrust", SkillInput.LEFT, 3, 2, 8, 0));
        result.put("rapier_wrist_rise", skill("rapier_wrist_rise", SkillInput.LEFT, 5, 3, 12, 0));
        result.put("sword_thrust", skill("sword_thrust", SkillInput.RIGHT, 10, 3, 14, 0));
        return result;
    }

    private static CombatRules rules(int iframeStart, int iframeDuration, boolean cancel, boolean air,
                                     double regen, int delay, double guardCost, double parryCost) {
        return new CombatRules(100, 100, regen, regen, delay, 0, 0, 120, 0.25, guardCost,
                4, parryCost, 8, 10, 2, 120, 12, iframeStart, iframeDuration, 20, 30, 3, cancel, air);
    }

    private static CombatActor ready(CombatRules rules) {
        CombatActor actor = new CombatActor(UUID.randomUUID(), rules);
        actor.toggleDrawn();
        return actor;
    }

    private static SkillDefinition skill(String id, SkillInput input, int windup, int active, int recovery, int cooldown) {
        return new SkillDefinition(id, id, "SWORD", input, windup, active, recovery, cooldown,
                20, 0, 6, 3, 90, 0.3, true, true, true, "editor_rig.fsvoxel", "sword_left");
    }

    private static HitResult hit(CombatActor a, CombatActor b, double distance, double angleA, double angleB) {
        return CombatResolver.resolveHit(a, b, distance, angleA, angleB);
    }

    private static void tick(CombatActor actor, int count) { for (int i = 0; i < count; i++) actor.tick(); }

    private static void near(double actual, double expected, String label) {
        check(Math.abs(actual - expected) < 1e-8, label + " (" + actual + " != " + expected + ")");
    }

    private static void equal(Object actual, Object expected, String label) {
        check(actual.equals(expected), label + " (" + actual + " != " + expected + ")");
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static void rejects(Runnable action, String label) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(label);
    }
}
