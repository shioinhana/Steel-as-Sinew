package org.fsrpg.combat;

import java.util.List;
import java.util.Map;

/** Sword-only action combat. Distances are blocks, times are server ticks. */
public record SwordCombatRules(boolean enabled, int parryWindupTicks, int parryActiveTicks,
        int parryRecoveryTicks, int parryCooldownTicks, double parryStaminaCost,
        int parryStunTicks, int returnWindowTicks, double chargedDamageMultiplier,
        boolean chargeSuperArmor, double steeringFraction, double moveSpeedMultiplier,
        List<String> heavyMobTypes, Map<String, Double> motionDistances) {
    public SwordCombatRules {
        SkillDefinition.checkTicks(parryWindupTicks,"sword parry windup",true);
        SkillDefinition.checkTicks(parryActiveTicks,"sword parry active",false);
        SkillDefinition.checkTicks(parryRecoveryTicks,"sword parry recovery",true);
        SkillDefinition.checkTicks(parryCooldownTicks,"sword parry cooldown",true);
        if ((long)parryWindupTicks+parryActiveTicks+parryRecoveryTicks>1200) throw new IllegalArgumentException("sword parry duration exceeds 1200 ticks");
        SkillDefinition.checkTicks(parryStunTicks,"sword parry stun",false);
        SkillDefinition.checkTicks(returnWindowTicks,"sword return window",false);
        SkillDefinition.checkNumber(parryStaminaCost,"sword parry cost",0,1000000);
        SkillDefinition.checkNumber(chargedDamageMultiplier,"sword charged multiplier",1,10);
        SkillDefinition.checkNumber(steeringFraction,"sword steering fraction",0,.35);
        SkillDefinition.checkNumber(moveSpeedMultiplier,"sword move speed multiplier",0,1);
        if(heavyMobTypes==null||heavyMobTypes.size()>128||motionDistances==null||motionDistances.size()>128)throw new IllegalArgumentException("invalid sword combat catalog");
        heavyMobTypes=List.copyOf(heavyMobTypes); motionDistances=Map.copyOf(motionDistances);
        for(String id:heavyMobTypes)if(id==null||!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("invalid heavy mob id");
        for(var e:motionDistances.entrySet()) {
            if(!e.getKey().matches("[a-z0-9_.:/-]{1,128}"))throw new IllegalArgumentException("invalid motion skill id");
            SkillDefinition.checkNumber(e.getValue(),"sword motion distance",0,3);
        }
    }
    public int parryDurationTicks(){return parryWindupTicks+parryActiveTicks+parryRecoveryTicks;}
    public static SwordCombatRules defaults(){return new SwordCombatRules(true,2,5,9,20,8,16,24,2,true,.20,.25,
            List.of("minecraft:vindicator","minecraft:piglin_brute","minecraft:iron_golem","minecraft:ravager"),
            Map.of("sword_left",.45,"rapier_downcut",.32,"rapier_crosscut",.40,"rapier_light_thrust",.25,
                    "rapier_lunge_finish",.85,"sword_thrust",.65,"rapier_riposte",.35,"rapier_reprise",.40,"sword_parry_return",.55));}
    /** Old constructors and untouched legacy JSON keep their original combat behavior. */
    public static SwordCombatRules legacy(){var r=defaults();return new SwordCombatRules(false,r.parryWindupTicks,r.parryActiveTicks,r.parryRecoveryTicks,r.parryCooldownTicks,r.parryStaminaCost,r.parryStunTicks,r.returnWindowTicks,r.chargedDamageMultiplier,r.chargeSuperArmor,r.steeringFraction,r.moveSpeedMultiplier,r.heavyMobTypes,r.motionDistances);}
}
