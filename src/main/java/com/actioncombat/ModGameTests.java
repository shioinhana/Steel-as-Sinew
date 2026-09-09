package com.actioncombat;

import com.actioncombat.combat.AttackTimings;
import com.actioncombat.entity.PlayerAnimatable;
import com.actioncombat.combat.CombatAttributes;
import com.actioncombat.combat.CombatData;
import com.actioncombat.combat.HandRest;
import com.actioncombat.combat.ServerCombat;
import com.actioncombat.combat.SwingSide;
import com.actioncombat.combat.WeaponCategory;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Consumer;
import java.util.ArrayList;

@EventBusSubscriber(modid = ActionCombat.MODID)
public final class ModGameTests {
    private static final Identifier ARENA =
            Identifier.fromNamespaceAndPath(ActionCombat.MODID, "combat_arena");

    public static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, ActionCombat.MODID);

    /** vanilla consumes TestFunctionLoaders during built-in registry bootstrap (before mod
     *  constructors), so test functions must go through the regular registry event. */
    public static void registerTestFunctions(IEventBus modBus) {
        TEST_FUNCTIONS.register("longsword_registered", () -> ModGameTests::longswordRegistered);
        TEST_FUNCTIONS.register("draw_requires_sword", () -> ModGameTests::drawRequiresSword);
        TEST_FUNCTIONS.register("attack_requires_drawn", () -> ModGameTests::attackRequiresDrawn);
        TEST_FUNCTIONS.register("attack_hits_target_in_front", () -> ModGameTests::attackHitsTargetInFront);
        TEST_FUNCTIONS.register("combo_advances_in_window", () -> ModGameTests::comboAdvancesInWindow);
        TEST_FUNCTIONS.register("combo_rejects_outside_window", () -> ModGameTests::comboRejectsOutsideWindow);
        TEST_FUNCTIONS.register("defend_reduces_damage", () -> ModGameTests::defendReducesDamage);
        TEST_FUNCTIONS.register("charged_attack_forces_stage_one", () -> ModGameTests::chargedAttackForcesStageOne);
        TEST_FUNCTIONS.register("attack_timings_loaded", () -> ModGameTests::attackTimingsLoaded);
        TEST_FUNCTIONS.register("dummy_entity_constructs", () -> ModGameTests::dummyEntityConstructs);
        TEST_FUNCTIONS.register("locomotion_preserves_lower_layer", () -> ModGameTests::locomotionPreservesLowerLayer);
        TEST_FUNCTIONS.register("same_side_does_not_chain", () -> ModGameTests::sameSideDoesNotChain);
        TEST_FUNCTIONS.register("alternating_sides_walk_authored_order", () -> ModGameTests::alternatingSidesWalkAuthoredOrder);
        TEST_FUNCTIONS.register("attack_interval_survives_sheath", () -> ModGameTests::attackIntervalSurvivesSheath);
        TEST_FUNCTIONS.register("attack_preserves_windup", () -> ModGameTests::attackPreservesWindup);
        TEST_FUNCTIONS.register("recovery_cancel_opens_after_contact", () -> ModGameTests::recoveryCancelOpensAfterContact);
        TEST_FUNCTIONS.register("attack_without_input_finishes_clip", () -> ModGameTests::attackWithoutInputFinishesClip);
        TEST_FUNCTIONS.register("charged_attack_uses_same_cancel_point", () -> ModGameTests::chargedAttackUsesSameCancelPoint);
        TEST_FUNCTIONS.register("defend_and_dodge_respect_cancel_point", () -> ModGameTests::defendAndDodgeRespectCancelPoint);
        TEST_FUNCTIONS.register("all_attacks_share_progressive_cadence", () -> ModGameTests::allAttacksShareProgressiveCadence);
        TEST_FUNCTIONS.register("progressive_attack_cadence", () -> ModGameTests::progressiveAttackCadence);
        TEST_FUNCTIONS.register("idle_restores_attack_cadence", () -> ModGameTests::idleRestoresAttackCadence);
        TEST_FUNCTIONS.register("rejected_input_preserves_cadence", () -> ModGameTests::rejectedInputPreservesCadence);
        TEST_FUNCTIONS.register("rapier_instant_hit", () -> ModGameTests::rapier_instant_hit);
        TEST_FUNCTIONS.register("rapier_miss_recovery", () -> ModGameTests::rapier_miss_recovery);
        TEST_FUNCTIONS.register("rapier_aim_and_wall", () -> ModGameTests::rapier_aim_and_wall);
        TEST_FUNCTIONS.register("rapier_random_attacks", () -> ModGameTests::rapier_random_attacks);
        TEST_FUNCTIONS.register("guard_window_boundaries", () -> ModGameTests::guard_window_boundaries);
        TEST_FUNCTIONS.register("dual_guard_bash_once", () -> ModGameTests::dual_guard_bash_once);
        TEST_FUNCTIONS.register("rapier_no_knockback", () -> ModGameTests::rapier_no_knockback);
        TEST_FUNCTIONS.register("rapier_cannot_block", () -> ModGameTests::rapier_can_block);
        TEST_FUNCTIONS.register("rapier_charge_pierces_armor", () -> ModGameTests::rapier_charge_pierces_armor);
        TEST_FUNCTIONS.register("katana_left_hand_draw", () -> ModGameTests::katana_left_hand_draw);
        TEST_FUNCTIONS.register("katana_command_attack", () -> ModGameTests::katana_command_attack);
        TEST_FUNCTIONS.register("left_hand_sword_cadence", () -> ModGameTests::left_hand_sword_cadence);
        TEST_FUNCTIONS.register("rapier_combo_cancels_ai", () -> ModGameTests::rapier_combo_cancels_ai);
        TEST_FUNCTIONS.register("dual_auto_guard", () -> ModGameTests::dual_auto_guard);
        TEST_FUNCTIONS.register("dual_guard_arms_dual_cut", () -> ModGameTests::dual_guard_arms_dual_cut);
        TEST_FUNCTIONS.register("rapier_charged_thrust_speeds_chain", () -> ModGameTests::rapier_charged_thrust_speeds_chain);
        TEST_FUNCTIONS.register("rapier_fourth_slash_contact_lags", () -> ModGameTests::rapier_fourth_slash_contact_lags);
        TEST_FUNCTIONS.register("charged_thrust_interrupts_everything", () -> ModGameTests::charged_thrust_interrupts_everything);
        TEST_FUNCTIONS.register("dodge_interrupts_everything", () -> ModGameTests::dodge_interrupts_everything);
        TEST_FUNCTIONS.register("dual_last_frame_hold_keeps_cancel_window", () -> ModGameTests::dual_last_frame_hold_keeps_cancel_window);
        TEST_FUNCTIONS.register("dual_every_contact_lands", () -> ModGameTests::dual_every_contact_lands);
        TEST_FUNCTIONS.register("spear_poke_contact", () -> ModGameTests::spear_poke_contact);
        TEST_FUNCTIONS.register("spear_actions", () -> ModGameTests::spear_actions);
        TEST_FUNCTIONS.register("spear_range_and_walls", () -> ModGameTests::spear_range_and_walls);
        TEST_FUNCTIONS.register("spear_speed_and_riding", () -> ModGameTests::spear_speed_and_riding);
        TEST_FUNCTIONS.register("spear_guard_and_steps", () -> ModGameTests::spear_guard_and_steps);
        TEST_FUNCTIONS.register(modBus);
    }

    private static ResourceKey<Consumer<GameTestHelper>> fn(String name) {
        return ResourceKey.create(Registries.TEST_FUNCTION,
                Identifier.fromNamespaceAndPath(ActionCombat.MODID, name));
    }

    @SubscribeEvent
    static void onRegisterGameTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition> midnight = event.registerEnvironment(
                Identifier.fromNamespaceAndPath(ActionCombat.MODID, "midnight"),
                new TestEnvironmentDefinition.TimeOfDay(18000));

        register(event, midnight, "spear_poke_contact", 100);
        register(event, midnight, "spear_actions", 100);
        register(event, midnight, "spear_range_and_walls", 100);
        register(event, midnight, "spear_speed_and_riding", 100);
        register(event, midnight, "spear_guard_and_steps", 100);
        register(event, midnight, "longsword_registered", 100);
        register(event, midnight, "draw_requires_sword", 100);
        register(event, midnight, "attack_requires_drawn", 100);
        register(event, midnight, "attack_hits_target_in_front", 200);
        register(event, midnight, "combo_advances_in_window", 100);
        register(event, midnight, "combo_rejects_outside_window", 100);
        register(event, midnight, "defend_reduces_damage", 100);
        register(event, midnight, "charged_attack_forces_stage_one", 100);
        register(event, midnight, "attack_timings_loaded", 100);
        register(event, midnight, "dummy_entity_constructs", 100);
        register(event, midnight, "locomotion_preserves_lower_layer", 100);
        register(event, midnight, "same_side_does_not_chain", 100);
        register(event, midnight, "alternating_sides_walk_authored_order", 100);
        register(event, midnight, "attack_interval_survives_sheath", 100);
        register(event, midnight, "attack_preserves_windup", 100);
        register(event, midnight, "recovery_cancel_opens_after_contact", 100);
        register(event, midnight, "attack_without_input_finishes_clip", 100);
        register(event, midnight, "charged_attack_uses_same_cancel_point", 100);
        register(event, midnight, "defend_and_dodge_respect_cancel_point", 100);
        register(event, midnight, "all_attacks_share_progressive_cadence", 100);
        register(event, midnight, "progressive_attack_cadence", 100);
        register(event, midnight, "idle_restores_attack_cadence", 100);
        register(event, midnight, "rejected_input_preserves_cadence", 100);
        register(event, midnight, "rapier_instant_hit", 140);
        register(event, midnight, "rapier_miss_recovery", 140);
        register(event, midnight, "rapier_aim_and_wall", 140);
        register(event, midnight, "rapier_random_attacks", 140);
        register(event, midnight, "guard_window_boundaries", 140);
        register(event, midnight, "dual_guard_bash_once", 140);
        register(event, midnight, "rapier_no_knockback", 140);
        register(event, midnight, "rapier_cannot_block", 140);
        register(event, midnight, "rapier_charge_pierces_armor", 140);
        register(event, midnight, "katana_left_hand_draw", 140);
        register(event, midnight, "katana_command_attack", 200);
        register(event, midnight, "left_hand_sword_cadence", 200);
        register(event, midnight, "rapier_combo_cancels_ai", 220);
        register(event, midnight, "dual_auto_guard", 100);
        register(event, midnight, "dual_guard_arms_dual_cut", 120);
        register(event, midnight, "rapier_charged_thrust_speeds_chain", 200);
        register(event, midnight, "rapier_fourth_slash_contact_lags", 120);
        register(event, midnight, "charged_thrust_interrupts_everything", 120);
        register(event, midnight, "dodge_interrupts_everything", 120);
        register(event, midnight, "dual_last_frame_hold_keeps_cancel_window", 120);
        register(event, midnight, "dual_every_contact_lands", 120);
    }

    private static void register(RegisterGameTestsEvent event, Holder<TestEnvironmentDefinition> env,
                                 String name, int maxTicks) {
        TestData<Holder<TestEnvironmentDefinition>> data =
                new TestData<>(env, ARENA, maxTicks, 0, true);
        event.registerTest(Identifier.fromNamespaceAndPath(ActionCombat.MODID, name),
                d -> new FunctionGameTestInstance(fn(name), d), data);
    }

    private static ServerPlayer drawnPlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
        ServerCombat.handleSetDrawn(player, true);
        return player;
    }

    private static void longswordRegistered(GameTestHelper helper) {
        if (ModItems.LONGSWORD.get() == null) {
            helper.fail("longsword not registered");
            return;
        }
        if (!ModItems.LONGSWORD.get().getDefaultInstance().is(net.minecraft.tags.ItemTags.SWORDS)) {
            helper.fail("longsword is not a member of the minecraft:swords item tag");
            return;
        }
        helper.succeed();
    }

    private static void drawRequiresSword(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        ServerCombat.handleSetDrawn(player, true);
        if (player.getData(ModAttachments.COMBAT).drawn) {
            helper.fail("drew weapon without holding any sword");
            return;
        }

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
        ServerCombat.handleSetDrawn(player, true);
        if (!player.getData(ModAttachments.COMBAT).drawn) {
            helper.fail("failed to draw while holding a vanilla sword");
            return;
        }
        helper.succeed();
    }

    private static void attackRequiresDrawn(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        ServerCombat.handleAttack(player, SwingSide.LEFT, false);
        if (player.getData(ModAttachments.COMBAT).active != null) {
            helper.fail("attack accepted while not drawn");
            return;
        }
        helper.succeed();
    }

    private static void attackHitsTargetInFront(GameTestHelper helper) {
        ServerPlayer player = drawnPlayer(helper);
        if (!player.getData(ModAttachments.COMBAT).drawn) {
            helper.fail("setup: failed to draw");
            return;
        }

        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        zombie.setNoAi(true);
        float initialHealth = zombie.getHealth();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        // no public moveTo on 1.21.11 Entity: set pos + facing directly
        player.setPos(origin.getX() + 2.5, origin.getY() + 1.0, origin.getZ() + 0.5);
        player.setYRot(0.0F);
        player.setXRot(0.0F);
        player.yRotO = 0.0F;
        player.xRotO = 0.0F;
        var eye = player.getEyePosition();
        ServerCombat.handleAim(player, 0.0F, 0.0F, eye.x, eye.y, eye.z);

        ServerCombat.handleAttack(player, SwingSide.LEFT, false);
        CombatData.ActiveAttack attack = player.getData(ModAttachments.COMBAT).active;
        if (attack == null) {
            helper.fail("attack was not accepted while drawn");
            return;
        }

        int contactTick = Math.max(1, (int) Math.ceil(
                (attack.contactSeconds - CombatConstants.HIT_SCAN_LEAD_SECONDS) * 20.0F - 1.0E-4F));
        float[] healthAfterContact = {initialHealth};
        helper.runAfterDelay(contactTick - 1, () -> {
            if (attack.scanned || zombie.getHealth() != initialHealth) {
                helper.fail("attack scanned or dealt damage before runtime contact tick three");
            }
        });
        helper.runAfterDelay(contactTick + 1, () -> {
            if (!attack.scanned || attack.hitCount != 1 || zombie.getHealth() >= initialHealth) {
                helper.fail("zombie in front at swing range did not take one hit at contact");
                return;
            }
            healthAfterContact[0] = zombie.getHealth();
        });
        helper.runAfterDelay(attack.durationTicks + 1, () -> {
            if (attack.hitCount == 1 && zombie.getHealth() == healthAfterContact[0]) {
                helper.succeed();
            } else {
                helper.fail("one swing dealt damage again during recovery");
            }
        });
    }

    /** The next swing starts as soon as the shared recovery cancel point opens. */
    private static void comboAdvancesInWindow(GameTestHelper helper) {
        ServerPlayer player = drawnPlayer(helper);

        ServerCombat.handleAttack(player, SwingSide.LEFT, false);
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (data.active == null || data.active.depth != 0 || data.active.side != SwingSide.LEFT) {
            helper.fail("expected combo to open on the left at depth 0");
            return;
        }

        helper.runAfterDelay(data.active.cancelStartTick, () -> {
            ServerCombat.handleAttack(player, SwingSide.LEFT, false);
            CombatData d = player.getData(ModAttachments.COMBAT);
            if (d.active != null && d.active.depth == 1 && d.active.side == SwingSide.LEFT
                    && d.leftHand == com.actioncombat.combat.HandRest.LEFT
                    && d.rightHand == com.actioncombat.combat.HandRest.LEFT) {
                helper.succeed();
            } else {
                helper.fail("second left click should send the right hand left");
            }
        });
    }

    private static void comboRejectsOutsideWindow(GameTestHelper helper) {
        ServerPlayer player = drawnPlayer(helper);

        ServerCombat.handleAttack(player, SwingSide.LEFT, false);
        ServerCombat.handleAttack(player, SwingSide.LEFT, false);
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (data.active == null || data.active.depth != 0 || data.active.side != SwingSide.LEFT) {
            helper.fail("attack before the cancel point was not rejected");
            return;
        }
        helper.succeed();
    }

    /**
     * Guard reduction is asserted by posting the incoming-damage event directly: going
     * through {@code hurtServer} drags in vanilla's hurt cooldown and the mock player's
     * client-load invulnerability, both of which make the probe flaky. The event is
     * exactly what the mod's handler sees in game.
     */
    private static void defendReducesDamage(GameTestHelper helper) {
        ServerPlayer player = drawnPlayer(helper);
        ServerCombat.handleSetDefending(player, true);
        CombatData data = player.getData(ModAttachments.COMBAT);
        if (!data.defending) {
            helper.fail("guard request was not accepted while drawn");
            return;
        }
        var source = player.damageSources().generic();

        data.defendStartGameTime = player.level().getGameTime() - 1;
        var perfect = new net.neoforged.neoforge.common.damagesource.DamageContainer(source, 10.0F);
        var perfectEvent = new net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent(
                player, perfect);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(perfectEvent);
        if (!perfectEvent.isCanceled()) {
            helper.fail("tap/perfect guard should cancel all 10 damage");
            return;
        }

        helper.runAfterDelay(10, () -> {
            data.defendStartGameTime = player.level().getGameTime() - 10;
            data.defending = true;
            data.defendHold = true;
            var hold = new net.neoforged.neoforge.common.damagesource.DamageContainer(source, 10.0F);
            var holdEvent = new net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent(
                    player, hold);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(holdEvent);
            float reduced = hold.getNewDamage();
            if (Math.abs(ServerCombat.defendReduction(data, player.level().getGameTime()) - 0.5F) > 0.001F
                    || holdEvent.isCanceled() || Math.abs(reduced - 5.0F) > 0.05F) {
                helper.fail("hold guard at 0.5s should let 50% through, got " + reduced);
            } else {
                helper.succeed();
            }
        });
    }

    private static void chargedAttackForcesStageOne(GameTestHelper helper) {
        int[] completed = {0};
        for (SwingSide side : SwingSide.values()) {
            ServerPlayer player = drawnPlayer(helper);
            ServerCombat.handleAttack(player, side, false);
            CombatData data = player.getData(ModAttachments.COMBAT);
            CombatData.ActiveAttack opening = data.active;
            if (opening == null) {
                helper.fail("setup: opening " + side + " attack was rejected");
                return;
            }
            helper.runAfterDelay(opening.durationTicks + 1, () -> {
                ServerCombat.handleAttack(player, side, true);
                int leadingHand = side == SwingSide.LEFT ? 0 : 1;
                com.actioncombat.combat.HandRest restingHand = leadingHand == 0 ? data.rightHand : data.leftHand;
                if (data.active == null || data.active.depth != leadingHand || !data.active.charged
                        || data.active.side != side || restingHand != com.actioncombat.combat.HandRest.CENTER) {
                    helper.fail("charged " + side + " attack did not restart with its leading hand and open stance");
                    return;
                }
                if (++completed[0] == SwingSide.values().length) helper.succeed();
            });
        }
    }

    /**
     * The animation dummy is a LivingEntity so the armour layer can render on it, which
     * means it needs a registered attribute supplier. Without one, constructing it throws
     * and combat mode breaks the moment a player draws. Constructing it here is what
     * proves the registration is wired, since nothing else in the tests builds one.
     */
    private static void dummyEntityConstructs(GameTestHelper helper) {
        PlayerAnimatable dummy =
                new PlayerAnimatable(ModEntities.PLAYER_DUMMY.get(), helper.getLevel());

        if (dummy.getAttributes() == null) {
            helper.fail("dummy has no attribute map");
            return;
        }
        // equipment slots back the armour layer; they must round-trip
        dummy.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,
                new ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE));
        if (!dummy.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)
                .is(net.minecraft.world.item.Items.IRON_CHESTPLATE)) {
            helper.fail("dummy equipment slots do not round-trip");
            return;
        }
        if (dummy.isPickable() || dummy.isPushable()) {
            helper.fail("dummy must not be pickable or pushable");
            return;
        }
        dummy.discard();
        helper.succeed();
    }

    private static void locomotionPreservesLowerLayer(GameTestHelper helper) {
        PlayerAnimatable dummy = new PlayerAnimatable(ModEntities.PLAYER_DUMMY.get(), helper.getLevel());
        String[] suffixes = {"", "_left", "_right", "_back"};
        float[][] directions = {{1, 0}, {0, 1}, {0, -1}, {-1, 0}};
        long now = System.nanoTime();
        try {
            dummy.drawn = true;
            for (WeaponCategory category : WeaponCategory.values()) {
                if (category == WeaponCategory.SPEAR) continue; // Spear owns bracing legs, tested separately.
                dummy.weaponCategory = category;
                dummy.airborne = false;
                dummy.descending = false;
                for (int direction = 0; direction < directions.length; direction++) {
                    dummy.moveForward = directions[direction][0];
                    dummy.moveStrafe = directions[direction][1];
                    dummy.crouching = false;
                    dummy.moveSpeed = 0;
                    if (!verifyLayeredLocomotion(helper, dummy, category.idle, now)) return;

                    dummy.moveSpeed = (CombatConstants.WALK_SPEED_THRESHOLD
                            + CombatConstants.RUN_SPEED_THRESHOLD) / 2;
                    if (!verifyLayeredLocomotion(helper, dummy, "walk_hold" + suffixes[direction], now)) return;
                    dummy.moveSpeed = CombatConstants.RUN_SPEED_THRESHOLD + 0.1F;
                    if (!verifyLayeredLocomotion(helper, dummy, "run_hold" + suffixes[direction], now)) return;

                    dummy.crouching = true;
                    // A crouched runner must retain the lower stance even while speed decays.
                    if (!verifyLayeredLocomotion(helper, dummy, "crouch_walk" + suffixes[direction], now)) return;
                    dummy.moveSpeed = (CombatConstants.WALK_SPEED_THRESHOLD
                            + CombatConstants.RUN_SPEED_THRESHOLD) / 2;
                    if (!verifyLayeredLocomotion(helper, dummy, "crouch_walk" + suffixes[direction], now)) return;
                    dummy.moveSpeed = 0;
                    if (!verifyLayeredLocomotion(helper, dummy, category.crouchIdle, now)) return;
                }

                dummy.crouching = true;
                dummy.moveSpeed = CombatConstants.RUN_SPEED_THRESHOLD + 0.1F;
                dummy.airborne = true;
                dummy.jumpStartNanos = now - 100_000_000L;
                if (!verifyLayeredLocomotion(helper, dummy, PlayerAnimatable.ANIM_JUMP_START, now)) return;
                dummy.jumpStartNanos = now - 280_000_000L;
                if (!verifyLayeredLocomotion(helper, dummy, category.jump, now)) return;
                dummy.descending = true;
                dummy.jumpStartNanos = now - 100_000_000L;
                if (!verifyLayeredLocomotion(helper, dummy, category.fall, now)) return;
                dummy.airborne = false;
                if (!verifyLayeredLocomotion(helper, dummy, "crouch_walk_back", now)) return;
            }
            helper.succeed();
        } finally {
            dummy.discard();
        }
    }

    private static boolean verifyLayeredLocomotion(GameTestHelper helper, PlayerAnimatable dummy,
                                                 String expected, long now) {
        if (!expected.equals(dummy.locomotionClip(now))) {
            helper.fail(dummy.weaponCategory + " expected " + expected + ", got " + dummy.locomotionClip(now));
            return false;
        }
        for (String attack : dummy.weaponCategory.allAttacks()) {
            dummy.playUpper(attack + "_upper", CombatConstants.ATTACK_DURATION_SECONDS);
            if (!expected.equals(dummy.locomotionClip(now))) {
                helper.fail(dummy.weaponCategory + "/" + attack + " replaced lower-body " + expected);
                return false;
            }
        }
        dummy.clearAction();
        dummy.defending = true;
        if (!expected.equals(dummy.locomotionClip(now))) {
            helper.fail(dummy.weaponCategory + " guard replaced lower-body " + expected);
            return false;
        }
        dummy.defending = false;
        dummy.aiming = true;
        if (!expected.equals(dummy.locomotionClip(now))) {
            helper.fail(dummy.weaponCategory + " charge replaced lower-body " + expected);
            return false;
        }
        dummy.aiming = false;
        return true;
    }

    /**
     * Every attack clip must have a generated timing entry. Without this a stale
     * attack_timings.json would silently fall back to estimated authored phases.
     */
    private static void attackTimingsLoaded(GameTestHelper helper) {
        int expectedCount = 0;
        for (WeaponCategory cat : WeaponCategory.values()) {
            for (String clip : cat.allAttacks()) {
                expectedCount++;
                AttackTimings.Timing timing = AttackTimings.of(cat, clip);
                if (timing.activeStartTick() != 2 || timing.activeEndTick() != 4
                        || timing.cancelStartTick() != 6 || timing.length() != 0.40F
                        || timing.comboStart() != 0.20F
                        || !(timing.authoredActiveEnd() > timing.authoredActiveStart())
                        || !(timing.authoredLength() > timing.authoredActiveEnd())) {
                    helper.fail("timing for " + clip + " lacks uniform 0.3s slash / 0.1s recovery phases");
                    return;
                }
            }
        }
        if (AttackTimings.size() != expectedCount) {
            helper.fail("generated authored phase data is missing; run tools/generate_attack_timings.mjs");
            return;
        }
        helper.succeed();
    }

    /** Repeating a side after the cancel point rotates to that side's second clip. */
    private static void sameSideDoesNotChain(GameTestHelper helper) {
        ServerPlayer player = drawnPlayer(helper);

        ServerCombat.handleAttack(player, SwingSide.LEFT, false);
        if (player.getData(ModAttachments.COMBAT).active == null) {
            helper.fail("setup: opening swing was rejected");
            return;
        }

        helper.runAfterDelay(player.getData(ModAttachments.COMBAT).active.cancelStartTick, () -> {
            ServerCombat.handleAttack(player, SwingSide.LEFT, false);
            CombatData.ActiveAttack second = player.getData(ModAttachments.COMBAT).active;
            if (second == null || second.depth != 1) {
                helper.fail("second left click should move the right hand left");
                return;
            }
            helper.runAfterDelay(second.cancelStartTick, () -> {
                CombatData d = player.getData(ModAttachments.COMBAT);
                // The clip may already be over by now; what matters is that no new
                // attack starts once both hands sit left.
                CombatData.ActiveAttack before = d.active;
                ServerCombat.handleAttack(player, SwingSide.LEFT, false);
                if (d.active == before) {
                    helper.succeed();
                } else {
                    helper.fail("third left click should be dropped once both hands sit left");
                }
            });
        });
    }

    /**
     * Right click leads with the right hand: first 右砍 is attack4, second is attack2.
     */
    private static void alternatingSidesWalkAuthoredOrder(GameTestHelper helper) {
        ServerPlayer player = drawnPlayer(helper);
        WeaponCategory cat = ServerCombat.categoryOf(player);

        if (!"attack_1".equals(cat.attackAnim(SwingSide.LEFT, 0))
                || !"attack2".equals(cat.attackAnim(SwingSide.LEFT, 1))
                || !"attack4".equals(cat.attackAnim(SwingSide.RIGHT, 0))
                || !"attack3".equals(cat.attackAnim(SwingSide.RIGHT, 1))) {
            helper.fail("expected 左手左砍/右手左砍/左手右砍/右手右砍 = attack_1/2/4/3");
            return;
        }

        ServerCombat.handleAttack(player, SwingSide.RIGHT, false);
        CombatData first = player.getData(ModAttachments.COMBAT);
        if (first.active == null || first.active.depth != 1 || first.active.side != SwingSide.RIGHT) {
            helper.fail("first right click should be attack4 (right-hand right slash)");
            return;
        }
        helper.runAfterDelay(first.active.cancelStartTick, () -> {
            ServerCombat.handleAttack(player, SwingSide.RIGHT, false);
            CombatData second = player.getData(ModAttachments.COMBAT);
            if (second.active != null && second.active.depth == 0 && second.active.side == SwingSide.RIGHT) {
                helper.succeed();
            } else {
                helper.fail("second right click should be attack2 (left-hand right slash)");
            }
        });
    }

    private static ServerPlayer drawnDualPlayer(GameTestHelper helper) {
        ServerPlayer player = drawnPlayer(helper);
        player.setItemInHand(InteractionHand.OFF_HAND,
                new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
        return player;
    }

    private static void attackIntervalSurvivesSheath(GameTestHelper helper) {
        if (CombatConstants.DUAL_INTERVAL_TICKS != 9) {
            helper.fail("dual attack cancel gate must be nine server ticks (0.45 seconds)");
            return;
        }
        ServerPlayer[] players = {drawnDualPlayer(helper), drawnDualPlayer(helper), drawnDualPlayer(helper)};
        for (ServerPlayer player : players) {
            ServerCombat.handleAttack(player, SwingSide.LEFT, false);
            CombatData data = player.getData(ModAttachments.COMBAT);
            if (data.active == null) {
                helper.fail("setup: opening attack was rejected");
                return;
            }
            long started = data.active.startGameTime;
            ServerCombat.handleSetDrawn(player, false);
            ServerCombat.handleSetDrawn(player, true);
            if (data.active != null || data.lastAttackStartGameTime != started) {
                helper.fail("sheathing must clear the active clip but preserve its start time");
                return;
            }
        }
        helper.runAfterDelay(CombatConstants.DUAL_INTERVAL_TICKS - 1, () -> {
            for (int i = 0; i < players.length; i++) {
                ServerCombat.handleAttack(players[i], SwingSide.RIGHT, i == 1);
                if (players[i].getData(ModAttachments.COMBAT).active != null) {
                    helper.fail("attack variant " + i + " bypassed the 0.25s gate after sheathing");
                    return;
                }
            }
        });
        helper.runAfterDelay(CombatConstants.DUAL_INTERVAL_TICKS, () -> {
            for (int i = 0; i < players.length; i++) {
                ServerCombat.handleAttack(players[i], SwingSide.RIGHT, i == 1);
                CombatData data = players[i].getData(ModAttachments.COMBAT);
                if (data.active == null
                        || data.active.cancelStartTick != CombatConstants.DUAL_INTERVAL_TICKS
                        || data.active.charged != (i == 1)) {
                    helper.fail("attack variant " + i + " was rejected at the 0.25s gate");
                    return;
                }
            }
            helper.succeed();
        });
    }

    private static void attackPreservesWindup(GameTestHelper helper) {
        ServerPlayer player = drawnDualPlayer(helper);
        ServerCombat.handleAttack(player, SwingSide.RIGHT, false);
        CombatData data = player.getData(ModAttachments.COMBAT);
        CombatData.ActiveAttack first = data.active;
        if (first == null || first.activeStartTick != 2 || first.activeEndTick != 4
                || first.cancelStartTick != CombatConstants.DUAL_INTERVAL_TICKS) {
            helper.fail("setup: expected contact in the slash and cancel at the 0.25s gate");
            return;
        }
        data.parryReady = true;
        data.parryReadyUntilGameTime = first.startGameTime + 100;
        helper.runAfterDelay(first.activeStartTick - 1, () -> {
            ServerCombat.handleAttack(player, SwingSide.LEFT, false);
            ServerCombat.handleAttack(player, SwingSide.LEFT, true);
            ServerCombat.handleAttack(player, SwingSide.LEFT, false, true);
            if (data.active != first || !data.parryReady) {
                helper.fail("normal, charged, or dual attack interrupted the windup");
            }
        });
        helper.runAfterDelay(first.cancelStartTick - 1, () -> {
            ServerCombat.handleAttack(player, SwingSide.LEFT, false);
            ServerCombat.handleAttack(player, SwingSide.LEFT, true);
            ServerCombat.handleAttack(player, SwingSide.LEFT, false, true);
            if (data.active != first) {
                helper.fail("an attack interrupted before the recovery cancel point");
            } else {
                helper.succeed();
            }
        });
    }

    private static void recoveryCancelOpensAfterContact(GameTestHelper helper) {
        ServerPlayer[] players = {drawnDualPlayer(helper), drawnDualPlayer(helper), drawnDualPlayer(helper)};
        CombatData.ActiveAttack[] first = new CombatData.ActiveAttack[players.length];
        for (int i = 0; i < players.length; i++) {
            ServerCombat.handleAttack(players[i], SwingSide.LEFT, false);
            CombatData data = players[i].getData(ModAttachments.COMBAT);
            first[i] = data.active;
            if (first[i] == null || first[i].activeEndTick != 4
                    || first[i].cancelStartTick != CombatConstants.DUAL_INTERVAL_TICKS
                    || first[i].durationTicks != 8) {
                helper.fail("expected contact in the slash, cancel at 0.25s and recovery to 0.4s");
                return;
            }
        }
        helper.runAfterDelay(first[0].cancelStartTick, () -> {
            for (int i = 0; i < players.length; i++) {
                CombatData data = players[i].getData(ModAttachments.COMBAT);
                ServerCombat.handleAttack(players[i], SwingSide.RIGHT, i == 1);
                if (data.active == null || data.active == first[i]
                        || data.active.cancelStartTick != CombatConstants.DUAL_INTERVAL_TICKS
                        || data.active.charged != (i == 1)) {
                    helper.fail("normal or charged follow-up was rejected at the same recovery cancel point");
                    return;
                }
            }
            helper.succeed();
        });
    }

    private static void attackWithoutInputFinishesClip(GameTestHelper helper) {
        ServerPlayer player = drawnDualPlayer(helper);
        ServerCombat.handleAttack(player, SwingSide.LEFT, false);
        CombatData data = player.getData(ModAttachments.COMBAT);
        CombatData.ActiveAttack first = data.active;
        if (first == null) {
            helper.fail("setup: opening attack was rejected");
            return;
        }
        helper.runAfterDelay(first.durationTicks - 1, () -> {
            if (data.active != first) helper.fail("the clip ended before its full recovery completed");
        });
        helper.runAfterDelay(first.durationTicks + 1, () -> {
            if (data.active != null || data.lastAttackEndGameTime == Long.MIN_VALUE) {
                helper.fail("the clip did not end after its complete remapped recovery");
            } else {
                helper.succeed();
            }
        });
    }

    private static void chargedAttackUsesSameCancelPoint(GameTestHelper helper) {
        ServerPlayer player = drawnDualPlayer(helper);
        ServerCombat.handleAttack(player, SwingSide.LEFT, false);
        CombatData data = player.getData(ModAttachments.COMBAT);
        CombatData.ActiveAttack first = data.active;
        if (first == null) {
            helper.fail("setup: opening attack was rejected");
            return;
        }
        helper.runAfterDelay(first.cancelStartTick - 1, () -> {
            ServerCombat.handleAttack(player, SwingSide.RIGHT, true);
            if (data.active != first) helper.fail("charged attack bypassed protected initial recovery");
        });
        helper.runAfterDelay(first.cancelStartTick, () -> {
            ServerCombat.handleAttack(player, SwingSide.RIGHT, true);
            if (data.active == null || data.active == first || !data.active.charged
                    || data.active.durationTicks != 8
                    || data.active.cancelStartTick != CombatConstants.DUAL_INTERVAL_TICKS) {
                helper.fail("charged attack did not chain at the 0.25s gate");
            } else {
                helper.succeed();
            }
        });
    }

    private static void defendAndDodgeRespectCancelPoint(GameTestHelper helper) {
        ServerPlayer guardPlayer = drawnDualPlayer(helper);
        ServerPlayer dodgePlayer = drawnDualPlayer(helper);
        ServerCombat.handleAttack(guardPlayer, SwingSide.RIGHT, false);
        ServerCombat.handleAttack(dodgePlayer, SwingSide.RIGHT, false);
        CombatData guardData = guardPlayer.getData(ModAttachments.COMBAT);
        CombatData dodgeData = dodgePlayer.getData(ModAttachments.COMBAT);
        CombatData.ActiveAttack guardAttack = guardData.active;
        CombatData.ActiveAttack dodgeAttack = dodgeData.active;
        if (guardAttack == null || dodgeAttack == null) {
            helper.fail("setup: opening attacks were rejected");
            return;
        }
        dodgePlayer.setOnGround(false);
        ServerCombat.handleSetDefending(guardPlayer, true);
        ServerCombat.handleDodge(dodgePlayer, 0.0F, 1);
        if (guardData.active != guardAttack || guardData.defending) {
            helper.fail("guard canceled attack windup");
            return;
        }
        long dodgeNow = dodgePlayer.level().getGameTime();
        var speed = dodgePlayer.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (dodgeData.active != dodgeAttack || dodgeData.dodgeTicksLeft != 0
                || dodgeData.dodgeBoostUntilGameTime <= dodgeNow
                || speed == null || speed.getModifier(CombatAttributes.DUAL_DASH_BOOST) == null) {
            helper.fail("dual dodge must be a 0.3s speed boost mid-swing, even in the air,"
                    + " without canceling the attack or moving the player itself");
            return;
        }
        helper.runAfterDelay(guardAttack.cancelStartTick - 1, () -> {
            ServerCombat.handleSetDefending(guardPlayer, true);
            if (guardData.active != guardAttack || guardData.defending) {
                helper.fail("guard canceled before the recovery cancel point");
            }
        });
        helper.runAfterDelay(guardAttack.cancelStartTick, () -> {
            ServerCombat.handleSetDefending(guardPlayer, true);
            if (guardData.active != null || !guardData.defending) {
                helper.fail("guard did not cancel at the recovery cancel point");
                return;
            }
            long now = guardPlayer.level().getGameTime();
            if (guardData.cadence.previewIntervalTicks(now) != CombatConstants.DUAL_INTERVAL_TICKS
                    || dodgeData.cadence.previewIntervalTicks(now) != CombatConstants.DUAL_INTERVAL_TICKS) {
                helper.fail("guard or dodge reset accumulated attack cadence");
                return;
            }
            dodgeData.cadence.reset();
            ServerCombat.handleAttack(dodgePlayer, SwingSide.LEFT, false);
            if (dodgeData.active == null) {
                helper.fail("dual dodge must not block attacking");
            } else {
                helper.succeed();
            }
        });
    }

    /**
     * Dual swings land every 6 ticks, which is inside vanilla's 10-tick hurt cooldown:
     * without opening a damage window per contact, every other cut dealt nothing.
     */
    private static void dual_every_contact_lands(GameTestHelper h) {
        ServerPlayer p = drawnPlayer(h);
        Zombie z = h.spawn(EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        z.setNoAi(true);
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        p.setPos(o.getX() + 2.5, o.getY() + 1.0, o.getZ() + 0.5);
        p.setYRot(0.0F);
        p.setXRot(0.0F);
        p.yRotO = 0.0F;
        p.xRotO = 0.0F;
        var eye = p.getEyePosition();
        ServerCombat.handleAim(p, 0.0F, 0.0F, eye.x, eye.y, eye.z);
        CombatData d = p.getData(ModAttachments.COMBAT);
        float before = z.getHealth();

        ServerCombat.handleAttack(p, SwingSide.LEFT, false);
        CombatData.ActiveAttack first = d.active;
        require(first != null, "first dual swing must start");
        h.runAfterDelay(CombatConstants.DUAL_INTERVAL_TICKS, () -> {
            require(z.getHealth() < before, "first dual contact must damage");
            float afterFirst = z.getHealth();
            // The hit knocked the target back; put it back in the fan for the follow-up.
            z.setPos(o.getX() + 2.5, o.getY() + 1.0, o.getZ() + 2.5);
            z.setDeltaMovement(0.0, 0.0, 0.0);
            d.cadence.reset();
            ServerCombat.handleAttack(p, SwingSide.RIGHT, false);
            CombatData.ActiveAttack second = d.active;
            require(second != null && second != first, "second dual swing must start");
            h.runAfterDelay(second.activeEndTick + 1, () -> {
                require(z.getHealth() < afterFirst,
                        "second dual contact must damage through the vanilla hurt cooldown");
                h.succeed();
            });
        });
    }

    /**
     * Every dual clip shares the same runtime phases (contact tick 2..4, 0.4s clip) and
     * the 0.25s opening gate; the follow-up chains on that gate, not before it.
     */
    private static void allAttacksShareProgressiveCadence(GameTestHelper helper) {
        var players = new ArrayList<ServerPlayer>();
        var openings = new ArrayList<CombatData.ActiveAttack>();
        WeaponCategory category = WeaponCategory.DOUBLE;
        for (String clip : category.allAttacks()) {
            ServerPlayer player = drawnDualPlayer(helper);
            CombatData data = player.getData(ModAttachments.COMBAT);
            boolean dual = WeaponCategory.DUAL_ATTACK.equals(clip);
            if (dual) {
                // 格挡后攻击: the armed parry turns the next attack into the dual cut.
                data.parryReady = true;
                data.parryReadyUntilGameTime = player.level().getGameTime() + 100;
                ServerCombat.handleAttack(player, SwingSide.LEFT, false);
            } else {
                for (SwingSide side : SwingSide.values()) {
                    for (int hand = 0; hand < category.chain(side).length(); hand++) {
                        if (!clip.equals(category.attackAnim(side, hand))) continue;
                        if (hand == 0) data.rightHand = HandRest.of(side);
                        else data.leftHand = HandRest.of(side);
                        ServerCombat.handleAttack(player, side, false);
                    }
                }
            }
            CombatData.ActiveAttack opening = data.active;
            if (opening == null || opening.activeStartTick != 2 || opening.activeEndTick != 4
                    || opening.contactSeconds != 0.18F
                    || opening.cancelStartTick != CombatConstants.DUAL_INTERVAL_TICKS
                    || opening.durationTicks != 8
                    || opening.dual != dual) {
                helper.fail(category + "/" + clip + " does not share the dual runtime phases");
                return;
            }
            players.add(player);
            openings.add(opening);
        }
        helper.runAfterDelay(CombatConstants.DUAL_INTERVAL_TICKS - 1, () -> {
            for (int i = 0; i < players.size(); i++) {
                CombatData.ActiveAttack opening = openings.get(i);
                ServerCombat.handleAttack(players.get(i), opening.side.opposite(), false);
                if (players.get(i).getData(ModAttachments.COMBAT).active != opening) {
                    helper.fail("attack " + i + " chained before the 0.25s gate");
                    return;
                }
            }
        });
        helper.runAfterDelay(CombatConstants.DUAL_INTERVAL_TICKS, () -> {
            for (int i = 0; i < players.size(); i++) {
                CombatData.ActiveAttack opening = openings.get(i);
                ServerCombat.handleAttack(players.get(i), opening.side.opposite(), false);
                CombatData.ActiveAttack followup = players.get(i).getData(ModAttachments.COMBAT).active;
                if (followup == null || followup == opening
                        || followup.cancelStartTick != CombatConstants.DUAL_INTERVAL_TICKS) {
                    helper.fail("attack " + i + " could not chain at the 0.25s gate");
                    return;
                }
            }
            helper.succeed();
        });
    }

    /**
     * 双刀手部档位: 左左右右 never slows down, while the same hand's third swing in a
     * row takes the slow 0.5s gate.
     */
    private static void progressiveAttackCadence(GameTestHelper helper) {
        ServerPlayer alternating = drawnDualPlayer(helper);
        ServerPlayer repeating = drawnDualPlayer(helper);
        int[] alternatingGates = new int[4];
        int[] repeatingGates = new int[4];
        // 左左右右 is the player-facing pattern the brief calls out: no slow gate.
        dualStreakStep(helper, alternating, alternatingGates, 0,
                new SwingSide[]{SwingSide.LEFT, SwingSide.LEFT, SwingSide.RIGHT, SwingSide.RIGHT},
                () -> dualStreakStep(helper, repeating, repeatingGates, 0,
                        new SwingSide[]{SwingSide.LEFT, SwingSide.LEFT, SwingSide.RIGHT, SwingSide.LEFT},
                        () -> {
                            for (int gate : alternatingGates) {
                                if (gate != CombatConstants.DUAL_INTERVAL_TICKS) {
                                    helper.fail("左左右右 must never slow down: "
                                            + java.util.Arrays.toString(alternatingGates));
                                    return;
                                }
                            }
                            if (repeatingGates[0] != CombatConstants.DUAL_INTERVAL_TICKS
                                    || repeatingGates[1] != CombatConstants.DUAL_INTERVAL_TICKS
                                    || repeatingGates[2] != CombatConstants.DUAL_INTERVAL_TICKS
                                    || repeatingGates[3] != CombatConstants.DUAL_SLOW_INTERVAL_TICKS) {
                                helper.fail("the same hand's third swing must take the slow gate: "
                                        + java.util.Arrays.toString(repeatingGates));
                                return;
                            }
                            helper.succeed();
                        }));
    }

    private static void dualStreakStep(GameTestHelper helper, ServerPlayer player, int[] gates, int index,
                                       SwingSide[] sides, Runnable done) {
        if (index >= sides.length) {
            done.run();
            return;
        }
        CombatData data = player.getData(ModAttachments.COMBAT);
        data.cadence.reset();
        data.active = null;
        ServerCombat.handleAttack(player, sides[index], false);
        CombatData.ActiveAttack attack = data.active;
        require(attack != null, "dual swing " + index + " was rejected");
        gates[index] = attack.cancelStartTick;
        helper.runAfterDelay(attack.cancelStartTick,
                () -> dualStreakStep(helper, player, gates, index + 1, sides, done));
    }

    private static void idleRestoresAttackCadence(GameTestHelper helper) {
        prepareCadenceRecovery(helper, new ServerPlayer[]{drawnDualPlayer(helper), drawnDualPlayer(helper)}, 0);
    }

    private static void prepareCadenceRecovery(GameTestHelper helper, ServerPlayer[] players, int step) {
        int expectedGate = CombatConstants.DUAL_INTERVAL_TICKS;
        SwingSide side = step % 2 == 0 ? SwingSide.LEFT : SwingSide.RIGHT;
        for (ServerPlayer player : players) {
            ServerCombat.handleAttack(player, side, false);
            CombatData data = player.getData(ModAttachments.COMBAT);
            if (data.active == null || data.active.cancelStartTick != expectedGate) {
                helper.fail("setup: could not keep the dual 0.25s gate");
                return;
            }
        }
        if (step < 1) {
            helper.runAfterDelay(expectedGate, () -> prepareCadenceRecovery(helper, players, step + 1));
            return;
        }
        for (ServerPlayer player : players) {
            ServerCombat.handleSetDrawn(player, false);
            ServerCombat.handleSetDrawn(player, true);
        }
        helper.runAfterDelay(expectedGate, () -> {
            for (ServerPlayer player : players) {
                if (player.getData(ModAttachments.COMBAT).cadence
                        .previewIntervalTicks(player.level().getGameTime()) != expectedGate) {
                    helper.fail("sheath changed the dual attack cadence");
                    return;
                }
            }
            ServerCombat.handleAttack(players[0], SwingSide.LEFT, false);
            CombatData.ActiveAttack attack = players[0].getData(ModAttachments.COMBAT).active;
            if (attack == null || attack.cancelStartTick != expectedGate) {
                helper.fail("follow-up after sheath did not keep the 0.25s gate");
            } else {
                helper.succeed();
            }
        });
    }

    private static void rejectedInputPreservesCadence(GameTestHelper helper) {
        ServerPlayer player = drawnDualPlayer(helper);
        ServerCombat.handleAttack(player, SwingSide.LEFT, false);
        CombatData data = player.getData(ModAttachments.COMBAT);
        helper.runAfterDelay(CombatConstants.DUAL_INTERVAL_TICKS, () -> {
            ServerCombat.handleAttack(player, SwingSide.LEFT, false);
            CombatData.ActiveAttack second = data.active;
            if (second == null || second.cancelStartTick != CombatConstants.DUAL_INTERVAL_TICKS) {
                helper.fail("setup: second left slash was rejected");
                return;
            }
            helper.runAfterDelay(CombatConstants.DUAL_INTERVAL_TICKS, () -> {
                long now = player.level().getGameTime();
                CombatData.ActiveAttack before = data.active;
                for (int press = 0; press < 5; press++) {
                    ServerCombat.handleAttack(player, SwingSide.LEFT, false);
                }
                if (data.active != before
                        || data.cadence.previewIntervalTicks(now) != CombatConstants.DUAL_INTERVAL_TICKS
                        || !data.cadence.canStart(now)) {
                    helper.fail("invalid hand inputs consumed cadence");
                    return;
                }
                ServerCombat.handleAttack(player, SwingSide.RIGHT, false);
                if (data.active == null || data.active == before
                        || data.active.cancelStartTick != CombatConstants.DUAL_INTERVAL_TICKS) {
                    helper.fail("valid third slash did not keep the 0.45s gate after rejected inputs");
                } else {
                    helper.succeed();
                }
            });
        });
    }

    /**
     * 格挡后攻击: a guard that lands inside the 0.1s auto-guard window arms the dual cut,
     * so the next attack is the dual cut without pressing both buttons.
     */
    private static void dual_guard_arms_dual_cut(GameTestHelper h) {
        ServerPlayer p = drawnDualPlayer(h);
        CombatData d = p.getData(ModAttachments.COMBAT);
        ServerCombat.handleAttack(p, SwingSide.LEFT, false);
        long expectedWindow = p.level().getGameTime()
                + Math.round(CombatConstants.DUAL_AUTO_GUARD_SECONDS * 20.0F);
        require(d.autoGuardUntilGameTime == expectedWindow,
                "a dual swing must count as a guard for 0.1s");

        var container = new net.neoforged.neoforge.common.damagesource.DamageContainer(
                p.damageSources().generic(), 10.0F);
        var event = new net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent(
                p, container);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
        require(event.isCanceled(), "the 0.1s auto-guard window must block the hit");
        require(d.parryReady, "a successful auto-guard must arm the dual cut");

        d.cadence.reset();
        d.active = null;
        ServerCombat.handleAttack(p, SwingSide.RIGHT, false);
        require(d.active != null && d.active.dual,
                "the next attack after a guard must be the dual cut");
        require(!d.parryReady, "the armed dual cut is consumed by the attack");
        h.succeed();
    }

    /**
     * 强化刺击: 0.20s long with contact at half of it, and a landed charged thrust opens
     * the tightened flat 0.3s chain for 3s; a normal thrust is 0.50s long.
     */
    private static void rapier_charged_thrust_speeds_chain(GameTestHelper h) {
        ServerPlayer p = rapierPlayer(h);
        Zombie z = rapierTarget(h);
        CombatData d = p.getData(ModAttachments.COMBAT);
        d.katanaLockId = z.getId();

        ServerCombat.handleAttack(p, SwingSide.RIGHT, false);
        CombatData.ActiveAttack normal = d.active;
        require(normal != null && normal.thrust
                        && Math.abs(normal.contactSeconds
                                - CombatConstants.RAPIER_THRUST_SECONDS
                                        * CombatConstants.RAPIER_THRUST_CONTACT_SHARE) < 1.0E-4F,
                "a normal thrust must be 0.50s with contact at half of it");
        h.runAfterDelay(thrustScanDelay(d), () -> {
            d.active = null;
            d.cadence.reset();
            d.thrustReadyAt = Long.MIN_VALUE;
            d.thrustRecoveryUntil = Long.MIN_VALUE;
            z.invulnerableTime = 0;
            z.hurtTime = 0;
            // The 5-arg overload carries the charge/pierce flag for the thrust path.
            ServerCombat.handleAttack(p, SwingSide.RIGHT, false, false, true);
            CombatData.ActiveAttack charged = d.active;
            require(charged != null && charged.chargePiercingArmor
                            && Math.abs(charged.contactSeconds
                                    - CombatConstants.RAPIER_CHARGED_THRUST_SECONDS
                                            * CombatConstants.RAPIER_THRUST_CONTACT_SHARE) < 1.0E-4F,
                    "a charged thrust must be 0.20s with contact at half of it");
            h.runAfterDelay(thrustScanDelay(d), () -> {
                require(d.rapierFastUntilGameTime != Long.MIN_VALUE,
                        "a landed charged thrust must open the tightened gate window");
                int[] gates = new int[CombatConstants.RAPIER_GATE_FAST_TICKS.length];
                rapierGateStep(h, p, gates, 0, () -> {
                    for (int i = 0; i < gates.length; i++) {
                        if (gates[i] != CombatConstants.RAPIER_GATE_FAST_TICKS[i]) {
                            h.fail("charged-thrust chain gates must be "
                                    + java.util.Arrays.toString(CombatConstants.RAPIER_GATE_FAST_TICKS)
                                    + ", got " + java.util.Arrays.toString(gates));
                            return;
                        }
                    }
                    h.succeed();
                });
            });
        });
    }

    /**
     * 第四刀: the fourth slash gates 0.5s and its damage scan lands
     * {@link CombatConstants#RAPIER_FOURTH_CONTACT_DELAY_SECONDS} later than the rest.
     */
    private static void rapier_fourth_slash_contact_lags(GameTestHelper h) {
        ServerPlayer p = rapierPlayer(h);
        CombatData d = p.getData(ModAttachments.COMBAT);
        float base = AttackTimings.of(WeaponCategory.SINGLE, "attack4",
                CombatConstants.ATTACK_DURATION_SECONDS).activeEnd();
        for (int variant = 0; variant < 4; variant++) {
            d.cadence.reset();
            d.active = null;
            ServerCombat.handleAttack(p, SwingSide.LEFT, false);
            CombatData.ActiveAttack attack = d.active;
            require(attack != null, "setup: rapier slash " + variant + " was rejected");
            require(attack.cancelStartTick == CombatConstants.RAPIER_GATE_TICKS[variant],
                    "slash " + variant + " must gate "
                            + CombatConstants.RAPIER_GATE_TICKS[variant] + " ticks, got "
                            + attack.cancelStartTick);
            float want = variant == 3
                    ? base + CombatConstants.RAPIER_FOURTH_CONTACT_DELAY_SECONDS : base;
            require(Math.abs(attack.contactSeconds - want) < 1.0E-4F,
                    "slash " + variant + " contact must be " + want
                            + ", got " + attack.contactSeconds);
        }
        h.succeed();
    }

    /** 强化刺击 interrupts everything: it starts mid-swing; a plain poke still waits. */
    private static void charged_thrust_interrupts_everything(GameTestHelper h) {
        ServerPlayer p = rapierPlayer(h);
        CombatData d = p.getData(ModAttachments.COMBAT);
        ServerCombat.handleAttack(p, SwingSide.LEFT, false);
        CombatData.ActiveAttack slash = d.active;
        require(slash != null && slash.cancelStartTick > 1,
                "setup: the opening slash must still be locked");
        d.thrustReadyAt = Long.MIN_VALUE;
        // The 5-arg overload carries the charged/piercing flag.
        ServerCombat.handleAttack(p, SwingSide.RIGHT, false, false, true);
        require(d.active != null && d.active != slash && d.active.thrust
                        && d.active.chargePiercingArmor,
                "a charged thrust must cancel a swing in its windup");

        d.active = null;
        d.cadence.reset();
        ServerCombat.handleAttack(p, SwingSide.LEFT, false);
        CombatData.ActiveAttack second = d.active;
        require(second != null && second.cancelStartTick > 1,
                "setup: the second slash must still be locked");
        d.thrustReadyAt = Long.MIN_VALUE;
        ServerCombat.handleAttack(p, SwingSide.RIGHT, false);
        require(d.active == second,
                "a plain poke must respect the swing's cancel gate");
        h.succeed();
    }

    /** 闪避 interrupts everything: it cancels a swing in its windup. */
    private static void dodge_interrupts_everything(GameTestHelper h) {
        ServerPlayer p = rapierPlayer(h);
        CombatData d = p.getData(ModAttachments.COMBAT);
        p.setOnGround(true);
        ServerCombat.handleAttack(p, SwingSide.LEFT, false);
        require(d.active != null && d.active.cancelStartTick > 1,
                "setup: the opening slash must still be locked");
        ServerCombat.handleDodge(p, 0.0F, 1);
        require(d.active == null && d.dodgeTicksLeft > 0,
                "a dodge must cancel a swing in its windup");
        h.succeed();
    }

    /**
     * 双刀 extends only the animation: the clip's last frame is held an extra 0.1s while
     * the cancel window stays at {@link CombatConstants#DUAL_INTERVAL_TICKS} ticks.
     */
    private static void dual_last_frame_hold_keeps_cancel_window(GameTestHelper h) {
        ServerPlayer p = drawnDualPlayer(h);
        CombatData d = p.getData(ModAttachments.COMBAT);
        ServerCombat.handleAttack(p, SwingSide.LEFT, false);
        CombatData.ActiveAttack swing = d.active;
        require(swing != null, "setup: the dual swing was rejected");
        require(swing.cancelStartTick == CombatConstants.DUAL_INTERVAL_TICKS,
                "the cancel window must stay at " + CombatConstants.DUAL_INTERVAL_TICKS
                        + " ticks, got " + swing.cancelStartTick);
        float clip = AttackTimings.of(WeaponCategory.DOUBLE, "attack_1",
                CombatConstants.ATTACK_DURATION_SECONDS).length();
        require(Math.abs(AttackTimings.playbackSeconds(WeaponCategory.DOUBLE, clip)
                        - clip - CombatConstants.DUAL_ATTACK_LAST_FRAME_HOLD_SECONDS) < 1.0E-4F,
                "dual playback must hold the final frame "
                        + CombatConstants.DUAL_ATTACK_LAST_FRAME_HOLD_SECONDS + "s longer");
        require(Math.abs(AttackTimings.playbackSeconds(WeaponCategory.SINGLE, clip) - clip) < 1.0E-4F,
                "single-wield playback must not hold the final frame");
        h.succeed();
    }

    private static void rapierGateStep(GameTestHelper h, ServerPlayer p, int[] gates, int index,
                                       Runnable done) {
        if (index >= gates.length) {
            done.run();
            return;
        }
        CombatData d = p.getData(ModAttachments.COMBAT);
        d.cadence.reset();
        d.active = null;
        ServerCombat.handleAttack(p, SwingSide.LEFT, false);
        require(d.active != null, "rapier slash " + index + " was rejected");
        gates[index] = d.active.cancelStartTick;
        h.runAfterDelay(d.active.cancelStartTick,
                () -> rapierGateStep(h, p, gates, index + 1, done));
    }

    private ModGameTests() {}

    private static ServerPlayer rapierPlayer(GameTestHelper helper) {
        ServerPlayer p = drawnPlayer(helper);
        p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        p.setPos(o.getX()+2.5, o.getY()+1, o.getZ()+1.0);
        p.setYRot(0); p.setXRot(0);
        var eye=p.getEyePosition(); ServerCombat.handleAim(p,0,0,eye.x,eye.y,eye.z);
        return p;
    }
    private static Zombie rapierTarget(GameTestHelper h) {
        Zombie z=h.spawn(EntityType.ZOMBIE,new BlockPos(2,1,3));z.setNoAi(true);return z;
    }
    private static void require(boolean value,String message) {
        if(!value)throw new IllegalStateException(message);
    }
    /**
     * Delay until a callback can observe the thrust's contact scan. GameTest callbacks
     * run before the server's tick handler, so they see the previous tick's state: the
     * scan lands on {@code start + ceil(contact * 20)} and this returns one more.
     */
    private static int thrustScanDelay(CombatData d) {
        CombatData.ActiveAttack a = d.active;
        require(a != null && a.thrust, "thrust must be scheduled");
        require(!a.scanned, "thrust must not scan on the request tick");
        return Math.max(1, (int) Math.ceil(a.contactSeconds * 20.0F - 1.0E-4F) + 1);
    }
    private static void rapier_instant_hit(GameTestHelper h) {
        ServerPlayer p=rapierPlayer(h);Zombie z=rapierTarget(h);float before=z.getHealth();
        long now=p.level().getGameTime();CombatData d=p.getData(ModAttachments.COMBAT);
        d.katanaLockId = z.getId();
        ServerCombat.handleAttack(p,SwingSide.RIGHT,false);
        require(z.getHealth()==before,"thrust must not damage before the blade extends");
        require(d.active!=null && d.active.thrust,"thrust must stay active until contact");
        require(d.thrustReadyAt==now+40,"hit cooldown must be 40 ticks");
        int wait = thrustScanDelay(d);
        h.runAfterDelay(wait, () -> {
            require(z.getHealth()<before,"thrust must damage when the blade extends");
            require(d.thrustRecoveryUntil==now+wait-1,"hit must have no recovery");
            d.cadence.reset();
            ServerCombat.handleAttack(p,SwingSide.LEFT,false);
            require(d.active!=null && !d.active.thrust,"normal attack must be available after a hit");
            h.succeed();
        });
    }
    private static void rapier_miss_recovery(GameTestHelper h) {
        ServerPlayer p=rapierPlayer(h);CombatData d=p.getData(ModAttachments.COMBAT);long now=p.level().getGameTime();
        ServerCombat.handleAttack(p,SwingSide.RIGHT,false);
        require(d.thrustReadyAt==now+40,"miss cooldown must latch on the request");
        int wait = thrustScanDelay(d);
        h.runAfterDelay(wait, () -> {
            long scan = now + wait - 1;
            require(d.thrustRecoveryUntil==scan+20,"miss recovery starts at contact");
            ServerCombat.handleAttack(p,SwingSide.LEFT,false);require(d.active==null || d.active.thrust,"miss blocks attacks");
            ServerCombat.handleSetDefending(p,true);require(!d.defending,"miss blocks guard");
            ServerCombat.handleSetDrawn(p,false);ServerCombat.handleSetDrawn(p,true);
            require(d.thrustReadyAt==now+40 && d.thrustRecoveryUntil==scan+20,"sheathing cannot clear cooldown/recovery");
            h.runAfterDelay(18,()->{ServerCombat.handleAttack(p,SwingSide.LEFT,false);require(d.active==null,"recovery opens too early");});
            h.runAfterDelay(20,()->{d.cadence.reset();ServerCombat.handleAttack(p,SwingSide.LEFT,false);require(d.active!=null,"recovery must open at 1s");});
            h.runAfterDelay(39-wait,()->{long ready=d.thrustReadyAt;ServerCombat.handleAttack(p,SwingSide.RIGHT,false);require(d.thrustReadyAt==ready,"cooldown opens too early");});
            h.runAfterDelay(40-wait,()->{
                d.cadence.reset();
                d.active=null;
                d.thrustRecoveryUntil=Long.MIN_VALUE;
                ServerCombat.handleAttack(p,SwingSide.RIGHT,false);
                require(d.thrustReadyAt==now+80,"cooldown must open at 2s");
                h.succeed();
            });
        });
    }
    private static void rapier_aim_and_wall(GameTestHelper h) {
        ServerPlayer p=rapierPlayer(h);Zombie z=rapierTarget(h);
        CombatData d=p.getData(ModAttachments.COMBAT);
        ServerCombat.handleKatanaLock(p, z.getId());
        require(d.katanaLockId == z.getId(), "lock must accept a visible target in range");
        Zombie far = h.spawn(EntityType.ZOMBIE, new BlockPos(2, 1, 20));
        far.setNoAi(true);
        ServerCombat.handleKatanaLock(p, far.getId());
        require(d.katanaLockId == z.getId(), "lock must reject a target beyond 12 blocks");
        h.succeed();
    }
    private static void rapier_random_attacks(GameTestHelper h) {
        ServerPlayer p=rapierPlayer(h);p.getRandom().setSeed(42);java.util.Set<String> clips=new java.util.HashSet<>();
        for(int i=0;i<12;i++){h.runAfterDelay(i*9,()->{
            CombatData d=p.getData(ModAttachments.COMBAT);
            d.cadence.reset(); d.active=null;
            ServerCombat.handleAttack(p,SwingSide.LEFT,false);
            require(d.active!=null && d.active.startGameTime==p.level().getGameTime(),"single must repeat without hand-side gating");
            clips.add(d.active.side+":"+d.active.depth);
        });}
        h.runAfterDelay(110,()->{require(clips.size()>1,"specific attack variants must vary");h.succeed();});
    }
    private static void guard_window_boundaries(GameTestHelper h) {
        ServerPlayer p=drawnPlayer(h);ServerCombat.handleSetDefending(p,true);CombatData d=p.getData(ModAttachments.COMBAT);long t=d.defendStartGameTime;
        require(!ServerCombat.isPerfectGuard(d,t),"tick zero must not parry");
        require(ServerCombat.defendReduction(d,t)==0,"tick zero must not fully block");
        for(int i=1;i<=6;i++)require(ServerCombat.isPerfectGuard(d,t+i),"ticks 1 through 6 must parry");
        require(!ServerCombat.isPerfectGuard(d,t+7),"tick 7 is outside parry window");h.succeed();
    }
    private static void dual_guard_bash_once(GameTestHelper h) {
        ServerPlayer p=rapierPlayer(h);p.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
        Zombie z=rapierTarget(h);z.setPos(p.getX(),p.getY(),p.getZ()+1.2);Zombie side=h.spawn(EntityType.ZOMBIE,new BlockPos(3,1,1));side.setNoAi(true);
        side.setPos(p.getX()+1.2,p.getY(),p.getZ());float health=z.getHealth();
        ServerCombat.handleSetDefending(p,true);p.setSprinting(true);ServerCombat.tryGuardBash(p);
        require(z.getDeltaMovement().horizontalDistanceSqr()>0 && side.getDeltaMovement().horizontalDistanceSqr()>0,"radial bash must shove front and side targets");
        require(z.getHealth()==health,"bash should not add attack damage");
        z.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);ServerCombat.tryGuardBash(p);
        require(z.getDeltaMovement().horizontalDistanceSqr()==0,"same guard may only bash once");h.succeed();
    }

    private static void dual_auto_guard(GameTestHelper h) {
        ServerPlayer p = drawnDualPlayer(h);
        ServerCombat.handleAttack(p, SwingSide.RIGHT, false);
        CombatData d = p.getData(ModAttachments.COMBAT);
        require(d.active != null, "dual swing must start");
        require(d.autoGuardUntilGameTime == p.level().getGameTime()
                        + Math.round(CombatConstants.DUAL_AUTO_GUARD_SECONDS * 20.0F),
                "dual swing must count as a guard for 0.1s");
        h.succeed();
    }

    /** Rapier thrusts never shove the target — the sword is about lines, not impact. */
    private static void rapier_no_knockback(GameTestHelper h) {
        ServerPlayer p = rapierPlayer(h);
        Zombie z = rapierTarget(h);
        z.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        float before = z.getHealth();
        p.getData(ModAttachments.COMBAT).katanaLockId = z.getId();
        ServerCombat.handleAttack(p, SwingSide.RIGHT, false);
        require(z.getHealth() == before, "rapier thrust must wait for the extend");
        // Vanilla playerAttack applies a small built-in impulse (~0.4 m/s). Rapier sets
        // RAPIER_NO_KNOCKBACK=true, so we add no extra shove. Allow that residual for
        // any post-tick knockback and assert a generous upper bound against it.
        h.runAfterDelay(thrustScanDelay(p.getData(ModAttachments.COMBAT)), () -> {
            require(z.getHealth() < before, "rapier thrust must damage");
            require(z.getDeltaMovement().horizontalDistanceSqr() < 0.2,
                    "rapier thrust must not add extra knockback on top of the vanilla "
                            + "leftover (got "
                            + z.getDeltaMovement().horizontalDistanceSqr() + ")");
            h.succeed();
        });
    }

    /** Rapier can block on X, same as dual-wield. */
    private static void rapier_can_block(GameTestHelper h) {
        ServerPlayer p = rapierPlayer(h);
        CombatData d = p.getData(ModAttachments.COMBAT);
        ServerCombat.handleSetDefending(p, true);
        require(d.defending, "rapier must raise a guard");
        require(ServerCombat.categoryOf(p) == WeaponCategory.SINGLE, "rapier player must hold a single weapon");
        h.succeed();
    }

    /**
     * Charged rapier thrust ignores 30 % of the target's armor. We compare two zombies:
     *  - {@code bare}: rapier thrust with no charge, no armor → baseline damage.
     *  - {@code armored}: 14 armor worth of equipment + the charge marker.
     * The charged hit on the armored target must deal ≥ the same damage as the
     * uncharged hit on the bare target — because the 30 %-pierce formula is calibrated
     * to keep that property. (Damage values chosen so armor alone could otherwise
     * absorb enough that the charged hit is unambiguously larger.)
     */
    private static void rapier_charge_pierces_armor(GameTestHelper h) {
        ServerPlayer p = rapierPlayer(h);

        // Baseline: uncharged rapier thrust at the bare zombie.
        Zombie bare = h.spawn(EntityType.ZOMBIE, new BlockPos(4, 1, 3));
        bare.setNoAi(true);
        bare.setPos(p.getX(), p.getY(), p.getZ() + 2.0);
        var eye = p.getEyePosition();
        ServerCombat.handleAim(p, 0, 0, eye.x, eye.y, eye.z);
        float bareBefore = bare.getHealth();
        ServerCombat.handleAttack(p, SwingSide.RIGHT, false);
        CombatData d = p.getData(ModAttachments.COMBAT);
        h.runAfterDelay(thrustScanDelay(d), () -> {
            float bareDamage = bareBefore - bare.getHealth();
            require(bareDamage > 0.0F, "baseline rapier thrust must deal damage");
            d.thrustReadyAt = Long.MIN_VALUE;
            d.thrustRecoveryUntil = Long.MIN_VALUE;
            d.active = null;
            d.cadence.reset();
            bare.discard();

            Zombie armored = h.spawn(EntityType.ZOMBIE, new BlockPos(5, 1, 3));
            armored.setNoAi(true);
            armored.setPos(p.getX(), p.getY(), p.getZ() + 2.0);
            var armorAttr = armored.getAttributes()
                    .getInstance(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
            require(armorAttr != null, "zombie must expose an armor attribute");
            armorAttr.setBaseValue(16.0);
            ServerCombat.handleAim(p, 0, 0, eye.x, eye.y, eye.z);
            d.katanaLockId = armored.getId();
            float armoredBefore = armored.getHealth();
            ServerCombat.handleAttack(p, SwingSide.RIGHT, false);
            h.runAfterDelay(thrustScanDelay(d), () -> {
                float unchargedPierce = armoredBefore - armored.getHealth();
                require(unchargedPierce > 0.0F, "rapier thrust on armored zombie must damage");
                armored.invulnerableTime = 0;
                armored.hurtTime = 0;
                d.thrustReadyAt = Long.MIN_VALUE;
                d.thrustRecoveryUntil = Long.MIN_VALUE;
                d.active = null;
                d.cadence.reset();
                d.chargeReady = true;
                d.chargeReadyAt = p.level().getGameTime();
                ServerCombat.handleAim(p, 0, 0, eye.x, eye.y, eye.z);
                float armoredBefore2 = armored.getHealth();
                ServerCombat.handleAttack(p, SwingSide.RIGHT, false);
                h.runAfterDelay(thrustScanDelay(d), () -> {
                    float chargedPierce = armoredBefore2 - armored.getHealth();
                    require(chargedPierce > unchargedPierce,
                            "charged thrust must deal more damage than uncharged on the same armored target ("
                                    + chargedPierce + " vs " + unchargedPierce + ")");
                    require(!d.chargeReady, "charge must be spent on a successful hit");
                    h.succeed();
                });
            });
        });
    }

    private static ServerPlayer katanaPlayer(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        p.setPos(o.getX() + 2.5, o.getY() + 1.0, o.getZ() + 1.0);
        p.setYRot(0.0F);
        p.setXRot(0.0F);
        return p;
    }

    private static void katana_left_hand_draw(GameTestHelper h) {
        ServerPlayer p = katanaPlayer(h);
        ServerCombat.handleSetDrawn(p, true, true);
        CombatData d = p.getData(ModAttachments.COMBAT);
        require(d.drawn && !d.katanaStance, "left-hand draw is now the rapier");
        require(p.getOffhandItem().is(net.minecraft.tags.ItemTags.SWORDS),
                "offhand sword must stay in the offhand");
        require(ServerCombat.categoryOf(p) == WeaponCategory.SINGLE, "category must be rapier");
        h.succeed();
    }

    private static void katana_command_attack(GameTestHelper h) {
        ServerPlayer p = katanaPlayer(h);
        ServerCombat.handleSetDrawn(p, true, true);
        CombatData d = p.getData(ModAttachments.COMBAT);
        Zombie locked = h.spawn(EntityType.ZOMBIE, new BlockPos(2, 1, 3));
        locked.setNoAi(true);
        Zombie bystander = h.spawn(EntityType.ZOMBIE, new BlockPos(3, 1, 1));
        bystander.setNoAi(true);
        d.katanaLockId = locked.getId();
        float lockBefore = locked.getHealth();
        float sideBefore = bystander.getHealth();
        ServerCombat.handleAttack(p, SwingSide.LEFT, false);
        CombatData.ActiveAttack attack = d.active;
        require(attack != null && attack.category == WeaponCategory.SINGLE, "rapier attack must start");
        int contact = Math.max(1, (int) Math.ceil(attack.contactSeconds * 20.0F - 1.0E-4F));
        h.runAfterDelay(contact + 1, () -> {
            require(attack.scanned, "rapier attack must scan at contact");
            require(locked.getHealth() < lockBefore, "locked target must take the command hit");
            require(bystander.getHealth() == sideBefore, "command attack must not hit a different enemy");
            h.succeed();
        });
    }

    /**
     * The katana stance is cut: a left-hand-only sword is the rapier, so the follow-up
     * slash takes the rapier's progressive gate (0.30s opening, 0.45s late combo).
     */
    private static void left_hand_sword_cadence(GameTestHelper h) {
        ServerPlayer p = katanaPlayer(h);
        ServerCombat.handleSetDrawn(p, true, true);
        CombatData d = p.getData(ModAttachments.COMBAT);
        Zombie z = h.spawn(EntityType.ZOMBIE, new BlockPos(2, 1, 3));
        z.setNoAi(true);
        d.katanaLockId = z.getId();
        ServerCombat.handleAttack(p, SwingSide.LEFT, false);
        CombatData.ActiveAttack first = d.active;
        int opening = CombatConstants.RAPIER_GATE_TICKS[0];
        require(first != null && first.cancelStartTick == opening,
                "left-hand sword must open on the rapier's 0.25s gate");
        ServerCombat.handleAttack(p, SwingSide.LEFT, false);
        require(d.active == first, "second slash before the gate must be rejected");
        h.runAfterDelay(opening, () -> {
            z.invulnerableTime = 0;
            ServerCombat.handleAttack(p, SwingSide.LEFT, false);
            CombatData.ActiveAttack second = d.active;
            require(second != null && second != first
                            && second.cancelStartTick == CombatConstants.RAPIER_GATE_TICKS[1],
                    "second slash must keep the rapier's 0.25s gate");
            d.cadence.reset();
            d.active = null;
            ServerCombat.handleAttack(p, SwingSide.LEFT, false);
            CombatData.ActiveAttack third = d.active;
            require(third != null && third != second
                            && third.cancelStartTick == CombatConstants.RAPIER_GATE_TICKS[2],
                    "third slash must take the rapier's 0.45s late gate");
            h.succeed();
        });
    }

    private static void rapier_combo_cancels_ai(GameTestHelper h) {
        ServerPlayer p = rapierPlayer(h);
        Zombie z = rapierTarget(h);
        z.setNoAi(false);
        // The probe keeps its AI on (that is what the test observes) but must not wander
        // out of lock range, and must survive four 6-damage slashes plus the bonus thrust.
        var maxHealth = z.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (maxHealth != null) maxHealth.setBaseValue(200.0);
        var speed = z.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0.0);
        z.setHealth(200.0F);
        CombatData d = p.getData(ModAttachments.COMBAT);
        swingRapierCombo(h, p, z, d, 0, () -> {
            require(z.isNoAi(), "every 4th rapier hit must cancel the target AI");
            require(d.rapierThrustBonusUntil != Long.MIN_VALUE, "4-hit mark must arm a 250% thrust");
            h.runAfterDelay(19, () -> require(z.isNoAi(), "1s silence still active at tick 19"));
            h.runAfterDelay(21, () -> {
                require(!z.isNoAi(), "AI must resume after 1s");
                float before = z.getHealth();
                z.invulnerableTime = 0;
                z.hurtTime = 0;
                d.cadence.reset();
                d.active = null;
                d.thrustReadyAt = Long.MIN_VALUE;
                d.thrustRecoveryUntil = Long.MIN_VALUE;
                d.katanaLockId = z.getId();
                ServerCombat.handleAttack(p, SwingSide.RIGHT, false);
                h.runAfterDelay(thrustScanDelay(d), () -> {
                    float bonus = before - z.getHealth();
                    require(bonus > 9.0F * 2.0F, "marked thrust must deal 250% (" + bonus + ")");
                    require(d.rapierThrustBonusUntil == Long.MIN_VALUE, "250% thrust is consumed");
                    h.succeed();
                });
            });
        });
    }

    private static void swingRapierCombo(GameTestHelper h, ServerPlayer p, Zombie z, CombatData d,
                                         int hits, Runnable onFour) {
        if (hits >= CombatConstants.RAPIER_AI_CANCEL_HITS) {
            onFour.run();
            return;
        }
        z.invulnerableTime = 0;
        z.hurtTime = 0;
        d.cadence.reset();
        d.active = null;
        d.lastAttackStartGameTime = Long.MIN_VALUE;
        d.katanaLockId = z.getId();
        var eye = p.getEyePosition();
        ServerCombat.handleAim(p, 0.0F, 0.0F, eye.x, eye.y, eye.z);
        ServerCombat.handleAttack(p, SwingSide.LEFT, false);
        CombatData.ActiveAttack attack = d.active;
        require(attack != null, "rapier slash " + (hits + 1) + " was rejected");
        int contact = Math.max(1, (int) Math.ceil(attack.contactSeconds * 20.0F - 1.0E-4F));
        h.runAfterDelay(contact + 1, () -> {
            require(attack.scanned, "rapier slash " + (hits + 1) + " did not scan");
            swingRapierCombo(h, p, z, d, hits + 1, onFour);
        });
    }

    private static ServerPlayer spearPlayer(GameTestHelper h) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.IRON_SPEAR));
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        p.setPos(o.getX() + 2.5, o.getY() + 1, o.getZ() + 2.5);
        p.setYRot(0); p.setXRot(0); p.setOnGround(true);
        ServerCombat.handleSetDrawn(p, true);
        var eye = p.getEyePosition();
        ServerCombat.handleAim(p, 0, 0, eye.x, eye.y, eye.z);
        return p;
    }

    private static void spear_actions(GameTestHelper h) {
        ServerPlayer p = spearPlayer(h); CombatData d = p.getData(ModAttachments.COMBAT);
        require(ServerCombat.categoryOf(p) == WeaponCategory.SPEAR && d.drawn, "vanilla spear must enter spear stance");
        ServerCombat.handleAttack(p, SwingSide.LEFT, false);
        require(d.active != null && d.active.depth == 0 && d.active.category == WeaponCategory.SPEAR, "LMB two-hand poke");
        d.active = null; d.cadence.reset();
        ServerCombat.handleAttack(p, SwingSide.LEFT, true);
        require(d.active != null && d.active.depth == 1, "C+LMB long thrust");
        d.active = null; d.cadence.reset();
        ServerCombat.handleAttack(p, SwingSide.RIGHT, true);
        require(d.active != null && d.active.side == SwingSide.RIGHT && !d.active.charged, "C+RMB shaft bash, no sword charge multiplier");
        PlayerAnimatable dummy = new PlayerAnimatable(ModEntities.PLAYER_DUMMY.get(), h.getLevel());
        dummy.weaponCategory = WeaponCategory.SPEAR; dummy.drawn = true; dummy.moveSpeed = 1;
        require(dummy.locomotionClip(System.nanoTime()).equals("walk_hold"), "early sprint keeps one-hand ready carry");
        dummy.spearSprintCarry = true;
        require(dummy.locomotionClip(System.nanoTime()).equals("run_carry"), "sustained sprint switches to middle grip behind back");
        dummy.playUpper("attack3", .4F, 8);
        require(dummy.hasFullBodyAction(), "long lunge must own bracing legs");
        dummy.discard(); h.succeed();
    }

    private static void spear_range_and_walls(GameTestHelper h) {
        ServerPlayer p = spearPlayer(h);
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        p.setPos(o.getX()+.5,o.getY()+1,o.getZ()+.5);
        var eye = p.getEyePosition(); ServerCombat.handleAim(p,-45,0,eye.x,eye.y,eye.z);
        Zombie z = h.spawn(EntityType.ZOMBIE, new BlockPos(3,1,3)); z.setNoAi(true); z.setBaby(false);
        z.setPos(o.getX()+3.3,p.getY(),o.getZ()+3.3);
        var normal = new CombatData.ActiveAttack(SwingSide.LEFT, 0, false, false, 0, .4F);
        var far = new CombatData.ActiveAttack(SwingSide.LEFT, 1, false, false, 0, .4F);
        require(com.actioncombat.combat.SpearCombat.target(p, normal) == null, "normal poke must not reach 3.8 blocks");
        require(com.actioncombat.combat.SpearCombat.target(p, far) == z, "long poke must reach 3.8 blocks");
        h.setBlock(new BlockPos(2, 2, 2), net.minecraft.world.level.block.Blocks.STONE);
        require(com.actioncombat.combat.SpearCombat.target(p, far) == null, "spear must not pass through wall");
        h.succeed();
    }

    private static void spear_speed_and_riding(GameTestHelper h) {
        var forward = new net.minecraft.world.phys.Vec3(0,0,1);
        require(Math.abs(com.actioncombat.combat.SpearMoves.speedMultiplier(forward.scale(.05), net.minecraft.world.phys.Vec3.ZERO, forward)-1.15F)<.0001, "+1 block/s = +15 percent");
        require(Math.abs(com.actioncombat.combat.SpearMoves.speedMultiplier(forward.scale(.1), forward.scale(-.05), forward)-1.45F)<.0001, "opposing movement adds closing speed");
        require(com.actioncombat.combat.SpearMoves.speedMultiplier(forward.scale(-1), net.minecraft.world.phys.Vec3.ZERO, forward)==1, "retreat adds no thrust energy");
        ServerPlayer p = spearPlayer(h); p.setSprinting(true); p.startFallFlying(); p.setKnownMovement(forward.scale(2));
        com.actioncombat.combat.SpearCombat.finishPoke(p);
        require(!p.isSprinting() && Math.abs(p.getDeltaMovement().z-1)<.0001, "poke halves gliding speed and stops sprint");
        p.stopFallFlying();
        var horse = h.spawn(EntityType.HORSE, new BlockPos(2,1,2));
        p.startRiding(horse, true, true); p.setSprinting(true); p.setDeltaMovement(forward);
        com.actioncombat.combat.SpearCombat.finishPoke(p);
        require(p.isPassenger() && p.isSprinting() && p.getDeltaMovement().equals(forward), "riding must preserve speed and sprint state");
        h.succeed();
    }

    private static void spear_guard_and_steps(GameTestHelper h) {
        ServerPlayer p = spearPlayer(h); CombatData d = p.getData(ModAttachments.COMBAT);
        ServerCombat.handleSetDefending(p, true);
        Zombie z = h.spawn(EntityType.ZOMBIE, new BlockPos(2,1,4)); z.setNoAi(true);
        z.setPos(p.getX(),p.getY(),p.getZ()+1);
        var hit = new net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent(p,
                new net.neoforged.neoforge.common.damagesource.DamageContainer(p.damageSources().mobAttack(z), 10));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(hit);
        require(hit.isCanceled(), "held horizontal shaft blocks front");
        z.setPos(p.getX(),p.getY(),p.getZ()-1);
        var rear = new net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent(p,
                new net.neoforged.neoforge.common.damagesource.DamageContainer(p.damageSources().mobAttack(z),10));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(rear);
        require(!rear.isCanceled(), "shaft guard must not block rear");
        ServerCombat.handleSetDefending(p,false); require(!d.defending,"RMB release lowers guard immediately");
        ServerCombat.handleDodge(p,0,1);
        require(d.dodgeTicksLeft==4 && Math.abs(d.dodgeDz*4+1)<.0001 && Math.abs(d.dodgeDx)<.0001,"Shift must go backward 1 block over 4 ticks regardless of W");
        var start=z.position();
        com.actioncombat.combat.SpearCombat.push(z,new net.minecraft.world.phys.Vec3(2,0,0));
        for(int i=0;i<4;i++)com.actioncombat.combat.SpearCombat.tick(p.level().getServer());
        require(Math.abs(z.getX()-start.x-2)<.01,"shaft push moves 2 blocks on clear ground");
        h.succeed();
    }

    private static void spear_poke_contact(GameTestHelper h) {
        ServerPlayer p = spearPlayer(h);
        var cow = h.spawn(EntityType.COW,new BlockPos(2,1,4)); cow.setNoAi(true);
        cow.setPos(p.getX(),p.getY(),p.getZ()+1.5);
        // Aim at the animal's chest so this also exercises the pitched spear ray.
        var eye = p.getEyePosition();
        ServerCombat.handleAim(p,0,25,eye.x,eye.y,eye.z);
        p.setKnownMovement(new net.minecraft.world.phys.Vec3(0,0,.1)); p.setSprinting(true);
        ServerCombat.handleAttack(p,SwingSide.LEFT,false);
        float before = cow.getHealth();
        h.runAfterDelay(5,()-> {
            double closing = 2 * 1.5 / Math.sqrt(1.5*1.5 + Math.pow(cow.getY()-p.getY(),2));
            float expected = (float)(6*(1+.15*closing));
            require(Math.abs(before-cow.getHealth()-expected)<.03,"normal poke must apply relative speed damage once at contact");
            require(!p.isSprinting(),"contact must stop sprint");
            float after = cow.getHealth();
            h.runAfterDelay(4,()-> { require(cow.getHealth()==after,"one poke must not hit a second time"); h.succeed(); });
        });
    }
}
