package org.fsrpg.combat;

/** A swing is tested exactly when it first becomes ACTIVE, even when no opponent qualifies. */
public final class SwingActivationGate {
    private long claimedSequence;
    public boolean claim(long sequence) {
        if(sequence<1)throw new IllegalArgumentException("A real positive swing sequence is required");
        if(sequence==claimedSequence)return false;
        claimedSequence=sequence;return true;
    }
}
