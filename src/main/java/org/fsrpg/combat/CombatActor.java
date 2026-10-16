package org.fsrpg.combat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntUnaryOperator;

/** Server-owned state machine. Collect all this tick's attack intents, then call tick exactly once. */
public final class CombatActor {
    private final UUID id;
    private CombatRules rules;
    private CombatPhase phase = CombatPhase.SHEATHED;
    private boolean drawn;
    private long tick;
    private long swingSequence;
    private int phaseRemaining;
    private int skillElapsed;
    private long guardStarted;
    private long counterUntil;
    private double attackDamageMultiplier = 1;
    private boolean leftIntent, queuedLeftIntent;
    private int dodgeElapsed;
    private CombatRules dodgeRules;
    private long dodgeReadyAt;
    private long staminaRegenAt;
    private long spiritRegenAt;
    private double stamina;
    private double spirit;
    private int pendingHitStun;
    private SkillDefinition skill;
    private WeaponStyleDefinition style;
    private WeaponStyleDefinition comboStyle;
    private int comboStage = -1;
    private SkillDefinition queuedSkill;
    private AttackAim inputAim, attackAim, queuedAim;
    private int leftRest = -1, rightRest = -1;
    private int leftSwingStreak, rightSwingStreak;
    private IntUnaryOperator legacyDualIntervalAdjustment = IntUnaryOperator.identity();
    private long lastDualEnd;
    private boolean chargeArmed, chargeHeld, chargeParryEnabled, chargeParried, chargeLungePending;
    private int chargeElapsed, chargeFullTicks, chargeBreakCooldown;
    public int leftSide() { return style != null && style.controls().legacyDual() ? leftRest : -1; }
    public int rightSide() { return style != null && style.controls().legacyDual() ? rightRest : -1; }
    public record ChargeSnapshot(boolean active, boolean holding, int elapsedTicks, boolean fullyCharged, boolean parried) {}
    public ChargeSnapshot chargeSnapshot() { return new ChargeSnapshot(chargeArmed, chargeHeld, chargeElapsed,
            chargeArmed && (chargeParried || chargeElapsed >= chargeFullTicks), chargeParried); }
    public boolean chargedParried() { return chargeArmed && chargeParried; }
    public boolean consumeChargeLunge() { boolean result=chargeLungePending; chargeLungePending=false; return result; }
    public AttackAim attackAim() { return attackAim; }
    /** Server adapter may track its validated rapier target through preparation and contact. */
    public void trackRapierAim(AttackAim aim) {
        if (style == null || !style.presentationStyleId().equals("rapier")) return;
        if (skill != null) attackAim = aim;
        if (queuedSkill != null) queuedAim = aim;
    }
    public ComboInputResult aimedInput(SkillInput input, Map<String,SkillDefinition> skills, AttackAim aim) {
        inputAim=aim; try { return requestStyledAttack(input,skills); } finally { inputAim=null; }
    }
    public ActionResult aimedSkill(SkillDefinition skill, AttackAim aim) {
        inputAim=aim; try { return requestAttack(skill); } finally { inputAim=null; }
    }
    /** A validated physical left click; named skills and right-hand routing do not invent this intent. */
    public ActionResult aimedLeftSkill(SkillDefinition skill, AttackAim aim) {
        boolean previous = leftIntent;
        inputAim = aim; leftIntent = true;
        try { return requestAttack(skill); } finally { inputAim = null; leftIntent = previous; }
    }
    private boolean dual() { return style!=null && style.controls().dual(); }
    private int queuedStage;
    private WeaponStyleDefinition queuedStyle;
    private ComboInputStatus lastComboStatus = ComboInputStatus.COMBO_FINISHED;
    private ActionResult lastComboRejection = ActionResult.ACCEPTED;
    private final Deque<SkillStart> startedSkills = new ArrayDeque<>();
    private long lastGuardContactTick;
    private int guardContacts;
    private boolean heavyGuard;
    private boolean guardHeld;
    private long riposteUntil, repriseUntil;
    private final Map<String, Long> skillReadyAt = new HashMap<>();
    private final Set<UUID> swingVictims = new HashSet<>();

    public CombatActor(UUID id, CombatRules rules) {
        if (id == null || rules == null) throw new IllegalArgumentException("id and rules are required");
        this.id = id;
        this.rules = rules;
        stamina = rules.maxStamina();
        spirit = rules.maxSpirit();
    }

    public UUID id() { return id; }
    public CombatRules rules() { return rules; }
    public CombatPhase phase() { return phase; }
    public boolean drawn() { return drawn; }
    public SkillDefinition activeSkill() { return phase == CombatPhase.ACTIVE ? skill : null; }
    public SkillDefinition currentSkill() { return skill; }
    public double stamina() { return stamina; }
    public double spirit() { return spirit; }
    public WeaponStyleDefinition style() { return style; }
    public int counterRemainingTicks() { return drawn ? (int)Math.max(0, counterUntil - tick) : 0; }
    public double attackDamageMultiplier() { return skill == null ? 1 : attackDamageMultiplier; }

    /** Applied at acceptance, after the legacy same-hand gate and before any minimum interval clamp. */
    public void setLegacyDualIntervalAdjustment(IntUnaryOperator adjustment) {
        legacyDualIntervalAdjustment = java.util.Objects.requireNonNull(adjustment, "interval adjustment");
    }

    public ActionResult setStyle(WeaponStyleDefinition replacement) {
        if (replacement == null) throw new IllegalArgumentException("style is required");
        if (!replacement.enabled()) return ActionResult.STYLE_DISABLED;
        boolean sameStyle = style != null && style.id().equals(replacement.id());
        if (!sameStyle && phase != CombatPhase.SHEATHED && phase != CombatPhase.READY) return ActionResult.BUSY;
        if (style != null && !style.techniques().equals(replacement.techniques())) clearTechniques();
        style = replacement;
        if (!sameStyle) { resetCombo(); clearTechniques(); clearCounter(); heavyGuard = guardHeld = false; resetDualHandsAndStreaks(); }
        return ActionResult.ACCEPTED;
    }

    public WeaponStyleDefinition.GuardRules guardRules() {
        return style == null ? WeaponStyleDefinition.GuardRules.from(rules)
                : heavyGuard && style.heavyGuard() != null ? style.heavyGuard() : style.guard();
    }
    public boolean heavyGuard() { return phase == CombatPhase.GUARDING && heavyGuard; }
    public TechniqueSnapshot techniqueSnapshot() {
        if (!drawn || style == null) return new TechniqueSnapshot("", "", 0);
        var techniques = style.techniques();
        if (riposteUntil > tick && !techniques.riposteSkill().isEmpty())
            return new TechniqueSnapshot(techniques.riposteSkill(), "RIPOSTE", (int) (riposteUntil - tick));
        if (repriseUntil > tick && !techniques.repriseSkill().isEmpty())
            return new TechniqueSnapshot(techniques.repriseSkill(), "REPRISE", (int) (repriseUntil - tick));
        return new TechniqueSnapshot("", "", 0);
    }
    public void perfectGuardSucceeded() {
        counterUntil = tick + rules.perfectGuardCounter().windowTicks();
        if (style != null && !heavyGuard && !style.techniques().riposteSkill().isEmpty())
            riposteUntil = tick + style.techniques().riposteWindowTicks();
    }
    private void clearTechniques() { riposteUntil = repriseUntil = 0; }
    private void clearCounter() { counterUntil = 0; attackDamageMultiplier = 1; }

    public List<SkillStart> drainStartedSkills() {
        List<SkillStart> result = List.copyOf(startedSkills);
        startedSkills.clear();
        return result;
    }

    public ComboSnapshot comboSnapshot() {
        boolean active = comboStyle != null && comboStage >= 0;
        var gate = active ? comboStyle.leftCombo().get(comboStage) : null;
        return new ComboSnapshot(style == null ? "" : style.id(), active ? comboStage + 1 : 0,
                active ? comboStyle.leftCombo().size() : style == null ? 0 : style.leftCombo().size(), queuedSkill != null,
                0, active ? skill.durationTicks() : 0,
                active ? skill.windupTicks() + skill.activeTicks() : 0, active ? skillElapsed : 0,
                lastComboStatus.name(), lastComboRejection.name(), queuedSkill == null ? "" : queuedSkill.id());
    }

    public void setRules(CombatRules replacement) {
        if (replacement == null) throw new IllegalArgumentException("rules are required");
        rules = replacement;
        clearCounter();
        stamina = Math.min(stamina, rules.maxStamina());
        spirit = Math.min(spirit, rules.maxSpirit());
    }

    public void setResources(double stamina, double spirit) {
        SkillDefinition.checkNumber(stamina, "stamina", 0, 1_000_000);
        SkillDefinition.checkNumber(spirit, "spirit", 0, 1_000_000);
        this.stamina = Math.min(stamina, rules.maxStamina());
        this.spirit = Math.min(spirit, rules.maxSpirit());
    }

    public void restoreResources(double staminaAmount, double spiritAmount) {
        SkillDefinition.checkNumber(staminaAmount, "staminaAmount", 0, 1_000_000);
        SkillDefinition.checkNumber(spiritAmount, "spiritAmount", 0, 1_000_000);
        stamina = Math.min(rules.maxStamina(), stamina + staminaAmount);
        spirit = Math.min(rules.maxSpirit(), spirit + spiritAmount);
    }

    public boolean consumeStamina(double amount) {
        SkillDefinition.checkNumber(amount, "stamina cost", 0, 1_000_000);
        if (stamina + 1e-9 < amount) return false;
        stamina = Math.max(0, stamina - amount);
        if (amount > 0) staminaRegenAt = tick + rules.resourceRegenDelayTicks();
        return true;
    }

    public boolean consumeSpirit(double amount) {
        SkillDefinition.checkNumber(amount, "spirit cost", 0, 1_000_000);
        if (spirit + 1e-9 < amount) return false;
        spirit = Math.max(0, spirit - amount);
        if (amount > 0) spiritRegenAt = tick + rules.resourceRegenDelayTicks();
        return true;
    }

    public ActionResult toggleDrawn() {
        if (phase != CombatPhase.SHEATHED && phase != CombatPhase.READY) return ActionResult.BUSY;
        if (drawn) {
            phase = CombatPhase.SHEATHING;
            phaseRemaining = rules.sheathTicks();
            if (phaseRemaining == 0) finishSheath();
        } else {
            phase = CombatPhase.DRAWING;
            phaseRemaining = rules.drawTicks();
            if (phaseRemaining == 0) finishDraw();
        }
        return ActionResult.ACCEPTED;
    }

    /** Idempotent server intent used by automatic equipment drawing. Never sheath a drawn weapon. */
    public ActionResult requestDrawn() {
        if (drawn || phase == CombatPhase.DRAWING) return ActionResult.ACCEPTED;
        if (phase != CombatPhase.SHEATHED) return ActionResult.BUSY;
        phase = CombatPhase.DRAWING;
        phaseRemaining = rules.drawTicks();
        if (phaseRemaining == 0) finishDraw();
        return ActionResult.ACCEPTED;
    }

    /** Equipment removal/death is a forced interrupt, including an active hit window. */
    public void forceSheathed() {
        clearAttack();
        clearCounter();
        resetDualHandsAndStreaks();
        clearTechniques();
        startedSkills.clear();
        inputAim = null;
        pendingHitStun = 0;
        heavyGuard = false;
        guardHeld = false;
        dodgeElapsed = 0;
        phaseRemaining = 0;
        drawn = false;
        phase = CombatPhase.SHEATHED;
    }

    public ActionResult requestAttack(SkillDefinition requested) {
        return requestNext(requested, 0, style).rejection();
    }

    /** Legacy thrust: cooldown begins when the hit is committed, cancellation stays free. */
    public ActionResult beginChargedAttack(SkillDefinition requested, AttackAim aim, boolean hold,
                                          boolean canParry, int fullTicks, int breakCooldown) {
        if (phase != CombatPhase.READY && phase != CombatPhase.GUARDING && phase != CombatPhase.RECOVERY)
            return ActionResult.BUSY;
        SkillDefinition.checkTicks(fullTicks,"full charge ticks",false);
        SkillDefinition.checkTicks(breakCooldown,"charge break cooldown",true);
        ActionResult result=aimedSkill(requested,aim);
        if(result==ActionResult.ACCEPTED && skill==requested) {
            chargeArmed=true; chargeHeld=hold; chargeParryEnabled=canParry;
            chargeElapsed=0; chargeFullTicks=fullTicks; chargeBreakCooldown=breakCooldown;
            skillReadyAt.remove(requested.id());
            if(phase==CombatPhase.ACTIVE) commitCharge();
        }
        return result;
    }
    public ActionResult releaseChargedAttack() {
        if(!chargeArmed || !chargeHeld || phase!=CombatPhase.WINDUP) return ActionResult.ACCEPTED;
        chargeHeld=false; skillElapsed=skill.windupTicks(); commitCharge(); updateAttackPhase();
        return ActionResult.ACCEPTED;
    }
    /** Opening a GUI cancels uncommitted preparation, never releases or interrupts a contact window. */
    public boolean cancelChargedPreparation() {
        if (!chargeArmed || phase != CombatPhase.WINDUP) return false;
        clearAttack(); phase=CombatPhase.READY; phaseRemaining=0;
        return true;
    }
    /** 0 means no interception, 1 blocks the hit, 2 is full-charge guard break and the hit proceeds. */
    public int receiveChargedParry() {
        if(!chargeArmed || !chargeParryEnabled || phase!=CombatPhase.WINDUP) return 0;
        if(chargeParried || chargeElapsed>=chargeFullTicks) {
            String id=skill.id(); int cooldown=chargeBreakCooldown;
            stun(1); skillReadyAt.put(id,tick+cooldown); return 2;
        }
        chargeParried=true; chargeElapsed=chargeFullTicks;pendingHitStun=0; return 1;
    }
    private void commitCharge() {
        if(!chargeArmed) return;
        chargeHeld=false; chargeLungePending=true;
        if(!chargeParried) skillReadyAt.put(skill.id(),tick+skill.cooldownTicks());
    }

    private ActionResult startAttack(SkillDefinition requested, boolean chained, int stage, String styleId) {
        if (requested == null) throw new IllegalArgumentException("skill is required");
        if (!drawn) return ActionResult.NOT_DRAWN;
        boolean allowedPhase = phase == CombatPhase.READY || phase == CombatPhase.GUARDING
                || chained && (phase == CombatPhase.WINDUP || phase == CombatPhase.RECOVERY);
        if (!allowedPhase || pendingHitStun > 0)
            return ActionResult.BUSY;
        if (style != null && style.techniques().conditional(requested.id())
                && !techniqueSnapshot().skillId().equals(requested.id())) return ActionResult.TECHNIQUE_NOT_READY;
        if (cooldownRemaining(requested.id()) > 0) return ActionResult.COOLDOWN;
        if (stamina + 1e-9 < requested.staminaCost()) return ActionResult.INSUFFICIENT_STAMINA;
        if (spirit + 1e-9 < requested.spiritCost()) return ActionResult.INSUFFICIENT_SPIRIT;
        SkillDefinition timed = legacyDualTiming(requested);
        boolean counter = leftIntent && counterRemainingTicks() > 0;
        consumeStamina(requested.staminaCost());
        consumeSpirit(requested.spiritCost());
        // Any accepted attack commits the opening; a rejected/buffered input never spends it.
        clearTechniques();
        heavyGuard = false;
        resetCombo();
        skill = timed; attackAim=inputAim;
        attackDamageMultiplier = counter ? rules.perfectGuardCounter().damageMultiplier() : 1;
        if (counter) counterUntil = 0;
        chargeArmed=chargeHeld=chargeParryEnabled=chargeParried=chargeLungePending=false; chargeElapsed=0;
        if(style!=null && style.controls().legacyDual()) {
            var controls=style.controls(); int destination=controls.destination(requested.id());
            if(destination>=0) {
                if(controls.leftHand(requested.id())) {
                    leftRest=destination; leftSwingStreak=Math.min(2,leftSwingStreak+1); rightSwingStreak=0;
                } else {
                    rightRest=destination; rightSwingStreak=Math.min(2,rightSwingStreak+1); leftSwingStreak=0;
                }
            }
            if(requested.id().equals("dual_combined")) leftRest=rightRest=-1;
        }
        skillElapsed = 0;
        swingSequence++;
        swingVictims.clear();
        // Cooldown is measured from acceptance; action phases impose their own separate lock.
        skillReadyAt.put(requested.id(), tick + requested.cooldownTicks());
        updateAttackPhase();
        startedSkills.addLast(new SkillStart(requested.id(), swingSequence, stage, styleId));
        return ActionResult.ACCEPTED;
    }

    private SkillDefinition legacyDualTiming(SkillDefinition requested) {
        if (style == null || !style.controls().legacyDual() || !requested.weapon().equals(style.weaponGroup()))
            return requested;
        var controls = style.controls();
        int streak = controls.leftHand(requested.id()) ? leftSwingStreak : rightSwingStreak;
        // Old 0.1.1: first two accepted swings on one hand use 9 ticks; third and later use 12.
        // Keep configured authored timing, adding the legacy three-tick penalty before body reduction.
        int penalty = controls.destination(requested.id()) >= 0 && streak >= 2 ? 3 : 0;
        int gate = legacyDualIntervalAdjustment.applyAsInt(Math.min(72_000,
                requested.windupTicks() + requested.activeTicks() + penalty));
        SkillDefinition.checkTicks(gate, "adjusted dual interval", false);
        int active = Math.max(1, gate - requested.windupTicks());
        if (active == requested.activeTicks()) return requested;
        return new SkillDefinition(requested.id(), requested.label(), requested.weapon(), requested.input(),
                requested.windupTicks(), active, requested.recoveryTicks(), requested.cooldownTicks(),
                requested.staminaCost(), requested.spiritCost(), requested.damage(), requested.reach(),
                requested.arcDegrees(), requested.knockback(), requested.blockable(), requested.parryable(),
                requested.clashable(), requested.model(), requested.action());
    }

    private WeaponStyleDefinition.CancelRules cancels() {
        return style == null ? WeaponStyleDefinition.CancelRules.defaults() : style.cancels();
    }

    /** Same-button follow-ups queue one next stage; no timing gate rejects an early/late click. */
    public ComboInputResult requestStyledAttack(SkillInput input, Map<String, SkillDefinition> skills) {
        boolean previous = leftIntent;
        leftIntent = input == SkillInput.LEFT;
        try { return styledAttack(input, skills); } finally { leftIntent = previous; }
    }

    private ComboInputResult styledAttack(SkillInput input, Map<String, SkillDefinition> skills) {
        if (style == null || !style.enabled()) return comboResult(ComboInputStatus.REJECTED, ActionResult.STYLE_DISABLED, 0, "");
        if (input == null || skills == null) throw new IllegalArgumentException("input and skill catalog are required");
        style.validateSkills(skills);
        if(dual() && (input==SkillInput.LEFT || input==SkillInput.RIGHT)) {
            var c=style.controls();
            // A click always belongs to its matching hand, regardless of previous cuts.
            // Cross-hand IDs remain available to named skills and existing quest/config references.
            String id=input==SkillInput.LEFT?c.leftHandLeft():c.rightHandRight();
            if(c.legacyDual()) {
                int destination=input==SkillInput.LEFT?0:1;
                if(input==SkillInput.LEFT) id=leftRest!=destination?c.leftHandLeft():rightRest!=destination?c.rightHandLeft():"";
                else id=rightRest!=destination?c.rightHandRight():leftRest!=destination?c.leftHandRight():"";
                if(id.isEmpty()) return comboResult(ComboInputStatus.REJECTED,ActionResult.UNSUPPORTED_INPUT,0,"");
            }
            var requested=skills.get(id);
            if(phase==CombatPhase.WINDUP && skill!=null && skill.input()==input) return queueAttack(requested,0,style);
            return requestNext(requested,0,style);
        }
        if (input == SkillInput.RIGHT) {
            String opening = techniqueSnapshot().skillId();
            SkillDefinition right = skills.get(opening.isEmpty() ? style.rightSkill() : opening);
            if (right == null) return comboResult(ComboInputStatus.REJECTED, ActionResult.SKILL_NOT_FOUND, 0, style.rightSkill());
            return requestNext(right,0,style);
        }
        if (input != SkillInput.LEFT) return comboResult(ComboInputStatus.REJECTED, ActionResult.UNSUPPORTED_INPUT, 0, "");
        boolean continuing = comboStyle != null && skill != null;
        var owner = continuing ? comboStyle : style;
        int next = continuing ? (comboStage + 1) % owner.leftCombo().size() : 0;
        SkillDefinition requested = skills.get(owner.leftCombo().get(next).skillId());
        // Repeated LEFT during windup expresses a follow-up, never skips the current strike.
        if (continuing && phase == CombatPhase.WINDUP) return queueAttack(requested,next+1,owner);
        return requestNext(requested,next+1,owner);
    }

    private ComboInputResult requestNext(SkillDefinition requested, int stage, WeaponStyleDefinition owner) {
        if (requested == null) return comboResult(ComboInputStatus.REJECTED,ActionResult.SKILL_NOT_FOUND,stage,"");
        if (!drawn) return comboResult(ComboInputStatus.REJECTED,ActionResult.NOT_DRAWN,stage,requested.id());
        if (pendingHitStun > 0) return comboResult(ComboInputStatus.REJECTED,ActionResult.BUSY,stage,requested.id());
        if (phase == CombatPhase.ACTIVE) return queueAttack(requested,stage,owner);
        boolean cancel = phase == CombatPhase.WINDUP && cancels().windupAttack()
                || phase == CombatPhase.RECOVERY && cancels().recoveryAttack();
        ActionResult result = startAttack(requested,cancel,stage,owner == null ? "" : owner.id());
        if (result == ActionResult.ACCEPTED && stage > 0) { comboStyle=owner; comboStage=stage-1; }
        return comboResult(result==ActionResult.ACCEPTED?ComboInputStatus.STARTED:ComboInputStatus.REJECTED,result,stage,requested.id());
    }

    private ComboInputResult queueAttack(SkillDefinition requested,int stage,WeaponStyleDefinition owner) {
        if (requested == null) return comboResult(ComboInputStatus.REJECTED,ActionResult.SKILL_NOT_FOUND,stage,"");
        if (pendingHitStun > 0 || !cancels().recoveryAttack()) return comboResult(ComboInputStatus.REJECTED,ActionResult.BUSY,stage,requested.id());
        if (queuedSkill != null && queuedSkill.id().equals(requested.id()))
            return comboResult(ComboInputStatus.ALREADY_QUEUED,ActionResult.ACCEPTED,stage,requested.id());
        // Only the latest distinct attack intent is retained, so mashing cannot queue a whole combo.
        queuedSkill=requested;queuedStage=stage;queuedStyle=owner;queuedAim=inputAim;queuedLeftIntent=leftIntent;
        return comboResult(ComboInputStatus.BUFFERED,ActionResult.ACCEPTED,stage,requested.id());
    }

    private ComboInputResult comboResult(ComboInputStatus status, ActionResult rejection, int stage, String skillId) {
        lastComboStatus = status;
        lastComboRejection = rejection;
        return new ComboInputResult(status, rejection, stage, skillId);
    }

    private ActionResult advanceCombo() {
        SkillDefinition next=queuedSkill; int stage=queuedStage; var owner=queuedStyle;
        boolean nextLeft = queuedLeftIntent;
        queuedSkill=null;queuedStyle=null;queuedStage=0;
        inputAim=queuedAim; queuedAim=null;
        boolean previous = leftIntent; leftIntent = nextLeft;
        var result=requestNext(next,stage,owner); inputAim=null; leftIntent=previous;
        return result.rejection();
    }

    public int cooldownRemaining(String skillId) {
        long remaining = Math.max(0, skillReadyAt.getOrDefault(skillId, 0L) - tick);
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }

    public ActionResult setGuarding(boolean guarding) {
        return setGuarding(guarding, false);
    }
    public ActionResult setGuarding(boolean guarding, boolean heavy) {
        if (!guarding) {
            guardHeld = false;
            if (phase == CombatPhase.GUARDING) phase = drawn ? CombatPhase.READY : CombatPhase.SHEATHED;
            heavyGuard = false;
            return ActionResult.ACCEPTED;
        }
        if (!drawn) return ActionResult.NOT_DRAWN;
        if (heavy && (style == null || style.heavyGuard() == null)) return ActionResult.UNSUPPORTED_INPUT;
        if (phase == CombatPhase.GUARDING) { guardHeld = true; heavyGuard = heavy; return ActionResult.ACCEPTED; }
        // A stale automatic renewal can arrive after a counter attack was accepted. Track the
        // actual release edge on the server, so network latency cannot cancel the enhanced swing.
        if (guardHeld && skill != null && attackDamageMultiplier > 1) return ActionResult.ACCEPTED;
        boolean cancel = phase == CombatPhase.WINDUP && cancels().windupGuard()
                || phase == CombatPhase.RECOVERY && cancels().recoveryGuard();
        if (phase != CombatPhase.READY && !cancel || pendingHitStun > 0) return ActionResult.BUSY;
        clearAttack(); phaseRemaining=0;
        phase = CombatPhase.GUARDING;
        guardHeld = true;
        heavyGuard = heavy;
        guardStarted = tick;
        return ActionResult.ACCEPTED;
    }

    public boolean inPerfectGuardWindow() {
        return phase == CombatPhase.GUARDING && tick - guardStarted < guardRules().perfectGuardTicks();
    }

    public boolean defenseLimitReached() {
        var guard = guardRules();
        return guard.repeatHitWindowTicks() > 0 && guardContacts >= guard.maxBlockedHits()
                && tick - lastGuardContactTick < guard.repeatHitWindowTicks();
    }

    public void recordGuardContact() {
        var guard = guardRules();
        if (guardContacts == 0 || tick - lastGuardContactTick >= guard.repeatHitWindowTicks()) guardContacts = 1;
        else guardContacts++;
        lastGuardContactTick = tick;
    }

    public void breakGuard(int stunTicks) {
        clearTechniques();
        setGuarding(false);
        stun(stunTicks);
    }

    public ActionResult requestDodge(boolean onGround) {
        if (!drawn) return ActionResult.NOT_DRAWN;
        if (!onGround && !rules.allowAirDodge()) return ActionResult.AIR_DODGE_DISABLED;
        boolean cancelRecovery = phase == CombatPhase.RECOVERY && rules.dodgeCancelsRecovery() && cancels().recoveryDodge()
                || phase == CombatPhase.WINDUP && cancels().windupDodge();
        if ((phase != CombatPhase.READY && phase != CombatPhase.GUARDING && !cancelRecovery) || pendingHitStun > 0)
            return ActionResult.BUSY;
        if (tick < dodgeReadyAt) return ActionResult.COOLDOWN;
        if (!consumeStamina(rules.dodgeStaminaCost())) return ActionResult.INSUFFICIENT_STAMINA;
        clearAttack();
        clearTechniques();
        clearCounter();
        heavyGuard = false;
        dodgeRules = rules;
        dodgeElapsed = 0;
        phase = CombatPhase.DODGING;
        phaseRemaining = dodgeRules.dodgeDurationTicks();
        dodgeReadyAt = tick + dodgeRules.dodgeCooldownTicks();
        return ActionResult.ACCEPTED;
    }

    public boolean invulnerable() {
        return phase == CombatPhase.DODGING
                && dodgeElapsed >= dodgeRules.dodgeInvulnerabilityStartTicks()
                && dodgeElapsed < dodgeRules.dodgeInvulnerabilityStartTicks() + dodgeRules.dodgeInvulnerabilityTicks();
    }

    /** Immediate interrupts, e.g. a successful parry. */
    public void stun(int ticks) {
        SkillDefinition.checkTicks(ticks, "stun ticks", true);
        if (ticks == 0) return;
        int remaining = phase == CombatPhase.STUNNED ? phaseRemaining : 0;
        clearAttack();
        clearTechniques();
        clearCounter();
        heavyGuard = false;
        pendingHitStun = 0;
        phase = CombatPhase.STUNNED;
        phaseRemaining = Math.max(remaining, ticks);
    }

    /** Apply ordinary hit reaction on the next server tick so simultaneous opposite swings can both land. */
    public void queueHitStun(int ticks) {
        SkillDefinition.checkTicks(ticks, "hit stun ticks", true);
        clearTechniques();
        // Preserve this tick's already committed outgoing strike for deterministic trades.
        // The next authoritative stun clears its multiplier with the rest of the attack.
        counterUntil = 0;
        pendingHitStun = Math.max(pendingHitStun, ticks);
    }

    public CombatTick tick() {
        CombatPhase before = phase;
        double dodgeDistance = phase == CombatPhase.DODGING ? dodgeRules.dodgeDistance() / dodgeRules.dodgeDurationTicks() : 0;
        tick++;
        if (pendingHitStun > 0) {
            int hitStun = pendingHitStun;
            stun(hitStun);
            // A hit consumed this tick's reaction; count its complete duration starting here.
            before = CombatPhase.STUNNED;
            dodgeDistance = 0;
        } else {
            switch (phase) {
                case WINDUP, ACTIVE, RECOVERY -> {
                    if(chargeArmed && phase==CombatPhase.WINDUP) {
                        chargeElapsed++;
                        if(chargeHeld) { skillElapsed=Math.min(skill.windupTicks()-1,skillElapsed+1); updateAttackPhase(); break; }
                    }
                    skillElapsed++;
                    if(chargeArmed && phase==CombatPhase.WINDUP && skillElapsed>=skill.windupTicks()) commitCharge();
                    if (queuedSkill != null && skillElapsed >= skill.windupTicks() + skill.activeTicks()) {
                        // Every active collision tick has completed. Bypass recovery before it clears the combo.
                        phase=CombatPhase.RECOVERY;
                        ActionResult result=advanceCombo();
                        if (result != ActionResult.ACCEPTED) updateAttackPhase();
                    } else updateAttackPhase();
                }
                case DRAWING -> { if (--phaseRemaining <= 0) finishDraw(); }
                case SHEATHING -> { if (--phaseRemaining <= 0) finishSheath(); }
                case DODGING -> {
                    dodgeElapsed++;
                    if (--phaseRemaining <= 0) {
                        phase = drawn ? CombatPhase.READY : CombatPhase.SHEATHED;
                        if (drawn && style != null && !style.techniques().repriseSkill().isEmpty())
                            repriseUntil = tick + style.techniques().repriseWindowTicks();
                    }
                }
                case STUNNED -> { if (--phaseRemaining <= 0) phase = drawn ? CombatPhase.READY : CombatPhase.SHEATHED; }
                default -> { }
            }
        }
        if (tick >= staminaRegenAt) stamina = Math.min(rules.maxStamina(), stamina + rules.staminaRegenPerSecond() / 20.0);
        if (tick >= spiritRegenAt) spirit = Math.min(rules.maxSpirit(), spirit + rules.spiritRegenPerSecond() / 20.0);
        if(style!=null && style.controls().legacyDual() && phase==CombatPhase.READY && tick-lastDualEnd>=40) leftRest=rightRest=-1;
        skillReadyAt.entrySet().removeIf(entry -> entry.getValue() <= tick);
        return new CombatTick(before, phase, dodgeDistance);
    }

    public CombatSnapshot snapshot() {
        return new CombatSnapshot(drawn, phase, Math.max(0, phaseRemaining),
                skill == null ? "" : skill.id(), skillElapsed,
                phase == CombatPhase.GUARDING ? (int) Math.min(Integer.MAX_VALUE, tick - guardStarted) : 0,
                invulnerable(), stamina, rules.maxStamina(), spirit, rules.maxSpirit(), tick, swingSequence);
    }

    public boolean hasHit(UUID victim) { return swingVictims.contains(victim); }
    public void markHit(UUID victim) {
        if (victim == null) throw new IllegalArgumentException("victim is required");
        swingVictims.add(victim);
    }

    /** For game entities without a CombatActor: claim each UUID once while the swing is active. */
    public boolean claimVictim(UUID victim) {
        if (activeSkill() == null || hasHit(victim)) return false;
        markHit(victim);
        return true;
    }

    void finishClash() {
        clearAttack();
        clearCounter();
        phase = drawn ? CombatPhase.READY : CombatPhase.SHEATHED;
        phaseRemaining = 0;
    }

    private void updateAttackPhase() {
        if (skillElapsed < skill.windupTicks()) {
            phase = CombatPhase.WINDUP;
            phaseRemaining = skill.windupTicks() - skillElapsed;
        } else if (skillElapsed < skill.windupTicks() + skill.activeTicks()) {
            phase = CombatPhase.ACTIVE;
            phaseRemaining = skill.windupTicks() + skill.activeTicks() - skillElapsed;
        } else if (skillElapsed < skill.durationTicks()) {
            phase = CombatPhase.RECOVERY;
            phaseRemaining = skill.durationTicks() - skillElapsed;
        } else {
            clearAttack();
            phase = drawn ? CombatPhase.READY : CombatPhase.SHEATHED;
            phaseRemaining = 0;
        }
    }

    private void resetCombo() { comboStyle = null; comboStage = -1; queuedSkill = null; queuedStyle=null; queuedStage=0; queuedAim=null; queuedLeftIntent=false; }
    private void clearAttack() {
        if(skill!=null && dual()) lastDualEnd=tick;
        skill = null; attackAim=null; skillElapsed = 0; swingVictims.clear(); resetCombo();
        attackDamageMultiplier = 1;
        chargeArmed=chargeHeld=chargeParryEnabled=chargeParried=chargeLungePending=false; chargeElapsed=0;
    }
    private void finishDraw() { drawn = true; phase = CombatPhase.READY; phaseRemaining = 0; }
    private void resetDualHandsAndStreaks() { leftRest=rightRest=-1; leftSwingStreak=rightSwingStreak=0; }
    private void finishSheath() { drawn = false; phase = CombatPhase.SHEATHED; phaseRemaining = 0; clearTechniques(); clearCounter(); heavyGuard = guardHeld = false; resetDualHandsAndStreaks(); }
}
