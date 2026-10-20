package org.fsrpg.client;

import java.util.UUID;

/** Pure single-use receipt policy: a packet may satisfy only its live physical press in the same avatar/world. */
public final class GroundImpulseReceipt {
    public record Context(UUID playerId,int entityId,String dimension){}
    private Context context;
    private long expectedRequest=-1,requestedAt,highestSequence,appliedRequest=-1,applications;
    public void expect(Context context,long requestId,long now){
        if(context==null||context.playerId()==null||context.dimension()==null||requestId<=0||now<0)throw new IllegalArgumentException("invalid impulse context");
        if(!context.equals(this.context)){this.context=context;highestSequence=0;appliedRequest=-1;}
        if(expectedRequest!=requestId){expectedRequest=requestId;requestedAt=now;}
    }
    public boolean accept(Context current,Context packet,long requestId,long sequence,long issued,long now,double x,double z){
        if(context==null||!context.equals(current)||!context.equals(packet)||expectedRequest!=requestId||requestId<=0||appliedRequest==requestId
                ||sequence<=highestSequence||issued<0||now<requestedAt||now-requestedAt>20||now-issued>20||issued-now>5
                ||!Double.isFinite(x)||!Double.isFinite(z)||Math.hypot(x,z)>4.000001||Math.hypot(x,z)<.000001)return false;
        highestSequence=sequence;appliedRequest=requestId;expectedRequest=-1;applications++;return true;
    }
    /** Focus/GUI/guard interruption rejects a late grant without lowering the replay high-water mark. A simple tap release need not cancel. */
    public void cancel(){expectedRequest=-1;appliedRequest=-1;}
    public boolean approved(long requestId){return context!=null&&appliedRequest==requestId;}
    public long sequence(){return highestSequence;}
    public long applications(){return applications;}
    public void reset(){context=null;expectedRequest=appliedRequest=-1;highestSequence=applications=0;}
}
