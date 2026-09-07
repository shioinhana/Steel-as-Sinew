package com.actioncombat.combat;

/**
 * Which of the two alternating swing chains an attack belongs to.
 * LEFT is bound to the attack key, RIGHT to the use key while drawn.
 * A chain only continues when the opposite side is pressed inside the combo
 * window; pressing the same side again is dropped rather than chaining.
 */
public enum SwingSide {
    LEFT,
    RIGHT;

    public SwingSide opposite() {
        return this == LEFT ? RIGHT : LEFT;
    }

    /** Network/packet form. */
    public int id() {
        return ordinal();
    }

    public static SwingSide byId(int id) {
        return id == RIGHT.ordinal() ? RIGHT : LEFT;
    }
}
