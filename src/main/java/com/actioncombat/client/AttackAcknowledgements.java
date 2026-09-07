package com.actioncombat.client;

import com.actioncombat.combat.SwingSide;
import java.util.ArrayDeque;

/** Ordered server confirmations may arrive after the next predicted swing starts. */
public final class AttackAcknowledgements {
    public record Prediction(SwingSide side, int hand, boolean dual,
                             long startedAtTick, long startedAtNanos, int intervalTicks) {
        private boolean matches(SwingSide confirmedSide, int confirmedHand, boolean confirmedDual) {
            return side == confirmedSide && hand == confirmedHand && dual == confirmedDual;
        }
    }
    private final ArrayDeque<Prediction> pending = new ArrayDeque<>();

    public void predict(SwingSide side, int hand, boolean dual) {
        predict(side, hand, dual, 0, 0, 5);
    }

    public void predict(SwingSide side, int hand, boolean dual,
                        long startedAtTick, long startedAtNanos, int intervalTicks) {
        if (pending.size() >= 64) pending.removeFirst();
        pending.addLast(new Prediction(side, hand, dual, startedAtTick, startedAtNanos, intervalTicks));
    }

    public boolean confirm(SwingSide side, int hand, boolean dual) {
        return confirmPrediction(side, hand, dual) != null;
    }

    public Prediction confirmPrediction(SwingSide side, int hand, boolean dual) {
        Prediction confirmed = pending.stream().filter(entry -> entry.matches(side, hand, dual))
                .findFirst().orElse(null);
        if (confirmed == null) return null;
        // TCP preserves accepted-attack order. Earlier unmatched predictions were
        // rejected by the server, and cannot receive a later confirmation.
        while (!pending.removeFirst().equals(confirmed)) {
        }
        return confirmed;
    }

    public void clear() {
        pending.clear();
    }
}
