package org.fsrpg.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import org.fsrpg.art.ArtCatalog;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Inspect the delivered source art without spawning mobs or changing world data. */
public final class ArtGalleryScreen extends Screen {
    private static final String[] TABS={"生物动作","界面素材"};
    private int tab,index,clipIndex,rows,listWidth;
    private long started=System.nanoTime();
    private double heldTime;
    private boolean paused,loop=true;
    private LivingEntity preview;
    private String previewId="";
    private long frames;
    public ArtGalleryScreen(){super(Component.literal("FSRPG 美术素材图鉴"));}
    @Override protected void init(){
        listWidth=Math.min(160,Math.max(100,width/3));rows=Math.max(1,(height-112)/22);
        for(int t=0;t<TABS.length;t++){final int choice=t;button(TABS[t],8+t*(width-16)/2,28,(width-24)/2,20,()->select(choice,0));}
        int count=count(),page=index/rows;
        for(int r=0;r<rows;r++){int choice=page*rows+r;if(choice>=count)break;
            button(font.plainSubstrByWidth((choice==index?"› ":"")+label(choice),listWidth-20),8,57+r*22,listWidth-12,20,()->select(tab,choice));}
        button("上一页",8,height-48,(listWidth-18)/2,20,()->select(tab,Math.max(0,(page-1)*rows))).active=page>0;
        button("下一页",listWidth/2+3,height-48,(listWidth-18)/2,20,()->select(tab,Math.min(count-1,(page+1)*rows))).active=(page+1)*rows<count;
        button("关闭",width-58,height-25,50,18,this::onClose);
        if(tab==0){
            int x=listWidth+10,w=Math.max(32,(width-x-18)/5);
            button("上一动作",x,height-49,w,20,()->changeClip(-1));button("下一动作",x+w+2,height-49,w,20,()->changeClip(1));
            button(paused?"继续":"暂停",x+(w+2)*2,height-49,w,20,()->{heldTime=seconds();paused=!paused;started=System.nanoTime();rebuild();});
            button("重播",x+(w+2)*3,height-49,w,20,()->{heldTime=0;started=System.nanoTime();});
            button(loop?"循环开":"循环关",x+(w+2)*4,height-49,w,20,()->{loop=!loop;rebuild();});
        }
    }
    private Button button(String label,int x,int y,int w,int h,Runnable action){return addRenderableWidget(Button.builder(Component.literal(label),b->action.run()).bounds(x,y,w,h).build());}
    private void rebuild(){clearWidgets();init();}
    public void select(int category,int selected){tab=category;index=Math.clamp(selected,0,count()-1);clipIndex=0;heldTime=0;started=System.nanoTime();preview=null;rebuild();}
    public void selectCreature(String id,String clip){
        var profiles=ArtCatalog.DATA.getAsJsonArray("profiles");for(int i=0;i<profiles.size();i++)if(row("profiles",i).get("id").getAsString().equals(id)){select(0,i);break;}
        var clips=row("profiles",index).getAsJsonArray("clips");clipIndex=0;for(int i=0;i<clips.size();i++)if(clips.get(i).getAsString().equals(clip))clipIndex=i+1;
        heldTime=0;started=System.nanoTime();
    }
    private void changeClip(int delta){clipIndex=Math.floorMod(clipIndex+delta,row("profiles",index).getAsJsonArray("clips").size()+1);heldTime=0;started=System.nanoTime();}
    private int count(){return ArtCatalog.DATA.getAsJsonArray(tab==0?"profiles":"hud").size();}
    private static JsonObject row(String category,int i){return ArtCatalog.DATA.getAsJsonArray(category).get(i).getAsJsonObject();}
    private String label(int i){return tab==0?Component.translatable("entity.minecraft."+row("profiles",i).get("id").getAsString()).getString():row("hud",i).get("label").getAsString();}
    public String currentClip(){return clipIndex==0?"":row("profiles",index).getAsJsonArray("clips").get(clipIndex-1).getAsString();}
    public double seconds(){return heldTime+(paused?0:(System.nanoTime()-started)/1e9);}
    public long drawnFrames(){return frames;}
    @Override public void render(GuiGraphics gui,int mouseX,int mouseY,float partial){
        gui.fill(0,0,width,height,0xff121c2a);gui.drawString(font,title,10,10,0xffefd8a9);
        int x=listWidth+8,y=80,w=width-x-10,h=height-y-140;
        gui.fill(x,56,width-8,height-58,0xff243448);
        gui.drawString(font,font.plainSubstrByWidth(label(index),w-12),x+8,64,0xffeeeeee);
        if(tab==0&&minecraft.level!=null){
            var profile=row("profiles",index);String id=profile.get("entity").getAsString();
            if(preview==null||!previewId.equals(id)){preview=(LivingEntity)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id)).create(minecraft.level,EntitySpawnReason.COMMAND);previewId=id;}
            var renderer=minecraft.getEntityRenderDispatcher().getRenderer(preview);
            if(renderer.createRenderState(preview,partial) instanceof CreatureArtRenderer.State state){
                state.clip=currentClip();state.seconds=seconds();state.loop=loop;state.bodyRot=210;state.scale=1;state.lightCoords=15728880;state.shadowPieces.clear();state.outlineColor=0;
                var bounds=profile.getAsJsonArray("bounds");float[] lo=org.fsrpg.art.CreatureArt.array(bounds.get(0).getAsJsonArray()),hi=org.fsrpg.art.CreatureArt.array(bounds.get(1).getAsJsonArray());
                float scale=Math.min(w/Math.max(1,(hi[0]-lo[0])+(hi[2]-lo[2])*.5f),Math.max(30,h)/Math.max(1,hi[1]-lo[1]))*.72f;
                var tilt=new Quaternionf().rotateX((float)Math.toRadians(12));
                gui.submitEntityRenderState(state,scale,new Vector3f(0,(lo[1]+hi[1])/2,0),new Quaternionf().rotateZ((float)Math.PI).mul(tilt),tilt,x,y,width-8,height-105);
            }
            gui.drawString(font,"动作："+(currentClip().isEmpty()?"原始静态姿势":currentClip()),x+8,height-96,0xffefd8a9);
            gui.drawString(font,"展示原素材；AI、伤害与刷新沿用原版",x+8,height-81,0xffa8b7c9);
        }else if(tab==1){
            var image=row("hud",index);int iw=image.get("width").getAsInt(),ih=image.get("height").getAsInt();float ratio=Math.min((w-20f)/iw,Math.max(40,h)/(float)ih);
            int dw=Math.max(1,(int)(iw*ratio)),dh=Math.max(1,(int)(ih*ratio));
            gui.blit(RenderPipelines.GUI_TEXTURED,Identifier.parse(image.get("texture").getAsString()),x+(w-dw)/2,y+10,0,0,dw,dh,iw,ih,iw,ih);
            gui.drawString(font,iw+" × "+ih+" · 原图完整预览",x+8,height-81,0xffa8b7c9);
        }
        gui.drawString(font,(index+1)+" / "+count()+" · F8 打开 · Esc 返回",10,height-17,0xffa8b7c9);super.render(gui,mouseX,mouseY,partial);frames++;
    }
    @Override public boolean isPauseScreen(){return false;}
}

