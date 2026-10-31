package org.fsrpg.client;

public final class SwordMotionReceiptTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        var receipt=new SwordMotionReceipt();
        check(receipt.accept(4,100,1,65,3),"valid endpoint");
        check(!receipt.accept(4,100,1,65,3),"duplicate cannot replay");
        check(!receipt.accept(3,101,1,65,3),"late old sequence cannot displace next action");
        check(!receipt.accept(5,99,1,65,3),"old timestamp cannot rewind position");
        check(!receipt.accept(5,101,Double.NaN,65,3),"non finite x rejected");
        check(!receipt.accept(5,101,1,Double.POSITIVE_INFINITY,3),"non finite y rejected");
        check(!receipt.accept(5,101,1,65,3.1e7),"outside world bound rejected");
        check(receipt.accept(5,101,1,65,3),"invalid receipts do not advance clock");
        receipt.reset();check(receipt.accept(1,2,0,64,0),"new world has independent clock");
        check(PoseLayers.attackElapsed("PARRYING",16,2,5,9)==0,"parry starts at zero");
        check(PoseLayers.attackElapsed("PARRYING",14,2,5,9)==2,"parry active entry");
        check(PoseLayers.attackElapsed("PARRYING",0,2,5,9)==16,"parry recovery reaches endpoint");
        check(PoseLayers.attackElapsed("GUARDING",0,2,5,9)==-1,"continuous guard has no false attack clock");
        System.out.println("SwordMotionReceiptTest passed "+checks+" checks");
    }
}
