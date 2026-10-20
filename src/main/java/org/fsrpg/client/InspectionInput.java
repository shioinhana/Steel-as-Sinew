package org.fsrpg.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Local cosmetic input; never dispatches damage, skill or resource intents. */
public final class InspectionInput {
    private static final KeyMapping KEY=new KeyMapping("key.fsrpg.inspect",KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_H,
            new KeyMapping.Category(Identifier.fromNamespaceAndPath("fsrpg","combat")));
    private InspectionInput(){}
    public static void register(IEventBus bus){bus.addListener(InspectionInput::keys);NeoForge.EVENT_BUS.addListener(InspectionInput::tick);}
    private static void keys(RegisterKeyMappingsEvent event){event.register(KEY);}
    private static void tick(ClientTickEvent.Post event){
        var game=Minecraft.getInstance();
        while(KEY.consumeClick())if(game.player!=null&&game.screen==null&&game.isWindowActive()&&!game.isPaused()){
            if(!PlayerAnimations.requestInspection())game.player.displayClientMessage(Component.literal(
                    game.options.getCameraType().isFirstPerson()?"切换越肩或第三人称后可检视武器":"拔刀站定后可检视武器"),true);
        }
    }
}
