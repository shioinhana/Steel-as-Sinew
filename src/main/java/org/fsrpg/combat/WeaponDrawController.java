package org.fsrpg.combat;

import java.util.Collection;
import java.util.List;
import org.fsrpg.config.WeaponConfig;

/** Server-owned equipment policy; item IDs are read from actual stacks by the Minecraft adapter. */
public final class WeaponDrawController {
    private String mainItem, offhandItem;
    private boolean alive;

    /**
     * Preserve file order for the R cycle. A matching two-handed setup wins over a general
     * main-hand-only group, so equipping two swords selects dual blades rather than two stances.
     * Generic any styles never turn ordinary items or empty hands into combat weapons.
     */
    public static List<WeaponStyleDefinition> matchingStyles(Collection<WeaponStyleDefinition> styles,
                                                            WeaponConfig weapons, String main, String offhand) {
        if (styles == null || weapons == null || main == null || offhand == null)
            throw new IllegalArgumentException("styles, weapons and equipment are required");
        if (main.equals("minecraft:air")) return List.of();
        boolean registeredWeapon = weapons.groups().entrySet().stream()
                .anyMatch(entry -> !entry.getKey().equals("unarmed") && entry.getValue().mainItems().contains(main));
        if (!registeredWeapon) return List.of();
        var candidates = styles.stream().filter(WeaponStyleDefinition::enabled)
                .filter(style -> !style.weaponGroup().equals("unarmed"))
                .filter(style -> weapons.matches(style.weaponGroup(), main, offhand)).toList();
        boolean specificOffhand = candidates.stream().anyMatch(style -> requiresOffhand(style, weapons));
        boolean concreteGroup = candidates.stream().anyMatch(style -> !style.weaponGroup().equals("any"));
        return candidates.stream()
                .filter(style -> !specificOffhand || requiresOffhand(style, weapons))
                .filter(style -> !concreteGroup || !style.weaponGroup().equals("any")).toList();
    }

    private static boolean requiresOffhand(WeaponStyleDefinition style, WeaponConfig weapons) {
        var group = weapons.groups().get(style.weaponGroup());
        return group != null && !group.offhandItems().isEmpty();
    }

    public static boolean matchesSelection(CombatActor actor, Collection<WeaponStyleDefinition> styles,
                                           WeaponConfig weapons, String main, String offhand) {
        return actor.style() != null && matchingStyles(styles, weapons, main, offhand).stream()
                .anyMatch(style -> style.id().equals(actor.style().id()));
    }

    /** Call before collision resolution every tick, even for a player who has just died. */
    public boolean equip(CombatActor actor, Collection<WeaponStyleDefinition> styles, WeaponConfig weapons,
                         String main, String offhand, boolean isAlive) {
        if (actor == null) throw new IllegalArgumentException("actor is required");
        var before = actor.snapshot();
        String beforeStyle = actor.style() == null ? "" : actor.style().id();
        var matches = matchingStyles(styles, weapons, main, offhand);
        boolean equipmentChanged = !main.equals(mainItem) || !offhand.equals(offhandItem) || alive != isAlive;
        mainItem = main;
        offhandItem = offhand;
        alive = isAlive;
        boolean selectedMatches = actor.style() != null && matches.stream()
                .anyMatch(style -> style.id().equals(actor.style().id()));
        if (equipmentChanged || !isAlive || !selectedMatches) actor.forceSheathed();
        if (isAlive && !matches.isEmpty()) {
            var selected = selectedMatches ? matches.stream()
                    .filter(style -> style.id().equals(actor.style().id())).findFirst().orElseThrow() : matches.getFirst();
            actor.setStyle(selected);
            if (matches.size() == 1) actor.requestDrawn();
        }
        return !before.equals(actor.snapshot()) || !beforeStyle.equals(actor.style() == null ? "" : actor.style().id());
    }

    /** R chooses the next drawn stance of the equipped weapon; it is never a sheath toggle. */
    public ActionResult cycle(CombatActor actor, Collection<WeaponStyleDefinition> styles, WeaponConfig weapons,
                              String main, String offhand, boolean isAlive) {
        equip(actor, styles, weapons, main, offhand, isAlive);
        var matches = matchingStyles(styles, weapons, main, offhand);
        if (!isAlive || matches.isEmpty()) return ActionResult.NOT_DRAWN;
        if (matches.size() == 1) return actor.requestDrawn();
        if (actor.phase() != CombatPhase.SHEATHED && actor.phase() != CombatPhase.READY) return ActionResult.BUSY;
        int current = 0;
        for (int i = 0; i < matches.size(); i++) if (matches.get(i).id().equals(actor.style().id())) current = i;
        var next = matches.get(actor.drawn() ? (current + 1) % matches.size() : current);
        var selected = actor.setStyle(next);
        return selected == ActionResult.ACCEPTED ? actor.requestDrawn() : selected;
    }
}
