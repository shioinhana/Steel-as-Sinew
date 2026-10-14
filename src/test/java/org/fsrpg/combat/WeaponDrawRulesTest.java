package org.fsrpg.combat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.fsrpg.config.WeaponConfig;

/** Equipment and R rules have no Minecraft/client dependency. */
public final class WeaponDrawRulesTest {
    private static int checks;
    private static final String SWORD = "minecraft:iron_sword";
    private static final String SPEAR = "minecraft:iron_spear";
    private static final String AIR = "minecraft:air";
    private static final CombatRules RULES = CombatRules.defaults();
    private static final WeaponConfig WEAPONS = new WeaponConfig(Map.of(
            "sword", new WeaponConfig.Group(List.of(SWORD, "minecraft:diamond_sword"), List.of()),
            "spear", new WeaponConfig.Group(List.of(SPEAR), List.of()),
            "dual_blade", new WeaponConfig.Group(List.of(SWORD, "minecraft:diamond_sword"), List.of(SWORD, "minecraft:diamond_sword")),
            "unarmed", new WeaponConfig.Group(List.of(AIR), List.of())), Map.of());
    private static final WeaponStyleDefinition RAPIER = style("rapier", "sword", true);
    private static final WeaponStyleDefinition DUAL = style("dual_blade", "dual_blade", true);
    private static final WeaponStyleDefinition LANCE = style("spear", "spear", true);
    private static final List<WeaponStyleDefinition> DEFAULTS = List.of(RAPIER, DUAL, LANCE, style("unarmed", "unarmed", false));

    private static WeaponStyleDefinition style(String id, String group, boolean enabled) {
        return new WeaponStyleDefinition(id, id, group, enabled,
                List.of(new WeaponStyleDefinition.ComboStage("cut")), "", WeaponStyleDefinition.GuardRules.from(RULES));
    }
    private static CombatActor actor() { return new CombatActor(UUID.randomUUID(), RULES); }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static void finishDraw(CombatActor actor) { for (int i = 0; i < RULES.drawTicks(); i++) actor.tick(); }
    private static SkillDefinition attack(int windup, int active, int recovery) {
        return new SkillDefinition("cut", "cut", "sword", SkillInput.LEFT, windup, active, recovery, 30,
                10, 0, 5, 3, 90, 0, true, true, true, "rapier.fsvoxel", "cut");
    }
    private static List<WeaponStyleDefinition> matches(List<WeaponStyleDefinition> styles, String main, String offhand) {
        return WeaponDrawController.matchingStyles(styles, WEAPONS, main, offhand);
    }

    public static void main(String[] args) {
        matchingEquipment();
        namedStancePresentation();
        uniqueAutomaticDraw();
        cycleOnlyTheEquippedWeapon();
        forcedEquipmentInterrupts();
        deathAndRespawn();
        System.out.println("WeaponDrawRulesTest passed: " + checks + " checks");
    }

    private static void namedStancePresentation() {
        check(RAPIER.presentationStyleId().equals("rapier"), "single-sword mode retains its existing presentation");
        check(style("sword_alt", "sword", true).presentationStyleId().equals("rapier"), "renamed sword stance still renders the existing sword rig");
        check(style("rapier_alt", "rapier", true).presentationStyleId().equals("rapier"), "legacy rapier group routes named stances correctly");
        var dual = new WeaponStyleDefinition("dual_alt", "dual alt", "dual_blade", true,
                List.of(new WeaponStyleDefinition.ComboStage("cut")), "", WeaponStyleDefinition.GuardRules.from(RULES),
                null, WeaponStyleDefinition.Techniques.none(), WeaponStyleDefinition.CancelRules.defaults(),
                new WeaponControls("dual", "dual_ll", "dual_rl", "dual_rr", "dual_lr", "", .18));
        var spear = new WeaponStyleDefinition("spear_alt", "spear alt", "spear", true,
                List.of(new WeaponStyleDefinition.ComboStage("cut")), "", WeaponStyleDefinition.GuardRules.from(RULES),
                null, WeaponStyleDefinition.Techniques.none(), WeaponStyleDefinition.CancelRules.defaults(),
                new WeaponControls("spear", "", "", "", "", "sweep", .18));
        check(dual.presentationStyleId().equals("dual_blade"), "named dual stance retains dual camera/hand routing");
        check(spear.presentationStyleId().equals("spear"), "named spear stance retains aiming and IK routing");
        check(style("custom_alt", "custom", true).presentationStyleId().equals("custom_alt"), "unrecognized custom presentation is preserved");
    }

    private static void matchingEquipment() {
        check(matches(DEFAULTS, SWORD, AIR).equals(List.of(RAPIER)), "single sword selects only rapier");
        check(matches(DEFAULTS, SWORD, SWORD).equals(List.of(DUAL)), "dual swords exclude the general single-sword stance");
        check(matches(DEFAULTS, SWORD, "minecraft:diamond_sword").equals(List.of(DUAL)), "mixed sword materials still select dual blades");
        check(matches(DEFAULTS, SPEAR, SWORD).equals(List.of(LANCE)), "spear does not acquire sword or dual stances");
        check(matches(DEFAULTS, SWORD, "minecraft:shield").equals(List.of(RAPIER)), "nonweapon offhand permits single sword");
        check(matches(DEFAULTS, AIR, SWORD).isEmpty(), "offhand weapon alone does not take over normal controls");
        check(matches(DEFAULTS, "minecraft:potato", AIR).isEmpty(), "ordinary items have no combat state");
        var generic = style("generic", "any", true);
        check(matches(List.of(generic), "minecraft:potato", AIR).isEmpty(), "any style cannot turn crops into weapons");
        check(matches(List.of(generic), AIR, AIR).isEmpty(), "any style cannot draw empty hands");
        check(matches(List.of(generic), SWORD, AIR).equals(List.of(generic)), "any style may serve a registered weapon");
        check(matches(List.of(generic, RAPIER), SWORD, AIR).equals(List.of(RAPIER)), "specific group takes priority over any");
        check(matches(List.of(RAPIER, style("disabled", "sword", false)), SWORD, AIR).equals(List.of(RAPIER)), "disabled states do not change single-state behavior");
        check(matches(List.of(RAPIER, style("dual_disabled", "dual_blade", false)), SWORD, SWORD).equals(List.of(RAPIER)), "disabled dual state does not suppress an enabled sword state");
    }

    private static void uniqueAutomaticDraw() {
        var actor = actor();
        var control = new WeaponDrawController();
        check(control.equip(actor, DEFAULTS, WEAPONS, SWORD, AIR, true), "taking out a unique weapon changes state immediately");
        check(actor.style().id().equals("rapier") && actor.phase() == CombatPhase.DRAWING, "unique state starts existing draw transition");
        check(!control.equip(actor, DEFAULTS, WEAPONS, SWORD, AIR, true), "stable equipment does not restart the transition");
        for (int i = 0; i < RULES.drawTicks() - 1; i++) {
            actor.tick();
            check(!control.equip(actor, DEFAULTS, WEAPONS, SWORD, AIR, true), "automatic draw does not reset its clock");
        }
        check(!actor.drawn(), "draw timing retains the last locked frame");
        actor.tick();
        check(actor.drawn() && actor.phase() == CombatPhase.READY, "automatic draw finishes at the normal boundary");
        for (int i = 0; i < 4; i++) {
            check(control.cycle(actor, DEFAULTS, WEAPONS, SWORD, AIR, true) == ActionResult.ACCEPTED, "R is idempotent for a single state");
            check(actor.drawn() && actor.phase() == CombatPhase.READY, "single-state R never sheaths");
        }
        check(control.equip(actor, DEFAULTS, WEAPONS, SWORD, SWORD, true), "equipping second sword updates state");
        check(actor.style().id().equals("dual_blade") && actor.phase() == CombatPhase.DRAWING, "dual setup automatically selects its sole state");
        finishDraw(actor);
        check(!WeaponDrawController.matchesSelection(actor, DEFAULTS, WEAPONS, SWORD, AIR), "dual state cannot claim single-sword equipment");
        actor.setStyle(RAPIER);
        check(!WeaponDrawController.matchesSelection(actor, DEFAULTS, WEAPONS, SWORD, SWORD), "manually choosing rapier cannot evade dual specificity");
        control.equip(actor, DEFAULTS, WEAPONS, SWORD, SWORD, true);
        check(actor.style().id().equals("dual_blade"), "equipment policy corrects a stale selected style");
    }

    private static void cycleOnlyTheEquippedWeapon() {
        var alternate = style("rapier_alt", "sword", true);
        var styles = List.of(RAPIER, LANCE, alternate, DUAL);
        var actor = actor();
        var control = new WeaponDrawController();
        control.equip(actor, styles, WEAPONS, SWORD, AIR, true);
        check(actor.style().id().equals("rapier") && !actor.drawn(), "multi-state equipment selects a valid initial state without bypassing R");
        check(control.cycle(actor, styles, WEAPONS, SWORD, AIR, true) == ActionResult.ACCEPTED, "first R starts the current state");
        check(actor.style().id().equals("rapier") && actor.phase() == CombatPhase.DRAWING, "first R draws instead of skipping initial state");
        check(control.cycle(actor, styles, WEAPONS, SWORD, AIR, true) == ActionResult.BUSY, "drawing cannot be bypassed with repeated R");
        finishDraw(actor);
        check(control.cycle(actor, styles, WEAPONS, SWORD, AIR, true) == ActionResult.ACCEPTED, "R cycles a ready weapon");
        check(actor.style().id().equals("rapier_alt") && actor.drawn(), "R skips different weapons and stays drawn");
        check(control.cycle(actor, styles, WEAPONS, SWORD, AIR, true) == ActionResult.ACCEPTED, "R wraps around");
        check(actor.style().id().equals("rapier") && actor.drawn(), "R wraps to the first state");
        for (int at : new int[]{0, 2, 6}) {
            actor.requestAttack(attack(2, 4, 5));
            for (int i = 0; i < at; i++) actor.tick();
            var before = actor.snapshot();
            check(control.cycle(actor, styles, WEAPONS, SWORD, AIR, true) == ActionResult.BUSY, "R respects windup/active/recovery lock at " + at);
            check(actor.snapshot().equals(before) && actor.style().id().equals("rapier"), "blocked R changes no attack state");
            actor.forceSheathed();
            actor.setResources(100, 100);
            actor.requestDrawn();
            finishDraw(actor);
            for (int i = 0; i < 30; i++) actor.tick();
        }
        actor.setGuarding(true);
        check(control.cycle(actor, styles, WEAPONS, SWORD, AIR, true) == ActionResult.BUSY, "R does not interrupt guard");
        actor.setGuarding(false);
        actor.requestDodge(true);
        check(control.cycle(actor, styles, WEAPONS, SWORD, AIR, true) == ActionResult.BUSY, "R does not interrupt dodge");
        check(control.cycle(actor, styles, WEAPONS, "minecraft:potato", AIR, true) == ActionResult.NOT_DRAWN, "R with ordinary item is rejected");
        check(!actor.drawn() && actor.phase() == CombatPhase.SHEATHED, "ordinary item releases combat control");
    }

    private static void forcedEquipmentInterrupts() {
        for (int at : new int[]{0, 2, 6}) {
            var actor = actor();
            var control = new WeaponDrawController();
            control.equip(actor, DEFAULTS, WEAPONS, SWORD, AIR, true);
            finishDraw(actor);
            actor.requestAttack(attack(2, 4, 5));
            for (int i = 0; i < at; i++) actor.tick();
            actor.requestAttack(attack(2, 4, 5));
            double stamina = actor.stamina();
            check(control.equip(actor, DEFAULTS, WEAPONS, AIR, AIR, true), "removing weapon interrupts every attack phase " + at);
            check(!actor.drawn() && actor.currentSkill() == null && actor.activeSkill() == null, "old weapon cannot cause a delayed hit");
            check(!actor.comboSnapshot().queued() && actor.drainStartedSkills().isEmpty(), "equipment removal clears buffer and undispatched starts");
            check(actor.stamina() == stamina && actor.cooldownRemaining("cut") > 0, "equipment change does not refund resources or cooldown");
            for (int i = 0; i < 20; i++) actor.tick();
            check(actor.phase() == CombatPhase.SHEATHED && actor.currentSkill() == null, "removed weapon never resumes attack");
            control.equip(actor, DEFAULTS, WEAPONS, SPEAR, AIR, true);
            check(actor.style().id().equals("spear") && actor.phase() == CombatPhase.DRAWING, "next weapon starts its own draw state");
        }
        var actor = actor();
        var control = new WeaponDrawController();
        control.equip(actor, DEFAULTS, WEAPONS, SWORD, AIR, true);
        finishDraw(actor);
        actor.requestDodge(true);
        check(actor.invulnerable(), "dodge initially owns its configured immunity");
        control.equip(actor, DEFAULTS, WEAPONS, AIR, AIR, true);
        check(!actor.invulnerable() && actor.phase() == CombatPhase.SHEATHED, "weapon removal cancels dodge immunity");
    }

    private static void deathAndRespawn() {
        var actor = actor();
        var control = new WeaponDrawController();
        control.equip(actor, DEFAULTS, WEAPONS, SWORD, AIR, true);
        finishDraw(actor);
        actor.requestAttack(attack(2, 4, 5));
        check(control.equip(actor, DEFAULTS, WEAPONS, SWORD, AIR, false), "death immediately changes the state");
        check(!actor.drawn() && actor.currentSkill() == null, "dead actor cannot retain an attack");
        check(!control.equip(actor, DEFAULTS, WEAPONS, SWORD, AIR, false), "repeated dead ticks remain stable");
        check(control.cycle(actor, DEFAULTS, WEAPONS, SWORD, AIR, false) == ActionResult.NOT_DRAWN, "dead actor cannot draw with R");
        control.equip(actor, DEFAULTS, WEAPONS, SWORD, AIR, true);
        check(actor.phase() == CombatPhase.DRAWING, "same held weapon after respawn automatically draws again");
        finishDraw(actor);
        check(actor.drawn(), "respawn draw transition completes normally");
    }
}
