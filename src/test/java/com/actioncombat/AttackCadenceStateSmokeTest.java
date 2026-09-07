package com.actioncombat;

import com.actioncombat.combat.AttackCadence;

/** Deterministic state regressions for the uniform 0.3s cancel gate. */
public final class AttackCadenceStateSmokeTest {
    public static void verify() {
        verifyContinuousAttacks();
        verifyReadOnlyQueriesAndRejection();
        verifyDelayedSynchronization();
        verifyReset();
        System.out.println("attack cadence state: uniform 6-tick gate, rejected input and ACK correction passed");
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
        cadence.synchronize(originalStart, 99);
        assertTrue("correction keeps the uniform gate closed", !cadence.canStart(originalStart + 5));
        assertTrue("corrected gate ends six ticks after original start", cadence.canStart(originalStart + 6));
        assertEquals("next interval stays uniform", 6,
                cadence.previewIntervalTicks(originalStart + 6));
    }

    private static void assertRejected(AttackCadence cadence, long now) {
        try {
            cadence.begin(now);
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("an attack before the current gate must be rejected");
    }

    private static void assertEquals(String description, int expected, int actual) {
        if (expected != actual) throw new AssertionError(description + ": expected " + expected + ", got " + actual);
    }

    private static void assertTrue(String description, boolean condition) {
        if (!condition) throw new AssertionError(description);
    }

    private AttackCadenceStateSmokeTest() {}
}
