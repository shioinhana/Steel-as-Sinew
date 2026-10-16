package org.fsrpg.combat;

import java.util.UUID;

/** Pure authoritative resource coverage: level arithmetic, clamping and tick delay. */
public final class StaminaLevelTest {
    private static int checks;
    public static void main(String[] args) {
        var defaults = CombatRules.defaults();
        near(50, defaults.maxStaminaAtLevel(0), "level zero capacity");
        near(5, defaults.staminaRegenAtLevel(0), "level zero rate avoids log zero");
        for (int level : new int[]{1, 2, 3, 4, 9, 10, 25, 100, 1000, 1_000_000, Integer.MAX_VALUE}) {
            near(50 + Math.pow(level, .8) + 3 * Math.log(level), defaults.maxStaminaAtLevel(level), "unrounded capacity at " + level);
            near(Math.floor(5 + Math.sqrt(level) + Math.log(level)), defaults.staminaRegenAtLevel(level), "whole-expression floor at " + level);
            check(Double.isFinite(defaults.maxStaminaAtLevel(level)) && Double.isFinite(defaults.staminaRegenAtLevel(level)), "finite high level " + level);
        }
        check(defaults.maxStaminaAtLevel(2) != Math.floor(defaults.maxStaminaAtLevel(2)), "capacity keeps fractional part");
        rejects(() -> defaults.maxStaminaAtLevel(-1), "negative formula level rejected");
        rejects(() -> defaults.staminaRegenAtLevel(-1), "negative regen level rejected");

        var actor = new CombatActor(UUID.randomUUID(), defaults);
        near(50, actor.stamina(), "new actor starts full at level zero");
        actor.setResources(31.25, 40); actor.setPlayerLevel(100);
        near(31.25, actor.stamina(), "level up never fills");
        equal(100, actor.playerLevel(), "authoritative level saved");
        near(defaults.maxStaminaAtLevel(100), actor.maxStamina(), "actor caches same capacity");
        near(19, actor.staminaRegenPerSecond(), "actor caches floored rate");
        near(actor.maxStamina(), actor.snapshot().maxStamina(), "snapshot reports derived capacity");
        actor.setResources(200, 40); near(actor.maxStamina(), actor.stamina(), "resource setter uses level cap");
        actor.setPlayerLevel(1); near(51, actor.stamina(), "level down clips to new cap");
        actor.setPlayerLevel(0); near(50, actor.stamina(), "zero level clips safely");
        actor.setResources(12, 40); actor.setPlayerLevel(100); actor.restoreResources(1000, 3);
        near(actor.maxStamina(), actor.stamina(), "restore uses level cap"); near(43, actor.spirit(), "spirit restoration unchanged");
        actor.setResources(10, 10); actor.setPlayerLevel(1000); near(10, actor.stamina(), "repeat upgrade cannot heal");
        actor.setPlayerLevel(1000); near(10, actor.stamina(), "same level is idempotent");
        rejects(() -> actor.setPlayerLevel(-1), "negative actor level rejected"); equal(1000, actor.playerLevel(), "invalid level leaves state intact");
        actor.setPlayerLevel(100); actor.setResources(0, 40); ticks(actor, 20);
        near(19, actor.stamina(), "20 server ticks regenerate exactly one level rate"); near(41, actor.spirit(), "spirit rate is independent");
        check(actor.consumeStamina(5), "spend accepted"); double spent = actor.stamina();
        ticks(actor, 19); near(spent, actor.stamina(), "spend preserves 20 tick delay");
        actor.setPlayerLevel(4); actor.tick(); near(spent + 8.0 / 20, actor.stamina(), "level updates rate without resetting delay");
        actor.setResources(actor.maxStamina() - .001, 100); actor.tick(); near(actor.maxStamina(), actor.stamina(), "regen clips fractional maximum");

        var custom = new StaminaLevelScaling(2, 3, 4, 1.1, 5);
        actor.setPlayerLevel(9); actor.setResources(20, 100); actor.setRules(rules(10, 2.75, 0, custom));
        near(10 + 4 * Math.pow(9, 1.1) + 5 * Math.log(9), actor.maxStamina(), "reload uses retained level and new coefficients");
        near(Math.floor(2.75 + 2 * Math.sqrt(9) + 3 * Math.log(9)), actor.staminaRegenPerSecond(), "reload floors complete custom expression");
        near(20, actor.stamina(), "rules reload never fills");
        actor.setRules(rules(3, 2.75, 0, new StaminaLevelScaling(0, 0, 0, 0, 0)));
        near(3, actor.stamina(), "rules reload clips new capacity");
        near(2, actor.staminaRegenPerSecond(), "positive level floors base plus zero coefficients");
        actor.setPlayerLevel(0); near(2.75, actor.staminaRegenPerSecond(), "zero level uses configured base exactly");
        actor.setRules(defaults); actor.setPlayerLevel(Integer.MAX_VALUE);
        actor.setResources(actor.maxStamina(), 100); near(actor.maxStamina(), actor.stamina(), "high level resources are not limited by old fixed million cap");
        actor.consumeStamina(1); actor.restoreResources(actor.maxStamina(), 0); near(actor.maxStamina(), actor.stamina(), "high level restore clips consistently");
        double savedHighLevelStamina=actor.stamina();check(savedHighLevelStamina>1_000_000,"saved high-level fixture exceeds legacy API limit");
        var lowerLevelActor=new CombatActor(UUID.randomUUID(),defaults);
        lowerLevelActor.restoreSavedResources(savedHighLevelStamina,1_000_001);
        near(50,lowerLevelActor.stamina(),"higher-level saved stamina restores by clamping after death/offline downgrade");
        near(100,lowerLevelActor.spirit(),"saved spirit clips independently");
        lowerLevelActor.restoreSavedResources(Double.MAX_VALUE,Double.MAX_VALUE);
        near(50,lowerLevelActor.stamina(),"any finite saved resource is clipped without overflow");
        lowerLevelActor.setResources(12,13);
        for(double invalid:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            rejects(()->lowerLevelActor.restoreSavedResources(invalid,10),"invalid saved stamina rejected");
            rejects(()->lowerLevelActor.restoreSavedResources(10,invalid),"invalid saved spirit rejected");
            near(12,lowerLevelActor.stamina(),"invalid saved input leaves stamina unchanged");
            near(13,lowerLevelActor.spirit(),"invalid saved input leaves spirit unchanged");
        }
        rejects(()->lowerLevelActor.setResources(savedHighLevelStamina,0),"bounded live setter retains old limit semantics");

        rejects(() -> new StaminaLevelScaling(-1, 1, 1, .8, 3), "negative coefficient rejected");
        rejects(() -> new StaminaLevelScaling(1, Double.NaN, 1, .8, 3), "NaN coefficient rejected");
        rejects(() -> new StaminaLevelScaling(1, 1, Double.POSITIVE_INFINITY, .8, 3), "infinite coefficient rejected");
        rejects(() -> new StaminaLevelScaling(1, 1, 1, 4.0001, 3), "exponent bound enforced");
        var highest = rules(1_000_000, 1_000_000, 0, new StaminaLevelScaling(1_000_000, 1_000_000, 1_000_000, 4, 1_000_000));
        check(Double.isFinite(highest.maxStaminaAtLevel(Integer.MAX_VALUE)), "all valid configuration and level extremes remain finite");
        System.out.println("StaminaLevelTest passed: " + checks + " checks");
    }
    private static CombatRules rules(double max, double regen, int delay, StaminaLevelScaling scaling) {
        var d = CombatRules.defaults();
        return new CombatRules(max,d.maxSpirit(),regen,d.spiritRegenPerSecond(),delay,d.drawTicks(),d.sheathTicks(),
                d.guardArcDegrees(),d.guardDamageMultiplier(),d.guardStaminaCost(),d.perfectGuardTicks(),d.perfectGuardStaminaCost(),
                d.parryStunTicks(),d.guardBreakStunTicks(),d.hitStunTicks(),d.clashFacingArcDegrees(),d.dodgeDurationTicks(),
                d.dodgeInvulnerabilityStartTicks(),d.dodgeInvulnerabilityTicks(),d.dodgeCooldownTicks(),d.dodgeStaminaCost(),d.dodgeDistance(),
                d.dodgeCancelsRecovery(),d.allowAirDodge(),d.rapierTargetLock(),d.perfectGuardCounter(),scaling);
    }
    private static void ticks(CombatActor actor,int count) { for(int i=0;i<count;i++) actor.tick(); }
    private static void near(double a,double b,String m) { check(Math.abs(a-b)<=Math.max(1e-8,Math.abs(a)*1e-12),m+": "+a+" != "+b); }
    private static void equal(int a,int b,String m) { check(a==b,m); }
    private static void rejects(Runnable action,String m) { checks++;try{action.run();throw new AssertionError(m);}catch(IllegalArgumentException expected){} }
    private static void check(boolean value,String m) { checks++;if(!value)throw new AssertionError(m); }
}
