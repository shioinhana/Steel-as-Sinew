package org.fsrpg.config;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.fsrpg.archery.*;
import org.fsrpg.combat.*;

/** Fixed per-attack debits, flat partial-bow discount, and upgrade preservation. */
public final class WeaponStaminaBalanceTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("fsrpg-cost-balance-");
        try {
            var config=new RpgConfig(dir);var defs=config.reload();
            for(var skill:defs.skills().values()) {
                Double cost=expected(skill);
                if(cost==null)continue;
                near(cost,skill.staminaCost(),skill.id()+" configured debit");
                var actor=ready(defs.combat());actor.setResources(cost,100);
                equal(ActionResult.ACCEPTED,actor.requestAttack(skill),"exact stamina accepts "+skill.id());
                near(0,actor.stamina(),"one debit "+skill.id());
                actor=ready(defs.combat());actor.setResources(cost-.001,100);
                equal(ActionResult.INSUFFICIENT_STAMINA,actor.requestAttack(skill),"insufficient rejects "+skill.id());
                near(cost-.001,actor.stamina(),"reject retains resources "+skill.id());
            }
            var bow=defs.archery();near(5,bow.rapidStaminaCost(),"rapid full cost");near(15,bow.snipeStaminaCost(),"sniper full cost");
            for(double power:new double[]{.2,.21,.5,.9,Math.nextDown(1.)}) {
                near(3.5,ArcheryScores.shotStaminaCost(bow,false,power),"flat rapid partial "+power);
                near(10.5,ArcheryScores.shotStaminaCost(bow,true,power),"flat sniper partial "+power);
            }
            near(5,ArcheryScores.shotStaminaCost(bow,false,1),"rapid full no discount");
            near(15,ArcheryScores.shotStaminaCost(bow,true,1),"sniper full no discount");
            var controller=new ArcheryController(bow);var progress=new ArcheryProgress(true,0,ArcheryProgress.Branch150.NONE,ArcheryProgress.Branch250.NONE,3,1);
            controller.reconcile("test-bow",true,ArcheryController.Stance.RAPID,0,bow,progress);controller.beginCharge(0);
            check(controller.release(1,false,bow,progress)==null,"under minimum release still produces no shot");
            var originalSkills=read(dir,"skills.json").getAsJsonArray();var originalBow=read(dir,"archery.json").getAsJsonObject();
            for(var value:originalSkills) {var s=value.getAsJsonObject();s.addProperty("staminaCost",42);s.addProperty("damage",17.25);}
            originalBow.addProperty("rapidStaminaCost",31);originalBow.addProperty("snipeStaminaCost",47);originalBow.addProperty("rapidDrawTicks",13);
            String skillsText=" \n"+RpgConfig.JSON.toJson(originalSkills)+"\n",bowText=" \n"+RpgConfig.JSON.toJson(originalBow)+"\n";
            Files.writeString(dir.resolve("skills.json"),skillsText);Files.writeString(dir.resolve("archery.json"),bowText);
            Files.delete(dir.resolve(WeaponStaminaBalanceMigration.MARKER));
            // Fresh seeds need no backup; the first actual upgrade now snapshots the custom originals.
            defs=config.reload();
            equal(skillsText,Files.readString(dir.resolve(WeaponStaminaBalanceMigration.BACKUP).resolve("skills.json")),"exact custom skill backup");
            equal(bowText,Files.readString(dir.resolve(WeaponStaminaBalanceMigration.BACKUP).resolve("archery.json")),"exact custom bow backup");
            for(var skill:defs.skills().values()) {near(17.25,skill.damage(),"preserve custom damage");Double e=expected(skill);near(e==null?42:e,skill.staminaCost(),"only requested cost");}
            near(13,defs.archery().rapidDrawTicks(),"preserve custom draw timing");near(3,defs.combat().staminaRegenPerSecond(),"regen never re-triples");
            String once=Files.readString(dir.resolve("skills.json"));config.reload();equal(once,Files.readString(dir.resolve("skills.json")),"reload idempotent");
            Files.delete(dir.resolve(WeaponStaminaBalanceMigration.MARKER));config.reload();equal(once,Files.readString(dir.resolve("skills.json")),"interrupted marker retry never doubles costs");
            equal(skillsText,Files.readString(dir.resolve(WeaponStaminaBalanceMigration.BACKUP).resolve("skills.json")),"retry retains original backup");
            System.out.println("WeaponStaminaBalanceTest passed: "+checks+" checks");
        }finally{try(var files=Files.walk(dir)){for(var p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static Double expected(SkillDefinition s) {
        if(s.weapon().equals("sword")||s.weapon().equals("rapier"))return 5.;
        if(s.weapon().equals("dual_blade"))return 8.;
        return switch(s.id()){case "spear_thrust"->10.;case "bow_rapid_release"->5.;case "bow_snipe_release"->15.;default->null;};
    }
    private static CombatActor ready(CombatRules rules){var a=new CombatActor(UUID.randomUUID(),rules);a.requestDrawn();for(int i=0;i<rules.drawTicks();i++)a.tick();return a;}
    private static JsonElement read(Path d,String name)throws Exception{return JsonParser.parseString(Files.readString(d.resolve(name)));}
    private static void near(double expected,double actual,String text){check(Math.abs(expected-actual)<1e-8,text+": "+actual);}
    private static void equal(Object a,Object b,String text){check(a.equals(b),text+": "+b);}
    private static void check(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
}
