#!/usr/bin/env node
// Render the best poses visually for review.
import {posePoint} from '/Users/mac/Downloads/minecraft 1.21.11/actioncombat/tools/fp_projection.mjs';
import fs from 'node:fs';
const REPO='/Users/mac/Downloads/minecraft 1.21.11/actioncombat';
const OUT_DIR=`${REPO}/docs/preview/poses`;
if(!fs.existsSync(OUT_DIR))fs.mkdirSync(OUT_DIR,{recursive:true});
const g=new Map(JSON.parse(fs.readFileSync(`${REPO}/src/main/resources/assets/actioncombat/geckolib/models/fp_singlesword.geo.json`,"utf8"))["minecraft:geometry"][0].bones.map(b=>[b.name,b]));
const OFF=[0,-0.65,-0.65];
const FOV=70;
function cam(c,bone,p){const q=posePoint(g,c,bone,0,p);return [q[0]/16+OFF[0],q[1]/16+OFF[1],q[2]/16+OFF[2]];}
function f(v){return v.map(x=>x.toFixed(3)).join(", ");}
function makeClip(rotMap){const c={bones:{}};for(const [b,v] of Object.entries(rotMap))c.bones[b]={rotation:v};return c;}
function renderSvg(c,label,W=520,H=400){
  const NS='http://www.w3.org/2000/svg';
  const f_proj=(H/2)/Math.tan(FOV*Math.PI/360);
  function project(p){const z=-p[2];if(z<=0.05)return null;return [W/2+p[0]/z*f_proj, H/2-p[1]/z*f_proj];}
  function cubeCorners(bn,cube){const pv=g.get(bn).pivot;const x0=pv[0]+cube.origin[0],x1=x0+cube.size[0],y0=pv[1]+cube.origin[1],y1=y0+cube.size[1],z0=pv[2]+cube.origin[2],z1=z0+cube.size[2];return[[x0,y0,z0],[x1,y0,z0],[x1,y1,z0],[x0,y1,z0],[x0,y0,z1],[x1,y0,z1],[x1,y1,z1],[x0,y1,z1]];}
  function quad(bn,cube){const cs=cubeCorners(bn,cube);const ws=cs.map(p=>{const q=posePoint(g,c,bn,0,p);return [q[0]/16+OFF[0],q[1]/16+OFF[1],q[2]/16+OFF[2]];});return[[0,1,2,3],[4,5,6,7],[0,1,5,4],[1,2,6,5],[2,3,7,6],[3,0,4,7]].map(f=>f.map(i=>ws[i]));}
  let svg=`<svg xmlns="${NS}" viewBox="0 0 ${W} ${H}"><rect width="${W}" height="${H}" fill="#10171f"/>`;
  // Grid
  svg+=`<g stroke="#1e2a36" stroke-width="0.5" fill="none">`;
  for(let gz=-1;gz>=-8;gz-=0.5){const pts=[];for(let gx=-4;gx<=4;gx+=0.5){const w=[gx*1,-1.6,gz];const s=project(w);if(s)pts.push(s.join(','));}if(pts.length>1)svg+=`<polyline points="${pts.join(' ')}"/>`;}
  for(let gx=-4;gx<=4;gx+=0.5){const pts=[];for(let gz=-1;gz>=-8;gz-=0.5){const w=[gx*1,-1.6,gz];const s=project(w);if(s)pts.push(s.join(','));}if(pts.length>1)svg+=`<polyline points="${pts.join(' ')}"/>`;}
  svg+=`</g>`;
  svg+=`<line x1="${W/2-8}" y1="${H/2}" x2="${W/2+8}" y2="${H/2}" stroke="#7fd3ff" stroke-width="1.2" opacity="0.7"/><line x1="${W/2}" y1="${H/2-8}" x2="${W/2}" y2="${H/2+8}" stroke="#7fd3ff" stroke-width="1.2" opacity="0.7"/>`;
  // Direction arrow from hand toward tip
  const hand=cam(c,"elucidator",g.get("elucidator").pivot);
  const tip=cam(c,"elucidator",[g.get("elucidator").pivot[0],g.get("elucidator").pivot[1]+15,g.get("elucidator").pivot[2]]);
  const hp=project(hand),tp=project(tip);
  if(hp&&tp){
    svg+=`<line x1="${hp[0].toFixed(1)}" y1="${hp[1].toFixed(1)}" x2="${tp[0].toFixed(1)}" y2="${tp[1].toFixed(1)}" stroke="#ffcf5c" stroke-width="2" stroke-dasharray="4 3"/>`;
    svg+=`<circle cx="${tp[0].toFixed(1)}" cy="${tp[1].toFixed(1)}" r="3" fill="#ffcf5c"/>`;
  }
  let polys=[];
  for(const [bn,color] of [["right","#c9d6e2"],["fp_forearm_r","#8fb3d9"],["elucidator","#d7dee6"]]){
    const bdef=g.get(bn);if(!bdef.cubes)continue;
    for(const cube of bdef.cubes){const q=quad(bn,cube);for(const face of q){const pf=face.map(project).filter(p=>p);if(pf.length===4){const avgZ=face.reduce((s,p)=>s+p[2],0)/4;const sh=Math.max(0.4,Math.min(1.0,1+avgZ*0.4));polys.push(`<polygon points="${pf.map(p=>p.join(',')).join(' ')}" fill="${color}" fill-opacity="${sh.toFixed(2)}" stroke="rgba(0,0,0,0.4)" stroke-width="0.6"/>`);}}}
  polys.sort(()=>0);
  svg+=polys.join('')+`</svg>`;
  return svg;
}
}

const candidates = [
  ["current (broken)", {right:[-45,-22,-41],fp_forearm_r:[-69,0,0],elucidator:[145,-46,0]}],
  ["blade-only-fix (math-best)", {right:[-45,-22,-41],fp_forearm_r:[-69,0,0],elucidator:[150,-140,45]}],
  ["clean forward", {right:[-45,-22,-41],fp_forearm_r:[-69,0,0],elucidator:[180,0,0]}],
  ["straight forward attempt", {right:[-60,-22,-41],fp_forearm_r:[-90,0,0],elucidator:[0,0,0]}],
  ["rest-thrust-attempt", {right:[-15,-30,-15],fp_forearm_r:[-110,0,0],elucidator:[60,0,0]}],
  ["low-grip-forward", {right:[-30,-22,-41],fp_forearm_r:[-90,0,0],elucidator:[0,0,0]}],
];

let html='<!doctype html><html><head><meta charset="utf-8"><title>FP 姿态对比</title>';
html+='<style>body{background:#0c1218;color:#eee;font-family:system-ui;margin:12px}h2{margin:6px 0}.grid{display:grid;grid-template-columns:repeat(2,1fr);gap:10px}.cell{background:#10171f;padding:8px;border-radius:6px}.info{font-family:monospace;font-size:12px;color:#9bb3c8}</style></head><body>';
html+='<h1>FP 姿态对比 v2 — 目标是手在下方居中、剑指向敌人</h1>';
html+='<p>黄色虚线是剑刃方向（手到剑尖）。每张图都标了骨骼旋转。</p><div class="grid">';
for(const [name,rot] of candidates){
  const svg=renderSvg(makeClip(rot),name);
  const fname=name.replace(/[^a-z0-9]/gi,'_')+'.svg';
  fs.writeFileSync(`${OUT_DIR}/${fname}`,svg);
  const c=makeClip(rot);
  const hand=cam(c,"elucidator",g.get("elucidator").pivot);
  const tip=cam(c,"elucidator",[g.get("elucidator").pivot[0],g.get("elucidator").pivot[1]+15,g.get("elucidator").pivot[2]]);
  const dir=[tip[0]-hand[0],tip[1]-hand[1],tip[2]-hand[2]];
  const len=Math.sqrt(dir[0]**2+dir[1]**2+dir[2]**2);
  const n=dir.map(v=>(v/len).toFixed(2));
  html+=`<div class="cell"><h2>${name}</h2><div class="info">r=${JSON.stringify(rot.right)}<br>f=${JSON.stringify(rot.fp_forearm_r)}<br>e=${JSON.stringify(rot.elucidator)}</div><div class="info">hand[${f(hand)}] tip[${f(tip)}] dir[${n.join(',')}]</div><img src="poses/${fname}" style="width:100%"></div>`;
}
html+='</div></body></html>';
fs.writeFileSync(`${REPO}/docs/preview/pose-grid.html`,html);
console.log(`Wrote: ${REPO}/docs/preview/pose-grid.html`);
