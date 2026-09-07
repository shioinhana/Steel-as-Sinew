package com.actioncombat.combat;

/** Where one hand currently sits after the last slash that moved it. */
public enum HandRest {
    CENTER,
    LEFT,
    RIGHT;

    public static HandRest of(SwingSide side) {
        return side == SwingSide.RIGHT ? RIGHT : LEFT;
    }

    /**
     * Which hand should slash toward {@code side}: 0 left, 1 right, -1 none.
     * <p>
     * Each mouse button leads with the matching hand: 左键 is left then right
     * ({@code attack1} / {@code attack3}), 右键 is right then left
     * ({@code attack4} / {@code attack2}).
     */
    public static int pickHand(SwingSide side, HandRest left, HandRest right) {
        HandRest target = of(side);
        boolean leftOk = left != target;
        boolean rightOk = right != target;
        if (!leftOk && !rightOk) return -1;
        if (side == SwingSide.RIGHT) {
            if (rightOk) return 1;
            return 0;
        }
        if (leftOk) return 0;
        return 1;
    }
}
