package org.fsrpg.creature;

/** A fresh input is authorized for one specific attack, never a generic invulnerability period. */
public final class GuardCueWindow {
    private final long started,flash,open,close,contact;
    private long pressSerial=-1;
    private boolean consumed,canceled;
    public GuardCueWindow(long started,long flash,long open,long close,long contact){
        // Quantization may leave a short fractional input interval empty (open > close).
        if(started<0||flash<started||open<flash||close<flash||contact<open||contact<close)throw new IllegalArgumentException("Invalid guard opportunity");
        this.started=started;this.flash=flash;this.open=open;this.close=close;this.contact=contact;
    }
    public long startedGameTick(){return started;}
    public long flashGameTick(){return flash;}
    public long openGameTick(){return open;}
    public long closeGameTick(){return close;}
    public long contactGameTick(){return contact;}
    public boolean accepts(long now){return !canceled&&!consumed&&now>=open&&now<=close;}
    /** Clash activation keeps the previous three-fifths through actual-contact interval. */
    public boolean acceptsClash(long now){return !canceled&&!consumed&&now>=open&&now<=contact;}
    public boolean press(long now,long serial){if(serial<1||!accepts(now))return false;pressSerial=serial;return true;}
    public boolean consume(long now,long currentSerial,boolean held){
        if(canceled||consumed||now<contact||!held||pressSerial<1||pressSerial!=currentSerial)return false;
        consumed=true;return true;
    }
    public void cancel(){canceled=true;pressSerial=-1;}
}
