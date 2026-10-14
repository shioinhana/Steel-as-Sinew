package org.fsrpg.client;

/** Boundary checks for viewport/offhand adaptation and authoritative resource readouts. */
public final class HudPresentationTest {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) {
        for (int width : new int[]{320, 360, 400, 427, 480, 640, 960, 1920}) {
            for (int left : new int[]{0, 24, 29}) for (int right : new int[]{0, 24, 29}) {
                var layout = HudLayout.fit(width, 240, left, right, 69);
                check(layout.leftX() - HudLayout.ICON_SPACE >= 0, "left icon stays inside viewport");
                check(layout.rightX() + layout.barWidth() + HudLayout.ICON_SPACE <= width, "right icon stays inside viewport");
                check(layout.leftX() + layout.barWidth() < layout.rightX(), "resource groups never overlap");
                check(layout.topY() >= 0 && layout.topY() + 21 <= 240, "both rows stay inside viewport");
                if (!layout.compact()) {
                    check(layout.leftX() + layout.barWidth() <= width / 2 - 91 - left - 5, "left group leaves native slot/indicator clear");
                    check(layout.rightX() >= width / 2 + 91 + right + 5, "right group leaves native slot/indicator clear");
                } else {
                    check(layout.topY() + 21 < 240 - 69, "compact rows clear armor/food/air/mount rows");
                }
            }
        }
        check(!HudLayout.fit(427, 240, 0, 0, 49).compact(), "standard scale3 viewport fits reference layout");
        check(HudLayout.fit(427, 240, 29, 24, 59).compact(), "offhand and cooldown at scale3 use compact layout");
        check(HudReadout.of(64, 100).label().equals("64/100"), "actual server max shown");
        check(HudReadout.of(64, 100).ratio() == .64, "resource fraction uses actual maximum");
        check(HudReadout.of(-4, 100).ratio() == 0, "negative values clamp empty");
        check(HudReadout.of(150, 100).ratio() == 1, "values above maximum clamp full");
        check(HudReadout.of(Double.NaN, 100).label().equals("--/--"), "missing snapshot displays placeholder");
        check(!HudReadout.of(5, 0).known(), "zero maximum never divides");
        check(!HudReadout.of(5, Double.POSITIVE_INFINITY).known(), "nonfinite maximum rejected");
        check(HudReadout.of(.25, 100).label().equals("0.3/100"), "fractional resources remain visible");
        System.out.println("HudPresentationTest passed: " + checks + " checks");
    }
}
