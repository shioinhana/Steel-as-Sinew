package org.fsrpg.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.fsrpg.mixin.ItemStateAccess;
import org.fsrpg.mixin.ItemLayerAccess;

/** Retarget the official render state, retaining its item components, resource-pack model and glint. */
public final class OfficialHeldItems {
    private static long submissions, foilSubmissions, offhandSubmissions, offhandAttachedSubmissions;
    private static String lastItemId = "";
    private static String lastOffhandItemId = "";
    private static boolean lastFoil;
    private OfficialHeldItems() {}
    public static long submissions() { return submissions; }
    public static String lastItemId() { return lastItemId; }
    public static boolean lastFoil() { return lastFoil; }
    public static long foilSubmissions() { return foilSubmissions; }
    public static long offhandSubmissions() { return offhandSubmissions; }
    public static String lastOffhandItemId() { return lastOffhandItemId; }
    public static long offhandAttachedSubmissions() { return offhandAttachedSubmissions; }
    public static void observeVanilla(ArmedEntityRenderState state,ItemStackRenderState item,ItemStack stack,HumanoidArm arm) {
        if(state instanceof AvatarRenderState&&arm!=state.mainArm&&!item.isEmpty()) {
            offhandSubmissions++;lastOffhandItemId=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        }
    }
    public static boolean submit(ArmedEntityRenderState armed, ItemStackRenderState item, ItemStack stack,
                                 HumanoidArm arm, PoseStack poses, SubmitNodeCollector collector, int light) {
        if (!(armed instanceof AvatarRenderState state) || state.mainArm != HumanoidArm.RIGHT
                || item.isEmpty() || state.isSpectator || state.isFallFlying || state.swimAmount > .5f) return false;
        var frame = PlayerAnimations.frame(state);
        if(frame!=null && LegacyMotion.applies(frame.model()))return submitLegacy(frame,item,stack,arm,poses,collector,light,state);
        String hand=arm==HumanoidArm.RIGHT?"right_hand":"left_hand";
        if (frame == null || !frame.world().containsKey(hand)) return false;
        poses.pushPose();
        // Official left-hand display transforms mirror their authored Y/Z rotations, so the
        // neutral handheld carrier is the same; the resolved item retains its LEFT_HAND context.
        boolean spear=arm==HumanoidArm.RIGHT && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_spear");
        // Determine the actor's grip from the rig, also for remote players and editor previews.
        boolean dual=frame.model().rig().clips().containsKey("dual_idle_00");
        poses.mulPose(spear?HeldItemTransform.spearCarrier(frame.world().get(hand)):dual?HeldItemTransform.dualCarrier(frame.world().get(hand)):HeldItemTransform.rapierCarrier(frame.world().get(hand)));
        item.submit(poses,collector,light,OverlayTexture.NO_OVERLAY,state.outlineColor);
        poses.popPose();
        if(arm==HumanoidArm.LEFT) {
            offhandSubmissions++;offhandAttachedSubmissions++;lastOffhandItemId=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        } else {
            submissions++;lastItemId=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();lastFoil=foil(item);
            if(lastFoil)foilSubmissions++;
        }
        return true;
    }
    private static boolean submitLegacy(PlayerAnimations.Frame frame,ItemStackRenderState original,ItemStack stack,
                                        HumanoidArm arm,PoseStack poses,SubmitNodeCollector collector,int light,AvatarRenderState state) {
        String kind=LegacyMotion.kind(frame.model());boolean bow=kind.equals("bow");
        boolean isBow=stack.getItem() instanceof net.minecraft.world.item.BowItem;
        String locator=bow?(isBow?"left_item":"right_item"):(arm==HumanoidArm.RIGHT?"right_item":"left_item");
        if(!frame.world().containsKey(locator))return false;
        var game=net.minecraft.client.Minecraft.getInstance();
        if(game.level==null || !(game.level.getEntity(state.id) instanceof net.minecraft.world.entity.LivingEntity owner))return false;
        var context=(bow&&isBow || kind.equals("rapier")&&arm==HumanoidArm.RIGHT)?net.minecraft.world.item.ItemDisplayContext.NONE:
                arm==HumanoidArm.LEFT?net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_LEFT_HAND:net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        var intent=PlayerAnimations.intent(owner.getUUID());
        ItemStack visual=stack;
        if(bow&&isBow)visual=owner==game.player?LegacyFirstPersonAnimations.bowVisual(stack):
                LegacyFirstPersonAnimations.bowVisual(stack,intent!=null&&intent.action().endsWith("_charge"),1);
        var resolved=new ItemStackRenderState();game.getItemModelResolver().updateForLiving(resolved,visual,context,owner);
        if(resolved.isEmpty())return false;
        poses.pushPose();poses.mulPose(HeldItemTransform.legacyLocator(frame.world().get(locator)));
        if(!bow || !isBow)poses.mulPose(kind.equals("rapier")&&arm==HumanoidArm.RIGHT?HeldItemTransform.legacyRapierItem():HeldItemTransform.legacyDualItem());
        resolved.submit(poses,collector,light,OverlayTexture.NO_OVERLAY,state.outlineColor);poses.popPose();
        if(arm==HumanoidArm.LEFT){offhandSubmissions++;offhandAttachedSubmissions++;lastOffhandItemId=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();}
        else{submissions++;lastItemId=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();lastFoil=foil(resolved);if(lastFoil)foilSubmissions++;}
        return true;
    }
    private static boolean foil(ItemStackRenderState item) {
        boolean foil=false;
        var resolved=(ItemStateAccess)(Object)item;
        for(int i=0;i<resolved.fsrpg$activeLayers();i++)
            foil|=((ItemLayerAccess)(Object)resolved.fsrpg$layers()[i]).fsrpg$foil()!=ItemStackRenderState.FoilType.NONE;
        return foil;
    }
}
