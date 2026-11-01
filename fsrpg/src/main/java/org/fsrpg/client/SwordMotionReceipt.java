package org.fsrpg.client;

/** Monotonic movement receipts, reset whenever the local player/world changes. */
public final class SwordMotionReceipt {
    private long sequence=Long.MIN_VALUE,tick=Long.MIN_VALUE;
    public boolean accept(long nextSequence,long nextTick,double x,double y,double z) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>30000000||Math.abs(z)>30000000
                ||Math.abs(y)>30000000||nextSequence<sequence||nextTick<=tick)return false;
        sequence=nextSequence;tick=nextTick;return true;
    }
    public void reset(){sequence=tick=Long.MIN_VALUE;}
}
