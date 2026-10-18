package org.fsrpg.client;

/** Cheap source-anchor withdrawal; the existing bounded two-hand solve still owns both wrists. */
public final class SpearStrokePose {
    private SpearStrokePose() {}

    public static float retraction(String phase,double elapsed,int windup,int active,int recovery) {
        return switch(phase) {
            case "WINDUP" -> 1-(float)Math.clamp(elapsed/Math.max(1,windup),0,1);
            case "ACTIVE" -> 0;
            case "RECOVERY" -> (float)Math.clamp((elapsed-windup-active)/Math.max(1,recovery),0,1);
            default -> 1;
        };
    }
}
