package org.fsrpg.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import org.fsrpg.ImportedItems;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Native skin arms and official ItemStack states for the ten imported objects. */
public final class ImportedItemPresentation {
    private static final ImportedItemMotion.State MAIN = new ImportedItemMotion.State();
    private static final ImportedItemMotion.State OFF = new ImportedItemMotion.State();
    private static final ImportedItemMotion.HeldPose MAIN_HELD = new ImportedItemMotion.HeldPose();
    private static final ImportedItemMotion.HeldPose OFF_HELD = new ImportedItemMotion.HeldPose();
    private record VisualKey(String itemId, Object components) {}
    private static ClientLevel level;
    private static LocalPlayer player;
    private static long armSubmissions, itemSubmissions, mainHandSubmissions, offHandSubmissions;
    private static long outgoingItemSubmissions;
    private static boolean lastOutgoing;
    private static double lastItemScale, lastArmRaise;
    private static boolean nativeArmSleeveVisible;
    private static String nativeArmSkin = "", nativeArmModel = "", lastItemId = "", lastArm = "";
    private static final Vector3f lastPouchPalmSupport = new Vector3f(), lastPouchBase = new Vector3f(),
            lastPouchCenter = new Vector3f(), lastPouchUp = new Vector3f();
    private static String lastPouchArm = "";

    /** Exact registry objects, not a broad namespace or name-prefix override. */
    public static boolean supports(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return ImportedItems.ITEMS.stream().anyMatch(item -> stack.is(item.get()));
    }

    static void tick(ClientTickEvent.Post event) { synchronize(); }

    private static void synchronize() {
        var game = Minecraft.getInstance();
        if (level != game.level || player != game.player) {
            reset(); level = game.level; player = game.player;
        }
        if (player == null || level == null) return;
        select(MAIN, player.getMainHandItem(), player.getInventory().getSelectedSlot(), player.getMainArm());
        select(OFF, player.getOffhandItem(), -1, player.getMainArm().getOpposite());
    }

    private static void select(ImportedItemMotion.State state, ItemStack stack, int slot, HumanoidArm arm) {
        if (!ClientOptions.IMPORTED_ITEM_HANDS.get() || !supports(stack)) {
            state.select(null, ImportedItemMotion.Kind.NONE);
            return;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        state.select(new ImportedItemMotion.EquipKey(id, slot, stack.getComponentsPatch(), arm == HumanoidArm.LEFT), kind(id));
    }

    private static ImportedItemMotion.Kind kind(String id) {
        if (id.equals("fsrpg:money_pouch")) return ImportedItemMotion.Kind.POUCH;
        return id.endsWith("_coin") ? ImportedItemMotion.Kind.COIN : ImportedItemMotion.Kind.BADGE;
    }

    /** Called at LOWEST priority. Another renderer's canceled hand stays theirs. */
    public static void onRenderHand(RenderHandEvent event) {
        if (event.isCanceled()) return;
        var game = Minecraft.getInstance();
        synchronize();
        if (player == null || level == null || !ClientOptions.IMPORTED_ITEM_HANDS.get()
                || !game.options.getCameraType().isFirstPerson() || game.getCameraEntity() != player
                || player.isSpectator() || player.isScoping() || !supports(event.getItemStack())) return;
        // A drawn legacy weapon renders one complete authored arm pair. Its
        // imported offhand must not add another arm on top of that existing rig.
        var intent = PlayerAnimations.localIntent();
        if (event.getHand() == InteractionHand.OFF_HAND && !supports(player.getMainHandItem()) && ClientState.drawn() && intent != null
                && intent.model().startsWith("legacy_")) return;
        var hand = event.getHand();
        ItemStack stack = event.getItemStack();
        // Vanilla keeps its outgoing cached stack during the lower phase. It
        // still needs the same native arm and mount; falling back here makes
        // the old item jump into its bare first-person JSON layout for 2 ticks.
        boolean outgoing = !ItemStack.isSameItemSameComponents(stack, player.getItemInHand(hand));
        boolean main = hand == InteractionHand.MAIN_HAND;
        var arm = main ? player.getMainArm() : player.getMainArm().getOpposite();
        boolean left = arm == HumanoidArm.LEFT;
        int side = left ? -1 : 1;
        var state = main ? MAIN : OFF;
        var held = main ? MAIN_HELD : OFF_HELD;
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        var renderedKind = kind(itemId);
        var visualKey = new VisualKey(itemId, stack.getComponentsPatch());
        var context = left ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        var item = new ItemStackRenderState();
        game.getItemModelResolver().updateForTopItem(item, stack, context, level, player, player.getId() + context.ordinal());
        if (item.isEmpty()) return;
        var bounds = item.getModelBoundingBox();
        if (!Double.isFinite(bounds.minX) || !Double.isFinite(bounds.minY) || !Double.isFinite(bounds.minZ)
                || !Double.isFinite(bounds.maxX) || !Double.isFinite(bounds.maxY) || !Double.isFinite(bounds.maxZ)
                || bounds.getYsize() <= 0) return;
        double seconds = PlayerAnimations.presentationTick() / 20;
        // Do not sample/consume the pending incoming one shot while drawing
        // the old stack. Freeze its last relative pose and let vanilla's equip
        // lowering retract both the arm and object smoothly together.
        var motion = outgoing ? held.outgoing(visualKey, renderedKind)
                : state.sample(seconds, event.getEquipProgress() <= .35f,
                        ClientOptions.IMPORTED_ITEM_EQUIP_ANIMATIONS.get(), settings());
        if (!outgoing) held.remember(visualKey, motion);
        boolean pouch = renderedKind == ImportedItemMotion.Kind.POUCH;
        float scale = (float) (pouch ? safe(ClientOptions.IMPORTED_POUCH_SCALE.get(), 1.02, .7, 1.3)
                : safe(ClientOptions.IMPORTED_ITEM_SCALE.get(), .65, .4, .85));
        double armRaise = pouch ? safe(ClientOptions.IMPORTED_POUCH_RAISE.get(), .14, 0, .3) : 0;
        boolean slim = player.getSkin().model().name().equals("SLIM");
        var renderer = game.getEntityRenderDispatcher().getPlayerRenderer(player);
        var skin = player.getSkin().body().texturePath();
        var poses = event.getPoseStack();
        event.setCanceled(true);
        poses.pushPose();
        try {
            // The same displacement drives arm and attached object; only a
            // coin's deliberate flight separates it from the contact point.
            double reveal = pouch ? 0 : 1 - motion.drawProgress();
            // Supporting the bottom puts the entire pouch above the hand.
            // Keep the existing configurable raise as an extra adjustment to
            // this lower support baseline rather than moving the bag sideways.
            poses.translate(0, armRaise - (pouch ? .14 : 0) + motion.armLift() - .10 * reveal, .04 * reveal);
            Matrix4f armMatrix = nativeArm(side, event.getEquipProgress(), event.getSwingProgress());
            float armTilt = radians((float) (side * motion.armTiltDegrees()));
            if (pouch) {
                armMatrix = supportingArm(armMatrix, side, slim);
                Vector3f support = fingertipCenter(side, slim);
                // Roll the wrist about the support itself. Rotating about the
                // arm model origin would translate the bag and reverse the
                // deliberate load/lift/catch height curve after leveling it.
                armMatrix.translate(support).rotateZ(armTilt).translate(-support.x, -support.y, -support.z);
            } else armMatrix.rotateZ(armTilt);
            if (!player.isInvisible()) {
                poses.pushPose();
                try {
                    poses.mulPose(armMatrix);
                    if (left) renderer.renderLeftHand(poses, event.getSubmitNodeCollector(), event.getPackedLight(), skin,
                            player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE), player);
                    else renderer.renderRightHand(poses, event.getSubmitNodeCollector(), event.getPackedLight(), skin,
                            player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE), player);
                } finally { poses.popPose(); }
                armSubmissions++;
                nativeArmSkin = skin.toString(); nativeArmModel = player.getSkin().model().name();
                nativeArmSleeveVisible = player.isModelPartShown(left ? PlayerModelPart.LEFT_SLEEVE : PlayerModelPart.RIGHT_SLEEVE);
            }
            // The pouch rests on the native fist's horizontal end face. Coins
            // and badges retain their established inner fingertip contact.
            Matrix4f palm = palm(armMatrix, side, slim, pouch);
            Matrix4f idleArm = nativeArm(side, 0, 0);
            if (pouch) idleArm = supportingArm(idleArm, side, slim);
            Quaternionf upright = palm(idleArm, side, slim, pouch).getNormalizedRotation(new Quaternionf()).conjugate();
            poses.pushPose();
            try {
                poses.mulPose(palm);
                if (pouch) poses.last().pose().transformPosition(0, 0, 0, lastPouchPalmSupport);
                poses.mulPose(upright);
                double gripY = switch (renderedKind) {
                    case BADGE -> bounds.minY + bounds.getYsize() * .2; // pinch the lower edge
                    default -> bounds.minY; // pouch base and landing coin sit on the palm
                };
                double centerX = (bounds.minX + bounds.maxX) / 2;
                double centerY = (bounds.minY + bounds.maxY) / 2;
                double centerZ = (bounds.minZ + bounds.maxZ) / 2;
                // Toss about the object's center of mass, not its contact edge;
                // otherwise a half-flipped coin sweeps back through the hand.
                poses.translate(0, motion.itemLift() + (centerY - gripY) * scale, 0);
                poses.mulPose(new Quaternionf().rotationX((float) Math.toRadians(motion.itemSpinDegrees())));
                poses.scale(scale, scale, scale);
                poses.translate(-centerX, -centerY, -centerZ);
                if (pouch) {
                    // Sample the exact matrix submitted below, independently
                    // from the native skin support point sampled above.
                    var submitted = poses.last().pose();
                    submitted.transformPosition((float) centerX, (float) bounds.minY, (float) centerZ, lastPouchBase);
                    submitted.transformPosition((float) centerX, (float) centerY, (float) centerZ, lastPouchCenter);
                    submitted.transformDirection(0, 1, 0, lastPouchUp).normalize();
                    lastPouchArm = arm.name();
                }
                item.submit(poses, event.getSubmitNodeCollector(), event.getPackedLight(), OverlayTexture.NO_OVERLAY, 0);
            } finally { poses.popPose(); }
            itemSubmissions++;
            if (outgoing) outgoingItemSubmissions++;
            lastOutgoing = outgoing;
            lastItemScale = scale; lastArmRaise = armRaise;
            if (main) mainHandSubmissions++; else offHandSubmissions++;
            lastItemId = itemId; lastArm = arm.name();
        } finally { poses.popPose(); }
    }

    /** Exact vanilla renderPlayerArm pose, including equip lowering and swing. */
    private static Matrix4f nativeArm(int side, float equip, float swing) {
        float root = (float) Math.sqrt(Math.max(0, swing));
        float wave = (float) Math.sin(root * Math.PI);
        return new Matrix4f()
                .translate(side * (.64000005f - .3f * wave), -.6f - .6f * equip + .4f * (float) Math.sin(root * Math.PI * 2), -.71999997f - .4f * (float) Math.sin(swing * Math.PI))
                .rotateY(radians(side * 45))
                .rotateY(radians(side * wave * 70))
                .rotateZ(radians(side * -20 * (float) Math.sin(swing * swing * Math.PI)))
                .translate(-side, 3.6f, 3.5f)
                .rotateZ(radians(side * 120)).rotateX(radians(200)).rotateY(radians(side * -135))
                .translate(side * 5.6f, 0, 0);
    }

    private static Matrix4f skinArmBasis(int side) {
        return new Matrix4f().translate(-side * 5f / 16, 2f / 16, 0).rotateZ(side * .1f);
    }
    private static Vector3f fingertipCenter(int side, boolean slim) {
        return skinArmBasis(side).transformPosition(-side * (slim ? .5f : 1f) / 16, 10f / 16, 0, new Vector3f());
    }
    private static Matrix4f supportingArm(Matrix4f arm, int side, boolean slim) {
        Matrix4f skin = skinArmBasis(side);
        Vector3f tip = fingertipCenter(side, slim);
        Vector3f nativeNormal = skin.transformDirection(0, 1, 0, new Vector3f()).normalize();
        Vector3f localUp = nativeArm(side, 0, 0).getNormalizedRotation(new Quaternionf()).conjugate()
                .transform(new Vector3f(0, 1, 0));
        // Reorient the actual skin arm about its fingertip end-face center;
        // its four real vertices now form the horizontal support under the bag.
        Quaternionf levelPalm = new Quaternionf().rotationTo(nativeNormal, localUp);
        return new Matrix4f(arm).translate(tip).rotate(levelPalm).translate(-tip.x, -tip.y, -tip.z);
    }
    private static Matrix4f palm(Matrix4f arm, int side, boolean slim, boolean pouch) {
        return new Matrix4f(arm).mul(skinArmBasis(side))
                .translate(-side * (slim ? .5f : 1f) / 16, (pouch ? 10f : 9f) / 16, pouch ? 0 : -2f / 16);
    }
    private static float radians(float degrees) { return (float) Math.toRadians(degrees); }
    private static double safe(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
    private static ImportedItemMotion.Settings settings() {
        return new ImportedItemMotion.Settings(
                safe(ClientOptions.IMPORTED_POUCH_SECONDS.get(), 1.0, .5, 2),
                safe(ClientOptions.IMPORTED_COIN_SECONDS.get(), 1.05, .45, 2),
                safe(ClientOptions.IMPORTED_BADGE_SECONDS.get(), .35, .15, 1),
                safe(ClientOptions.IMPORTED_COIN_TOSS_HEIGHT.get(), .18, .04, .3),
                safe(ClientOptions.IMPORTED_POUCH_HEFT_HEIGHT.get(), .10, 0, .18));
    }

    public static JsonObject diagnostics() {
        var out = new JsonObject();
        out.addProperty("armSubmissions", armSubmissions); out.addProperty("itemSubmissions", itemSubmissions);
        out.addProperty("mainHandSubmissions", mainHandSubmissions); out.addProperty("offHandSubmissions", offHandSubmissions);
        out.addProperty("outgoingItemSubmissions", outgoingItemSubmissions); out.addProperty("lastOutgoing", lastOutgoing);
        out.addProperty("lastItemScale", lastItemScale); out.addProperty("lastArmRaise", lastArmRaise);
        out.addProperty("nativeArmSkin", nativeArmSkin); out.addProperty("nativeArmModel", nativeArmModel);
        out.addProperty("nativeArmSleeveVisible", nativeArmSleeveVisible);
        out.addProperty("lastItemId", lastItemId); out.addProperty("lastArm", lastArm);
        out.add("lastPouchPalmSupport", vector(lastPouchPalmSupport)); out.add("lastPouchBase", vector(lastPouchBase));
        out.add("lastPouchCenter", vector(lastPouchCenter)); out.add("lastPouchUp", vector(lastPouchUp));
        out.addProperty("lastPouchContactDistance", lastPouchBase.distance(lastPouchPalmSupport));
        out.addProperty("lastPouchSideOffset", lastPouchBase.x - lastPouchPalmSupport.x);
        out.addProperty("lastPouchArm", lastPouchArm);
        out.addProperty("lastPouchSupportMode", lastPouchArm.isEmpty() ? "" : "base_on_palm");
        out.add("mainHand", diagnostic(MAIN)); out.add("offHand", diagnostic(OFF));
        return out;
    }
    private static JsonObject vector(Vector3f value) {
        var out = new JsonObject();
        out.addProperty("x", value.x); out.addProperty("y", value.y); out.addProperty("z", value.z);
        return out;
    }
    private static JsonObject diagnostic(ImportedItemMotion.State state) {
        var out = new JsonObject(); var key = state.key(); var motion = state.last();
        out.addProperty("itemId", key == null ? "" : key.itemId());
        out.addProperty("arm", key == null ? "" : key.leftArm() ? "LEFT" : "RIGHT");
        out.addProperty("equipSerial", state.serial()); out.addProperty("equipSeconds", motion.elapsedSeconds());
        out.addProperty("settled", motion.settled()); out.addProperty("pending", state.pending());
        out.addProperty("kind", state.kind().name()); out.addProperty("itemLift", motion.itemLift());
        out.addProperty("armLift", motion.armLift()); out.addProperty("itemSpinDegrees", motion.itemSpinDegrees());
        out.addProperty("armTiltDegrees", motion.armTiltDegrees());
        out.addProperty("drawProgress", motion.drawProgress());
        return out;
    }
    public static void reset() {
        MAIN.reset(); OFF.reset(); level = null; player = null;
        MAIN_HELD.reset(); OFF_HELD.reset();
        armSubmissions = itemSubmissions = mainHandSubmissions = offHandSubmissions = 0;
        outgoingItemSubmissions = 0; lastOutgoing = false;
        lastItemScale = lastArmRaise = 0;
        nativeArmSkin = nativeArmModel = lastItemId = lastArm = "";
        lastPouchArm = "";
        lastPouchPalmSupport.zero(); lastPouchBase.zero(); lastPouchCenter.zero(); lastPouchUp.zero();
        nativeArmSleeveVisible = false;
    }
    private ImportedItemPresentation() {}
}
