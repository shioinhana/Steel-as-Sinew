#!/usr/bin/env python3
"""Compare imported local joints against the packaged 0.1.1 source, including subframes."""
from __future__ import annotations
import bisect, hashlib, json, math, zipfile
from pathlib import Path
from import_legacy_011 import Channel,positive_scale,authored_scale_min

ROOT=Path(__file__).resolve().parents[1]
def mul(a,b):
    x,y,z,w=a;X,Y,Z,W=b
    return [w*X+x*W+y*Z-z*Y,w*Y-x*Z+y*W+z*X,w*Z+x*Y-y*X+z*W,w*W-x*X-y*Y-z*Z]
def axis(deg,i):
    a=math.radians(deg)*.5;q=[0.,0.,0.,math.cos(a)];q[i]=math.sin(a);return q
def bedrock(v):return mul(axis(-v[2],2),mul(axis(-v[1],1),axis(v[0],0)))
def fs(v):return mul(axis(v[0],0),mul(axis(v[1],1),axis(v[2],2)))
def slerp(a,b,u):
    d=sum(x*y for x,y in zip(a,b))
    if d<0:b=[-x for x in b];d=-d
    if d>.9995:
        q=[x+(y-x)*u for x,y in zip(a,b)];l=math.sqrt(sum(x*x for x in q));return [x/l for x in q]
    theta=math.acos(max(-1,min(1,d)));den=math.sin(theta)
    return [(x*math.sin((1-u)*theta)+y*math.sin(u*theta))/den for x,y in zip(a,b)]
def sample(keys,t):
    i=bisect.bisect_right([f['time'] for f in keys],t)
    if i==0:return fs(keys[0]['rotation']),keys[0]['position'],keys[0]['scale']
    if i==len(keys):return fs(keys[-1]['rotation']),keys[-1]['position'],keys[-1]['scale']
    a,b=keys[i-1],keys[i];u=(t-a['time'])/(b['time']-a['time'])
    return slerp(fs(a['rotation']),fs(b['rotation']),u),[x+(y-x)*u for x,y in zip(a['position'],b['position'])],[x+(y-x)*u for x,y in zip(a['scale'],b['scale'])]
def rows(path):
    out=[]
    for line in path.read_text(encoding='utf8').splitlines():
        values=line.split('\t');
        if values[0] in ['part','action','box']:out.append((values[0],[json.loads(v) for v in values[1:]]))
    return out
def main():
    manifest=json.loads((ROOT/'docs/legacy-0.1.1-animation-map.json').read_text(encoding='utf8'))
    jar=Path(r'C:/Users/34003/Desktop/actioncombat_legacy_20260923/build/libs/actioncombat-neoforge-0.1.1.jar')
    report={'pass':True,'samplingHz':manifest['samplingHz'],'sourceJarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'models':[]}
    cache={};samples=0;angle_max=0.;position_max=0.
    with zipfile.ZipFile(jar) as z:
        for entry in manifest['models']:
            path=ROOT/'src/main/resources/assets/fsrpg/fsvoxel/models'/entry['model'];doc=rows(path)
            if hashlib.sha256(path.read_bytes()).hexdigest()!=entry['outputSha256']:raise AssertionError('Output hash mismatch')
            if path.read_bytes()!=(ROOT/'run/client/config/fsrpg/models'/entry['model']).read_bytes():raise AssertionError('Config model differs')
            for typ,folder in [('geometry','models'),('animation','animations')]:
                source=Path(entry[typ]);key='assets/actioncombat/geckolib/'+folder+'/'+source.name
                if hashlib.sha256(z.read(key)).hexdigest()!=entry['sourceSha256'][source.name]:raise AssertionError('Not the packaged 0.1.1 asset: '+key)
            ap=entry['animation'];gp=entry['geometry']
            if ap not in cache:cache[ap]=json.loads(Path(ap).read_text(encoding='utf-8-sig'))['animations']
            geo={b['name']:b for b in json.loads(Path(gp).read_text(encoding='utf-8-sig'))['minecraft:geometry'][0]['bones']}
            actions={v[0]['id']:v[0] for tag,v in doc if tag=='action'}
            if len(actions)!=sum(tag=='action' for tag,v in doc):raise AssertionError('Duplicate action IDs')
            local_angle=0.;local_position=0.;local_scale=0.;count=0
            for mapped in entry['actions']:
                if mapped['alias']:continue
                action=actions[mapped['action']];source=cache[ap][mapped['sourceClip']]
                for original,part in entry['boneMap'].items():
                    if part not in action['tracks']:continue
                    keys=action['tracks'][part]
                    if any(min(k['scale'])<=0 for k in keys):raise AssertionError('Nonpositive FS scale')
                    if max(b['time']-a['time'] for a,b in zip(keys,keys[1:]))>1/30+1e-7:raise AssertionError('Under 30Hz')
                    ch=source.get('bones',{}).get(original,{})
                    channels={name:Channel(ch.get(name),[1,1,1] if name=='scale' else [0,0,0]) for name in ['rotation','position','scale']}
                    # Both exact samples and intermediate render frames use the real engine's quaternion interpolation.
                    times={round(action['duration']*i/12,9) for i in range(13)}|{min(action['duration'],1/120),max(0,action['duration']-1/120)}
                    for t in sorted(times):
                        expected=bedrock([x+y for x,y in zip(channels['rotation'].at(t),geo[original].get('rotation',[0,0,0]))])
                        q,p,s=sample(keys,t);dot=abs(sum(x*y for x,y in zip(expected,q)))
                        error=math.degrees(2*math.acos(min(1,dot)))
                        v=channels['position'].at(t);wanted=[v[0]*6.25,v[1]*6.25,-v[2]*6.25]
                        dist=math.sqrt(sum((a-b)**2 for a,b in zip(p,wanted)))
                        if authored_scale_min(channels['scale'])<0:raise AssertionError('Authored negative mirror scale silently lost')
                        scale_error=max(abs(a-b) for a,b in zip(s,positive_scale(channels['scale'].at(t))))
                        local_scale=max(local_scale,scale_error)
                        local_angle=max(local_angle,error);local_position=max(local_position,dist);count+=1
            boxes=[v for tag,v in doc if tag=='box']
            if entry['perspective']=='third_person' and boxes:raise AssertionError('TP mesh replaces player equipment')
            if any('item' in v[0] or v[0]=='rapier' for v in boxes):raise AssertionError('Authored weapon mesh imported')
            result={'model':entry['model'],'clips':len(actions),'jointSamples':count,'maxRotationErrorDegrees':round(local_angle,6),'maxPositionErrorCm':round(local_position,6),
                'maxScaleErrorAfterHiddenNormalization':round(local_scale,6),'scaleNormalization':entry.get('scaleNormalization',[]),
                'pass':local_angle<=1.0 and local_position<=.25 and local_scale<=.002,'packagedSourceHashMatch':True,'configHashMatch':True}
            report['models'].append(result);report['pass'] &= result['pass'];samples+=count;angle_max=max(angle_max,local_angle);position_max=max(position_max,local_position)
            print(entry['model'],'rotation error',round(local_angle,4),'deg; position',round(local_position,4),'cm')
    report.update(jointSamples=samples,maxRotationErrorDegrees=round(angle_max,6),maxPositionErrorCm=round(position_max,6))
    directory=ROOT/'verification/legacy-011';directory.mkdir(parents=True,exist_ok=True)
    (directory/'asset-fidelity.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    if not report['pass']:raise SystemExit('Source fidelity tolerance failed')
    print('PASS',samples,'joint subframes; every packaged source hash matches 0.1.1')
if __name__=='__main__':main()
