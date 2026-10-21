package org.fsrpg.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import org.fsrpg.network.RpgGuardImpactPayload;

/** Suppresses only the camera's hurt transform; native hurt timers, damage and knockback remain intact. */
public final class GuardImpactPresentation {
    private static final GuardHurtMotion motion=new GuardHurtMotion();
    private static long damagePackets,suppressedPackets,skippedBobs;
    public static void accept(RpgGuardImpactPayload payload){
        var g=Minecraft.getInstance();if(g.player==null||g.level==null||!g.player.getUUID().equals(payload.targetId()))return;
        if(payload.damageFollows())motion.guarded(payload.causeId(),payload.directId(),g.level.getGameTime());
        else motion.cancelCurrentHurt();
    }
    public static void damage(ClientboundDamageEventPacket packet){
        var g=Minecraft.getInstance();if(g.player==null||g.level==null||packet.entityId()!=g.player.getId())return;
        damagePackets++;if(motion.damage(packet.sourceCauseId(),packet.sourceDirectId(),g.level.getGameTime()))suppressedPackets++;
    }
    public static boolean suppressed(){
        var g=Minecraft.getInstance();return g.player!=null&&g.player.isAlive()&&g.getCameraEntity()==g.player&&g.player.hurtTime>0&&motion.suppressed();
    }
    public static boolean skipCameraBob(){if(!suppressed())return false;skippedBobs++;return true;}
    public static long receivedDamagePackets(){return damagePackets;}
    public static long suppressedDamagePackets(){return suppressedPackets;}
    public static long skippedCameraBobs(){return skippedBobs;}
    public static void reset(){motion.reset();damagePackets=suppressedPackets=skippedBobs=0;}
    private GuardImpactPresentation(){}
}
