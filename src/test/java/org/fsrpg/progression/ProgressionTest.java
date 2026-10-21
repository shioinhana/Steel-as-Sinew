package org.fsrpg.progression;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.fsrpg.progression.ProgressionService.*;

/** Genuine event-driven learning, configured milestones, exclusive choices and durable old growth. */
public final class ProgressionTest {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long DAY = 20000;
    private static int checks;
    public static void main(String[] args) throws Exception {
        SkillTreeCatalog catalog = SkillTreeCatalog.bundled(JSON);
        strictCatalog(catalog); groundFilter(); firstCharacter(catalog); swordMilestones(catalog);
        bowBranches(catalog); aptitudeGrowth(catalog); dailyDecay(catalog);
        durableIsolation(catalog); reloadAndInvalidSaves(catalog);
        System.out.println("ProgressionTest passed: " + checks + " checks");
    }
    private static void groundFilter() {
        near(.2,GroundTrainingRules.distance(true,true,true,1,false,.2,0,1,0),"alignedserverinputgroundmovementtrains");
        near(0,GroundTrainingRules.distance(true,true,true,1,false,.2,0,0,0),"noinputenvironmentpushcannottrain");
        near(0,GroundTrainingRules.distance(true,true,true,1,false,.2,0,-1,0),"oppositeexternalpushcannottrain");
        near(0,GroundTrainingRules.distance(true,true,true,1,false,.2,0,0,1),"perpendicularpushcannottrain");
        near(0,GroundTrainingRules.distance(true,true,true,2,false,.2,0,1,0),"nonadjacentsamplescannottrain");
        near(0,GroundTrainingRules.distance(true,true,false,1,false,.2,0,1,0),"dimensionchangecannottrain");
        near(0,GroundTrainingRules.distance(true,true,true,1,true,.2,0,1,0),"forcedlungecannottrain");
        near(0,GroundTrainingRules.distance(false,true,true,1,false,.2,0,1,0),"landingframecannottrain");
        near(0,GroundTrainingRules.distance(true,false,true,1,false,.2,0,1,0),"takeoff/liquid/ladderframecannottrain");
        near(0,GroundTrainingRules.distance(true,true,true,1,false,1.5,0,1,0),"sampleatteleportcutoffcannottrain");
        near(0,GroundTrainingRules.distance(true,true,true,1,false,Double.NaN,0,1,0),"nonfinitepositioncannottrain");
    }
    private static void strictCatalog(SkillTreeCatalog c) {
        equal(25, c.nodes().size(), "complete migrated weapon and footwork trees");
        equal(new SkillTreeCatalog.Cost(2,3,4), c.node("rapier").cost(), "old single sword costs");
        equal(new SkillTreeCatalog.Cost(1,5,3), c.node("dual_sword").cost(), "old double sword costs");
        equal(new SkillTreeCatalog.Cost(3,4,1), c.node("archery").cost(), "old bow costs");
        equal(7, c.rules().rapier().windupTicks(), "old .35s thrust chamber");
        equal(16, c.rules().rapier().fullChargeTicks(), "old .8s charge");
        near(9, c.rules().rapier().thrustDamage(), "normal thrust damage");
        near(18, c.rules().rapier().parriedDamage(), "parried thrust damage");
        c.validateReferences(id -> true, id -> true);
        rejects(() -> c.validateReferences(id -> !id.equals("sword_thrust"), id -> true), "missing skill reference");
        rejects(() -> c.validateReferences(id -> true, id -> !id.equals("bow_snipe")), "missing second bow state");
        JsonObject base = definition(c);
        JsonObject unknown = base.deepCopy(); unknown.addProperty("clientMayUnlock", true); invalid(unknown, "unknown root key");
        JsonObject nested = base.deepCopy(); nested.getAsJsonObject("rules").getAsJsonObject("training").addProperty("allFree", true); invalid(nested, "unknown nested key");
        JsonObject fractional = base.deepCopy(); fractional.getAsJsonObject("rules").getAsJsonObject("rapier").addProperty("fullChargeTicks", 16.5); invalid(fractional, "fractional ticks");
        JsonObject numericString = base.deepCopy(); numericString.getAsJsonObject("rules").getAsJsonObject("rapier").addProperty("range", "4"); invalid(numericString, "numeric string");
        JsonObject infinity = base.deepCopy(); infinity.getAsJsonObject("rules").getAsJsonObject("rapier").addProperty("range", Double.POSITIVE_INFINITY); invalid(infinity, "infinite range");
        JsonObject negative = base.deepCopy(); negative.getAsJsonObject("rules").getAsJsonObject("dual").addProperty("decayTicks", -1); invalid(negative, "negative interval");
        JsonObject duplicate = base.deepCopy(); duplicate.getAsJsonArray("nodes").add(duplicate.getAsJsonArray("nodes").get(0).deepCopy()); invalid(duplicate, "duplicate ID");
        JsonObject unknownEffect = base.deepCopy(); node(unknownEffect, "rapier").addProperty("effect", "CLIENT_GOD_MODE"); invalid(unknownEffect, "unknown effect");
        JsonObject wrongTree = base.deepCopy(); node(wrongTree, "rapier_thrust").addProperty("tree", "bow"); invalid(wrongTree, "wrong effect-tree mapping");
        JsonObject missingPrior = base.deepCopy(); node(missingPrior, "rapier_thrust").getAsJsonArray("prerequisites").add("removed_parent"); invalid(missingPrior, "missing prerequisite");
        JsonObject cycle = base.deepCopy(); node(cycle,"rapier_thrust").getAsJsonArray("prerequisites").add("rapier_parry_thrust"); invalid(cycle, "cyclic dependency");
        JsonObject unsorted = base.deepCopy(); unsorted.getAsJsonObject("rules").getAsJsonObject("training").add("bodyDamageThresholds", JSON.toJsonTree(List.of(20,10))); invalid(unsorted,"threshold order");
        JsonObject autoCost = base.deepCopy(); node(autoCost,"dual_combo").getAsJsonObject("cost").addProperty("mind",1); invalid(autoCost,"automatic node cannot deduct points");
        JsonObject wrongGroup = base.deepCopy(); node(wrongGroup,"archery_ru_suo").addProperty("exclusiveGroup",""); invalid(wrongGroup,"branch cannot bypass exclusive group");
        JsonObject bypassRoot = base.deepCopy(); node(bypassRoot,"rapier_thrust").add("prerequisites",new JsonArray()); invalid(bypassRoot,"effect cannot bypass learned weapon root");
        equal(8L, c.nodes().stream().filter(n->n.tree().equals("footwork")).count(), "eight legacy footwork nodes");
        for(String id:List.of("rabbit","flying_swan","cloud_step","cloud_step_ii","cloud_step_iii","light_body","light_body_ii","light_body_iii"))
            check(c.find(id).isPresent()&&c.node(id).tree().equals("footwork"),"footwork node present: "+id);
        equal(FootworkRules.defaults(),c.rules().footwork(),"footwork rule block carries the published legacy parameters");
        equal("footwork_style",c.node("rabbit").exclusiveGroup(),"schools are exclusive");
        equal(c.node("rabbit").exclusiveGroup(),c.node("flying_swan").exclusiveGroup(),"both schools share one exclusive group");
        check(!c.node("cloud_step").automatic()&&!c.node("light_body").automatic(),"techniques are manual choices");
        equal(new SkillTreeCatalog.Cost(0,0,0),c.node("cloud_step").cost(),"techniques cost selection credits, not resource points");
    }
    private static void firstCharacter(SkillTreeCatalog c) {
        ProgressionService s = new ProgressionService(c); s.initializeAptitudes(A,0,0,DAY);
        check(!s.weaponUnlocked(A,"rapier") && !s.weaponUnlocked(A,"dual_blade") && !s.weaponUnlocked(A,"bow_rapid"), "new player does not bypass learning");
        check(s.weaponUnlocked(A,"spear") && s.skillUnlocked(A,"spear_thrust"), "spear unchanged");
        equal(18,s.effects(A).regenerationFoodLevel(),"vanilla hunger regen threshold retained");
        check(!s.learn(A,"rapier").success(), "must complete sleep");
        s.onFood(A,"minecraft:apple",20); s.onHostileDamage(A,10); s.onMined(A,1000); s.onGroundTravel(A,1000);
        equal(new Points(0,0),s.view(A).mind(),"pre-sleep events do not grow mind");
        check(!s.view(A).bodyUnlocked(),"pre-sleep damage cannot unlock body");
        check(s.onSleep(A),"effective sleep unlocks mind"); check(!s.onSleep(A),"repeat sleep does not grant points");
        s.onFood(A,"minecraft:apple",20); equal(new Points(3,3),s.view(A).mind(),"distinct food and actual hunger growth");
        s.onFood(A,"minecraft:apple",0); equal(3,s.view(A).mind().total(),"same food cannot grant another discovery");
        s.onHostileDamage(A,9.9); check(!s.view(A).bodyUnlocked(),"real HP loss must reach ten");
        s.onHostileDamage(A,.1); check(s.view(A).bodyUnlocked(),"ten real HP unlocks body");
        check(s.onHostileIncoming(A,B,1),"blocked hostile incoming also trains unlocked body");
        check(!s.onHostileIncoming(A,B,1),"same source and tick deduplicated");
        equal(1,s.view(A).body().total(),"duplicate incoming cannot farm points");
        s.onMined(A,200); check(s.view(A).footworkUnlocked(),"five body points unlock footwork");
        s.onGroundTravel(A,200); equal(7,s.view(A).body().total(),"ground distance grows body each100m"); equal(4,s.view(A).footwork().total(),"ground distance grows footwork each50m");
        check(s.learn(A,"rapier").success(),"real growth funds single sword");
        equal(new Points(3,1),s.view(A).mind(),"learning consumes available mind only");
        equal(new Points(7,4),s.view(A).body(),"learning preserves earned body total");
        equal(new Points(4,0),s.view(A).footwork(),"learning footwork debit");
        Snapshot once = s.snapshot(); check(!s.learn(A,"rapier").success(),"repeat learning rejected"); equal(once,s.snapshot(),"repeat learning leaves all points unchanged");
        check(!s.learn(A,"dual_sword").success(),"insufficient balance fails atomically"); equal(once,s.snapshot(),"failed purchase consumes nothing");
        check(s.skillUnlocked(A,"sword_left"),"learned root permits actual basic sword skills");
        check(!s.skillUnlocked(A,"sword_thrust"),"eighty-kill thrust still gated");
        check(!s.recordArcheryKill(A,DAY),"bow kills cannot train unlearned bow"); equal(0,s.view(A).archeryScore(),"unlearned bow score remains zero");
        s.onGroundTravel(A,150); s.onFood(A,"minecraft:carrot",0);
        check(s.learn(A,"dual_sword").success(),"new actual events can fund double sword");
        check(s.skillUnlocked(A,"dual_combined"),"root grants real linked slash");
        check(!s.learn(A,"dual_combo").success(),"automatic nodes cannot be directly purchased");
        rejects(() -> s.onGroundTravel(A,Double.NaN),"nonfinite movement rejected");
        rejects(() -> s.onHostileDamage(A,-1),"negative damage rejected");
        rejects(() -> s.onActiveTicks(A,Long.MAX_VALUE),"active time bounded");
        ProgressionService walking = new ProgressionService(c); walking.initializeAptitudes(B,0,0,DAY); walking.onSleep(B); walking.onHostileDamage(B,10);
        walking.onGroundTravel(B,500); check(walking.view(B).footworkUnlocked(),"travel reaching body5 unlocks footwork"); equal(0,walking.view(B).footwork().total(),"previouslylocked footwork cannot claim earlier travel");
        walking.onGroundTravel(B,50); equal(1,walking.view(B).footwork().total(),"onlysubsequent eligible movement trains footwork");
    }
    private static void swordMilestones(SkillTreeCatalog c) {
        ProgressionService s = fixture(c,30,25,40,79,49,0);
        check(!s.unlocked(A,"rapier_thrust"),"thrust locked at79"); s.recordRapierKill(A,DAY);
        check(s.unlocked(A,"rapier_thrust") && s.effects(A).rapierThrust(),"actual80th enemy kill unlocks thrust");
        check(s.skillUnlocked(A,"sword_thrust"),"unlocked thrust reaches actual skill gate");
        s = fixture(c,50,40,90,179,0,0); s.recordRapierKill(A,DAY); check(s.effects(A).rapierParry(),"180th enemy unlocks parry with exact totals");
        s = fixture(c,80,60,160,300,0,0); s.recordRapierKill(A,DAY); check(!s.effects(A).rapierExecution(),"301 alone cannot skip dragon");
        check(s.dragonKilled(A),"actual dragon event recorded"); check(s.effects(A).rapierExecution(),"301 plus dragon unlocks execution");
        check(!s.dragonKilled(A),"repeat dragon boolean does not grant repeated unlock");
        s = fixture(c,20,25,30,0,49,0); s.recordDualHit(A,100,DAY);
        equal(50,s.view(A).dualHits(),"real dual hit increments score"); equal(3,s.view(A).dualCap(),"fiftieth hit activates3layer mechanic"); equal(1,s.view(A).dualStacks(),"unlocking hit itself adds first stack");
        s.recordDualHit(A,101,DAY); s.recordDualHit(A,102,DAY); s.recordDualHit(A,103,DAY); equal(3,s.view(A).dualStacks(),"cap prevents unlimited stacks");
        near(.15,s.effects(A).dualHoldingMoveBonus(),"holding dual speed bonus"); near(.25,s.effects(A).dualAttackingMoveBonus(),"swing speed adds .15-.35+3*.15");
        s.tick(A,202); equal(3,s.view(A).dualStacks(),"no decay before100tick quiet"); s.tick(A,203); equal(2,s.view(A).dualStacks(),"five seconds removes exactly one stack");
        s.tick(A,403); equal(0,s.view(A).dualStacks(),"additional quiet periods clear layers");
        s = fixture(c,40,45,80,0,249,0); s.recordDualHit(A,100,DAY); equal(4,s.view(A).dualCap(),"250hit tier with exact stats");
        s = fixture(c,60,65,140,0,399,0); s.recordDualHit(A,100,DAY); equal(5,s.view(A).dualCap(),"400hit tier with exact stats");
        s = fixture(c,85,90,220,0,50,0); equal(3,s.view(A).dualCap(),"being near trial does not unlocktier"); s.trialPassed(A); equal(6,s.view(A).dualCap(),"complete trial and real thresholds grant6 independent of400 tier");
        s = fixture(c,20,24,30,0,400,0); equal(0,s.view(A).dualCap(),"mastery alone cannot skip body condition");
    }
    private static void bowBranches(SkillTreeCatalog c) {
        ProgressionService s = fixture(c,50,40,100,0,0,49);
        check(!s.unlocked(A,"archery_auto_charge"),"auto shot locked at49"); s.recordArcheryKill(A,DAY); check(s.unlocked(A,"archery_auto_charge"),"50th real arrowkill unlocks auto shot");
        for (int i = 50; i < 100; i++) s.recordArcheryKill(A,DAY); check(s.unlocked(A,"archery_trajectory"),"100actual kills unlock trajectory");
        check(!s.learn(A,"archery_ru_suo").success(),"branch score must reach150");
        for (int i = 100; i < 150; i++) s.recordArcheryKill(A,DAY);
        check(s.learn(A,"archery_ru_suo").success(),"150score branch chosen through authority");
        equal("RU_SUO",s.archeryView(A).branch150(),"bow adapter uses agreed enum name");
        Snapshot selected = s.snapshot(); check(!s.learn(A,"archery_nie_shi").success(),"other150branch cannot be chosen too"); equal(selected,s.snapshot(),"mutual exclusion no debit or state change");
        check(!s.learn(A,"archery_ru_suo").success(),"duplicate selection rejected");
        for (int i = 150; i < 250; i++) s.recordArcheryKill(A,DAY);
        check(s.learn(A,"archery_chuan_tang_feng").success(),"250second independent branch chosen");
        equal("CHUAN_TANG_FENG",s.archeryView(A).branch250(),"arrow modifier enum name");
        check(!s.learn(A,"archery_yan_xia_dian").success(),"250choices mutually exclusive");
        for (int i = 250; i < 300; i++) s.recordArcheryKill(A,DAY);
        check(s.unlocked(A,"archery_scope"),"300real kills unlock scope");
        equal(new Points(50,50),s.view(A).mind(),"free branches leave mind available");
    }
    private static void aptitudeGrowth(SkillTreeCatalog c) {
        ProgressionService s = new ProgressionService(c); s.initializeAptitudes(A,-30,-30,DAY); s.onSleep(A); s.migrateExistingCombat(A);
        check(!s.initializeAptitudes(A,30,30,DAY),"aptitudes never reroll on reconnect");
        for (int i=0;i<10;i++) s.recordArcheryKill(A,DAY); equal(7,s.view(A).archeryScore(),"70%growth preserves fraction across ten real kills");
        s.onActiveTicks(A,3_456_000); equal(0,s.view(A).mind().total(),"lowphysique first0.7mind retained as remainder");
        s.onActiveTicks(A,3_456_000); equal(1,s.view(A).mind().total(),"second interval realizes savedfraction");
        for(int i=0;i<13;i++) s.bossDefeated(A); equal(100,s.view(A).insightPercent(),"permanent boss insight reaches200% efficiency cap");
        for(int i=0;i<20;i++) s.bossDefeated(A); equal(100,s.view(A).insightPercent(),"extra bosses cannot exceed cap");
        s.recordArcheryKill(A,DAY); equal(9,s.view(A).archeryScore(),"capped wisdom doubles real kill gain");
        check(s.rerollPhysique(A,30),"one free physique reroll"); check(!s.rerollPhysique(A,-30),"reroll limited toonce");
        s.onActiveTicks(A,3_456_000); equal(2,s.view(A).mind().total(),"newphysique combined with persisted remainder");
    }
    private static void dailyDecay(SkillTreeCatalog c) {
        ProgressionService s = fixture(c,100,100,600,301,400,300); s.learn(A,"archery_ru_suo"); s.learn(A,"archery_chuan_tang_feng");
        s.maintain(A,DAY+1); equal(95,s.view(A).mind().total(),"one day rounds5percent base loss"); equal(570,s.view(A).footwork().total(),"footwork dailybase decay"); equal(300,s.view(A).archeryScore(),"mastery not decayed beforethird day");
        Snapshot once = s.snapshot(); s.maintain(A,DAY+1); equal(once,s.snapshot(),"same day cannot double decay");
        s.maintain(A,DAY-1); equal(once,s.snapshot(),"clockrollback cannot replay decay");
        s.maintain(A,DAY+3); equal(86,s.view(A).mind().total(),"three individual daily round operations"); equal(270,s.view(A).archeryScore(),"thirdidle day mastery drops10percent");
        equal(271,s.view(A).rapierKills(),"rapier score decay rounds");
        s.recordArcheryKill(A,DAY+3); equal(271,s.view(A).archeryScore(),"practice increments after settled decay");
        s.maintain(A,DAY+5); equal(271,s.view(A).archeryScore(),"practice resets idle decay anchor"); s.maintain(A,DAY+6); equal(244,s.view(A).archeryScore(),"nextthree-day period afterpractice");
        equal("NONE",s.archeryView(A).branch250(),"score below250 revokes oldbranch"); check(s.weaponUnlocked(A,"bow_rapid"),"root learning survives decay");
        s = fixture(c,30,25,50,0,0,150); check(s.learn(A,"archery_ru_suo").success(),"branch starts exactly atgates"); s.maintain(A,DAY+1);
        equal("NONE",s.archeryView(A).branch150(),"base stat decline also revokes branch");
        s = fixture(c,100,100,600,0,0,0); Effects e = s.effects(A);
        near(10,e.healthBonus(),"five mindhealthtiers"); near(3,e.damageBonus(),"three bodydamage tiers"); near(.08,e.attackIntervalReductionSeconds(),"body attackinterval reduction");
        near(.81,e.bowDrawFactor(),"body bowdraw multiplicativelevels"); near(Math.pow(.95,4),e.miningTimeFactor(),"four miningtime levels"); near(.2,e.movementSpeedBonus(),"footworkcap20percent speed");
        equal(3,e.attackIntervalTicks(2),"minimum .12seconds roundsup toserver ticks"); equal(5,e.attackIntervalTicks(7),"normalbody cadence rounds tickclock");
        equal(16,e.regenerationFoodLevel(),"old50mind foodthreshold16"); near(2,e.regenerationMultiplier(),"old10mind regen eligibility");
    }
    private static void durableIsolation(SkillTreeCatalog c) throws Exception {
        ProgressionService s = fixture(c,50,45,100,80,250,250); s.learn(A,"archery_nie_shi"); s.learn(A,"archery_yan_xia_dian");
        s.recordDualHit(A,100,DAY); s.onFood(A,"minecraft:apple",7); s.onGroundTravel(A,21.5); s.onActiveTicks(A,123);
        s.initializeAptitudes(B,30,30,DAY); check(!s.weaponUnlocked(B,"rapier"),"otherplayer doesnotshare learning");
        equal(0,s.view(B).archeryScore(),"otherplayerdoesnotshare mastery");
        Path folder=Files.createTempDirectory("fsrpg-progression-test-"); Path file=folder.resolve("cultivation.json");
        try {
            ProgressionStore store=new ProgressionStore(file,JSON); equal(Map.of(),store.load().players(),"missing store starts empty"); store.save(s.snapshot());
            ProgressionService loaded=new ProgressionService(c); loaded.restore(store.load()); equal(s.snapshot(),loaded.snapshot(),"durable data exactly survives reload"); equal(s.view(A),loaded.view(A),"restored passive and node view identical");
            check(!loaded.learn(A,"archery_ru_suo").success(),"mutual exclusion survives restart");
            loaded.onFood(A,"minecraft:apple",3); equal(s.view(A).mind().total()+1,loaded.view(A).mind().total(),"savedfood discovery and hunger remainder continue precisely");
            store.save(loaded.snapshot()); ProgressionService again=new ProgressionService(c); again.restore(store.load()); equal(loaded.snapshot(),again.snapshot(),"second atomicsave preserves consumed learning state");
            Files.writeString(file,"{broken"); rejectsChecked(store::load,"corrupt store isreported not silentlyreset");
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(folder); }
    }
    private static void reloadAndInvalidSaves(SkillTreeCatalog c) {
        ProgressionService s = fixture(c,100,100,600,350,500,350);
        JsonObject saved=JSON.toJsonTree(s.snapshot()).getAsJsonObject(); player(saved).getAsJsonArray("learnedNodes").add("removed_future_skill"); s.restore(JSON.fromJson(saved,Snapshot.class));
        check(s.snapshot().players().get(A).learnedNodes().contains("removed_future_skill"),"removedstableID remainssaved");
        JsonObject config=definition(c); config.getAsJsonObject("rules").getAsJsonObject("training").addProperty("footworkCap",500); s.reconfigure(SkillTreeCatalog.parse(config,JSON)); equal(500,s.view(A).footwork().total(),"newcap applieswithoutresetting progress");
        saved=JSON.toJsonTree(s.snapshot()).getAsJsonObject(); player(saved).add("footwork",JSON.toJsonTree(new Points(600,400))); s.restore(JSON.fromJson(saved,Snapshot.class)); equal(new Points(500,300),s.view(A).footwork(),"capnormalize preserves spent200points");
        Snapshot before=s.snapshot(); JsonObject invalid=JSON.toJsonTree(before).getAsJsonObject(); player(invalid).addProperty("dualStacks",99); rejects(() -> s.restore(JSON.fromJson(invalid,Snapshot.class)),"badcombo state rejected"); equal(before,s.snapshot(),"failedrestore atomic");
        JsonObject conflict=JSON.toJsonTree(before).getAsJsonObject(); player(conflict).getAsJsonArray("learnedNodes").add("archery_ru_suo"); player(conflict).getAsJsonArray("learnedNodes").add("archery_nie_shi"); rejects(() -> s.restore(JSON.fromJson(conflict,Snapshot.class)),"malformedmutualbranches rejected");
        JsonObject nan=JSON.toJsonTree(before).getAsJsonObject(); player(nan).addProperty("bodyMetres",Double.NaN); rejects(() -> s.restore(JSON.fromJson(nan,Snapshot.class)),"savednonfinitecounter rejected");
        ProgressionService migrated=new ProgressionService(c); check(migrated.migrateExistingCombat(B),"existingadventuregetsrootmigration"); Snapshot once=migrated.snapshot(); check(!migrated.migrateExistingCombat(B),"migrationonce"); equal(once,migrated.snapshot(),"repeatedmigrationnopoints");
        check(migrated.weaponUnlocked(B,"rapier") && migrated.weaponUnlocked(B,"dual_blade") && migrated.weaponUnlocked(B,"bow_snipe"),"oldbasiccombatremainsusable"); check(!migrated.effects(B).rapierThrust(),"migrationdoesnotgrantadvancedabilities");
    }
    private static ProgressionService fixture(SkillTreeCatalog c,int mind,int body,int foot,int rapier,int dual,int bow) {
        ProgressionService s=new ProgressionService(c); s.initializeAptitudes(A,0,0,DAY); s.migrateExistingCombat(A); s.onSleep(A);
        JsonObject save=JSON.toJsonTree(s.snapshot()).getAsJsonObject(); JsonObject p=player(save);
        p.add("mind",JSON.toJsonTree(new Points(mind,mind))); p.add("body",JSON.toJsonTree(new Points(body,body))); p.add("footwork",JSON.toJsonTree(new Points(foot,foot)));
        p.addProperty("bodyUnlocked",true); p.addProperty("footworkUnlocked",true); p.addProperty("rapierKills",rapier); p.addProperty("dualHits",dual); p.addProperty("archeryScore",bow);
        p.add("growthDays",JSON.toJsonTree(Map.of("rapier",DAY,"dual_blade",DAY,"bow",DAY)));
        s.restore(JSON.fromJson(save,Snapshot.class)); return s;
    }
    private static JsonObject player(JsonObject save) { return save.getAsJsonObject("players").getAsJsonObject(A.toString()); }
    private static JsonObject definition(SkillTreeCatalog c) { JsonObject result=new JsonObject(); result.addProperty("schemaVersion",1); result.add("rules",JSON.toJsonTree(c.rules())); result.add("nodes",JSON.toJsonTree(c.nodes())); return result; }
    private static JsonObject node(JsonObject config,String id) { for(JsonElement e:config.getAsJsonArray("nodes")) if(e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject(); throw new AssertionError(id); }
    private static void invalid(JsonObject json,String message) { rejects(() -> SkillTreeCatalog.parse(json,JSON),message); }
    private static void equal(Object expected,Object actual,String message) { check(Objects.equals(expected,actual),message+" expected="+expected+" actual="+actual); }
    private static void near(double expected,double actual,String message) { check(Math.abs(expected-actual)<1e-8,message+" expected="+expected+" actual="+actual); }
    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
    private static void rejects(Runnable call,String message) { checks++; try {call.run();} catch(RuntimeException expected){return;} throw new AssertionError(message); }
    private interface Checked { Object run() throws Exception; }
    private static void rejectsChecked(Checked call,String message) { checks++; try {call.run();} catch(Exception expected){return;} throw new AssertionError(message); }
}
