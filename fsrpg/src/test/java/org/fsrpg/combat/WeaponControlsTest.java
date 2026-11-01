package org.fsrpg.combat;
import org.fsrpg.config.RpgConfig;
import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;
/** Fixed dual-hand inputs, phase cancels and buffered aim ownership are independent of rendering. */
public final class WeaponControlsTest {
    static int checks;
    static void check(boolean yes,String text){if(!yes)throw new AssertionError(text);checks++;}
    static RpgConfig.Definitions definitions() throws Exception {
        var values=new ArrayList<com.google.gson.JsonElement>();for(String n:List.of("combat","skills","quests","weapons","styles")) values.add(JsonParser.parseString(Files.readString(Path.of("src/main/resources/defaults/"+n+".json"))));
        return RpgConfig.parse(values.get(0),values.get(1),values.get(2),values.get(3),values.get(4));
    }
    static CombatActor actor(RpgConfig.Definitions d,String style){
        var a=new CombatActor(UUID.randomUUID(),d.combat());var definition=d.styles().get(style);
        if(style.equals("dual_blade")) {
            // This suite preserves the 0.3 custom fixed-hand contract; LegacyMeleeTest covers migrated controls.
            var c=definition.controls();
            definition=new WeaponStyleDefinition(definition.id(),definition.label(),definition.weaponGroup(),definition.enabled(),
                    definition.leftCombo(),definition.rightSkill(),definition.guard(),definition.heavyGuard(),definition.techniques(),definition.cancels(),
                    new WeaponControls("dual",c.leftHandLeft(),c.rightHandLeft(),c.rightHandRight(),c.leftHandRight(),c.sweepSkill(),c.spearRadius()));
        }
        a.setStyle(definition);a.toggleDrawn();for(int i=0;i<30;i++)a.tick();return a;
    }
    static void finish(CombatActor a){for(int i=0;i<40;i++)a.tick();a.setResources(100,100);}
    static void ticks(CombatActor a,int count){for(int i=0;i<count;i++)a.tick();}
    public static void main(String[] args) throws Exception {
        var d=definitions();
        fixedHands(d); repeatedClicks(d); dualCancelsAndBuffer(d); legacySkills(d);
        var a=actor(d,"spear");var one=AttackAim.at(0,5);var two=new AttackAim(List.of(new AttackAim.Point(-25,8),new AttackAim.Point(30,-8)));
        var thrust=d.skills().get("spear_thrust");
        a.aimedSkill(thrust,one);ticks(a,thrust.windupTicks());a.aimedSkill(d.skills().get("spear_sweep"),two);
        check(a.attackAim().equals(one),"buffer does not retarget current active spear");
        ticks(a,thrust.activeTicks());check(a.attackAim().equals(two)&&a.currentSkill().id().equals("spear_sweep"),"queued path starts after all active frames");
        check(a.setGuarding(true)==ActionResult.ACCEPTED&&a.attackAim()==null,"cancel clears pending aim");
        var wrapped=new AttackAim(List.of(new AttackAim.Point(175,0),new AttackAim.Point(-175,10)));
        check(Math.abs(Math.abs(wrapped.sample(.5).yaw())-180)<.001,"yaw interpolation crosses seam by shortest arc");
        check(wrapped.sample(.5).pitch()==5,"vertical sweep interpolation");
        try{AttackAim.at(Double.NaN,0);throw new AssertionError("NaN accepted");}catch(IllegalArgumentException e){checks++;}
        try{new AttackAim(Collections.nCopies(33,new AttackAim.Point(0,0)));throw new AssertionError("oversize accepted");}catch(IllegalArgumentException e){checks++;}
        try{new AttackAim(List.of(new AttackAim.Point(-150,0),new AttackAim.Point(0,0),new AttackAim.Point(150,0)));throw new AssertionError("path flood accepted");}catch(IllegalArgumentException e){checks++;}
        System.out.println("WeaponControlsTest passed: "+checks+" checks");
    }

    private static void fixedHands(RpgConfig.Definitions d) {
        var a=actor(d,"dual_blade");
        for(var input:List.of(SkillInput.LEFT,SkillInput.LEFT,SkillInput.LEFT,SkillInput.RIGHT,
                SkillInput.RIGHT,SkillInput.RIGHT,SkillInput.LEFT,SkillInput.RIGHT)) {
            String id=input==SkillInput.LEFT?"dual_ll":"dual_rr";
            long sequence=a.snapshot().swingSequence();
            check(a.requestStyledAttack(input,d.skills()).started(),"each fresh click starts without direction exhaustion");
            check(a.currentSkill().id().equals(id),"button always selects the matching hand "+id);
            finish(a);
            check(a.snapshot().swingSequence()==sequence+1,"one click starts exactly one cut without automatic repetition");
            check(a.drainStartedSkills().size()==1,"one click emits one cost/quest event");
            check(a.phase()==CombatPhase.READY&&a.currentSkill()==null,"single cut fully returns to ready");
            check(a.leftSide()==-1&&a.rightSide()==-1,"ready snapshot returns both hands to neutral carry");
        }
    }

    private static void repeatedClicks(RpgConfig.Definitions d) {
        for(var input:List.of(SkillInput.LEFT,SkillInput.RIGHT)) {
            String id=input==SkillInput.LEFT?"dual_ll":"dual_rr";
            var definition=d.skills().get(id);
            int contactEnd=definition.windupTicks()+definition.activeTicks();
            for(int at=0;at<definition.durationTicks();at++) {
                var a=actor(d,"dual_blade");a.requestStyledAttack(input,d.skills());a.drainStartedSkills();ticks(a,at);
                double stamina=a.stamina();
                var next=a.requestStyledAttack(input,d.skills());
                check(next.accepted(),"second same-button click works during every attack phase "+input+" tick="+at);
                if(at<contactEnd) {
                    check(next.status()==ComboInputStatus.BUFFERED,"windup/active click buffers one same-hand follow-up");
                    check(a.stamina()==stamina&&a.drainStartedSkills().isEmpty(),"buffer neither charges nor advances skill quests");
                    for(int press=0;press<5;press++)
                        check(a.requestStyledAttack(input,d.skills()).status()==ComboInputStatus.ALREADY_QUEUED,"mashing cannot grow the single follow-up slot");
                    ticks(a,contactEnd-at-1);
                    check(a.snapshot().swingSequence()==1,"all first-cut collision ticks remain intact");
                    a.tick();
                } else check(next.started(),"recovery click starts the next same-hand cut immediately");
                check(a.currentSkill().id().equals(id)&&a.snapshot().swingSequence()==2,"follow-up retains the same button's hand");
                check(a.drainStartedSkills().size()==1,"follow-up emits exactly one start");
                finish(a);
                check(a.snapshot().swingSequence()==2&&!a.comboSnapshot().queued(),"repeated input leaves no hidden third cut");
                check(a.leftSide()==-1&&a.rightSide()==-1,"repeated cuts finish with neutral hand state");
            }
        }
    }

    private static void dualCancelsAndBuffer(RpgConfig.Definitions d) {
        var a=actor(d,"dual_blade");
        a.requestStyledAttack(SkillInput.LEFT,d.skills());a.requestStyledAttack(SkillInput.LEFT,d.skills());a.drainStartedSkills();
        check(a.setGuarding(true)==ActionResult.ACCEPTED,"dual windup permits guard cancellation");
        check(!a.comboSnapshot().queued(),"guard cancellation clears pending cut");
        a.setGuarding(false);finish(a);
        check(a.drainStartedSkills().isEmpty(),"cancelled follow-up never starts later");
        a.requestStyledAttack(SkillInput.LEFT,d.skills());a.drainStartedSkills();
        check(a.requestStyledAttack(SkillInput.RIGHT,d.skills()).status()==ComboInputStatus.BUFFERED,"other button buffers without losing the already accepted cut");
        check(a.currentSkill().id().equals("dual_ll"),"windup input preserves the current hand");
        ticks(a,d.skills().get("dual_ll").windupTicks()+d.skills().get("dual_ll").activeTicks());
        check(a.currentSkill().id().equals("dual_rr"),"other hand begins after all current contact ticks");
        a.drainStartedSkills();ticks(a,d.skills().get("dual_rr").windupTicks());
        check(a.setGuarding(true)==ActionResult.BUSY,"active dual strike remains locked");
        check(a.requestStyledAttack(SkillInput.LEFT,d.skills()).status()==ComboInputStatus.BUFFERED,"other hand buffers during active contact");
        check(a.requestStyledAttack(SkillInput.RIGHT,d.skills()).status()==ComboInputStatus.BUFFERED,"latest distinct intent replaces the single slot");
        check(a.comboSnapshot().queuedSkillId().equals("dual_rr"),"latest button owns the next cut");
        ticks(a,d.skills().get("dual_rr").activeTicks());
        check(a.drainStartedSkills().size()==1&&a.currentSkill().id().equals("dual_rr"),"one latest buffered cut starts after contact");
        a.stun(2);finish(a);
        check(!a.comboSnapshot().queued()&&a.leftSide()==-1&&a.rightSide()==-1,"forced interrupts preserve neutral ready state");
    }

    private static void legacySkills(RpgConfig.Definitions d) {
        var a=actor(d,"dual_blade");
        for(String id:List.of("dual_ll","dual_rl","dual_rr","dual_lr","dual_rl","dual_lr")) {
            check(a.requestAttack(d.skills().get(id))==ActionResult.ACCEPTED,"stable named skill remains available "+id);
            check(a.currentSkill().id().equals(id),"legacy named skill keeps its original ID for quests/configs");
            finish(a);
        }
        check(a.requestStyledAttack(SkillInput.LEFT,d.skills()).skillId().equals("dual_ll"),"legacy skill usage cannot change the fixed left input");
    }
}
