import fs from 'node:fs/promises';
import path from 'node:path';
import {Workbook,SpreadsheetFile} from '@oai/artifact-tool';

const root=process.cwd(),out=path.join(root,'delivery','0.7.0');
const catalog=JSON.parse(await fs.readFile('src/main/resources/assets/fsrpg/art/catalog.json','utf8'));
const w=Workbook.create();
const sourceDesign='C:/Users/34003/Desktop/xy001003/新Minecraft 1.21.1 动作战斗模组需求 (2).docx';
const cultivation='C:/Users/34003/Desktop/xy001001/actioncombat_cultivation_design.md';
const entries=[];
function task(priority,kind,title,known,deliver,accept,source=sourceDesign){entries.push([`D${String(entries.length+1).padStart(2,'0')}`,priority,kind,title,known,deliver,accept,'',null,'待认领',source]);}
task('P0','设计确认','旧属性与修行体系口径','当前使用心法/煅体/步法/武器修行，旧稿为力量/敏捷/体力/灵力','确认旧怪物等级、威压和元素技能如何映射当前修行；标明废弃字段','一份最终规则表，避免同时套用两套成长',cultivation+'；'+sourceDesign);
task('P0','数值设计','普通怪物等级生成','旧稿要求随机等级；当前未迁移旧等级系统','按种类/维度/区域给等级范围、分布、锁定时机与上限','提供至少低中高等级样例及重复加载规则');
task('P0','数值设计','怪物等级属性曲线','原版生命/攻击/AI继续生效，外观已替换','等级→生命、攻击、防御、速度、经验、掉落的公式与上下限','给1/10/50/100级算例，明确与原版难度叠加方式');
task('P0','Boss设计','Boss名单与身份','旧稿：末影龙/凋灵/劫掠兽/远古守卫者；监守者有素材','确认劫掠兽是否全部为Boss，监守者是否新增Boss及独立ID','最终名单含实体ID、普通/精英/Boss身份');
task('P0','Boss数值','Boss等级与属性','旧稿已给随机1–200级，范围无需重拟','确认等级分布、属性曲线、人数缩放、仇恨与回血规则','给1/50/100/200级样例及多人场景');
task('P0','Boss技能','各Boss战斗阶段','目前保留原版行为；导入动作不会自动产生技能','逐Boss给阶段阈值、技能表、前摇/有效/后摇、范围、伤害、冷却、打断和免疫','每技能有配置ID、单位、动作片段与判定时间轴');
task('P0','Boss产出','召唤、刷新及奖励','当前仍使用原版召唤、刷新、战利品','确认召唤物/地点、重生周期、战利品、首杀与重复奖励及多人归属','给完整刷新到领奖流程，避免重复发奖');
task('P0','AI设计','怪物格挡/闪避/招架','素材有defend；旧稿要求怪物闪避、格挡','按种类/等级给触发条件、概率、资源、无敌窗、冷却、可打断范围','状态机、概率边界和防御动画时序一致');
task('P0','技能确认','威压触发方式与成本冲突','旧稿在主动/开关间未定；消耗同时出现30和60','选定触发方式、唯一消耗值、冷却、范围、目标过滤与Boss抗性','同一版本只保留一套成本与释放条件');
task('P1','技能数值','威压持续时间公式','旧稿解锁总值10，基础3秒；50对10约15秒','给等级差→时长公式、最短/最长、抗性、重复控制衰减','公式覆盖已给算例并明确Boss处理');
task('P1','技能设计','元素附魔效果','旧稿解锁1，启动灵力10，持续1/秒；火红/雷蓝紫/冰淡色','确认当前体系解锁映射、元素增伤/触发率/持续/叠层/抗性和切换规则','每元素给触发与结算样例；确认现有10和1/秒是否沿用');
task('P1','技能边界','额外武器特殊技能范围','旧稿特殊攻击只要求框架；当前已有17个修行节点','若扩展长枪/空手，提供技能清单及解锁、资源、冷却、动作','明确本期范围；不把现有17节点重新列为缺设计',cultivation);
task('P0','世界生成','群系、地形与建筑资源','这五包未提供群系、地形或建筑资源','提供目标群系/维度、建筑/地形模型或NBT、风格参考及版本','提供实际可加载资源及ID清单');
task('P0','世界生成','结构与怪物刷新规则','未找到自然生成规则，当前不新增自然生成','结构间距/频率/高度/地形适配/排斥；怪物群落/权重/数量/条件','固定种子可复现，列出现有世界新增区块规则');
task('P1','世界设计','场景玩法与奖励','这五包未提供场景玩法资源','场景入口、任务/事件、刷怪点、Boss房、奖励箱与重置规则','给平面布局和点位坐标；区分装饰与玩法');
task('P0','美术缺口','远古守卫者模型与动作','Boss需求中存在；现有30个模型无此项','模型、贴图、待机/游泳/攻击/防御/受击/死亡及激光绑定点','Blockbench可打开，单位/朝向/骨骼/动作命名明确');
task('P0','美术缺口','末影龙缺基础动作','现素材只有denfend（导入别名defend）1秒','补待机/飞行/盘旋/攻击/受击/死亡，确认龙息与水晶光束表现','连续循环无跳变，给每动作长度及事件点');
task('P1','美术缺口','溺尸、监守者、僵尸疣猪兽缺动作','这三类当前只有attack/defend；溺尸内外层各一模型','补idle/walk，监守者另确认钻地/咆哮/音波，溺尸游泳','移动时不再停在原始静态姿势；特殊状态有对应片段');
task('P1','美术规范','装备与特殊效果挂点','原模型骨骼命名各异；当前原版手持物近似挂到手臂','统一左右手定位器、武器轴向、护甲附着、眼睛/光束/粒子挂点','各武器不悬空不穿手；明确特殊效果所需附件');
task('P1','动作设计','动画与真实判定时序','117段源动作全部保留，默认AI仍为原版','每种怪物给攻击接触帧、可打断帧、格挡窗、动作优先级与转场','逐动作提供时间轴；视觉挥击与服务器命中对齐');
task('P1','美术确认','原始素材视觉验收','F8图鉴可逐项查看；现有素材含原始动作缺项和辅助几何','同事逐项签收轮廓、UV、色彩、透明、动作、尺度，标记保留或替换','填写素材接入页验收状态及关联D编号','artwork/imports/all-assets-manifest.json');
// Explicit implementation gaps stay separate from colleagues' design tasks.
task('P1','程序迁移','怪物等级系统接回当前架构','旧项目存在MonsterLevelSystem；当前FSRPG未迁移','设计D01–D03确认后迁移服务器权威等级与存档/显示','按最终曲线测试，不把旧实现存在等同于已接入','C:/Users/34003/Desktop/xy001001/newmod');
task('P1','程序适配','Boss特效、护甲和原版特殊状态','通用素材渲染尚未覆盖护甲层、龙水晶光束等专用渲染','逐实体补齐原版特殊效果与动画事件适配','生存实战逐项验收，不仅图鉴播放','src/main/java/org/fsrpg/client/CreatureArtRenderer.java');
task('P1','美术返修','劫掠兽底板与模型尺度','实机可见源文件cube：[-27,-2,-21]→[54,0,30]大底板；并非程序生成','确认并清理展示辅助几何；核定各生物与原版碰撞箱的目标比例','去掉非角色几何或明确保留用途；提供尺寸基准','artwork/imports/creatures/生物动作/ravager.bbmodel');

function sheet(name,title,headers,rows,widths){
 const s=w.worksheets.add(name);s.showGridLines=false;s.getRange('A2').values=[[title]];s.getRange('A2').format.font={name:'Microsoft YaHei',size:14,bold:true,color:'#203449'};
 const end=String.fromCharCode(64+headers.length),last=rows.length+5;
 s.getRange(`A5:${end}${last}`).values=[headers,...rows];
 s.tables.add(`A5:${end}${last}`,true);
 const body=s.getRange(`A5:${end}${last}`);body.format.font={name:'Microsoft YaHei',size:10,color:'#243447'};body.format.verticalAlignment='center';body.format.wrapText=true;body.format.rowHeight=64;
 s.getRange(`A5:${end}5`).format={fill:'#203449',font:{name:'Microsoft YaHei',size:10,bold:true,color:'#FFFFFF'},horizontalAlignment:'center',verticalAlignment:'center',rowHeight:28};
 widths.forEach((width,i)=>s.getRange(`${String.fromCharCode(65+i)}5:${String.fromCharCode(65+i)}${last}`).format.columnWidth=width);
 s.freezePanes.freezeRows(5);s.freezePanes.freezeColumns(4);return s;
}
const s=sheet('设计待补','FSRPG 0.7.0｜生物与HUD待补清单',['编号','优先级','缺口类型','事项','已知基线（勿重复设计）','同事需交付','验收标准','负责人','截止日期','状态','依据 / 来源'],entries,[8,8,14,27,44,48,42,13,14,13,62]);
s.tabColor='#203449';s.getRange('A3').values=[['2026-10-16 · P0先确认；P1接玩法前补齐；P2精修。负责人、日期和状态可编辑。']];
s.getRange(`H6:J${entries.length+5}`).format.fill='#FFF3CD';s.getRange(`I6:I${entries.length+5}`).setNumberFormat('yyyy-mm-dd');
s.getRange(`J6:J${entries.length+5}`).dataValidation={rule:{type:'list',values:['待认领','进行中','待验收','已完成','暂缓']}};
s.getRange(`J6:J${entries.length+5}`).conditionalFormats.add('containsText',{text:'已完成',format:{fill:'#DDEBDC',font:{color:'#244B2A'}}});
const inventory=[];
for(const c of catalog.creatures)inventory.push(['生物模型',c.id,c.source,`${c.nodes}骨骼 / ${c.faces}面`,c.clips.map(x=>x.name).join('、'),'已接入图鉴及原版实体','待美术确认','',`artwork/imports/creatures/生物动作/${c.source}`]);
for(const c of catalog.hud)inventory.push(['HUD源图',c.id,c.label,`${c.width}×${c.height}`,'四条HUD继续使用；F8查看全部源图','已接入HUD/图鉴','待美术确认','',c.texture]);
const a=sheet('素材接入','素材接入与美术验收',['类别','游戏ID / 素材ID','名称 / 原文件','规模 / 属性','当前用途与限制','接入状态','美术验收','关联待补 / 备注','来源'],inventory,[14,38,31,27,57,30,16,30,75]);
a.getRange('A3').values=[['仅五个指定包：30生物模型 / 27实体 / 117动作 / 28张HUD源图。额外锻造和旧平台已撤出，原有武器与徽章保留。']];
a.getRange(`G6:H${inventory.length+5}`).format.fill='#FFF3CD';a.getRange(`G6:G${inventory.length+5}`).dataValidation={rule:{type:'list',values:['待美术确认','通过','需修改','暂缓']}};
w.recalculate();await fs.mkdir(out,{recursive:true});
for(const [name,range,file] of [['设计待补','A2:G10','checklist-preview.png'],['素材接入','A2:F10','inventory-preview.png']]){
 const image=await w.render({sheetName:name,range,scale:1,format:'png'});await fs.writeFile(path.join(out,file),new Uint8Array(await image.arrayBuffer()));
}
console.log((await w.inspect({kind:'sheet,table',maxChars:1800,tableMaxRows:2,tableMaxCols:5})).ndjson);
const file=await SpreadsheetFile.exportXlsx(w);await file.save(path.join(out,'FSRPG-设计与素材待补清单-0.7.0.xlsx'));
console.log(JSON.stringify({designTasks:entries.length,assetRows:inventory.length,output:out}));
