package org.fsrpg.combat;

import java.util.*;

public final class WeaponClashTest {
    private static int checks;
    private static final UUID A=new UUID(0,1), B=new UUID(0,2), C=new UUID(0,3);
    public static void main(String[] args) {
        var rules=WeaponClashRules.defaults();
        check(rules.contactWindowTicks()==3,"default real-contact window is three ticks");
        for (int gap=0;gap<=4;gap++) for (boolean reverse:List.of(false,true)) {
            var buffer=new WeaponClash<String>();
            var first=contact(A,B,1,100,10);var second=contact(B,A,2,100+gap,6);
            if(reverse){buffer.offer(second);buffer.offer(first);}else{buffer.offer(first);buffer.offer(second);}
            var batch=buffer.resolve(100+gap,rules);
            check(batch.pairs().size()==(gap<=3?1:0),"inclusive window, gap="+gap+" reverse="+reverse);
            if(gap<=3){near(batch.pairs().getFirst().exchange().firstDamage(),1,"10 versus 6 leaves one");check(buffer.size()==0,"pair consumed once");}
        }
        for(double delta:List.of(0.,2.9999,3.,3.0001,10.)) {
            var exchange=WeaponClash.compare(20+delta,20,3);
            near(exchange.firstDamage(),Math.max(0,delta-3),"tolerance boundary "+delta);
            near(exchange.secondDamage(),0,"weak side never deals damage");
            near(WeaponClash.compare(20,20+delta,3).secondDamage(),exchange.firstDamage(),"symmetric formula");
        }
        var buffer=new WeaponClash<String>();
        check(buffer.offer(contact(A,B,1,10,6)),"initial contact accepted");
        check(!buffer.offer(contact(A,B,1,10,6)),"same swing-target contact deduplicated");
        check(buffer.resolve(11,rules).expired().isEmpty(),"wait through one tick");
        check(buffer.resolve(12,rules).expired().isEmpty(),"wait through two ticks");
        check(buffer.resolve(13,rules).expired().size()==1,"unanswered hit released after three ticks");
        check(buffer.resolve(14,rules).expired().isEmpty(),"ordinary damage released exactly once");
        buffer.offer(contact(A,C,2,20,10));buffer.offer(contact(A,B,2,20,10));buffer.offer(contact(B,A,3,21,10));
        var batch=buffer.resolve(21,rules);
        check(batch.pairs().size()==1&&batch.expired().isEmpty()&&buffer.size()==0,"clashed swing cannot hit bystander");
        buffer.offer(contact(A,B,3,30,10));buffer.remove(B);check(buffer.size()==0,"departure clears both directions");
        buffer.offer(contact(A,B,3,30,10));buffer.clear();check(buffer.size()==0,"reload clears pending contacts");
        boolean rejected=false;try{WeaponClash.compare(Double.NaN,1,3);}catch(IllegalArgumentException e){rejected=true;}
        check(rejected,"nonfinite damage rejected");
        capturedContactAndLateDefense();
        capturedDefenseAndCounters();
        rearGuardContactKeepsReturnWindow();
        realReturnContacts();
        configuredWindowAndValidation();
        decimalToleranceBoundary();
        captureValidationAndGeometry();
        compatibleContactsAndRemoval();
        System.out.println("WeaponClashTest passed: "+checks+" checks");
    }

    private static void capturedContactAndLateDefense() {
        for(boolean dodge:List.of(false,true)) {
            var attacker=actor();var defender=actor();var cut=cut("single_active",6,1);attacker.requestAttack(cut);
            long sequence=attacker.snapshot().swingSequence();double paid=attacker.stamina();
            var captured=CombatResolver.captureMelee(attacker,defender,2,0,0);
            check(captured.outcome()==HitOutcome.HIT&&captured.damage()==6,"ordinary contact freezes its original hit result");
            check(attacker.hasHit(defender.id()),"capture immediately claims the swing-target pair");
            check(CombatResolver.captureMelee(attacker,defender,2,0,0).outcome()==HitOutcome.ALREADY_HIT,"same contact cannot be captured twice");
            check(attacker.staminaCombatState()&&defender.staminaCombatState(),"captured contact immediately starts combat recovery state");
            var buffer=new WeaponClash<HitResult>();buffer.offer(new WeaponClash.Contact<>(attacker.id(),defender.id(),sequence,100,captured.damage(),captured));
            check((dodge?defender.requestDodge(true):defender.setGuarding(true))==ActionResult.ACCEPTED,"a later fresh defense can begin while the old damage is pending");
            attacker.tick();defender.tick();
            check(defender.phase()==(dodge?CombatPhase.DODGING:CombatPhase.GUARDING),"captured ordinary contact does not prematurely queue hit stun");
            near(attacker.stamina(),paid,"one-active-tick real contact keeps its full attack fee at natural completion");
            check(buffer.resolve(101,WeaponClashRules.defaults()).expired().isEmpty(),"ordinary contact waits for the return-contact window");
            check(buffer.resolve(102,WeaponClashRules.defaults()).expired().isEmpty(),"new three-tick return window includes the second following tick");
            var expired=buffer.resolve(103,WeaponClashRules.defaults()).expired();
            check(expired.size()==1&&expired.getFirst().value()==captured,"unanswered hit releases the exact captured outcome once");
            check(expired.getFirst().value().outcome()==HitOutcome.HIT,"new C or dodge cannot turn the prior contact into block, parry or dodge");
            near(expired.getFirst().value().damage(),6,"later defense cannot alter frozen incoming damage");
        }
    }

    private static void capturedDefenseAndCounters() {
        var attacker=actor();var defender=actor();attacker.requestAttack(cut("guard_contact",6,1));defender.setGuarding(true);
        double paid=attacker.stamina(),guardStamina=defender.stamina();
        var parried=CombatResolver.captureMelee(attacker,defender,2,0,0);
        check(parried.outcome()==HitOutcome.PARRIED&&attacker.phase()==CombatPhase.STUNNED,"a guard already present at contact resolves immediately");
        near(attacker.stamina(),paid,"immediate parry retains the real-contact attack fee");near(defender.stamina(),guardStamina-5,"immediate perfect guard charges its configured fee once");
        check(defender.counterRemainingTicks()==4,"immediate real parry awards the existing counter opportunity");
        attacker=actor();defender=actor();attacker.requestAttack(cut("dodge_contact",6,1));defender.requestDodge(true);
        paid=attacker.stamina();
        check(CombatResolver.captureMelee(attacker,defender,2,0,0).outcome()==HitOutcome.DODGED,"invulnerability already active at contact resolves immediately");
        attacker.tick();near(attacker.stamina(),paid+4,"a dodged attack has no real blade contact and retains its half-cost miss refund");
        check(!attacker.staminaCombatState(),"a wholly dodged outgoing attack does not create contact combat state");

        attacker=actor();defender=actor();attacker.perfectGuardSucceeded();
        check(attacker.aimedLeftSkill(cut("counter_contact",8,1),null)==ActionResult.ACCEPTED,"physical left attack commits its earned counter");
        near(attacker.attackDamageMultiplier(),1.5,"accepted counter multiplier is active before contact");
        check(attacker.counterRemainingTicks()==0,"accepted counter consumes the one opening");
        var captured=CombatResolver.captureMelee(attacker,defender,2,0,0);near(captured.damage(),12,"captured damage includes the committed counter multiplier");
        var counterContact=new WeaponClash.Contact<>(attacker.id(),defender.id(),attacker.snapshot().swingSequence(),100,captured.damage(),captured);
        attacker.tick();near(attacker.attackDamageMultiplier(),1,"completed attack clears its transient counter multiplier");
        near(captured.damage(),12,"completed attack cannot erase its frozen counter damage");
        defender.requestAttack(cut("counter_return",6,1));var response=CombatResolver.captureMelee(defender,attacker,2,0,0);
        var counterBuffer=new WeaponClash<HitResult>();counterBuffer.offer(counterContact);
        counterBuffer.offer(new WeaponClash.Contact<>(defender.id(),attacker.id(),defender.snapshot().swingSequence(),103,response.damage(),response));
        var counterPair=counterBuffer.resolve(103,WeaponClashRules.defaults()).pairs();
        check(counterPair.size()==1,"completed counter attack still pairs with a return at the inclusive three-tick limit");
        near(counterPair.getFirst().exchange().firstDamage(),3,"residual clash damage uses frozen twelve versus six minus three tolerance");
        var miss=actor();miss.requestAttack(cut("genuine_miss",6,1));paid=miss.stamina();miss.tick();
        near(miss.stamina(),paid+4,"an actual empty one-tick attack still receives the existing half-cost refund");
        check(!miss.staminaCombatState(),"genuine miss does not create combat state");
    }

    private static void realReturnContacts() {
        for(int gap:List.of(0,1,2,3,4)) for(boolean reverse:List.of(false,true)) {
            var first=actor();var second=actor();var cut=cut("return_contact",6,1);var buffer=new WeaponClash<HitResult>();
            first.requestAttack(cut);var outgoing=CombatResolver.captureMelee(first,second,2,0,0);
            var one=new WeaponClash.Contact<>(first.id(),second.id(),first.snapshot().swingSequence(),100,outgoing.damage(),outgoing);
            for(int elapsed=0;elapsed<gap;elapsed++){first.tick();second.tick();}
            check(second.phase()==CombatPhase.READY,"real ordinary capture leaves a short later return attack available");
            second.requestAttack(cut);var incoming=CombatResolver.captureMelee(second,first,2,0,0);
            var two=new WeaponClash.Contact<>(second.id(),first.id(),second.snapshot().swingSequence(),100+gap,incoming.damage(),incoming);
            if(reverse){buffer.offer(two);buffer.offer(one);}else{buffer.offer(one);buffer.offer(two);}
            var result=buffer.resolve(100+gap,WeaponClashRules.defaults());
            check(result.pairs().size()==(gap<=3?1:0),"captured one-tick attacks use real inclusive return gap "+gap+" order="+reverse);
            if(gap<=3)check(result.pairs().getFirst().exchange().winner()==0,"equal captured return attacks cancel within tolerance");
        }
    }

    private static void rearGuardContactKeepsReturnWindow() {
        var attacker=actor();var defender=actor();attacker.requestAttack(cut("rear_capture",6,1));defender.setGuarding(true);
        var captured=CombatResolver.captureMelee(attacker,defender,2,0,180);
        check(captured.outcome()==HitOutcome.HIT,"rear contact bypasses the current guard");
        defender.setGuarding(false);
        check(defender.requestAttack(cut("rear_return",6,1))==ActionResult.ACCEPTED,"ordinary rear-guard HIT must preserve the configured return opportunity");
    }

    private static void configuredWindowAndValidation() {
        for(int window:List.of(0,1,2,3,10)) {
            var buffer=new WeaponClash<String>();buffer.offer(contact(A,B,1,100,8));buffer.offer(contact(B,A,2,100+window,6));
            var result=buffer.resolve(100+window,new WeaponClashRules(window,1));
            check(result.pairs().size()==1,"configured inclusive window works at its exact boundary "+window);
            near(result.pairs().getFirst().exchange().firstDamage(),1,"configured damage tolerance replaces the default");
            buffer.offer(contact(A,B,3,200,8));
            check(buffer.resolve(200+window,new WeaponClashRules(window,1)).expired().size()==1,"configured unanswered contact expires exactly at its boundary");
        }
        for(double bad:List.of(-1.0,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) {
            rejects(()->WeaponClash.compare(bad,1,3),"invalid first damage rejected");
            rejects(()->WeaponClash.compare(1,bad,3),"invalid second damage rejected");
            rejects(()->WeaponClash.compare(1,1,bad),"invalid tolerance rejected");
            rejects(()->contact(A,B,1,10,bad),"invalid captured damage rejected");
        }
        rejects(()->contact(A,A,1,10,1),"self-contact rejected");
        rejects(()->contact(A,B,-1,10,1),"negative swing sequence rejected");
        rejects(()->contact(A,B,1,-1,1),"negative contact time rejected");
    }

    private static CombatActor actor() {
        var rules=new CombatRules(100,100,0,0,20,0,0,120,.25,10,2,5,4,10,2,120,12,0,6,20,6,3,true,false);
        var actor=new CombatActor(UUID.randomUUID(),rules);actor.requestDrawn();return actor;
    }

    private static void captureValidationAndGeometry() {
        var attacker=actor();var defender=actor();attacker.requestAttack(cut("capture_geometry",6,1));double paid=attacker.stamina();
        rejects(()->CombatResolver.captureMelee(null,defender,2,0,0),"capture rejects missing attacker");
        rejects(()->CombatResolver.captureMelee(attacker,null,2,0,0),"capture rejects missing defender");
        for(double value:List.of(-1.0,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY))
            rejects(()->CombatResolver.captureMelee(attacker,defender,value,0,0),"capture validates finite nonnegative distance");
        rejects(()->CombatResolver.captureMelee(attacker,defender,2,Double.NaN,0),"capture rejects invalid attacker angle");
        rejects(()->CombatResolver.captureMelee(attacker,defender,2,0,Double.NaN),"capture rejects invalid defender angle");
        check(CombatResolver.captureMelee(attacker,defender,4,0,0).outcome()==HitOutcome.OUT_OF_RANGE,"captured contact still requires actual reach");
        check(CombatResolver.captureMelee(attacker,defender,2,90,0).outcome()==HitOutcome.OUTSIDE_ARC,"captured contact still requires the attack arc");
        check(!attacker.hasHit(defender.id())&&!attacker.staminaCombatState(),"invalid geometry neither claims a victim nor invents real contact");
        near(attacker.stamina(),paid,"invalid capture inputs cannot alter reserved resources");
        check(CombatResolver.captureMelee(attacker,defender,2,0,0).outcome()==HitOutcome.HIT,"failed geometry does not consume the later legal contact");
    }

    private static void compatibleContactsAndRemoval() {
        var buffer=new WeaponClash<String>(String::equals);
        buffer.offer(new WeaponClash.Contact<>(A,B,1,100,6,"overworld"));
        buffer.offer(new WeaponClash.Contact<>(B,A,2,100,6,"the_nether"));
        check(buffer.resolve(100,WeaponClashRules.defaults()).pairs().isEmpty(),"incompatible contact contexts cannot form a clash");
        check(buffer.resolve(103,WeaponClashRules.defaults()).expired().size()==2,"incompatible contacts still expire as ordinary hits");
        buffer.offer(new WeaponClash.Contact<>(A,B,3,200,6,"overworld"));
        buffer.offer(new WeaponClash.Contact<>(B,A,4,201,6,"overworld"));
        check(buffer.resolve(201,WeaponClashRules.defaults()).pairs().size()==1,"matching contact contexts can form a clash");
        buffer.offer(contact(A,B,5,300,6));buffer.offer(contact(A,C,6,300,6));buffer.offer(contact(B,A,7,300,6));
        var snapshot=buffer.contacts();
        boolean immutable=false;try{snapshot.clear();}catch(UnsupportedOperationException expected){immutable=true;}
        check(immutable,"contact inspection snapshot cannot mutate the pending buffer");
        buffer.removeSwing(A,5);
        check(buffer.size()==2&&snapshot.size()==3,"removing one swing leaves newer and reciprocal contacts while preserving the snapshot");
        check(buffer.contacts().stream().allMatch(c->!c.attacker().equals(A)||c.sequence()!=5),"removeSwing matches both attacker and sequence");
        var exact=buffer.contacts().getFirst();
        check(buffer.remove(exact)&&!buffer.remove(exact),"precise contact removal reports exactly one removal");
        check(buffer.size()==1,"precise contact removal preserves other targets and reciprocal swings");
    }

    private static void decimalToleranceBoundary() {
        for(double lower:List.of(.1,.3,1.1,2.6,4.2,6.6,9.7,19.9,123.45)) {
            double upper=lower+3;
            var exchange=WeaponClash.compare(upper,lower,3);
            check(exchange.winner()==0&&exchange.firstDamage()==0&&exchange.secondDamage()==0,"decimal damage at exactly three tolerance has no rounding residue");
            check(WeaponClash.compare(Math.nextUp(upper),lower,3).winner()==0,"one binary rounding step above the decimal boundary is balanced");
            check(WeaponClash.compare(upper+1e-10,lower,3).winner()==1,"a real 1e-10 advantage beyond tolerance remains damage");
            check(WeaponClash.compare(lower,upper+1e-10,3).winner()==-1,"tiny real advantage remains symmetric");
        }
        check(WeaponClash.compare(9.6,6.6,3).winner()==0,"9.6 against 6.6 is balanced");
        check(WeaponClash.compare(Math.nextUp(1.0),1,0).winner()==1,"zero tolerance preserves a real distinct representable damage value");
    }
    private static SkillDefinition cut(String id,double damage,int active) {
        return new SkillDefinition(id,id,"sword",SkillInput.LEFT,0,active,0,0,8,0,damage,3,90,.3,true,true,false,"","legacy");
    }
    private static void rejects(Runnable action,String message) {try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError(message);}
    private static WeaponClash.Contact<String> contact(UUID a,UUID b,long seq,long tick,double damage){return new WeaponClash.Contact<>(a,b,seq,tick,damage,a+"/"+seq);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
    private static void near(double actual,double expected,String message){check(Math.abs(actual-expected)<1e-8,message+" "+actual+" != "+expected);}
}
