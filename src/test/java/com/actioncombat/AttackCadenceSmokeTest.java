package com.actioncombat;

import com.actioncombat.client.InputBuffer;
import com.actioncombat.combat.AttackTimings;
import com.actioncombat.combat.SwingSide;
import com.actioncombat.combat.WeaponCategory;

import java.util.concurrent.atomic.AtomicLong;

/** Headless regressions for uniform 0.3s slash / 0.1s recovery / 0.2s pre-input. */
public final class AttackCadenceSmokeTest {
    public static void verify() {
        verifyAuthoredTimings();
        verifyFirstPersonPhases();
        verifyUniformFallback();
        verifyInputBuffer();
        System.out.println("attack cadence: 0.3s slash, 0.1s recovery, 0.2s pre-input and buffered intent passed");
    }

    private static void verifyFirstPersonPhases() {
        for (String clip : WeaponCategory.SINGLE.allAttacks()) {
            AttackTimings.Timing timing = AttackTimings.firstPersonOf(WeaponCategory.SINGLE, clip);
            assertUniform("first-person " + clip, timing);
            assertTrue(clip + " FP overlay uses the same mapping",
                    timing.equals(AttackTimings.firstPersonOf(WeaponCategory.SINGLE, clip + "_upper")));
        }
        for (String clip : WeaponCategory.DOUBLE.allAttacks()) {
            assertTrue(clip + " dual FP reuses its exact third-person phases",
                    AttackTimings.firstPersonOf(WeaponCategory.DOUBLE, clip)
                            .equals(AttackTimings.of(WeaponCategory.DOUBLE, clip)));
        }
        assertTrue("first-person metadata does not count as additional authored attacks", AttackTimings.size() == 10);
    }

    private static void verifyAuthoredTimings() {
        assertNear("slash length", 0.30, CombatConstants.ATTACK_SLASH_SECONDS);
        assertNear("recovery length", 0.10, CombatConstants.ATTACK_RECOVERY_SECONDS);
        assertNear("pre-input opens", 0.20, CombatConstants.COMBO_WINDOW_START);
        assertNear("cancel gate", 0.30, CombatConstants.ATTACK_INTERVAL_SECONDS);
        assertNear("clip length", 0.40, CombatConstants.ATTACK_DURATION_SECONDS);
        assertNear("input buffer", 0.10, CombatConstants.INPUT_BUFFER_SECONDS);
        assertTrue("all generated timings loaded", AttackTimings.size() == 10);
        int checked = 0;
        for (WeaponCategory category : WeaponCategory.values()) {
            for (String clip : category.allAttacks()) {
                String name = category.animationsId + "/" + clip;
                AttackTimings.Timing timing = AttackTimings.of(category, clip);
                assertTrue(name + " legacy overload uses the same timing",
                        timing.equals(AttackTimings.of(category, clip, 0.4F)));
                assertTrue(name + " upper-body overlay shares timing",
                        timing.equals(AttackTimings.of(category, clip + "_upper")));
                assertNear(name + " retargeted clip length", 0.40, timing.authoredLength());
                assertTrue(name + " contact stays inside the slash",
                        timing.authoredActiveStart() > 0.0F
                                && timing.authoredActiveEnd() > timing.authoredActiveStart()
                                && timing.authoredActiveEnd() <= CombatConstants.ATTACK_SLASH_SECONDS + 0.00001F);
                assertUniform(name, timing);
                verifyLinearPlayback(name, timing);
                assertTrue(name + " withInterval is a no-op", timing.equals(timing.withInterval(99)));
                checked++;
            }
            for (SwingSide side : SwingSide.values()) {
                for (int hand = 0; hand < category.chain(side).length(); hand++) {
                    assertNear(category + " attack duration", 0.40, category.attackDuration(side, hand));
                }
            }
            assertNear(category + " dual attack duration", 0.40, category.dualDuration());
        }
        assertTrue("both categories' complete attack sets checked", checked == 10);
    }

    private static void assertUniform(String name, AttackTimings.Timing timing) {
        assertNear(name + " runtime windup", 0.12, timing.activeStart());
        assertNear(name + " runtime contact end", 0.18, timing.activeEnd());
        assertNear(name + " complete runtime recovery", 0.40, timing.length());
        assertNear(name + " cancel marker", 0.30, timing.cancelStart());
        assertNear(name + " client cancel seconds", 0.30, timing.cancelSeconds());
        assertTrue(name + " server contact and cancellation ticks",
                timing.activeStartTick() == 2 && timing.activeEndTick() == 4
                        && timing.cancelStartTick() == 6);
        assertNear(name + " buffered input opens at 0.2s", 0.20, timing.comboStart());
        assertNear(name + " combo remains open through recovery", 0.40, timing.comboEnd());
    }

    private static void verifyLinearPlayback(String name, AttackTimings.Timing timing) {
        assertNear(name + " opening pose", 0.0, timing.animationSeconds(0.0));
        assertNear(name + " mid slash", 0.15, timing.animationSeconds(0.15));
        assertNear(name + " slash end / cancel pose", 0.30, timing.animationSeconds(0.30));
        assertNear(name + " mid recovery", 0.35, timing.animationSeconds(0.35));
        assertNear(name + " complete recovery pose", 0.40, timing.animationSeconds(0.40));
        assertNear(name + " negative time clamps to opening", 0.0, timing.animationSeconds(-0.5));
        assertNear(name + " invalid time clamps to opening", 0.0, timing.animationSeconds(Double.NaN));
        assertNear(name + " late time clamps to final pose", 0.40, timing.animationSeconds(2.0));
        double previous = 0.0;
        for (int sample = 0; sample <= 1000; sample++) {
            double current = timing.animationSeconds(sample / 1000.0 * timing.length());
            assertTrue(name + " playback remains finite and monotonic",
                    Double.isFinite(current) && current >= previous - 0.00000001 && current <= 0.40 + 0.00000001);
            previous = current;
        }
    }

    private static void verifyUniformFallback() {
        AttackTimings.Timing timing = AttackTimings.of(WeaponCategory.SINGLE, "missing_attack", 0.4F);
        assertUniform("fallback clip", timing);
        assertNear("fallback preserves supplied authored duration", 0.4, timing.authoredLength());
        verifyLinearPlayback("fallback clip", timing);
    }

    private static void verifyInputBuffer() {
        AtomicLong now = new AtomicLong(20_000_000_000L);
        InputBuffer buffer = new InputBuffer(now::get);
        assertEmpty("new buffer", buffer);
        assertNear("input grace period", 0.10, CombatConstants.INPUT_BUFFER_SECONDS);

        long availableAt = now.get() + 300_000_000L;
        buffer.push(SwingSide.LEFT, false, false, availableAt);
        now.addAndGet(200_000_000L);
        assertIntent("early pre-input survives until the 0.3s gate", buffer, SwingSide.LEFT, false, false);
        buffer.push(SwingSide.RIGHT, true, true, availableAt);
        now.set(availableAt);
        assertIntent("latest press replaces side and keeps charged dual flags", buffer, SwingSide.RIGHT, true, true);
        now.set(availableAt + 99_000_000L);
        assertIntent("input survives until after the cancel opportunity", buffer, SwingSide.RIGHT, true, true);
        now.set(availableAt + 101_000_000L);
        assertEmpty("repeated reads do not refresh expiry", buffer);

        buffer.push(SwingSide.LEFT, true, true, now.get() + 2_000_000_000L);
        buffer.clear();
        assertEmpty("consuming a buffered attack clears every flag", buffer);

        long pressedAt = now.get();
        buffer.push(SwingSide.RIGHT, false, false, pressedAt - 1_000_000_000L);
        now.set(pressedAt + 99_000_000L);
        assertIntent("past cancel opportunity gets grace from this press", buffer, SwingSide.RIGHT, false, false);
        now.set(pressedAt + 101_000_000L);
        assertEmpty("past opportunity expires normally", buffer);
    }

    private static void assertIntent(String description, InputBuffer buffer, SwingSide side, boolean charged, boolean dual) {
        assertTrue(description, buffer.hasPending() && buffer.pendingSide() == side
                && buffer.pendingCharged() == charged && buffer.pendingDual() == dual);
    }

    private static void assertEmpty(String description, InputBuffer buffer) {
        assertTrue(description, !buffer.hasPending() && buffer.pendingSide() == null
                && !buffer.pendingCharged() && !buffer.pendingDual());
    }

    private static void assertNear(String description, double expected, double actual) {
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > 0.00001) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(String description, boolean condition) {
        if (!condition) throw new AssertionError(description);
    }

    private AttackCadenceSmokeTest() {}
}
