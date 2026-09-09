package com.actioncombat;

import com.actioncombat.combat.AttackCadence;
import com.actioncombat.combat.AttackTimings;
import com.actioncombat.combat.WeaponCategory;

/** Deterministic state regressions for the shared attack gate bookkeeping. */
public final class AttackCadenceStateSmokeTest {
    public static void verify() {
        verifyContinuousAttacks();
        verifyReadOnlyQueriesAndRejection();
        verifyDelayedSynchronization();
        verifyReset();
        verifyRapierIntervals();
        System.out.println("attack cadence state: 6-tick default gate, rapier 0.4/0.4/0.4/0.5 chain,"
                + " dual 0.45s gate with a 0.1s final-frame hold,"
                + " charged-thrust 0.3/0.3/0.3/0.3 chain, dual hand streaks, rejected input and ACK correction passed");
    }

    private static void verifyContinuousAttacks() {
        AttackCadence cadence = new AttackCadence();
        long now = 1_000;
        for (int step = 0; step < 5; step++) {
            assertTrue("next attack starts at the preceding gate", cadence.canStart(now));
            assertEquals("preview agrees with the accepted attack", 6, cadence.previewIntervalTicks(now));
            assertEquals("continuous attack interval", 6, cadence.begin(now));
            assertTrue("accepted attack closes its gate", !cadence.canStart(now));
            assertTrue("gate stays closed one tick before recovery", !cadence.canStart(now + 5));
            now += 6;
            assertTrue("gate opens exactly on its latched tick", cadence.canStart(now));
        }
    }

    private static void verifyReadOnlyQueriesAndRejection() {
        AttackCadence cadence = new AttackCadence();
        assertEquals("first attack", 6, cadence.begin(2_000));
        for (int attempt = 0; attempt < 10; attempt++) {
            assertTrue("early input remains blocked", !cadence.canStart(2_005));
            assertEquals("early preview adds no fatigue", 6, cadence.previewIntervalTicks(2_005));
            assertRejected(cadence, 2_005);
        }
        assertTrue("rejected attempts retain the original gate", cadence.canStart(2_006));
        assertEquals("only accepted attacks advance cadence", 6, cadence.begin(2_006));
        assertTrue("the accepted follow-up latches its own gate", !cadence.canStart(2_011));
        assertTrue("the follow-up opens after six ticks", cadence.canStart(2_012));
    }

    private static void verifyRapierIntervals() {
        assertEquals("1→2 waits 0.4s", 8, AttackCadence.intervalTicksFor(WeaponCategory.SINGLE, 0));
        assertEquals("2→3 waits 0.4s", 8, AttackCadence.intervalTicksFor(WeaponCategory.SINGLE, 1));
        assertEquals("3→4 waits 0.4s", 8, AttackCadence.intervalTicksFor(WeaponCategory.SINGLE, 2));
        assertEquals("4→1 waits 0.5s", 10, AttackCadence.intervalTicksFor(WeaponCategory.SINGLE, 3));
        assertEquals("charged chain waits 0.3s", 6,
                AttackCadence.intervalTicksFor(WeaponCategory.SINGLE, 0, true));
        assertEquals("charged chain stays 0.3s", 6,
                AttackCadence.intervalTicksFor(WeaponCategory.SINGLE, 3, true));
        assertEquals("dual base gate is 0.45s", 9,
                AttackCadence.intervalTicksFor(WeaponCategory.DOUBLE, 1));
        assertEquals("same hand twice then again goes slow", 12,
                AttackCadence.dualIntervalTicks(true, 2, 0));
        assertEquals("the other hand's swing relaxes the streak", 9,
                AttackCadence.dualIntervalTicks(true, 1, 2));
        // 双刀 holds its final frame 0.1s longer than the clip; the cancel gate is untouched.
        assertNear("dual playback holds the last frame", 0.5F,
                AttackTimings.playbackSeconds(WeaponCategory.DOUBLE, 0.4F));
        assertNear("other weapons play the clip 1:1", 0.4F,
                AttackTimings.playbackSeconds(WeaponCategory.SINGLE, 0.4F));
        assertNear("the hold does not touch the cancel window", 0.45F,
                AttackCadence.intervalTicksFor(WeaponCategory.DOUBLE, 1) / 20.0F);
        assertTrue("第四刀 scans later than the other slashes",
                CombatConstants.RAPIER_FOURTH_CONTACT_DELAY_SECONDS > 0.0F
                        && CombatConstants.RAPIER_FOURTH_CONTACT_DELAY_SECONDS <= 0.1F);
        AttackCadence cadence = new AttackCadence();
        assertEquals("rapier late slash latches 10 ticks", 10, cadence.begin(4_000, 10));
        assertTrue("0.5s gate stays closed", !cadence.canStart(4_009));
        assertTrue("0.5s gate opens at tick 10", cadence.canStart(4_010));
    }

    private static void verifyReset() {
        AttackCadence cadence = new AttackCadence();
        cadence.begin(10_000);
        cadence.reset();
        assertTrue("reset drops the preceding gate", cadence.canStart(10_000));
        assertEquals("reset restores the first interval", 6, cadence.begin(10_000));
    }

    private static void verifyDelayedSynchronization() {
        AttackCadence cadence = new AttackCadence();
        assertEquals("opening attack before prediction correction", 6, cadence.begin(3_000));
        long originalStart = 3_006;
        assertEquals("second attack stays on six ticks", 6, cadence.begin(originalStart));
        cadence.synchronize(originalStart, 9);
        assertTrue("correction keeps the latched gate closed", !cadence.canStart(originalStart + 8));
        assertTrue("corrected gate ends at the supplied interval", cadence.canStart(originalStart + 9));
        assertEquals("ACK interval is what the next preview reports", 9,
                cadence.previewIntervalTicks(originalStart + 9));
    }

    private static void assertRejected(AttackCadence cadence, long now) {
        try {
            cadence.begin(now);
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("an attack before the current gate must be rejected");
    }

    private static void assertNear(String description, float expected, float actual) {
        if (Math.abs(expected - actual) > 1.0E-4F) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(String description, int expected, int actual) {
        if (expected != actual) throw new AssertionError(description + ": expected " + expected + ", got " + actual);
    }

    private static void assertTrue(String description, boolean condition) {
        if (!condition) throw new AssertionError(description);
    }

    private AttackCadenceStateSmokeTest() {}
}
