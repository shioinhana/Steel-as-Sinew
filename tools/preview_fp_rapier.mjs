// Renders a self-contained first-person preview of the rapier viewmodel.
// Usage: node tools/preview_fp_rapier.mjs [--out <html>] [--baseline <json>] [--save <json>]
import fs from 'node:fs';
import path from 'node:path';
import { posePoint, sample } from './fp_projection.mjs';

const ROOT = path.resolve(import.meta.dirname, '..');
const A = path.join(ROOT, 'src/main/resources/assets/actioncombat/geckolib');
const argv = process.argv.slice(2);
const flag = (name, fallback) => {
  const i = argv.indexOf('--' + name);
  return i >= 0 ? argv[i + 1] : fallback;
};
const OUT = path.resolve(flag('out', path.join(ROOT, 'docs/preview/fp-rapier.html')));
const BASELINE = flag('baseline', null);
const SAVE = flag('save', null);

const geo = JSON.parse(fs.readFileSync(path.join(A, 'models/fp_singlesword.geo.json')));
const bones = new Map(geo['minecraft:geometry'][0].bones.map(b => [b.name, b]));
const anims = JSON.parse(fs.readFileSync(path.join(A, 'animations/fp_singlesword.animation.json'))).animations;
const CLIPS = ['static_idle', 'attack1', 'attack2', 'attack3', 'attack4', 'rapier_thrust_hit', 'draw'];
const FPS = 60;

function bake(name) {
  const src = anims[name];
  if (!src) throw new Error('missing clip ' + name);
  const frames = Math.max(1, Math.round(src.animation_length * FPS)) + 1;
  const out = { length: src.animation_length, fps: FPS, rotation: {}, position: {} };
  for (const bone of ['right', 'fp_forearm_r', 'elucidator']) {
    out.rotation[bone] = [];
    out.position[bone] = [];
    for (let i = 0; i < frames; i++) {
      const t = Math.min(src.animation_length, i / FPS);
      const ch = src.bones[bone] ?? {};
      out.rotation[bone].push(sample(ch.rotation, t, [0, 0, 0]).map(v => Math.round(v * 1e3) / 1e3));
      out.position[bone].push(sample(ch.position, t, [0, 0, 0]).map(v => Math.round(v * 1e3) / 1e3));
    }
  }
  return out;
}
const data = {};
for (const name of CLIPS) data[name] = bake(name);
if (SAVE) { fs.mkdirSync(path.dirname(path.resolve(SAVE)), { recursive: true }); fs.writeFileSync(path.resolve(SAVE), JSON.stringify(data)); }

const model = {
  bones: geo['minecraft:geometry'][0].bones
    .filter(b => ['root', 'knife_R', 'right', 'fp_forearm_r', 'elucidator'].includes(b.name))
    .map(b => ({ name: b.name, parent: b.parent ?? null, pivot: b.pivot, rotation: b.rotation ?? [0, 0, 0],
      cubes: (b.cubes ?? []).map(c => ({ origin: c.origin, size: c.size })) })),
  offset: [0, -0.65, -0.65],
};
const baseline = BASELINE ? JSON.parse(fs.readFileSync(path.resolve(BASELINE))) : null;

const html = `<!DOCTYPE html>
<html lang="zh"><head><meta charset="utf-8"><title>第一人称西洋剑动作预览</title>
<style>
 :root{--bg:#f5f6f8;--panel:#fff;--line:#d8dee6;--ink:#1d2733;--dim:#6b7785;--accent:#2f6fed}
 *{box-sizing:border-box}
 body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.5 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
 header{padding:18px 24px 12px}
 h1{margin:0;font-size:18px}
 .sub{color:var(--dim);font-size:12px;margin-top:4px}
 .wrap{padding:0 24px 32px;display:flex;gap:18px;flex-wrap:wrap;align-items:flex-start}
 .card{background:var(--panel);border:1px solid var(--line);border-radius:10px}
 .stage{padding:14px}
 .stage svg{display:block;border-radius:6px;background:#20262e}
 .controls{padding:14px 16px;min-width:240px;flex:1}
 .row{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px}
 button{font:inherit;padding:6px 12px;border:1px solid var(--line);background:#fff;border-radius:6px;cursor:pointer;color:inherit}
 button.on{background:var(--accent);border-color:var(--accent);color:#fff}
 label{display:block;font-size:12px;color:var(--dim);margin:10px 0 4px}
 input[type=range]{width:100%}
 .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:12px;margin-top:18px}
 .cell{padding:8px}
 .cell svg{display:block;width:100%;border-radius:5px;background:#20262e}
 .cap{font-size:11px;color:var(--dim);margin-top:5px;text-align:center}
 .cmps{display:flex;gap:14px;margin-top:18px;flex-wrap:wrap}
 .cmps>div{flex:1;min-width:280px}
 .legend{font-size:12px;color:var(--dim);margin-top:8px}
 table{width:100%;border-collapse:collapse;font-size:12px;margin-top:8px}
 td,th{padding:4px 8px;border-bottom:1px solid var(--line);text-align:right}
 th{color:var(--dim);font-weight:500}
 td:first-child,th:first-child{text-align:left}
</style></head><body>
<header><h1>第一人称西洋剑动作预览</h1>
<div class="sub">Minecraft 视锥：FOV 70° / 16:9 / 视模型偏移 (0, -0.65, -0.65)。准星为画面正中的十字。${baseline ? '左＝修改前，右＝修改后。' : ''}</div></header>
<pre id="err" style="color:#b00;background:#fff7f7;padding:6px 10px;margin:0 24px;border:1px solid #f3b5b5;border-radius:6px;display:none;font-size:12px;white-space:pre-wrap"></pre>
<div class="wrap">
  <div class="card stage">
    <svg id="main" width="720" height="405" viewBox="0 0 720 405"></svg>
    <div class="legend">蓝＝小臂 / 浅蓝＝上臂 / 灰＝剑 / 虚线＝剑尖轨迹（整段）</div>
  </div>
  <div class="card controls">
    <div class="row" id="clips"></div>
    <label>时间 <span id="tval"></span></label>
    <input type="range" id="time" min="0" max="1" step="0.001" value="0">
    <div class="row" style="margin-top:12px">
      <button id="play">播放</button>
      <button id="trail" class="on">剑尖轨迹</button>
      <button id="grid" class="on">参考网格</button>
    </div>
    <table id="stats"></table>
  </div>
</div>
<div class="wrap">
  <div class="cmps">
    <div><div class="card cell"><svg id="cmpA" width="100%" height="240" viewBox="0 0 400 225"></svg><div class="cap" id="capA"></div></div></div>
    <div><div class="card cell"><svg id="cmpB" width="100%" height="240" viewBox="0 0 400 225"></svg><div class="cap" id="capB"></div></div></div>
  </div>
</div>
<div class="wrap"><div class="grid" id="sheet"></div></div>
<script>
const MODEL=${JSON.stringify(model)};
const LIVE=${JSON.stringify(data)};
const BASE=${JSON.stringify(baseline)};
const FOV=70, NEAR=0.05;
const BONE_BY_NAME={};for(const b of MODEL.bones)BONE_BY_NAME[b.name]=b;
function sampleArr(arr,idx){if(!arr)return [0,0,0];return arr[Math.min(arr.length-1,Math.max(0,idx))];}
function clipAt(clip,name,t){
 const i=Math.round(t*clip.fps);
 return {rot:sampleArr(clip.rotation[name],i),pos:sampleArr(clip.position[name],i)};
}
function rotate(p,d){
 let [x,y,z]=p;const [rx,ry,rz]=d.map(v=>v*Math.PI/180);
 [y,z]=[y*Math.cos(rx)-z*Math.sin(rx),y*Math.sin(rx)+z*Math.cos(rx)];
 [x,z]=[x*Math.cos(ry)+z*Math.sin(ry),-x*Math.sin(ry)+z*Math.cos(ry)];
 [x,y]=[x*Math.cos(rz)-y*Math.sin(rz),x*Math.sin(rz)+y*Math.cos(rz)];
 return [x,y,z];
}
// Mirrors tools/fp_projection.mjs posePoint(): geo pivot is model-space absolute.
function posePoint(clip,boneName,point){
 let p=[-point[0],point[1],point[2]];
 for(let b=BONE_BY_NAME[boneName];b;b=b.parent?BONE_BY_NAME[b.parent]:null){
  const s=clipAt(clip,b.name,currentTime);
  const pivot=[-b.pivot[0],b.pivot[1],b.pivot[2]];
  const rot=s.rot.map((v,i)=>(v+(b.rotation[i]||0))*(i<2?-1:1));
  const pos=[-s.pos[0],s.pos[1],s.pos[2]];
  p=p.map((v,i)=>v-pivot[i]);
  p=rotate(p,rot);
  p=p.map((v,i)=>v+pivot[i]+pos[i]);
 }
 return p;
}
let currentTime=0;
function toCam(model){return [model[0]/16+MODEL.offset[0],model[1]/16+MODEL.offset[1],model[2]/16+MODEL.offset[2]];}
function camPoint(clip,boneName,local){
 const m=posePoint(clip,boneName,local);
 return toCam(m);
}
const FACES=[[0,1,2,3],[5,4,7,6],[4,0,3,7],[1,5,6,2],[4,5,1,0],[3,2,6,7]];
function cubeQuads(clip,boneName,cube){
 const o=cube.origin,s=cube.size;
 const corners=[[o[0],o[1]+s[1],o[2]],[o[0]+s[0],o[1]+s[1],o[2]],[o[0]+s[0],o[1],o[2]],[o[0],o[1],o[2]],
  [o[0],o[1]+s[1],o[2]+s[2]],[o[0]+s[0],o[1]+s[1],o[2]+s[2]],[o[0]+s[0],o[1],o[2]+s[2]],[o[0],o[1],o[2]+s[2]]]
  .map(p=>camPoint(clip,boneName,p));
 return FACES.map(idx=>idx.map(i=>corners[i]));
}
function camCube(center,size){
 const h=size/2;
 const corners=[[-h,-h,h],[-h,h,h],[h,h,h],[h,-h,h],[-h,-h,-h],[-h,h,-h],[h,h,-h],[h,-h,-h]]
  .map(p=>[center[0]+p[0],center[1]+p[1],center[2]+p[2]]);
 return FACES.map(idx=>idx.map(i=>corners[i]));
}
function project(p,W,H){
 const f=(H/2)/Math.tan(FOV*Math.PI/360);
 return [W/2+p[0]/-p[2]*f,H/2-p[1]/-p[2]*f];
}
function clipPoly(quad){
 const out=[];
 for(let i=0;i<quad.length;i++){
  const a=quad[i],b=quad[(i+1)%quad.length];
  const inA=a[2]<=-NEAR,inB=b[2]<=-NEAR;
  if(inA)out.push(a);
  if(inA!==inB){const t=(-NEAR-a[2])/(b[2]-a[2]);out.push(a.map((v,j)=>v+(b[j]-v)*t));}
 }
 return out;
}
const norm=v=>{const l=Math.hypot(...v)||1;return v.map(x=>x/l)};
const sub=(a,b)=>a.map((v,i)=>v-b[i]);
const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
const dot=(a,b)=>a.reduce((s,v,i)=>s+v*b[i],0);
function drawView(svg,clip,t,opts={}){
 const errEl=document.getElementById('err');
 const _step=[];function fail(e){errEl.textContent='drawView 错误: '+e.message+' @ '+(_step.join('>')||'init')+' / '+(e.stack||'').split(String.fromCharCode(10)).slice(1,3).join(' | ');errEl.style.display='block';return {hand:[0,0,0],tip:[0,0,0]};}
 try{
 const W=svg.viewBox.baseVal.width,H=svg.viewBox.baseVal.height;
 currentTime=t;
 while(svg.firstChild)svg.removeChild(svg.firstChild);
 const NS='http://www.w3.org/2000/svg';
 const el=(n,a)=>{const e=document.createElementNS(NS,n);for(const k in a)e.setAttribute(k,a[k]);svg.appendChild(e);return e};
 _step.push('init');
 if(opts.grid){_step.push('grid');
  const f=(H/2)/Math.tan(FOV*Math.PI/360);
  for(let gz=-1;gz>=-8;gz--){
   const y=(0.0)/ -gz;
   const pts=[];
   for(let gx=-4;gx<=4;gx++){const p=[gx*1,-1.6,gz];const s=project(p,W,H);pts.push(s.join(','));}
   el('polyline',{points:pts.join(' '),fill:'none',stroke:'#31404d','stroke-width':gz%2?0.6:1});
  }
  for(let gx=-4;gx<=4;gx++){
   const pts=[];
   for(let gz=-1;gz>=-8;gz-=0.5){const p=[gx*1,-1.6,gz];pts.push(project(p,W,H).join(','));}
   el('polyline',{points:pts.join(' '),fill:'none',stroke:'#2b3945','stroke-width':0.6});
  }
 }
 _step.push('cross');
 el('rect',{x:0,y:0,width:W,height:H,fill:'none'});
 const cx=W/2,cy=H/2;
 el('line',{x1:cx-7,y1:cy,x2:cx+7,y2:cy,stroke:'#7fd3ff','stroke-width':1.2,opacity:.85});
 el('line',{x1:cx,y1:cy-7,x2:cx,y2:cy+7,stroke:'#7fd3ff','stroke-width':1.2,opacity:.85});
 _step.push('crosshair');
 if(opts.trail){_step.push('trail');
  const pts=[];
  const steps=Math.max(2,Math.round(clip.length*40));
  for(let i=0;i<=steps;i++){
   const tt=clip.length*i/steps;
   currentTime=tt;
   const tip=camPoint(clip,'elucidator',[BONE_BY_NAME.elucidator.pivot[0],BONE_BY_NAME.elucidator.pivot[1]+15,BONE_BY_NAME.elucidator.pivot[2]]);
   currentTime=t;
   if(tip[2]<=-NEAR)pts.push(project(tip,W,H).join(','));
  }
  if(pts.length>1)el('polyline',{points:pts.join(' '),fill:'none',stroke:'#ffcf5c','stroke-width':1.6,'stroke-dasharray':'5 4',opacity:.9});
  _step[ _step.length-1]='trail-end';
 }
 _step.push('parts');const parts=[];
 _step.push('parts-cubes');
 for(const [boneName,cube] of [['right',{origin:[-5.5,-2,-2],size:[3,6,4]}],['fp_forearm_r',{origin:[-5.5,-8,-2],size:[3,6,4]}]]){
  for(const q of cubeQuads(clip,boneName,cube))parts.push({q,color:boneName==='right'?'#c9d6e2':'#8fb3d9'});
 }
 _step[ _step.length-1]='parts-arm-cubes';
 const hand=camPoint(clip,'elucidator',BONE_BY_NAME.elucidator.pivot);
 for(const q of camCube(hand,0.14))parts.push({q,color:'#e8c9a0'});
 const blade=BONE_BY_NAME.elucidator;
 currentTime=t;
 for(const cube of blade.cubes)for(const q of cubeQuads(clip,'elucidator',cube))parts.push({q,color:'#d7dee6'});
 _step[ _step.length-1]='parts-blade';
 const ordered=parts.map(p=>{
  const valid=p.q.filter(v=>v[2]<=-NEAR);
  const depth=valid.length?valid.reduce((s,v)=>s-v[2],0)/valid.length:Infinity;
  return {...p,depth};
 }).filter(p=>isFinite(p.depth)).sort((a,b)=>b.depth-a.depth);
 _step[ _step.length-1]='parts-fill';
 for(const p of ordered){_step.push('face');
  const poly=clipPoly(p.q);
  if(poly.length<3){_step.pop();continue;}
  const n=norm(cross(sub(poly[1],poly[0]),sub(poly[2],poly[0])));
  const shade=0.55+0.45*Math.abs(dot(n,norm([0.25,0.8,0.55])));
  const c=p.color;
  const rgb=[parseInt(c.slice(1,3),16),parseInt(c.slice(3,5),16),parseInt(c.slice(5,7),16)].map(v=>Math.round(v*shade));
  el('polygon',{points:poly.map(v=>project(v,W,H).join(',')).join(' '),fill:'rgb('+rgb.join(',')+')',stroke:'rgba(0,0,0,.25)','stroke-width':0.5});
  _step.pop();
 }
 _step.push('tip');
 const tip=camPoint(clip,'elucidator',[blade.pivot[0],blade.pivot[1]+15,blade.pivot[2]]);
 if(tip[2]<=-NEAR){const s=project(tip,W,H);el('circle',{cx:s[0],cy:s[1],r:3,fill:'#ffcf5c'});}
 return {hand:camPoint(clip,'elucidator',blade.pivot),tip};
 }catch(e){return fail(e);}
}
function screenPos(p,W,H){const f=(H/2)/Math.tan(FOV*Math.PI/360);return [p[0]/-p[2]*f/((H/2))*100,-p[1]/-p[2]*f/((H/2))*100];}
let clipName='static_idle',playing=false,trail=true,grid=true;
const clipsEl=document.getElementById('clips');
const labels={static_idle:'待机',attack1:'第1招 上挑',attack2:'第2招 下劈',attack3:'第3招 斜劈',attack4:'第4招 反斜劈',rapier_thrust_hit:'刺击',draw:'拔刀'};
for(const name of Object.keys(LIVE)){
 const b=document.createElement('button');b.textContent=labels[name]||name;
 b.onclick=()=>{clipName=name;currentTime=0;document.getElementById('time').value=0;sync();};
 b.dataset.name=name;clipsEl.appendChild(b);
}
const timeEl=document.getElementById('time');
timeEl.oninput=()=>{currentTime=Number(timeEl.value)*LIVE[clipName].length;sync()};
document.getElementById('play').onclick=e=>{playing=!playing;e.target.textContent=playing?'暂停':'播放';e.target.classList.toggle('on',playing);if(playing)tick()};
document.getElementById('trail').onclick=e=>{trail=!trail;e.target.classList.toggle('on',trail);sync()};
document.getElementById('grid').onclick=e=>{grid=!grid;e.target.classList.toggle('on',grid);sync()};
function sync(){
 for(const b of clipsEl.children)b.classList.toggle('on',b.dataset.name===clipName);
 const clip=LIVE[clipName];
 document.getElementById('tval').textContent=currentTime.toFixed(3)+' / '+clip.length.toFixed(3)+' s';
 timeEl.value=String(currentTime/clip.length);
 const main=document.getElementById('main');
 const r=drawView(main,clip,currentTime,{trail,grid});
 const W=main.viewBox.baseVal.width,H=main.viewBox.baseVal.height;
 const fmt=p=>'('+p.map(v=>v.toFixed(2)).join(', ')+')';
 const sp=screenPos(r.hand,W,H),tp=screenPos(r.tip,W,H);
 document.getElementById('stats').innerHTML=
  '<tr><th>项目</th><th>相机空间 (m)</th><th>屏幕位置 %</th></tr>'+
  '<tr><td>手（握把）</td><td>'+fmt(r.hand)+'</td><td>x '+sp[0].toFixed(0)+' / y '+sp[1].toFixed(0)+'</td></tr>'+
  '<tr><td>剑尖</td><td>'+fmt(r.tip)+'</td><td>x '+tp[0].toFixed(0)+' / y '+tp[1].toFixed(0)+'</td></tr>';
 const caps=['起手','发力中','接触','收招'];
 if(BASE){
  drawView(document.getElementById('cmpA'),BASE[clipName],Math.min(currentTime,BASE[clipName].length),{trail,grid:false});
  document.getElementById('capA').textContent='修改前 · '+currentTime.toFixed(2)+'s';
 }
 drawView(document.getElementById('cmpB'),clip,currentTime,{trail,grid:false});
 document.getElementById('capB').textContent=(BASE?'修改后 · ':'')+currentTime.toFixed(2)+'s';
}
function tick(){
 if(!playing)return;
 const clip=LIVE[clipName];
 currentTime+=1/60;
 if(currentTime>clip.length)currentTime=0;
 sync();
 requestAnimationFrame(()=>setTimeout(tick,1000/60));
}
const sheet=document.getElementById('sheet');
const marks={static_idle:[0],attack1:[0.105,0.19],attack2:[0.105,0.19],attack3:[0.105,0.19],attack4:[0.105,0.19],rapier_thrust_hit:[0.14,0.25],draw:[0.4]};
for(const name of Object.keys(LIVE)){
 for(const t of (marks[name]||[0])){
  const d=document.createElement('div');d.className='card cell';
  const s=document.createElementNS('http://www.w3.org/2000/svg','svg');
  s.setAttribute('viewBox','0 0 400 225');s.setAttribute('width','100%');s.setAttribute('height','180');
  d.appendChild(s);const cap=document.createElement('div');cap.className='cap';
  cap.textContent=(labels[name]||name)+' · '+t.toFixed(3)+'s';d.appendChild(cap);sheet.appendChild(d);
  drawView(s,LIVE[name],t,{trail:true,grid:false});
 }
}
sync();
</script></body></html>`;
fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, html);
console.log('FP rapier preview:', OUT, baseline ? '(with baseline)' : '');
