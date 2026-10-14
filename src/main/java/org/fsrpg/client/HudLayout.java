package org.fsrpg.client;

/** GUI-coordinate layout; side reservations include the official offhand slot/attack indicator. */
public record HudLayout(int leftX, int rightX, int barWidth, int topY, boolean compact) {
    public static final int BAR_HEIGHT = 10;
    public static final int ROW_STEP = 11;
    public static final int ICON_SPACE = 12;

    public static HudLayout fit(int width, int height, int leftReservation, int rightReservation, int auxiliaryHeight) {
        int center = width / 2;
        int leftEdge = center - 91 - leftReservation - 5;
        int rightEdge = center + 91 + rightReservation + 5;
        int available = Math.min(leftEdge - ICON_SPACE - 6, width - rightEdge - ICON_SPACE - 6);
        if (available >= 80) {
            int bar = Math.min(104, available);
            return new HudLayout(leftEdge - bar, rightEdge, bar, height - 21, false);
        }
        int bar = Math.max(1, Math.min(104, (width - 2 * ICON_SPACE - 24) / 2));
        int group = bar + ICON_SPACE;
        // Leave room for armor, food, air, mount hearts and the selected-item name.
        int top = Math.max(4, height - Math.max(90, auxiliaryHeight + 40));
        return new HudLayout(center - 6 - group + ICON_SPACE, center + 6, bar, top, true);
    }
}
