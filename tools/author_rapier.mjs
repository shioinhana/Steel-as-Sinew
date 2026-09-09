// Author in posed model coordinates (+X right, +Y up, -Z forward), then bake
// shoulder/elbow IK and an independent wrist frame to ordinary GeckoLib keys.
import fs from 'node:fs';
import path from 'node:path';
import {posePoint, sample} from './fp_projection.mjs';
const add=(a,b)=>a.map((x,i)=>x+b[i]), sub=(a,b)=>a.map((x,i)=>x-b[i]);
const mul=(a,s)=>a.map(x=>x*s), dot=(a,b)=>a.reduce((s,x,i)=>s+x*b[i],0);
const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
const norm=a=>mul(a,1/Math.max(1e-10,Math.hypot(...a)));
const mix=(a,b,u)=>a.map((x,i)=>x+(b[i]-x)*u), smooth=u=>u*u*(3-2*u);
const clamp=x=>Math.max(0,Math.min(1,x));
const I=[[1,0,0],[0,1,0],[0,0,1]];
const transpose=a=>a[0].map((_,i)=>a.map(r=>r[i]));
const mm=(a,b)=>a.map(r=>transpose(b).map(c=>dot(r,c)));
const mv=(a,b)=>a.map(r=>dot(r,b));
const columns=(x,y,z)=>transpose([x,y,z]);
const rx=d=>{const a=d*Math.PI/180,c=Math.cos(a),s=Math.sin(a);return [[1,0,0],[0,c,-s],[0,s,c]]};
const ry=d=>{const a=d*Math.PI/180,c=Math.cos(a),s=Math.sin(a);return [[c,0,s],[0,1,0],[-s,0,c]]};
const rz=d=>{const a=d*Math.PI/180,c=Math.cos(a),s=Math.sin(a);return [[c,-s,0],[s,c,0],[0,0,1]]};
const mirror=p=>[-p[0],p[1],p[2]];
function euler(m){
 const y=Math.asin(Math.max(-1,Math.min(1,-m[2][0]))), cy=Math.cos(y);
 const x=Math.abs(cy)>1e-7?Math.atan2(m[2][1],m[2][2]):0;
 const z=Math.abs(cy)>1e-7?Math.atan2(m[1][0],m[0][0]):Math.atan2(-m[0][1],m[1][1]);
 return [-x,-y,z].map(x=>x*180/Math.PI);
}
function geometry(root,fp){const p=path.join(root,'src/main/resources/assets/actioncombat/geckolib/models/',fp?'fp_singlesword.geo.json':'player_longsword.geo.json');return new Map(JSON.parse(fs.readFileSync(p))['minecraft:geometry'][0].bones.map(b=>[b.name,b]));}
function world(g,c,b,p){return posePoint(g,c,b,0,p||g.get(b).pivot)}
function rotation(g,c,name){
 if(!name)return I;
 const b=g.get(name),r=sample(c.bones[name]?.rotation,0,[0,0,0]).map((x,i)=>x+(b.rotation?.[i]||0));
 return mm(rotation(g,c,b.parent),mm(rz(r[2]),mm(ry(-r[1]),rx(-r[0]))));
}
function orient(g,c,b,m){
 const local=mm(transpose(rotation(g,c,g.get(b).parent)),m);
 c.bones[b]={...c.bones[b],rotation:euler(local).map((x,i)=>x-(g.get(b).rotation?.[i]||0))};
}
function downFrame(d,n){const y=mul(norm(d),-1),x=norm(sub(n,mul(y,dot(n,y))));return columns(x,y,cross(x,y));}
function aim(g,c,fp,d,n){
 d=norm(d);n=norm(sub(n,mul(d,dot(n,d))));
 const y=fp?d:n,z=fp?n:mul(d,-1),x=norm(cross(y,z));
 orient(g,c,fp?'elucidator':'hand_item_r',columns(x,y,cross(x,y)));
}
function ik(g,c,fp,target,pole,left=false){
 const u=fp?'right':left?'upper_arm_l':'upper_arm_r',f=fp?'fp_forearm_r':left?'forearm_l':'forearm_r';
 const h=fp?'elucidator':left?'hand_l':'hand_item_r';
 const s=world(g,c,u),l1=Math.hypot(...sub(g.get(f).pivot,g.get(u).pivot)),l2=Math.hypot(...sub(g.get(h).pivot,g.get(f).pivot));
 const delta=sub(target,s),dist=Math.hypot(...delta),d=norm(delta),r=Math.min(l1+l2-.00001,Math.max(Math.abs(l1-l2)+.00001,dist));
 const a=(l1*l1-l2*l2+r*r)/(2*r),bend=norm(sub(sub(pole,s),mul(d,dot(sub(pole,s),d))));
 const elbow=add(add(s,mul(d,a)),mul(bend,Math.sqrt(Math.max(0,l1*l1-a*a))));
 const hand=add(s,mul(d,r)),normal=norm(cross(sub(elbow,s),sub(hand,elbow)));
 orient(g,c,u,downFrame(sub(elbow,s),normal));orient(g,c,f,downFrame(sub(hand,elbow),normal));
 return {hand,elbow,shoulder:s};
}
const TP_BASE={root:[0,0,0],lower_body:[0,-65,0],upper_body:[0,0,0],head:[0,65,0],upper_arm_r:[0,0,0],forearm_r:[0,0,0],hand_r:[0,0,0],hand_item_r:[0,0,0],upper_arm_l:[52,62,38],forearm_l:[-72,28,22],hand_l:[14,12,24],leg_r:[-12,-65,0],lower_leg_r:[15,0,0],foot_r:[0,0,0],leg_l:[12,-65,0],lower_leg_l:[8,0,0],foot_l:[0,0,0]};
function base(g,fp){
 const c={bones:Object.fromEntries(Object.entries(fp?{right:[0,0,0],fp_forearm_r:[0,0,0],elucidator:[0,0,0],left:[40,0,-25]}:TP_BASE).map(([b,r])=>[b,{rotation:r}]))};
 if(!fp)backHand(g,c,0);
 return c;
}
function backHand(g,c,u){
 const target=world(g,c,'upper_body',[2,16,3+2*u]);
 const pole=world(g,c,'upper_body',[10,16,4]);
 ik(g,c,false,target,pole,true);
}
function idle(g,fp){
 const c=base(g,fp);
 if(fp)ik(g,c,true,[0,5,-9],[0,1.826,-3.908]);
 else {
  const s=world(g,c,'upper_arm_r');
  ik(g,c,false,add(s,mul(norm([-.12,-.1,-1]),11.6)),add(s,[4,-6,-5]));
 }
 aim(g,c,fp,fp?[0,0,-1]:[0,0,-1],fp?[0,1,0]:[0,1,0]);
 return c;
}
function blend(a,b,u){
 const c={bones:{}};
 for(const bone of new Set([...Object.keys(a.bones),...Object.keys(b.bones)])){
 c.bones[bone]={};for(const ch of new Set([...Object.keys(a.bones[bone]||{}),...Object.keys(b.bones[bone]||{})])){
 const av=a.bones[bone]?.[ch]||[0,0,0],bv=b.bones[bone]?.[ch]||[0,0,0];
 const end=ch==='rotation'?bv.map((v,i)=>av[i]+((v-av[i]+540)%360)-180):bv;
 c.bones[bone][ch]=mix(av,end,u);
 }}return c;
}
function bake(length,fn,loop=false,events=[]){
 const c={loop,animation_length:length,bones:{}};const times=new Set([0,length,...events]);
 for(let i=1;i<Math.ceil(length*120);i++)times.add(Number((i/120).toFixed(4)));
 const prev={};
 for(const t of [...times].sort((a,b)=>a-b)){
 const p=fn(t);for(const [b,channels]of Object.entries(p.bones)){
 c.bones[b]||={};for(const[ch,val]of Object.entries(channels)){
 let v=[...val];if(ch==='rotation'&&prev[b])v=v.map((x,i)=>x+360*Math.round((prev[b][i]-x)/360));
 if(ch==='rotation')prev[b]=v;c.bones[b][ch]||={};c.bones[b][ch][t.toFixed(4)]=v.map(x=>Math.round(x*1e5)/1e5);
 }}
 }return c;
}
const upper=new Set(['upper_body','head','upper_arm_r','forearm_r','hand_r','hand_item_r','upper_arm_l','forearm_l','hand_l']);
function put(doc,name,c,fp){doc.animations[name]=c;if(!fp)doc.animations[name+'_upper']={...structuredClone(c),bones:Object.fromEntries(Object.entries(c.bones).filter(([b])=>upper.has(b)))};}
function cut(g,fp,kind){
 const diagonal=kind>=3,roll=diagonal?(kind===3?45:-45):kind===1?(fp?-3:-8):0;
 const shoulderPosition=world(g,base(g,fp),fp?'right':'upper_arm_r');
 // Aim the crossing of the HAND trajectories at the centre, not just the sword tips.
 const yaw=kind===2?0:Math.atan2(shoulderPosition[0],11.92)*180/Math.PI;
 const plane=mm(ry(yaw),rz(roll)),normal=mv(plane,[1,0,0]);
 function stroke(u){
  const c=base(g,fp);let shoulder,elbow;
  if(diagonal){shoulder=112-47*u;elbow=34*(1-u);}
  else if(kind===2){shoulder=90-5*u;elbow=90*(1-u);}
  else {shoulder=85+5*u;elbow=80*u;}
  const arm=mm(plane,rx(shoulder)),fore=mm(plane,rx(shoulder+elbow));
  orient(g,c,fp?'right':'upper_arm_r',arm);
  orient(g,c,fp?'fp_forearm_r':'forearm_r',fore);
  aim(g,c,fp,mv(fore,[0,-1,0]),normal);return c;
 }
 const rest=idle(g,fp),start=stroke(0),end=stroke(1);
 return bake(.4,t=>t<.105?blend(rest,start,smooth(t/.105)):t<.19?stroke(smooth((t-.105)/.085)):t<.215?end:blend(end,rest,smooth(clamp((t-.215)/.185))),false,[.105,.12,.18,.19,.215,.3]);
}
function thrust(g,fp,length){
 const rest=idle(g,fp),hold=.14,release=.18,contact=.25;
 const gather=fp?[-2,6,-5]:[-1.2,20,-3.2];
 function pose(u){const c=base(g,fp);
 const s=world(g,c,fp?'right':'upper_arm_r');
 const reach=fp?11.99999:Math.hypot(...sub(g.get('forearm_r').pivot,g.get('upper_arm_r').pivot))+Math.hypot(...sub(g.get('hand_item_r').pivot,g.get('forearm_r').pivot))-.00001;
 const extensionDir=norm(fp?[-4,6.5,-20]:[-s[0],0,-32]);
 const extended=add(s,mul(extensionDir,reach));
 const target=mix(gather,extended,u),p=ik(g,c,fp,target,fp?[7,-5,-2]:[10,15,-1]);
 // The wrist aims down the enemy line while folded, and becomes collinear at full reach.
 const gatheredDir=norm(sub(fp?[0,11.2,-28]:[0,20,-35],gather));
 aim(g,c,fp,norm(mix(gatheredDir,extensionDir,u)),[1,0,0]);
 if(!fp){
 backHand(g,c,u);
 c.bones.leg_r.rotation=[-12-20*u,-30,0];c.bones.lower_leg_r.rotation=[15+22*u,0,0];
 c.bones.leg_l.rotation=[12+8*u,-35,0];
 }
 return c;}
 const gathered=pose(0),extended=pose(1);
 return bake(length,t=>t<hold?blend(rest,gathered,smooth(t/hold)):t<release?gathered:t<contact?pose(smooth((t-release)/(contact-release))):t<.30?extended:blend(extended,rest,smooth(clamp((t-.30)/(length-.30)))),false,[hold,release,contact,.30]);
}
function sheath(g,fp){
 const length=1.65,rest=idle(g,fp),opening=fp?[-3,3,-1.5]:[-3,15,-1];
 // Opening faces forward and slightly right. The insertion axis is its opposite.
 const direction=norm([-.48,-.14,.866]);
 const bladeLength=11.43536;
 const alignment=add(opening,mul(direction,-bladeLength));
 function inserted(u){const c=base(g,fp);ik(g,c,fp,add(alignment,mul(direction,bladeLength*u)),fp?[7,-4,-2]:[9,15,-1]);aim(g,c,fp,direction,[0,1,0]);return c;}
 const aligned=inserted(0),home=inserted(1);
 function flourish(t){const c=base(g,fp),u=t/.70;
 const centre=fp?[.8,4,-8]:[3,19,-7];
 const hand=add(centre,[.65*Math.sin(u*Math.PI*2),.7*Math.sin(u*Math.PI*2),0]);
 ik(g,c,fp,hand,fp?[5,-3,-3]:[9,15,-2]);
 const phase=u*Math.PI*2;
 const d=norm([Math.sin(phase),Math.cos(phase),-.6]);
 aim(g,c,fp,d,[0,0,1]);return c;}
 const lastFlourish=flourish(.70);
 return bake(length,t=>t<.10?blend(rest,flourish(t),smooth(t/.10)):t<.70?flourish(t):t<1.05?blend(lastFlourish,aligned,smooth((t-.70)/.35)):t<1.15?aligned:t<1.58?inserted(smooth((t-1.15)/.43)):home,false,[.10,.70,1.05,1.15,1.58]);
}
function quickerSheath(c){
 const ratio=1.10/c.animation_length;c.animation_length=1.10;
 for(const channels of Object.values(c.bones))for(const[ch,track]of Object.entries(channels)){
  if(Array.isArray(track))continue;
  channels[ch]=Object.fromEntries(Object.entries(track).map(([t,v])=>[(Number(t)*ratio).toFixed(5),v]));
 }
 return c;
}
export function authorThirdPerson(doc,root){
 const g=geometry(root,false);doc.animations.static_idle=bake(3.2,()=>idle(g,false),true);
 for(let i=1;i<=4;i++)put(doc,'attack'+i,cut(g,false,i),false);
 for(const name of ['rapier_thrust_hit','rapier_thrust_miss'])put(doc,name,thrust(g,false,.50),false);
 doc.animations.put_away=quickerSheath(sheath(g,false));
 const rest=idle(g,false);
 for(const[name,c]of Object.entries(doc.animations))if(/^(walk|run|crouch|jump|fall|idle_upper|aiming|defend)/.test(name)){
  for(const b of upper)c.bones[b]=structuredClone(rest.bones[b]);
 }
 doc.animations.draw=bake(.7333,t=>blend(base(g,false),rest,smooth(clamp(t/.60))));
}
export function authorFirstPerson(doc,root){
 const g=geometry(root,true),rest=idle(g,true);
 doc.animations.static_idle=bake(3.2,()=>rest,true);
 for(let i=1;i<=4;i++)put(doc,'attack'+i,cut(g,true,i),true);
 for(const name of ['rapier_thrust_hit','rapier_thrust_miss'])put(doc,name,thrust(g,true,.50),true);
 doc.animations.put_away=quickerSheath(sheath(g,true));
 for(const[name,c]of Object.entries(doc.animations))if(/^(walk|run|static_pose|aiming|defend)/.test(name))c.bones=structuredClone(rest.bones);
 doc.animations.draw=bake(.7333,t=>{const c=structuredClone(rest);c.bones.right.position=[0,-7*(1-smooth(clamp(t/.60))),0];return c;});
}
