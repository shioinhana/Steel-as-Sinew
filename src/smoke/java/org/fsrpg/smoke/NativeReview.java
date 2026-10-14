package org.fsrpg.smoke;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.Enchantments;
import org.fsrpg.client.ClientState;
import org.fsrpg.client.PlayerAnimations;
import org.fsrpg.client.OfficialHeldItems;
import org.fsrpg.client.HeldItemTransform;
import org.fsrpg.client.PoseLayers;
import net.minecraft.core.registries.BuiltInRegistries;
import org.fsrpg.network.RpgAnimationPayload;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.fsvoxel.animation.FsvoxAnimation.Pose;
import org.fsvoxel.render.ModelDocument;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

/** Dev-only pose fixtures run through real default/slim player renderers, skin UVs and armor layers. */
final class NativeReview {
    private record View(String label, String action, String phase, int remaining) {}
    private static final List<View> VIEWS = List.of(
            new View("idle", "rapier_idle", "READY", 0), new View("guard", "rapier_guard", "GUARDING", 0),
            new View("charge", "rapier_thrust", "WINDUP", 4), new View("extension", "rapier_thrust", "ACTIVE", 0),
            new View("rise", "rapier_rise", "ACTIVE", 0), new View("down", "rapier_down", "ACTIVE", 0),
            new View("cross-first", "rapier_cross", "ACTIVE", 4), new View("cross-second", "rapier_cross", "ACTIVE", 0),
            new View("light-thrust", "rapier_light_thrust", "ACTIVE", 0), new View("wrist-rise", "rapier_wrist_rise", "ACTIVE", 0),
            new View("heavy-guard", "rapier_guard_heavy", "GUARDING", 0), new View("upright-run", "rapier_run", "READY", 0),
            new View("riposte", "rapier_riposte", "ACTIVE", 0), new View("reprise", "rapier_reprise", "ACTIVE", 0));
    private static final class Fixture extends RemotePlayer {
        PlayerSkin skin;
        Fixture(ClientLevel level, PlayerSkin original) {
            super(level, new GameProfile(UUID.fromString("201f7723-8ee0-4055-a210-010011001100"), "PoseReview"));
            skin = original; setId(200001); setNoGravity(true);
        }
        @Override public PlayerSkin getSkin() { return skin; }
    }
    private Fixture actor;
    private int elapsed, images, posePairs;
    private int movingImages, motionLegChecks;
    private int ambientImages, ambientChecks;
    private float smallestChest, largestChest;
    private float smallestLeg = Float.POSITIVE_INFINITY, largestLeg = Float.NEGATIVE_INFINITY;
    private long precedingItemSubmissions, precedingFoilSubmissions, precedingOffhandSubmissions, precedingAttachedOffhand;
    private final Set<String> officialItems = new LinkedHashSet<>();
    private final Map<String,Map<String,Matrix4f>> precedingViews = new HashMap<>();
    private final Consumer<String> capture;
    private final RenderFrameCapture movies;
    private Supplier<JsonObject> movieSampler;
    private int motionStage = -1;
    private ModelDocument fixtureModel;
    private Map<String, Pose> fixturePose;
    NativeReview(Consumer<String> capture, RenderFrameCapture movies) {
        this.capture = capture; this.movies = movies;
        // The same detached preview route used by the timing editor renders the static
        // fixture through the real skin, armor and ItemStack layers. No actor clock is altered.
        NeoForge.EVENT_BUS.addListener((RenderPlayerEvent.Pre<?> event) -> {
            if (actor != null && event.getRenderState().id == actor.getId()) applyFixturePose(event.getRenderState());
        });
    }
    int images() { return images; }
    int posePairs() { return posePairs; }
    int movingImages() { return movingImages; }
    int motionLegChecks() { return motionLegChecks; }
    int ambientImages() { return ambientImages; }
    int ambientChecks() { return ambientChecks; }
    Set<String> officialItems() { return Set.copyOf(officialItems); }
    private void freezeFixturePose(View view, int windup, int active, int recovery) {
        if (fixtureModel == null) {
            AvatarRenderState state = new AvatarRenderState(); state.id = actor.getId(); state.skin = actor.skin;
            fixtureModel = PlayerAnimations.frame(state).model();
        }
        double ticks = PoseLayers.attackElapsed(view.phase, view.remaining, windup, active, recovery);
        var clip = fixtureModel.rig().clips().get(view.action);
        double seconds = ticks < 0 ? 0 : PoseLayers.phaseTime(clip, ticks, windup, active, recovery);
        fixturePose = new PoseLayers.Playback(fixtureModel.rig(), view.action)
                .sample(seconds, 0, 0, 0, 0, .25f, 55).pose();
    }
    private void applyFixturePose(AvatarRenderState state) {
        if (fixturePose == null) PlayerAnimations.clearPreview(state);
        else PlayerAnimations.preview(state, fixtureModel, fixturePose);
    }
    private void verifyHeading() {
        PlayerAnimations.accept(new RpgAnimationPayload(actor.getUUID(), "rapier", "rapier.fsvoxel", "rapier_idle", "READY", 0,0,0,0,999,true));
        for (var model : new PlayerModelType[]{PlayerModelType.WIDE,PlayerModelType.SLIM})
            for(float headYaw:new float[]{0,90,180,-90})for(float difference:new float[]{0,35,-35}) {
                AvatarRenderState state=new AvatarRenderState();state.id=actor.getId();
                var skin=actor.skin;state.skin=new PlayerSkin(skin.body(),null,null,model,false);
                state.bodyRot=headYaw-difference;state.yRot=difference;
                var frame=PlayerAnimations.frame(state);
                // The official default sword's sprite axis and display transform are the ground truth;
                // the unrendered FS authoring blade no longer proves the held item's direction.
                Vector3f actual=new Matrix4f(HeldItemTransform.rapierCarrier(frame.world().get("right_hand")))
                        .mul(HeldItemTransform.standardDisplay()).transformDirection(new Vector3f(1,1,0)).normalize();
                new Matrix4f().rotateY((float)Math.toRadians(180-state.bodyRot)).scale(-1,-1,1).transformDirection(actual).normalize();
                Vector3f expected=new Vector3f(-(float)Math.sin(Math.toRadians(headYaw)),0,(float)Math.cos(Math.toRadians(headYaw)));
                RpgSmoke.check(actual.distance(expected)<.01f,"official default sword cardinal yaw "+headYaw+" body delta "+difference+" model "+model);
            }
    }
    boolean tick() throws IOException {
        Minecraft game = Minecraft.getInstance();
        int step = elapsed / 10, total = 4 * VIEWS.size() * 2;
        if (step >= total) return movingReview(elapsed++ - total * 10);
        if (actor == null) { actor = new Fixture(game.level, game.player.getSkin()); game.level.addEntity(actor); verifyHeading(); }
        int profile = step / (VIEWS.size() * 2), poseIndex = step / 2 % VIEWS.size(); boolean back = step % 2 == 1;
        View view = VIEWS.get(poseIndex); boolean slim = profile % 2 == 1, armor = profile >= 2;
        var original = game.player.getSkin();
        actor.skin = new PlayerSkin(original.body(), null, null, slim ? PlayerModelType.SLIM : PlayerModelType.WIDE, false);
        actor.setPos(.5, 80, .5); actor.xo = .5; actor.yo = 80; actor.zo = .5;
        actor.setYRot(0); actor.yRotO = 0; actor.yBodyRot = actor.yBodyRotO = actor.yHeadRot = actor.yHeadRotO = 0;
        if (elapsed % 10 == 0) {
            precedingItemSubmissions = OfficialHeldItems.submissions(); precedingFoilSubmissions = OfficialHeldItems.foilSubmissions();
            precedingOffhandSubmissions = OfficialHeldItems.offhandSubmissions();
            precedingAttachedOffhand = OfficialHeldItems.offhandAttachedSubmissions();
            ItemStack held = new ItemStack(profile == 1 ? Items.IRON_SWORD : profile == 3 ? Items.TRIDENT : Items.DIAMOND_SWORD);
            if (profile == 2) held.enchant(game.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING), 1);
            actor.setItemSlot(EquipmentSlot.MAINHAND, held);
            actor.setItemSlot(EquipmentSlot.OFFHAND, profile > 0 ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY);
            actor.setItemSlot(EquipmentSlot.HEAD, armor ? new ItemStack(Items.DIAMOND_HELMET) : ItemStack.EMPTY);
            actor.setItemSlot(EquipmentSlot.CHEST, armor ? new ItemStack(Items.DIAMOND_CHESTPLATE) : ItemStack.EMPTY);
            actor.setItemSlot(EquipmentSlot.LEGS, armor ? new ItemStack(Items.DIAMOND_LEGGINGS) : ItemStack.EMPTY);
            actor.setItemSlot(EquipmentSlot.FEET, armor ? new ItemStack(Items.DIAMOND_BOOTS) : ItemStack.EMPTY);
            game.options.setCameraType(CameraType.FIRST_PERSON); game.options.fov().set(50); game.options.hideGui = true;
            RpgSmoke.server(p -> { p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR); p.connection.teleport(3, 80, back ? -4 : 5, back ? 29.05f : 150.95f, 7); });
        }
        double cameraZ=back?-4:5;float cameraYaw=back?29.05f:150.95f;
        game.player.setPos(3,80,cameraZ);game.player.xo=3;game.player.yo=80;game.player.zo=cameraZ;
        game.player.setYRot(cameraYaw);game.player.yRotO=cameraYaw;game.player.setXRot(7);game.player.xRotO=7;
        int windup = 10, active = 6, recovery = 10;
        for (var skill : ClientState.skills()) if (ClientState.text(skill, "action", "").equals(view.action)) {
            windup = (int) ClientState.number(skill, "windupTicks", 10); active = (int) ClientState.number(skill, "activeTicks", 6);
            recovery = (int) ClientState.number(skill, "recoveryTicks", 10); break;
        }
        PlayerAnimations.accept(new RpgAnimationPayload(actor.getUUID(), "rapier", "rapier.fsvoxel", view.action, view.phase,
                view.remaining, windup, active, recovery, step + 1000, true));
        freezeFixturePose(view, windup, active, recovery);
        if (elapsed % 10 == 8) {
            String actualItem = BuiltInRegistries.ITEM.getKey(actor.getMainHandItem().getItem()).toString();
            RpgSmoke.check(OfficialHeldItems.submissions() > precedingItemSubmissions && OfficialHeldItems.lastItemId().equals(actualItem),
                    "actual mainhand official ItemStack pipeline profile=" + profile + " item=" + actualItem + " pose=" + view.label + " back=" + back);
            officialItems.add(actualItem);
            if (profile == 2) RpgSmoke.check(OfficialHeldItems.foilSubmissions() > precedingFoilSubmissions && OfficialHeldItems.lastFoil(),
                    "resolved official enchantment glint profile=2 pose=" + view.label + " back=" + back);
            if (profile > 0) RpgSmoke.check(OfficialHeldItems.offhandSubmissions() > precedingOffhandSubmissions
                    && OfficialHeldItems.offhandAttachedSubmissions() > precedingAttachedOffhand && OfficialHeldItems.lastOffhandItemId().equals("minecraft:shield"),
                    "offhand shield retains official state and follows left wrist profile=" + profile + " pose=" + view.label + " back=" + back);
            AvatarRenderState state=new AvatarRenderState();state.id=actor.getId();state.skin=actor.skin;
            applyFixturePose(state);
            var frame=PlayerAnimations.frame(state);String key=profile+":"+poseIndex;
            if(!back) {
                Map<String,Matrix4f> copy=new HashMap<>();frame.world().forEach((id,matrix)->copy.put(id,new Matrix4f(matrix)));precedingViews.put(key,copy);
            } else {
                var previous=precedingViews.get(key);boolean same=previous!=null;
                for(var id:List.of("body","right_arm","right_forearm","right_hand","left_hand","rapier"))
                    same&=previous!=null&&frame.world().get(id).equals(previous.get(id),.0001f);
                RpgSmoke.check(same,"same frozen native joints both views profile="+profile+" pose="+view.label);
                posePairs++;
            }
            capture.accept("review/" + (slim ? "slim" : "stock") + (armor ? "-armor" : "-skin") + "-" + view.label + (back ? "-back" : "-side")); images++;
        }
        elapsed++; return false;
    }

    private boolean movingReview(int motionTick) throws IOException {
        Minecraft game = Minecraft.getInstance();
        fixturePose = null;
        // The two movies are independent dev fixtures, following the real server/network checks.
        // Their animation clocks advance normally; neither the fixture pose nor its leg gait is held static.
        int scene = motionTick < 90 ? 0 : 1, sceneTick = scene == 0 ? motionTick : motionTick - 90;
        if (motionTick >= 160) {
            return ambientReview(motionTick - 160);
        }
        if (sceneTick == 0) {
            var original = game.player.getSkin();
            actor.skin = new PlayerSkin(original.body(), null, null, PlayerModelType.WIDE, false);
            actor.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(scene == 0 ? Items.IRON_SWORD : Items.DIAMOND_SWORD));
            actor.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) actor.setItemSlot(slot, ItemStack.EMPTY);
            game.options.setCameraType(CameraType.FIRST_PERSON); game.options.hideGui = true;
            RpgSmoke.server(p -> { p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR); p.connection.teleport(3, 80, 5, 150.95f, 7); });
            PlayerAnimations.accept(new RpgAnimationPayload(actor.getUUID(), "rapier", "rapier.fsvoxel", "rapier_idle", "READY", 0, 0, 0, 0, 10000 + scene, false));
            PlayerAnimations.accept(new RpgAnimationPayload(actor.getUUID(), "rapier", "rapier.fsvoxel", "rapier_idle", "READY", 0, 0, 0, 0, 11000 + scene, true));
            smallestLeg = Float.POSITIVE_INFINITY; largestLeg = Float.NEGATIVE_INFINITY;
            movieSampler = null; motionStage = -1;
        }
        double previousX = actor.getX(), previousZ = actor.getZ();
        actor.setPos(.5 + Math.sin(motionTick * .10) * .85, 80, .5 + Math.cos(motionTick * .10) * .35);
        actor.xo = actor.xOld = previousX; actor.yo = actor.yOld = 80; actor.zo = actor.zOld = previousZ;
        actor.setYRot(0); actor.yRotO = actor.yBodyRot = actor.yBodyRotO = actor.yHeadRot = actor.yHeadRotO = 0;
        actor.walkAnimation.update(.65f, .4f, 1);
        actor.setOnGround(true); actor.setSprinting(false);
        game.player.setPos(3, 80, 5); game.player.xo = game.player.xOld = 3;
        game.player.yo = game.player.yOld = 80; game.player.zo = game.player.zOld = 5;
        game.player.setYRot(150.95f); game.player.yRotO = 150.95f; game.player.setXRot(7); game.player.xRotO = 7;
        if (scene == 0 && sceneTick >= 10) upperStage(sceneTick - 10);
        if (sceneTick == 10) {
            movieSampler = RenderObservations.sampler(actor);
            if (scene == 0) {
                movies.start("moving-upper-combo", "dev-fixture; five actual upper clips at configured phase clocks with native gait", this::motionObservation);
            } else {
                var skill = ClientState.skills().stream().filter(value -> ClientState.text(value, "id", "").equals("sword_thrust")).findFirst().orElseThrow();
                int windup = (int) ClientState.number(skill, "windupTicks", 10), active = (int) ClientState.number(skill, "activeTicks", 3), recovery = (int) ClientState.number(skill, "recoveryTicks", 14);
                PlayerAnimations.accept(new RpgAnimationPayload(actor.getUUID(), "rapier", "rapier.fsvoxel", "rapier_thrust", "WINDUP", windup, windup, active, recovery, 13000, true));
                movies.start("moving-full-thrust", "dev-fixture; live combined thrust and preserved gait", this::motionObservation);
            }
        }
        if (sceneTick == 25 || sceneTick == 45) {
            var observation = recordLeg(RenderObservations.sample(actor));
            RpgSmoke.check(RenderObservations.stableLowerSupport(observation),"final skin hips share pelvis and a complete boot sole supports moving attack scene="+scene);
            RpgSmoke.check(observation.get("walkAnimationSpeed").getAsFloat() > .05f, "moving review has actual vanilla gait scene=" + scene);
            if (scene == 0) RpgSmoke.check(observation.get("lowerBodyWeight").getAsFloat() < .01f, "moving upper attack retains directional locomotion scene=0");
            else RpgSmoke.check(observation.get("lowerBodyWeight").getAsFloat() < .4f, "moving combined thrust preserves directional locomotion contribution scene=1");
            capture.accept("moving/" + (scene == 0 ? "upper-combo" : "full-thrust") + "-" + sceneTick); movingImages++;
        }
        if (sceneTick == (scene == 0 ? 89 : 69)) {
            RpgSmoke.check(largestLeg - smallestLeg > .2f, "actual rendered leg changes across moving attack scene=" + scene);
            motionLegChecks++; movies.stop();
        }
        return false;
    }

    private JsonObject motionObservation() {
        return recordLeg(movieSampler.get());
    }

    private boolean ambientReview(int time) throws IOException {
        var game = Minecraft.getInstance();
        if (time >= 250) {
            game.level.removeEntity(actor.getId(), Entity.RemovalReason.DISCARDED); return true;
        }
        boolean running = time < 140;
        int t = running ? time : time - 140;
        if (t == 0) {
            movies.stop();
            PlayerAnimations.accept(new RpgAnimationPayload(actor.getUUID(), "rapier", "rapier.fsvoxel", "rapier_idle", "READY", 0,0,0,0,20000,false));
            PlayerAnimations.accept(new RpgAnimationPayload(actor.getUUID(), "rapier", "rapier.fsvoxel", "rapier_idle", "READY", 0,0,0,0,20001,true));
            actor.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            smallestChest = Float.POSITIVE_INFINITY; largestChest = Float.NEGATIVE_INFINITY;
        }
        if (t > 0 && t % 40 == 0)
            PlayerAnimations.accept(new RpgAnimationPayload(actor.getUUID(), "rapier", "rapier.fsvoxel", "rapier_idle", "READY", 0,0,0,0,20001+t,true));
        double oldX = actor.getX(), oldZ = actor.getZ();
        actor.setPos(running ? .5 + Math.sin(t * .16) * 1.2 : .5, 80, running ? .5 + Math.cos(t * .16) * .9 : .5);
        actor.xo = actor.xOld = running ? oldX : .5; actor.yo = actor.yOld = 80; actor.zo = actor.zOld = running ? oldZ : .5;
        actor.setYRot(0); actor.yRotO = actor.yBodyRot = actor.yBodyRotO = actor.yHeadRot = actor.yHeadRotO = 0;
        actor.setOnGround(true); actor.setSprinting(running);
        actor.walkAnimation.update(running ? .8f : 0, .4f, 1);
        game.player.setPos(3, 80, 5); game.player.xo = game.player.xOld = 3; game.player.yo = game.player.yOld = 80; game.player.zo = game.player.zOld = 5;
        game.player.setYRot(150.95f); game.player.yRotO = 150.95f; game.player.setXRot(7); game.player.xRotO = 7;
        if (t == 10) {
            movieSampler = RenderObservations.sampler(actor);
            movies.start(running ? "sprint-directional-lean" : "idle-breathing", running
                    ? "dev-fixture; live sprint flag and changing movement direction, upright official sword"
                    : "dev-fixture; uninterrupted four-second breathing loop through official skin renderer", () -> {
                var sample = movieSampler.get();
                float chest = sample.get("chestY").getAsFloat();
                smallestChest = Math.min(smallestChest, chest); largestChest = Math.max(largestChest, chest);
                return sample;
            });
        }
        if (running && (t == 30 || t == 60 || t == 90 || t == 120)) {
            var sample = RenderObservations.sample(actor);
            RpgSmoke.check(sample.getAsJsonArray("bladeAxis").get(1).getAsFloat() > .93f, "sprinting carries actual sword upright t="+t);
            RpgSmoke.check(sample.get("lowerBodyWeight").getAsFloat() < .01f, "sprint carry retains native gait t="+t);
            var up = sample.getAsJsonArray("torsoUp");
            double dx = actor.getX() - actor.xo, dz = actor.getZ() - actor.zo;
            RpgSmoke.check(up.get(0).getAsDouble()*dx + up.get(2).getAsDouble()*dz > .002, "torso leans toward current movement t="+t);
            capture.accept("moving/sprint-direction-"+t); ambientImages++; ambientChecks += 3;
        }
        if (!running && (t == 20 || t == 60)) { capture.accept("moving/breath-"+t); ambientImages++; }
        if (t == (running ? 139 : 109)) {
            if (!running) {
                RpgSmoke.check(largestChest-smallestChest > 2.5f && largestChest-smallestChest < 3.1f, "live idle breath gently raises and lowers torso while feet remain planted");
                ambientChecks++;
            }
            movies.stop();
        }
        return false;
    }

    private JsonObject recordLeg(JsonObject result) {
        float leg = result.getAsJsonObject("rig").getAsJsonArray("nativeRightLeg").get(3).getAsFloat();
        smallestLeg = Math.min(smallestLeg, leg); largestLeg = Math.max(largestLeg, leg);
        return result;
    }

    private void upperStage(int ticks) {
        var style = ClientState.objects(ClientState.snapshot(), "styles").stream()
                .filter(value -> ClientState.text(value, "id", "").equals("rapier")).findFirst().orElseThrow();
        var stages = style.getAsJsonArray("leftCombo");
        int offset = 0;
        for (int stage = 0; stage < stages.size(); stage++) {
            var definition = stages.get(stage).getAsJsonObject();
            String id = ClientState.text(definition, "skillId", "");
            var skill = ClientState.skills().stream().filter(value -> ClientState.text(value, "id", "").equals(id)).findFirst().orElseThrow();
            int windup = (int) ClientState.number(skill, "windupTicks", 4), active = (int) ClientState.number(skill, "activeTicks", 3), recovery = (int) ClientState.number(skill, "recoveryTicks", 9);
            int chain = windup + active + (stage == stages.size()-1 ? recovery : 0);
            if (ticks < offset + chain || stage == stages.size() - 1) {
                if (stage != motionStage) {
                    String action = ClientState.text(skill, "action", "");
                    PlayerAnimations.accept(new RpgAnimationPayload(actor.getUUID(), "rapier", "rapier.fsvoxel", action, "WINDUP",
                            windup, windup, active, recovery, 12000 + stage, true));
                    motionStage = stage;
                }
                return;
            }
            offset += chain;
        }
    }
}
