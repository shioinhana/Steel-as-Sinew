package com.actioncombat.item;

import com.actioncombat.client.LongswordFpController;
import com.actioncombat.client.LongswordFpRenderer;
import net.minecraft.world.item.Item;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.manager.AnimatableManager;
import software.bernie.geckolib.renderer.GeoItemRenderer;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.function.Consumer;

/**
 * The demo longsword. Rendered through GeckoLib's special item model so the
 * viewmodel (and GUI/third-person icon) is an animated geo rig.
 * isPerspectiveAware=true gives every ItemDisplayContext its own
 * AnimatableManager so first-person, GUI and third-person don't fight over
 * one controller state. The renderer/controllers live in client classes and
 * are only loaded on the render thread, keeping the dedicated server clean.
 */
public class LongswordItem extends Item implements GeoItem {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public LongswordItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isPerspectiveAware() {
        return true;
    }

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private LongswordFpRenderer renderer;

            @Override
            public GeoItemRenderer<?> getGeoItemRenderer() {
                if (this.renderer == null) {
                    this.renderer = new LongswordFpRenderer();
                }
                return this.renderer;
            }
        });
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(LongswordFpController.create());
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
