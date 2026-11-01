package org.fsrpg.client;

import java.util.UUID;

public final class GroundImpulseReceiptTest {
    private static int checks;
    private static final GroundImpulseReceipt.Context CONTEXT=new GroundImpulseReceipt.Context(UUID.randomUUID(),42,"minecraft:overworld");
    public static void main(String[] args){
        var receipt=new GroundImpulseReceipt();receipt.expect(CONTEXT,1,100);
        check(receipt.accept(CONTEXT,CONTEXT,1,1,100,101,1.35,0),"a current matching authorization applies once");
        check(receipt.applications()==1&&receipt.approved(1),"grant is observable and enables held vanilla sprint");
        check(!receipt.accept(CONTEXT,CONTEXT,1,1,100,101,1.35,0),"duplicate does not apply twice");
        receipt.expect(CONTEXT,1,102);check(!receipt.accept(CONTEXT,CONTEXT,1,2,102,102,1.35,0),"renewal cannot let even a higher sequence grant the same press again");
        receipt.expect(CONTEXT,2,103);check(!receipt.accept(CONTEXT,CONTEXT,2,1,103,104,1.35,0),"older sequence is rejected across presses");
        check(receipt.accept(CONTEXT,CONTEXT,2,2,103,104,0,-1.35),"a distinct fresh press may apply a later sequence");
        check(receipt.applications()==2,"native count measures physical applications, not packets");
        for(int kind=0;kind<10;kind++){
            var r=new GroundImpulseReceipt();r.expect(CONTEXT,1,100);
            var current=kind==0?new GroundImpulseReceipt.Context(CONTEXT.playerId(),43,CONTEXT.dimension()):CONTEXT;
            var packet=kind==1?new GroundImpulseReceipt.Context(CONTEXT.playerId(),42,"minecraft:the_nether"):CONTEXT;
            long request=kind==2?2:1,seq=kind==3?0:1,issued=kind==4?70:kind==5?110:100,now=kind==6?121:101;
            double x=kind==7?Double.NaN:kind==8?4.01:kind==9?0:1.35;
            check(!r.accept(current,packet,request,seq,issued,now,x,0),"wrong avatar/world/request/sequence/time or unsafe velocity is ignored case "+kind);
            check(r.applications()==0,"rejected packet never marks an application case "+kind);
        }
        var lost=new GroundImpulseReceipt();lost.expect(CONTEXT,1,100);lost.cancel();
        check(!lost.accept(CONTEXT,CONTEXT,1,1,100,101,1.35,0),"guard, GUI or focus loss rejects a late grant");
        var respawn=new GroundImpulseReceipt();respawn.expect(CONTEXT,1,100);respawn.reset();
        check(!respawn.accept(CONTEXT,CONTEXT,1,1,100,101,1.35,0),"respawn without a new press cannot receive the old impulse");
        var replacement=new GroundImpulseReceipt.Context(CONTEXT.playerId(),43,CONTEXT.dimension());
        respawn.expect(replacement,2,105);check(respawn.accept(replacement,replacement,2,1,105,105,1.35,0),"a new avatar can start its independent server sequence after a fresh press");
        System.out.println("GroundImpulseReceiptTest passed: "+checks+" checks");
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
