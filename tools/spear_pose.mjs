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
function geometry(root,fp){const p=path.join(root,'src/main/resources/assets/actioncombat/geckolib/models/',fp?'fp_spear.geo.json':'player_longsword.geo.json');return new Map(JSON.parse(fs.readFileSync(p))['minecraft:geometry'][0].bones.map(b=>[b.name,b]));}
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
 const u=fp?(left?'left':'right'):left?'upper_arm_l':'upper_arm_r',f=fp?(left?'fp_forearm_l':'fp_forearm_r'):left?'forearm_l':'forearm_r';
 const h=fp?(left?'fp_hand_l':'elucidator'):left?'hand_l':'hand_item_r';
 const s=world(g,c,u),l1=Math.hypot(...sub(g.get(f).pivot,g.get(u).pivot)),l2=Math.hypot(...sub(g.get(h).pivot,g.get(f).pivot));
 const delta=sub(target,s),dist=Math.hypot(...delta),d=norm(delta),r=Math.min(l1+l2-.00001,Math.max(Math.abs(l1-l2)+.00001,dist));
 const a=(l1*l1-l2*l2+r*r)/(2*r),bend=norm(sub(sub(pole,s),mul(d,dot(sub(pole,s),d))));
 const elbow=add(add(s,mul(d,a)),mul(bend,Math.sqrt(Math.max(0,l1*l1-a*a))));
 const hand=add(s,mul(d,r)),normal=norm(cross(sub(elbow,s),sub(hand,elbow)));
 orient(g,c,u,downFrame(sub(elbow,s),normal));orient(g,c,f,downFrame(sub(hand,elbow),normal));
 return {hand,elbow,shoulder:s};
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
 for(let i=1;i<Math.ceil(length*240);i++)times.add(Number((i/240).toFixed(4)));
 const prev={};
 for(const t of [...times].sort((a,b)=>a-b)){
 const p=fn(t);for(const [b,channels]of Object.entries(p.bones)){
 c.bones[b]||={};for(const[ch,val]of Object.entries(channels)){
 let v=[...val];if(ch==='rotation'&&prev[b])v=v.map((x,i)=>x+360*Math.round((prev[b][i]-x)/360));
 if(ch==='rotation')prev[b]=v;c.bones[b][ch]||={};c.bones[b][ch][t.toFixed(4)]=v.map(x=>Math.round(x*1e5)/1e5);
 }}
 }return c;
}

export {geometry,world,rotation,orient,aim,ik,blend,bake,add,sub,mul,dot,norm,mix,smooth,clamp,mv,transpose,mirror};
