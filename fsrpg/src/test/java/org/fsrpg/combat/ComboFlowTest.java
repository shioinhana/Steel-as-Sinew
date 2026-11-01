package org.fsrpg.combat;

import java.util.*;
import java.nio.file.*;
import com.google.gson.*;
import org.fsrpg.config.RpgConfig;

/** Button intent is forgiving; costs, complete collision frames and validated defensive interrupts are strict. */
public final class ComboFlowTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var definitions=WeaponControlsTest.definitions();
        everyInputInstant(definitions);
        relaxedContinuation(definitions);
        fiveStageContinuousBuffer(definitions);
        directionalAvailability(definitions);
        singleSlotAndResources(definitions);
        fullRecoveryQueue(definitions);
        clashSequenceOwnership(definitions);
        precisionInterrupt(definitions);
        settings(definitions);
        configCompatibility();
        System.out.println("ComboFlowTest passed: "+checks+" checks");
    }

    private static CombatActor actor(RpgConfig.Definitions d,String style) {
        var rules=new CombatRules(1000,100,0,0,72000,0,0,120,.25,10,2,10,4,10,2,120,
                12,0,6,20,6,3,true,false);
        var actor=new CombatActor(UUID.randomUUID(),rules);
        equal(ActionResult.ACCEPTED,actor.setStyle(d.styles().get(style)),"fixture style");
        actor.requestDrawn();
        return actor;
    }

    private static void everyInputInstant(RpgConfig.Definitions d) {
        for(String style:List.of("rapier","dual_blade")) {
            String first=style.equals("rapier")?"sword_left":"dual_ll";
            String second=style.equals("rapier")?"rapier_downcut":"dual_rl";
            var initial=d.skills().get(first);
            int contact=initial.windupTicks()+initial.activeTicks();
            for(int at=0;at<initial.durationTicks();at++) {
                var actor=actor(d,style);
                actor.requestStyledAttack(SkillInput.LEFT,d.skills());actor.drainStartedSkills();ticks(actor,at);
                double before=actor.stamina();long sequence=actor.snapshot().swingSequence();
                var result=actor.requestStyledAttack(SkillInput.LEFT,d.skills());
                check(result.accepted(),"all attack instants accept a next click: "+style+" @"+at);
                if(at<contact) {
                    equal(ComboInputStatus.BUFFERED,result.status(),"early click is retained");
                    near(before,actor.stamina(),"buffering is free");
                    int remainingActive=0;
                    while(actor.snapshot().swingSequence()==sequence) {
                        if(actor.phase()==CombatPhase.ACTIVE)remainingActive++;
                        actor.tick();
                    }
                    equal(Math.min(initial.activeTicks(),contact-at),remainingActive,"all remaining contact frames survive");
                } else check(result.started(),"recovery click immediately starts next cut");
                equal(second,actor.currentSkill().id(),"one follow-up selects the next authored clip");
                equal(0,actor.snapshot().skillElapsedTicks(),"full next windup");
                equal(1,actor.drainStartedSkills().size(),"one cost/quest start event");
                near(before-d.skills().get(second).staminaCost()+(at<contact?initial.staminaCost()*.5:0),actor.stamina(),"one exact follow-up reservation after emitted miss settlement");
                ticks(actor,actor.currentSkill().durationTicks()+20);
                equal(sequence+1,actor.snapshot().swingSequence(),"no unintended third attack after one early click");
            }
        }
    }

    private static void relaxedContinuation(RpgConfig.Definitions d) {
        for(int pause=0;pause<=14;pause++) {
            var actor=actor(d,"rapier");actor.requestStyledAttack(SkillInput.LEFT,d.skills());
            ticks(actor,actor.currentSkill().durationTicks());
            equal(CombatPhase.READY,actor.phase(),"normal recovery completes");ticks(actor,pause);
            check(actor.requestStyledAttack(SkillInput.LEFT,d.skills()).started(),"READY click always starts");
            equal(pause<12?"rapier_downcut":"sword_left",actor.currentSkill().id(),"wide idle continuation with exact reset boundary "+pause);
        }
        var actor=actor(d,"rapier");
        var sequence=List.of("sword_left","rapier_downcut","rapier_crosscut","rapier_light_thrust","rapier_lunge_finish","sword_left","rapier_downcut");
        for(String expected:sequence) {
            check(actor.requestStyledAttack(SkillInput.LEFT,d.skills()).started(),"single click after a short pause accepted");
            equal(expected,actor.currentSkill().id(),"last stage loops automatically");
            ticks(actor,actor.currentSkill().durationTicks()+5);
        }
    }

    private static void fiveStageContinuousBuffer(RpgConfig.Definitions d) {
        var ids=List.of("sword_left","rapier_downcut","rapier_crosscut","rapier_light_thrust","rapier_lunge_finish");
        equal(ids,d.styles().get("rapier").leftCombo().stream().map(WeaponStyleDefinition.ComboStage::skillId).toList(),"five stable configured stages");
        var actor=actor(d,"rapier");
        check(actor.requestStyledAttack(SkillInput.LEFT,d.skills()).started(),"first fencing thrust starts");
        actor.drainStartedSkills();
        for(int stage=0;stage<ids.size();stage++) {
            var current=actor.currentSkill();
            equal(ids.get(stage),current.id(),"authored five-stage order");
            equal(stage==4?4:3,current.windupTicks(),"full authored preparation remains intact");
            equal(3,current.activeTicks(),"all fencing contact frames remain intact");
            equal(stage==4?5:3,current.recoveryTicks(),"natural return keeps its authored recovery");
            double paid=actor.stamina();long sequence=actor.snapshot().swingSequence();
            equal(ComboInputStatus.BUFFERED,actor.requestStyledAttack(SkillInput.LEFT,d.skills()).status(),"any early click queues the next fencing stage");
            for(int mash=0;mash<6;mash++)equal(ComboInputStatus.ALREADY_QUEUED,actor.requestStyledAttack(SkillInput.LEFT,d.skills()).status(),"mashing keeps one next-stage slot");
            near(paid,actor.stamina(),"pre-input charges no fee");
            ticks(actor,current.windupTicks()+current.activeTicks()-1);
            equal(sequence,actor.snapshot().swingSequence(),"next stage cannot truncate the final contact tick");
            equal(CombatPhase.ACTIVE,actor.phase(),"final collision frame stays active");
            actor.tick();
            equal(sequence+1,actor.snapshot().swingSequence(),"buffer starts exactly one next stage");
            equal(ids.get((stage+1)%ids.size()),actor.currentSkill().id(),"stage five loops directly into the first thrust");
            equal(CombatPhase.WINDUP,actor.phase(),"continuous chain never exposes a default READY pose");
            equal(0,actor.snapshot().skillElapsedTicks(),"each next stage gets its complete windup");
            equal(1,actor.drainStartedSkills().size(),"one skill/cost event per actual follow-up");
        }
    }

    private static void directionalAvailability(RpgConfig.Definitions d) {
        for(var input:List.of(SkillInput.LEFT,SkillInput.RIGHT)) {
            var actor=actor(d,"dual_blade");
            String first=input==SkillInput.LEFT?"dual_ll":"dual_rr",second=input==SkillInput.LEFT?"dual_rl":"dual_lr";
            double before=actor.stamina();
            check(actor.requestStyledAttack(input,d.skills()).started(),"first direction uses an available hand");
            equal(first,actor.currentSkill().id(),"first cut uses its original hand");near(before-8,actor.stamina(),"first actual cut costs eight");
            equal(ComboInputStatus.BUFFERED,actor.requestStyledAttack(input,d.skills()).status(),"second hand can be buffered from the first windup");
            equal(9,actor.currentSkill().windupTicks()+actor.currentSkill().activeTicks(),"authored cadence has no new timing penalty");
            ticks(actor,9);equal(second,actor.currentSkill().id(),"second accepted cut uses the remaining available hand");
            actor.drainStartedSkills();
            int duration=actor.currentSkill().durationTicks();
            for(int elapsed=0;elapsed<duration;elapsed++) {
                double stamina=actor.stamina(),spirit=actor.spirit();long sequence=actor.snapshot().swingSequence();
                int counter=actor.counterRemainingTicks();
                var rejected=actor.requestStyledAttack(input,d.skills());
                equal(ActionResult.DIRECTION_EXHAUSTED,rejected.rejection(),"third cut rejects through windup, active and full recovery "+elapsed);
                near(stamina,actor.stamina(),"blocked third cut spends no stamina");near(spirit,actor.spirit(),"blocked third cut spends no spirit");
                equal(counter,actor.counterRemainingTicks(),"blocked third cut does not invent or consume a counter");
                equal(sequence,actor.snapshot().swingSequence(),"blocked third cut issues no extra attack");
                check(actor.drainStartedSkills().isEmpty()&&!actor.comboSnapshot().queued(),"blocked third cut never enters the next slot");
                actor.tick();
            }
            equal(CombatPhase.READY,actor.phase(),"full authored recovery returns to READY");
            equal(-1,actor.leftSide(),"full return restores default left carry");equal(-1,actor.rightSide(),"full return restores default right carry");
            ticks(actor,20);equal(2L,actor.snapshot().swingSequence(),"rejected clicks never replay automatically after return");
            check(actor.requestStyledAttack(input,d.skills()).started(),"a new click starts after complete return");
            equal(first,actor.currentSkill().id(),"new sequence starts from default hand availability");
        }
        var actor=actor(d,"dual_blade");actor.requestStyledAttack(SkillInput.LEFT,d.skills());
        equal(ComboInputStatus.BUFFERED,actor.requestStyledAttack(SkillInput.RIGHT,d.skills()).status(),"direction reversal during windup queues");
        ticks(actor,9);equal("dual_rr",actor.currentSkill().id(),"reversal starts after current complete contact");
        ticks(actor,actor.currentSkill().durationTicks());
        check(actor.aimedSkill(d.skills().get("dual_combined"),null)==ActionResult.ACCEPTED,"linked cut keeps its stable skill ID and availability");
        equal(-1,actor.leftSide(),"linked cut recovers left hand");equal(-1,actor.rightSide(),"linked cut recovers right hand");
        equal(100,actor.cooldownRemaining("dual_combined"),"linked cut retains its configured cooldown");
    }

    private static void singleSlotAndResources(RpgConfig.Definitions d) {
        var actor=actor(d,"rapier");actor.setResources(5,100);actor.requestStyledAttack(SkillInput.LEFT,d.skills());
        actor.drainStartedSkills();
        for(int i=0;i<20;i++)check(actor.requestStyledAttack(SkillInput.LEFT,d.skills()).accepted(),"mashing accepts only a single pending intent");
        ticks(actor,7);
        equal(1L,actor.snapshot().swingSequence(),"unaffordable buffered attack never starts");
        near(2.5,actor.stamina(),"failed follow-up keeps the prior miss refund and charges nothing extra");
        equal("INSUFFICIENT_STAMINA",actor.comboSnapshot().lastRejection(),"execution-time rejection is visible");
        check(actor.drainStartedSkills().isEmpty(),"rejection cannot count a skill start");
        actor.restoreResources(5,0);
        check(actor.requestStyledAttack(SkillInput.LEFT,d.skills()).started(),"resource restoration permits immediate retry");
        ticks(actor,actor.currentSkill().durationTicks()+30);
        equal(2L,actor.snapshot().swingSequence(),"mashing did not create a hidden list");
        actor=actor(d,"rapier");actor.requestStyledAttack(SkillInput.LEFT,d.skills());
        actor.requestStyledAttack(SkillInput.LEFT,d.skills());actor.drainStartedSkills();
        check(actor.setGuarding(true)==ActionResult.ACCEPTED,"normal windup defense still cancels");
        check(!actor.comboSnapshot().queued(),"defense clears pending input");actor.setGuarding(false);ticks(actor,30);
        check(actor.drainStartedSkills().isEmpty(),"cancelled input never resurfaces");
        actor.requestStyledAttack(SkillInput.LEFT,d.skills());equal("sword_left",actor.currentSkill().id(),"guard clears the continuation memory");
    }

    private static void fullRecoveryQueue(RpgConfig.Definitions d) {
        var actor=actor(d,"rapier");var s=actor.style();
        actor.setStyle(new WeaponStyleDefinition(s.id(),s.label(),s.weaponGroup(),true,s.leftCombo(),s.rightSkill(),s.guard(),s.heavyGuard(),s.techniques(),
                new WeaponStyleDefinition.CancelRules(false,false,false,false,false,false),s.controls()));
        actor.requestStyledAttack(SkillInput.LEFT,d.skills());
        equal(ComboInputStatus.BUFFERED,actor.requestStyledAttack(SkillInput.LEFT,d.skills()).status(),"custom recovery lock retains an early click");
        ticks(actor,d.skills().get("sword_left").durationTicks()-1);equal("sword_left",actor.currentSkill().id(),"custom noncancelable recovery is retained");
        equal(CombatPhase.RECOVERY,actor.phase(),"full recovery phase still runs");
        actor.tick();equal("rapier_downcut",actor.currentSkill().id(),"pending input runs at full completion without a new timing test");
        actor=actor(d,"dual_blade");s=actor.style();
        actor.setStyle(new WeaponStyleDefinition(s.id(),s.label(),s.weaponGroup(),true,s.leftCombo(),s.rightSkill(),s.guard(),s.heavyGuard(),s.techniques(),
                new WeaponStyleDefinition.CancelRules(false,false,false,false,false,false),s.controls()));
        actor.requestStyledAttack(SkillInput.LEFT,d.skills());actor.requestStyledAttack(SkillInput.LEFT,d.skills());
        ticks(actor,d.skills().get("dual_ll").durationTicks());
        equal("dual_rl",actor.currentSkill().id(),"dual buffered follow-up survives a custom full recovery");
        equal(0,actor.leftSide(),"buffer execution does not manufacture a default carry reset at completion");
        equal(0,actor.rightSide(),"buffered second cut occupies the remaining hand");
        equal(ActionResult.DIRECTION_EXHAUSTED,actor.requestStyledAttack(SkillInput.LEFT,d.skills()).rejection(),"custom recovery lock cannot buffer a third left cut");
        ticks(actor,actor.currentSkill().durationTicks());
        equal(-1,actor.leftSide(),"uninterrupted final recovery finally restores default carry");
    }

    private static void clashSequenceOwnership(RpgConfig.Definitions d) {
        var actor=actor(d,"rapier");actor.requestStyledAttack(SkillInput.LEFT,d.skills());
        actor.requestStyledAttack(SkillInput.LEFT,d.skills());ticks(actor,d.skills().get("sword_left").windupTicks());
        long old=actor.snapshot().swingSequence();double paid=actor.stamina();int cooldown=actor.cooldownRemaining("sword_left");
        check(actor.finishClash(old),"matching clash contact ends its own committed swing");
        near(paid,actor.stamina(),"clash keeps the paid attack cost and charges no ordinary guard fee");
        equal(cooldown,actor.cooldownRemaining("sword_left"),"clash retains the existing cooldown");
        equal(CombatPhase.READY,actor.phase(),"clash returns to drawn default state");
        check(actor.currentSkill()==null&&!actor.comboSnapshot().queued(),"clash cancels both the old contact and its queued follow-up");
        actor.perfectGuardSucceeded();int counter=actor.counterRemainingTicks();
        check(!actor.finishClash(old),"already finished contact cannot finish twice");
        equal(counter,actor.counterRemainingTicks(),"duplicate contact cannot consume the new reward");
        actor.requestStyledAttack(SkillInput.LEFT,d.skills());
        long fresh=actor.snapshot().swingSequence();double multiplier=actor.attackDamageMultiplier();paid=actor.stamina();
        check(fresh!=old&&!actor.finishClash(old),"delayed previous contact cannot cancel a newer swing");
        equal(fresh,actor.snapshot().swingSequence(),"stale contact preserves the new swing sequence");
        equal("sword_left",actor.currentSkill().id(),"stale contact preserves the new active preparation");
        near(multiplier,actor.attackDamageMultiplier(),"stale contact preserves the accepted counter multiplier");
        near(paid,actor.stamina(),"stale contact cannot change resources");
        check(actor.finishClash(fresh),"matching preparation may resolve a near-contact clash");
        near(paid,actor.stamina(),"uncommitted preparation keeps its original full reservation");
    }

    private static void precisionInterrupt(RpgConfig.Definitions d) {
        var expensive=d.skills().get("sword_left");
        var catalog=new HashMap<>(d.skills());
        catalog.put(expensive.id(),new SkillDefinition(expensive.id(),expensive.label(),expensive.weapon(),expensive.input(),
                expensive.windupTicks(),expensive.activeTicks(),expensive.recoveryTicks(),40,expensive.staminaCost(),expensive.spiritCost(),
                expensive.damage(),expensive.reach(),expensive.arcDegrees(),expensive.knockback(),expensive.blockable(),expensive.parryable(),
                expensive.clashable(),expensive.model(),expensive.action()));
        for(int at=0;at<expensive.durationTicks();at++) {
            var actor=actor(d,"rapier");actor.requestStyledAttack(SkillInput.LEFT,catalog);
            actor.drainStartedSkills();ticks(actor,at);
            if(actor.phase()!=CombatPhase.RECOVERY)actor.requestStyledAttack(SkillInput.LEFT,catalog);
            var victim=UUID.randomUUID();boolean committed=actor.claimVictim(victim);
            if(committed)actor.markAttackContact(actor.snapshot().swingSequence());
            long sequence=actor.snapshot().swingSequence();double cost=actor.stamina();int cooldown=actor.cooldownRemaining(expensive.id());
            equal(ActionResult.ACCEPTED,actor.requestPrecisionGuard(false),"validated precision defense has priority in every attack phase "+at);
            equal(CombatPhase.GUARDING,actor.phase(),"precision request enters actual guarding");
            check(actor.guardHeld()&&!actor.comboSnapshot().queued()&&actor.currentSkill()==null,"precision request clears current and pending damage");
            near(cost,actor.stamina(),"cancellation cannot refund attack cost");equal(cooldown,actor.cooldownRemaining(expensive.id()),"cancellation cannot refund cooldown");
            check(!actor.claimVictim(victim),"cancelled contact cannot inflict another hit, previous hit="+committed);
            actor.setGuarding(false);ticks(actor,20);
            equal(sequence,actor.snapshot().swingSequence(),"precision cancellation cannot restart the queued cut");
            check(actor.drainStartedSkills().isEmpty(),"precision cancellation emits no hidden skill starts");
        }
        var actor=actor(d,"rapier");actor.perfectGuardSucceeded();int before=actor.counterRemainingTicks();
        actor.requestPrecisionGuard(false);equal(before,actor.counterRemainingTicks(),"guard selection itself never manufactures or consumes a counter chance");
        actor=actor(d,"rapier");actor.requestDodge(true);equal(ActionResult.BUSY,actor.requestPrecisionGuard(false),"precision request cannot skip dodge state");
        actor=actor(d,"rapier");actor.stun(3);equal(ActionResult.BUSY,actor.requestPrecisionGuard(false),"precision request cannot skip authoritative stun");
        actor=actor(d,"rapier");actor.queueHitStun(2);equal(ActionResult.BUSY,actor.requestPrecisionGuard(false),"pending hit reaction cannot be bypassed");
        actor=actor(d,"rapier");actor.forceSheathed();equal(ActionResult.NOT_DRAWN,actor.requestPrecisionGuard(false),"precision request cannot draw or guard without a weapon stance");
    }

    private static void settings(RpgConfig.Definitions d) {
        equal(12,WeaponControls.defaults().comboResetTicks(),"old styles receive the wide continuation default");
        var original=d.styles().get("rapier");var actor=actor(d,"rapier");
        var controls=new WeaponControls("ordered","","","","","",.18,0);
        actor.setStyle(new WeaponStyleDefinition(original.id(),original.label(),original.weaponGroup(),true,original.leftCombo(),original.rightSkill(),
                original.guard(),original.heavyGuard(),original.techniques(),original.cancels(),controls));
        actor.requestStyledAttack(SkillInput.LEFT,d.skills());ticks(actor,actor.currentSkill().durationTicks());
        actor.requestStyledAttack(SkillInput.LEFT,d.skills());equal("sword_left",actor.currentSkill().id(),"zero explicitly disables idle continuation");
        try {new WeaponControls("ordered","","","","","",.18,-1);throw new AssertionError("negative combo reset accepted");}
        catch(IllegalArgumentException expected){checks++;}
    }

    private static void configCompatibility() throws Exception {
        var files=new ArrayList<JsonElement>();
        for(String name:List.of("combat","skills","quests","weapons","styles"))
            files.add(JsonParser.parseString(Files.readString(Path.of("src/main/resources/defaults/"+name+".json"))));
        var style=files.get(4).getAsJsonArray().asList().stream().map(JsonElement::getAsJsonObject)
                .filter(value->value.get("id").getAsString().equals("dual_blade")).findFirst().orElseThrow();
        var controls=style.getAsJsonObject("controls");controls.remove("comboResetTicks");
        equal(12,parse(files).styles().get("dual_blade").controls().comboResetTicks(),"existing controls need no file rewrite for the default");
        controls.addProperty("comboResetTicks",0);
        equal(0,parse(files).styles().get("dual_blade").controls().comboResetTicks(),"explicit zero survives strict config loading");
        for(JsonElement bad:List.of(new JsonPrimitive(-1),new JsonPrimitive(1.5),new JsonPrimitive("12"),JsonNull.INSTANCE)) {
            controls.add("comboResetTicks",bad);
            try {parse(files);throw new AssertionError("invalid comboResetTicks accepted "+bad);}
            catch(RuntimeException expected){checks++;}
        }
    }
    private static RpgConfig.Definitions parse(List<JsonElement> files) {
        return RpgConfig.parse(files.get(0),files.get(1),files.get(2),files.get(3),files.get(4));
    }

    private static void ticks(CombatActor actor,int count){for(int i=0;i<count;i++)actor.tick();}
    private static void equal(Object expected,Object actual,String message){check(Objects.equals(expected,actual),message+" expected="+expected+" actual="+actual);}
    private static void near(double expected,double actual,String message){check(Math.abs(expected-actual)<1e-8,message+" expected="+expected+" actual="+actual);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
