package com.actioncombat.client;

import com.actioncombat.combat.FpWeaponClips;
import com.actioncombat.combat.CombatAnimationController;
import com.actioncombat.combat.WeaponCategory;
import com.actioncombat.entity.PlayerAnimatable;
import net.minecraft.client.Minecraft;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.manager.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.animation.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Camera-space first-person rig. Independent of the held item so any sword can
 * enter combat mode and still play the authored viewmodel clips.
 */
public final class FpViewmodel implements GeoAnimatable {
    public static final FpViewmodel INSTANCE = new FpViewmodel();

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private String actionClip;
    private long actionUntilNanos;
    private String playingClip;
    private RawAnimation playingAnim;

    private FpViewmodel() {
    }

    public void playAction(String clip, float seconds) {
        if (clip == null || clip.isEmpty()) return;
        long until = System.nanoTime() + (long) (Math.max(seconds, 0.05F) * 1.0E9F);
        this.actionClip = clip;
        this.actionUntilNanos = until;
        this.playingClip = null;
        this.playingAnim = null;
    }

    public void clearAction() {
        actionClip = null;
        actionUntilNanos = 0L;
        playingClip = null;
        playingAnim = null;
    }

    private boolean hasAction() {
        if (actionClip == null) return false;
        if (System.nanoTime() >= actionUntilNanos) {
            actionClip = null;
            playingClip = null;
            playingAnim = null;
            return false;
        }
        return true;
    }

    private RawAnimation hold(String clip) {
        if (clip.equals(playingClip) && playingAnim != null) return playingAnim;
        playingClip = clip;
        playingAnim = RawAnimation.begin().thenPlayAndHold(clip);
        return playingAnim;
    }

    private RawAnimation playOnce(String clip) {
        if (clip.equals(playingClip) && playingAnim != null) return playingAnim;
        playingClip = clip;
        playingAnim = RawAnimation.begin().thenPlay(clip);
        return playingAnim;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new CombatAnimationController<>("fp", 0, test -> {
            if (ClientCombat.localWeaponCategory() == WeaponCategory.DOUBLE) return PlayState.STOP;
            FpWeaponClips.Clips fp = FpWeaponClips.of(ClientCombat.localWeaponCategory());

            if (!ClientCombat.isLocalDrawn() && ClientCombat.isSheathing()
                    && fp.putAway() != null) {
                return test.setAndContinue(hold(fp.putAway()));
            }
            if (!ClientCombat.isLocalDrawn()) {
                return test.setAndContinue(hold("static_pose"));
            }
            var dummy = localDummy();
            if (dummy != null && dummy.hasUpperAction() && dummy.actionClip.startsWith("attack")) {
                String clip = dummy.actionClip.endsWith("_upper")
                        ? dummy.actionClip.substring(0, dummy.actionClip.length() - "_upper".length())
                        : dummy.actionClip;
                return test.setAndContinue(playOnce(clip));
            }
            if (hasAction() && (dummy == null || !actionClip.startsWith("attack"))) {
                return test.setAndContinue(playOnce(actionClip));
            }
            if (fp.defend() != null && ClientCombat.isLocalDefending()) {
                return test.setAndContinue(hold(fp.defend()));
            }
            if (ClientCombat.isFpDrawActive()) {
                return test.setAndContinue(hold(fp.draw()));
            }
            net.minecraft.client.player.LocalPlayer player =
                    net.minecraft.client.Minecraft.getInstance().player;
            float speed = player != null ? player.walkAnimation.speed() : 0.0F;
            if (fp.runHold() != null && speed > com.actioncombat.CombatConstants.RUN_SPEED_THRESHOLD) {
                return test.setAndContinue(hold(fp.runHold()));
            }
            if (fp.walkHold() != null && speed > com.actioncombat.CombatConstants.WALK_SPEED_THRESHOLD) {
                return test.setAndContinue(hold(fp.walkHold()));
            }
            return test.setAndContinue(hold(fp.idle()));
        }, self -> {
            var dummy = localDummy();
            return dummy != null && ClientCombat.isLocalAttackActive() && dummy.hasUpperAction()
                    ? dummy.firstPersonActionAnimationSeconds() : -1.0;
        }));
        controllers.add(dualLocomotionController(), dualActionController());
    }

    static PlayerAnimatable localDualDummy() {
        return ClientCombat.localWeaponCategory() == WeaponCategory.DOUBLE ? localDummy() : null;
    }

    private static PlayerAnimatable localDummy() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : ClientCombat.dummyOf(player.getId());
    }

    private AnimationController<FpViewmodel> dualLocomotionController() {
        // The generated arm rig keeps the third-person ancestor bones, so its base
        // stance and upper-body overlay must run independently, as on the dummy.
        return new CombatAnimationController<>("dual_locomotion", 6, test -> {
            var dummy = localDualDummy();
            if (dummy == null) return PlayState.STOP;
            if (dummy.hasFullBodyAction()) {
                test.controller().setTransitionTicks(2);
                return test.setAndContinue(RawAnimation.begin().thenPlay(dummy.actionClip));
            }
            test.controller().setTransitionTicks(dummy.airborne ? 2 : 4);
            String clip = dummy.locomotionClip(System.nanoTime());
            return test.setAndContinue(PlayerAnimatable.ANIM_JUMP_START.equals(clip)
                    ? RawAnimation.begin().thenPlay(clip) : RawAnimation.begin().thenLoop(clip));
        }, self -> {
            var dummy = localDualDummy();
            return dummy != null && dummy.hasFullBodyAction() ? dummy.actionElapsedSeconds() : -1;
        });
    }

    private AnimationController<FpViewmodel> dualActionController() {
        return new CombatAnimationController<>("dual_action", 5, test -> {
            var dummy = localDualDummy();
            if (dummy == null || dummy.hasFullBodyAction()) return PlayState.STOP;
            if (dummy.hasUpperAction()) {
                test.controller().setTransitionTicks(0);
                String clip = dummy.actionClip.equals("attack_1_upper")
                        ? "attack1_upper" : dummy.actionClip;
                return test.setAndContinue(RawAnimation.begin().thenPlay(clip));
            }
            if (dummy.defending) {
                return test.setAndContinue(RawAnimation.begin().thenPlayAndHold(dummy.weaponCategory.defend));
            }
            if (dummy.guardRecovering()) {
                return test.setAndContinue(RawAnimation.begin().thenPlay(PlayerAnimatable.ANIM_DEFEND_END));
            }
            if (dummy.aiming) {
                return test.setAndContinue(RawAnimation.begin().thenPlayAndHold(dummy.weaponCategory.aiming));
            }
            return PlayState.STOP;
        }, self -> {
            var dummy = localDualDummy();
            return dummy != null && dummy.hasUpperAction() ? dummy.actionAnimationSeconds() : -1;
        });
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
