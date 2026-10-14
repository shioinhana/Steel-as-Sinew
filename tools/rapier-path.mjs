// Anatomical fencing-grip IK. The official handle is fixed 50 degrees from the
// palm's long axis; flexion is bounded to 26 degrees, and net pronation to 120,
// shared equally between forearm and palm (each at most 60 degrees).
const R=Math.PI/180,U=37.5,F=25,K=6.25;
const add=(a,b)=>a.map((v,i)=>v+b[i]),sub=(a,b)=>a.map((v,i)=>v-b[i]),scale=(a,s)=>a.map(v=>v*s);
const dot=(a,b)=>a.reduce((s,v,i)=>s+v*b[i],0),cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
const norm=a=>scale(a,1/Math.hypot(...a)),clamp=(v)=>Math.max(-1,Math.min(1,v));
const trans=a=>[a[0],a[3],a[6],a[1],a[4],a[7],a[2],a[5],a[8]];
const mul=(a,b)=>Array.from({length:9},(_,i)=>{const r=Math.floor(i/3),c=i%3;return a[r*3]*b[c]+a[r*3+1]*b[c+3]+a[r*3+2]*b[c+6];});
const xf=(m,p)=>[dot(m.slice(0,3),p),dot(m.slice(3,6),p),dot(m.slice(6,9),p)];
const matrix=(x,y,z)=>[x[0],y[0],z[0],x[1],y[1],z[1],x[2],y[2],z[2]];
const rx=a=>{const c=Math.cos(a*R),s=Math.sin(a*R);return[1,0,0,0,c,-s,0,s,c];};
const ry=a=>{const c=Math.cos(a*R),s=Math.sin(a*R);return[c,0,s,0,1,0,-s,0,c];};
const euler=m=>Math.abs(m[2])>1-1e-10?[Math.atan2(m[7],m[4])/R,Math.asin(clamp(m[2]))/R,0]:[Math.atan2(-m[5],m[8])/R,Math.asin(clamp(m[2]))/R,Math.atan2(-m[1],m[0])/R];
const distance=(a,b)=>Math.acos(clamp((dot(a,b)-1)/2))/R;
function solve(q,theta,sign) {
 let palm=q.palm, wrist=sub(palm,scale(norm(sub(palm,q.shoulder)),K));
 let upper,lower,hand,elbow,flex,twist,bend;
 for(let iteration=0;iteration<7;iteration++) {
  const d=sub(wrist,q.shoulder),length=Math.hypot(...d);
  if(length<14||length>62.48)return null;
  const axis=scale(d,1/length),along=(length*length+U*U-F*F)/(2*length);
  let reference=sub(q.pole,scale(axis,dot(q.pole,axis)));
  if(Math.hypot(...reference)<.01)reference=cross(axis,[0,0,1]);
  const v=norm(reference),t=norm(cross(axis,v));
  const radial=add(scale(v,Math.cos(theta)),scale(t,Math.sin(theta)));
  elbow=add(add(q.shoulder,scale(axis,along)),scale(radial,Math.sqrt(Math.max(0,U*U-along*along))));
  const u=norm(sub(elbow,q.shoulder)),f=norm(sub(wrist,elbow)),hinge=scale(norm(cross(axis,radial)),sign);
  upper=matrix(hinge,scale(u,-1),norm(cross(hinge,scale(u,-1))));
  lower=matrix(hinge,scale(f,-1),norm(cross(hinge,scale(f,-1))));
  const blade=xf(trans(lower),q.blade),angle=Math.acos(clamp(-blade[1]))/R;
  flex=50-angle;twist=Math.atan2(blade[0],blade[2])/R;
  hand=mul(lower,mul(ry(twist),rx(flex)));
  wrist=sub(palm,xf(hand,[0,-K,0]));
 }
 const actualPalm=add(elbow,add(xf(lower,[0,-F,0]),xf(hand,[0,-K,0])));
 const error=Math.hypot(...sub(actualPalm,palm));
 bend=euler(mul(trans(upper),lower))[0];
 if(Math.abs(flex)>26.0001||flex<(q.minFlex??-26.0001)||flex>(q.maxFlex??26.0001)||Math.abs(twist)>120||Math.abs(bend)>140||error>.15)return null;
 const pronation=twist*.5,foreLocal=mul(rx(bend),ry(pronation));
 const upperLocal=mul(trans(q.parent),upper),handLocal=mul(ry(twist-pronation),rx(flex));
 const finalLower=mul(lower,ry(pronation));
 return {upper,lower:finalLower,hand,elbow,flex,twist,error,bend,pose:{right_arm:{rotation:euler(upperLocal)},right_forearm:{rotation:euler(foreLocal)},right_hand:{rotation:euler(handLocal)},rapier:{rotation:[0,0,0]}},cost:((flex-(q.desiredFlex||0))/8)**2+(twist/75)**2*.6+(Math.abs(bend)/140)**2*.15};
}
export function planRapierPath(queries,{maxStep=32,loop=false,startHint=null}={}) {
 const pools=queries.map((q,i)=>{
  const all=[];for(let deg=0;deg<360;deg+=3)for(const sign of[-1]){const v=solve(q,deg*R,sign);if(v)all.push(v);}
  if(!all.length)throw new Error(`Rapier frame ${i} has no legal fencing grip; palm=${q.palm}, blade=${q.blade}`);
  if(q.cutTangent)for(const v of all){v.slip=Math.abs(dot(xf(v.hand,[1,0,0]),q.cutTangent));v.cost+=2500*v.slip*v.slip;}
  all.sort((a,b)=>a.cost-b.cost);
  if(i===0&&startHint)all.sort((a,b)=>['upper','lower','hand'].reduce((s,k)=>s+distance(a[k],startHint[k]),0)-['upper','lower','hand'].reduce((s,k)=>s+distance(b[k],startHint[k]),0));
  return i===0||i===queries.length-1?[all[0]]:all;
 });
 let costs=[pools[0][0].cost],parents=[];
 for(let f=1;f<pools.length;f++) {
  const current=pools[f],previous=pools[f-1],next=new Array(current.length).fill(Infinity),indices=new Int32Array(current.length).fill(-1);
  for(let j=0;j<current.length;j++)for(let i=0;i<previous.length;i++)if(Number.isFinite(costs[i])) {
   const a=previous[i],b=current[j];if(a.bend*b.bend<0)continue;
   const steps=[distance(a.upper,b.upper),distance(a.lower,b.lower),distance(a.hand,b.hand)];
   const rate=Math.max(queries[f-1].sourceStep||1/60,queries[f].sourceStep||1/60);
   const allowed=queries[f].time===undefined?maxStep:42*(queries[f].time-queries[f-1].time)/rate;
   if(Math.max(...steps)>Math.min(maxStep,allowed))continue;
   const cost=costs[i]+steps.reduce((s,v)=>s+v*v,0)+Math.hypot(...sub(a.elbow,b.elbow))**2+current[j].cost*3;
   if(cost<next[j]){next[j]=cost;indices[j]=i;}
  }
  if(next.every(x=>!Number.isFinite(x))){let nearest=Infinity;for(let i=0;i<previous.length;i++)if(Number.isFinite(costs[i]))for(const b of current)if(previous[i].bend*b.bend>=0)nearest=Math.min(nearest,...[Math.max(...['upper','lower','hand'].map(k=>distance(previous[i][k],b[k])))]);throw new Error(`Rapier anatomical path discontinuity at frame ${f}; nearest ${nearest.toFixed(2)} degrees; branches ${previous.filter((x,i)=>Number.isFinite(costs[i])).map(x=>x.bend.toFixed(1)).slice(0,3)} -> ${current.map(x=>x.bend.toFixed(1)).slice(0,3)}`);}
  parents.push(indices);costs=next;
 }
 let index=0;const selected=new Array(pools.length);
 for(let f=pools.length-1;f>=0;f--){selected[f]=pools[f][index];if(f)index=parents[f-1][index];}
 const slips=selected.filter(s=>s.slip!==undefined).map(s=>s.slip);
 const diagnostics={frames:selected.length,maxWristFlex:0,maxNetPronation:0,maxForearmPronation:0,maxWristPronation:0,maxAuthoredPalmAdjustment:0,maxPalmError:0,maxWorldStep:0,maxElbowBend:0,activeBladeFaceSlipMean:slips.length?slips.reduce((s,x)=>s+x,0)/slips.length:null,activeBladeFaceSlipMax:slips.length?Math.max(...slips):null};
 selected.forEach((s,i)=>{diagnostics.maxWristFlex=Math.max(diagnostics.maxWristFlex,Math.abs(s.flex));diagnostics.maxNetPronation=Math.max(diagnostics.maxNetPronation,Math.abs(s.twist));diagnostics.maxForearmPronation=Math.max(diagnostics.maxForearmPronation,Math.abs(s.twist*.5));diagnostics.maxWristPronation=diagnostics.maxForearmPronation;diagnostics.maxAuthoredPalmAdjustment=Math.max(diagnostics.maxAuthoredPalmAdjustment,s.adjustment||0);diagnostics.maxPalmError=Math.max(diagnostics.maxPalmError,s.error);diagnostics.maxElbowBend=Math.max(diagnostics.maxElbowBend,Math.abs(s.bend));if(i)for(const key of['upper','lower','hand'])diagnostics.maxWorldStep=Math.max(diagnostics.maxWorldStep,distance(selected[i-1][key],s[key]));});
 return{poses:selected.map(s=>s.pose),diagnostics,states:selected};
}
export function nearbyRapierGrip(query,twistLimit=95) {
 let best=null;
 for(const dx of[-30,-20,-10,0,10,20,30])for(const dy of[-20,-10,0,10,20,30])for(const dz of[-20,-10,0,10,20]) {
  const q={...query,palm:add(query.palm,[dx,dy,dz])};
  const displacement=dx*dx+dy*dy+dz*dz;
  if(best&&displacement>best.cost)continue;
  for(let degree=0;degree<360;degree+=6){const v=solve(q,degree*R,-1);if(v&&Math.abs(v.twist)<=twistLimit){const cost=displacement+v.cost;if(!best||cost<best.cost)best={palm:q.palm,cost,flex:v.flex,twist:v.twist,bend:v.bend};}}
 }
 return best;
}
