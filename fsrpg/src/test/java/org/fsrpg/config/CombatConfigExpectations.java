package org.fsrpg.config;

import org.fsrpg.combat.CombatRules;
import org.fsrpg.combat.SwordCombatRules;

/** Historical migration tests compare every historical field; 0.12 sword opt-in has separate coverage. */
final class CombatConfigExpectations {
    private CombatConfigExpectations(){}
    static CombatRules legacyMode(CombatRules rules){
        var value=RpgConfig.JSON.toJsonTree(rules).getAsJsonObject();
        value.add("swordCombat",RpgConfig.JSON.toJsonTree(SwordCombatRules.legacy()));
        return RpgConfig.JSON.fromJson(value,CombatRules.class);
    }
}
