import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {geometry,world,rotation,aim,ik,bake,add,sub,mul,norm,mix,smooth,clamp,mv,transpose,mirror} from './spear_pose.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const dir=path.join(root,'src/main/resources/assets/actioncombat/geckolib');
const load=(kind,id)=>JSON.parse(fs.readFileSync(path.join(dir,kind,id+(kind==='models'?'.geo.json':'.animation.json'))));
const write=(kind,id,doc)=>fs.writeFileSync(path.join(dir,kind,id+(kind==='models'?'.geo.json':'.animation.json')),JSON.stringify(doc,null,2)+'\n');
// A symmetric segmented skin rig; the original rapier's left arm was a rigid 12px block.
const src=load('models','fp_singlesword'), geo=src['minecraft:geometry'][0];
geo.description.identifier='geometry.actioncombat.fp_spear';geo.description.texture_width=64;geo.description.texture_height=64;
geo.bones=[{name:'root',pivot:[0,0,0]},{name:'knife_R',parent:'root',pivot:[0,0,0]},
 {name:'right',parent:'knife_R',pivot:[-4,4,0],cubes:[{origin:[-5.5,-2,-2],size:[3,6,4],uv:[40,16]}]},
 {name:'fp_forearm_r',parent:'right',pivot:[-4,-2,0],cubes:[{origin:[-5.5,-8,-2],size:[3,6,4],uv:[40,22]}]},
 {name:'elucidator',parent:'fp_forearm_r',pivot:[-4,-8,0]},
 {name:'left',parent:'knife_R',pivot:[4,4,0],cubes:[{origin:[2.5,-2,-2],size:[3,6,4],uv:[32,48]}]},
 {name:'fp_forearm_l',parent:'left',pivot:[4,-2,0],cubes:[{origin:[2.5,-8,-2],size:[3,6,4],uv:[32,54]}]},
 {name:'fp_hand_l',parent:'fp_forearm_l',pivot:[4,-8,0]}];
write('models','fp_spear',src);
const lerp=(a,b,u)=>Object.fromEntries(Object.keys(a).map(k=>[k,Array.isArray(a[k])?mix(a[k],b[k],u):a[k]+(b[k]-a[k])*u]));
export function state(fp,kind,u=0){
 const y=fp?4:20;
 const rest={r:[5,y-6,2],l:[-6,y-7,0],d:norm([.12,.72,.68]),yaw:fp?0:20,grip:0,legR:0,kneeR:5,legL:0,kneeL:5,rootY:0};
 if(kind==='idle')return rest;
 if(kind==='carry')return {...rest,r:[6,y-3,3],d:norm([-.1,.65,.75]),yaw:fp?0:22,grip:18.6};
 if(kind==='poke')return {...rest,r:mix([2,y-3,4],[0,y+1,fp?1:0],u),d:[0,0,-1],yaw:fp?0:30,grip:0,l:[0,0,0]};
 if(kind==='guard'||kind==='bash')return {...rest,r:[6,y+1,-5-4*u],l:[-6,y+1,-5-4*u],d:[-1,0,0],yaw:0,grip:0,legR:22-15*u,kneeR:5+10*u,legL:-18,kneeL:22,rootY:-.7};
 if(kind==='long')return {...rest,r:mix([4,y-2,2],[fp?3:2,y+1,fp?-11.6:-6.1],u),l:mix([-1,y-2,-5],[-7,y-6,3],u),d:[0,0,-1],yaw:fp?0:70*u,grip:0,legR:-38*u,kneeR:40*u,legL:26*u,kneeL:2,rootY:-u};
 if(kind==='dodge')return {...rest,r:[5,y-5,3],d:norm([0,.65,.76]),rootY:-1.4*Math.sin(Math.PI*u),legR:25*Math.sin(Math.PI*u),legL:-24*Math.sin(Math.PI*u)};
 throw Error(kind);
}
export function pose(g,fp,s,contact=0){
 const c={bones:{}};
 for(const b of g.keys())c.bones[b]={rotation:[0,0,0]};
 if(!fp){
  c.bones.upper_body.rotation=[0,s.yaw,0];c.bones.head.rotation=[0,-s.yaw,0];
  c.bones.leg_r.rotation=[s.legR,0,0];c.bones.lower_leg_r.rotation=[s.kneeR,0,0];
  c.bones.leg_l.rotation=[s.legL,0,0];c.bones.lower_leg_l.rotation=[s.kneeL,0,0];
  c.bones.root.position=[0,s.rootY,0];
 }
 const y=fp?4:20;
 const r=ik(g,c,fp,s.r,[12,y-8,1]);
 const desired=add(r.hand,mul(norm(s.d),12));
 const left=mix(s.l,desired,contact);
 ik(g,c,fp,left,[-12,y-8,0],true);
 aim(g,c,fp,s.d,[0,1,.02]);
 // Shift the item along its own shaft when the right hand carries the middle.
 // Its parent-space translation leaves the arm/wrist in place.
 const item=fp?'elucidator':'hand_item_r';
 const delta=mv(transpose(rotation(g,c,g.get(item).parent)),mul(norm(s.d),-s.grip));
 c.bones[item].position=mirror(delta);
 return c;
}
for(const fp of [false,true]){
 const g=geometry(root,fp),doc={format_version:'1.8.0',animations:{}};
 const p=(kind,u=0,contact=0)=>pose(g,fp,state(fp,kind,u),contact);
 const idle=state(fp,'idle');
 const movementSource=load('animations','third_person_singlesword').animations;
 const names=['static_idle','static_pose','inspect','run_hold','walk_hold','crouch_idle','jump','fall','run_carry'];
 for(const name of names)doc.animations[name]=bake(1.0,t=>{
  const s=state(fp,name==='run_carry'?'carry':'idle');
  if(name==='run_carry')s.r[1]+=.3*Math.sin(t*Math.PI*4);
  return pose(g,fp,s);
 },true);
 for(const [name,clip] of Object.entries(movementSource))if(/^(walk|run|crouch_walk|jump|fall)/.test(name)){
  if(['run_start','run_end','walk_start','walk_end'].includes(name))continue;
  const c=bake(clip.animation_length||.8,()=>p('idle'),true);
  if(!fp)for(const [b,v] of Object.entries(clip.bones))if(/^(root|lower_body|leg_|lower_leg_|foot_)/.test(b))c.bones[b]=structuredClone(v);
  doc.animations[name]=c;
 }
 if(!fp){const gait=movementSource.run_hold;for(const[b,v]of Object.entries(gait.bones))if(/^(root|lower_body|leg_|lower_leg_|foot_)/.test(b))doc.animations.run_carry.bones[b]=structuredClone(v);doc.animations.run_carry.animation_length=gait.animation_length;}
 for(const name of ['draw','put_away','walk_start','walk_end','run_end','hurt','hurt_upper','defend_end','aiming','aiming_upper'])doc.animations[name]=bake(name==='draw'?.73333:name==='put_away'?.7333:.22,()=>p('idle'));
 doc.animations.run_start=bake(.2,t=>p('dodge',t/.2));
 const guard=bake(.18,t=>pose(g,fp,lerp(idle,state(fp,'guard'),smooth(clamp(t/.18))),smooth(clamp(t/.18))));
 doc.animations.defend=guard;doc.animations.defend_upper=structuredClone(guard);doc.animations.defend_1=structuredClone(guard);
 for(const [name,kind]of [['attack1','poke'],['attack3','long'],['attack2','bash'],['attack4','bash'],['attack_dual','bash']]){
  const clip=bake(.4,t=>{
   // Gather for 0.10s, extend to contact at 0.18s, hold to 0.23s, clean recovery.
   const start=state(fp,kind,0),end=state(fp,kind,1);
   let s,contact;
   if(t<.1){const u=smooth(t/.1);s=lerp(idle,start,u);contact=kind==='long'?0:u;}
   else if(t<.18){const u=smooth((t-.1)/.08);s=lerp(start,end,u);contact=kind==='long'?0:1;}
   else if(t<.23){s=end;contact=kind==='long'?0:1;}
   else {const u=smooth((t-.23)/.17);s=lerp(end,idle,u);contact=kind==='long'?0:1-u;}
   return pose(g,fp,s,contact);
  },false,[.1,.18,.23]);
  doc.animations[name]=clip;
  if(!fp)doc.animations[name+'_upper']=structuredClone(clip); // Spear attacks deliberately own the bracing legs.
 }
 for(const[name,c]of Object.entries(doc.animations))if(name.endsWith('_upper'))
  c.bones=Object.fromEntries(Object.entries(c.bones).filter(([b])=>!/^(root|lower_body|leg_|lower_leg_|foot_)/.test(b)));
 if(fp)for(const name of ['defend_1','aiming','static_pose'])doc.animations[name].loop='hold_on_last_frame';
 write('animations',fp?'fp_spear':'third_person_spear',doc);
}
console.log('Spear: symmetric FP arms, two-hand shaft contacts, release/lunge, brace, carry and backstep baked');
