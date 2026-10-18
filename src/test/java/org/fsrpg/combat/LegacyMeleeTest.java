package org.fsrpg.combat;

import java.util.*;

/** Legacy charged-thrust commitment plus unrestricted directional cuts using the original four clips. */
public final class LegacyMeleeTest {
    private static int checks;
    private static final SkillDefinition THRUST = skill("sword_thrust", SkillInput.RIGHT, 7,2,2,20,9,"sword");
    private static final SkillDefinition SLASH = skill("sword_left", SkillInput.LEFT, 2,1,1,0,6,"sword");
    public static void main(String[] args) {
        normalCommit(); cancellation(); guiCancellation(); holdAndParry(); forcedInterrupts(); legacyDualRouting();
        legacyDualCadence(); legacyDualBufferedCadence(); legacyDualCadenceResets(); legacyDualBodyAdjustment(); orderedDualCompatibility();
        System.out.println("LegacyMeleeTest passed: " + checks + " checks");
    }
    private static void normalCommit() {
        CombatActor a=ready(null); AttackAim aim=AttackAim.at(25,-30);
        equal(ActionResult.ACCEPTED,a.beginChargedAttack(THRUST,aim,false,false,16,200),"normal thrust begins");
        equal(CombatPhase.WINDUP,a.phase(),"normal thrust starts chamber"); equal(0,a.cooldownRemaining(THRUST.id()),"no cooldown beforecommit");
        check(!a.consumeChargeLunge(),"no lunge beforecommit");
        tick(a,6); equal(CombatPhase.WINDUP,a.phase(),"first six ticks remain windup"); equal(0,a.cooldownRemaining(THRUST.id()),"windup hasno cooldown");
        a.tick(); equal(CombatPhase.ACTIVE,a.phase(),"tickseven commits"); equal(20,a.cooldownRemaining(THRUST.id()),"twenty-tick cooldown begins atthecommit");
        equal(aim,a.attackAim(),"camera pitchandyaw preserved for3Dadapterlunge");
        check(a.consumeChargeLunge(),"commit issuesone adapterlunge intent"); check(!a.consumeChargeLunge(),"lunge latch cannotbe consumedtwice");
        check(!a.chargedParried(),"ordinary thrust no parrymultiplier");
        a.tick(); equal(CombatPhase.ACTIVE,a.phase(),"secondcontacttickactive"); a.tick(); equal(CombatPhase.RECOVERY,a.phase(),"two active ticks thenrecovery");
        tick(a,2); equal(CombatPhase.READY,a.phase(),"two recovery ticks thenready"); equal(16,a.cooldownRemaining(THRUST.id()),"recovery doesnot reset cooldown");
        equal(ActionResult.COOLDOWN,a.beginChargedAttack(THRUST,aim,false,false,16,200),"cannot bypass remaining cooldown");
        tick(a,16); equal(ActionResult.ACCEPTED,a.beginChargedAttack(THRUST,aim,false,false,16,200),"nextthrust allowed atexact cooldown boundary");
    }
    private static void cancellation() {
        CombatActor a=ready(null); a.beginChargedAttack(THRUST,null,false,false,16,200); tick(a,6);
        equal(ActionResult.ACCEPTED,a.requestAttack(SLASH),"normal slash cancels uncommitted thrust");
        equal(SLASH.id(),a.currentSkill().id(),"cancel starts real slash"); equal(0,a.cooldownRemaining(THRUST.id()),"cancelledwindup remainsfree"); check(!a.chargeSnapshot().active(),"cancelclears chargestate"); check(!a.consumeChargeLunge(),"cancel cannotlunge");
        a=ready(null); a.beginChargedAttack(THRUST,null,true,true,16,200); tick(a,10);
        equal(ActionResult.ACCEPTED,a.requestDodge(true),"dodge cancels heldwindup"); equal(0,a.cooldownRemaining(THRUST.id()),"dodgecancel consumesnothrust cooldown"); check(!a.chargeSnapshot().active(),"dodgeclearshold");
        a=ready(null); a.beginChargedAttack(THRUST,null,true,true,16,200);
        equal(ActionResult.ACCEPTED,a.setGuarding(true),"normalguard cancelswindup"); equal(0,a.cooldownRemaining(THRUST.id()),"guardcancel free"); check(!a.chargeSnapshot().active(),"guardclearshold");
        a=ready(null); a.beginChargedAttack(THRUST,null,false,false,16,200); tick(a,7);
        equal(ActionResult.BUSY,a.requestDodge(true),"active contact cannotbe dodgecancelled");
    }
    private static void holdAndParry() {
        CombatActor a=ready(null); a.beginChargedAttack(THRUST,null,true,true,16,200); tick(a,15);
        equal(CombatPhase.WINDUP,a.phase(),"hold extendswindup beyondnormal7ticks"); check(!a.chargeSnapshot().fullyCharged(),"fifteen ticks notfullycharged");
        a.tick(); check(a.chargeSnapshot().fullyCharged(),"sixteen ticks reachesfullcharge"); tick(a,40);
        equal(CombatPhase.WINDUP,a.phase(),"full charge holdsindefinitely untilrelease"); equal(0,a.cooldownRemaining(THRUST.id()),"holdingdoesnotstart cooldown");
        equal(2,a.receiveChargedParry(),"fullcharged incomingbreaks instead ofimmunity"); equal(CombatPhase.STUNNED,a.phase(),"fullchargeguardbreakinterrupts"); equal(200,a.cooldownRemaining(THRUST.id()),"guardbreak10secondscooldown");
        a=ready(null); a.beginChargedAttack(THRUST,null,true,true,16,200); tick(a,3);
        equal(1,a.receiveChargedParry(),"unfullchargeblocks incoming damage"); check(a.chargeSnapshot().fullyCharged(),"blockedhit immediatelyfillscharge"); check(a.chargedParried(),"parrybonuslatch set");
        equal(CombatPhase.WINDUP,a.phase(),"parrydoesnotreleasebeforeinput"); equal(2,a.receiveChargedParry(),"nextincomingatfullcharge breaks"); equal(200,a.cooldownRemaining(THRUST.id()),"secondhitguardbreakCD");
        a=ready(null); a.beginChargedAttack(THRUST,AttackAim.at(50,-45),true,true,16,200); tick(a,2);
        equal(1,a.receiveChargedParry(),"freshparrybefore release"); equal(ActionResult.ACCEPTED,a.releaseChargedAttack(),"releaseparriedthrust");
        equal(CombatPhase.ACTIVE,a.phase(),"release immediatelycommitsheldthrust"); check(a.chargedParried(),"adaptercanselect18damageparriedthrust"); equal(0,a.cooldownRemaining(THRUST.id()),"parriedrelease hasnoordinarycooldown");
        check(a.consumeChargeLunge(),"parriedrelease alsoissues6mpslungeintent"); check(!a.consumeChargeLunge(),"parriedlunge once");
        tick(a,4); equal(CombatPhase.READY,a.phase(),"parriedactiveandrecovery totalfour ticks");
        equal(ActionResult.ACCEPTED,a.beginChargedAttack(THRUST,null,true,true,16,200),"parried releaseallowsnextchargewithoutCD");
        a=ready(null); a.beginChargedAttack(THRUST,null,true,false,16,200); equal(0,a.receiveChargedParry(),"notunlocked parrycannotcancel damage");
    }
    private static void guiCancellation() {
        for(boolean hold:List.of(false,true)) {
            var a=ready(null);a.beginChargedAttack(THRUST,AttackAim.at(25,-30),hold,true,16,200);tick(a,3);
            check(a.cancelChargedPreparation(),"GUI cancels uncommitted thrust preparation");equal(CombatPhase.READY,a.phase(),"GUI cancellation returns to drawn READY");
            check(a.currentSkill()==null&&!a.chargeSnapshot().active()&&a.attackAim()==null,"GUI cancellation clears skill, holding and aim");
            equal(0,a.snapshot().phaseTicksRemaining(),"GUI cancellation clears phase countdown");equal(0,a.cooldownRemaining(THRUST.id()),"GUI cancellation cannot start thrust cooldown");
            check(!a.consumeChargeLunge(),"GUI cancellation cannot issue a lunge");a.releaseChargedAttack();tick(a,10);check(a.currentSkill()==null,"later release cannot resurrect a cancelled thrust");
            check(!a.cancelChargedPreparation(),"repeated GUI cancellation is harmless");
        }
        var a=ready(null);a.beginChargedAttack(THRUST,null,true,true,16,200);tick(a,20);check(a.cancelChargedPreparation(),"GUI also cancels fully charged but unreleased preparation");equal(0,a.cooldownRemaining(THRUST.id()),"full preparation still has no committed cooldown");
        a=ready(null);a.beginChargedAttack(THRUST,null,false,false,16,200);tick(a,7);check(!a.cancelChargedPreparation(),"GUI cannot cancel an already committed active contact");equal(CombatPhase.ACTIVE,a.phase(),"committed contact remains active");equal(20,a.cooldownRemaining(THRUST.id()),"GUI cannot clear committed cooldown");
        a=ready(null);a.requestAttack(SLASH);check(!a.cancelChargedPreparation(),"GUI charge cancellation cannot interrupt an unrelated slash");equal(SLASH,a.currentSkill(),"ordinary attack remains intact");
    }
    private static void forcedInterrupts() {
        for(boolean hold:List.of(false,true)) {
            CombatActor a=ready(null); a.beginChargedAttack(THRUST,null,hold,true,16,200); tick(a,3); a.forceSheathed();
            equal(CombatPhase.SHEATHED,a.phase(),"equipmentremoval/death forciblysheathe"); check(a.currentSkill()==null,"forcedinterrupt clearsdamagewindow"); check(!a.chargeSnapshot().active(),"forcedinterrupt clearsholding"); check(!a.consumeChargeLunge(),"forcedinterrupt preventsstalelunge"); equal(0,a.cooldownRemaining(THRUST.id()),"uncommittedforcedinterrupt leavesnoCD");
        }
        CombatActor a=ready(null); a.beginChargedAttack(THRUST,null,false,false,16,200); tick(a,7); a.forceSheathed();
        equal(CombatPhase.SHEATHED,a.phase(),"forced deathinterrupts evenactivecontact"); check(!a.consumeChargeLunge(),"unconsumedlungecleared ondeath"); equal(20,a.cooldownRemaining(THRUST.id()),"committedthrust cooldownstillretained");
    }
    private static void legacyDualRouting() {
        Map<String,SkillDefinition> skills=dualSkills(); CombatActor a=ready(style("legacy_dual",skills));
        equal(-1,a.leftSide(),"initialleftneutral"); equal(-1,a.rightSide(),"initialrightneutral");
        equal("dual_ll",a.requestStyledAttack(SkillInput.LEFT,skills).skillId(),"firstleftuseslefthandleft");
        equal(0,a.leftSide(),"leftendsatleft"); equal(-1,a.rightSide(),"unusedrightstillneutral"); tick(a,4);
        equal("dual_rl",a.requestStyledAttack(SkillInput.LEFT,skills).skillId(),"secondleftusesrighthandleft"); equal(0,a.rightSide(),"bothhandsnowleft"); tick(a,4);
        equal("dual_ll",a.requestStyledAttack(SkillInput.LEFT,skills).skillId(),"thirdleftrecoverslefthandwithoutdirectionexhaustion");tick(a,4);
        equal("dual_rr",a.requestStyledAttack(SkillInput.RIGHT,skills).skillId(),"nextrightmovesrighthandright"); tick(a,4);
        equal("dual_lr",a.requestStyledAttack(SkillInput.RIGHT,skills).skillId(),"secondrightmoveslefthandright"); tick(a,4);
        equal("dual_rr",a.requestStyledAttack(SkillInput.RIGHT,skills).skillId(),"thirdrightrecoversrighthandwithoutdirectionexhaustion");tick(a,4);
        equal(ActionResult.ACCEPTED,a.requestAttack(skills.get("dual_combined")),"namedlinkedcut accepted"); equal(-1,a.leftSide(),"linkedcut resetslefthand"); equal(-1,a.rightSide(),"linkedcut resetsrighthand");
        equal(100,a.cooldownRemaining("dual_combined"),"linkedcut5secondscooldown"); tick(a,4);
        equal("dual_ll",a.requestStyledAttack(SkillInput.LEFT,skills).skillId(),"linkedcut allowsfreshleft"); tick(a,4);
        tick(a,39); equal(0,a.leftSide(),"handstayspositionuntilfull2secondidle"); a.tick(); equal(-1,a.leftSide(),"two secondsidleleftneutral"); equal(-1,a.rightSide(),"two secondsidlerightneutral");
        a=ready(style("legacy_dual",skills)); a.requestStyledAttack(SkillInput.LEFT,skills);
        equal(ComboInputStatus.BUFFERED,a.requestStyledAttack(SkillInput.LEFT,skills).status(),"secondleftpreinputqueuesrighthand");
        equal(ComboInputStatus.ALREADY_QUEUED,a.requestStyledAttack(SkillInput.LEFT,skills).status(),"mashingcannotqueueextrathirdleft");
        tick(a,3); equal("dual_rl",a.currentSkill().id(),"secondhandstartsafteractivecontact");
        equal(ComboInputStatus.BUFFERED,a.requestStyledAttack(SkillInput.LEFT,skills).status(),"nextsame-directioncutcanbequeuedwithouttwocutlimit");
        tick(a,3);equal("dual_ll",a.currentSkill().id(),"thirdsame-directioncutstartsafterfullcontact");
    }
    private static void orderedDualCompatibility() {
        Map<String,SkillDefinition> skills=dualSkills(); CombatActor a=ready(style("dual",skills));
        for(int i=0;i<4;i++) {equal("dual_ll",a.requestStyledAttack(SkillInput.LEFT,skills).skillId(),"existingdualmodeleftalwayslefthand");tick(a,4);}
        for(int i=0;i<4;i++) {equal("dual_rr",a.requestStyledAttack(SkillInput.RIGHT,skills).skillId(),"existingdualmode rightalwaysrighthand");tick(a,4);}
        equal(-1,a.leftSide(),"existingmodeexportsnoneofnewhandpositionstate"); equal(-1,a.rightSide(),"existingmoderightpositionunaffected");
    }
    private static void legacyDualCadence() {
        var skills=dualSkills(3,6,1);var a=ready(style("legacy_dual",skills));
        cut(a,skills,SkillInput.LEFT,"dual_ll",9);
        cut(a,skills,SkillInput.LEFT,"dual_rl",9);
        cut(a,skills,SkillInput.RIGHT,"dual_rr",9);
        // Repeated use keeps the same authored cadence instead of imposing a hidden fatigue gate.
        cut(a,skills,SkillInput.LEFT,"dual_rl",9);
        cut(a,skills,SkillInput.RIGHT,"dual_rr",9);
        // Both hands end at the right after LR, then the left hand can alternate destinations.
        cut(a,skills,SkillInput.RIGHT,"dual_lr",9);
        cut(a,skills,SkillInput.LEFT,"dual_ll",9);
        cut(a,skills,SkillInput.RIGHT,"dual_lr",9);
        cut(a,skills,SkillInput.LEFT,"dual_ll",9);
        cut(a,skills,SkillInput.LEFT,"dual_rl",9);
    }
    private static void legacyDualBufferedCadence() {
        var skills=dualSkills(3,6,1);var a=ready(style("legacy_dual",skills));
        cut(a,skills,SkillInput.LEFT,"dual_ll",9);cut(a,skills,SkillInput.LEFT,"dual_rl",9);
        equal(ComboInputStatus.STARTED,a.requestStyledAttack(SkillInput.RIGHT,skills).status(),"second right-hand cut accepted");
        tick(a,3);equal(ComboInputStatus.BUFFERED,a.requestStyledAttack(SkillInput.LEFT,skills).status(),"third right-hand cut can be queued while contact is active");
        for(int i=0;i<4;i++)equal(ComboInputStatus.ALREADY_QUEUED,a.requestStyledAttack(SkillInput.LEFT,skills).status(),"repeated queued intents do not count as accepted cuts");
        tick(a,6);equal("dual_rl",a.currentSkill().id(),"third same-hand cut starts at the preceding nine-tick gate");
        equal(9,gate(a),"queued third same-hand cut retains nine authored contact ticks");
        tick(a,3);a.requestStyledAttack(SkillInput.RIGHT,skills);tick(a,5);
        equal("dual_rl",a.currentSkill().id(),"next queued cut cannot bypass any authored contact tick");
        a.tick();equal("dual_rr",a.currentSkill().id(),"next queued cut starts at the exact nine-tick boundary");equal(9,gate(a),"fourth accepted same-hand cut retains authored cadence");
    }
    private static void legacyDualCadenceResets() {
        var skills=dualSkills(3,6,1);var a=rightHandTwice(skills);
        equal(ActionResult.ACCEPTED,a.requestAttack(skills.get("dual_combined")),"linked slash accepted after same-hand cuts");equal(9,gate(a),"linked slash is not counted as a single hand");tick(a,10);
        equal(-1,a.leftSide(),"linked slash resets hand positions");cut(a,skills,SkillInput.RIGHT,"dual_rr",9);
        a=rightHandTwice(skills);tick(a,40);equal(-1,a.rightSide(),"idle resets hand position");cut(a,skills,SkillInput.RIGHT,"dual_rr",9);
        a=rightHandTwice(skills);a.setGuarding(true);cut(a,skills,SkillInput.LEFT,"dual_rl",9);
        a=rightHandTwice(skills);a.stun(1);a.tick();cut(a,skills,SkillInput.LEFT,"dual_rl",9);
        a=rightHandTwice(skills);a.forceSheathed();equal(-1,a.leftSide(),"forced sheath resets left hand");equal(-1,a.rightSide(),"forced sheath resets right hand");a.requestDrawn();tick(a,8);cut(a,skills,SkillInput.RIGHT,"dual_rr",9);
        a=rightHandTwice(skills);a.toggleDrawn();tick(a,a.rules().sheathTicks());a.requestDrawn();tick(a,8);cut(a,skills,SkillInput.RIGHT,"dual_rr",9);
        a=rightHandTwice(skills);a.setStyle(style("dual",skills));a.setStyle(style("legacy_dual",skills));
        // Same-ID configuration updates preserve counts; a genuinely different stance clears them.
        equal(9,acceptedGate(a,skills,SkillInput.LEFT),"same style id reload preserves cadence without a hidden streak penalty");
        a=rightHandTwice(skills);var oldStyle=a.style();
        a.setStyle(new WeaponStyleDefinition("dual_alt",oldStyle.label(),oldStyle.weaponGroup(),true,oldStyle.leftCombo(),oldStyle.rightSkill(),oldStyle.guard(),oldStyle.heavyGuard(),oldStyle.techniques(),oldStyle.cancels(),oldStyle.controls()));
        cut(a,skills,SkillInput.RIGHT,"dual_rr",9);
        a=ready(style("legacy_dual",skills));a.setResources(0,0);
        var expensive=skills.get("dual_ll");expensive=new SkillDefinition(expensive.id(),expensive.label(),expensive.weapon(),expensive.input(),3,6,1,0,1,0,4,4,90,0,true,true,false,"","legacy");
        equal(ActionResult.INSUFFICIENT_STAMINA,a.requestAttack(expensive),"rejected resource check does not accept a hand cut");a.setResources(100,100);cut(a,skills,SkillInput.LEFT,"dual_ll",9);
    }
    private static void legacyDualBodyAdjustment() {
        var skills=dualSkills(3,6,1);var a=ready(style("legacy_dual",skills));
        a.setLegacyDualIntervalAdjustment(base->Math.max(4,base-2));
        cut(a,skills,SkillInput.LEFT,"dual_ll",7);cut(a,skills,SkillInput.LEFT,"dual_rl",7);cut(a,skills,SkillInput.RIGHT,"dual_rr",7);cut(a,skills,SkillInput.LEFT,"dual_rl",7);
        a=ready(style("legacy_dual",skills));a.setLegacyDualIntervalAdjustment(base->Math.max(4,base-9));
        cut(a,skills,SkillInput.LEFT,"dual_ll",4);cut(a,skills,SkillInput.LEFT,"dual_rl",4);cut(a,skills,SkillInput.RIGHT,"dual_rr",4);cut(a,skills,SkillInput.LEFT,"dual_rl",4);
        var old=ready(style("dual",skills));old.setLegacyDualIntervalAdjustment(base->4);equal(9,acceptedGate(old,skills,SkillInput.LEFT),"old fixed-hand mode does not apply the legacy timing transform");
    }
    private static CombatActor rightHandTwice(Map<String,SkillDefinition> skills) {
        var a=ready(style("legacy_dual",skills));cut(a,skills,SkillInput.LEFT,"dual_ll",9);cut(a,skills,SkillInput.LEFT,"dual_rl",9);cut(a,skills,SkillInput.RIGHT,"dual_rr",9);return a;
    }
    private static int acceptedGate(CombatActor a,Map<String,SkillDefinition> skills,SkillInput input) {
        equal(ComboInputStatus.STARTED,a.requestStyledAttack(input,skills).status(),"fixture cut accepted");return gate(a);
    }
    private static int gate(CombatActor a) { return a.currentSkill().windupTicks()+a.currentSkill().activeTicks(); }
    private static void cut(CombatActor a,Map<String,SkillDefinition> skills,SkillInput input,String id,int gate) {
        equal(ComboInputStatus.STARTED,a.requestStyledAttack(input,skills).status(),"hand cut accepted: "+id);equal(id,a.currentSkill().id(),"position routing remains correct: "+id);equal(gate,gate(a),"accepted cadence: "+id);tick(a,gate+1);
    }
    private static Map<String,SkillDefinition> dualSkills() {
        return dualSkills(2,1,1);
    }
    private static Map<String,SkillDefinition> dualSkills(int w,int active,int recovery) {
        return Map.of("dual_ll",skill("dual_ll",SkillInput.LEFT,w,active,recovery,0,4,"dual_blade"),
                "dual_rl",skill("dual_rl",SkillInput.LEFT,w,active,recovery,0,4,"dual_blade"),
                "dual_rr",skill("dual_rr",SkillInput.RIGHT,w,active,recovery,0,5,"dual_blade"),
                "dual_lr",skill("dual_lr",SkillInput.RIGHT,w,active,recovery,0,4,"dual_blade"),
                "dual_combined",skill("dual_combined",SkillInput.SPECIAL,w,active,recovery,100,8,"dual_blade"));
    }
    private static WeaponStyleDefinition style(String mode,Map<String,SkillDefinition> skills) {
        WeaponStyleDefinition result=new WeaponStyleDefinition("dual_blade","Dual","dual_blade",true,
                List.of(new WeaponStyleDefinition.ComboStage("dual_ll")),"dual_rr",WeaponStyleDefinition.GuardRules.from(CombatRules.defaults()),
                null,WeaponStyleDefinition.Techniques.none(),WeaponStyleDefinition.CancelRules.defaults(),
                new WeaponControls(mode,"dual_ll","dual_rl","dual_rr","dual_lr","",.18)); result.validateSkills(skills);return result;
    }
    private static CombatActor ready(WeaponStyleDefinition style) {
        CombatActor a=new CombatActor(UUID.randomUUID(),CombatRules.defaults()); if(style!=null)equal(ActionResult.ACCEPTED,a.setStyle(style),"fixturestyle accepted"); a.requestDrawn();tick(a,8);return a;
    }
    private static SkillDefinition skill(String id,SkillInput input,int windup,int active,int recovery,int cd,double damage,String weapon) {
        return new SkillDefinition(id,id,weapon,input,windup,active,recovery,cd,0,0,damage,4,90,0,true,true,false,"","legacy");
    }
    private static void tick(CombatActor a,int count){for(int i=0;i<count;i++)a.tick();}
    private static void equal(Object expected,Object actual,String message){check(Objects.equals(expected,actual),message+" expected="+expected+" actual="+actual);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
