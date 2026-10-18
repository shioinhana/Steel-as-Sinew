package org.fsrpg.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.fsrpg.combat.AttackAim;
import org.fsrpg.combat.SpearAimField;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.mixin.ClientInputAccess;
import java.util.ArrayList;

/** Camera orbit is separate from the authoritative actor direction. OS cursor stays captured. */
public final class CombatCamera {
    private static String mode="";
    private static float yaw,pitch;
    private static double cursorX,cursorY;
    private static boolean held;
    private static boolean dualFacingLocked;
    private static long pressedTick;
    private static final ArrayList<AttackAim.Point> path=new ArrayList<>();
    private record FieldKey(int width,int height,float fov,double requestedWidth,double requestedHeight,
                            float qx,float qy,float qz,float qw,float x,float y,float z,float bodyYaw) {}
    private static FieldKey cachedFieldKey;
    private static SpearCursorField.Frame cachedField;
    public static float shoulder() {return ClientOptions.SHOULDER.get().floatValue();}
    public static String style() {
        var snapshot = ClientState.snapshot();
        String mode = ClientState.text(snapshot, "weaponMode", "");
        if (!mode.isEmpty()) return mode;
        String selected = ClientState.text(snapshot, "weaponStyle", "rapier");
        for (var style : ClientState.objects(snapshot, "styles")) {
            if (!ClientState.text(style, "id", "").equals(selected)) continue;
            String controls = ClientState.text(ClientState.object(style, "controls"), "mode", "ordered");
            if (controls.equals("dual")) return "dual_blade";
            if (controls.equals("spear")) return "spear";
            String group = ClientState.text(style, "weaponGroup", "");
            if (group.equals("sword") || group.equals("rapier")) return "rapier";
        }
        return selected;
    }
    public static boolean active() {
        var g=Minecraft.getInstance(); return ClientState.drawn() && g.player!=null
                && (style().equals("rapier") || g.options.getCameraType()==net.minecraft.client.CameraType.THIRD_PERSON_BACK);
    }
    private static void sync() {
        var g=Minecraft.getInstance();String next=active()?style():"";
        if(!next.equals(mode)) { mode=next; held=false;dualFacingLocked=false;path.clear();cursorX=cursorY=0;
            if(g.player!=null){yaw=g.player.getYRot();pitch=g.player.getXRot();
                if(mode.equals("spear")){pitch=(float)Math.clamp(pitch,-SpearAimField.CAMERA_PITCH_DEGREES,SpearAimField.CAMERA_PITCH_DEGREES);face(yaw,pitch);}} }
        else if(mode.equals("spear") && (Math.abs(Mth.wrapDegrees(g.player.getYRot()-yaw))>.1 || Math.abs(g.player.getXRot()-pitch)>.1)) {
            // A server teleport/correction owns player rotation. Do not write the old camera angle back.
            yaw=g.player.getYRot();pitch=g.player.getXRot();held=false;path.clear();cursorX=cursorY=0;
        }
        if(mode.equals("spear")) {
            // Vanilla strafing rotates the body away from the cursor/server basis.
            // Use the current facing for both render endpoints, including between ticks.
            float facing=g.player.getYRot();
            g.player.yBodyRot=facing;g.player.yBodyRotO=facing;
        }
        if(mode.equals("rapier")) lockFacing();
    }
    public static void reset() { mode="";held=false;dualFacingLocked=false;path.clear();cursorX=cursorY=0;cachedFieldKey=null;cachedField=null; }
    public static boolean mouse(double dx,double dy) {
        sync();var g=Minecraft.getInstance();
        if(!active() || g.screen!=null || (!mode.equals("dual_blade")&&!mode.equals("spear")&&!mode.equals("rapier"))) return false;
        double sensitivity=g.options.sensitivity().get()*.6+.2;
        double gain=sensitivity*sensitivity*sensitivity*8;
        dx*=gain*(g.options.invertMouseX().get()?-1:1);dy*=gain*(g.options.invertMouseY().get()?-1:1);
        if(mode.equals("spear")) {
            var frame=spearFrame();
            var movement=SpearCursorField.move(frame,cursorX,cursorY,dx,dy);
            yaw+=(float)(movement.overflowX()*.15);pitch=(float)Math.clamp(pitch+movement.overflowY()*.15,-SpearAimField.CAMERA_PITCH_DEGREES,SpearAimField.CAMERA_PITCH_DEGREES);
            cursorX=movement.x();cursorY=movement.y();
            // Within the displayed field only the pole moves. Overflow turns the actor.
            if(Math.abs(movement.overflowX())>.00001 || Math.abs(movement.overflowY())>.00001)face(yaw,pitch);
        } else {yaw+=(float)(dx*.15);pitch=Mth.clamp(pitch+(float)(dy*.15),mode.equals("rapier")?-80:-65,mode.equals("rapier")?80:65);}
        yaw=Mth.wrapDegrees(yaw);
        if(held) samplePath();
        return true;
    }
    public static float yaw() { sync();return yaw; }
    public static float pitch() { sync();return pitch; }
    /** The server selects this entity; local positions only predict its visible facing. */
    public static LivingEntity lockedTarget() {
        var game=Minecraft.getInstance();if(game.level==null||game.player==null||!ClientState.drawn()||!style().equals("rapier"))return null;
        var lock=ClientState.object(ClientState.snapshot(),"targetLock");
        if(!ClientState.bool(lock,"enabled",false))return null;
        int id=(int)ClientState.number(lock,"targetId",-1);var entity=game.level.getEntity(id);
        double range=ClientState.number(lock,"range",10);
        if(!(entity instanceof LivingEntity living)||!living.isAlive()||!Double.isFinite(range)||range<=0
                ||game.player.distanceToSqr(living)>range*range
                ||!living.getUUID().toString().equals(ClientState.text(lock,"targetUuid","")))return null;
        return living;
    }
    public static float lockYaw() {
        var game=Minecraft.getInstance();var target=lockedTarget();if(target==null)return game.player==null?0:game.player.getYRot();
        var delta=target.position().subtract(game.player.position());
        return delta.horizontalDistanceSqr()<1e-10?game.player.getYRot():(float)AttackAim.wrap(-Math.toDegrees(Math.atan2(delta.x,delta.z)));
    }
    private static void lockFacing() {
        var game=Minecraft.getInstance();var target=lockedTarget();if(target==null)return;
        var player=game.player;var delta=target.getBoundingBox().getCenter().subtract(player.getEyePosition());
        float facing=lockYaw(),elevation=(float)Math.clamp(-Math.toDegrees(Math.atan2(delta.y,delta.horizontalDistance())),-80,80);
        face(facing,elevation);
        // Vanilla strafing/head limits must not turn a locked fighter toward travel or camera.
        player.yBodyRotO=facing;player.yHeadRotO=facing;player.yRotO=facing;player.xRotO=elevation;
    }
    private static float inputCameraYaw() {
        return yaw+(Minecraft.getInstance().options.getCameraType()==net.minecraft.client.CameraType.THIRD_PERSON_FRONT?180:0);
    }
    public static void prepareRapierAttack() {
        sync();if(!mode.equals("rapier"))return;
        if(lockedTarget()!=null)lockFacing();else face(inputCameraYaw(),pitch);
    }
    /** Direction intent only; the server retains dodge distance, cost and allowed states. */
    public static String dodgeIntent() {
        var game=Minecraft.getInstance();if(game.player==null)return "";
        var move=game.player.input.getMoveVector();if(move.lengthSquared()<.001)return "";
        var world=OrbitMovement.relative(move.x,move.y,game.player.getYRot(),0);
        double length=Math.hypot(world.left(),world.forward());if(length<1e-6)return "";
        var request=new JsonObject();request.addProperty("x",world.left()/length);request.addProperty("z",world.forward()/length);
        return request.toString();
    }
    public static double cursorX() { return cursorX; }
    public static double cursorY() { return cursorY; }
    /** Same pixel field for input, reticle, picking and the focused live review. */
    public static SpearCursorField.Frame spearFrame() {
        var g=Minecraft.getInstance();var camera=g.gameRenderer.getMainCamera();
        float fov=((org.fsrpg.mixin.GameRendererAccess)g.gameRenderer).fsrpg$fov(camera,g.getDeltaTracker().getGameTimeDeltaPartialTick(false),true);
        var rotation=camera.rotation();var offset=vector(camera.position().subtract(g.player.getEyePosition()));
        var key=new FieldKey(g.getWindow().getScreenWidth(),g.getWindow().getScreenHeight(),fov,
                SpearCursorField.expandedWidth(ClientOptions.SPEAR_RECT_W.get()),SpearCursorField.expandedHeight(ClientOptions.SPEAR_RECT_H.get()),
                rotation.x,rotation.y,rotation.z,rotation.w,offset.x,offset.y,offset.z,g.player.getYRot());
        if(key.equals(cachedFieldKey))return cachedField;
        cachedField=SpearCursorField.frame(key.width(),key.height(),fov,key.requestedWidth(),key.requestedHeight(),rotation,offset,key.bodyYaw());
        cachedFieldKey=key;return cachedField;
    }
    private static org.joml.Vector3f vector(Vec3 value){return new org.joml.Vector3f((float)value.x,(float)value.y,(float)value.z);}
    public static void angles(ViewportEvent.ComputeCameraAngles event) {
        sync();if(active() && (mode.equals("dual_blade")||mode.equals("spear")||mode.equals("rapier"))) {event.setYaw(yaw);event.setPitch(pitch);}
    }
    private static String phase() { return ClientState.text(ClientState.snapshot(),"phase",""); }
    private static boolean locked() { return java.util.Set.of("WINDUP","ACTIVE","RECOVERY","GUARDING").contains(phase()); }
    private static void face(float angle,float elevation) {
        var p=Minecraft.getInstance().player;if(p==null)return;
        p.setYRot(angle);p.setXRot(elevation);p.yHeadRot=angle;p.yBodyRot=angle;
    }
    public static void movement(MovementInputUpdateEvent event) {
        sync(); if(!active() || (!mode.equals("dual_blade")&&!mode.equals("rapier")))return;
        var p=Minecraft.getInstance().player;var v=event.getInput().getMoveVector();
        boolean rapier=mode.equals("rapier"),targeted=rapier&&lockedTarget()!=null;
        if(v.lengthSquared()>.001 && !locked()&&!targeted) {
            dualFacingLocked=false;
            float target=OrbitMovement.facing(inputCameraYaw(),v.x,v.y);
            face(p.getYRot()+Mth.wrapDegrees(target-p.getYRot())*.65f,0);
        }
        // Rotate camera-relative WASD into the actor's local movement basis.
        var relative=OrbitMovement.relative(v.x,v.y,inputCameraYaw(),p.getYRot());
        ((ClientInputAccess)event.getInput()).fsrpg$move(new Vec2(relative.left(),relative.forward()));
    }
    public static void attack(String button) {
        sync();var p=Minecraft.getInstance().player;
        if(mode.equals("dual_blade")) {
            if(!locked()&&!dualFacingLocked&&p.input.getMoveVector().lengthSquared()<.001) face(yaw,0);
            dualFacingLocked=true;
            var request=new JsonObject();request.addProperty("button",button);
            request.add("aim",RpgConfig.JSON.toJsonTree(AttackAim.at(p.getYRot(),0)));
            ClientHooks.send("aimAttack",request.toString());
        } else if(mode.equals("spear")) {
            var point=aimPoint();if(!SpearAimField.contains(point,p.getYRot()))return;
            var request=new JsonObject();request.addProperty("button","left");
            request.add("aim",RpgConfig.JSON.toJsonTree(new AttackAim(java.util.List.of(point))));
            ClientHooks.send("aimAttack",request.toString());
        } else ClientHooks.send("attack",button);
    }
    public static boolean gestureHeld() { return held; }
    public static void tick(boolean down) {
        sync();var g=Minecraft.getInstance();
        if(!active() || !mode.equals("spear") || g.screen!=null || !g.isWindowActive()) {held=false;path.clear();return;}
        gesture(down);
    }
    /** Gesture processing is shared by physical input and the integrated-client review. */
    public static void gesture(boolean down) {
        sync();var g=Minecraft.getInstance();
        if(!active() || !mode.equals("spear") || g.screen!=null) {held=false;path.clear();return;}
        if(down&&!held) {held=true;path.clear();pressedTick=g.level.getGameTime();samplePath();}
        if(down) samplePath();
        if(!down&&held) {
            samplePath();held=false;
            if(path.isEmpty())return;
            double length=0;for(int i=1;i<path.size();i++)length+=Math.hypot(AttackAim.wrap(path.get(i).yaw()-path.get(i-1).yaw()),path.get(i).pitch()-path.get(i-1).pitch());
            boolean sweep=g.level.getGameTime()-pressedTick>=ClientOptions.SPEAR_HOLD.get() && length>=ClientOptions.SPEAR_DRAG.get();
            var points=sweep?java.util.List.copyOf(path):java.util.List.of(path.getLast());
            if(length<=240) {
                var request=new JsonObject();request.addProperty("button",sweep?"sweep":"left");request.add("aim",RpgConfig.JSON.toJsonTree(new AttackAim(points)));
                ClientHooks.send("aimAttack",request.toString());
            }
            path.clear();
        }
    }
    private static void samplePath() {
        var point=aimPoint();
        // Turning at an outer edge starts a new legal arc; old world directions must
        // never leave the current body field in the packet sent on gesture release.
        var p=Minecraft.getInstance().player;
        path.removeIf(old->!SpearAimField.contains(old,p.getYRot()));
        if(!SpearAimField.contains(point,p.getYRot()))return;
        if(path.isEmpty()) {path.add(point);return;}
        var previous=path.getLast();
        if(Math.hypot(AttackAim.wrap(point.yaw()-previous.yaw()),point.pitch()-previous.pitch())<1)return;
        if(path.size()>=32) {for(int i=path.size()-2;i>0;i-=2)path.remove(i);}
        path.add(point);
    }
    public static AttackAim.Point aimPoint() {
        var g=Minecraft.getInstance();var camera=g.gameRenderer.getMainCamera();
        var frame=spearFrame();cursorX=frame.clampX(cursorX);cursorY=frame.clampY(cursorY);
        double height=g.getWindow().getScreenHeight(),width=g.getWindow().getScreenWidth();
        float fov=((org.fsrpg.mixin.GameRendererAccess)g.gameRenderer).fsrpg$fov(camera,g.getDeltaTracker().getGameTimeDeltaPartialTick(false),true);
        var local=SpearCursorField.ray(cursorX,cursorY,height,fov,camera.rotation());
        Vec3 ray=new Vec3(local),start=camera.position(),end=start.add(ray.scale(64));
        double minimum=SpearCursorField.minimumRayDistance(vector(start.subtract(g.player.getEyePosition())),local,g.player.getYRot());
        Vec3 pickStart=start.add(ray.scale(Math.min(63,minimum)));
        var block=g.level.clip(new ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,g.player));
        Vec3 target=block.getLocation();
        // Near targets retain their true eye direction for combat. Ignore only hits
        // outside the admitted forward field, including camera-side obstructions.
        if(spearTarget(target)==null)target=g.level.clip(new ClipContext(pickStart,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,g.player)).getLocation();
        // Prefer an actual entity under the cursor, never a screen-to-yaw approximation.
        double nearest=start.distanceToSqr(target);
        var admitted=g.player.getBoundingBox().inflate(64);
        // Only boxes touching this ray can be picked. Preserve the old admitted
        // domain while avoiding a 128-block-wide cube query on every render frame.
        var rayBounds=new net.minecraft.world.phys.AABB(start,end).inflate(.080001);
        for(var entity:g.level.getEntities(g.player,rayBounds,e->e.isPickable()&&e.getBoundingBox().intersects(admitted))) {
            var hit=entity.getBoundingBox().inflate(.08).clip(start,end);
            if(hit.isPresent()&&spearTarget(hit.get())!=null&&start.distanceToSqr(hit.get())<nearest){target=hit.get();nearest=start.distanceToSqr(target);}
        }
        var point=spearTarget(target);
        return point!=null?point:new AttackAim.Point(AttackAim.wrap(g.player.getYRot()),Math.clamp(g.player.getXRot(),-SpearAimField.MAX_PITCH_DEGREES,SpearAimField.MAX_PITCH_DEGREES),64);
    }
    private static AttackAim.Point spearTarget(Vec3 target){var g=Minecraft.getInstance();var d=target.subtract(g.player.getEyePosition());return SpearAimField.target(d.x,d.y,d.z,g.player.getYRot());}
    public static void hud(GuiGraphics gui) {
        sync();if(!active())return;var g=Minecraft.getInstance();
        if(mode.equals("spear")) {
            var frame=spearFrame();cursorX=frame.clampX(cursorX);cursorY=frame.clampY(cursorY);
            int cx=gui.guiWidth()/2,cy=gui.guiHeight()/2;
            double scaleX=gui.guiWidth()/(double)g.getWindow().getScreenWidth(),scaleY=gui.guiHeight()/(double)g.getWindow().getScreenHeight();
            int left=cx+(int)Math.ceil(frame.left()*scaleX),right=cx+(int)Math.floor(frame.right()*scaleX),top=cy+(int)Math.ceil(frame.top()*scaleY),bottom=cy+(int)Math.floor(frame.bottom()*scaleY);
            int color=held?0xffe5bf65:0x668fb8d8;
            gui.fill(left,top,right,top+1,color);gui.fill(left,bottom,right,bottom+1,color);
            gui.fill(left,top,left+1,bottom,color);gui.fill(right,top,right+1,bottom,color);
            int x=cx+(int)(cursorX*gui.guiWidth()/g.getWindow().getScreenWidth()),y=cy+(int)(cursorY*gui.guiHeight()/g.getWindow().getScreenHeight());
            gui.fill(x-5,y,x+6,y+1,0xffffdf83);gui.fill(x,y-5,x+1,y+6,0xffffdf83);
            gui.drawString(g.font,held?"松开扫击 · 拖动记录轨迹":"点按刺击 · 按住拖动扫击",10,66,0xffe6edff);
        } else if(mode.equals("rapier")) {
            var target=lockedTarget();var lock=ClientState.object(ClientState.snapshot(),"targetLock");
            String info=target==null?"索敌范围 "+HudReadout.number(ClientState.number(lock,"range",10))+" 格 · 优先敌对生物"
                    :"锁定 "+target.getDisplayName().getString()+" · "+String.format(java.util.Locale.ROOT,"%.1f",g.player.distanceTo(target))+" 格";
            gui.drawString(g.font,info,10,66,target==null?0xffa8b7c9:0xffffdf83);
        } else if(mode.equals("dual_blade")) {
            var combo=ClientState.snapshot().getAsJsonObject("combo");
            String queued=combo!=null&&ClientState.bool(combo,"queued",false)?" · 下一刀已缓存":"";
            gui.drawString(g.font,"左右键挥斩 · 点按一次一刀"+queued,10,66,0xffe6edff);
        }
    }
}
