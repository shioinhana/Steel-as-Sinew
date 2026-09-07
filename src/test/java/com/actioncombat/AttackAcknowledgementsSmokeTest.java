package com.actioncombat;

import com.actioncombat.client.AttackAcknowledgements;
import com.actioncombat.combat.SwingSide;

public final class AttackAcknowledgementsSmokeTest {
    public static void verify() {
        AttackAcknowledgements acknowledgements = new AttackAcknowledgements();
        acknowledgements.predict(SwingSide.LEFT, 0, false);
        acknowledgements.predict(SwingSide.LEFT, 1, false);
        check(acknowledgements.confirm(SwingSide.LEFT, 0, false), "late first swing acknowledged");
        check(acknowledgements.confirm(SwingSide.LEFT, 1, false), "next swing survives previous acknowledgement");
        check(!acknowledgements.confirm(SwingSide.LEFT, 0, false), "confirmation consumed once");
        acknowledgements.predict(SwingSide.RIGHT, 1, false);
        acknowledgements.predict(SwingSide.LEFT, 0, true);
        check(!acknowledgements.confirm(SwingSide.LEFT, 0, false), "dual intent is distinct");
        check(acknowledgements.confirm(SwingSide.LEFT, 0, true), "accepted swing skips rejected prediction");
        check(!acknowledgements.confirm(SwingSide.RIGHT, 1, false), "rejected prediction removed");
        acknowledgements.predict(SwingSide.LEFT, 0, false);
        acknowledgements.clear();
        check(!acknowledgements.confirm(SwingSide.LEFT, 0, false), "disconnect clears old confirmations");
        acknowledgements.predict(SwingSide.LEFT, 0, false, 100, 1_000_000_000L, 5);
        acknowledgements.predict(SwingSide.LEFT, 1, false, 105, 1_250_000_000L, 6);
        var first = acknowledgements.confirmPrediction(SwingSide.LEFT, 0, false);
        check(first != null && first.startedAtTick() == 100
                && first.startedAtNanos() == 1_000_000_000L && first.intervalTicks() == 5,
                "late ACK retains original start instead of altering the current swing");
        var second = acknowledgements.confirmPrediction(SwingSide.LEFT, 1, false);
        check(second != null && second.startedAtTick() == 105
                && second.startedAtNanos() == 1_250_000_000L && second.intervalTicks() == 6,
                "current ACK identifies the exact prediction to retime");
        System.out.println("attack acknowledgements: delayed, ordered, dual and rejected predictions passed");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private AttackAcknowledgementsSmokeTest() {}
}
