"""Author the 0.10.0 rapier: thrust, rising cut, diagonal cut, return cut, lunge.

The official ItemStack's calibrated blade axis is carried by a rigid grip.  The
forearm has an anatomical hinge plus bounded pronation; the hand supplies only
the shortest bounded flexion to the blade.  TP and FP use the same phrase intent,
but independent reachable hand paths.  No geometry is replaced.
"""
from __future__ import annotations
from copy import deepcopy
from pathlib import Path
import importlib.util
import hashlib
import json
import math

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / 'src/main/resources/assets/fsrpg/fsvoxel/models'
MANIFEST = ROOT / 'docs/rapier-fencing-0.10.0-manifest.json'
BASELINE = {'legacy_rapier.fsvoxel':'7a03daf4fb62df9677d89d1d1a6379d905a2954a060d750f56c2469889dd6881',
            'legacy_rapier_fp.fsvoxel':'4e499e54aeff95c3a782ecc5aedbf8dc03057470e4bfa3490d8867a6cc3aa1d4'}
loader=importlib.util.spec_from_file_location('fencing_math',ROOT/'tools/rework-rapier-fencing-085.py')
old=importlib.util.module_from_spec(loader);loader.loader.exec_module(old)
add,sub,mul,dot,cross,unit=old.add,old.sub,old.mul,old.dot,old.cross,old.unit
transpose,mm,mv,columns,xyz,euler=old.transpose,old.mm,old.mv,old.columns,old.xyz,old.euler
TIMES=[0,.14,.26,.56,.72,.86,1]
NAMES=['direct-thrust','rising-cut','diagonal-cut','return-cut','lunge-thrust']
CONTROLLED={'body','head','hip','right_arm','right_forearm','right_hand','right_item','left_arm','left_forearm','left_hand'}

def sha(data):return hashlib.sha256(data).hexdigest()
def norm(v):return math.sqrt(dot(v,v))
def angle(a,b):return math.degrees(math.acos(max(-1,min(1,dot(unit(a),unit(b))))))
def state(hand,yaw=0,pitch=0,body=None,pronation=0):
    return dict(hand=hand,yaw=yaw,pitch=pitch,body=body or [0,0,0],pronation=pronation)

GUARD=state([-18,-2,78],0,4,[-.6,-4,-.4],5)
HANDOFFS=[GUARD,
    state([-18,6,94],-8,-12,[.5,-2,-.3],-9),
    state([0,31,83],24,38,[.2,-9,-1.4],-28),
    state([-34,-3,86],-24,-16,[.3,8,1],24),
    state([-8,8,84],9,2,[0,-4,-.2],-12),GUARD]
PREP=[state([-18,-5,75],0,4,[-1,-3,-.4],4),
      state([-30,-13,78],-25,-25,[-.8,5,1],-24),
      state([-3,35,78],26,38,[-.5,-8,-1],-30),
      state([-36,1,79],-30,-3,[-.7,7,1.2],26),
      state([-17,-4,75],1,3,[-1.5,-3,0],4)]
CONTACT=[state([-17,12,104],-1,1,[1.1,-1,-.3],3),
         state([-8,20,99],10,20,[1,-7,-1],-22),
         state([-18,5,100],-8,-8,[1.4,5,1],18),
         state([5,13,94],22,5,[1,-7,-1],-18),
         state([-15,16,107],0,1,[3,0,0],4)]
CHAMBER=state([-27,-9,65],3,10,[-1.8,1,.4],-5)

def blend(a,b,w):
    return {k:([a[k][i]+(b[k][i]-a[k][i])*w for i in range(len(a[k]))] if isinstance(a[k],list)
               else a[k]+(b[k]-a[k])*w) for k in a}

def interpolate(values,t):
    slopes=[(values[i+1]-values[i])/(TIMES[i+1]-TIMES[i]) for i in range(6)]
    tangent=[0]+[0 if slopes[i-1]*slopes[i]<=0 else 2*slopes[i-1]*slopes[i]/(slopes[i-1]+slopes[i]) for i in range(1,6)]+[0]
    # Both alternatives after the queued boundary start with zero acceleration
    # impulse: no hidden reversal from the unqueued recovery influences .86.
    tangent[5]=0
    i=next((i for i in range(6) if t<=TIMES[i+1]),5);span=TIMES[i+1]-TIMES[i];u=(t-TIMES[i])/span
    return (2*u**3-3*u*u+1)*values[i]+(u**3-2*u*u+u)*span*tangent[i]+(-2*u**3+3*u*u)*values[i+1]+(u**3-u*u)*span*tangent[i+1]

def spec_at(points,t):
    return {k:([interpolate([p[k][i] for p in points],t) for i in range(len(points[0][k]))]
               if isinstance(points[0][k],list) else interpolate([p[k] for p in points],t)) for k in points[0]}

def rotation_between(a,b):
    a,b=unit(a),unit(b);v=cross(a,b);c=dot(a,b)
    if norm(v)<1e-9:return xyz([0,0,0])
    k=[[0,-v[2],v[1]],[v[2],0,-v[0]],[-v[1],v[0],0]];kk=mm(k,k)
    return [[(1 if i==j else 0)+k[i][j]+kk[i][j]/(1+c) for j in range(3)] for i in range(3)]

def bounded_direction(forearm,requested,limit=34):
    theta=angle(forearm,requested)
    if theta<=limit:return requested
    theta=math.radians(theta);u=math.radians(limit)/theta
    return unit(add(mul(forearm,math.sin((1-u)*theta)/math.sin(theta)),mul(requested,math.sin(u*theta)/math.sin(theta))))

def official_axis(fp):
    a=math.radians(41.987212496+45)
    return unit([-math.cos(a),math.sin(a),0] if fp else [-math.cos(a),0,math.sin(a)])

def item_mount(fp):
    local=official_axis(fp);seed=[0,0,1] if fp else [0,1,0]
    lx=unit(cross(seed,local));ly=cross(local,lx)
    return mm(columns([1,0,0],[0,0,1],[0,-1,0]),transpose(columns(lx,ly,local)))

def solve_arm(body,shoulder,target,pole,pronation=0):
    a,b,forearm=old.arm(body,shoulder,target,pole)
    b=mm(b,xyz([0,pronation,0]));forearm=mm(mm(body,a),b)
    shoulder_world=mv(body,shoulder);elbow=add(shoulder_world,mv(mm(body,a),[0,-37.5,0]))
    return a,b,forearm,shoulder_world,elbow

def pose_at(spec,fp,diagnostics=False):
    body=mm(xyz([0,65,0]),xyz(spec['body']))
    target=spec['hand'];shoulder=[-37.5,31.25,0]
    if fp:
        body=xyz([0,0,0]);shoulder=[-25,-51.953125,.390625]
        target=[-19+(target[0]+18)*.48,-57+(target[1]+2)*.43,55+(target[2]-78)*.57]
    a,b,forearm,shoulder_world,elbow=solve_arm(body,shoulder,target,[-.62,-1,-.12] if not fp else [-.55,-1,0],spec['pronation'])
    yaw,pitch=map(math.radians,[spec['yaw'],spec['pitch']])
    requested=[math.sin(yaw)*math.cos(pitch),math.sin(pitch),math.cos(yaw)*math.cos(pitch)]
    lower=mv(forearm,[0,-1,0]);direction=bounded_direction(lower,requested)
    wrist=rotation_between([0,-1,0],mv(transpose(forearm),direction))
    hand_world=mm(forearm,wrist)
    out=dict(right_arm=euler(a),right_forearm=euler(b),right_item=euler(mm(wrist,item_mount(fp)) if fp else item_mount(fp)))
    if fp:out['left_arm']=[31+(spec['hand'][2]-78)*.12,-8-spec['body'][1]*.35,15]
    else:
        out.update(body=spec['body'],hip=[0,65,0],head=euler(transpose(body)),right_hand=euler(wrist))
        # Free hand remains behind and beside the rib cage, elbow softly bent;
        # twist drives a small counter-swing, extension opens the balancing arm.
        extension=max(0,min(1,(target[2]-78)/29))
        left_target=mv(body,[48+extension*3,-4-extension*3,-45-extension*10])
        la,lb,lw,ls,le=solve_arm(body,[37.5,31.25,0],left_target,mv(body,[1,-.8,-.25]),-8)
        out.update(left_arm=euler(la),left_forearm=euler(lb),left_hand=[0,0,0])
    info=dict(shoulder=shoulder_world,elbow=elbow,wrist=target,blade=direction,tip=add(target,mul(direction,75)),
              wristBend=angle(lower,direction),forearm=lower,body=body,pronation=spec['pronation'])
    if not fp:info.update(leftShoulder=ls,leftElbow=le,leftWrist=left_target)
    return (out,info) if diagnostics else out

def stages(index):
    start,end=HANDOFFS[index:index+2];pre,contact=PREP[index],CONTACT[index]
    return [start,blend(start,pre,.60),pre,contact,blend(contact,end,.5),end,GUARD]

def branch_stages(name):
    if name=='charged-thrust':
        return [CHAMBER,blend(CHAMBER,PREP[4],.3),PREP[4],CONTACT[4],blend(CONTACT[4],GUARD,.45),blend(CONTACT[4],GUARD,.72),GUARD]
    if name=='riposte':
        parry=state([-30,10,82],-13,15,[0,4,.5],-22)
        return [GUARD,blend(GUARD,parry,.6),parry,CONTACT[0],blend(CONTACT[0],GUARD,.5),blend(CONTACT[0],GUARD,.8),GUARD]
    if name=='reprise':
        withdraw=state([-23,-4,72],8,6,[-2.2,3,.5],9)
        return [GUARD,blend(GUARD,withdraw,.6),withdraw,CONTACT[4],blend(CONTACT[4],GUARD,.5),blend(CONTACT[4],GUARD,.8),GUARD]
    raise ValueError(name)

def author(template,points,fp,phrase,duration):
    out=deepcopy(template);out.update(duration=duration,transition=0,loop='once',interpolation='linear',rotation_mode='quaternion')
    out['tags']=[t for t in out.get('tags',[]) if not t.startswith(('rapier_rework:','rapier_recoil:','rapier_fencing:','rapier_phrase:','rapier_guard:','source_hz:'))]+['source_hz:120','rapier_fencing:0100','rapier_phrase:'+phrase,'rapier_recoil:'+str(1 if phrase in ('rising-cut','return-cut') else -1)]
    # Motion in these files is the upper phrase.  Runtime support owns the lower
    # half; retaining a historical root bob would detach its phase at handoff.
    out['tracks']={}
    if not fp:out['layer']='upper'
    count=math.ceil(duration*120);previous={}
    for t in sorted(set([n/count for n in range(count+1)]+TIMES)):
        for bone,angles in pose_at(spec_at(points,t),fp).items():
            if bone in previous:angles=[v+360*round((previous[bone][i]-v)/360) for i,v in enumerate(angles)]
            previous[bone]=angles
            out['tracks'].setdefault(bone,[]).append(dict(time=round(duration*t,9),rotation=[round(v,6) for v in angles],position=[0,0,0],scale=[1,1,1]))
    return out

def guard(template,fp,charge=False,full=False):
    out=deepcopy(template);out['transition']=0
    out['tags']=[t for t in out.get('tags',[]) if not t.startswith(('rapier_guard:','rapier_charge:','rapier_fencing:','rapier_phrase:','source_hz:'))]+['source_hz:120','rapier_charge:0100' if charge else 'rapier_guard:0100']
    out['tracks']={k:v for k,v in out['tracks'].items() if k not in CONTROLLED}
    count=math.ceil(out['duration']*120)
    for n in range(count+1):
        t=n/count;spec=CHAMBER if full else blend(GUARD,CHAMBER,t*t*(3-2*t)) if charge else GUARD
        for bone,angles in pose_at(spec,fp).items():
            out['tracks'].setdefault(bone,[]).append(dict(time=round(out['duration']*t,9),rotation=[round(v,6) for v in angles],position=[0,0,0],scale=[1,1,1]))
    return out

def main():
    previous=json.loads(MANIFEST.read_text(encoding='utf8')) if MANIFEST.exists() else {}
    rows=[];assets=[]
    for fp in [False,True]:
        name='legacy_rapier_fp.fsvoxel' if fp else 'legacy_rapier.fsvoxel';path=MODELS/name;data=path.read_bytes()
        last=next((r for r in previous.get('assets',[]) if r['relativePath']=='models/'+name),{})
        assert sha(data) in {BASELINE[name],last.get('afterSha256')},'Unexpected input model; refusing to replace external edits'
        lines=data.decode('utf8').splitlines();clips={v['id']:v for l in lines if l.startswith('action\t') for v in [json.loads(l.split('\t',1)[1])]};authored={}
        for i in range(5):
            for id in [f'legacy_rapier_attack{i+1}',f'legacy_rapier_attack{i+1}_upper',f'rapier_attack{i+1}']:
                authored[id]=author(clips[id],stages(i),fp,NAMES[i],.6 if i==4 else .45)
        for id,clip in clips.items():
            if any(q in id for q in ['static_idle','idle_upper','walk_hold','run_hold']) or id in {'rapier_idle','rapier_stance','rapier_run'}:authored[id]=guard(clip,fp)
            if 'rapier_thrust_hit' in id or 'rapier_thrust_miss' in id:authored[id]=author(clip,branch_stages('charged-thrust'),fp,'charged-thrust',.55)
            if id in {'rapier_charge','rapier_full_charge'}:authored[id]=guard(clip,fp,charge=True,full=id.endswith('full_charge'))
            if id in {'rapier_counter','legacy_rapier_attack_dual','legacy_rapier_attack_dual_upper'}:authored[id]=author(clip,branch_stages('riposte'),fp,'riposte',.65)
        for id,duration in [('rapier_riposte',.65),('rapier_reprise',.9)]:
            template=deepcopy(clips['legacy_rapier_attack1_upper']);template['id']=id;template['name']=id
            template['tags']=[t for t in template['tags'] if not t.startswith('source:')]+['source:'+id]
            authored[id]=author(template,branch_stages(id.removeprefix('rapier_')),fp,id.removeprefix('rapier_'),duration)
        output=[];written=set()
        for line in lines:
            if line.startswith('summary\t'):line='summary\t'+json.dumps(dict(kind='model',purpose='rapier_fencing_0100',status='authored_rework'),separators=(',',':'))
            if line.startswith('action\t'):
                id=json.loads(line.split('\t',1)[1])['id']
                if id in authored:line='action\t'+json.dumps(authored[id],ensure_ascii=False,separators=(',',':'));written.add(id)
            output.append(line)
        for id in sorted(set(authored)-written):output.append('action\t'+json.dumps(authored[id],ensure_ascii=False,separators=(',',':')))
        after=('\n'.join(output)+'\n').encode('utf8');path.write_bytes(after)
        config=ROOT/'config/fsrpg/models'/name
        config.parent.mkdir(parents=True,exist_ok=True);config.write_bytes(after)
        assert [l for l in lines if not l.startswith(('action\t','summary\t'))]==[l for l in output if not l.startswith(('action\t','summary\t'))]
        rows.append(dict(path=path.relative_to(ROOT).as_posix(),changedActions=sorted(authored),unchangedActions=len(set(clips)-set(authored)),geometryUnchanged=True,officialItemCarrierUnchanged=True))
        assets.append(dict(entry='assets/fsrpg/fsvoxel/models/'+name,relativePath='models/'+name,beforeSha256=BASELINE[name],afterSha256=sha(after)))
    MANIFEST.write_text(json.dumps(dict(format='fsrpg.rapier-fencing.v2',version='0.10.0',samplingHz=120,
        sourcePhase=dict(windupEnd=.26,contact=.56,activeEnd=.86),phrases=NAMES,branches=['charged-thrust','riposte','reprise'],
        handoff='Previous ACTIVE end equals next source first pose; unqueued recovery returns to guard',
        anatomy=dict(armSegmentCm=37.5,maxWristFlexionDegrees=34,maxForearmPronationDegrees=30,baseStanceDegrees=65,maxTorsoExtraYawDegrees=9),files=rows,assets=assets),ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps(assets,indent=2))
if __name__=='__main__':main()
