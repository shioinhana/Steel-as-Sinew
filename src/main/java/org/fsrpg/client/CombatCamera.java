package org.fsrpg.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
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
    private static final SpearCursorSteering steering=new SpearCursorSteering();
    private static boolean held;
    private static boolean dualFacingLocked;
    private static boolean cameraOnly;
    private static CameraType previousCamera;
    private static final ShoulderCameraMotion cameraTravel=new ShoulderCameraMotion();
    private static final CameraClipSmoother clipSmoother=new CameraClipSmoother();
    private static long lastClipNanos;
    private static long perfCalls,perfRays,perfNanos,perfMaxNanos;
    private static String cachedStyle;private static long cachedStyleRevision=Long.MIN_VALUE;
    public static void resetPerfCounters(){perfCalls=perfRays=perfNanos=perfMaxNanos=0;}
    /** Render-thread camera cost, used by the DISPLAY5 movement acceptance report. */
    public static long[] perfSnapshot(){return new long[]{perfCalls,perfRays,perfNanos,perfMaxNanos};}
    private static long nextSpearInput,spearInput,lastSpearSentTick;
    private static boolean spearBlockedUntilRelease,spearAimDirty;
    private static final ArrayList<AttackAim.Point> path=new ArrayList<>();
    private record FieldKey(int width,int height,float fov,double requestedWidth,double requestedHeight,
                            float qx,float qy,float qz,float qw,float x,float y,float z,float bodyYaw) {}
    private static FieldKey cachedFieldKey;
    private static SpearCursorField.Frame cachedField;
    public static float shoulder() {return ClientOptions.SHOULDER.get().floatValue();}
    public static boolean cameraOnly(){return cameraOnly;}
    public static double transitionBlend(){return cameraTravel.blend();}
    private static boolean itemAvailable(){var p=Minecraft.getInstance().player;return p!=null&&(!p.getMainHandItem().isEmpty()||!p.getOffhandItem().isEmpty());}
    private static boolean combatCameraRequested(){String phase=ClientState.text(ClientState.snapshot(),"phase","");return phase.equals("DRAWING")||ClientState.drawn()&&!phase.equals("SHEATHING");}
    private static boolean wantsShoulder(){
        var game=Minecraft.getInstance();
        return game.player!=null&&game.player.isAlive()&&!game.player.isSpectator()&&game.getCameraEntity()==game.player
                &&(combatCameraRequested()&&ClientOptions.THIRD_PERSON.get()||cameraOnly&&itemAvailable());
    }
    public static void drawKey(){
        var game=Minecraft.getInstance();if(game.player==null)return;
        if(ClientState.bool(ClientState.snapshot(),"weaponEquipped",false)||ClientState.drawn()){
            cameraOnly=false;ClientHooks.send("draw","");
        }else if(itemAvailable()){cameraOnly=!cameraOnly;updateView();}
    }
    /** Keep third person until the return has actually reached the eyes; packets cannot cause camera jumps. */
    public static void updateView(){
        var game=Minecraft.getInstance();
        if(cameraOnly&&!itemAvailable())cameraOnly=false;
        if(game.player==null||game.level==null||!game.player.isAlive()||game.player.isSpectator()||game.getCameraEntity()!=game.player){
            if(previousCamera!=null)game.options.setCameraType(previousCamera);
            previousCamera=null;cameraTravel.reset();clipSmoother.reset();lastClipNanos=0;return;
        }
        if(wantsShoulder()){
            if(previousCamera==null){previousCamera=game.options.getCameraType();cameraTravel.reset();clipSmoother.reset();lastClipNanos=0;}
            game.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }else if(previousCamera!=null&&cameraTravel.settledOff()){
            game.options.setCameraType(previousCamera);previousCamera=null;
        }
    }
    /** Camera.setup already established the final orbit rotation; translate the real camera with wall safety. */
    public static Vec3 cameraPosition(net.minecraft.world.level.Level level,net.minecraft.world.entity.Entity entity,Camera camera,float partial){
        var game=Minecraft.getInstance();
        if(entity!=game.player||game.getCameraEntity()!=game.player||previousCamera==null)return null;
        Vec3 eye=entity.getEyePosition(partial);
        long started=System.nanoTime();
        var local=new org.joml.Vector3f(shoulder(),ClientOptions.CAMERA_HEIGHT.get().floatValue(),ClientOptions.CAMERA_DISTANCE.get().floatValue()).rotate(camera.rotation());
        // Smooth the orbit first, then clip the final offset once. Smoothing before the safety clip
        // keeps the same wall protection without a second eight-corner sweep on every frame.
        var offset=cameraTravel.sample(wantsShoulder(),System.nanoTime()/1e9,new ShoulderCameraMotion.Offset(local.x,local.y,local.z),
                ClientOptions.CAMERA_TRANSITION.get(),ClientOptions.CAMERA_RESPONSE.get());
        Vec3 result=eye.add(clipCamera(level,entity,eye,new Vec3(offset.x(),offset.y(),offset.z())));
        long elapsed=System.nanoTime()-started;
        perfCalls++;perfNanos+=elapsed;perfMaxNanos=Math.max(perfMaxNanos,elapsed);
        return result;
    }
    private static Vec3 clipCamera(net.minecraft.world.level.Level level,net.minecraft.world.entity.Entity entity,Vec3 eye,Vec3 offset){
        double length=offset.length();if(length<1e-7)return offset;
        double fraction=1;
        for(int i=0;i<8;i++){
            Vec3 corner=new Vec3((i&1)==0?-.1:.1,(i&2)==0?-.1:.1,(i&4)==0?-.1:.1);
            Vec3 from=eye.add(corner),end=from.add(offset);
            var hit=level.clip(new ClipContext(from,end,ClipContext.Block.VISUAL,ClipContext.Fluid.NONE,entity));
            perfRays++;
            if(hit.getType()!=net.minecraft.world.phys.HitResult.Type.MISS)fraction=Math.min(fraction,Math.max(0,from.distanceTo(hit.getLocation())-.05)/length);
        }
        long now=System.nanoTime();
        double frameSeconds=lastClipNanos==0?0:Math.clamp((now-lastClipNanos)/1e9,0,.25);
        lastClipNanos=now;
        double eased=clipSmoother.advance(Math.clamp(fraction,0,1),frameSeconds);
        return offset.scale(Math.clamp(eased,0,1));
    }
    public static Vec3 burstDirection(){
        var game=Minecraft.getInstance();if(game.player==null)return Vec3.ZERO;
        var input=game.player.input.getMoveVector();
        if(input.lengthSquared()>.001){var world=OrbitMovement.relative(input.x,input.y,game.player.getYRot(),0);return new Vec3(world.left(),0,world.forward()).normalize();}
        return game.player.getLookAngle().multiply(1,0,1).normalize();
    }
    public static String style() {
        long revision=ClientState.revision();
        if(revision==cachedStyleRevision&&cachedStyle!=null)return cachedStyle;
        cachedStyle=resolveStyle();cachedStyleRevision=revision;return cachedStyle;
    }
    private static String resolveStyle() {
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
        var g=Minecraft.getInstance();return g.player!=null&&g.player.isAlive()&&!g.player.isSpectator()&&g.getCameraEntity()==g.player
                &&(wantsShoulder()||previousCamera!=null&&cameraTravel.active());
    }
    private static void sync() {
        var g=Minecraft.getInstance();String next=active()?(combatCameraRequested()?style():cameraOnly?"camera_only":mode):"";
        if(!next.equals(mode)) { releaseSpear(true); mode=next;dualFacingLocked=false;path.clear();steering.reset();
            if(g.player!=null){yaw=g.player.getYRot();pitch=g.player.getXRot();
                if(mode.equals("spear")){pitch=(float)Math.clamp(pitch,-SpearAimField.CAMERA_PITCH_DEGREES,SpearAimField.CAMERA_PITCH_DEGREES);face(yaw,pitch);}} }
        if(mode.equals("spear") && (Math.abs(Mth.wrapDegrees(g.player.getYRot()-yaw))>.1 || Math.abs(g.player.getXRot()-pitch)>.1)) {
            // A real server correction replaces both camera rotation and pending mouse movement.
            releaseSpear(true);yaw=g.player.getYRot();pitch=(float)Math.clamp(g.player.getXRot(),-SpearAimField.CAMERA_PITCH_DEGREES,SpearAimField.CAMERA_PITCH_DEGREES);
            face(yaw,pitch);path.clear();steering.reset();
        }
        if(mode.equals("spear")) {
            // Vanilla strafing must not rotate the body away from the shoulder camera/server basis.
            // Use the current facing for both render endpoints, including between ticks.
            float facing=g.player.getYRot();
            g.player.yBodyRot=facing;g.player.yBodyRotO=facing;
        }
        if(!mode.equals("spear")&&lockedTarget()!=null)lockFacing();
    }
    public static void reset() {
        var game=Minecraft.getInstance();if(previousCamera!=null)game.options.setCameraType(previousCamera);
        previousCamera=null;cameraOnly=false;cameraTravel.reset();clipSmoother.reset();lastClipNanos=0;mode="";held=false;spearInput=0;spearBlockedUntilRelease=spearAimDirty=false;dualFacingLocked=false;path.clear();steering.reset();cachedFieldKey=null;cachedField=null;
    }
    public static boolean mouse(double dx,double dy) {
        sync();var g=Minecraft.getInstance();
        if(!active() || g.screen!=null)return false;
        double sensitivity=g.options.sensitivity().get()*.6+.2;
        double gain=sensitivity*sensitivity*sensitivity*8;
        dx*=gain*(g.options.invertMouseX().get()?-1:1);dy*=gain*(g.options.invertMouseY().get()?-1:1);
        if(mode.equals("spear")) {
            advanceSpear();
            steering.move(dx,dy,g.getWindow().getScreenWidth(),g.getWindow().getScreenHeight());
        } else {
            // Raw input first changes the camera. Unlocked actor facing is derived from that final camera direction.
            yaw+=(float)(dx*.15);pitch=Mth.clamp(pitch+(float)(dy*.15),-80,80);
            if(lockedTarget()==null)face(yaw,pitch);else lockFacing();
        }
        yaw=Mth.wrapDegrees(yaw);
        if(held) samplePath();
        return true;
    }
    public static float yaw() { sync();return yaw; }
    public static float pitch() { sync();return pitch; }
    /** The server selects this entity; local positions only predict its visible facing. */
    public static LivingEntity lockedTarget() {
        var game=Minecraft.getInstance();if(game.level==null||game.player==null||!ClientState.drawn()||style().equals("spear"))return null;
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
        sync();if(mode.isEmpty()||mode.equals("spear"))return;
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
    public static double normalizedCursorX(){return steering.x();}
    public static double normalizedCursorY(){return steering.y();}
    public static double cursorX(){var f=cursorFrame();return steering.x()<0?-steering.x()*f.left():steering.x()*f.right();}
    public static double cursorY(){var f=cursorFrame();return steering.y()<0?-steering.y()*f.top():steering.y()*f.bottom();}
    private record CursorKey(FieldKey geometry,double scale,double y){}
    private static CursorKey cursorKey;
    private static SpearCursorField.Frame cursorFrame;
    /** Current input rotation, including mouse input since the last render, using the native camera convention. */
    public static org.joml.Quaternionf cursorRotation(){
        return new org.joml.Quaternionf().rotationYXZ((float)Math.toRadians(180-yaw),(float)Math.toRadians(-pitch),(float)Math.toRadians(-cameraRoll()));
    }
    private static SpearCursorField.Frame cursorFrame(){
        var g=Minecraft.getInstance();if(g.player==null)return new SpearCursorField.Frame(0,0,0,0);
        var camera=g.gameRenderer.getMainCamera();var rotation=cursorRotation();
        float fov=((org.fsrpg.mixin.GameRendererAccess)g.gameRenderer).fsrpg$fov(camera,g.getDeltaTracker().getGameTimeDeltaPartialTick(false),true);
        var offset=vector(camera.position().subtract(g.player.getEyePosition()));
        var geometry=new FieldKey(g.getWindow().getScreenWidth(),g.getWindow().getScreenHeight(),fov,1,1,
                rotation.x,rotation.y,rotation.z,rotation.w,offset.x,offset.y,offset.z,g.player.getYRot());
        var key=new CursorKey(geometry,ClientOptions.SPEAR_LOCKED_RANGE.get(),steering.y());
        if(key.equals(cursorKey))return cursorFrame;
        cursorFrame=SpearCursorField.steeringFrame(geometry.width(),geometry.height(),fov,rotation,offset,geometry.bodyYaw(),key.scale(),key.y());
        cursorKey=key;return cursorFrame;
    }
    public static Vec3 cursorRay(){
        var g=Minecraft.getInstance();var camera=g.gameRenderer.getMainCamera();
        float fov=((org.fsrpg.mixin.GameRendererAccess)g.gameRenderer).fsrpg$fov(camera,g.getDeltaTracker().getGameTimeDeltaPartialTick(false),true);
        return new Vec3(SpearCursorField.ray(cursorX(),cursorY(),g.getWindow().getScreenHeight(),fov,cursorRotation()));
    }
    private static void advanceSpear(){
        if(!mode.equals("spear"))return;var g=Minecraft.getInstance();
        if(g.screen!=null||g.isPaused()||!g.isWindowActive()){steering.reset();return;}
        double scale=ClientOptions.SPEAR_LOCKED_RANGE.get();
        var settings=new SpearCursorSteering.Settings(SpearAimField.MAX_YAW_DEGREES*scale,SpearAimField.MAX_PITCH_DEGREES*scale,
                ClientOptions.SPEAR_TURN_CENTER.get(),ClientOptions.SPEAR_TURN_EDGE.get(),ClientOptions.SPEAR_TURN_EDGE_START.get(),ClientOptions.SPEAR_TURN_RESPONSE.get());
        var step=steering.advance(System.nanoTime()/1e9,pitch,SpearAimField.CAMERA_PITCH_DEGREES,settings);
        yaw=Mth.wrapDegrees(yaw+(float)step.yaw());pitch+=(float)step.pitch();face(yaw,pitch);
        if(held&&(Math.abs(step.yaw())+Math.abs(step.pitch())>.001))samplePath();
    }
    /** Legacy geometry review API. Live shoulder aiming no longer uses a screen field. */
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
        sync();if(active()) {
            advanceSpear();
            event.setYaw(yaw);event.setPitch(pitch);
            event.setRoll(event.getRoll()+cameraRoll());
        }
    }
    public static float cameraRoll(){return (float)(ClientOptions.CAMERA_ROLL.get()*transitionBlend());}
    public static boolean reticleVisible(){return active()&&(mode.equals("spear")||lockedTarget()==null||!mode.equals("rapier"));}
    private static String phase() { return ClientState.text(ClientState.snapshot(),"phase",""); }
    private static boolean locked() { return java.util.Set.of("WINDUP","ACTIVE","RECOVERY","GUARDING").contains(phase()); }
    private static void face(float angle,float elevation) {
        var p=Minecraft.getInstance().player;if(p==null)return;
        p.setYRot(angle);p.setXRot(elevation);p.yHeadRot=angle;p.yBodyRot=angle;
    }
    public static void movement(MovementInputUpdateEvent event) {
        sync();
        if(ClientState.drawn()){
            var input=event.getInput();var keys=input.keyPresses;
            if(keys.shift())input.keyPresses=new net.minecraft.world.entity.player.Input(keys.forward(),keys.backward(),keys.left(),keys.right(),keys.jump(),false,keys.sprint());
            Minecraft.getInstance().player.setShiftKeyDown(false);
        }
        if(!active()||mode.equals("spear"))return;
        var p=Minecraft.getInstance().player;var v=event.getInput().getMoveVector();
        if(lockedTarget()!=null)lockFacing();else face(inputCameraYaw(),pitch);
        // Rotate camera-relative WASD into the actor's local movement basis.
        var relative=OrbitMovement.relative(v.x,v.y,inputCameraYaw(),p.getYRot());
        ((ClientInputAccess)event.getInput()).fsrpg$move(new Vec2(relative.left(),relative.forward()));
    }
    public static void attack(String button) {
        sync();var p=Minecraft.getInstance().player;
        if(mode.equals("spear")) {
            // A complete physical click can occur between two client ticks.
            if(!held&&!spearBlockedUntilRelease){gesture(true);gesture(false);}
        } else targetAttack(button);
    }
    /** Real camera reticle selection is an intention; the server verifies target, range, ray and attack acceptance. */
    public static void targetAttack(String button){
        sync();var game=Minecraft.getInstance();if(game.player==null||game.level==null)return;
        prepareRapierAttack();
        LivingEntity target=null;Vec3 contact=null;
        float aimYaw=active()?yaw:game.player.getYRot(),aimPitch=active()?pitch:game.player.getXRot();
        var camera=game.gameRenderer.getMainCamera();Vec3 start=camera.isInitialized()?camera.position():game.player.getEyePosition();
        Vec3 direction=Vec3.directionFromRotation(aimPitch,aimYaw),end=start.add(direction.scale(10+start.distanceTo(game.player.getEyePosition())+.5));
        var wall=game.level.clip(new ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,game.player));
        double nearest=start.distanceToSqr(wall.getLocation());
        for(var entity:game.level.getEntitiesOfClass(LivingEntity.class,new net.minecraft.world.phys.AABB(start,end).inflate(.1),
                e->e!=game.player&&e.isAlive()&&!e.isSpectator()&&!(e instanceof net.minecraft.world.entity.player.Player)&&game.player.distanceToSqr(e)<=100)){
            var box=entity.getBoundingBox().inflate(.05);var hit=box.contains(start)?java.util.Optional.of(start):box.clip(start,end);
            if(hit.isPresent()&&start.distanceToSqr(hit.get())<=nearest){target=entity;contact=hit.get();nearest=start.distanceToSqr(contact);}
        }
        AttackAim aim=AttackAim.at(aimYaw,aimPitch);
        if(target!=null){
            var delta=contact.subtract(game.player.getEyePosition());double length=delta.length();
            double elevation=-Math.toDegrees(Math.atan2(delta.y,delta.horizontalDistance()));
            if(length<.0001||Math.abs(elevation)>80){target=null;}else aim=new AttackAim(java.util.List.of(new AttackAim.Point(AttackAim.wrap(-Math.toDegrees(Math.atan2(delta.x,delta.z))),elevation,Math.clamp(length,.5,96))));
        }
        var request=new JsonObject();request.addProperty("button",button);request.addProperty("targetId",target==null?-1:target.getId());
        request.addProperty("targetUuid",target==null?"":target.getUUID().toString());request.add("aim",RpgConfig.JSON.toJsonTree(aim));
        ClientHooks.send("targetAttack",request.toString());
    }
    public static boolean gestureHeld() { return held; }
    public static void tick(boolean down) {
        sync();var g=Minecraft.getInstance();
        if(!active() || !mode.equals("spear") || g.screen!=null || !g.isWindowActive()) {releaseSpear(true);steering.reset();return;}
        advanceSpear();gesture(down);
    }
    /** Gesture processing is shared by physical input and the integrated-client review. */
    public static void gesture(boolean down) {
        sync();var g=Minecraft.getInstance();
        if(!active() || !mode.equals("spear") || g.screen!=null) {releaseSpear(true);return;}
        if(!down)spearBlockedUntilRelease=false;
        if(down&&!held&&!spearBlockedUntilRelease) {
            var point=aimPoint();if(!SpearAimField.contains(point,g.player.getYRot()))return;
            held=true;path.clear();path.add(point);spearInput=++nextSpearInput;
            sendSpear("spearPress");
        }
        if(down&&held) {
            samplePath();
            if(spearAimDirty||path.size()>1||g.level.getGameTime()-lastSpearSentTick>=4)sendSpear("spearMove");
        }
        if(!down&&held) {samplePath();releaseSpear(false);}
    }
    private static void sendSpear(String action) {
        if(spearInput==0)return;
        var request=new JsonObject();request.addProperty("input",spearInput);
        if(!path.isEmpty())request.add("aim",RpgConfig.JSON.toJsonTree(new AttackAim(java.util.List.copyOf(path))));
        ClientHooks.send(action,request.toString());
        spearAimDirty=false;
        var level=Minecraft.getInstance().level;if(level!=null)lastSpearSentTick=level.getGameTime();
        if(!path.isEmpty()){var point=path.getLast();path.clear();path.add(point);}
    }
    private static void releaseSpear(boolean interrupted) {
        if(held){sendSpear("spearRelease");if(interrupted)spearBlockedUntilRelease=true;}
        held=false;spearInput=0;spearAimDirty=false;path.clear();
    }
    private static void samplePath() {
        var point=aimPoint();
        // A body turn starts a legal forward arc; no bridge may cross the torso.
        var p=Minecraft.getInstance().player;
        if(path.removeIf(old->!SpearAimField.contains(old,p.getYRot())))spearAimDirty=true;
        if(!SpearAimField.contains(point,p.getYRot()))return;
        if(path.isEmpty()) {path.add(point);spearAimDirty=true;return;}
        var previous=path.getLast();
        if(Math.hypot(AttackAim.wrap(point.yaw()-previous.yaw()),point.pitch()-previous.pitch())<.1)return;
        // Flush before either packet bound. A fast back-and-forth drag can exceed 240 degrees in fewer than 32 points.
        double length=Math.hypot(AttackAim.wrap(point.yaw()-previous.yaw()),point.pitch()-previous.pitch());
        for(int i=1;i<path.size();i++)length+=Math.hypot(AttackAim.wrap(path.get(i).yaw()-path.get(i-1).yaw()),path.get(i).pitch()-path.get(i-1).pitch());
        if(path.size()>=32||length>240)sendSpear("spearMove");
        path.add(point);spearAimDirty=true;
    }
    public static AttackAim.Point aimPoint() {
        var g=Minecraft.getInstance();var camera=g.gameRenderer.getMainCamera();
        // The visible floating reticle and combat share this exact world ray, including shoulder parallax.
        Vec3 start=camera.position(),ray=cursorRay();
        Vec3 end=start.add(ray.scale(64));
        var local=vector(ray);
        double minimum=SpearCursorField.minimumRayDistance(vector(start.subtract(g.player.getEyePosition())),local,g.player.getYRot());
        Vec3 pickStart=start.add(ray.scale(Math.min(63,minimum)));
        var block=g.level.clip(new ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,g.player));
        Vec3 target=block.getLocation();
        // Near targets retain their true eye direction for combat. Ignore only hits
        // outside the admitted forward field, including camera-side obstructions.
        if(spearTarget(target)==null)target=g.level.clip(new ClipContext(pickStart,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,g.player)).getLocation();
        // Aim through the visible cursor at the real world hit, preserving parallax.
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
    private static AttackAim.Point spearTarget(Vec3 target){
        var g=Minecraft.getInstance();var d=target.subtract(g.player.getEyePosition());var point=SpearAimField.target(d.x,d.y,d.z,g.player.getYRot());
        double scale=ClientOptions.SPEAR_LOCKED_RANGE.get();
        return point!=null&&Math.abs(AttackAim.wrap(point.yaw()-g.player.getYRot()))<=SpearAimField.MAX_YAW_DEGREES*scale+.001
                &&Math.abs(point.pitch())<=SpearAimField.MAX_PITCH_DEGREES*scale+.001?point:null;
    }
    public static void hud(GuiGraphics gui) {
        sync();if(!active())return;var g=Minecraft.getInstance();
        if(reticleVisible()){
            int x=gui.guiWidth()/2+(mode.equals("spear")?(int)Math.round(cursorX()*gui.guiWidth()/g.getWindow().getScreenWidth()):0),
                    y=gui.guiHeight()/2+(mode.equals("spear")?(int)Math.round(cursorY()*gui.guiHeight()/g.getWindow().getScreenHeight()):0),
                    color=ShoulderInteraction.blocked()?0xffff7070:held||lockedTarget()!=null?0xffffd26e:0xfff0f3f5;
            boolean showReticle=true;
            if(ShoulderInteraction.nativeDirectionalAttack()&&g.hitResult!=null){
                // Vanilla piercing attacks trace the player's head direction on the server,
                // rather than using hitResult. Show that native ray's actual point without
                // rotating the actor or sending extra movement/attack packets.
                Vec3 point=g.hitResult.getLocation();
                Vec3 projected=g.gameRenderer.projectPointToScreen(point);
                var camera=g.gameRenderer.getMainCamera();
                showReticle=Double.isFinite(projected.x)&&Double.isFinite(projected.y)
                        &&point.subtract(camera.position()).dot(new Vec3(camera.forwardVector()))>0;
                var rendered=ShoulderInteractionView.current();
                if(rendered!=null){
                    var actual=rendered.project(new ShoulderViewRay.Point(point.x,point.y,point.z));
                    projected=new Vec3(actual.x(),actual.y(),actual.depth());
                    showReticle=actual.inFront()&&Double.isFinite(actual.x())&&Double.isFinite(actual.y());
                }
                if(showReticle){
                    x=(int)Math.round((projected.x+1)*gui.guiWidth()*.5);
                    y=(int)Math.round((1-projected.y)*gui.guiHeight()*.5);
                    showReticle=x>=0&&x<gui.guiWidth()&&y>=0&&y<gui.guiHeight();
                }
            }
            if(showReticle){
                gui.fill(x-6,y,x-2,y+1,color);gui.fill(x+3,y,x+7,y+1,color);
                gui.fill(x,y-6,x+1,y-2,color);gui.fill(x,y+3,x+1,y+7,color);
            }
            if(cameraOnly){gui.drawString(g.font,"越肩观察 · R 返回",10,66,0xffd9e6ef);return;}
        }
        if(mode.equals("spear")) {
            gui.drawString(g.font,held?"保持刺出 · 移动准星扫击 · 松开收回":"长枪收回 · 点按刺出 · 偏移准星转向",10,66,0xffe6edff);
        } else if(mode.equals("rapier")) {
            var target=lockedTarget();var lock=ClientState.object(ClientState.snapshot(),"targetLock");
            String info=target==null?"准星攻击锁定 · "+HudReadout.number(ClientState.number(lock,"range",10))+" 格"
                    :"锁定 "+target.getDisplayName().getString()+" · "+String.format(java.util.Locale.ROOT,"%.1f",g.player.distanceTo(target))+" 格 · R 解锁";
            gui.drawString(g.font,info,10,66,target==null?0xffa8b7c9:0xffffdf83);
        } else if(mode.equals("dual_blade")) {
            var combo=ClientState.snapshot().getAsJsonObject("combo");
            String queued=combo!=null&&ClientState.bool(combo,"queued",false)?" · 下一刀已缓存":"";
            gui.drawString(g.font,(lockedTarget()==null?"左右键挥斩 · 准星攻击锁定":"已锁定 · R 解锁")+queued,10,66,0xffe6edff);
        }
    }
}
