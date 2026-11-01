package org.fsrpg.smoke;

import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.fsrpg.RpgServer;
import org.fsrpg.client.ClientHooks;
import org.fsrpg.client.ClientState;

/** Actual local-player input and collision physics, never manually supplied animation velocity. */
final class InputMovementReview {
    private int tick;
    private double startX, startZ;
    private int checks;
    int checks() { return checks; }
    boolean tick(RenderFrameCapture movie) throws Exception {
        var game = Minecraft.getInstance(); var p = game.player; int t = tick++;
        if (t == 0) {
            RpgSmoke.server(player -> {
                for(int x=-20;x<=20;x++) for(int z=-20;z<=20;z++)
                    player.level().setBlock(new BlockPos(x,79,z), Blocks.STONE.defaultBlockState(), 3);
                player.connection.teleport(.5,80,.5,0,5);
                player.getFoodData().setFoodLevel(20); RpgSmoke.actor(player).setResources(100,100);
                if(!RpgSmoke.actor(player).drawn()) RpgServer.instance().action(player,"draw","");
            });
        }
        game.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        game.options.fov().set(45); game.options.hideGui = true;
        // A forward run, right sidestep, left reversal, brake, then a moving attack.
        game.options.keyUp.setDown(t>=20&&t<52 || t>=130&&t<166);
        game.options.keyRight.setDown(t>=52&&t<78);
        game.options.keyLeft.setDown(t>=78&&t<104);
        game.options.keySprint.setDown(t>=20&&t<52 || t>=130&&t<166);
        if(t==20) {
            startX=p.getX(); startZ=p.getZ();
            movie.start("actual-input-movement", "local player keyboard input, native collision, run/strafe/reversal/stop and moving attack", RenderObservations.sampler(p));
        }
        if(t==42 || t==70 || t==97) {
            var sample=RenderObservations.sample(p); var up=sample.getAsJsonArray("torsoUp");
            double angle=Math.toDegrees(Math.acos(Math.clamp(up.get(1).getAsDouble(),-1,1)));
            RpgSmoke.check(p.onGround() && Math.hypot(p.getX()-startX,p.getZ()-startZ)>.8,"actual keys move grounded player t="+t); checks++;
            RpgSmoke.check(angle > (t==42 ? 10 : 6), "visible native torso lean from real input t="+t+" angle="+angle); checks++;
            var head=sample.getAsJsonObject("rig").getAsJsonArray("head");
            double headDot=0; for(int i=0;i<3;i++) headDot+=head.get(i+4).getAsDouble()*up.get(i).getAsDouble();
            RpgSmoke.check(headDot>.995,"actual head follows torso tilt t="+t); checks++;
            var leg=sample.getAsJsonObject("rig").getAsJsonArray("nativeRightLeg");
            RpgSmoke.check(Math.abs(leg.get(0).getAsFloat()+1.9f-sample.get("balanceXcm").getAsFloat()/6.25f)<.05f
                    && Math.hypot(sample.get("balanceXcm").getAsFloat(),sample.get("balanceZcm").getAsFloat())>2,
                    "actual leg roots shift with pelvis t="+t); checks++;
            RpgSmoke.screenshot("moving/input-"+t);
        }
        if(t==125) {
            var up=RenderObservations.sample(p).getAsJsonArray("torsoUp");
            double angle=Math.toDegrees(Math.acos(Math.clamp(up.get(1).getAsDouble(),-1,1)));
            RpgSmoke.check(angle<2, "actual stop returns torso to settled stance angle="+angle); checks++;
            RpgSmoke.screenshot("moving/input-stop");
        }
        if(t==144) ClientHooks.send("attack","left");
        if(t==151) RpgSmoke.screenshot("moving/input-moving-attack");
        if(t==185) {
            movie.stop();
            RpgSmoke.check(org.fsrpg.client.SkinnedArms.renderedLegSections()>0,"actual player knee skin sections reached renderer"); checks++;
            RpgSmoke.check(ClientState.drawn(),"real-input review retains drawn combat state"); checks++;
            ClientHooks.send("draw","");
        }
        return t>=205;
    }
}
