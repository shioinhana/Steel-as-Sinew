package com.actioncombat.client;

import com.actioncombat.combat.FpWeaponClips;
import com.actioncombat.combat.SwingSide;
import com.actioncombat.item.LongswordItem;
import net.minecraft.world.item.ItemDisplayContext;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.constant.DataTickets;

/**
 * First-person viewmodel animation driver. Pure setAndContinue: returning an
 * equal RawAnimation keeps the current clip (and advances its stage list),
 * so looping chains need no bookkeeping.
 * <p>
 * The draw clip replays on every drawn-idle evaluation unless it is gated:
 * while the draw window (clip length) is open we return draw->idle, and once
 * it closes we return plain idle. The switch happens only after draw finished,
 * and idle restarting is invisible.
 * <p>
 * Combat mode works in either perspective — the player picks with vanilla F5, the mod
 * never touches it — so this viewmodel is live whenever the local player is drawn and
 * currently in first person. It holds static_pose otherwise, because the third-person
 * body is the dummy entity's rig, not this item. DOUBLE clips are null for
 * run/walk/defend/aiming; those branches are skipped.
 */
public final class LongswordFpController {
    public static AnimationController<LongswordItem> create() {
        return new AnimationController<>("root", 3, test -> {
            ItemDisplayContext ctx = test.renderState().getOrDefaultGeckolibData(
                    DataTickets.ITEM_RENDER_PERSPECTIVE, ItemDisplayContext.NONE);
            FpWeaponClips.Clips fp = FpWeaponClips.of(ClientCombat.localWeaponCategory());
            boolean firstPersonMain = ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;

            // Leaving combat mode clears the drawn flag immediately, so the sheath clip
            // has to be driven by its own window or it would never be seen here.
            if (firstPersonMain && !ClientCombat.isLocalDrawn() && ClientCombat.isSheathing()
                    && fp.putAway() != null) {
                return test.setAndContinue(RawAnimation.begin().thenPlayAndHold(fp.putAway()));
            }
            if (!firstPersonMain || !ClientCombat.isLocalDrawn()) {
                return test.setAndContinue(RawAnimation.begin().thenPlayAndHold("static_pose"));
            }
            if (ClientCombat.isLocalAttackActive()) {
                String attack = ClientCombat.isLocalDualAttack()
                        ? fp.dualAttack()
                        : (ClientCombat.localAttackSide() != null
                        ? fp.attackClip(ClientCombat.localAttackSide(), ClientCombat.localAttackDepth())
                        : null);
                if (attack != null) {
                    return test.setAndContinue(RawAnimation.begin().thenPlay(attack));
                }
            }
            test.setControllerSpeed(1.0F);
            if (fp.defend() != null && ClientCombat.isLocalDefending()) {
                return test.setAndContinue(RawAnimation.begin().thenPlayAndHold(fp.defend()));
            }
            if (fp.aiming() != null && ClientCombat.isLocalAiming()) {
                return test.setAndContinue(RawAnimation.begin().thenPlayAndHold(fp.aiming()));
            }
            if (fp.runStart() != null && ClientCombat.isLocalRunning()) {
                return test.setAndContinue(RawAnimation.begin()
                        .thenPlay(fp.runStart()).thenLoop(fp.runHold()));
            }
            if (fp.walkStart() != null && ClientCombat.isLocalWalking()) {
                return test.setAndContinue(RawAnimation.begin()
                        .thenPlay(fp.walkStart()).thenLoop(fp.walkHold()));
            }
            if (fp.inspect() != null && ClientCombat.isLocalInspecting()) {
                return test.setAndContinue(RawAnimation.begin().thenLoop(fp.inspect()));
            }
            if (ClientCombat.isFpDrawActive()) {
                return test.setAndContinue(RawAnimation.begin()
                        .thenPlay(fp.draw()).thenLoop(fp.idle()));
            }
            return test.setAndContinue(RawAnimation.begin().thenLoop(fp.idle()));
        });
    }

    private LongswordFpController() {
    }
}
