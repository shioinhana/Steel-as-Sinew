package org.fsrpg.client;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Scrollable diagram and independent details; all learning conditions come from the server. */
public final class SkillTreeScreen extends Screen {
    private static final int ROW_HEIGHT=42,TEXT=0xffc6d4e0,MUTED=0xff9badbd,GREEN=0xffafd59f,GOLD=0xffecd29d;
    private String tree="rapier",selected="",lastSelected="",lastTree="",feedback="";
    private int left,top,panelWidth,panelHeight,chartX,chartY,chartWidth,chartHeight,detailX,detailWidth,bodyY,bodyHeight;
    private int chartPage,visibleRows,detailScroll,detailPixels;
    private boolean learning;
    private long learnRevision;
    private final Map<String,int[]> positions=new LinkedHashMap<>();
    private final Map<String,JsonObject> byId=new LinkedHashMap<>();
    private List<JsonObject> nodes=List.of();
    private List<Stage> stages=List.of();
    private Button detailUp,detailDown;
    private record Stage(String caption,List<JsonObject> nodes) {}
    private record TextLine(FormattedCharSequence text,int color) {}
    public SkillTreeScreen(){super(Component.literal("武器修行 · 技能树"));}
    private JsonObject progress(){return ClientState.object(ClientState.snapshot(),"progression");}
    private JsonObject chosen(){return byId.getOrDefault(selected,new JsonObject());}
    @Override protected void init() {
        panelWidth=Math.min(680,width-16);panelHeight=Math.min(420,height-16);
        left=(width-panelWidth)/2;top=(height-panelHeight)/2;
        chartX=left+10;chartY=top+77;chartWidth=Math.max(122,(panelWidth-32)*44/100);
        chartHeight=Math.max(42,panelHeight-113);visibleRows=Math.max(1,chartHeight/ROW_HEIGHT);
        detailX=chartX+chartWidth+12;detailWidth=left+panelWidth-12-detailX;
        bodyY=chartY+29;bodyHeight=Math.max(12,top+panelHeight-38-bodyY);
        positions.clear();byId.clear();
        String[] ids={"rapier","dual_blade","bow"},labels={"西洋剑","双刀","弓"};int tabWidth=(panelWidth-24)/3;
        for(int i=0;i<ids.length;i++) {
            String id=ids[i];addRenderableWidget(Button.builder(Component.literal((tree.equals(id)?"● ":"")+labels[i]),b->{
                tree=id;selected="";chartPage=detailScroll=0;feedback="";learning=false;rebuild();
            }).bounds(left+12+i*tabWidth,top+30,tabWidth-3,20).build());
        }
        nodes=ClientState.objects(progress(),"nodes").stream().filter(n->ClientState.text(n,"tree","").equals(tree)).toList();
        for(var node:nodes)byId.put(ClientState.text(node,"id",""),node);
        if(!byId.containsKey(selected))selected=nodes.isEmpty()?"":ClientState.text(nodes.getFirst(),"id","");
        stages=stageRows();
        chartPage=Math.clamp(chartPage,0,Math.max(0,stages.size()-visibleRows));
        if(!selected.equals(lastSelected)||!tree.equals(lastTree)) {
            detailScroll=0;feedback="";
            for(int row=0;row<stages.size();row++)for(var node:stages.get(row).nodes())if(ClientState.text(node,"id","").equals(selected)) {
                if(row<chartPage)chartPage=row;else if(row>=chartPage+visibleRows)chartPage=row-visibleRows+1;
            }
            lastSelected=selected;lastTree=tree;
        }
        for(int row=0;row<stages.size();row++) {
            var stage=stages.get(row);int count=stage.nodes().size(),nodeWidth=(chartWidth-12-(count-1)*6)/count;
            for(int column=0;column<count;column++) {
                JsonObject node=stage.nodes().get(column);String id=ClientState.text(node,"id","");
                int x=chartX+6+column*(nodeWidth+6),y=chartY+(row-chartPage)*ROW_HEIGHT+13;
                positions.put(id,new int[]{x,y,nodeWidth,row});
                if(row<chartPage||row>=chartPage+visibleRows)continue;
                boolean learned=ClientState.bool(node,"learned",false),available=ClientState.bool(node,"available",false);
                String marker=learned?"✓ ":available?"+ ":"· ";
                addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(marker+ClientState.text(node,"label",id),nodeWidth-8)),b->{
                    selected=id;detailScroll=0;feedback="";learning=false;rebuild();
                }).bounds(x,y,nodeWidth,20).build());
                // The right column contains the complete description; hovering must not cover it.
            }
        }
        int footer=top+panelHeight-27;
        addRenderableWidget(Button.builder(Component.literal("▲"),b->{chartPage--;rebuild();}).bounds(chartX,footer,22,20).build()).active=chartPage>0;
        addRenderableWidget(Button.builder(Component.literal("▼"),b->{chartPage++;rebuild();}).bounds(chartX+chartWidth-22,footer,22,20).build()).active=chartPage+visibleRows<stages.size();
        detailUp=addRenderableWidget(Button.builder(Component.literal("▲"),b->scrollDetails(-33)).bounds(detailX+detailWidth-31,chartY,14,14).build());
        detailDown=addRenderableWidget(Button.builder(Component.literal("▼"),b->scrollDetails(33)).bounds(detailX+detailWidth-15,chartY,14,14).build());
        boolean learned=ClientState.bool(chosen(),"learned",false),automatic=ClientState.bool(chosen(),"automatic",false);
        Button learn=addRenderableWidget(Button.builder(Component.literal(learning?"等待服务端…":learned?"已学会":automatic?"自动解锁":"学习所选技能"),b->{
            learning=true;learnRevision=ClientState.revision();feedback="";ClientHooks.send("learnSkill",selected);rebuild();
        }).bounds(detailX,footer,Math.max(55,detailWidth-59),20).build());
        learn.active=!learning&&ClientState.bool(chosen(),"available",false)&&!learned&&!automatic;
        addRenderableWidget(Button.builder(Component.literal("关闭"),b->onClose()).bounds(left+panelWidth-63,footer,51,20).build());
    }
    private List<Stage> stageRows() {
        Map<String,Integer> depths=new HashMap<>();for(var node:nodes)depths.put(ClientState.text(node,"id",""),depth(node,new HashSet<>()));
        List<JsonObject> ordered=new ArrayList<>(nodes);
        ordered.sort(Comparator.comparingInt((JsonObject n)->depths.get(ClientState.text(n,"id","")))
                .thenComparingInt(this::scoreOrder).thenComparingInt(this::pointStage));
        Map<String,List<JsonObject>> groups=new LinkedHashMap<>();
        for(var node:ordered) {
            String key=depths.get(ClientState.text(node,"id",""))+":"+scoreOrder(node)+":"+pointStage(node)+":"+ClientState.text(node,"exclusiveGroup","");
            groups.computeIfAbsent(key,x->new ArrayList<>()).add(node);
        }
        List<Stage> result=new ArrayList<>();
        for(var group:groups.values())for(int start=0;start<group.size();start+=2) {
            var node=group.get(start);var req=ClientState.object(node,"requirements");int score=(int)ClientState.number(req,"score",0);
            String caption=depths.get(ClientState.text(node,"id",""))==0?"入门":score>0?"熟练度 "+score:
                    ClientState.bool(req,"trial",false)?"完成试炼":ClientState.bool(req,"dragon",false)?"击败末影龙":"修行总值 "+pointStage(node);
            if(!ClientState.text(node,"exclusiveGroup","").isBlank())caption+=" · 二选一";
            result.add(new Stage(caption,List.copyOf(group.subList(start,Math.min(start+2,group.size())))));
        }
        return List.copyOf(result);
    }
    private int scoreOrder(JsonObject node) {
        var req=ClientState.object(node,"requirements");int score=(int)ClientState.number(req,"score",0);
        return score==0&&(ClientState.bool(req,"trial",false)||ClientState.bool(req,"dragon",false))?Integer.MAX_VALUE:score;
    }
    private int pointStage(JsonObject node) {
        var req=ClientState.object(node,"requirements");return (int)Math.max(ClientState.number(req,"mind",0),Math.max(ClientState.number(req,"body",0),ClientState.number(req,"footwork",0)));
    }
    private int depth(JsonObject node,Set<String> visited) {
        String id=ClientState.text(node,"id","");if(!visited.add(id))return 0;int result=0;
        var parents=node.get("prerequisites");if(parents!=null&&parents.isJsonArray())for(var parent:parents.getAsJsonArray()) {
            var definition=byId.get(parent.getAsString());if(definition!=null)result=Math.max(result,1+depth(definition,new HashSet<>(visited)));
        }
        return result;
    }
    private void prose(List<TextLine> rows,String text,int color) {
        for(var line:font.split(Component.literal(text),Math.max(20,detailWidth-10)))rows.add(new TextLine(line,color));
    }
    private List<TextLine> detailRows() {
        List<TextLine> rows=new ArrayList<>();JsonObject node=chosen();
        if(node.size()==0){prose(rows,"等待服务端技能配置…",MUTED);return rows;}
        if(!feedback.isBlank())prose(rows,feedback,GOLD);
        if(!ClientState.bool(node,"learned",false))for(String blocker:strings(node,"blockers"))prose(rows,"• "+blocker,GOLD);
        prose(rows,ClientState.text(node,"description",""),TEXT);
        List<String> parents=new ArrayList<>();for(String id:strings(node,"prerequisites"))parents.add(ClientState.text(byId.getOrDefault(id,new JsonObject()),"label",id));
        if(!parents.isEmpty())prose(rows,"前置："+String.join("、",parents),GREEN);
        var req=ClientState.object(node,"requirements");List<String> requirements=new ArrayList<>();
        for(String key:List.of("score","mind","body","footwork")) {
            int value=(int)ClientState.number(req,key,0);if(value<=0)continue;
            requirements.add(switch(key){case "score"->"熟练度 "+value;case "mind"->"心法 "+value;case "body"->"煅体 "+value;default->"步法 "+value;});
        }
        if(ClientState.bool(req,"trial",false))requirements.add("完整通过试炼刷怪笼");
        if(ClientState.bool(req,"dragon",false))requirements.add("击杀末影龙");
        if(!requirements.isEmpty())prose(rows,"要求："+String.join("；",requirements),MUTED);
        String exclusive=ClientState.text(node,"exclusiveGroup","");
        if(!exclusive.isBlank()) {
            List<String> peers=nodes.stream().filter(n->!ClientState.text(n,"id","").equals(selected)&&ClientState.text(n,"exclusiveGroup","").equals(exclusive))
                    .map(n->ClientState.text(n,"label","")).toList();
            prose(rows,"与 "+String.join("、",peers)+" 互斥，同阶段只能选一项。",GOLD);
        }
        return rows;
    }
    private static List<String> strings(JsonObject node,String field) {
        List<String> result=new ArrayList<>();var value=node.get(field);
        if(value!=null&&value.isJsonArray())for(var entry:value.getAsJsonArray())if(entry.isJsonPrimitive())result.add(entry.getAsString());
        return result;
    }
    private void scrollDetails(int delta){detailScroll=Math.clamp(detailScroll+delta,0,Math.max(0,detailPixels-bodyHeight));}
    private void rebuild(){clearWidgets();init();}
    public void acceptSnapshot() {
        if(learning&&ClientState.revision()>learnRevision) {feedback=ClientState.text(ClientState.snapshot(),"message","");learning=false;}
        if(panelWidth>0)rebuild();
    }
    @Override public boolean mouseScrolled(double mouseX,double mouseY,double scrollX,double scrollY) {
        if(scrollY!=0&&mouseY>=chartY&&mouseY<top+panelHeight-34) {
            if(mouseX>=chartX&&mouseX<chartX+chartWidth) {chartPage=Math.clamp(chartPage-(int)Math.signum(scrollY),0,Math.max(0,stages.size()-visibleRows));rebuild();return true;}
            if(mouseX>=detailX&&mouseX<detailX+detailWidth) {scrollDetails(-(int)Math.signum(scrollY)*33);return true;}
        }
        return super.mouseScrolled(mouseX,mouseY,scrollX,scrollY);
    }
    @Override public void render(GuiGraphics gui,int mouseX,int mouseY,float partialTick) {
        gui.fill(left,top,left+panelWidth,top+panelHeight,0xf016202c);gui.fill(left,top,left+panelWidth,top+3,0xffb8d19c);
        var view=ClientState.object(progress(),"view");int mastery=(int)ClientState.number(view,tree.equals("rapier")?"rapierKills":tree.equals("dual_blade")?"dualHits":"archeryScore",0);
        gui.drawString(font,font.plainSubstrByWidth(title.getString()+" · 熟练度 "+mastery,panelWidth-24),left+12,top+12,0xffe9f4dc);
        String stats="可用/总值 · 心法 "+points(view,"mind")+"  煅体 "+points(view,"body")+"  步法 "+points(view,"footwork");
        gui.drawString(font,font.plainSubstrByWidth(stats,panelWidth-24),left+12,top+57,TEXT);
        gui.fill(chartX-2,chartY-3,chartX+chartWidth+2,top+panelHeight-35,0xb9101720);
        gui.fill(detailX-4,chartY-3,detailX+detailWidth+3,top+panelHeight-35,0xb9101720);
        gui.enableScissor(chartX,chartY,chartX+chartWidth,chartY+chartHeight);
        for(var node:nodes) {
            var to=positions.get(ClientState.text(node,"id",""));if(to==null)continue;
            for(String parent:strings(node,"prerequisites")) {
                var from=positions.get(parent);if(from==null)continue;
                int x1=from[0]+from[2]/2,y1=from[1]+20,x2=to[0]+to[2]/2,y2=to[1],gutter=chartX+2,turn=y2-4;
                int color=ClientState.bool(node,"learned",false)?0xff63876e:0xff3e515e;
                gui.hLine(gutter,x1,y1+3,color);gui.vLine(gutter,y1+3,turn,color);gui.hLine(gutter,x2,turn,color);
                gui.vLine(x1,y1,y1+3,color);gui.vLine(x2,turn,y2,color);
            }
        }
        for(int row=chartPage;row<Math.min(stages.size(),chartPage+visibleRows);row++) {
            int y=chartY+(row-chartPage)*ROW_HEIGHT;String caption=font.plainSubstrByWidth(stages.get(row).caption(),chartWidth-12);
            gui.fill(chartX+6,y,chartX+8+font.width(caption),y+10,0xff101720);gui.drawString(font,caption,chartX+6,y+1,MUTED);
        }
        var selection=positions.get(selected);if(selection!=null&&selection[3]>=chartPage&&selection[3]<chartPage+visibleRows)
            gui.fill(selection[0]-3,selection[1],selection[0]-1,selection[1]+20,GREEN);
        gui.disableScissor();
        gui.drawCenteredString(font,(stages.isEmpty()?"0":(chartPage+1)+"–"+Math.min(stages.size(),chartPage+visibleRows))+" / "+stages.size(),chartX+chartWidth/2,top+panelHeight-21,MUTED);
        JsonObject node=chosen();gui.drawString(font,font.plainSubstrByWidth(ClientState.text(node,"label","技能详情"),detailWidth-36),detailX,chartY+2,GOLD);
        var cost=ClientState.object(node,"cost");String state=ClientState.bool(node,"learned",false)?"已学会":ClientState.bool(node,"available",false)?"可学习":ClientState.bool(node,"automatic",false)?"条件达成自动解锁":"尚未解锁";
        String meta=state+(ClientState.bool(node,"automatic",false)?" · 自动节点":" · 消耗 心"+(int)ClientState.number(cost,"mind",0)+"/体"+(int)ClientState.number(cost,"body",0)+"/步"+(int)ClientState.number(cost,"footwork",0));
        gui.drawString(font,font.plainSubstrByWidth(meta,detailWidth),detailX,chartY+18,ClientState.bool(node,"learned",false)?GREEN:MUTED);
        var lines=detailRows();detailPixels=lines.size()*11;detailScroll=Math.clamp(detailScroll,0,Math.max(0,detailPixels-bodyHeight));
        detailUp.active=detailScroll>0;detailDown.active=detailScroll<Math.max(0,detailPixels-bodyHeight);
        gui.enableScissor(detailX,bodyY,detailX+detailWidth,bodyY+bodyHeight);int y=bodyY-detailScroll;
        for(var line:lines) {if(y+11>bodyY&&y<bodyY+bodyHeight)gui.drawString(font,line.text(),detailX,y,line.color());y+=11;}
        gui.disableScissor();
        if(detailPixels>bodyHeight) {
            int thumb=Math.max(8,bodyHeight*bodyHeight/detailPixels),track=bodyHeight-thumb;
            int offset=track*detailScroll/Math.max(1,detailPixels-bodyHeight);
            gui.fill(detailX+detailWidth-2,bodyY,detailX+detailWidth,bodyY+bodyHeight,0xff293b4c);
            gui.fill(detailX+detailWidth-2,bodyY+offset,detailX+detailWidth,bodyY+offset+thumb,0xffa9c5a0);
        }
        super.render(gui,mouseX,mouseY,partialTick);
    }
    private String points(JsonObject view,String id){var p=ClientState.object(view,id);return (int)ClientState.number(p,"available",0)+"/"+(int)ClientState.number(p,"total",0);}
    @Override public boolean isPauseScreen(){return false;}
}
