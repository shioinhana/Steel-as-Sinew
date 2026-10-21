"""Author five compact rapier phrases in the existing rigs; no mesh or item replacement.

Standard-library-only authoring: Cartesian hand paths, anatomical two-link arms,
and an independently aimed official-item locator sampled at 120 Hz.  The active
end is the next phrase's first pose; an unqueued recovery returns to guard.
"""
from __future__ import annotations
from copy import deepcopy
from pathlib import Path
import hashlib
import json
import math

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / 'src/main/resources/assets/fsrpg/fsvoxel/models'
MANIFEST = ROOT / 'docs/rapier-fencing-0.8.5-manifest.json'
BEFORE = {'legacy_rapier.fsvoxel': '6c23401a5010c59d1d1a01dff6a48be1684b3ecd0f2f072652530507d962123a',
          'legacy_rapier_fp.fsvoxel': '227b5cfdf5e709eed25db0e2b88c557feccd2bb97c6dc39e7794716ba1f5de07'}
TIMES = [0, .16, .26, .56, .72, .86, 1]
NAMES = ['direct-thrust', 'disengage-thrust', 'opposition-thrust', 'short-return-cut', 'lunge-thrust']

def sha(data): return hashlib.sha256(data).hexdigest()
def add(a, b): return [a[i] + b[i] for i in range(3)]
def sub(a, b): return [a[i] - b[i] for i in range(3)]
def mul(a, x): return [v * x for v in a]
def dot(a, b): return sum(a[i] * b[i] for i in range(3))
def cross(a, b): return [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
def unit(a): return mul(a, 1 / math.sqrt(dot(a, a)))
def transpose(a): return [list(x) for x in zip(*a)]
def mm(a, b): return [[dot(row, col) for col in zip(*b)] for row in a]
def mv(a, b): return [dot(row, b) for row in a]
def columns(x, y, z): return transpose([x, y, z])
def xyz(e):
    x, y, z = map(math.radians, e)
    rx = [[1,0,0],[0,math.cos(x),-math.sin(x)],[0,math.sin(x),math.cos(x)]]
    ry = [[math.cos(y),0,math.sin(y)],[0,1,0],[-math.sin(y),0,math.cos(y)]]
    rz = [[math.cos(z),-math.sin(z),0],[math.sin(z),math.cos(z),0],[0,0,1]]
    return mm(mm(rx, ry), rz)
def euler(a):
    y = math.asin(max(-1, min(1, a[0][2])))
    assert abs(math.cos(y)) > 1e-5, 'Avoid the imported XYZ Euler singularity'
    return [math.degrees(math.atan2(-a[1][2], a[2][2])), math.degrees(y), math.degrees(math.atan2(-a[0][1], a[0][0]))]
def interpolate(values, t):
    slopes = [(values[i+1]-values[i])/(TIMES[i+1]-TIMES[i]) for i in range(6)]
    tangent = [slopes[0]*.65] + [0 if slopes[i-1]*slopes[i] <= 0 else 2*slopes[i-1]*slopes[i]/(slopes[i-1]+slopes[i]) for i in range(1,6)] + [0]
    i = next((i for i in range(6) if t <= TIMES[i+1]), 5)
    span = TIMES[i+1]-TIMES[i]; u = (t-TIMES[i])/span
    return (2*u**3-3*u*u+1)*values[i]+(u**3-2*u*u+u)*span*tangent[i]+(-2*u**3+3*u*u)*values[i+1]+(u**3-u*u)*span*tangent[i+1]
def state(hand, yaw=0, pitch=0, body=0, roll=0):
    return dict(hand=hand, yaw=yaw, pitch=pitch, body=body, roll=roll)

# A point-forward, somewhat bent guard. Handoffs retain the preceding blade line,
# rather than passing through the default stance between every input.
GUARD = state([-15,-4,78], 0, 3, -.7)
HANDOFFS = [GUARD, state([-14,10,101],-2,2,.6), state([-8,12,99],5,0,.4),
            state([-20,10,99],-5,2,.5), state([-23,0,84],13,3,-.4), GUARD]
PREP = [state([-15,-5,75],0,4,-.9), state([-19,3,87],-9,-7,-.6),
        state([-5,5,88],13,2,-.6), state([-28,6,85],19,4,-.5), state([-15,-6,78],1,3,-1.4)]
CONTACT = [state([-14,14,105],-1,1,1.2), state([-6,16,104],4,0,1.3),
           state([-20,15,104],-4,1,1.6), state([4,12,94],-15,0,1.1), state([-14,17,107],0,1,3.5)]

def arm(body, shoulder, target, pole):
    shoulder = mv(body, shoulder); d = sub(target, shoulder); length = math.sqrt(dot(d,d))
    assert 10 < length < 74.8, ('Unreachable hand', target, length)
    axis = unit(d); p = unit(sub(pole, mul(axis, dot(pole,axis))))
    elbow = add(add(shoulder,mul(d,.5)),mul(p,math.sqrt(37.5**2-(length*.5)**2)))
    upper = unit(sub(elbow,shoulder)); lower = unit(sub(target,elbow))
    hinge = unit(mul(cross(upper,lower),-1))
    a = columns(hinge,mul(upper,-1),unit(cross(hinge,mul(upper,-1))))
    b = columns(hinge,mul(lower,-1),unit(cross(hinge,mul(lower,-1))))
    return mm(transpose(body),a), mm(transpose(a),b), b

def item_rotation(hand_world, direction, fp, roll):
    # Official sprite's diagonal after the unchanged native carrier transform.
    # TP has the additional existing -90 X mount; FP does not.
    angle=math.radians(41.987212496+45)
    local=unit([-math.cos(angle),math.sin(angle),0] if fp else [-math.cos(angle),0,math.sin(angle)])
    seed=[0,0,1] if fp else [0,1,0]
    lx=unit(cross(seed,local));ly=cross(local,lx)
    wx=unit(sub([1,0,0],mul(direction,direction[0])));wy=cross(direction,wx)
    r=math.radians(roll);x=add(mul(wx,math.cos(r)),mul(wy,math.sin(r)));y=cross(direction,x)
    world=mm(columns(x,y,direction),transpose(columns(lx,ly,local)))
    return mm(transpose(hand_world),world)

def pose_at(spec, fp, extension):
    body=mm(xyz([0,65,0]),xyz([spec['body'],0,0]))
    target=spec['hand']
    if fp:
        # The independent FP rig has a lower shoulder. Keep its hilt in the lower
        # screen, and project the same hand extension and blade line toward +Z.
        body=xyz([0,0,0]); target=[-19+target[0]*.13,-58+(target[1]+4)*.28,42+(target[2]-78)*.72]
        shoulder=[-25,-51.953125,.390625]
    else: shoulder=[-37.5,31.25,0]
    a,b,hand=arm(body,shoulder,target,[-.8,-1,-.12] if not fp else [-.7,-1,0])
    yaw,pitch=map(math.radians,[spec['yaw'],spec['pitch']]);direction=[math.sin(yaw)*math.cos(pitch),math.sin(pitch),math.cos(yaw)*math.cos(pitch)]
    out={'right_arm':euler(a),'right_forearm':euler(b),'right_item':euler(item_rotation(hand,direction,fp,12+spec['roll']))}
    if fp:
        out['left_arm']=[30+extension*8,12,15]
    else:
        out['body']=[spec['body'],0,0];out['right_hand']=[0,0,0]
        la,lb,_=arm(body,[37.5,31.25,0],[35,0,-51-extension*17],[.7,-1,-.1])
        out.update(left_arm=euler(la),left_forearm=euler(lb),left_hand=[0,0,0])
    return out

def stages(index):
    start,end=HANDOFFS[index],HANDOFFS[index+1]
    early=deepcopy(PREP[index]);pre=deepcopy(PREP[index]);contact=deepcopy(CONTACT[index])
    # Cavazione drops under a blade and changes line before extending; opposition
    # presses a small outside line. Neither raises a sword over the head.
    early['hand']=[start['hand'][i]+(pre['hand'][i]-start['hand'][i])*.6 for i in range(3)]
    follow={k:([contact[k][i]+(end[k][i]-contact[k][i])*.36 for i in range(3)] if k=='hand' else contact[k]+(end[k]-contact[k])*.36) for k in contact}
    return [start,early,pre,contact,follow,end,GUARD]

def author(template,index,fp):
    out=deepcopy(template);out['duration']=.6 if index==4 else .45;out['transition']=0
    out['tags']=[t for t in out.get('tags',[]) if not t.startswith(('rapier_rework:','rapier_recoil:','rapier_fencing:','rapier_phrase:','source_hz:'))]+['source_hz:120','rapier_fencing:085','rapier_phrase:'+NAMES[index],'rapier_recoil:'+str([-1,1,-1,1,-1][index])]
    out['tracks']={k:v for k,v in out['tracks'].items() if k not in {'body','right_arm','right_forearm','right_hand','right_item','left_arm','left_forearm','left_hand'}}
    if not fp:
        out['tracks']['hip']=[dict(time=0,rotation=[0,65,0],position=[0,0,0],scale=[1,1,1])]
    points=stages(index);count=round(out['duration']*120);previous={}
    for t in sorted(set([n/count for n in range(count+1)]+TIMES)):
        spec={k:([interpolate([p[k][i] for p in points],t) for i in range(3)] if k=='hand' else interpolate([p[k] for p in points],t)) for k in points[0]}
        extension=max(0,min(1,(spec['hand'][2]-78)/29));pose=pose_at(spec,fp,extension)
        for bone,angles in pose.items():
            # Adjacent numeric Euler spellings remain close; the engine still
            # uses quaternion interpolation between the 120 Hz source samples.
            if bone in previous:
                angles=[v+360*round((previous[bone][i]-v)/360) for i,v in enumerate(angles)]
            previous[bone]=angles
            out['tracks'].setdefault(bone,[]).append(dict(time=round(out['duration']*t,9),rotation=[round(v,6) for v in angles],position=[0,0,0],scale=[1,1,1]))
    return out

def guard(template,fp):
    out=deepcopy(template);out['transition']=0
    out['tags']=[t for t in out.get('tags',[]) if not t.startswith(('rapier_guard:','source_hz:'))]+['source_hz:120','rapier_guard:085']
    p=pose_at(GUARD,fp,0);count=math.ceil(out['duration']*120)
    for bone,angles in p.items():
        out['tracks'][bone]=[dict(time=round(out['duration']*n/count,9),rotation=[round(v,6) for v in angles],position=[0,0,0],scale=[1,1,1]) for n in range(count+1)]
    return out

def main():
    previous=json.loads(MANIFEST.read_text(encoding='utf8')) if MANIFEST.exists() else {}
    rows=[];all_before={p.name:sha(p.read_bytes()) for p in MODELS.glob('*.fsvoxel')}
    for fp in [False,True]:
        name='legacy_rapier_fp.fsvoxel' if fp else 'legacy_rapier.fsvoxel';path=MODELS/name;data=path.read_bytes()
        last=next((r for r in previous.get('assets',[]) if r['relativePath']=='models/'+name),{})
        assert sha(data) in {BEFORE[name],last.get('afterSha256')}, 'Input is not 084 stock or the preceding generated 085 asset'
        lines=data.decode('utf8').splitlines();clips={v['id']:v for l in lines if l.startswith('action\t') for v in [json.loads(l.split('\t',1)[1])]}
        authored={}
        for i in range(5):
            for id in [f'legacy_rapier_attack{i+1}',f'legacy_rapier_attack{i+1}_upper',f'rapier_attack{i+1}']:
                template=deepcopy(clips.get(id,clips['legacy_rapier_attack4_upper']))
                template['id']=id;template['name']=id.removeprefix('legacy_rapier_')
                template['tags']=[t for t in template.get('tags',[]) if not t.startswith('source:')]+['source:attack'+str(i+1)+('_upper' if id.endswith('_upper') else '')]
                authored[id]=author(template,i,fp)
        for id,clip in clips.items():
            if any(q in id for q in ['static_idle','idle_upper','walk_hold','run_hold']) or id in {'rapier_idle','rapier_stance','rapier_run'}:
                authored[id]=guard(clip,fp)
        output=[];written=set()
        for line in lines:
            if line.startswith('summary\t'):line='summary\t'+json.dumps(dict(kind='model',purpose='rapier_fencing_085',status='authored_rework'),separators=(',',':'))
            if line.startswith('action\t'):
                id=json.loads(line.split('\t',1)[1])['id']
                if id in authored:line='action\t'+json.dumps(authored[id],ensure_ascii=False,separators=(',',':'));written.add(id)
            output.append(line)
        for id in sorted(set(authored)-written):output.append('action\t'+json.dumps(authored[id],ensure_ascii=False,separators=(',',':')))
        after=('\n'.join(output)+'\n').encode('utf8');path.write_bytes(after)
        original_lines={json.loads(l.split('\t',1)[1])['id']:l for l in lines if l.startswith('action\t')}
        final_lines={json.loads(l.split('\t',1)[1])['id']:l for l in output if l.startswith('action\t')}
        for id in clips:
            if id not in authored:assert original_lines[id]==final_lines[id]
        assert [l for l in lines if not l.startswith(('action\t','summary\t'))]==[l for l in output if not l.startswith(('action\t','summary\t'))]
        rows.append(dict(path=path.relative_to(ROOT).as_posix(),originalSha256=BEFORE[name],newSha256=sha(after),changedActions=sorted(authored),unchangedActions=len(set(clips)-set(authored)),geometryUnchanged=True,officialItemCarrierUnchanged=True,previousFourAttacksReplaced=True,addedActions=[f'legacy_rapier_attack5',f'legacy_rapier_attack5_upper','rapier_attack5']))
    for p in MODELS.glob('*.fsvoxel'):
        if p.name not in BEFORE:assert sha(p.read_bytes())==all_before[p.name]
    assets=[dict(entry='assets/fsrpg/fsvoxel/models/'+Path(r['path']).name,relativePath='models/'+Path(r['path']).name,beforeSha256=r['originalSha256'],afterSha256=r['newSha256']) for r in rows]
    MANIFEST.write_text(json.dumps(dict(format='fsrpg.rapier-fencing.v1',version='0.8.5',authorization='User explicitly authorized a complete 4-6 phrase single-handed rapier rework',samplingHz=120,sourcePhase=dict(windupEnd=.26,activeEnd=.86),handoff='Previous ACTIVE end equals next source first pose; independent recovery returns to guard',phrases=NAMES,files=rows,assets=assets),ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps(assets,indent=2))
if __name__=='__main__':main()
