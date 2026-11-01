"""Read actual stock/slim spear frames; quantify complete shaft/body intersection."""
import json,sys,math
from pathlib import Path
import numpy as np
from importlib.util import spec_from_file_location,module_from_spec
spec=spec_from_file_location('collision',Path(__file__).with_name('analyze-spear-collision.py'))
collision=module_from_spec(spec);spec.loader.exec_module(collision)
run=Path(sys.argv[1]).resolve();capture=json.loads((run/'render-capture.json').read_text(encoding='utf-8'))
summary={'run':str(run),'clips':{},'worst':[],'lowerBodyViolations':[],'waistSurfaceViolations':[],'centralCarryViolations':[]}
for clip in capture['clips']:
    if not clip['id'].startswith('spear-'):continue
    metrics={'frames':len(clip['frames']),'sourceFps':clip['sourceFps'],'samples':0,'shaftBodyIntersections':0,'minimumShaftClearanceCm':1e6,'elbowRearFrames':0,'elbowPrimarilyDownFrames':0,'maximumElbowForwardCm':-1e6,'turningFootLiftFrames':0,'centralCarryFrames':0,'waistSurfaceSamples':0,'maximumWaistSurfaceErrorPixels':0}
    headings=[]
    for frame in clip['frames']:
        stock=frame['observation']
        for profile,o in [(stock.get('profile','stock'),stock),('slim',stock.get('slimActor'))]:
            if o is None:continue
            rear=np.array(o['rightPalmCm']);axis=np.array(o['rightBladeAxis']);worst=1e6
            for bone,bounds in [('body',collision.BOXES['torso']),('head',collision.BOXES['head'])]:
                inv=np.linalg.inv(np.array(o['rig'][bone]).reshape(4,4,order='F'))
                distance=collision.clearance(rear-axis*60,rear+axis*178,inv,bounds,2)
                worst=min(worst,distance)
                if bone=='body':
                    delta=inv[:3,:3]@(np.array(o['rightElbowCm'])-np.array(o['rightShoulderCm']))
                    metrics['elbowRearFrames']+=int(delta[2]<-8)
                    metrics['elbowPrimarilyDownFrames']+=int(-delta[1]>max(0,-delta[2]))
                    metrics['maximumElbowForwardCm']=max(metrics['maximumElbowForwardCm'],float(delta[2]))
                    anim=o.get('renderAnimation',{});aim=anim.get('lastAim',{})
                    if anim.get('phase')=='READY' and abs(aim.get('pitch',90))<=5 and abs(aim.get('yaw',90)-o.get('bodyYaw',0))<=5:
                        metrics['centralCarryFrames']+=1
                        palm=(inv@np.r_[rear,1])[:3];shoulder=(inv@np.r_[o['rightShoulderCm'],1])[:3]
                        if delta[2]>-8 or -delta[2]<-delta[1] or palm[1]-shoulder[1]>-8:
                            summary['centralCarryViolations'].append({'clip':clip['id'],'profile':profile,'frame':frame['index'],'upper':delta.tolist(),'palmHeightFromShoulderCm':float(palm[1]-shoulder[1])})
            lower=o.get('finalLowerBody')
            if lower:
                yaw=lower.get('pelvisYawRadians',0);headings.append(math.degrees(yaw))
                sections=lower.get('renderedTorsoSections')
                if sections:
                    rot=np.array([[math.cos(yaw),0,math.sin(yaw)],[0,1,0],[-math.sin(yaw),0,math.cos(yaw)]])
                    pelvis=np.array(lower['pelvis']);corners=np.array(sections[-1])
                    expected=np.array([rot@np.array([x,0,z])+pelvis for x in [-4,4] for z in [-2,2]])
                    error=float(np.max(np.linalg.norm(corners-expected,axis=1)))
                    for side in ['right','left']:
                        leg=lower[side];cap=np.array(leg.get('hipCapCorners',[]))
                        if len(cap)!=4:error=1e6;continue
                        expected=np.array([rot@np.array([x,0,z])+np.array(leg['hip']) for x in [-2,2] for z in [-2,2]])
                        error=max(error,float(np.max(np.linalg.norm(cap-expected,axis=1))))
                    metrics['waistSurfaceSamples']+=1;metrics['maximumWaistSurfaceErrorPixels']=max(metrics['maximumWaistSurfaceErrorPixels'],error)
                    if error>.03 or o.get('torsoSkinSectionsSubmitted',0)<=0:summary['waistSurfaceViolations'].append({'clip':clip['id'],'profile':profile,'frame':frame['index'],'errorPixels':error})
                else:summary['waistSurfaceViolations'].append({'clip':clip['id'],'profile':profile,'frame':frame['index'],'error':'Missing actual torso surface evidence'})
                hipdelta=np.array(lower['left']['hip'])-np.array(lower['right']['hip'])
                waist=np.array([o['rig']['body'][12]/6.25,(150-o['rig']['body'][13])/6.25,-o['rig']['body'][14]/6.25])
                grounded=False;bad=np.linalg.norm(hipdelta-[3.8*math.cos(yaw),0,-3.8*math.sin(yaw)])>.03 or np.linalg.norm(waist-np.array(lower['pelvis']))>.03
                lifted=False
                for side in ['right','left']:
                    leg=lower[side];contact=leg['support']>.5;grounded|=contact
                    ys=[v[1] for v in leg['armorSoleCorners']]
                    bad|=any(y>24.03 or (contact and abs(y-24)>.03) for y in ys)
                    lifted|=not contact and min(ys)<23.9
                metrics['turningFootLiftFrames']+=int(lifted)
                if bad or not grounded:summary['lowerBodyViolations'].append({'clip':clip['id'],'profile':profile,'frame':frame['index']})
            metrics['samples']+=1;metrics['shaftBodyIntersections']+=worst<-.01
            metrics['minimumShaftClearanceCm']=min(metrics['minimumShaftClearanceCm'],worst)
            if worst<0:
                summary['worst'].append({'clearanceCm':worst,'clip':clip['id'],'profile':profile,'frame':frame['index'],'image':str(run/frame['file'])})
    metrics['pelvisHeadingRangeDegrees']=max(headings)-min(headings) if headings else 0
    summary['clips'][clip['id']]=metrics
summary['worst']=sorted(summary['worst'],key=lambda w:w['clearanceCm'])[:20]
summary['passed']=all(v['shaftBodyIntersections']==0 and v['sourceFps']>=30 for v in summary['clips'].values()) and not summary['lowerBodyViolations'] and not summary['waistSurfaceViolations'] and not summary['centralCarryViolations']
out=run/'underarm-contact.json';out.write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
print(json.dumps(summary['clips'],indent=2));print(out)
print('passed:',summary['passed'],'lower:',len(summary['lowerBodyViolations']),'waist:',len(summary['waistSurfaceViolations']),'carry:',len(summary['centralCarryViolations']))
raise SystemExit(0 if summary['passed'] else 2)
