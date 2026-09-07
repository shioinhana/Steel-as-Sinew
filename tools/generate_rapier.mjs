// Workbench FSVOXEL 1 is the source of truth for the rapier clips.
import fs from 'node:fs';
import path from 'node:path';
const ROOT=path.resolve(import.meta.dirname,'..'), A=path.join(ROOT,'src/main/resources/assets/actioncombat/geckolib/animations');
const source=path.join(ROOT,'assets/rapier-workbench');
// Workbench uses XYZ Euler; GeckoLib applies ZYX after mirroring X/Y.
function gecko(v){
 const [x,y,z]=v.map(n=>n*Math.PI/180),sx=Math.sin(x),cx=Math.cos(x),sy=Math.sin(y),cy=Math.cos(y),sz=Math.sin(z),cz=Math.cos(z);
 const a=Math.atan2(sx*cz+cx*sy*sz,cx*cy),b=Math.asin(Math.max(-1,Math.min(1,cx*sy*cz-sx*sz))),c=Math.atan2(cx*sz+sx*sy*cz,cy*cz);
 return [-a,-b,c].map(n=>Math.round(n*180/Math.PI*1e6)/1e6);
}
function readClip(name){
 const lines=fs.readFileSync(path.join(source,name+'.motion.fsvox'),'utf8').trim().split('\n');
 if(lines[0]!=='FSVOXEL\t1\tmotion')throw Error('Expected FSVOXEL 1 motion');
 const clip=JSON.parse(lines.find(l=>l.startsWith('clip\t')).slice(5));
 const bones={};
 for(const [bone,keys] of Object.entries(clip.tracks)){
  bones[bone]={rotation:Object.fromEntries(keys.map(k=>[k.time.toFixed(4),gecko(k.rotation)]))};
 }
 return {loop:clip.loop==='loop',animation_length:clip.duration,bones};
}
const single=JSON.parse(fs.readFileSync(path.join(A,'third_person_singlesword.animation.json')));
const idle=readClip('rapier_idle');
single.animations.static_idle=idle;
const upper=new Set(['upper_body','head','upper_arm_r','forearm_r','hand_r','hand_item_r','upper_arm_l','forearm_l','hand_l']);
function overlay(c){return {...structuredClone(c),bones:Object.fromEntries(Object.entries(c.bones).filter(([b])=>upper.has(b)))}}
for(const [name,c] of Object.entries(single.animations)){
 if(/^(walk|run|crouch|jump|fall|idle_upper|aiming|defend|inspect)/.test(name)){
  for(const [bone,ch] of Object.entries(idle.bones)){
   if(!upper.has(bone))continue;
   const first=Object.values(ch.rotation)[0];
   c.bones[bone]={rotation:first};
  }
 }
}
for(const [i,name] of ['attack1','attack3','attack2','attack4'].entries()){
 const c=readClip('rapier_attack'+(i+1));single.animations[name]=c;single.animations[name+'_upper']=overlay(c);
}
for(const name of ['rapier_thrust_hit','rapier_thrust_miss']){
 const c=readClip(name);single.animations[name]=c;single.animations[name+'_upper']=overlay(c);
}
for(const name of ['draw','put_away']) {
 const duration=single.animations[name].animation_length;
 const bones={};
 for(const [bone,ch] of Object.entries(idle.bones)) {
  const pose=Object.values(ch.rotation)[0];
  bones[bone]={rotation:name==='draw'?{'0.0':[0,0,0],[String(duration*.75)]:pose,[String(duration)]:pose}:{'0.0':pose,[String(duration)]:[0,0,0]}};
 }
 single.animations[name]={animation_length:duration,loop:false,bones};
}
fs.writeFileSync(path.join(A,'third_person_singlesword.animation.json'),JSON.stringify(single,null,2)+'\n');
// Legacy FP bones differ. Derive its compact fencing movements from the same wrist/elbow keys.
const fp=JSON.parse(fs.readFileSync(path.join(A,'fp_singlesword.animation.json')));
function fpClip(c){
 const keys=c.bones.forearm_r.rotation;
 return {loop:c.loop,animation_length:c.animation_length,bones:{
  right:{rotation:Object.fromEntries(Object.entries(keys).map(([t,v])=>[t,[-65+(v[0]+10)*.4,0,0]])),position:[0,0,0]},
  left:{rotation:[0,0,-18],position:[-2,-5,0]},elucidator:{rotation:Object.fromEntries(Object.entries(keys).map(([t,v])=>[t,[10-(v[0]+10)*.4,0,0]])),position:Object.fromEntries(Object.entries(keys).map(([t,v])=>[t,[0,0,(v[0]+10)*.14]]))}
 }};
}
for(const name of ['static_idle','attack1','attack2','attack3','attack4','rapier_thrust_hit','rapier_thrust_miss'])fp.animations[name]=fpClip(single.animations[name]);
fs.writeFileSync(path.join(A,'fp_singlesword.animation.json'),JSON.stringify(fp,null,2)+'\n');
for(const file of ['third_person_doublesword','fp_doublesword']){
 const doc=JSON.parse(fs.readFileSync(path.join(A,file+'.animation.json')));
 const base=structuredClone(doc.animations.defend_upper||doc.animations.defend_1||doc.animations.static_idle);
 base.loop=false;base.animation_length=.25;
 // A short torso/forearm drive while the guard is raised.
 for(const bone of ['upper_body','upper_arm_r','upper_arm_l'])base.bones[bone]={rotation:{'0.0':[0,0,0],'0.08':[18,0,0],'0.25':[0,0,0]}};
 if(file==='fp_doublesword'){
  const map={upper_arm_r:'right_arm',upper_arm_l:'left_arm',forearm_r:'right_sleve_bottom',forearm_l:'left_arm_bottom',hand_item_r:'right_arm_item',hand_item_l:'left_arm_item'};
  base.bones=Object.fromEntries(Object.entries(base.bones).filter(([b])=>b!=='head').map(([b,c])=>[map[b]||b,c]));
 }
 doc.animations.guard_bash=base;doc.animations.guard_bash_upper=structuredClone(base);
 fs.writeFileSync(path.join(A,file+'.animation.json'),JSON.stringify(doc,null,2)+'\n');
}
console.log('Workbench rapier: stance, 4 attacks, hit/miss thrust, dual guard bash exported');
