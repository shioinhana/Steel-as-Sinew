package com.actioncombat;

import com.actioncombat.combat.AttackTimings;
import com.actioncombat.entity.PlayerAnimatable;
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

        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(2, 1, 3));
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

    private static void defendReducesDamage(GameTestHelper helper) {
        ServerPlayer player = drawnPlayer(helper);
        // mock players hardcode GameType.CREATIVE -> abilities.invulnerable; undo it so the hit lands
        player.getAbilities().invulnerable = false;

        ServerCombat.handleSetDefending(player, true);
        if (!player.getData(ModAttachments.COMBAT).defending) {
            helper.fail("guard request was not accepted while drawn");
            return;
        }

        float before = player.getHealth();
        // mock players never send the client-load ack (embedded connection) and the
        // server treats not-yet-loaded clients as invulnerable for 60 ticks; drain it
        for (int i = 0; i < 60; i++) {
            player.connection.tickClientLoadTimeout();
        }
        player.hurtServer(helper.getLevel(), player.damageSources().generic(), 10.0F);
        float lost = before - player.getHealth();
        if (lost > 0.01F) {
            helper.fail("tap/perfect guard should block all 10 damage, lost " + lost);
            return;
        }
        helper.runAfterDelay(10, () -> {
            float beforeHold = player.getHealth();
            player.hurtServer(helper.getLevel(), player.damageSources().generic(), 10.0F);
            float holdLost = beforeHold - player.getHealth();
            if (Math.abs(holdLost - 5.0F) > 0.05F) {
                helper.fail("hold guard at 0.5s should let 50% through, lost " + holdLost);
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
                ServerCombat.handleAttack(player, SwingSide.LEFT, false);
                CombatData d = player.getData(ModAttachments.COMBAT);
                if (d.active == second && d.active.side == SwingSide.LEFT) {
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

        if (!"attack1".equals(cat.attackAnim(SwingSide.LEFT, 0))
                || !"attack3".equals(cat.attackAnim(SwingSide.LEFT, 1))
                || !"attack2".equals(cat.attackAnim(SwingSide.RIGHT, 0))
                || !"attack4".equals(cat.attackAnim(SwingSide.RIGHT, 1))) {
            helper.fail("expected 左手左砍/右手左砍/左手右砍/右手右砍 = attack1/3/2/4");
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
        if (CombatConstants.ATTACK_INTERVAL_TICKS != 6) {
            helper.fail("attack cancel gate must be six server ticks (0.30 seconds)");
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
            data.parryReady = true;
            data.parryReadyUntilGameTime = started + 40;
        }
        helper.runAfterDelay(4, () -> {
            for (int i = 0; i < players.length; i++) {
                ServerCombat.handleAttack(players[i], SwingSide.RIGHT, i == 1, i == 2);
                if (players[i].getData(ModAttachments.COMBAT).active != null) {
                    helper.fail("attack variant " + i + " bypassed the 0.3s gate after sheathing");
                    return;
                }
            }
        });
        helper.runAfterDelay(6, () -> {
            for (int i = 0; i < players.length; i++) {
                ServerCombat.handleAttack(players[i], SwingSide.RIGHT, i == 1, i == 2);
                CombatData data = players[i].getData(ModAttachments.COMBAT);
                if (data.active == null || data.active.cancelStartTick != 6
                        || data.active.charged != (i == 1) || data.active.dual != (i == 2)) {
                    helper.fail("attack variant " + i + " was rejected at the 0.3s gate");
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
                || first.cancelStartTick != 6) {
            helper.fail("setup: expected contact in the 0.3s slash and cancel at tick six");
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
            if (first[i] == null || first[i].activeEndTick != 4 || first[i].cancelStartTick != 6
                    || first[i].durationTicks != 8) {
                helper.fail("expected contact in the slash, cancel at 0.3s and recovery to 0.4s");
                return;
            }
            data.parryReady = true;
            data.parryReadyUntilGameTime = first[i].startGameTime + 100;
        }
        helper.runAfterDelay(first[0].cancelStartTick, () -> {
            for (int i = 0; i < players.length; i++) {
                CombatData data = players[i].getData(ModAttachments.COMBAT);
                ServerCombat.handleAttack(players[i], SwingSide.RIGHT, i == 1, i == 2);
                if (data.active == null || data.active == first[i] || data.active.cancelStartTick != 6
                        || data.active.charged != (i == 1) || data.active.dual != (i == 2)) {
                    helper.fail("normal, charged or dual follow-up was rejected at the same recovery cancel point");
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
                    || data.active.durationTicks != 8 || data.active.cancelStartTick != 6) {
                helper.fail("charged attack did not chain at the 0.3s gate");
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
        dodgePlayer.setOnGround(true);
        ServerCombat.handleSetDefending(guardPlayer, true);
        ServerCombat.handleDodge(dodgePlayer, 0.0F, 1);
        if (guardData.active != guardAttack || guardData.defending
                || dodgeData.active != dodgeAttack || dodgeData.dodgeTicksLeft > 0) {
            helper.fail("guard or dodge canceled attack windup");
            return;
        }
        helper.runAfterDelay(guardAttack.cancelStartTick - 1, () -> {
            dodgePlayer.setOnGround(true);
            ServerCombat.handleSetDefending(guardPlayer, true);
            ServerCombat.handleDodge(dodgePlayer, 0.0F, 1);
            if (guardData.active != guardAttack || guardData.defending
                    || dodgeData.active != dodgeAttack || dodgeData.dodgeTicksLeft > 0) {
                helper.fail("guard or dodge canceled before the recovery cancel point");
            }
        });
        helper.runAfterDelay(guardAttack.cancelStartTick, () -> {
            dodgePlayer.setOnGround(true);
            ServerCombat.handleSetDefending(guardPlayer, true);
            ServerCombat.handleDodge(dodgePlayer, 0.0F, 1);
            if (guardData.active != null || !guardData.defending
                    || dodgeData.active != null || dodgeData.dodgeTicksLeft <= 0) {
                helper.fail("guard or dodge did not cancel at the recovery cancel point");
                return;
            }
            long now = guardPlayer.level().getGameTime();
            if (guardData.cadence.previewIntervalTicks(now) != 6
                    || dodgeData.cadence.previewIntervalTicks(now) != 6) {
                helper.fail("guard or dodge reset accumulated attack cadence");
                return;
            }
            ServerCombat.handleAttack(dodgePlayer, SwingSide.LEFT, false);
            if (dodgeData.active != null) {
                helper.fail("attack was accepted while dodge was still active");
            } else {
                helper.succeed();
            }
        });
    }

    private static void allAttacksShareProgressiveCadence(GameTestHelper helper) {
        var players = new ArrayList<ServerPlayer>();
        var openings = new ArrayList<CombatData.ActiveAttack>();
        for (WeaponCategory category : WeaponCategory.values()) {
            for (String clip : category.allAttacks()) {
                ServerPlayer player = category == WeaponCategory.DOUBLE
                        ? drawnDualPlayer(helper) : drawnPlayer(helper);
                CombatData data = player.getData(ModAttachments.COMBAT);
                boolean dual = WeaponCategory.DUAL_ATTACK.equals(clip);
                data.parryReady = true;
                data.parryReadyUntilGameTime = player.level().getGameTime() + 100;
                if (dual) {
                    ServerCombat.handleAttack(player, SwingSide.LEFT, false, true);
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
                        || opening.contactSeconds != 0.18F || opening.cancelStartTick != 6
                        || opening.durationTicks != 8) {
                    helper.fail(category + "/" + clip + " does not share the uniform 0.3s/0.4s runtime phases");
                    return;
                }
                data.parryReady = true;
                data.parryReadyUntilGameTime = opening.startGameTime + 100;
                players.add(player);
                openings.add(opening);
            }
        }
        helper.runAfterDelay(5, () -> {
            for (int i = 0; i < players.size(); i++) {
                CombatData.ActiveAttack opening = openings.get(i);
                ServerCombat.handleAttack(players.get(i), opening.side.opposite(), i % 3 == 1, i % 3 == 2);
                if (players.get(i).getData(ModAttachments.COMBAT).active != opening) {
                    helper.fail("attack " + i + " chained before the 0.3s gate");
                    return;
                }
            }
        });
        helper.runAfterDelay(6, () -> {
            for (int i = 0; i < players.size(); i++) {
                CombatData.ActiveAttack opening = openings.get(i);
                ServerCombat.handleAttack(players.get(i), opening.side.opposite(), i % 3 == 1, i % 3 == 2);
                CombatData.ActiveAttack followup = players.get(i).getData(ModAttachments.COMBAT).active;
                if (followup == null || followup == opening || followup.cancelStartTick != 6
                        || followup.charged != (i % 3 == 1) || followup.dual != (i % 3 == 2)) {
                    helper.fail("attack " + i + " could not chain at the 0.3s gate");
                    return;
                }
            }
            helper.succeed();
        });
    }

    private static void progressiveAttackCadence(GameTestHelper helper) {
        ServerPlayer[] players = {
                drawnPlayer(helper), drawnPlayer(helper), drawnPlayer(helper),
                drawnDualPlayer(helper), drawnDualPlayer(helper), drawnDualPlayer(helper)};
        progressiveAttackStep(helper, players, 0);
    }

    private static void progressiveAttackStep(GameTestHelper helper, ServerPlayer[] players, int step) {
        int expectedGate = CombatConstants.ATTACK_INTERVAL_TICKS;
        SwingSide side = step % 2 == 0 ? SwingSide.LEFT : SwingSide.RIGHT;
        CombatData.ActiveAttack[] accepted = new CombatData.ActiveAttack[players.length];
        for (int i = 0; i < players.length; i++) {
            CombatData data = players[i].getData(ModAttachments.COMBAT);
            data.parryReady = true;
            data.parryReadyUntilGameTime = players[i].level().getGameTime() + 100;
            ServerCombat.handleAttack(players[i], side, i % 3 == 1, i % 3 == 2);
            accepted[i] = data.active;
            if (accepted[i] == null || accepted[i].cancelStartTick != expectedGate
                    || accepted[i].durationTicks != 8
                    || accepted[i].charged != (i % 3 == 1) || accepted[i].dual != (i % 3 == 2)) {
                helper.fail("attack " + step + " variant " + i + " did not keep the uniform 0.3s gate");
                return;
            }
        }
        if (step == 2) {
            helper.succeed();
            return;
        }
        helper.runAfterDelay(expectedGate - 1, () -> {
            for (int i = 0; i < players.length; i++) {
                for (int press = 0; press < 4; press++) {
                    ServerCombat.handleAttack(players[i], side.opposite(), i % 3 == 1, i % 3 == 2);
                }
                if (players[i].getData(ModAttachments.COMBAT).active != accepted[i]) {
                    helper.fail("a repeated input bypassed progressive gate " + expectedGate);
                    return;
                }
            }
        });
        helper.runAfterDelay(expectedGate, () -> progressiveAttackStep(helper, players, step + 1));
    }

    private static void idleRestoresAttackCadence(GameTestHelper helper) {
        prepareCadenceRecovery(helper, new ServerPlayer[]{drawnDualPlayer(helper), drawnDualPlayer(helper)}, 0);
    }

    private static void prepareCadenceRecovery(GameTestHelper helper, ServerPlayer[] players, int step) {
        int expectedGate = CombatConstants.ATTACK_INTERVAL_TICKS;
        SwingSide side = step % 2 == 0 ? SwingSide.LEFT : SwingSide.RIGHT;
        for (ServerPlayer player : players) {
            ServerCombat.handleAttack(player, side, false);
            CombatData data = player.getData(ModAttachments.COMBAT);
            if (data.active == null || data.active.cancelStartTick != expectedGate) {
                helper.fail("setup: could not keep the uniform 0.3s gate");
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
                    helper.fail("sheath changed the uniform attack cadence");
                    return;
                }
            }
            ServerCombat.handleAttack(players[0], SwingSide.LEFT, false);
            CombatData.ActiveAttack attack = players[0].getData(ModAttachments.COMBAT).active;
            if (attack == null || attack.cancelStartTick != expectedGate) {
                helper.fail("follow-up after sheath did not keep the 0.3s gate");
            } else {
                helper.succeed();
            }
        });
    }

    private static void rejectedInputPreservesCadence(GameTestHelper helper) {
        ServerPlayer player = drawnDualPlayer(helper);
        ServerCombat.handleAttack(player, SwingSide.LEFT, false);
        CombatData data = player.getData(ModAttachments.COMBAT);
        helper.runAfterDelay(6, () -> {
            ServerCombat.handleAttack(player, SwingSide.LEFT, false);
            CombatData.ActiveAttack second = data.active;
            if (second == null || second.cancelStartTick != 6) {
                helper.fail("setup: second left slash was rejected");
                return;
            }
            helper.runAfterDelay(6, () -> {
                long now = player.level().getGameTime();
                for (int press = 0; press < 5; press++) {
                    ServerCombat.handleAttack(player, SwingSide.LEFT, false);
                    ServerCombat.handleAttack(player, SwingSide.LEFT, false, true);
                }
                if (data.active != second || data.cadence.previewIntervalTicks(now) != 6
                        || !data.cadence.canStart(now)) {
                    helper.fail("invalid hand or unavailable parry inputs consumed cadence");
                    return;
                }
                ServerCombat.handleAttack(player, SwingSide.RIGHT, false);
                if (data.active == null || data.active == second || data.active.cancelStartTick != 6) {
                    helper.fail("valid third slash did not keep the 0.3s gate after rejected inputs");
                } else {
                    helper.succeed();
                }
            });
        });
    }

    private ModGameTests() {}
}
