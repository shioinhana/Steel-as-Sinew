package org.fsrpg.combat;

import java.util.List;
import java.util.Map;

/** Optional style extension; old styles retain their ordered combo behavior. */
public record WeaponControls(String mode, String leftHandLeft, String rightHandLeft,
        String rightHandRight, String leftHandRight, String sweepSkill, double spearRadius, Integer comboResetTicks) {
    public WeaponControls(String mode, String leftHandLeft, String rightHandLeft,
            String rightHandRight, String leftHandRight, String sweepSkill, double spearRadius) {
        this(mode,leftHandLeft,rightHandLeft,rightHandRight,leftHandRight,sweepSkill,spearRadius,12);
    }
    public WeaponControls {
        if (comboResetTicks == null) comboResetTicks=12;
        if(!List.of("ordered","dual","legacy_dual","spear").contains(mode)) throw new IllegalArgumentException("unknown control mode");
        for(String id:List.of(leftHandLeft,rightHandLeft,rightHandRight,leftHandRight,sweepSkill))
            if(!id.isEmpty()&&!id.matches("[a-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("invalid control skill id");
        SkillDefinition.checkNumber(spearRadius,"spearRadius",.02,.6);
        SkillDefinition.checkTicks(comboResetTicks,"comboResetTicks",true);
        if((mode.equals("dual") || mode.equals("legacy_dual")) && (List.of(leftHandLeft,rightHandLeft,rightHandRight,leftHandRight).stream().anyMatch(String::isEmpty)
                || java.util.Set.of(leftHandLeft,rightHandLeft,rightHandRight,leftHandRight).size()!=4)) throw new IllegalArgumentException("dual requires four distinct skills");
        if(mode.equals("spear") && sweepSkill.isEmpty()) throw new IllegalArgumentException("spear requires sweepSkill");
    }
    public static WeaponControls defaults() { return new WeaponControls("ordered","","","","","",.18); }
    public boolean dual() { return mode.equals("dual") || mode.equals("legacy_dual"); }
    public boolean legacyDual() { return mode.equals("legacy_dual"); }
    public boolean spear() { return mode.equals("spear"); }
    /** Direction chooses a cut, never an exhausted-input rejection; the idle-to-clip blend recovers the hand. */
    public String directionalSkill(SkillInput input, int leftRest, int rightRest, boolean lastHandLeft) {
        if (input != SkillInput.LEFT && input != SkillInput.RIGHT) return "";
        if (!legacyDual()) return input == SkillInput.LEFT ? leftHandLeft : rightHandRight;
        if (input == SkillInput.LEFT) {
            if (leftRest != 0) return leftHandLeft;
            if (rightRest != 0) return rightHandLeft;
            return lastHandLeft ? rightHandLeft : leftHandLeft;
        }
        if (rightRest != 1) return rightHandRight;
        if (leftRest != 1) return leftHandRight;
        return lastHandLeft ? rightHandRight : leftHandRight;
    }
    public boolean leftHand(String id) { return dual() && (id.equals(leftHandLeft)||id.equals(leftHandRight)); }
    public int destination(String id) { return id.equals(leftHandLeft)||id.equals(rightHandLeft)?0:id.equals(rightHandRight)||id.equals(leftHandRight)?1:-1; }
    public void validate(Map<String,SkillDefinition> skills,String group) {
        for(String id:List.of(leftHandLeft,rightHandLeft,rightHandRight,leftHandRight,sweepSkill)) if(!id.isEmpty()) {
            var s=skills.get(id); if(s==null||!s.weapon().equals(group)) throw new IllegalArgumentException("control references missing/incompatible skill "+id);
            if(dual() && s.input()!=(destination(id)==0?SkillInput.LEFT:SkillInput.RIGHT)) throw new IllegalArgumentException("dual cut input differs from direction");
        }
    }
}
