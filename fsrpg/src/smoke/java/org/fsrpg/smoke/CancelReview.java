package org.fsrpg.smoke;

import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;
import org.fsrpg.RpgServer;
import org.fsrpg.client.ClientState;
import org.fsrpg.combat.ActionResult;
import org.fsrpg.combat.CombatPhase;

/** Live integrated-server requests exercise phase cancels without touching the actor's clock. */
final class CancelReview {
    private int tick;
    private volatile int checks;
    int checks() { return checks; }
    private void check(boolean ok,String message) { RpgSmoke.check(ok,message);checks++; }
    boolean tick(RenderFrameCapture movie) throws Exception {
        var game=Minecraft.getInstance();int t=tick++;
        game.options.setCameraType(CameraType.THIRD_PERSON_FRONT);game.options.hideGui=true;
        if(t==0)RpgSmoke.server(p->{
            p.connection.teleport(.5,80,.5,0,3);RpgSmoke.actor(p).setResources(100,100);
            if(!RpgSmoke.actor(p).drawn())RpgServer.instance().action(p,"draw","");
        });
        if(t==15) {
            var observe=RenderObservations.sampler(game.player);
            movie.start("combat-cancels","live server windup cancels, protected active frames and buffered alternate attack",()->{
                var sample=observe.get();sample.addProperty("serverPhase",ClientState.text(ClientState.snapshot(),"phase",""));return sample;
            });
        }
        if(t==16)RpgSmoke.server(p->RpgServer.instance().action(p,"attack","left"));
        if(t==17)RpgSmoke.server(p->{
            check(RpgSmoke.actor(p).phase()==CombatPhase.WINDUP,"live guard cancel begins during windup");
            RpgServer.instance().action(p,"guard","true");
            check(RpgSmoke.actor(p).phase()==CombatPhase.GUARDING&&RpgSmoke.actor(p).currentSkill()==null,"live guard cancels windup and removes attack");
        });
        if(t==24)RpgSmoke.server(p->{RpgServer.instance().action(p,"guard","false");RpgServer.instance().action(p,"attack","right");});
        if(t==28)RpgSmoke.server(p->{
            check(RpgSmoke.actor(p).phase()==CombatPhase.WINDUP,"live dodge cancel begins during thrust charge");
            RpgServer.instance().action(p,"dodge","");
            check(RpgSmoke.actor(p).phase()==CombatPhase.DODGING,"live dodge cancels charge before damage");
        });
        if(t==48)RpgSmoke.server(p->{RpgSmoke.actor(p).setResources(100,100);RpgServer.instance().action(p,"attack","left");});
        if(t==52)RpgSmoke.server(p->{
            var a=RpgSmoke.actor(p);
            check(a.phase()==CombatPhase.ACTIVE,"live active lock sampled during actual collision phase");
            check(a.setGuarding(true)==ActionResult.BUSY&&a.requestDodge(true)==ActionResult.BUSY,"live active attack rejects guard and dodge cancels");
            RpgServer.instance().action(p,"attack","right");
            check(a.phase()==CombatPhase.ACTIVE&&a.currentSkill().id().equals("sword_left")&&a.comboSnapshot().queued(),"live alternate attack buffers while active stays intact");
        });
        if(t==58)RpgSmoke.server(p->{
            var a=RpgSmoke.actor(p);
            check(a.currentSkill()!=null&&a.currentSkill().id().equals("sword_thrust")&&a.phase()==CombatPhase.WINDUP,"live buffered alternate starts immediately after active end");
            RpgServer.instance().action(p,"guard","true");
            check(a.phase()==CombatPhase.GUARDING,"live replacement windup remains cancellable");
        });
        if(t==80) {movie.stop();RpgSmoke.server(p->{RpgServer.instance().action(p,"guard","false");RpgServer.instance().action(p,"draw","");});}
        return t>=100;
    }
}
