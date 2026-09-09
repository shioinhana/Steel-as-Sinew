import assert from 'node:assert/strict';
import fs from 'node:fs';import path from 'node:path';import{fileURLToPath}from'node:url';
import{posePoint,sample,vector}from'./fp_projection.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const dir=path.join(root,'src/main/resources/assets/actioncombat/geckolib');
const sub=(a,b)=>a.map((v,i)=>v-b[i]),len=a=>Math.hypot(...a),dot=(a,b)=>a.reduce((s,v,i)=>s+v*b[i],0);
let checks=0;
for(const fp of[false,true]){
const id=fp?'fp_spear':'third_person_spear',model=fp?'fp_spear':'player_longsword';
const g=new Map(JSON.parse(fs.readFileSync(path.join(dir,'models',model+'.geo.json')))['minecraft:geometry'][0].bones.map(b=>[b.name,b]));
const a=JSON.parse(fs.readFileSync(path.join(dir,'animations',id+'.animation.json'))).animations;
const item=fp?'elucidator':'hand_item_r',lh=fp?'fp_hand_l':'hand_l',rf=fp?'fp_forearm_r':'forearm_r';
const point=(c,b,t,p=g.get(b).pivot)=>posePoint(g,c,b,t,p);
const axis=(c,t)=>{const p=g.get(item).pivot.map((v,i)=>v+(fp?(i===1?1:0):(i===2?-1:0)));return sub(point(c,item,t,p),point(c,item,t));};
for(const name of['attack1','attack2','attack4','defend']){
 const c=a[name];const from=name==='defend'?.18:.1,to=name==='defend'?.18:.23;
 for(let t=from;t<=to+.00001;t+=1/240){
  const hand=point(c,item,t),left=point(c,lh,t),d=axis(c,t),expected=hand.map((v,i)=>v+d[i]*12);
  assert.ok(len(sub(left,expected))<.035,`${id}/${name}@${t.toFixed(3)} left grip error ${len(sub(left,expected))}`);
  const wrist=point(c,rf,t,g.get(item).pivot);
  assert.ok(len(sub(hand,wrist))<.002,`${id}/${name} rear grip detached`);checks++;
 }
}
const reach=a.attack3,t=.18,d=axis(reach,t),hand=point(reach,item,t),left=point(reach,lh,t);
assert.ok(len(sub(left,hand))>8,`${id} long thrust must release left hand`);
const shoulder=point(reach,fp?'right':'upper_arm_r',t),wrist=point(reach,rf,t,g.get(item).pivot);
assert.ok(len(sub(wrist,shoulder))>11.2,`${id} long thrust arm not extended`);
assert.ok(d[2]<-.99,`${id} long thrust must face target`);
if(!fp){const head=g.get('head'),f=point(reach,'head',t,head.pivot.map((v,i)=>v-(i===2?1:0)));
 assert.ok(sub(f,point(reach,'head',t))[2]<-.99,'side-on head does not face target');
 assert.ok(sample(reach.bones.leg_r.rotation,t,[0,0,0])[0]<-30,'right leg must lead');}
assert.equal(a.run_start.animation_length,.2);assert.equal(a.run_start.loop,false);
for(const[name,c]of Object.entries(a))for(const[b,v]of Object.entries(c.bones))for(const[ch,keys]of Object.entries(v)){
 assert.ok(g.has(b),`${id}/${name}: unknown bone ${b}`);
 for(const value of (Array.isArray(keys)?[keys]:Object.values(keys)))assert.ok(vector(value).every(Number.isFinite),`${id}/${name}/${b}/${ch} invalid key`);
}
}
console.log(`Spear ${checks} grip samples: both hands on shaft, single-hand long reach, head facing, right-leg lunge and 0.2s backstep passed`);
