"""Independent FK audit of the exported 0.10.0 assets, including limb volumes.

Reads the shipped files, not the authoring IK; reconstructs every exported arm,
hand, official blade and torso in world space.  The JSON report records measured
bounds and collision failures. Optional plots are review aids, never game proof.
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
import numpy as np

ROOT=Path(__file__).resolve().parents[1]
PHRASES=['direct-thrust','rising-cut','diagonal-cut','return-cut','lunge-thrust']

def rotation(angles):
    x,y,z=np.radians(angles);sx,cx=np.sin(x),np.cos(x);sy,cy=np.sin(y),np.cos(y);sz,cz=np.sin(z),np.cos(z)
    return np.array([[1,0,0],[0,cx,-sx],[0,sx,cx]])@np.array([[cy,0,sy],[0,1,0],[-sy,0,cy]])@np.array([[cz,-sz,0],[sz,cz,0],[0,0,1]])

def transform(r,p):
    m=np.eye(4);m[:3,:3]=r;m[:3,3]=p;return m

def read(path):
    parts={};clips={}
    for line in path.read_text(encoding='utf8').splitlines():
        if line.startswith('part\t'):
            _,id,value=line.split('\t',2);parts[json.loads(id)]=json.loads(value)
        elif line.startswith('action\t'):
            a=json.loads(line.split('\t',1)[1]);clips[a['id']]=a
    return parts,clips

def frame(parts,clip,at):
    local={};world={}
    for id,p in parts.items():
        rest=p['transform'];keys=clip['tracks'].get(id)
        if keys:
            key=min(keys,key=lambda k:abs(k['time']-at))
            # Every generated controlled track shares precisely the same times.
            assert abs(key['time']-at)<1e-7,(clip['id'],id,at)
            r=rotation(rest['rotation'])@rotation(key['rotation']);pos=np.array(rest['position'])+key['position']
        else:r=rotation(rest['rotation']);pos=rest['position']
        local[id]=transform(r,pos)
    def one(id):
        if id not in world:
            parent=parts[id].get('parent','');world[id]=(one(parent) if parent in parts else np.eye(4))@local[id]
        return world[id]
    for id in parts:one(id)
    return local,world

def official_axis(fp):
    a=math.radians(41.987212496+45)
    return np.array([-math.cos(a),math.sin(a),0] if fp else [-math.cos(a),0,math.sin(a)])

def bend(a,b):return math.degrees(math.acos(float(np.clip(np.dot(a,b)/(np.linalg.norm(a)*np.linalg.norm(b)),-1,1))))
def point(matrix,p):return (matrix@np.append(p,1))[:3]

def intersects_box(a,b,lo,hi):
    start,end=0.,1.;delta=b-a
    for axis in range(3):
        if abs(delta[axis])<1e-9:
            if a[axis]<lo[axis] or a[axis]>hi[axis]:return False
        else:
            x,y=(lo[axis]-a[axis])/delta[axis],(hi[axis]-a[axis])/delta[axis]
            start=max(start,min(x,y));end=min(end,max(x,y))
            if start>end:return False
    return True

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,default=ROOT/'verification/rapier-0100-assets');parser.add_argument('--plots',action='store_true');args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    checks=0;failures=[];models=[];plots=[]
    def check(ok,message):
        nonlocal checks
        checks+=1
        if not ok:failures.append(message)
    for fp in [False,True]:
        path=ROOT/'src/main/resources/assets/fsrpg/fsvoxel/models'/('legacy_rapier_fp.fsvoxel' if fp else 'legacy_rapier.fsvoxel');parts,clips=read(path);axis=official_axis(fp);rows=[]
        check((ROOT/'config/fsrpg/models'/path.name).read_bytes()==path.read_bytes(),f'{path.name}: editable config template differs from packaged asset')
        ids=[f'legacy_rapier_attack{i}_upper' for i in range(1,6)]+['legacy_rapier_rapier_thrust_hit_upper','rapier_riposte','rapier_reprise']
        for id in ids:
            clip=clips[id];times=[k['time'] for k in clip['tracks']['right_arm']];allframes=[];tips=[];maxbend=0;minelbow=180;maxelbow=0;maxstep=0;previous=None
            check('rapier_fencing:0100' in clip['tags'],f'{id}: missing current fencing generation')
            check(clip['loop']=='once' and clip['mode']=='delta' and clip['rotation_mode']=='quaternion',f'{id}: invalid exported FSVOXEL animation contract')
            check(max(np.diff(times))<=1/120+1e-8,f'{id}: source sampling under 120 Hz')
            for at in times:
                local,world=frame(parts,clip,at);allframes.append(world)
                shoulder=world['right_arm'][:3,3];elbow=world['right_forearm'][:3,3];wrist=world['right_item' if fp else 'right_hand'][:3,3]
                blade=world['right_item'][:3,:3]@axis;tip=point(world['right_item'],axis*75);tips.append(tip)
                check(abs(np.linalg.norm(elbow-shoulder)-37.5)<1e-5,f'{id}: upper arm disconnected')
                check(abs(np.linalg.norm(wrist-elbow)-37.5)<1e-5,f'{id}: forearm disconnected')
                elbow_angle=180-bend(shoulder-elbow,wrist-elbow);minelbow=min(minelbow,elbow_angle);maxelbow=max(maxelbow,elbow_angle)
                check(10<=elbow_angle<=135,f'{id}: elbow outside 10-135 degrees at {at}')
                flex=bend(wrist-elbow,blade);maxbend=max(maxbend,flex)
                check(flex<=34.00001,f'{id}: wrist/official blade flexion exceeds 34 degrees at {at}: {flex}')
                keys=clip['tracks']['right_forearm'];key=min(keys,key=lambda k:abs(k['time']-at))
                check(-135<=key['rotation'][0]<=-10 and abs(key['rotation'][1])<=30.00001 and abs(key['rotation'][2])<1e-5,f'{id}: elbow no longer pure positive hinge with bounded forearm pronation')
                if previous is not None:
                    for bone in ('right_arm','right_forearm','right_item','left_arm'):
                        delta=previous[bone][:3,:3].T@world[bone][:3,:3];degrees=math.degrees(math.acos(float(np.clip((np.trace(delta)-1)/2,-1,1))));maxstep=max(maxstep,degrees)
                        check(degrees<18,f'{id}: native 120 Hz rotation jump {bone}: {degrees}')
                previous=world
                if not fp:
                    check(np.linalg.norm(world['right_item'][:3,3]-wrist)<1e-5,f'{id}: official grip leaves palm')
                    inverse=np.linalg.inv(world['body'])
                    torso_forward=world['body'][:3,:3]@np.array([0,0,1]);yaw=math.degrees(math.atan2(torso_forward[0],torso_forward[2]))
                    check(54<yaw<75,f'{id}: torso rotates away from threat')
                    for side in ('right','left'):
                        s,e,w=[world[side+suffix][:3,3] for suffix in ('_arm','_forearm','_hand')]
                        check(abs(np.linalg.norm(e-s)-37.5)<1e-5 and abs(np.linalg.norm(w-e)-37.5)<1e-5,f'{id}: {side} arm segment disconnects')
                        check(10<=180-bend(s-e,w-e)<=135,f'{id}: {side} elbow outside anatomical hinge range')
                        # Inflated volumes detect centerline clearance for the
                        # elbow/forearm and distal upper arm, excluding shoulder.
                        for label,a,b in [('upper',s+(e-s)*.22,e),('forearm',e,w)]:
                            a,b=point(inverse,a),point(inverse,b)
                            check(not intersects_box(a,b,np.array([-34.375,-53.125,-21.875]),np.array([34.375,43.75,21.875])),f'{id}: {side} {label} enters torso at {at}')
                            check(not intersects_box(a,b,np.array([-34.375,43.75,-34.375]),np.array([34.375,103.125,34.375])),f'{id}: {side} {label} enters head at {at}')
                    a,b=point(inverse,wrist),point(inverse,tip)
                    check(not intersects_box(a,b,np.array([-26,-44.75,-13.5]),np.array([26,42.75,13.5])),f'{id}: official blade crosses torso at {at}')
                    check(not intersects_box(a,b,np.array([-26,43.75,-26]),np.array([26,94.75,26])),f'{id}: official blade crosses head at {at}')
            tips=np.array(tips);prep=times.index(round(clip['duration']*.26,9));contact=times.index(round(clip['duration']*.56,9));travel=tips[contact]-tips[prep]
            phrase=next(t.split(':',1)[1] for t in clip['tags'] if t.startswith('rapier_phrase:'))
            if phrase in ('direct-thrust','lunge-thrust','charged-thrust','riposte','reprise'):check(travel[2]>(12 if fp else 23),f'{id}: no clear forward thrust')
            elif phrase=='rising-cut':check(travel[0]>(20 if fp else 45) and travel[1]>(30 if fp else 65),f'{id}: no diagonal rising trajectory')
            elif phrase=='diagonal-cut':check(travel[0]<(-25 if fp else -40) and travel[1]<(-40 if fp else -65),f'{id}: no diagonal descending trajectory')
            elif phrase=='return-cut':check(travel[0]>(45 if fp else 80) and abs(travel[1])<30,f'{id}: no compact horizontal return')
            rows.append(dict(action=id,phrase=phrase,frames=len(times),wristFlexionMax=maxbend,elbowFlexionMin=minelbow,elbowFlexionMax=maxelbow,sourceJointStepMax=maxstep,tipPreparationToContactCm=travel.tolist()))
            if phrase in PHRASES:plots.append((fp,phrase,allframes,tips,times,clip))
        for stage in range(1,6):
            clip=clips[f'legacy_rapier_attack{stage}_upper'];next_clip=clips[f'legacy_rapier_attack{stage%5+1}_upper']
            a,_=frame(parts,clip,round(clip['duration']*.86,9));b,_=frame(parts,next_clip,0)
            for bone in clip['tracks']:check(np.max(np.abs(a[bone]-b[bone]))<1e-5,f'{stage}: {bone} queued handoff jumps')
        models.append(dict(path=str(path),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),clips=rows))
    report=dict(format='fsrpg.rapier-asset-audit.v1',checks=checks,passed=not failures,failures=failures,models=models)
    (args.output/'asset-audit.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf8')
    if args.plots:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        for fp in [False,True]:
            fig,axes=plt.subplots(2,5,figsize=(20,8),constrained_layout=True)
            for column,(_,name,frames,tips,times,clip) in enumerate(p for p in plots if p[0]==fp):
                for row,(xaxis,yaxis,label) in enumerate([(0,1,'front: X/Y (cm)'),(2,1,'side: Z/Y (cm)')]):
                    ax=axes[row,column];ax.plot(tips[:,xaxis],tips[:,yaxis],color='#9aa6b1',linewidth=1)
                    for t,color in [(.26,'#437bb1'),(.56,'#e2633c'),(.86,'#36a28a')]:
                        world=frames[times.index(round(clip['duration']*t,9))];points=[world[b][:3,3] for b in ['right_arm','right_forearm','right_item']];points.append(point(world['right_item'],official_axis(fp)*75));points=np.array(points)
                        ax.plot(points[:,xaxis],points[:,yaxis],'-o',color=color,markersize=3,linewidth=2,label=str(t))
                    ax.set_title(name);ax.set_xlabel(label);ax.axis('equal');ax.grid(alpha=.2)
                axes[0,column].legend(fontsize=8)
            fig.suptitle(('First' if fp else 'Third')+' person: preparation / contact / queued handoff',fontsize=16)
            fig.savefig(args.output/('first-person-trajectories.png' if fp else 'third-person-trajectories.png'),dpi=140);plt.close(fig)
    print(json.dumps(dict(checks=checks,passed=not failures,failureCount=len(failures),firstFailures=failures[:20],report=str(args.output/'asset-audit.json')),indent=2))
    if failures:raise SystemExit(1)
if __name__=='__main__':main()
