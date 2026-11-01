// Reproducible animation authoring through the supplied Workbench MCP adapter.
// The adapter uses the actual Workbench command store, version writer and GPU preview.
import {readFile, writeFile, mkdir, copyFile} from 'node:fs/promises';
import {resolve, join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {planRapierPath} from './rapier-path.mjs';
const root = resolve(import.meta.dirname, '..');
const source = resolve(process.argv.slice(2).find(x=>!x.startsWith('--')) || join(root, '../FSVOXEL-Renderer-0.6.0-Windows/FSVOXEL-Renderer-0.6.0-Windows/工作台/FSVOXEL-Workbench/source'));
const {WorkbenchAdapter} = await import(pathToFileURL(join(source, 'tools/workbench-mcp.mjs')));
const adapter = new WorkbenchAdapter({'ready-file':join(root,'workbench/rapier-workspace/server-ready.json'),'output-dir':join(root,'workbench/exports')});
const output = join(root,'src/main/resources/assets/fsrpg/fsvoxel/models/rapier.fsvoxel');
const evidence=resolve(root,process.argv.find(x=>x.startsWith('--evidence-dir='))?.slice('--evidence-dir='.length)||'verification/rapier-r6');
const sampleRate=60;
const log=[];
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
function arm(shoulder,hand,pole,parent,side,blade,roll=0,wristReference=null){
  // Authored hand coordinates mean palm center; solve two bones to the actual wrist axis.
  hand=V.sub(hand,V.scale(V.norm(blade),6.25));
  const d=V.sub(hand,shoulder),length=Math.max(12.6,Math.min(62.4,Math.hypot(...d))),axis=V.norm(d);
  hand=V.add(shoulder,V.scale(axis,length));
  const projected=V.sub(pole,V.scale(axis,V.dot(pole,axis)));
  const along=(length*length+37.5**2-25**2)/(2*length);
  const elbow=V.add(V.add(shoulder,V.scale(axis,along)),V.scale(V.norm(projected),Math.sqrt(Math.max(0,37.5**2-along**2))));
  // A shared bend-plane normal makes the elbow a hinge, with no arbitrary axial flip.
  const hinge=V.norm(V.cross(axis,projected));
  // Keep one bend-plane normal for both links: the elbow is a hinge, never an axial corkscrew.
  const upper=frame(V.sub(elbow,shoulder),false,0,hinge),lower=frame(V.sub(hand,elbow),false,0,hinge);
  // Blade roll uses a stable world reference, independent of the elbow plane crossing the torso.
  const wrist=frame(blade,false,roll,wristReference||[1,0,0]);
  return {[side+'_arm']:{rotation:euler(mul(transpose(parent),upper))},[side+'_forearm']:{rotation:euler(mul(transpose(upper),lower))},[side+'_hand']:{rotation:euler(mul(transpose(lower),wrist))},...(side==='right'?{rapier:{rotation:[0,0,0]}}:{})};
}
function pose({body=65,hand=[-14,125,83],blade=[0,0,1],roll=0,lean=0,bank=0,pole=null,wristReference=null}={}){
  const parent=rotationMatrix([lean,body,bank]),shoulder=V.add([0,75,0],transform(parent,[-37.5,75,0]));
  const leftShoulder=V.add([0,75,0],transform(parent,[37.5,75,0]));
  return {root:{rotation:[0,0,0]},body:{rotation:[lean,body,bank]},head:{rotation:[0,-body,0]},
    // Flat feet: lead foot faces the opponent, rear foot follows the side-on pelvis.
    // Rotate hip spacing with the 65-degree stance, then add a restrained fore/aft offset.
    right_leg:{position:[6.856,0,16.763+lean],rotation:[0,0,0]},
    left_leg:{position:[-6.856,0,-16.763],rotation:[0,55,0]},
    ...arm(shoulder,hand,pole||transform(parent,[-.8,-1,-.2]),parent,'right',blade,roll,wristReference),
    ...arm(leftShoulder,V.add([0,75,0],transform(parent,[8,37,-30])),transform(parent,[1,-.6,-1]),parent,'left',[0,-1,0],20)};
}
const idle=pose();
const P=spec=>pose(spec);
const riseLow=P({body:77,hand:[-24,110,61],blade:[-.15,-.66,.74],lean:-3,bank:2}),riseHigh=P({body:37,hand:[-7,157,69],blade:[.09,.76,.64],lean:10,bank:-4});
const downHigh=P({body:36,hand:[-7,158,59],blade:[.08,.88,.47],lean:-3,bank:-3}),downLow=P({body:82,hand:[-26,102,63],blade:[-.12,-.76,.64],lean:12,bank:5});
const crossLeft=P({body:82,hand:[-34,143,65],blade:[-.61,.55,.58],lean:-3,bank:4}),crossRight=P({body:42,hand:[-4,119,62],blade:[.60,-.48,.64],lean:7,bank:-4}),crossTop=P({body:44,hand:[4,131,75],blade:[.55,.52,.65],lean:5,bank:-3}),crossEnd=P({body:78,hand:[-29,113,69],blade:[-.63,-.44,.64],lean:9,bank:4});
const chargeBody=yaw(65),chargeShoulder=V.add([0,75,0],transform(chargeBody,[-37.5,75,0]));
const chargeWrist=V.add([0,75,0],transform(chargeBody,[-37.5,Math.sqrt(37.5**2-25**2)*-1+100,25]));
const chargeElbow=V.add(chargeWrist,[0,-25,0]);
const charge=P({body:65,hand:V.add(chargeWrist,[0,0,6.25]),blade:[0,0,1],pole:V.sub(chargeElbow,chargeShoulder),roll:15});
function straightThrust(body,x,y,roll=0,lean=0){
  const parent=rotationMatrix([lean,body,0]),shoulder=V.add([0,75,0],transform(parent,[-37.5,75,0]));
  const elbow=[x,y,shoulder[2]+Math.sqrt(37.5**2-(x-shoulder[0])**2-(y-shoulder[1])**2)];
  return P({body,hand:V.add(elbow,[0,0,28.25]),blade:[0,0,1],pole:V.sub(elbow,shoulder),roll,lean});
}
const lightEnd=straightThrust(50,-32,135);
const lightReady=P({body:63,hand:[-23,128,72],blade:[0,0,1],lean:2});
const thrustEnd=straightThrust(46,-15,131,15,15);
const wristLow=P({body:78,hand:[-28,112,63],blade:[-.43,-.63,.64],roll:55,lean:-3,bank:3});
const wristHigh=P({body:35,hand:[12,151,70],blade:[.40,.64,.66],roll:30,lean:10,bank:-5});
// Actor coordinates: right is -X, left is +X; both diagonals remain ahead of the chest.
const guard=P({body:65,hand:[-20,144,64],blade:[.58,.76,.29],roll:-15});
const heavyGuard=P({body:95,hand:[15,141,61],blade:[-.61,.74,.28],roll:25,pole:[-.6,-1,-.2]});
const runPose=(sway=0)=>P({body:32+sway*2.0,hand:[-18+sway*1.2,153+sway*.8,54],blade:[sway*.035,.985,.16],roll:10});
const breathe=amount=>{
  const p=P({hand:[-14,125+amount*.45,83-amount*.2],lean:amount*.18});
  p.body.position=[0,amount,0]; return p;
};
const actions=[];
function action(id,frames,events=[],loop='once',duration=1){
  const tracks={}; for(const [time,p] of frames)for(const [bone,key]of Object.entries(p))(tracks[bone]??=[]).push({...key,time:Math.round(time*1000000)/1000000});
  for(const bone of Object.keys(tracks))tracks[bone]=[...new Map(tracks[bone].map(k=>[k.time,k])).values()].sort((a,b)=>a.time-b.time);
  const result={id,duration:Math.round(duration*1000000)/1000000,layer:'full',mode:'delta',loop,interpolation:'smoothstep',rotation_mode:'quaternion',transition:.065,root_policy:'preserve',tracks,events:events.map(e=>({...e,time:Math.round(e.time*1000000)/1000000})).sort((a,b)=>a.time-b.time)};actions.push(result);return result;
}
const combatEvents=[{time:.35,name:'active_begin',payload:{}},{time:.55,name:'active_end',payload:{}}];
action('rapier_idle',[[0,breathe(0)],[1,breathe(1.4)],[2,breathe(0)],[3,breathe(-1.4)],[4,breathe(0)]],[],'loop',4);
// The palm stays ahead of the face; turning the wrist presents both flats of the actual held blade.
const inspectBlade=(roll=0,lower=0)=>{
  const p=P({body:49,hand:[-18,150-lower,58],blade:[.02,.994,.105],roll,lean:-1});
  p.head.rotation=[-5,-43,-3]; return p;
};
action('rapier_inspect_blade',[[0,idle],[.6,inspectBlade(-12)],[1.15,inspectBlade(-12)],
  [1.9,inspectBlade(55)],[2.5,inspectBlade(55)],[3.2,inspectBlade(-48,2)],
  [3.8,inspectBlade(-48,2)],[4.35,inspectBlade(0)],[5,idle]],[],'once',5);
// A compact circle ahead and to the right of the body, then two restrained diagonal practice cuts.
const flower=angle=>{const a=angle*Math.PI/180;return P({body:57,hand:[-24-8*Math.sin(a),128+24*Math.cos(a),64],
  blade:[Math.sin(a)*.95,Math.cos(a)*.95,.31],roll:12,lean:2,bank:-Math.sin(a)*1.5,wristReference:[0,0,1]});};
action('rapier_inspect_flourish',[[0,idle],...Array.from({length:19},(_,i)=>[.5+i*1.55/18,flower(i*20)]),
  [2.35,P({body:49,hand:[8,146,57],blade:[.5,.65,.57],bank:-3})],
  [2.65,P({body:74,hand:[-32,115,62],blade:[-.5,-.6,.62],lean:5,bank:3})],
  [2.95,P({body:74,hand:[-30,137,55],blade:[-.55,.53,.65],bank:3})],
  [3.25,P({body:49,hand:[8,116,64],blade:[.5,-.55,.67],lean:5,bank:-3})],
  [3.7,P({body:56,hand:[-9,125,74],blade:[.12,.1,.99]})],[4.4,idle]],[],'once',4.4);
action('rapier_guard',[[0,guard],[1,guard]],[],'loop');
action('rapier_guard_heavy',[[0,heavyGuard],[1,heavyGuard]],[],'loop');
action('rapier_run',[[0,runPose(0)],[.2,runPose(1)],[.4,runPose(0)],[.6,runPose(-1)],[.8,runPose(0)]],[],'loop',.8);
action('rapier_rise',[[0,idle],[.20,riseLow],[.35,P({body:59,hand:[-20,126,72],blade:[-.03,-.06,.998],lean:7})],[.49,riseHigh],[.55,P({body:35,hand:[-5,159,68],blade:[.12,.83,.55],lean:9,bank:-4})],[.66,P({body:41,hand:[-7,151,72],blade:[.08,.62,.78],lean:6,bank:-2})],[.84,P({body:55,hand:[-13,135,78],blade:[0,.23,.97],lean:2})],[1,idle]],combatEvents);
action('rapier_down',[[0,idle],[.20,downHigh],[.35,P({body:54,hand:[-12,139,73],blade:[0,.06,.998],lean:8})],[.49,downLow],[.55,P({body:82,hand:[-25,101,65],blade:[-.14,-.80,.58],lean:10,bank:4})],[.68,P({body:78,hand:[-24,108,68],blade:[-.10,-.56,.82],lean:6,bank:2})],[.85,P({body:69,hand:[-17,119,76],blade:[0,-.17,.985],lean:2})],[1,idle]],combatEvents);
const crossArc=P({body:43,hand:[1,130,66],blade:[.70,.05,.71],lean:6,bank:-3});
action('rapier_cross',[[0,idle],[.22,crossLeft],[.35,P({body:60,hand:[-19,135,71],blade:[-.12,.12,.985],lean:6})],[.405,crossRight],[.438,crossArc],[.468,crossTop],[.505,P({body:60,hand:[-11,129,73],blade:[.04,-.08,.996],lean:8})],[.55,crossEnd],[.70,P({body:76,hand:[-27,115,73],blade:[-.38,-.30,.875],lean:5,bank:2})],[1,idle]],combatEvents);
action('rapier_light_thrust',[[0,idle],[.15,lightReady],[.35,lightEnd],[.55,lightEnd],[1,idle]],combatEvents);
action('rapier_wrist_rise',[[0,idle],[.20,wristLow],[.35,P({body:59,hand:[-16,126,72],blade:[.04,-.04,.998],roll:22,lean:7})],[.50,wristHigh],[.55,P({body:34,hand:[14,155,68],blade:[.44,.70,.56],roll:30,lean:9,bank:-4})],[.70,P({body:43,hand:[6,145,75],blade:[.31,.40,.86],roll:18,lean:5,bank:-2})],[.86,P({body:57,hand:[-10,131,79],blade:[.10,.14,.985],roll:8,lean:2})],[1,idle]],combatEvents);
action('rapier_thrust',[[0,idle],[.18,charge],[.23,charge],[.35,thrustEnd],[.55,thrustEnd],[.65,P({body:58,hand:[-17,135,85],blade:[0,.03,1],roll:12,lean:4})],[1,idle]],combatEvents);
action('rapier_riposte',[[0,guard],[.14,P({body:59,hand:[-17,130,63],blade:[0,.04,1]})],[.35,lightEnd],[.55,lightEnd],[.70,P({body:57,hand:[-14,130,77],blade:[0,.03,1]})],[1,idle]],combatEvents);
action('rapier_reprise',[[0,P({body:68,hand:[-18,124,65],blade:[0,.1,1]})],[.18,charge],[.23,charge],[.35,straightThrust(57,-16,136,8,4)],[.55,straightThrust(57,-16,136,8,4)],[.70,P({body:60,hand:[-17,130,81],blade:[0,.04,1],lean:2})],[1,idle]],combatEvents);
action('rapier_stance',[[0,{right_leg:idle.right_leg,left_leg:idle.left_leg}],[1,{right_leg:idle.right_leg,left_leg:idle.left_leg}]],[],'loop');
// A composed Workbench review action; combat still requires successive accepted inputs.
const chainIds=['rapier_rise','rapier_down','rapier_cross','rapier_light_thrust','rapier_wrist_rise'];
const frames=[],events=[];let offset=0,lastPose=null;
const definitions=JSON.parse(await readFile(join(root,'src/main/resources/defaults/skills.json'),'utf8'));
const style=JSON.parse(await readFile(join(root,'src/main/resources/defaults/styles.json'),'utf8')).find(s=>s.id==='rapier');
const timings=style.leftCombo.map((g,i)=>{const s=definitions.find(s=>s.id===g.skillId);return[s.windupTicks,s.activeTicks,s.recoveryTicks,s.windupTicks+s.activeTicks+(i===style.leftCombo.length-1?s.recoveryTicks:0)];});
const quaternion=m=>{
  const q=[0,0,0,0],trace=m[0]+m[4]+m[8];
  if(trace>0){const s=Math.sqrt(trace+1)*2;q[3]=s/4;q[0]=(m[7]-m[5])/s;q[1]=(m[2]-m[6])/s;q[2]=(m[3]-m[1])/s;}
  else {const i=m[0]>m[4]?(m[0]>m[8]?0:2):(m[4]>m[8]?1:2),j=(i+1)%3,k=(i+2)%3,s=Math.sqrt(1+m[i*3+i]-m[j*3+j]-m[k*3+k])*2;q[i]=s/4;q[3]=(m[k*3+j]-m[j*3+k])/s;q[j]=(m[j*3+i]+m[i*3+j])/s;q[k]=(m[k*3+i]+m[i*3+k])/s;}
  return q;
};
const qmul=(a,b)=>[a[3]*b[0]+a[0]*b[3]+a[1]*b[2]-a[2]*b[1],a[3]*b[1]-a[0]*b[2]+a[1]*b[3]+a[2]*b[0],a[3]*b[2]+a[0]*b[1]-a[1]*b[0]+a[2]*b[3],a[3]*b[3]-a[0]*b[0]-a[1]*b[1]-a[2]*b[2]];
const qinv=q=>[-q[0],-q[1],-q[2],q[3]];
const qlog=q=>{const l=Math.hypot(...q.slice(0,3)),s=l<1e-8?1:Math.atan2(l,q[3])/l;return q.slice(0,3).map(v=>v*s);};
const qexp=v=>{const l=Math.hypot(...v),s=l<1e-8?1:Math.sin(l)/l;return[...v.map(x=>x*s),Math.cos(l)];};
const qslerp=(a,b,t)=>{let dot=a.reduce((s,v,i)=>s+v*b[i],0);b=dot<0?b.map(v=>-v):b;dot=Math.abs(dot);let u=1-t,v=t;if(dot<.9995){const angle=Math.acos(Math.min(1,dot));u=Math.sin((1-t)*angle)/Math.sin(angle);v=Math.sin(t*angle)/Math.sin(angle);}const q=a.map((n,i)=>n*u+b[i]*v),l=Math.hypot(...q);return q.map(n=>n/l);};
const qangles=([x,y,z,w])=>euler([1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w),2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w),2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)]);
// Quaternion cubic arcs carry angular motion through intermediate keys instead of stopping at each key.
// Exact authored poses and server phase markers remain intact in the baked 60 Hz output.
const sample=(a,t)=>Object.fromEntries(Object.entries(a.tracks).map(([bone,keys])=>{
  let i=Math.max(0,keys.findLastIndex(k=>k.time<=t));
  if(i>=keys.length-1)return[bone,{...keys.at(-1)}];
  const lo=keys[i],hi=keys[i+1],f=(t-lo.time)/(hi.time-lo.time);
  const quats=keys.map(k=>quaternion(rotationMatrix(k.rotation)));
  for(let j=1;j<quats.length;j++)if(quats[j-1].reduce((s,v,k)=>s+v*quats[j][k],0)<0)quats[j]=quats[j].map(v=>-v);
  const control=j=>{
    const q=quats[j],prev=quats[j===0?(a.loop==='loop'?keys.length-2:0):j-1],next=quats[j===keys.length-1?(a.loop==='loop'?1:j):j+1];
    const p=qlog(qmul(qinv(q),prev)),n=qlog(qmul(qinv(q),next));
    return qmul(q,qexp(p.map((v,k)=>-.25*(v+n[k]))));
  };
  // Large cosmetic wrist turns can make SQUAD's inner arcs antipodal. Use an unambiguous
  // spherical arc there; dense flower keys preserve continuous speed through the complete circle.
  const inspection=a.id.startsWith('rapier_inspect_');
  const circle=a.id==='rapier_inspect_flourish' && lo.time>=.5 && hi.time<=2.050001;
  const c0=control(i),c1=control(i+1);
  const unsafe=c0.reduce((sum,v,k)=>sum+v*c1[k],0)<.2 || quats[i].reduce((sum,v,k)=>sum+v*quats[i+1][k],0)<Math.cos(Math.PI/6);
  const q=bone.endsWith('_forearm') ? quaternion(rotationMatrix([lo.rotation[0]+(hi.rotation[0]-lo.rotation[0])*(circle?f:f*f*(3-2*f)),0,0])) :
    inspection||unsafe?qslerp(quats[i],quats[i+1],circle||(!inspection&&bone.endsWith('_arm'))?f:f*f*(3-2*f)):
    qslerp(qslerp(quats[i],quats[i+1],f),qslerp(c0,c1,f),2*f*(1-f));
  const position=lo.position?lo.position.map((value,axis)=>{
    const prev=keys[Math.max(0,i-1)],next=keys[Math.min(keys.length-1,i+2)],end=(hi.position||lo.position)[axis],dt=hi.time-lo.time;
    const p=(prev.position||lo.position)[axis],n=(next.position||hi.position||lo.position)[axis];
    const m0=(end-p)/(hi.time-prev.time||1),m1=(n-value)/(next.time-lo.time||1);
    return (2*f**3-3*f**2+1)*value+(f**3-2*f**2+f)*dt*m0+(-2*f**3+3*f**2)*end+(f**3-f**2)*dt*m1;
  }):null;
  return[bone,{rotation:qangles(q),...(position?{position}:{})}];
}));
// Combo clips begin from the previous default chain pose instead of snapping toward idle.
for(let i=1;i<chainIds.length;i++){
  const previous=actions.find(a=>a.id===chainIds[i-1]),current=actions.find(a=>a.id===chainIds[i]);
  const [windup,active,recovery,chain]=timings[i-1];
  const at=.55+.45*(chain-windup-active)/recovery,previousPose=sample(previous,at);
  for(const [bone,keys]of Object.entries(current.tracks))keys[0]={...keys[0],...previousPose[bone],time:0};
}
for(const [i,id]of chainIds.entries()){
  const a=actions.find(x=>x.id===id),[windup,active,recovery,chain,gateStart,gateEnd,buffer]=timings[i];
  const tickOf=t=>t<=.35?t/.35*windup:t<=.55?windup+(t-.35)/.2*active:windup+active+(t-.55)/.45*recovery;
  const fractionOf=t=>t<=windup?.35*t/windup:t<=windup+active?.35+.2*(t-windup)/active:.55+.45*(t-windup-active)/recovery;
  frames.push([offset,lastPose||idle]);
  for(const t of [...new Set(Object.values(a.tracks).flat().map(k=>k.time))].sort((a,b)=>a-b))if(t>0&&tickOf(t)<chain)frames.push([offset+tickOf(t)/20,sample(a,t)]);
  lastPose=sample(a,fractionOf(chain));frames.push([offset+chain/20,lastPose]);
  events.push({time:offset+windup/20,name:'active_begin',payload:{stage:i+1}},{time:offset+(windup+active)/20,name:'active_end',payload:{stage:i+1}});
  if(i<4)events.push({time:offset+(windup+active)/20,name:'recovery_cancel',payload:{stage:i+1}});
  offset+=chain/20;
}
action('rapier_combo',frames,events,'once',offset);
// Bake a minimum of 60 samples per second, retaining exact phase markers and key poses.
// Linear quaternion interpolation between these samples keeps rendering continuous at any FPS.
const anatomicalDiagnostics=[],solvedChainEnds=new Map();
for(const a of actions){
  const stage=chainIds.indexOf(a.id),timing=stage<0?null:timings[stage];
  const chainTime=timing ? .55+.45*(timing[3]-timing[0]-timing[1])/timing[2] : null;
  const times=[...new Set([0,a.duration,...Object.values(a.tracks).flat().map(k=>k.time),
    ...(chainTime===null?[]:[chainTime]),...a.events.map(e=>e.time),...Array.from({length:Math.ceil(a.duration*sampleRate)},(_,i)=>Math.min(a.duration,i/sampleRate))].map(t=>Math.round(t*1000000)/1000000))].sort((x,y)=>x-y);
  const sampled=times.map(t=>[t,sample(a,t)]), tracks={};
  if(a.id!=='rapier_stance'&&a.id!=='rapier_combo') {
    const queries=sampled.map(([t,p])=>{
      const parent=rotationMatrix(p.body.rotation),body=V.add([0,75,0],p.body.position||[0,0,0]);
      const shoulder=V.add(body,transform(parent,[-37.5,75,0]));
      const upper=mul(parent,rotationMatrix(p.right_arm.rotation)),elbow=V.add(shoulder,transform(upper,[0,-37.5,0]));
      const lower=mul(upper,rotationMatrix(p.right_forearm.rotation)),wrist=V.add(elbow,transform(lower,[0,-25,0]));
      const hand=mul(lower,rotationMatrix(p.right_hand.rotation));
      const blade=transform(hand,[0,-1,0]),fore=transform(lower,[0,-1,0]);
      const sameActions=definitions.filter(s=>s.action===a.id),phase=t<=.35?'windupTicks':t<=.55?'activeTicks':'recoveryTicks',span=phase==='windupTicks'?.35:phase==='activeTicks'?.2:.45;
      const sourceStep=sameActions.length?Math.max(...sameActions.map(s=>span/(Math.max(1,s[phase])*3))):1/60;
      return {parent,shoulder,palm:V.add(wrist,transform(hand,[0,-6.25,0])),blade,pole:V.sub(elbow,shoulder),time:t,sourceStep,
        ...(['rapier_thrust','rapier_reprise'].includes(a.id)&&t>=.18&&t<=.230001?{maxFlex:-23}:{}),
        ...(['rapier_thrust','rapier_reprise','rapier_light_thrust','rapier_riposte'].includes(a.id)&&t>=.35&&t<=.550001?{minFlex:24.3}:{}),
        desiredFlex:Math.max(-26,Math.min(26,50-Math.acos(Math.max(-1,Math.min(1,V.dot(fore,blade))))*180/Math.PI))};
    });
    if(['rapier_rise','rapier_down','rapier_cross','rapier_wrist_rise'].includes(a.id))for(let i=1;i<queries.length-1;i++)if(times[i]>=.35&&times[i]<=.55){
      const before=V.add(queries[i-1].palm,V.scale(queries[i-1].blade,82.6)),after=V.add(queries[i+1].palm,V.scale(queries[i+1].blade,82.6));
      if(Math.hypot(...V.sub(after,before))>.01)queries[i].cutTangent=V.norm(V.sub(after,before));
    }
    let planned;try{planned=planRapierPath(queries,{loop:a.loop==='loop',startHint:stage>0?solvedChainEnds.get(chainIds[stage-1]):null});}catch(error){throw new Error(`${a.id}: ${error.message}`);}
    if(stage>=0)solvedChainEnds.set(a.id,planned.states[times.findIndex(t=>Math.abs(t-.55)<.000001)]);
    sampled.forEach((entry,i)=>{Object.assign(entry[1],planned.poses[i]);entry[1].left_hand.rotation=[0,0,0];});
    anatomicalDiagnostics.push({action:a.id,...planned.diagnostics}); console.log(JSON.stringify({action:a.id,...planned.diagnostics}));
  }
  for(const [time,at]of sampled)for(const [bone,key]of Object.entries(at))(tracks[bone]??=[]).push({...key,time:Math.round(time*1000000)/1000000});
  a.tracks=tracks; a.interpolation='linear';
  a.layer=a.id==='rapier_stance'?'lower':(['rapier_thrust','rapier_reprise','rapier_combo'].includes(a.id)?'full':'upper');
  if(a.id==='rapier_thrust')a.events.unshift({time:0,name:'moving_lower_weight',payload:{value:.25}});
  if(a.id==='rapier_reprise')a.events.unshift({time:0,name:'moving_lower_weight',payload:{value:.15}});
  if(a.layer==='upper')for(const bone of ['root','right_leg','left_leg'])delete a.tracks[bone];
}
// The review is assembled from the final solved clips, at the actual configured
// combat clock. Solving its sparse pre-IK key poses again would create a different
// elbow path and defeat exact recovery-to-windup handoffs.
const linearSample=(a,t)=>Object.fromEntries(Object.entries(a.tracks).map(([bone,keys])=>{
 const i=Math.max(0,keys.findLastIndex(k=>k.time<=t)),lo=keys[i],hi=keys[Math.min(i+1,keys.length-1)],f=hi.time===lo.time?0:(t-lo.time)/(hi.time-lo.time);
 return[bone,{rotation:qangles(qslerp(quaternion(rotationMatrix(lo.rotation)),quaternion(rotationMatrix(hi.rotation)),f)),...(lo.position?{position:lo.position.map((v,j)=>v+((hi.position||lo.position)[j]-v)*f)}:{})}];
}));
const solvedCombo=actions.find(a=>a.id==='rapier_combo');solvedCombo.tracks={};let solvedOffset=0;
for(const [stage,id]of chainIds.entries()) {
 const clip=actions.find(a=>a.id===id),[windup,active,recovery,chain]=timings[stage],duration=chain/20;
 const timeAt=t=>t<=windup?.35*t/windup:t<=windup+active?.35+.2*(t-windup)/active:.55+.45*(t-windup-active)/recovery;
 const clipTimes=[0,duration,windup/20,(windup+active)/20,...Array.from({length:Math.ceil(duration*60)},(_,i)=>i/60)].sort((a,b)=>a-b);
 for(const dt of [...new Set(clipTimes)])for(const [bone,p]of Object.entries({...linearSample(clip,timeAt(dt*20)),root:idle.root,right_leg:idle.right_leg,left_leg:idle.left_leg}))(solvedCombo.tracks[bone]??=[]).push({...p,time:Math.round((solvedOffset+dt)*1e6)/1e6});
 solvedOffset+=duration;
}
for(const bone of Object.keys(solvedCombo.tracks))solvedCombo.tracks[bone]=[...new Map(solvedCombo.tracks[bone].map(k=>[k.time,k])).values()];
const parts=[{id:'root'},{id:'body',parent:'root',position:[0,75,0]},{id:'head',parent:'body',position:[0,75,0]},
  {id:'right_leg',parent:'root',position:[-11.875,75,0]},{id:'left_leg',parent:'root',position:[11.875,75,0]}];
for(const [side,x]of [['right',-37.5],['left',37.5]])parts.push({id:side+'_arm',parent:'body',position:[x,75,0]},{id:side+'_forearm',parent:side+'_arm',position:[0,-37.5,0]},{id:side+'_hand',parent:side+'_forearm',position:[0,-25,0]});
parts.push({id:'rapier',parent:'right_hand',position:[0,-6.25,0],rotation:[130,0,0]});
const geometry=[];const box=(part,material,position,size)=>geometry.push({kind:'box',values:[part,'set',material,position,size]});
box('body','coat',[-25,0,-12.5],[50,75,25]);box('body','trim',[-25.25,2,-12.75],[50.5,7,25.5]);box('body','trim',[-2,8,12.5],[4,57,1]);
box('head','skin',[-25,0,-25],[50,50,50]);box('head','hair',[-25.25,36,-25.25],[50.5,14.5,50.5]);box('head','eye',[-16,20,25],[6,4,1]);box('head','eye',[10,20,25],[6,4,1]);
for(const side of ['right','left']){box(side+'_leg','trousers',[-12.5,-60,-12.5],[25,60,25]);box(side+'_leg','boot',[-12.5,-75,-12.5],[25,15,25]);box(side+'_arm','coat',[-12.5,-37.5,-12.5],[25,37.5,25]);box(side+'_forearm','coat',[-12.5,-25,-12.5],[25,25,25]);box(side+'_hand','skin',[-12.5,-12.5,-12.5],[25,12.5,25]);}
await mkdir(evidence,{recursive:true});
await writeFile(join(evidence,'rapier-anatomy.json'),JSON.stringify(anatomicalDiagnostics,null,2));
if(process.argv.includes('--dry-run')){console.log(JSON.stringify(anatomicalDiagnostics));process.exit(0);}
await call('get_capabilities',{});
await call('create_resource',{kind:'model',id:'fsrpg.rapier',name:'西洋剑 · 50度击剑握柄与连贯发力 / 60Hz',modelSpec:{type:'actor',parts,geometry,materials:[{id:'coat',color:'#294b74'},{id:'trim',color:'#c7b185'},{id:'skin',color:'#dbaf87'},{id:'hair',color:'#3c302c'},{id:'eye',color:'#132a3d'},{id:'trousers',color:'#394757'},{id:'boot',color:'#292b31'}],sockets:[{id:'weapon_tip',part:'rapier',position:[0,98,0]},{id:'weapon_grip',part:'right_hand',position:[0,-6.25,0]}]}});
await call('dispatch_commands',{baseRevision:adapter.snapshot.revision,commands:actions.map(a=>({type:'addAction',targetId:null,patch:a}))});
await call('save_copy',{});
const exported=(await call('export_resource',{format:'fsvox'})).structuredContent.result;
await mkdir(resolve(output,'..'),{recursive:true});await copyFile(exported.path,output);
const saved=adapter.summary();await writeFile(join(root,'workbench/rapier-workspace/rapier-resource.json'),JSON.stringify({...saved,output,actions:actions.map(a=>a.id),forward:'+Z',units:'centimeters',phaseMarkers:[.35,.55],sampleRate,heldItemRenderer:'minecraft',gripAngleDegrees:50,curve:'continuous anatomical IK with configured-phase rate bounds, baked quaternion-linear',anatomicalDiagnostics,layers:{upper:actions.filter(a=>a.layer==='upper').map(a=>a.id),lower:['rapier_stance'],full:actions.filter(a=>a.layer==='full').map(a=>a.id)}},null,2));
await writeFile(join(evidence,'workbench-authoring.json'),JSON.stringify(log,null,2));
console.log(JSON.stringify({ok:true,...saved,output,actions:actions.map(a=>a.id)}));
if(process.argv.includes('--preview')){
  for(const [name,id,time,view]of [['idle','rapier_combo',0,'perspective'],['charge','rapier_thrust',.23,'side'],['thrust','rapier_thrust',.55,'side'],['rise','rapier_rise',.55,'perspective'],['cross','rapier_cross',.50,'perspective'],['wrist','rapier_wrist_rise',.55,'perspective'],['light-guard','rapier_guard',0,'front'],['heavy-guard','rapier_guard_heavy',0,'front'],['run','rapier_run',0,'perspective'],['breath','rapier_idle',1,'side'],['riposte','rapier_riposte',.55,'perspective'],['reprise','rapier_reprise',.55,'perspective']]){
    const r=await call('render_preview',{backend:'opengl',action:id,time,view,projection:'orthographic',width:900,height:700});
    const img=r.content.find(c=>c.type==='image');await writeFile(join(evidence,name+'.png'),Buffer.from(img.data,'base64'));
    await writeFile(join(evidence,name+'.json'),JSON.stringify(r.structuredContent.result.metadata,null,2));console.log(JSON.stringify({preview:name,complete:r.structuredContent.result.metadata.render.complete}));
  }
}
