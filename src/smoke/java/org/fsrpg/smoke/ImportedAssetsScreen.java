package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.fsrpg.ImportedItems;

/** Development-only native item-model acceptance, outside the release JAR. */
final class ImportedAssetsScreen extends Screen {
    final JsonArray observations=new JsonArray();
    private final List<ItemStack> stacks=new ArrayList<>();
    private int drawn;
    ImportedAssetsScreen() {super(Component.literal("261001 原模型 · 实际游戏物品验收"));}
    @Override protected void init() {
        if(!stacks.isEmpty())return;
        var game=Minecraft.getInstance();
        for(int i=0;i<ImportedItems.ITEMS.size();i++) {
            var stack=new ItemStack(ImportedItems.ITEMS.get(i).get());stacks.add(stack);
            var state=new ItemStackRenderState();game.getItemModelResolver().updateForTopItem(state,stack,ItemDisplayContext.GUI,game.level,game.player,i);
            if(state.isEmpty())throw new AssertionError("Imported item has no native model: "+ImportedItems.IDS.get(i));
            var particle=state.pickParticleIcon(net.minecraft.util.RandomSource.create(1));
            if(particle==null||particle.contents().name().toString().contains("missingno"))throw new AssertionError("Imported item has a missing texture: "+ImportedItems.IDS.get(i));
            var bounds=state.getModelBoundingBox();
            if(!Double.isFinite(bounds.minX)||bounds.getSize()<=0)throw new AssertionError("Invalid imported model bounds");
            var o=new JsonObject();o.addProperty("id","fsrpg:"+ImportedItems.IDS.get(i));o.addProperty("label",stack.getHoverName().getString());
            o.addProperty("particleTexture",particle.contents().name().toString());o.addProperty("nativeModelSize",bounds.getSize());observations.add(o);
        }
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics gui,int mouseX,int mouseY,float tick) {
        gui.fill(0,0,width,height,0xff152031);
        gui.drawCenteredString(font,title,width/2,10,0xffefcd91);
        int cellWidth=width/3,cellHeight=(height-30)/3;
        for(int i=0;i<stacks.size();i++) {
            int x=(i%3)*cellWidth,y=30+(i/3)*cellHeight;
            gui.fill(x+3,y+3,x+cellWidth-3,y+cellHeight-3,0xff253448);
            gui.pose().pushMatrix();gui.pose().translate(x+cellWidth/2f-24,y+8);gui.pose().scale(3,3);gui.renderItem(stacks.get(i),0,0);gui.pose().popMatrix();
            gui.drawCenteredString(font,stacks.get(i).getHoverName(),x+cellWidth/2,y+cellHeight-26,0xfff1eee4);
            gui.drawCenteredString(font,ImportedItems.IDS.get(i),x+cellWidth/2,y+cellHeight-14,0xff93a8bd);
        }
        drawn++;
    }
    int drawnFrames(){return drawn;}
}
