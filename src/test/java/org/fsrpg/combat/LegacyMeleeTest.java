package org.fsrpg.combat;

import java.util.*;

/** Legacy charged-thrust commitment plus directional hand availability using the original four clips. */
public final class LegacyMeleeTest {
    private static int checks;
    private static final SkillDefinition THRUST = skill("sword_thrust", SkillInput.RIGHT, 7,2,2,20,9,"sword");
    private static final SkillDefinition SLASH = skill("sword_left", SkillInput.LEFT, 2,1,1,0,6,"sword");
    public static void main(String[] args) {
        normalCommit(); cancellation(); guiCancellation(); holdAndParry(); forcedInterrupts(); legacyDualRouting();
        legacyDualCadence(); legacyDualBufferedCadence(); legacyDualCadenceResets(); legacyDualBodyAdjustment(); orderedDualCompatibility();
        legacyDualExhaustionAndReturn();
        legacyDualClashReturn();
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
        equal(0,a.leftSide(),"leftendsatleft"); equal(-1,a.rightSide(),"unusedrightstillneutral"); tick(a,3);
        equal("dual_rl",a.requestStyledAttack(SkillInput.LEFT,skills).skillId(),"secondleftusesrighthandleft"); equal(0,a.rightSide(),"bothhandsnowleft"); tick(a,3);
        equal(ActionResult.DIRECTION_EXHAUSTED,a.requestStyledAttack(SkillInput.LEFT,skills).rejection(),"third left waits for a real return");
        equal("dual_rr",a.requestStyledAttack(SkillInput.RIGHT,skills).skillId(),"nextrightmovesrighthandright"); tick(a,3);
        equal("dual_lr",a.requestStyledAttack(SkillInput.RIGHT,skills).skillId(),"secondrightmoveslefthandright"); tick(a,3);
        equal(ActionResult.DIRECTION_EXHAUSTED,a.requestStyledAttack(SkillInput.RIGHT,skills).rejection(),"third right also waits for a real return");
        equal(ActionResult.ACCEPTED,a.requestAttack(skills.get("dual_combined")),"namedlinkedcut accepted"); equal(-1,a.leftSide(),"linkedcut resetslefthand"); equal(-1,a.rightSide(),"linkedcut resetsrighthand");
        equal(100,a.cooldownRemaining("dual_combined"),"linkedcut5secondscooldown"); tick(a,4);
        equal("dual_ll",a.requestStyledAttack(SkillInput.LEFT,skills).skillId(),"linkedcut allowsfreshleft"); tick(a,3);
        equal(0,a.leftSide(),"hand stays on its side throughout recovery");a.tick();
        equal(-1,a.leftSide(),"complete natural recovery restores default left carry"); equal(-1,a.rightSide(),"complete natural recovery restores default right carry");
        a=ready(style("legacy_dual",skills)); a.requestStyledAttack(SkillInput.LEFT,skills);
        equal(ComboInputStatus.BUFFERED,a.requestStyledAttack(SkillInput.LEFT,skills).status(),"secondleftpreinputqueuesrighthand");
        equal(ComboInputStatus.ALREADY_QUEUED,a.requestStyledAttack(SkillInput.LEFT,skills).status(),"mashingcannotqueueextrathirdleft");
        tick(a,3); equal("dual_rl",a.currentSkill().id(),"secondhandstartsafteractivecontact");
        equal(ActionResult.DIRECTION_EXHAUSTED,a.requestStyledAttack(SkillInput.LEFT,skills).rejection(),"third same-direction cut cannot enter the windup buffer");
        tick(a,3);equal("dual_rl",a.currentSkill().id(),"second cut completes its contact without a hidden third cut");
        a.tick();equal(CombatPhase.READY,a.phase(),"exhausted pair returns naturally to ready");
        equal("dual_ll",a.requestStyledAttack(SkillInput.LEFT,skills).skillId(),"fresh left is restored after complete return");
    }
    private static void orderedDualCompatibility() {
        Map<String,SkillDefinition> skills=dualSkills(); CombatActor a=ready(style("dual",skills));
        for(int i=0;i<4;i++) {equal("dual_ll",a.requestStyledAttack(SkillInput.LEFT,skills).skillId(),"existingdualmodeleftalwayslefthand");tick(a,4);}
        for(int i=0;i<4;i++) {equal("dual_rr",a.requestStyledAttack(SkillInput.RIGHT,skills).skillId(),"existingdualmode rightalwaysrighthand");tick(a,4);}
        equal(-1,a.leftSide(),"existingmodeexportsnoneofnewhandpositionstate"); equal(-1,a.rightSide(),"existingmoderightpositionunaffected");
    }
    private static void legacyDualExhaustionAndReturn() {
        var skills=dualSkills(2,1,3);
        for(var direction:List.of(SkillInput.LEFT,SkillInput.RIGHT)) {
            String first=direction==SkillInput.LEFT?"dual_ll":"dual_rr";
            String second=direction==SkillInput.LEFT?"dual_rl":"dual_lr";
            for(int elapsed=0;elapsed<skills.get(second).durationTicks();elapsed++) {
                var a=ready(style("legacy_dual",skills));
                a.requestStyledAttack(direction,skills);tick(a,3);
                equal(ComboInputStatus.STARTED,a.requestStyledAttack(direction,skills).status(),"second hand can chain before full return");
                equal(second,a.currentSkill().id(),"second cut uses the other available hand");tick(a,elapsed);
                a.drainStartedSkills();a.perfectGuardSucceeded();
                int counter=a.counterRemainingTicks();double stamina=a.stamina(),spirit=a.spirit();long sequence=a.snapshot().swingSequence();
                var rejected=a.requestStyledAttack(direction,skills);
                equal(ComboInputStatus.REJECTED,rejected.status(),"third direction rejects in every second-cut phase at "+elapsed);
                equal(ActionResult.DIRECTION_EXHAUSTED,rejected.rejection(),"both hand destinations are exhausted");
                equal(ActionResult.DIRECTION_EXHAUSTED,a.aimedLeftSkill(skills.get(first),null),"named physical left input cannot bypass hand occupancy");
                equal(stamina,a.stamina(),"exhaustion rejection spends no stamina");equal(spirit,a.spirit(),"exhaustion rejection spends no spirit");
                equal(counter,a.counterRemainingTicks(),"exhaustion rejection preserves the counter opening");
                equal(sequence,a.snapshot().swingSequence(),"exhaustion rejection cannot issue a new swing");
                check(a.drainStartedSkills().isEmpty()&&!a.comboSnapshot().queued(),"rejection cannot issue a quest event or queue a third cut");
                tick(a,skills.get(second).durationTicks()-elapsed);
                equal(CombatPhase.READY,a.phase(),"entire recovery completes before default carry");
                equal(-1,a.leftSide(),"natural return restores left neutral");equal(-1,a.rightSide(),"natural return restores right neutral");
                equal(first,a.requestStyledAttack(direction,skills).skillId(),"new click can cut again after complete return");
            }
        }
        var a=ready(style("legacy_dual",skills));
        a.requestStyledAttack(SkillInput.LEFT,skills);tick(a,3);a.requestStyledAttack(SkillInput.LEFT,skills);tick(a,3);
        equal(ComboInputStatus.STARTED,a.requestStyledAttack(SkillInput.RIGHT,skills).status(),"opposite cut moves a real hand back across the body");
        equal("dual_rr",a.currentSkill().id(),"right hand moves to the right");
        equal(ComboInputStatus.BUFFERED,a.requestStyledAttack(SkillInput.LEFT,skills).status(),"moved hand can queue a left cut again");
        equal("dual_rl",a.comboSnapshot().queuedSkillId(),"queued reversal belongs to the hand that actually moved");
        tick(a,3);equal("dual_rl",a.currentSkill().id(),"buffered reversal starts after every contact tick");
        equal(ComboInputStatus.BUFFERED,a.requestStyledAttack(SkillInput.RIGHT,skills).status(),"valid opposite follow-up occupies the one slot");
        equal(ActionResult.DIRECTION_EXHAUSTED,a.requestStyledAttack(SkillInput.LEFT,skills).rejection(),"third left cannot displace an already valid opposite follow-up");
        equal("dual_rr",a.comboSnapshot().queuedSkillId(),"rejected input preserves the previous valid slot");

        for(boolean precision:List.of(false,true)) {
            a=ready(style("legacy_dual",skills));a.requestStyledAttack(SkillInput.LEFT,skills);tick(a,3);a.requestStyledAttack(SkillInput.LEFT,skills);
            equal(ActionResult.ACCEPTED,precision?a.requestPrecisionGuard(false):a.setGuarding(true),"guard can interrupt second preparation");
            for(int press=0;press<4;press++) {
                a.setGuarding(false);a.setGuarding(true);a.setGuarding(false);
                equal(ActionResult.DIRECTION_EXHAUSTED,a.requestStyledAttack(SkillInput.LEFT,skills).rejection(),"repeated guard edges cannot wash occupied hands");
            }
            tick(a,39);equal(0,a.leftSide(),"interrupted hand occupancy survives until the existing idle reset");
            a.tick();equal(-1,a.leftSide(),"existing forty-tick idle fallback recovers interrupted hands");
        }
    }
    private static void legacyDualClashReturn() {
        var skills=dualSkills(2,1,3);
        for(boolean sequenceApi:List.of(false,true)) {
            var a=exhaustedLeft(skills);long sequence=a.snapshot().swingSequence();
            equal(ActionResult.DIRECTION_EXHAUSTED,a.requestStyledAttack(SkillInput.LEFT,skills).rejection(),"two left cuts exhaust both hands before the real clash");
            equal(ComboInputStatus.BUFFERED,a.requestStyledAttack(SkillInput.RIGHT,skills).status(),"real clash also owns a possible pending reversal");
            a.drainStartedSkills();
            if(sequenceApi)check(a.finishClash(sequence),"matching real-contact sequence finishes the exhausted second cut");else a.finishClash();
            equal(CombatPhase.READY,a.phase(),"real clash finishes directly at the rendered default carry");
            check(a.currentSkill()==null&&!a.comboSnapshot().queued(),"real clash clears its current and queued cuts");
            equal(-1,a.leftSide(),"real clash returns left hand to default carry");equal(-1,a.rightSide(),"real clash returns right hand to default carry");
            a.perfectGuardSucceeded();equal(4,a.counterRemainingTicks(),"advantage clash grants its normal short counter opening");
            var followUp=a.requestStyledAttack(SkillInput.LEFT,skills);
            equal(ComboInputStatus.STARTED,followUp.status(),"a new same-direction left can use the real returned hands immediately");
            equal("dual_ll",a.currentSkill().id(),"post-clash left starts from the default left-hand clip");
            equal(1.5,a.attackDamageMultiplier(),"post-clash physical left consumes the advantage multiplier");
            equal(0,a.counterRemainingTicks(),"post-clash counter is consumed once at acceptance");
            equal(sequence+1,a.snapshot().swingSequence(),"post-clash input accepts exactly one new cut");
            equal(1,a.drainStartedSkills().size(),"clash return itself emits no extra attack event");
        }
        var a=exhaustedLeft(skills);long oldSequence=a.snapshot().swingSequence();check(a.finishClash(oldSequence),"fixture closes the first real clash");
        a.requestStyledAttack(SkillInput.LEFT,skills);tick(a,3);a.requestStyledAttack(SkillInput.LEFT,skills);tick(a,2);
        long newSequence=a.snapshot().swingSequence();var current=a.currentSkill();a.perfectGuardSucceeded();
        check(!a.finishClash(oldSequence),"old clash sequence cannot end a later accepted cut");
        equal(newSequence,a.snapshot().swingSequence(),"old clash cannot change the new swing sequence");equal(current,a.currentSkill(),"old clash cannot clear the new active cut");
        equal(0,a.leftSide(),"old clash cannot return a new occupied left hand");equal(0,a.rightSide(),"old clash cannot return a new occupied right hand");
        equal(4,a.counterRemainingTicks(),"old clash cannot clear a later earned counter opening");
        equal(ActionResult.DIRECTION_EXHAUSTED,a.requestStyledAttack(SkillInput.LEFT,skills).rejection(),"old clash cannot wash the new pair's third-left restriction");
        for(boolean stunned:List.of(false,true)) {
            a=exhaustedLeft(skills);
            if(stunned)a.stun(1);else{a.requestPrecisionGuard(false);a.setGuarding(false);}
            var before=a.phase();a.perfectGuardSucceeded();a.finishClash();
            equal(before,a.phase(),"an empty no-argument clash cannot invent a state transition after an ordinary interrupt");
            equal(0,a.leftSide(),"an empty no-argument clash cannot wash the interrupted left hand");equal(0,a.rightSide(),"an empty no-argument clash cannot wash the interrupted right hand");
            equal(4,a.counterRemainingTicks(),"an empty no-argument clash cannot clear a later reward");
            if(stunned)a.tick();
            equal(ActionResult.DIRECTION_EXHAUSTED,a.requestStyledAttack(SkillInput.LEFT,skills).rejection(),"ordinary guard or stun still requires a real hand return");
        }
    }
    private static CombatActor exhaustedLeft(Map<String,SkillDefinition> skills) {
        var a=ready(style("legacy_dual",skills));a.requestStyledAttack(SkillInput.LEFT,skills);tick(a,3);
        a.requestStyledAttack(SkillInput.LEFT,skills);tick(a,2);return a;
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
        a=rightHandTwice(skills);a.tick();a.toggleDrawn();tick(a,a.rules().sheathTicks());a.requestDrawn();tick(a,8);cut(a,skills,SkillInput.RIGHT,"dual_rr",9);
        a=rightHandTwice(skills);a.setGuarding(true);a.setGuarding(false);a.setStyle(style("dual",skills));a.setStyle(style("legacy_dual",skills));
        // Same-ID configuration updates preserve counts; a genuinely different stance clears them.
        equal(9,acceptedGate(a,skills,SkillInput.LEFT),"same style id reload preserves cadence without a hidden streak penalty");
        a=rightHandTwice(skills);a.tick();var oldStyle=a.style();
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
        equal(ComboInputStatus.STARTED,a.requestStyledAttack(input,skills).status(),"hand cut accepted: "+id);equal(id,a.currentSkill().id(),"position routing remains correct: "+id);equal(gate,gate(a),"accepted cadence: "+id);tick(a,gate);
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
