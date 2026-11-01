"""Author the first three rapier cuts; retain geometry, thrusts and every other weapon byte-for-byte."""
from __future__ import annotations
import hashlib, json, math
from copy import deepcopy
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
MODELS=ROOT/'src/main/resources/assets/fsrpg/fsvoxel/models'
MANIFEST=ROOT/'docs/rapier-motion-0.8.4-manifest.json'
TIMES=[0,.22,.58,.84,1]
def sha(data):return hashlib.sha256(data).hexdigest()
def actions(lines):return {v['id']:v for line in lines if line.startswith('action\t') for v in [json.loads(line.split('\t',1)[1])]}
def interpolate(values,t):
    # Shape-preserving cubic Hermite: immediate preparation, no overshoot at anatomical limits.
    slopes=[(values[i+1]-values[i])/(TIMES[i+1]-TIMES[i]) for i in range(4)]
    tangent=[slopes[0]*.7]+[0 if slopes[i-1]*slopes[i]<=0 else 2*slopes[i-1]*slopes[i]/(slopes[i-1]+slopes[i]) for i in range(1,4)]+[0]
    i=next((i for i in range(4) if t<=TIMES[i+1]),3);span=TIMES[i+1]-TIMES[i];u=(t-TIMES[i])/span
    return (2*u**3-3*u*u+1)*values[i]+(u**3-2*u*u+u)*span*tangent[i]+(-2*u**3+3*u*u)*values[i+1]+(u**3-u*u)*span*tangent[i+1]
def author(clip,stages,index,fp):
    result=deepcopy(clip);duration=clip['duration'];tracks=result['tracks']
    for bone in stages[0]:
        base=deepcopy(tracks.get(bone,[{'position':[0,0,0],'scale':[1,1,1]}])[0]);keys=[]
        delay=.025 if bone=='body' else -.018 if 'forearm' in bone else -.035 if 'hand' in bone or 'item' in bone else 0
        for n in range(49):
            phase=n/48;t=max(0,min(1,phase+delay*math.sin(math.pi*phase)))
            key=deepcopy(base);key['time']=round(duration*phase,9)
            key['rotation']=[round(interpolate([pose[bone][axis] for pose in stages],t),6) for axis in range(3)]
            keys.append(key)
        tracks[bone]=keys
    result['transition']=0
    result['tags']=[t for t in result.get('tags',[]) if not t.startswith(('rapier_rework:','rapier_recoil:'))]+['rapier_rework:084','rapier_recoil:'+str([-1,1,-1][index])]
    return result
def main():
    before_all={p.name:sha(p.read_bytes()) for p in MODELS.glob('*.fsvoxel')};previous=json.loads(MANIFEST.read_text(encoding='utf-8')) if MANIFEST.exists() else {};rows=[]
    for fp in [False,True]:
        name='legacy_rapier_fp.fsvoxel' if fp else 'legacy_rapier.fsvoxel';path=MODELS/name;before=path.read_bytes();lines=before.decode('utf-8').splitlines();clips=actions(lines)
        bones=['right_arm','right_forearm','right_item','left_arm'] if fp else ['body','right_arm','right_forearm','right_hand','right_item','left_arm','left_forearm','left_hand']
        idle={b:deepcopy(clips['legacy_rapier_static_idle']['tracks'][b][0]['rotation']) for b in bones}
        end3={b:deepcopy(clips['legacy_rapier_attack4_upper']['tracks'][b][0]['rotation']) for b in bones}
        end1=deepcopy(idle);end2=deepcopy(idle)
        if fp:
            # The imported Euler/quaternion adapter loses roll exactly at 90 degrees.
            # Stay two tenths off that singularity; enter the unchanged thrust's actual
            # sampled orientation rather than its ambiguous authored Euler spelling.
            end3['right_item']=[0,89.8,0]
            end1.update(right_arm=[-85,-28,6],right_forearm=[-36,0,0],right_item=[180,89.8,0])
            end2.update(right_arm=[-74,30,20],right_forearm=[-24,0,0],right_item=[180,89.8,0])
            chambers=[dict(right_arm=[-75,24,23],right_forearm=[-24,0,0],right_item=idle['right_item']),dict(right_arm=[-112,-20,18],right_forearm=[-48,0,0]),dict(right_arm=[-94,45,24],right_forearm=[-50,0,0])]
            contacts=[dict(right_arm=[-112,-35,8],right_forearm=[-15,0,0],right_item=[180,80,0]),dict(right_arm=[-78,12,0],right_forearm=[-12,0,0]),dict(right_arm=[-135,-35,-28],right_forearm=[-12,0,0],right_item=[80,89.8,0])]
        else:
            end1.update(body=[0,-9,0],right_arm=[-112,-20,-58],right_forearm=[-65,0,0],right_hand=[0,0,8],right_item=[90,0,90])
            end2.update(body=[1,10,0],right_arm=[-40,30,-55],right_forearm=[-38,0,0],right_hand=[0,0,-8],right_item=[90,0,90])
            chambers=[dict(body=[-1,6,0],right_arm=[-85,32,-45],right_forearm=[-82,0,0],right_hand=[5,0,-12],right_item=[90,0,90]),dict(body=[-1,-12,0],right_arm=[-140,-20,-35],right_forearm=[-100,0,0],right_hand=[8,0,-12]),dict(body=[-1,16,0],right_arm=[-65,55,-45],right_forearm=[-58,0,0],right_hand=[0,0,12])]
            contacts=[dict(body=[1,-8,0],right_arm=[-96,-35,-56],right_forearm=[-24,0,0],right_hand=[-8,0,12],right_item=[90,0,90]),dict(body=[1,8,0],right_arm=[-75,12,-65],right_forearm=[-20,0,0],right_hand=[-10,0,-5]),dict(body=[1,-6,0],right_arm=[-127,-10,-60],right_forearm=[-15,0,0],right_hand=[-8,0,-8])]
        starts=[idle,end1,end2];ends=[end1,end2,end3];authored={}
        for i in range(3):
            chamber=deepcopy(starts[i]);chamber.update(chambers[i]);contact=deepcopy(starts[i]);contact.update(contacts[i]);follow={b:[contact[b][a]+(ends[i][b][a]-contact[b][a])*.82 for a in range(3)] for b in bones}
            # The off-hand participates quietly; it never drives the weapon or leaves its carry side.
            for pose,weight in [(chamber,.35),(contact,1),(follow,.45)]:
                pose['left_arm']=[idle['left_arm'][a]+([0,3,2][a])*[-1,1,-1][i]*weight for a in range(3)]
            stages=[starts[i],chamber,contact,follow,ends[i]]
            for id in [f'legacy_rapier_attack{i+1}',f'legacy_rapier_attack{i+1}_upper',f'rapier_attack{i+1}']:
                if id in clips:authored[id]=author(clips[id],stages,i,fp)
        output=[]
        for line in lines:
            if line.startswith('summary\t'):line='summary\t'+json.dumps(dict(kind='model',purpose='legacy_011_rapier_rework_084',status='authored_rework'),separators=(',',':'))
            if line.startswith('action\t'):
                id=json.loads(line.split('\t',1)[1])['id']
                if id in authored:line='action\t'+json.dumps(authored[id],ensure_ascii=False,separators=(',',':'))
            output.append(line)
        after=('\n'.join(output)+'\n').encode('utf-8');path.write_bytes(after)
        old=next((r for r in previous.get('files',[]) if r['path'].endswith(name) and r['newSha256']==sha(before)),None)
        original_lines={json.loads(line.split('\t',1)[1])['id']:line for line in lines if line.startswith('action\t')}
        final_lines={json.loads(line.split('\t',1)[1])['id']:line for line in output if line.startswith('action\t')}
        for id in clips:
            if id not in authored:assert original_lines[id]==final_lines[id]
        rows.append(dict(path=str(path.relative_to(ROOT)).replace('\\','/'),originalSha256=old['originalSha256'] if old else sha(before),newSha256=sha(after),changedActions=sorted(authored),unchangedActions=len(clips)-len(authored),geometryUnchanged=True,thrustFourthUnchanged=True))
    for p in MODELS.glob('*.fsvoxel'):
        if p.name not in {'legacy_rapier.fsvoxel','legacy_rapier_fp.fsvoxel'}:assert sha(p.read_bytes())==before_all[p.name]
    assets=[dict(entry='assets/fsrpg/fsvoxel/models/'+Path(r['path']).name,relativePath='models/'+Path(r['path']).name,beforeSha256=r['originalSha256'],afterSha256=r['newSha256']) for r in rows]
    MANIFEST.write_text(json.dumps(dict(format='fsrpg.rapier-rework.v1',version='0.8.4',authorization='User explicitly requested replacement rapier cuts and combined movement/reaction posture',samplingHz=120,sourcePhase=dict(windupEnd=.26,activeEnd=.86),files=rows,assets=assets),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(rows,ensure_ascii=True,indent=2))
if __name__=='__main__':main()
