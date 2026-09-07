package com.actioncombat;

import com.actioncombat.combat.CombatAnimationController;
import net.minecraft.resources.Identifier;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.manager.AnimatableManager;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.cache.animation.Animation;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.base.GeoRenderState;

/** Exercises GeckoLib's actual timeline lifecycle when a renderer misses frames. */
public final class CombatAnimationControllerSmokeTest {
    private static final GeoModel<TestAnimatable> MODEL = new GeoModel<>() {
        @Override
        public Identifier getModelResource(GeoRenderState state) {
            return Identifier.fromNamespaceAndPath("actioncombat", "phase_test");
        }

        @Override
        public Identifier getTextureResource(GeoRenderState state) {
            return getModelResource(state);
        }

        @Override
        public Identifier getAnimationResource(TestAnimatable animatable) {
            return getModelResource(null);
        }

        @Override
        public Animation getBakedAnimation(TestAnimatable animatable, String name) {
            return Animation.generateWaitAnimation(1.6);
        }
    };

    public static void verify() {
        TestAnimatable action = new TestAnimatable();
        TestController thirdPerson = new TestController(0);
        TestController firstPerson = new TestController(0);
        action.tick = 100;
        thirdPerson.sample(action);

        action.tick = 113;
        action.elapsed = 0.65;
        thirdPerson.sample(action);
        firstPerson.sample(action);
        assertNear("first render during a slash", 0.65, firstPerson.getCurrentAnimationTime());
        assertNear("first/third-person slash phase", thirdPerson.getCurrentAnimationTime(),
                firstPerson.getCurrentAnimationTime());

        firstPerson.sample(action);
        assertNear("hitstop holds phase", 0.65, firstPerson.getCurrentAnimationTime());

        action.tick = 118;
        action.elapsed = 0.9;
        firstPerson.sample(action);
        thirdPerson.sample(action);
        assertNear("return to third person", 0.9, thirdPerson.getCurrentAnimationTime());

        // Neither controller observed idle before the same clip began again.
        action.tick = 144;
        action.elapsed = 0.2;
        thirdPerson.sample(action);
        firstPerson.sample(action);
        assertNear("same clip on a later attack", 0.2, firstPerson.getCurrentAnimationTime());
        assertNear("repeated third-person attack", 0.2, thirdPerson.getCurrentAnimationTime());

        TestController fullBody = new TestController(2);
        action.elapsed = 0.65;
        fullBody.sample(action);
        assertNear("full-body phase retains transition", 0.55, fullBody.getCurrentAnimationTime());

        TestController locomotion = new TestController(0);
        action.elapsed = -1;
        action.tick = 200;
        locomotion.sample(action);
        action.tick = 202;
        locomotion.sample(action);
        assertNear("uncontrolled locomotion still advances", 0.1, locomotion.getCurrentAnimationTime());
        System.out.println("combat animation phase: perspective switch, hitstop and repeated clips passed");
    }

    private static void assertNear(String description, double expected, double actual) {
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > 0.00001) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class TestController extends CombatAnimationController<TestAnimatable> {
        TestController(int transitionTicks) {
            super("phase_test", transitionTicks,
                    test -> test.setAndContinue(test.animatable().elapsed < 0
                            ? RawAnimation.begin().thenLoop("idle")
                            : RawAnimation.begin().thenPlay("attack")),
                    animatable -> animatable.elapsed);
        }

        void sample(TestAnimatable animatable) {
            GeoRenderState state = new GeoRenderState.Impl();
            state.addGeckolibData(DataTickets.TICK, animatable.tick);
            if (!checkControllerState(animatable, state, animatable.manager, MODEL)) {
                throw new AssertionError("controller failed to produce an animation point");
            }
        }
    }

    private static final class TestAnimatable implements GeoAnimatable {
        final AnimatableManager<TestAnimatable> manager = new AnimatableManager<>(this);
        double tick;
        double elapsed;

        @Override
        public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        }

        @Override
        public AnimatableInstanceCache getAnimatableInstanceCache() {
            throw new UnsupportedOperationException("test supplies its manager directly");
        }
    }

    private CombatAnimationControllerSmokeTest() {
    }
}
