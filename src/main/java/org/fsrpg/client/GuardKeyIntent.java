package org.fsrpg.client;

/** Physical input owns the edge; changing screens, equipment or guard weight never invents a press. */
public final class GuardKeyIntent {
    private boolean initialized,physicalHeld,owned,eligible,heavy;
    private int retryTicks;

    /** Null means no network intent. The first held sample is deliberately a renewal, not a new press. */
    public String sample(boolean down,boolean canGuard,boolean heavyMode,boolean retryAllowed) {
        boolean fresh=initialized&&!physicalHeld&&down;
        boolean released=initialized&&physicalHeld&&!down;
        boolean eligibilityChanged=eligible!=canGuard;
        boolean modeChanged=heavy!=heavyMode;
        initialized=true;physicalHeld=down;eligible=canGuard;heavy=heavyMode;
        if(released) {
            retryTicks=0;
            if(owned){owned=false;return "false";}
            return null;
        }
        if(!down){retryTicks=0;return null;}
        boolean firstOwnership=!owned;
        owned=true;
        if(firstOwnership||fresh||eligibilityChanged||modeChanged&&canGuard) {
            retryTicks=0;
            if(!canGuard)return "suspend";
            return fresh?(heavyMode?"heavy":"true"):(heavyMode?"held_heavy":"held");
        }
        if(canGuard&&retryAllowed) {
            if(++retryTicks>=4){retryTicks=0;return heavyMode?"held_heavy":"held";}
        } else retryTicks=0;
        return null;
    }

    public boolean ownsInput(){return owned;}
    public void reset(){initialized=physicalHeld=owned=eligible=heavy=false;retryTicks=0;}
}
