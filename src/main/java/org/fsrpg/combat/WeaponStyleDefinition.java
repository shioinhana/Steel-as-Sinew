package org.fsrpg.combat;

import java.util.List;
import java.util.Map;

/** Text-editable weapon stance, ordered follow-ups and phase-specific cancellation rules. */
public record WeaponStyleDefinition(String id, String label, String weaponGroup, boolean enabled,
                                    List<ComboStage> leftCombo, String rightSkill, GuardRules guard,
                                    GuardRules heavyGuard, Techniques techniques, CancelRules cancels, WeaponControls controls) {
    public WeaponStyleDefinition(String id,String label,String weaponGroup,boolean enabled,List<ComboStage> leftCombo,String rightSkill,GuardRules guard,GuardRules heavyGuard,Techniques techniques,CancelRules cancels) {
        this(id,label,weaponGroup,enabled,leftCombo,rightSkill,guard,heavyGuard,techniques,cancels,WeaponControls.defaults());
    }
    public WeaponStyleDefinition(String id, String label, String weaponGroup, boolean enabled,
                                 List<ComboStage> leftCombo, String rightSkill, GuardRules guard,
                                 GuardRules heavyGuard, Techniques techniques) {
        this(id,label,weaponGroup,enabled,leftCombo,rightSkill,guard,heavyGuard,techniques,CancelRules.defaults());
    }
    public WeaponStyleDefinition(String id, String label, String weaponGroup, boolean enabled,
                                 List<ComboStage> leftCombo, String rightSkill, GuardRules guard) {
        this(id, label, weaponGroup, enabled, leftCombo, rightSkill, guard, null, Techniques.none());
    }
    public WeaponStyleDefinition {
        SkillDefinition.requireText(id, "style id");
        if (!id.matches("[a-z0-9_]+")) throw new IllegalArgumentException("invalid style id " + id);
        SkillDefinition.requireText(label, "style label");
        SkillDefinition.requireText(weaponGroup, "style weaponGroup");
        if (!weaponGroup.matches("[a-z0-9_]+")) throw new IllegalArgumentException("invalid style weaponGroup");
        if (leftCombo == null || leftCombo.size() > 16) throw new IllegalArgumentException("leftCombo must contain at most 16 stages");
        leftCombo = List.copyOf(leftCombo);
        if (enabled && leftCombo.isEmpty()) throw new IllegalArgumentException("enabled styles require a leftCombo");
        if (rightSkill == null || (!rightSkill.isEmpty() && !rightSkill.matches("[a-z0-9_.:-]+")))
            throw new IllegalArgumentException("rightSkill must be a skill id or an empty string");
        if (guard == null) throw new IllegalArgumentException("style guard rules are required");
        if (techniques == null) techniques = Techniques.none();
        if (cancels == null) cancels = CancelRules.defaults();
        if (controls == null) controls = WeaponControls.defaults();
    }

    /** Keep presentation routing stable when a weapon defines more than one named stance. */
    public String presentationStyleId() {
        if (controls.dual()) return "dual_blade";
        if (controls.spear()) return "spear";
        if (weaponGroup.equals("bow")) return "bow";
        if (weaponGroup.equals("sword") || weaponGroup.equals("rapier")) return "rapier";
        return id;
    }

    /** Cross-file validation, also repeated by the actor when accepting a new combo. */
    public void validateSkills(Map<String, SkillDefinition> skills) {
        if (skills == null) throw new IllegalArgumentException("skill catalog is required");
        controls.validate(skills,weaponGroup);
        for (int i = 0; i < leftCombo.size(); i++) {
            ComboStage stage = leftCombo.get(i);
            SkillDefinition skill = skills.get(stage.skillId());
            if (skill == null) throw new IllegalArgumentException("style " + id + " references unknown skill " + stage.skillId());
            if (skill.input() != SkillInput.LEFT) throw new IllegalArgumentException("leftCombo skill must use LEFT: " + skill.id());
        }
        if (!rightSkill.isEmpty()) {
            SkillDefinition right = skills.get(rightSkill);
            if (right == null) throw new IllegalArgumentException("style " + id + " references unknown rightSkill " + rightSkill);
            if (right.input() != SkillInput.RIGHT && right.input() != SkillInput.SPECIAL)
                throw new IllegalArgumentException("rightSkill must use RIGHT or SPECIAL: " + rightSkill);
        }
        for (String id : List.of(techniques.riposteSkill(), techniques.repriseSkill())) {
            if (id.isEmpty()) continue;
            SkillDefinition skill = skills.get(id);
            if (skill == null || skill.input() != SkillInput.RIGHT)
                throw new IllegalArgumentException("technique must reference a RIGHT skill: " + id);
            if (!skill.weapon().equals("any") && !skill.weapon().equals(weaponGroup))
                throw new IllegalArgumentException("technique weapon group differs from style: " + id);
            if (id.equals(rightSkill)) throw new IllegalArgumentException("conditional technique cannot also be the default rightSkill");
        }
    }

    /** One-use server windows; these never grant damage or a free attack on their own. */
    public record Techniques(String riposteSkill, int riposteWindowTicks, String repriseSkill, int repriseWindowTicks) {
        public Techniques {
            for (String id : new String[]{riposteSkill, repriseSkill})
                if (id == null || (!id.isEmpty() && !id.matches("[a-z0-9_.:-]{1,128}")))
                    throw new IllegalArgumentException("technique skill must be a stable id or empty string");
            SkillDefinition.checkTicks(riposteWindowTicks, "riposteWindowTicks", true);
            SkillDefinition.checkTicks(repriseWindowTicks, "repriseWindowTicks", true);
            if (riposteSkill.isEmpty() != (riposteWindowTicks == 0) || repriseSkill.isEmpty() != (repriseWindowTicks == 0))
                throw new IllegalArgumentException("technique id and positive window must be configured together");
            if (!riposteSkill.isEmpty() && riposteSkill.equals(repriseSkill))
                throw new IllegalArgumentException("riposte and reprise must use distinct skill ids");
        }
        public static Techniques none() { return new Techniques("", 0, "", 0); }
        public boolean conditional(String id) { return !id.isEmpty() && (id.equals(riposteSkill) || id.equals(repriseSkill)); }
    }

    /** The four old timing fields are accepted for file compatibility only, never used as input gates. */
    public record ComboStage(String skillId, int gateStartTick, int gateEndTick, int chainTick, int bufferTicks) {
        public ComboStage(String skillId) { this(skillId,0,0,0,0); }
        public ComboStage {
            SkillDefinition.requireText(skillId, "combo skillId");
            if (!skillId.matches("[a-z0-9_.:-]+")) throw new IllegalArgumentException("invalid combo skillId");
            SkillDefinition.checkTicks(gateStartTick, "gateStartTick", true);
            SkillDefinition.checkTicks(gateEndTick, "gateEndTick", true);
            SkillDefinition.checkTicks(chainTick, "chainTick", true);
            SkillDefinition.checkTicks(bufferTicks, "bufferTicks", true);
        }
    }

    /** Player cancels never shorten ACTIVE; parry/hit reactions remain authoritative interrupts. */
    public record CancelRules(boolean windupAttack, boolean windupGuard, boolean windupDodge,
                              boolean recoveryAttack, boolean recoveryGuard, boolean recoveryDodge) {
        public static CancelRules defaults() { return new CancelRules(true,true,true,true,true,true); }
    }

    public record GuardRules(double arcDegrees, double damageMultiplier, double staminaCost,
                             int perfectGuardTicks, double perfectGuardStaminaCost, int parryStunTicks,
                             int guardBreakStunTicks, int repeatHitWindowTicks, int maxBlockedHits,
                             boolean heavyBreaksGuard, boolean perfectGuardRequiresBlockable) {
        public GuardRules {
            SkillDefinition.checkNumber(arcDegrees, "style guard arcDegrees", 0, 360);
            SkillDefinition.checkNumber(damageMultiplier, "style guard damageMultiplier", 0, 1);
            SkillDefinition.checkNumber(staminaCost, "style guard staminaCost", 0, 1_000_000);
            SkillDefinition.checkTicks(perfectGuardTicks, "style perfectGuardTicks", true);
            SkillDefinition.checkNumber(perfectGuardStaminaCost, "style perfectGuardStaminaCost", 0, 1_000_000);
            SkillDefinition.checkTicks(parryStunTicks, "style parryStunTicks", true);
            SkillDefinition.checkTicks(guardBreakStunTicks, "style guardBreakStunTicks", true);
            SkillDefinition.checkTicks(repeatHitWindowTicks, "style repeatHitWindowTicks", true);
            if (maxBlockedHits < 1 || maxBlockedHits > 128) throw new IllegalArgumentException("maxBlockedHits must be 1..128");
        }

        public static GuardRules from(CombatRules rules) {
            return new GuardRules(rules.guardArcDegrees(), rules.guardDamageMultiplier(), rules.guardStaminaCost(),
                    rules.perfectGuardTicks(), rules.perfectGuardStaminaCost(), rules.parryStunTicks(),
                    rules.guardBreakStunTicks(), 0, 128, false, false);
        }
    }
}
