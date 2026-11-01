package org.fsrpg.client;

/** Only finite, received values are displayed; a missing snapshot is an empty placeholder. */
public record HudReadout(double current, double maximum, boolean known) {
    public static HudReadout of(double current, double maximum) {
        if (!Double.isFinite(current) || !Double.isFinite(maximum) || maximum <= 0)
            return new HudReadout(0, 0, false);
        return new HudReadout(Math.max(0, Math.min(current, maximum)), maximum, true);
    }
    public double ratio() { return known ? current / maximum : 0; }
    public String label() { return known ? number(current) + "/" + number(maximum) : "--/--"; }
    public static String number(double value) {
        if (!Double.isFinite(value)) return "--";
        if (Math.abs(value - Math.rint(value)) < .05) return Long.toString(Math.round(value));
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
