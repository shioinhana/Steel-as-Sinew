package org.fsrpg.smoke;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import org.fsrpg.client.PlayerAnimations;
import org.fsrpg.client.OfficialHeldItems;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.HeldItemTransform;
import org.fsrpg.client.LegDeformation;
import net.minecraft.client.model.geom.ModelPart;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.fsrpg.config.RpgConfig;
import org.fsrpg.network.RpgAnimationPayload;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Samples the real player's renderer and the same partial-tick rig used for the framebuffer just captured. */
final class RenderObservations {
    private RenderObservations() {}
    private record Context(AvatarRenderState state, PlayerModel model) {}
    private static final Map<String,Field> fields=new HashMap<>();
    private static final Map<Integer,JsonObject> submittedAvatars=new HashMap<>();
    private static long submittedRenderFrame;

    /** Read the actual avatar submission before deferred drawing reuses the shared
     * PlayerModel for a later actor/spectator. No detached model setup is performed. */
    static void beginSubmittedFrame(RenderFrameEvent.Pre event) { submittedRenderFrame++;submittedAvatars.clear(); }
    static void captureSubmittedAvatar(RenderLivingEvent.Post<?,?,?> event) {
        var game=Minecraft.getInstance();
        if(game.level==null||!(event.getRenderState() instanceof AvatarRenderState state)||state.isSpectator
                ||!(event.getRenderer().getModel() instanceof PlayerModel model)
                ||!(game.level.getEntity(state.id) instanceof AbstractClientPlayer actor))return;
        JsonObject value=observe(actor,state,model);value.addProperty("nativePoseSnapshotStage","RenderLivingEvent.Post actual avatar submission");
        value.addProperty("nativePoseSnapshotRenderFrame",submittedRenderFrame);submittedAvatars.put(state.id,value);
    }
    static Supplier<JsonObject> submittedSampler(AbstractClientPlayer actor) {
        return ()->{var value=submittedAvatars.get(actor.getId());if(value==null)
            throw new IllegalStateException("No actual current-frame avatar submission for "+actor.getUUID());return value.deepCopy();};
    }

    private static Context context(AbstractClientPlayer actor) {
        Minecraft game = Minecraft.getInstance();
        float partial = game.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var renderer = game.getEntityRenderDispatcher().getRenderer(actor);
        var rawState = renderer.createRenderState(actor, partial);
        if (!(rawState instanceof AvatarRenderState state) || !(renderer instanceof AvatarRenderer<?> avatar))
            throw new IllegalStateException("Expected the official avatar renderer/state for " + actor.getUUID());
        PlayerModel model = avatar.getModel();
        return new Context(state, model);
    }

    /** Full official extraction/setup is reserved for isolated leg assertions, rather than every captured frame. */
    static JsonObject sample(AbstractClientPlayer actor) {
        Context context = context(actor);
        context.model.setupAnim(context.state);
        return observe(actor, context.state, context.model);
    }

    static Supplier<JsonObject> sampler(AbstractClientPlayer actor) {
        Context context = context(actor);
        return () -> {
            float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
            AvatarRenderState state = context.state;
            state.id = actor.getId(); state.skin = actor.getSkin(); state.partialTick = partial;
            state.walkAnimationPos = actor.walkAnimation.position(partial); state.walkAnimationSpeed = actor.walkAnimation.speed(partial);
            state.bodyRot = Mth.rotLerp(partial, actor.yBodyRotO, actor.yBodyRot);
            state.yRot = Mth.wrapDegrees(Mth.rotLerp(partial, actor.yHeadRotO, actor.yHeadRot) - state.bodyRot);
            // The model was already set up by the real render pipeline this frame. Read its legs without mutating them.
            return observe(actor, state, context.model);
        };
    }

    /** Fresh official state extraction for the two isolated spear actors, without advancing or setting up their models. */
    static Supplier<JsonObject> freshSampler(AbstractClientPlayer actor) {
        return () -> {
            Context current=context(actor);
            return observe(actor,current.state,current.model);
        };
    }

    private static JsonObject observe(AbstractClientPlayer actor, AvatarRenderState state, PlayerModel model) {
        var frame = PlayerAnimations.frame(state);
        JsonObject observation = new JsonObject();
        observation.addProperty("entityId", actor.getUUID().toString());
        observation.addProperty("mainHandItem", BuiltInRegistries.ITEM.getKey(actor.getMainHandItem().getItem()).toString());
        observation.addProperty("mainHandFoil", actor.getMainHandItem().hasFoil());
        observation.addProperty("officialMainSubmissions", OfficialHeldItems.submissions());
        observation.addProperty("officialLastItemId", OfficialHeldItems.lastItemId());
        observation.addProperty("officialLastFoil", OfficialHeldItems.lastFoil());
        observation.addProperty("officialFoilSubmissions", OfficialHeldItems.foilSubmissions());
        observation.addProperty("officialOffhandSubmissions", OfficialHeldItems.offhandSubmissions());
        observation.addProperty("wristAttachedOffhandSubmissions", OfficialHeldItems.offhandAttachedSubmissions());
        observation.addProperty("offHandItem", BuiltInRegistries.ITEM.getKey(actor.getOffhandItem().getItem()).toString());
        observation.addProperty("walkAnimationPos", state.walkAnimationPos);
        observation.addProperty("walkAnimationSpeed", state.walkAnimationSpeed);
        observation.addProperty("actorTickCount",actor.tickCount);
        observation.addProperty("renderStateAgeInTicks",state.ageInTicks);
        observation.addProperty("renderPartialTick",state.partialTick);
        observation.addProperty("bodyYaw", state.bodyRot); observation.addProperty("aimDeltaYaw", state.yRot);
        observation.addProperty("sprinting", actor.isSprinting());
        observation.addProperty("phase", ClientState.text(ClientState.snapshot(), "phase", ""));
        observation.addProperty("skill", ClientState.text(ClientState.snapshot(), "skill", ""));
        observation.add("actorPositionMeters", RpgConfig.JSON.toJsonTree(new double[]{actor.getX(), actor.getY(), actor.getZ()}));
        var renderPosition=actor.getPosition(state.partialTick);
        observation.add("actorRenderPositionMeters",RpgConfig.JSON.toJsonTree(new double[]{renderPosition.x,renderPosition.y,renderPosition.z}));
        observation.add("movementXZ", RpgConfig.JSON.toJsonTree(new double[]{actor.getX() - actor.xo, actor.getZ() - actor.zo}));
        observation.add("deltaMovementMetersPerTick",RpgConfig.JSON.toJsonTree(new double[]{actor.getDeltaMovement().x,actor.getDeltaMovement().y,actor.getDeltaMovement().z}));
        JsonObject rig = new JsonObject();
        if (frame != null) {
            boolean dual = frame.model().rig().clips().containsKey("dual_idle_00");
            boolean spearRig = frame.model().rig().clips().containsKey("spear_idle");
            observation.add("renderAnimation",bindingObservation(actor,frame));
            observation.addProperty("layer", frame.layer());
            observation.addProperty("lowerBodyWeight", frame.lowerBodyWeight());
            observation.addProperty("movingWeight", frame.movingWeight());
            observation.addProperty("rightKnee", frame.rightKnee());
            observation.addProperty("leftKnee", frame.leftKnee());
            observation.addProperty("balanceXcm",frame.balance().x());
            observation.addProperty("balanceZcm",frame.balance().z());
            observation.addProperty("sampledTick", frame.sampledTick());
            for (String id : List.of("body", "head", "right_arm", "right_forearm", "right_hand", "right_item", "left_arm", "left_forearm", "left_hand", "left_item", "right_leg", "left_leg")) {
                if (frame.world().containsKey(id)) rig.add(id, RpgConfig.JSON.toJsonTree(frame.world().get(id).get(new float[16])));
            }
            for (String side : List.of("right", "left")) {
                Matrix4f arm = frame.world().get(side + "_arm"), forearm = frame.world().get(side + "_forearm"), hand = frame.world().get(side + "_hand");
                if (arm != null) {
                    observation.add(side + "ShoulderCm", vector(arm.getTranslation(new Vector3f())));
                    Quaternionf upperWorld=arm.getUnnormalizedRotation(new Quaternionf()).normalize();
                    finiteQuaternion(upperWorld,side+" world upper arm");
                    observation.add(side+"UpperArmWorldQuaternion",quaternion(upperWorld));
                }
                if (forearm != null) {
                    observation.add(side + "ElbowCm", vector(forearm.getTranslation(new Vector3f())));
                    Quaternionf forearmWorld=forearm.getUnnormalizedRotation(new Quaternionf()).normalize();
                    finiteQuaternion(forearmWorld,side+" world forearm");
                    observation.add(side+"ForearmWorldQuaternion",quaternion(forearmWorld));
                }
                if(frame.local().containsKey(side+"_forearm")) {
                    Quaternionf elbow=frame.local().get(side+"_forearm").fsQuaternion().normalize();
                    finiteQuaternion(elbow,side+" local forearm");
                    observation.add(side+"ForearmLocalQuaternion",RpgConfig.JSON.toJsonTree(new float[]{elbow.x,elbow.y,elbow.z,elbow.w}));
                    double hingeDot=Math.sqrt(elbow.x*elbow.x+elbow.w*elbow.w);
                    observation.addProperty(side+"ForearmOffXHingeDegrees",Math.toDegrees(2*Math.acos(Math.clamp(hingeDot,0,1))));
                    if (dual) {
                        // Keep the raw off-X angle above: Rx(-bend)*Ry(pronation)
                        // has real, bounded forearm rotation rather than a wrist bend.
                        double pronation=Math.toDegrees(2*Math.atan2(elbow.y,elbow.w));
                        pronation=((pronation+180)%360+360)%360-180;
                        Quaternionf hinge=new Quaternionf(elbow).rotateY((float)-Math.toRadians(pronation)).normalize();
                        double residual=Math.sqrt(hinge.x*hinge.x+hinge.w*hinge.w);
                        double bend=Math.toDegrees(2*Math.atan2(hinge.x,hinge.w));
                        bend=((bend+180)%360+360)%360-180;
                        observation.addProperty(side+"ForearmPronationDegrees",pronation);
                        observation.addProperty(side+"ForearmResidualOffXHingeDegrees",Math.toDegrees(2*Math.acos(Math.clamp(residual,0,1))));
                        observation.addProperty(side+"ForearmSignedHingeDegrees",bend);
                    }
                }
                if (hand != null) {
                    Vector3f palm = hand.transformPosition(new Vector3f(0, -6.25f, 0));
                    observation.add(side + "PalmCm", vector(palm));
                    observation.add(side + "WorldPalmCm", vector(worldBodyPoint(palm,state.bodyRot)));
                    Vector3f handLongAxis = hand.transformDirection(new Vector3f(0,-1,0)).normalize();
                    Vector3f axis = hand.transformDirection(dual ? HeldItemTransform.dualBladeAxis() : spearRig ? new Vector3f(0,0,1) : HeldItemTransform.rapierBladeAxis()).normalize();
                    observation.add(side + "BladeAxis", vector(axis));
                    observation.add(side + "HandLongAxis", vector(handLongAxis));
                    Quaternionf rotation = hand.getUnnormalizedRotation(new Quaternionf()).normalize();
                    finiteQuaternion(rotation,side+" world hand");
                    observation.add(side + "HandWorldQuaternion", RpgConfig.JSON.toJsonTree(new float[]{rotation.x,rotation.y,rotation.z,rotation.w}));
                    if(frame.local().containsKey(side+"_hand")) {
                        Quaternionf local=frame.local().get(side+"_hand").fsQuaternion().normalize();
                        finiteQuaternion(local,side+" local hand");
                        observation.add(side + "HandLocalQuaternion", RpgConfig.JSON.toJsonTree(new float[]{local.x,local.y,local.z,local.w}));
                        double twistDegrees=Math.toDegrees(2*Math.atan2(local.y,local.w));
                        twistDegrees=((twistDegrees+180)%360+360)%360-180;
                        observation.addProperty(side + "HandLocalTwistYDegrees",twistDegrees);
                    }
                    if(forearm!=null) {
                        Vector3f forearmLongAxis=forearm.transformDirection(new Vector3f(0,-1,0)).normalize();
                        observation.addProperty(side + "WristFlexionDegrees",Math.toDegrees(Math.acos(Math.clamp(handLongAxis.dot(forearmLongAxis),-1,1))));
                    }
                    // Follow the unchanged vanilla model through its calibrated official item carrier.
                    // This is a stock-model reference point, not a substitute weapon mesh.
                    boolean spear = side.equals("right") && BuiltInRegistries.ITEM.getKey(actor.getMainHandItem().getItem()).getPath().endsWith("_spear");
                    Matrix4f carrier = spear ? HeldItemTransform.spearCarrier(hand).mul(spearDisplay()) : (dual ? HeldItemTransform.dualCarrier(hand) : HeldItemTransform.rapierCarrier(hand)).mul(HeldItemTransform.standardDisplay());
                    Vector3f face = carrier.transformDirection(new Vector3f(0,0,1)).normalize();
                    face.set(face.x, -face.y, -face.z);
                    observation.add(side + "CalibratedBladeFaceNormal", vector(face));
                    Vector3f tip = carrier.transformPosition(spear ? new Vector3f(1f/32,31f/32,.5f) : new Vector3f(14.5f/16,14.5f/16,.5f));
                    Vector3f hilt = carrier.transformPosition(spear ? new Vector3f(.81f,.19f,.5f) : new Vector3f(3.5f/16,3.5f/16,.5f));
                    // Undo the FS-to-humanoid conversion to store all trajectories in one centimetre space.
                    tip.set(tip.x * 100, (1.5f - tip.y) * 100, -tip.z * 100);
                    hilt.set(hilt.x * 100, (1.5f - hilt.y) * 100, -hilt.z * 100);
                    observation.add(side + "CalibratedItemTipCm", vector(tip));
                    observation.add(side + "WorldCalibratedItemTipCm", vector(worldBodyPoint(tip,state.bodyRot)));
                    observation.add(side + "HiltCm",vector(hilt));
                    observation.addProperty(side + "HiltPalmErrorCm",hilt.distance(palm));
                    observation.addProperty(side + "HiltBladeAlignmentDot",new Vector3f(tip).sub(hilt).normalize().dot(axis));
                    observation.addProperty(side + "ItemTipReference", spear ? "stock 32px spear upper-left tip through official display/carrier" : "stock 16px handheld sword diagonal tip through official display/carrier");
                    if(side.equals("right")&&org.fsrpg.client.SwordMotion.applies(frame.model())&&frame.world().containsKey("right_item")) {
                        // submitLegacy resolves ItemDisplayContext.NONE and submits the authored
                        // locator, so the older standard 50-degree carrier above is not this rig's
                        // actual weapon. Record the exact production mount's direction separately.
                        Matrix4f locator=frame.world().get("right_item");
                        Matrix4f actual=HeldItemTransform.legacyLocator(locator).mul(HeldItemTransform.legacyRapierItem());
                        Vector3f actualAxis=actual.transformDirection(new Vector3f(1,1,0));
                        actualAxis.set(actualAxis.x,-actualAxis.y,-actualAxis.z).normalize();
                        observation.add("rightActualOfficialBladeAxis",vector(actualAxis));
                        observation.add("rightActualOfficialLocatorCm",vector(locator.getTranslation(new Vector3f())));
                        observation.addProperty("rightOfficialLocatorHandErrorCm",locator.getTranslation(new Vector3f()).distance(hand.getTranslation(new Vector3f())));
                        Vector3f forearmDown=forearm.transformDirection(new Vector3f(0,-1,0)).normalize();
                        observation.addProperty("rightActualBladeWristFlexionDegrees",Math.toDegrees(Math.acos(Math.clamp(actualAxis.dot(forearmDown),-1,1))));
                        observation.addProperty("rightActualOfficialItemPath","right_item / legacyLocator / legacyRapierItem / ItemDisplayContext.NONE");
                    }
                }
            }
            if (frame.world().containsKey("right_hand")) {
                Vector3f palm = frame.world().get("right_hand").transformPosition(new Vector3f(0, -6.25f, 0));
                observation.add("palmCm", RpgConfig.JSON.toJsonTree(new float[]{palm.x, palm.y, palm.z}));
                Vector3f blade = frame.world().get("right_hand").transformDirection(dual ? HeldItemTransform.dualBladeAxis() : spearRig ? new Vector3f(0,0,1) : HeldItemTransform.rapierBladeAxis()).normalize();
                observation.add("bladeAxis", RpgConfig.JSON.toJsonTree(new float[]{blade.x, blade.y, blade.z}));
            }
            if (frame.world().containsKey("body")) {
                Quaternionf bodyWorld=frame.world().get("body").getUnnormalizedRotation(new Quaternionf()).normalize();
                finiteQuaternion(bodyWorld,"world body");
                observation.add("bodyWorldQuaternion",quaternion(bodyWorld));
                if(frame.local().containsKey("body")) {
                    Quaternionf bodyLocal=frame.local().get("body").fsQuaternion().normalize();
                    finiteQuaternion(bodyLocal,"local body");
                    observation.add("bodyLocalQuaternion",quaternion(bodyLocal));
                }
                Vector3f up = frame.world().get("body").transformDirection(new Vector3f(0, 1, 0)).normalize();
                observation.add("torsoUp", RpgConfig.JSON.toJsonTree(new float[]{up.x, up.y, up.z}));
                observation.addProperty("chestY", frame.world().get("body").getTranslation(new Vector3f()).y);
                observation.add("rootCm", vector(frame.world().get("body").getTranslation(new Vector3f())));
                observation.addProperty("torsoTiltDegrees", Math.toDegrees(Math.acos(Math.clamp(up.y, -1, 1))));
                if (frame.world().containsKey("head")) {
                    Vector3f headUp = frame.world().get("head").transformDirection(new Vector3f(0,1,0)).normalize();
                    observation.add("headUp", vector(headUp));
                    observation.addProperty("headTorsoUpDot", headUp.dot(up));
                    observation.addProperty("headTiltDegrees", Math.toDegrees(Math.acos(Math.clamp(headUp.y, -1, 1))));
                    observation.add("headCm", vector(frame.world().get("head").getTranslation(new Vector3f())));
                }
            }
        }
        rig.add("nativeRightLeg", RpgConfig.JSON.toJsonTree(new float[]{model.rightLeg.x, model.rightLeg.y, model.rightLeg.z,
                model.rightLeg.xRot, model.rightLeg.yRot, model.rightLeg.zRot}));
        rig.add("nativeLeftLeg", RpgConfig.JSON.toJsonTree(new float[]{model.leftLeg.x, model.leftLeg.y, model.leftLeg.z,
                model.leftLeg.xRot, model.leftLeg.yRot, model.leftLeg.zRot}));
        observation.addProperty("lowerBodyApplicable",frame != null && actor.onGround() && !actor.isPassenger() && !state.isFallFlying && state.swimAmount <= .5f);
        if(frame != null && frame.supportedLower() != null) {
            var lower = frame.supportedLower();
            JsonObject finalLower = new JsonObject();
            finalLower.addProperty("units", "native-pixels (Y down; groundY=24; 16 pixels/m)");
            finalLower.addProperty("groundY",24);
            finalLower.add("pelvis",vector(new Vector3f((model.rightLeg.x+model.leftLeg.x)*.5f,
                    (model.rightLeg.y+model.leftLeg.y)*.5f,(model.rightLeg.z+model.leftLeg.z)*.5f)));
            Vector3f waist=frame.world().get("body").getTranslation(new Vector3f());
            finalLower.add("waist",vector(new Vector3f(waist.x/6.25f,(150-waist.y)/6.25f,-waist.z/6.25f)));
            finalLower.add("directionXZ",RpgConfig.JSON.toJsonTree(new float[]{lower.directionX(),lower.directionForward()}));
            finalLower.addProperty("phaseRadians",lower.phase());
            finalLower.addProperty("pelvisYawRadians",lower.pelvisYaw());
            finalLower.addProperty("pelvisDropCm",lower.pelvisDropCm());
            float pelvisYaw=frame.model().rig().id().equals("fsrpg.spear")||org.fsrpg.client.SwordMotion.applies(frame.model())?lower.pelvisYaw():Float.NaN;
            finalLower.add("right",finalLeg(model.rightLeg,frame.rightKnee(),lower.right().support(),pelvisYaw));
            finalLower.add("left",finalLeg(model.leftLeg,frame.leftKnee(),lower.left().support(),pelvisYaw));
            observation.add("finalLowerBody",finalLower);
            if(frame.model().rig().id().equals("fsrpg.spear")||org.fsrpg.client.SwordMotion.applies(frame.model())) {
                var torso=new org.fsrpg.client.TorsoDeformation(frame.world().get("body"),lower);
                var sections=new com.google.gson.JsonArray();
                for(float y:new float[]{0,6,8,10,12}) {
                    var vertices=new com.google.gson.JsonArray();
                    for(float x:new float[]{-4,4})for(float z:new float[]{-2,2})vertices.add(vector(torso.point(x,y,z).mul(16)));
                    sections.add(vertices);
                }
                finalLower.add("renderedTorsoSections",sections);
                observation.addProperty("torsoSkinSectionsSubmitted",org.fsrpg.client.SkinnedArms.renderedTorsoSections());
            }
        }
        if(frame!=null&&frame.model().rig().id().equals("fsrpg.spear"))observation.addProperty("completeSpearShaftClear",org.fsrpg.client.SpearAimPose.hasClearShaft(frame.world()));
        observation.add("rig", rig);
        return observation;
    }
    private static JsonObject finalLeg(ModelPart part,float flexion,float support,float pelvisYaw) {
        var leg=new LegDeformation(part.x,part.y,part.z,part.xRot,part.yRot,part.zRot,flexion,true,pelvisYaw);
        var result=new JsonObject();
        result.add("hip",vector(leg.point(0,0,0).mul(16)));
        result.add("knee",vector(leg.point(0,6,0).mul(16)));
        result.add("ankle",vector(leg.point(0,10.5f,0).mul(16)));
        result.add("soleCenter",vector(leg.point(0,12,0).mul(16)));
        var caps=new com.google.gson.JsonArray();
        for(float x:new float[]{-2,2})for(float z:new float[]{-2,2})caps.add(vector(leg.point(x,0,z).mul(16)));
        result.add("hipCapCorners",caps);
        var sole=new com.google.gson.JsonArray();
        var armor=new com.google.gson.JsonArray();
        var knees=new com.google.gson.JsonArray();var ankles=new com.google.gson.JsonArray();
        for(float x:new float[]{-1,1}) for(float z:new float[]{-1,1}) {
            sole.add(vector(leg.point(x*2,12,z*2).mul(16)));
            armor.add(vector(leg.point(x*2.25f,12.25f,z*2.25f).mul(16)));
            knees.add(vector(leg.point(x*2,6,z*2).mul(16)));
            ankles.add(vector(leg.point(x*2,10.5f,z*2).mul(16)));
        }
        result.add("soleCorners",sole);result.add("armorSoleCorners",armor);
        result.add("kneeCorners",knees);result.add("ankleCorners",ankles);
        result.addProperty("support",support);
        return result;
    }
    static boolean stableLowerSupport(JsonObject observation) {
        if (!observation.has("lowerBodyApplicable") || !observation.get("lowerBodyApplicable").getAsBoolean()) return true;
        JsonObject lower=observation.getAsJsonObject("finalLowerBody");if(lower==null)return false;
        boolean grounded=false;
        for(String side:List.of("right","left")) {
            JsonObject leg=lower.getAsJsonObject(side);if(leg==null)return false;
            boolean contact=leg.get("support").getAsFloat()>.5f;
            for(var corner:leg.getAsJsonArray("armorSoleCorners")) {
                double y=corner.getAsJsonArray().get(1).getAsDouble();
                if(!Double.isFinite(y)||y>24.03||(contact&&Math.abs(y-24)>.03))return false;
            }
            grounded|=contact;
        }
        var r=lower.getAsJsonObject("right").getAsJsonArray("hip");var l=lower.getAsJsonObject("left").getAsJsonArray("hip");
        double dx=l.get(0).getAsDouble()-r.get(0).getAsDouble(),dz=l.get(2).getAsDouble()-r.get(2).getAsDouble();
        double yaw=lower.has("pelvisYawRadians")?lower.get("pelvisYawRadians").getAsDouble():0;
        return grounded&&Math.abs(Math.hypot(dx,dz)-3.8)<.03
                &&Math.abs(dx-3.8*Math.cos(yaw))<.03&&Math.abs(dz+3.8*Math.sin(yaw))<.03
                &&Math.abs(l.get(1).getAsDouble()-r.get(1).getAsDouble())<.03
                &&Double.isFinite(dx)&&Double.isFinite(dz);
    }

    private static com.google.gson.JsonArray vector(Vector3f value) {
        return RpgConfig.JSON.toJsonTree(new float[]{value.x,value.y,value.z}).getAsJsonArray();
    }
    private static com.google.gson.JsonArray quaternion(Quaternionf value) {
        return RpgConfig.JSON.toJsonTree(new float[]{value.x,value.y,value.z,value.w}).getAsJsonArray();
    }
    private static void finiteQuaternion(Quaternionf value,String description) {
        if(!Float.isFinite(value.x)||!Float.isFinite(value.y)||!Float.isFinite(value.z)||!Float.isFinite(value.w))
            throw new IllegalStateException("Non-finite final rendered quaternion: "+description);
    }
    /** Dev-only, read-only trace of the exact binding sampled by the real renderer. */
    private static JsonObject bindingObservation(AbstractClientPlayer actor,PlayerAnimations.Frame frame) {
        JsonObject result=new JsonObject();
        try {
            @SuppressWarnings("unchecked") Map<java.util.UUID,Object> bindings=(Map<java.util.UUID,Object>)field(PlayerAnimations.class,null,"bindings");
            Object binding=bindings==null?null:bindings.get(actor.getUUID());if(binding==null)return result;
            RpgAnimationPayload payload=(RpgAnimationPayload)field(binding.getClass(),binding,"state");
            if(payload==null)return result;
            result.addProperty("action",payload.action());result.addProperty("phase",payload.phase());
            result.addProperty("style",payload.style());result.addProperty("model",payload.model());
            result.addProperty("drawn",payload.drawn());result.addProperty("actorAlive",actor.isAlive());
            Object asset=field(binding.getClass(),binding,"asset");
            if(asset!=null)result.addProperty("assetIdentity",System.identityHashCode(asset));
            result.addProperty("phaseRemaining",payload.phaseRemaining());result.addProperty("sequence",payload.sequence());
            result.addProperty("leftSide",payload.leftSide());result.addProperty("rightSide",payload.rightSide());
            result.addProperty("windupTicks",payload.windupTicks());result.addProperty("activeTicks",payload.activeTicks());
            result.addProperty("recoveryTicks",payload.recoveryTicks());
            for(String name:List.of("received","phaseReceived","started","transitionStarted","transitionTicks","sampledElapsedTicks","sampledClipSeconds")) {
                Object value=field(binding.getClass(),binding,name);if(value instanceof Number n)result.addProperty(name,n.doubleValue());
            }
            double received=result.has("phaseReceived")?result.get("phaseReceived").getAsDouble():result.has("received")?result.get("received").getAsDouble():frame.sampledTick();
            double delta=Math.max(0,frame.sampledTick()-received);
            double authoritative=switch(payload.phase()) {
                case "WINDUP" -> payload.windupTicks()-payload.phaseRemaining()+delta;
                case "ACTIVE" -> payload.windupTicks()+payload.activeTicks()-payload.phaseRemaining()+delta;
                case "RECOVERY" -> payload.windupTicks()+payload.activeTicks()+payload.recoveryTicks()-payload.phaseRemaining()+delta;
                default -> -1;
            };
            result.addProperty("receivedDeltaTicks",delta);result.addProperty("authoritativeElapsedTicks",authoritative);
            if(result.has("transitionStarted"))result.addProperty("transitionElapsedTicks",Math.max(0,frame.sampledTick()-result.get("transitionStarted").getAsDouble()));
            var clip=frame.model().rig().clips().get(payload.action());
            if(clip!=null) {
                result.add("clipTags",RpgConfig.JSON.toJsonTree(clip.tags()));
                result.addProperty("rapierPhrase",org.fsrpg.client.RapierMotion.phrase(clip));
                result.addProperty("clipDurationSeconds",clip.duration());
                double begin=clip.events().stream().filter(e->e.name().equals("active_begin")).mapToDouble(e->e.time()).findFirst().orElse(clip.duration()*.35);
                double end=clip.events().stream().filter(e->e.name().equals("active_end")).mapToDouble(e->e.time()).findFirst().orElse(clip.duration()*.55);
                result.addProperty("activeBeginSeconds",begin);result.addProperty("activeEndSeconds",end);
                if(authoritative>=0)result.addProperty("authoritativeClipSeconds",PlayerAnimations.phaseTime(clip,authoritative,payload.windupTicks(),payload.activeTicks(),payload.recoveryTicks()));
            }
            if(payload.aim()!=null)result.add("serverAim",RpgConfig.JSON.toJsonTree(payload.aim()));
            // Inspect the deferred authoritative intent without advancing its clock or release state.
            Object pending=field(binding.getClass(),binding,"pendingAction");
            if(pending!=null) {
                JsonObject intent=new JsonObject();
                Object value=field(pending.getClass(),pending,"value");
                if(value instanceof RpgAnimationPayload next) {
                    intent.addProperty("action",next.action());intent.addProperty("phase",next.phase());
                    intent.addProperty("style",next.style());intent.addProperty("model",next.model());
                    intent.addProperty("phaseRemaining",next.phaseRemaining());intent.addProperty("sequence",next.sequence());
                }
                for(String name:List.of("receivedTick","phaseReceivedTick","activeEnd","endpointTick")) {
                    Object valueNumber=field(pending.getClass(),pending,name);
                    if(valueNumber instanceof Number n)intent.addProperty(name,n.doubleValue());
                }
                Object endpoint=field(pending.getClass(),pending,"endpointPresented");
                if(endpoint instanceof Boolean valueBoolean)intent.addProperty("endpointPresented",valueBoolean);
                result.add("pendingAction",intent);
            }
            for(String name:List.of("lastAim","aimTransitionFrom","aimTransitionStarted","aimTransitionTicks")) {
                Object value=field(binding.getClass(),binding,name);if(value!=null)result.add(name,RpgConfig.JSON.toJsonTree(value));
            }
            for(String name:List.of("lastSpearSourceRear","lastSpearRear","lastSpearSourceChest","lastSpearChest")) {
                Object value=field(binding.getClass(),binding,name);
                if(value instanceof Quaternionf q) {finiteQuaternion(q,name);result.add(name,quaternion(q));}
                else if(value instanceof Vector3f v)result.add(name,vector(v));
            }
            Object motion=field(binding.getClass(),binding,"motion");
            for(String name:List.of("motion","swordLean","swordTurn")) {
                Object persistent=field(binding.getClass(),binding,name);
                if(persistent!=null)result.addProperty(name+"Identity",System.identityHashCode(persistent));
            }
            if(motion!=null) {
                JsonObject sampledMotion=new JsonObject();
                for(String name:List.of("positionTick","velocityX","velocityZ","previousTick","previousGaitTick","gaitRadians","gaitLane","x","forward","run")) {
                    Object value=field(motion.getClass(),motion,name);
                    if(value instanceof Number n&&Double.isFinite(n.doubleValue()))sampledMotion.addProperty(name,n.doubleValue());
                }
                result.add("motion",sampledMotion);
            }
        }catch(ReflectiveOperationException error){throw new IllegalStateException("Cannot inspect actual dev-only render animation binding",error);}
        return result;
    }
    private static Object field(Class<?> type,Object instance,String name) throws ReflectiveOperationException {
        String key=type.getName()+"#"+name;
        if(!fields.containsKey(key)) {
            try {Field found=type.getDeclaredField(name);found.setAccessible(true);fields.put(key,found);}
            catch(NoSuchFieldException optional){fields.put(key,null);}
        }
        Field found=fields.get(key);return found==null?null:found.get(instance);
    }
    private static Matrix4f spearDisplay() {
        float degree = (float)(Math.PI/180);
        return new Matrix4f().translation(0,2f/16,2f/16).rotateXYZ(5*degree,270*degree,-40*degree)
                .scale(1.7f,1.7f,.85f).translate(-.5f,-.5f,-.5f);
    }
    /** Body-relative FS axes rotated into world-facing axes; centimetres relative to the actor's origin. */
    private static Vector3f worldBodyPoint(Vector3f point,float bodyYaw) {
        double yaw=Math.toRadians(bodyYaw),cos=Math.cos(yaw),sin=Math.sin(yaw);
        return new Vector3f((float)(point.x*cos-point.z*sin),point.y,(float)(point.x*sin+point.z*cos));
    }
}
