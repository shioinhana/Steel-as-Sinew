import fs from 'node:fs';
import assert from 'node:assert/strict';
import {posePoint} from './fp_projection.mjs';
const root=new URL('../',import.meta.url),read=p=>JSON.parse(fs.readFileSync(new URL(p,root)));
const P='src/main/resources/assets/actioncombat/geckolib/';
const sub=(a,b)=>a.map((x,i)=>x-b[i]),mul=(a,s)=>a.map(x=>x*s);
const dot=(a,b)=>a.reduce((s,x,i)=>s+x*b[i],0),len=a=>Math.hypot(...a),norm=a=>mul(a,1/len(a));
const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
const angle=(a,b)=>Math.acos(Math.max(-1,Math.min(1,dot(norm(a),norm(b)))))*180/Math.PI;
let samples=0;
for(const fp of [false,true]){
 const g=new Map(read(P+'models/'+(fp?'fp_singlesword':'player_longsword')+'.geo.json')['minecraft:geometry'][0].bones.map(b=>[b.name,b]));
 const clips=read(P+'animations/'+(fp?'fp_singlesword':'third_person_singlesword')+'.animation.json').animations;
 const u=fp?'right':'upper_arm_r',e=fp?'fp_forearm_r':'forearm_r',h=fp?'elucidator':'hand_item_r';
 const point=(c,b,t,p=g.get(b).pivot)=>posePoint(g,c,b,t,p);
 const blade=(c,t)=>{const p=g.get(h).pivot;return sub(point(c,h,t,p.map((x,i)=>x+(i===(fp?1:2)?(fp?11.43536:-11.43536):0))),point(c,h,t));};
 const tip=(c,t)=>point(c,h,t).map((x,i)=>x+blade(c,t)[i]);
 const segment=(c,a,b,t)=>sub(point(c,b,t),point(c,a,t));
 for(let i=1;i<=4;i++){
  const c=clips['attack'+i];assert.equal(c.animation_length,.4);
  const shoulderArc=angle(segment(c,u,e,.105),segment(c,u,e,.19));
  const forearmArc=angle(segment(c,e,h,.105),segment(c,e,h,.19));
  assert.ok(i<3?shoulderArc<=10:shoulderArc>=40,`attack${i}: shoulder arc ${shoulderArc}`);
  assert.ok(forearmArc>80,`attack${i}: insufficient forearm sweep`);
  for(let t=.11;t<.185;t+=.002){
   const p=g.get(h).pivot,origin=point(c,h,t);
   // +Z is the FP sprite face normal; TP mounts that sprite with -90 X, so +Y.
   const normal=norm(sub(point(c,h,t,p.map((x,k)=>x+(k===(fp?2:1)?1:0))),origin));
   const velocity=norm(sub(tip(c,t+.0001),tip(c,t-.0001)));
   assert.ok(Math.abs(dot(normal,velocity))<.035,`attack${i}: blade slaps sideways at ${t}`);
   if(!fp)assert.ok(len(sub(point(c,'hand_l',t),point(c,'hand_l',0)))<.002,'free hand stays behind during cuts');
   samples++;
  }
 }
 const x0=tip(clips.attack3,.105),x1=tip(clips.attack3,.19),y0=tip(clips.attack4,.105),y1=tip(clips.attack4,.19);
 assert.ok(x0[0]<x1[0]&&x0[1]>x1[1]&&y0[0]>y1[0]&&y0[1]>y1[1],'diagonal tips must make opposing downward cuts');
 function ccw(a,b,c){return(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);}
 assert.ok(ccw(x0,x1,y0)*ccw(x0,x1,y1)<0&&ccw(y0,y1,x0)*ccw(y0,y1,x1)<0,'two cuts must intersect as X');
 for(const name of ['rapier_thrust_hit','rapier_thrust_miss']){
  const c=clips[name],gather=point(c,h,.16),extend=point(c,h,.25);
  assert.ok(gather[0]<0,'right hand gathers across the body to left chest');
  assert.ok(gather[2]>extend[2]+5,'thrust must retract then extend');
  assert.ok(angle(segment(c,u,e,.25),segment(c,e,h,.25))<1,'elbow must straighten');
  assert.ok(angle(segment(c,e,h,.25),blade(c,.25))<1,'extended blade parallel to forearm');
  assert.ok(angle(segment(c,e,h,.16),blade(c,.16))>25,'gathered wrist must angle blade toward enemy');
  if(!fp)assert.ok(dot(sub(point(c,'hand_l',.25),point(c,'hand_l',.16)),[Math.sin(65*Math.PI/180),0,Math.cos(65*Math.PI/180)])>1.8,'left hand balances behind the side-on torso');
 }
 // Explicit new downstroke: shoulder forward, elbow square, then all three segments align.
 const down=clips.attack2;
 assert.ok(Math.abs(angle(segment(down,u,e,.105),segment(down,e,h,.105))-90)<.01,'downstroke starts at 90 degree elbow');
 assert.ok(angle(segment(down,u,e,.105),[0,0,-1])<.01,'downstroke upper arm starts pointing forward');
 assert.ok(angle(segment(down,u,e,.19),segment(down,e,h,.19))<.01,'downstroke ends with straight arm');
 assert.ok(angle(segment(down,e,h,.19),blade(down,.19))<.01,'downstroke blade extends the arm line');
 assert.ok(blade(down,.19)[1]<0,'downstroke carries slightly down through the finish');
 if(!fp){
  const c=clips.static_idle;
  const p=g.get('head').pivot;
  const facing=sub(point(c,'head',0,p.map((v,i)=>v-(i===2?1:0))),point(c,'head',0));
  assert.ok(angle(facing,[0,0,-1])<.01,'head looks directly at enemy');
  const body=g.get('upper_body').pivot;
  const bodyFacing=sub(point(c,'upper_body',0,body.map((v,i)=>v-(i===2?1:0))),point(c,'upper_body',0));
  assert.ok(Math.abs(angle(bodyFacing,[0,0,-1])-65)<.01,'torso stands side-on at 65 degrees');
  assert.ok(angle(segment(c,u,e,0),segment(c,e,h,0))<31,'right arm stays nearly extended in guard');
 }
 const c=clips.put_away,ratio=c.animation_length/1.65,insertAt=1.15*ratio,homeAt=1.58*ratio;
 assert.equal(c.animation_length,1.10,'sheath is faster without truncating insertion');
 const start=point(c,h,insertAt),end=point(c,h,homeAt),axis=norm(sub(end,start));
 const opening=fp?[-3,3,-1.5]:[-3,15,-1];
 assert.ok(len(sub(tip(c,insertAt),opening))<.005,'tip must first meet the scabbard opening');
 assert.ok(len(sub(end,opening))<.005,'hilt must finish at the left waist opening');
 for(let t=insertAt;t<=homeAt;t+=.005){
  assert.ok(len(cross(sub(point(c,h,t),start),axis))<.02,'sheathing must translate on one straight line');
  assert.ok(angle(blade(c,t),axis)<.1,'sheathing must not rotate the blade');samples++;
 }
 for(const name of ['static_idle','attack1','attack2','attack3','attack4','rapier_thrust_hit','rapier_thrust_miss','put_away']){
  const c=clips[name];for(let t=0;t<c.animation_length;t+=.005){
   const grip=point(c,h,t),hand=fp?point(c,e,t,g.get(h).pivot):point(c,'hand_r',t);
   assert.ok(len(sub(grip,hand))<.001,`${name}: item detached from hand`);samples++;
  }
 }
 if(fp){
  const renderer=fs.readFileSync(new URL('src/main/java/com/actioncombat/client/FpViewmodelRenderer.java',root),'utf8');
  const off=['X','Y','Z'].map(axis=>Number(renderer.match(new RegExp(`SINGLE_OFFSET_${axis} = (-?[\\d.]+)F`))[1]));
  const camera=p=>p.map((x,i)=>x/16+off[i]);
  const hilt=camera(point(clips.static_idle,h,0)),elbow=camera(point(clips.static_idle,e,0)),end=camera(tip(clips.static_idle,0));
  for(const p of [hilt,elbow,end])assert.ok(Math.abs(p[0])<.005,'idle arm and blade share screen centre');
  assert.ok(hilt[1]<-.25&&hilt[1]>-.5,'guard below crosshair');
  const projected=p=>[p[0]/-p[2],p[1]/-p[2]];
  const handAt=(name,t)=>projected(camera(point(clips[name],h,t)));
  const tipAt=(name,t)=>projected(camera(tip(clips[name],t)));
  const a=tipAt('attack1',.105),b=tipAt('attack1',.19);
  const risingAngle=Math.atan2(b[0]-a[0],b[1]-a[1])*180/Math.PI;
  assert.ok(risingAngle>12&&risingAngle<18,`rising cut must read as 15 degrees on screen: ${risingAngle}`);
  const ha=handAt('attack3',.105),hb=handAt('attack3',.19),hc=handAt('attack4',.105),hd=handAt('attack4',.19);
  assert.ok(ccw(ha,hb,hc)*ccw(ha,hb,hd)<0&&ccw(hc,hd,ha)*ccw(hc,hd,hb)<0,'visible HAND paths form X');
  for(const[name]of [['attack3'],['attack4']]){
   const a=handAt(name,.105),b=handAt(name,.19);
   assert.ok(Math.abs(b[0]-a[0])>.30&&Math.abs(b[1]-a[1])>.30,'hand must travel across screen, not just rotate sword');
  }
  for(const name of ['static_idle','attack1','attack2','attack3','attack4']){
   for(const t of [0,.105,.145,.19]){
    for(const joint of [e,h]){
     const q=camera(point(clips[name],joint,t));
     assert.ok(q[2]<-.1&&Math.abs(q[1]/-q[2])<Math.tan(70*Math.PI/360),'elbow and wrist remain in the 70 degree view');
    }
    const aa=camera(point(clips[name],u,t)),bb=camera(point(clips[name],e,t));
    const mid=aa.map((v,i)=>(v+bb[i])/2);
    assert.ok(Math.abs(mid[1]/-mid[2])<Math.tan(70*Math.PI/360),'part of upper arm remains visible');
   }
  }
  assert.ok(renderer.includes('"left", snapshot -> snapshot.skipChildrenRender(true)')&&renderer.includes('"dissipate_view_452", snapshot -> snapshot.skipRender(true)'),'rapier renderer must hide the left arm and its children');
  for(const fov of [60,70,90,110]){
   const tangent=Math.tan(fov*Math.PI/360);
   for(const p of [hilt,end])assert.ok(Math.abs(p[1]/-p[2])<tangent,'idle sword visible across FOV range');
  }
 }
}
// Canonical item sprite grip: NONE display context avoids hidden third-person offsets.
for(const file of ['FpHeldItemLayer.java','DummyHeldItemLayer.java']){
 const s=fs.readFileSync(new URL('src/main/java/com/actioncombat/client/'+file,root),'utf8');
 assert.ok(s.includes('ItemDisplayContext.NONE')&&s.includes('RapierItemPose.apply'),'both perspectives use canonical mount');
}
for(const file of ['third_person_doublesword','fp_doublesword']){
 const names=new Set(read(P+'models/'+(file.startsWith('fp_')?'fp_doublesword':'player_doublesword')+'.geo.json')['minecraft:geometry'][0].bones.map(b=>b.name));
 for(const b of Object.keys(read(P+'animations/'+file+'.animation.json').animations.guard_bash_upper.bones))assert.ok(names.has(b));
}


const bodyRenderer=fs.readFileSync(new URL('src/main/java/com/actioncombat/client/PlayerGeoRenderer.java',root),'utf8');
assert.ok(bodyRenderer.includes('upper.getRotY() - pelvis.getRotY()') && bodyRenderer.includes('rapier ? 65.0F * net.minecraft.util.Mth.DEG_TO_RAD'), 'side-on hip yaw must survive the runtime render pass');
const constants=fs.readFileSync(new URL('src/main/java/com/actioncombat/CombatConstants.java',root),'utf8');
assert.ok(constants.includes('RAPIER_SHEATH_SECONDS = 1.10F'), 'runtime sheath duration matches authored duration');

console.log(`Rapier ${samples} samples: visible right arm, 15-degree rising cut, square-elbow downstroke, hand/sword X paths, side-on torso and 1.10s sheath passed`);
