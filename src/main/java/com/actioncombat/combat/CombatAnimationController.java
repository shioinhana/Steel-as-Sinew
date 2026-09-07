package com.actioncombat.combat;

import java.util.function.ToDoubleFunction;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.manager.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.object.PlayState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.base.GeoRenderState;

/** Keeps one-shot actions at their shared combat-clock position across renderers. */
public class CombatAnimationController<T extends GeoAnimatable> extends AnimationController<T> {
    private final ToDoubleFunction<T> actionElapsedSeconds;

    /** The elapsed supplier returns a negative value when this layer has no action. */
    public CombatAnimationController(String name, int transitionTicks,
                                     AnimationStateHandler<T> stateHandler,
                                     ToDoubleFunction<T> actionElapsedSeconds) {
        super(name, transitionTicks, stateHandler);
        this.actionElapsedSeconds = actionElapsedSeconds;
    }

    @Override
    protected boolean checkControllerState(T animatable, GeoRenderState renderState,
                                            AnimatableManager<T> manager, GeoModel<T> model) {
        boolean animating = super.checkControllerState(animatable, renderState, manager, model);
        double elapsed = actionElapsedSeconds.applyAsDouble(animatable);
        if (elapsed < 0 || !Double.isFinite(elapsed) || timeline == null || playState == PlayState.STOP) {
            return animating;
        }

        // An unseen controller starts at zero, and a cached controller may still
        // contain an earlier occurrence of this same clip. Both seek to the action.
        timelineTime = Math.min(elapsed, timeline.lastAnimationEndTime());
        animationPoint = timeline.createAnimationPoint(timelineTime, null, easingOverride);
        if (!isTransitioning()) transitionFromPoint = null;
        return isAnimatingBones();
    }
}
