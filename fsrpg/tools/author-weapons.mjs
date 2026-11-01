// Reproducible animation authoring through the supplied Workbench MCP adapter.
// The adapter uses the actual Workbench command store, version writer and GPU preview.
import {readFile, writeFile, mkdir, copyFile} from 'node:fs/promises';
import {resolve, join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {planSwordGesture,solveSwordGesture,evaluateSwordGesture,evaluateSwordArc} from './sword-path.mjs';
const root = resolve(import.meta.dirname, '..');
const source = resolve(process.argv.slice(2).find(x=>!x.startsWith('--')) || join(root, '../FSVOXEL-Renderer-0.6.0-Windows/FSVOXEL-Renderer-0.6.0-Windows/工作台/FSVOXEL-Workbench/source'));
const {WorkbenchAdapter} = await import(pathToFileURL(join(source, 'tools/workbench-mcp.mjs')));
const adapter = new WorkbenchAdapter({'ready-file':join(root,'workbench/rapier-workspace/server-ready.json'),'output-dir':join(root,'workbench/exports')});
const evidence=resolve(root,process.argv.find(x=>x.startsWith('--evidence-dir='))?.slice('--evidence-dir='.length)||'verification/weapons-r2');
const only=process.argv.find(x=>x.startsWith('--only='))?.slice('--only='.length);
if(only&&!['dual_blade','spear'].includes(only))throw new Error('--only must be dual_blade or spear');
const sampleRate=60;const log=[];
const choreography=JSON.parse(await readFile(join(root,'tools/weapon-choreography.json'),'utf8'));
if(choreography.schemaVersion!==1||choreography.sampleRate!==sampleRate)throw new Error('Choreography requires schema 1 and 60Hz authoring');
for(const [name,width]of [['dualLeadArc',13],['dualFollowArc',13],['spearThrust',10],['spearSweep',10]]){
 const rows=choreography[name];
 if(!Array.isArray(rows)||rows.length<3||rows.some((row,i)=>!Array.isArray(row)||row.length!==width||!row.every(Number.isFinite)||row[0]<0||row[0]>1||(i>0&&row[0]<=rows[i-1][0])))
  throw new Error(`Invalid ${name}: finite coordinates and strictly increasing times in [0,1] required`);
}
async function call(name,args) { const r=await adapter.call(name,args); log.push({tool:name,args: name==='create_resource'?{kind:args.kind,id:args.id}:args,result:r.structuredContent.result}); return r; }
const V={add:(a,b)=>a.map((v,i)=>v+b[i]),sub:(a,b)=>a.map((v,i)=>v-b[i]),scale:(a,s)=>a.map(v=>v*s),dot:(a,b)=>a.reduce((s,v,i)=>s+v*b[i],0),cross:(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],norm:a=>{const n=Math.hypot(...a);return a.map(v=>v/n);}};
const identity=[1,0,0,0,1,0,0,0,1];
const transpose=a=>[a[0],a[3],a[6],a[1],a[4],a[7],a[2],a[5],a[8]];
const mul=(a,b)=>Array.from({length:9},(_,i)=>{const r=Math.floor(i/3),c=i%3;return a[r*3]*b[c]+a[r*3+1]*b[c+3]+a[r*3+2]*b[c+6];});
const transform=(m,p)=>[V.dot(m.slice(0,3),p),V.dot(m.slice(3,6),p),V.dot(m.slice(6,9),p)];
const yaw=degrees=>{const t=degrees*Math.PI/180,c=Math.cos(t),s=Math.sin(t);return[c,0,s,0,1,0,-s,0,c];};
const rotationMatrix=angles=>{
  const [x,y,z]=angles.map(v=>v*Math.PI/180),cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z);
  return[cy*cz,-cy*sz,sy,cx*sz+sx*sy*cz,cx*cz-sx*sy*sz,-sx*cy,sx*sz-cx*sy*cz,sx*cz+cx*sy*sz,cx*cy];
};
const euler=m=>[Math.atan2(-m[5],m[8]),Math.asin(Math.max(-1,Math.min(1,m[2]))),Math.atan2(-m[1],m[0])].map(x=>Math.round(x*180/Math.PI*1000)/1000);
function frame(direction,upAxis=false,twist=0,referenceX=null){
  const y=V.scale(V.norm(direction),upAxis?1:-1);
  const reference=referenceX||[1,0,0];
  let projected=V.sub(reference,V.scale(y,V.dot(reference,y)));
  if(Math.hypot(...projected)<.001)projected=V.cross(y,[0,1,0]);
  let x=V.norm(projected),z=V.cross(x,y);
  if(twist){const t=twist*Math.PI/180,c=Math.cos(t),s=Math.sin(t);const a=x;x=V.add(V.scale(a,c),V.scale(z,s));z=V.add(V.scale(a,-s),V.scale(z,c));}
  return[x[0],y[0],z[0],x[1],y[1],z[1],x[2],y[2],z[2]];
}
function arm(shoulder,palm,pole,parent,side,shaft){
  // A transverse closed fist: shaft +Z, wrist-to-knuckles -Y parallel to forearm.
  // Solve upper-arm 37.5 + forearm/fist 31.25 with f perpendicular to the shaft.
  const d=V.sub(palm,shoulder),a=V.dot(d,shaft),v=V.sub(d,V.scale(shaft,a)),b=Math.hypot(...v);
  const c=(V.dot(d,d)+31.25**2-37.5**2)/(2*31.25*b);
  if(!Number.isFinite(c)||Math.abs(c)>1.0001)throw new Error(`Unreachable closed ${side} grip: ${a},${b},${c}`);
  const radial=V.norm(v),perp=V.norm(V.cross(shaft,radial)),k=Math.max(-1,Math.min(1,c)),s=Math.sqrt(Math.max(0,1-k*k));
  const candidates=[V.add(V.scale(radial,k),V.scale(perp,s)),V.sub(V.scale(radial,k),V.scale(perp,s))];
  // Fix each anatomical elbow branch instead of changing solutions when pole scores cross.
  const f=candidates[side==='right'?0:1];
  const upperDirection=V.sub(d,V.scale(f,31.25)),hinge=V.norm(V.cross(upperDirection,f));
  const upper=frame(upperDirection,false,0,hinge),lower=frame(f,false,0,hinge),y=V.scale(f,-1),x=V.norm(V.cross(y,shaft));
  const grip=[x[0],y[0],shaft[0],x[1],y[1],shaft[1],x[2],y[2],shaft[2]],wrist=euler(mul(transpose(lower),grip));
  if(Math.abs(wrist[0])+Math.abs(wrist[2])>.01||Math.abs(wrist[1])>90.01)throw new Error(`Twisted closed ${side} grip: ${wrist}`);
  return {[side+'_arm']:{rotation:euler(mul(transpose(parent),upper))},[side+'_forearm']:{rotation:euler(mul(transpose(upper),lower))},[side+'_hand']:{rotation:wrist},...(side==='right'?{rapier:{rotation:[0,0,0]}}:{})};
}
function fitSpearRear(rear,shaft,shoulders){
 const R=shoulders.right,separation=V.sub(shoulders.left,R),along=V.dot(separation,shaft),difference=58-along,delta=V.sub(rear,R);
 const minimum=Math.max(4,4-difference),maximum=Math.min(36.5,36.5-difference);
 if(minimum>maximum)throw new Error('Spear stance cannot reach both closed fists');
 const a=Math.max(minimum,Math.min(maximum,V.dot(delta,shaft))),other=a+difference;
 const qr=Math.sqrt(37.5**2-a*a),ql=Math.sqrt(37.5**2-other*other);
 const rl=Math.max(Math.abs(31.25-qr)+.1,Math.sqrt(Math.max(0,24.25**2-a*a))),rh=31.25+qr-.1;
 const ll=Math.max(Math.abs(31.25-ql)+.1,Math.sqrt(Math.max(0,24.25**2-other*other))),lh=31.25+ql-.1;
 const across=V.sub(separation,V.scale(shaft,along)),width=Math.hypot(...across),ax=V.norm(across);
 let down=V.norm(V.cross(shaft,ax));if(V.dot(down,[0,-1,0])<0)down=V.scale(down,-1);
 let x=Math.max(Math.max(-rh,width-lh),Math.min(Math.min(rh,width+lh),V.dot(delta,ax)));
 const valid=x=>Math.max(0,rl*rl-x*x,ll*ll-(x-width)**2)<=Math.min(rh*rh-x*x,lh*lh-(x-width)**2);
 if(!valid(x)){
  const middle=width*rh/(rh+lh);let allowed=0,blocked=1;
  for(let i=0;i<24;i++){const amount=(allowed+blocked)/2;if(valid(middle+(x-middle)*amount))allowed=amount;else blocked=amount;}
  x=middle+(x-middle)*allowed;
 }
 const lo=Math.sqrt(Math.max(0,rl*rl-x*x,ll*ll-(x-width)**2)),hi=Math.sqrt(Math.max(0,Math.min(rh*rh-x*x,lh*lh-(x-width)**2)));
 const y=Math.max(lo,Math.min(hi,V.dot(delta,down)));
 return V.add(V.add(V.add(R,V.scale(shaft,a)),V.scale(ax,x)),V.scale(down,y));
}
// Dual swords use the official 50-degree diagonal grip. The fist continues the
// forearm; elbow flexion is negative local X and the wrist never folds to aim.
let swordQueries=null;
const swordPathDiagnostics=[];
function swordArm(query) {
 if(swordQueries){swordQueries[query.side].push(query);return {};}
 return query.palm?evaluateSwordArc(query).pose:solveSwordGesture(query);
}
const actions=[];
function action(id,frames,events=[],loop='once',duration=1){
  const tracks={}; for(const [time,p] of frames)for(const [bone,key]of Object.entries(p))(tracks[bone]??=[]).push({...key,time:Math.round(time*1000000)/1000000});
  for(const bone of Object.keys(tracks))tracks[bone]=[...new Map(tracks[bone].map(k=>[k.time,k])).values()].sort((a,b)=>a.time-b.time);
  const result={id,duration:Math.round(duration*1000000)/1000000,layer:'full',mode:'delta',loop,interpolation:'smoothstep',rotation_mode:'quaternion',transition:.065,root_policy:'preserve',tracks,events:events.map(e=>({...e,time:Math.round(e.time*1000000)/1000000})).sort((a,b)=>a.time-b.time)};actions.push(result);return result;
}
const parts=[{id:'root'},{id:'body',parent:'root',position:[0,75,0]},{id:'head',parent:'body',position:[0,75,0]},
  {id:'right_leg',parent:'root',position:[-11.875,75,0]},{id:'left_leg',parent:'root',position:[11.875,75,0]}];
for(const [side,x]of [['right',-37.5],['left',37.5]])parts.push({id:side+'_arm',parent:'body',position:[x,75,0]},{id:side+'_forearm',parent:side+'_arm',position:[0,-37.5,0]},{id:side+'_hand',parent:side+'_forearm',position:[0,-25,0]});
parts.push({id:'rapier',parent:'right_hand',position:[0,-12.5,0],rotation:[180,0,0]});
const geometry=[];const box=(part,material,position,size)=>geometry.push({kind:'box',values:[part,'set',material,position,size]});
box('body','coat',[-25,0,-12.5],[50,75,25]);box('body','trim',[-25.25,2,-12.75],[50.5,7,25.5]);box('body','trim',[-2,8,12.5],[4,57,1]);
box('head','skin',[-25,0,-25],[50,50,50]);box('head','hair',[-25.25,36,-25.25],[50.5,14.5,50.5]);box('head','eye',[-16,20,25],[6,4,1]);box('head','eye',[10,20,25],[6,4,1]);
for(const side of ['right','left']){box(side+'_leg','trousers',[-12.5,-60,-12.5],[25,60,25]);box(side+'_leg','boot',[-12.5,-75,-12.5],[25,15,25]);box(side+'_arm','coat',[-12.5,-37.5,-12.5],[25,37.5,25]);box(side+'_forearm','coat',[-12.5,-25,-12.5],[25,25,25]);box(side+'_hand','skin',[-12.5,-12.5,-12.5],[25,12.5,25]);}

// Blade arcs, elbow flexion and chest drive are evaluated at every 60Hz sample.
// Closed sword fists stay collinear with the forearms; the spear alone uses +Z.
const direction=(azimuth,elevation)=>{
 const y=azimuth*Math.PI/180,p=elevation*Math.PI/180;
 return [Math.sin(y)*Math.cos(p),Math.sin(p),Math.cos(y)*Math.cos(p)];
};
const curve=(keys,t)=>{
 let i=Math.max(0,keys.findLastIndex(k=>k[0]<=t));
 if(i>=keys.length-1)return keys.at(-1).slice(1);
 const lo=keys[i],hi=keys[i+1],u=(t-lo[0])/(hi[0]-lo[0]),dt=hi[0]-lo[0];
 return lo.slice(1).map((v,j)=>{
  const col=j+1,prev=keys[Math.max(0,i-1)],next=keys[Math.min(keys.length-1,i+2)];
  const m0=i===0?0:(hi[col]-prev[col])/(hi[0]-prev[0]);
  const m1=i+1===keys.length-1?0:(next[col]-lo[col])/(next[0]-lo[0]);
  return (2*u**3-3*u*u+1)*v+(u**3-2*u*u+u)*dt*m0+(-2*u**3+3*u*u)*hi[col]+(u**3-u*u)*dt*m1;
 });
};
const carry=choreography.dualCarry;
for(const side of ['left','right'])for(const state of [-1,0,1,'guard']) {
 const c=state==='guard'?choreography.dualGuard?.[side]:carry?.[side]?.[state];
 if(!c||![c.a,c.e,c.b,c.r??0].every(Number.isFinite)||Math.abs(c.a)>180||Math.abs(c.e)>90||c.b<10||c.b>110||Math.abs(c.r??0)>35)
  throw new Error(`Invalid dual carry ${side}/${state}: a [-180,180], e [-90,90], b [10,110], r [-35,35] degrees required`);
}
function dualPose(left=-1,right=-1,breath=0,{yaw:turn=0,lean=0,bank=0,shift=[0,0,0],strike=null,guard=false,support=0}={}) {
 const rot=[lean-breath*.7,turn,bank],parent=rotationMatrix(rot),base=V.add([0,75+breath,0],shift);
 const result={root:{rotation:[0,0,0]},body:{rotation:rot,position:V.add([0,breath,0],shift)},head:{rotation:[breath*.12,-turn*.88,0]},
 right_leg:{rotation:[-7,-10,-4],position:[0,0,0]},left_leg:{rotation:[7,10,4],position:[0,0,0]}};
 for(const [side,state]of [['left',left],['right',right]]) {
  const c=guard?choreography.dualGuard[side]:carry[side][state],x=side==='right'?-37.5:37.5;
  const shoulder=V.add(base,transform(parent,[x,75,0]));
  const active=strike?.hand===side;
  if(active&&strike.palm) {
   Object.assign(result,swordArm({shoulder,parent,side,local:false,palm:strike.palm,elbowHint:strike.elbowHint,cutSign:strike.cutSign}));
   continue;
  }
  Object.assign(result,swordArm({shoulder,parent,side,local:!active,
   azimuth:active?strike.azimuth:c.a+(side==='left'?1:-1)*support*8,elevation:active?strike.elevation:c.e+breath*.2+support*12,
   elbowFlex:active?strike.elbowFlex:c.b+breath*.4+support*20,bladeRoll:active?strike.bladeRoll:(c.r??0)}));
 }
 return result;
}
function spearPose({rear=[-16,123,0],azimuth=0,elevation=0,lean=0,turn=-55,bank=0,shift=[0,0,0],breath=0}={}) {
 const rot=[lean-breath*.65,turn,bank],parent=rotationMatrix(rot),base=V.add([0,75+breath,0],shift),shaft=direction(azimuth,elevation);
 const shoulders=Object.fromEntries([['right',-37.5],['left',37.5]].map(([side,x])=>[side,V.add(base,transform(parent,[x,75,0]))]));
 const rearPalm=fitSpearRear(V.add(rear,[0,breath,0]),shaft,shoulders);
 const result={root:{rotation:[0,0,0]},body:{rotation:rot,position:V.add([0,breath,0],shift)},head:{rotation:[breath*.1,-turn*.94,0]},
 right_leg:{rotation:[15,-25,-4],position:[0,0,0]},left_leg:{rotation:[-15,0,4],position:[0,0,0]}};
 for(const [side,x,d]of [['right',-37.5,0],['left',37.5,58]]) {
  const shoulder=shoulders[side],palm=V.add(rearPalm,V.scale(shaft,d));
  Object.assign(result,arm(shoulder,palm,transform(parent,[side==='right'?-.8:.8,-1,-.4]),parent,side,shaft));
 }
 return result;
}
const denseAction=(id,duration,sampler,events=[],loop='once')=>{
 swordQueries={left:[],right:[]};
 const times=[...new Set([0,duration,...events.map(e=>e.time),...Array.from({length:Math.ceil(duration*sampleRate)},(_,i)=>i/sampleRate)])].sort((a,b)=>a-b);
 const frames=times.map(t=>[t,sampler(t)]),queries=swordQueries;swordQueries=null;
 for(const side of ['left','right'])if(queries[side].length) {
  if(queries[side].length!==frames.length)throw new Error(`Incomplete sword queries ${id} ${side}`);
  const poses=planSwordGesture(queries[side],{loop:loop==='loop',times});
  for(let i=0;i<frames.length;i++)Object.assign(frames[i][1],poses[i]);
  swordPathDiagnostics.push({id,side,...poses.diagnostics});
 }
 const a=action(id,frames,events,loop,duration);
 a.layer='upper';a.interpolation='linear';a.transition=.05;
 for(const bone of ['root','right_leg','left_leg'])delete a.tracks[bone];
 return a;
};
const breath=t=>1.7*Math.sin(t*2*Math.PI/4);
for(let l=-1;l<=1;l++)for(let r=-1;r<=1;r++)denseAction('dual_idle_'+(l+1)+(r+1),4,t=>dualPose(l,r,breath(t)),[],'loop');
const combatEvents=[{time:.30,name:'active_begin',payload:{}},{time:.57,name:'active_end',payload:{}}];
// Both hands slash down/across in separate lanes. The authored grip follows an
// actual lateral arc; exact elbow IK carries the sword instead of thrusting a
// forward-reaching fist and rotating only the blade across the target.
for(const [id,hand,to,lead]of [['dual_ll','left',0,true],['dual_rl','right',0,false],['dual_rr','right',1,true],['dual_lr','left',1,false]]) {
 // The two mouse-button cuts start and settle at the same ready stance.
 // Legacy crossing cuts retain their IDs and endpoints for authored skill references.
 const sign=to===0?1:-1,start=carry[hand][lead?-1:to===1?0:-1],end=carry[hand][lead?-1:to];
 const endpoint=c=>evaluateSwordGesture({side:hand,parent:identity,shoulder:[hand==='left'?37.5:-37.5,150,0],local:true,azimuth:c.a,elevation:c.e,elbowFlex:c.b,bladeRoll:c.r??0});
 const first=endpoint(start),last=endpoint(end);
 // time, chest yaw/lean/bank, pelvis X/Y/Z, world palm XYZ, elbow-hint XYZ.
 const keys=[
  [0,0,0,0,0,0,0,...first.palm,...first.elbow],
  ...choreography[lead?'dualLeadArc':'dualFollowArc'].map(k=>k.map((v,i)=>[1,3,4,7,10].includes(i)?v*sign:v)),
  [1,0,0,0,0,0,0,...last.palm,...last.elbow]
 ];
 denseAction(id,1,t=>{const v=curve(keys,t);return dualPose(-1,-1,0,{yaw:v[0],lean:v[1],bank:v[2],shift:v.slice(3,6),support:lead?Math.sin(Math.PI*t)**2:0,strike:{hand,palm:v.slice(6,9),elbowHint:v.slice(9,12),cutSign:sign}});},combatEvents);
}
denseAction('dual_guard',4,t=>dualPose(1,0,breath(t)*.35,{lean:5,guard:true}),[],'loop');
denseAction('dual_run',1,t=>dualPose(-1,-1,0,{yaw:Math.sin(t*Math.PI*2)*5,lean:5,bank:Math.cos(t*Math.PI*2)*1.5,shift:[0,-1+Math.cos(t*Math.PI*4),0]}),[],'loop');
denseAction('spear_idle',4,t=>spearPose({breath:breath(t)}),[],'loop');
denseAction('spear_guard',4,t=>spearPose({rear:[-16,127,4],lean:4,breath:breath(t)*.35}),[],'loop');
denseAction('spear_run',1,t=>spearPose({rear:[-18,127+Math.sin(t*Math.PI*2)*1.2,4],elevation:5,lean:5,turn:-48+Math.sin(t*Math.PI*2)*3,shift:[0,-1+Math.cos(t*Math.PI*4),0]}),[],'loop');
// Right hand supplies the rear drive, left hand stays 58cm ahead and guides the same shaft.
// The hands move relative to the shoulders; the chest and hips transfer weight by a much smaller amount.
const thrust=choreography.spearThrust;
denseAction('spear_thrust',1,t=>{const v=curve(thrust,t);return spearPose({lean:v[0],turn:v[1],bank:v[2],shift:v.slice(3,6),rear:v.slice(6,9)});},combatEvents);
const sweep=choreography.spearSweep;
denseAction('spear_sweep',1,t=>{const v=curve(sweep,t);return spearPose({lean:v[0],turn:v[1],bank:v[2],shift:v.slice(3,6),rear:v.slice(6,9)});},combatEvents);
await mkdir(evidence,{recursive:true});
const carryGeometry=[];
for(const side of ['left','right'])for(const state of [-1,0,1]) {
 const c=carry[side][state],shoulder=[side==='left'?37.5:-37.5,150,0];
 const g=evaluateSwordGesture({side,parent:identity,shoulder,local:true,azimuth:c.a,elevation:c.e,elbowFlex:c.b,bladeRoll:c.r??0});
 carryGeometry.push({side,state,...c,shoulder,elbow:g.elbow,palm:g.palm,tip:g.tip});
}
await writeFile(join(evidence,'dual-carry-geometry.json'),JSON.stringify(carryGeometry,null,2));
await writeFile(join(evidence,'sword-path.json'),JSON.stringify(swordPathDiagnostics,null,2));
if(process.argv.includes('--dry-run')){console.log(JSON.stringify({carryGeometry,swordPathDiagnostics}));process.exit(0);}
await call('get_capabilities',{});
for(const [id,label,prefix,stance]of [['dual_blade','双刀 · 独立左右手 / 60Hz','dual_',dualPose()],['spear','长枪 · 双手握持 / 60Hz','spear_',spearPose()]]) {
 if(only&&id!==only)continue;
 const selected=actions.filter(a=>a.id.startsWith(prefix));
 const lower={id:'rapier_stance',duration:1,layer:'lower',mode:'delta',loop:'loop',interpolation:'linear',rotation_mode:'quaternion',transition:.1,root_policy:'preserve',tracks:{right_leg:[{time:0,...stance.right_leg},{time:1,...stance.right_leg}],left_leg:[{time:0,...stance.left_leg},{time:1,...stance.left_leg}]},events:[]};
 selected.push(lower);
 await call('create_resource',{kind:'model',id:'fsrpg.'+id,name:label,modelSpec:{type:'actor',parts,geometry,materials:[{id:'coat',color:'#294b74'},{id:'trim',color:'#c7b185'},{id:'skin',color:'#dbaf87'},{id:'hair',color:'#3c302c'},{id:'eye',color:'#132a3d'},{id:'trousers',color:'#394757'},{id:'boot',color:'#292b31'}],sockets:[{id:'weapon_grip',part:'right_hand',position:[0,-6.25,0]},{id:'offhand_grip',part:'left_hand',position:[0,-6.25,0]}]}});
 await call('dispatch_commands',{baseRevision:adapter.snapshot.revision,commands:selected.map(a=>({type:'addAction',targetId:null,patch:a}))});
 await call('save_copy',{});const exported=(await call('export_resource',{format:'fsvox'})).structuredContent.result;
 const output=join(root,'src/main/resources/assets/fsrpg/fsvoxel/models/'+id+'.fsvoxel');await copyFile(exported.path,output);
 await writeFile(join(root,'workbench/rapier-workspace/'+id+'-resource.json'),JSON.stringify({...adapter.summary(),output,actions:selected.map(a=>a.id),sampleRate:60},null,2));
 console.log(JSON.stringify({id,output,actions:selected.map(a=>a.id)}));
 for(const [name,time]of id==='spear'?[['spear_idle',0],['spear_thrust',.47]]:[['dual_idle_00',0],['dual_ll',.405],['dual_rr',.405]]) {
  const r=await call('render_preview',{backend:'opengl',action:name,time,view:'perspective',projection:'orthographic',width:900,height:700});
  await writeFile(join(evidence,name+'.png'),Buffer.from(r.content.find(c=>c.type==='image').data,'base64'));
 }
}
await writeFile(join(evidence,'workbench-authoring.json'),JSON.stringify(log,null,2));
await writeFile(join(evidence,'sword-path.json'),JSON.stringify(swordPathDiagnostics,null,2));
