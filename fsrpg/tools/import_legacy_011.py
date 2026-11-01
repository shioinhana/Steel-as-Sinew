#!/usr/bin/env python3
"""Read-only import of the user's ActionCombat 0.1.1 GeckoLib motion into FSVOXEL.

Keep the source hierarchy and locators. Bedrock's Rz*Ry*Rx, signed axes and
pre/post/step/Catmull-Rom channels are evaluated before converting to FS XYZ.
Weapons are never baked into the mesh; the official ItemStack uses the locator.
"""
from __future__ import annotations
import argparse, bisect, hashlib, json, math
from copy import deepcopy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HIDDEN_SCALE_EPSILON = 1e-6
TP_NAMES = {'Root':'root','Hip':'hip','Body':'body','Head':'head',
    'RightArm':'right_arm','RightForeArm':'right_forearm','RightHand':'right_hand',
    'RightHandLocator':'right_item','LeftArm':'left_arm','LeftForeArm':'left_forearm',
    'LeftHand':'left_hand','LeftHandLocator':'left_item','RightLeg':'right_leg',
    'RightForeLeg':'right_foreleg','RightFoot':'right_foot','LeftLeg':'left_leg',
    'LeftForeLeg':'left_foreleg','LeftFoot':'left_foot'}
FP_SINGLE = {'root':'root','knife_R':'camera_root','right':'right_arm',
    'fp_forearm_r':'right_forearm','elucidator':'right_item','left':'left_arm'}
FP_DUAL = {'root':'root','lower_body':'hip','upper_body':'body','right_arm':'right_arm',
    'right_sleve_bottom':'right_forearm','hand_r':'right_hand','right_arm_item':'right_item',
    'left_arm':'left_arm','left_arm_bottom':'left_forearm','hand_l':'left_hand','left_arm_item':'left_item'}
COMMON = ['static_idle','inspect','draw','put_away','run_start','run_hold','run_end',
    'walk_start','walk_hold','walk_end','crouch_idle','jump','jump_start','fall','hurt',
    'idle_upper','defend_upper','aiming_upper','hurt_upper','defend_end', 'crouch_walk',
    'walk_hold_left','walk_hold_right','walk_hold_back','crouch_walk_left',
    'crouch_walk_right','crouch_walk_back','run_hold_left','run_hold_right','run_hold_back']
RAPIER = ['attack1','attack2','attack3','attack4','attack_dual','defend','aiming',
    'rapier_thrust_hit','rapier_thrust_miss']
DUAL = ['attack_1','attack2','attack3','attack4','attack_dual','guard_bash','aiming',
    'defence_1','defence_2','defence_3','defence_4']
DODGES = ['rapier_dodge_'+n for n in ['forward','back','left','right',
    'forward_left','forward_right','back_left','back_right']]
BOW = ['draw_bow','put_away_bow','bow_rapid_idle_upper','bow_rapid_draw_upper',
    'bow_rapid_release_upper','bow_snipe_idle_upper','bow_snipe_draw_upper',
    'bow_snipe_release_upper','bow_bash_upper']
ALIASES = {
 'rapier': {'rapier_idle':'static_idle','rapier_draw':'draw','rapier_guard':'defend','rapier_guard_heavy':'defend',
   'rapier_charge':'rapier_thrust_miss_upper','rapier_full_charge':'rapier_thrust_miss_upper',
   'rapier_run':'run_hold','rapier_stance':'static_idle',
   **{'rapier_attack'+str(i):'attack'+str(i)+'_upper' for i in range(1,5)},
   'rapier_thrust_hit':'rapier_thrust_hit_upper','rapier_thrust_miss':'rapier_thrust_miss_upper',
   'rapier_counter':'attack_dual_upper', **{n:n for n in DODGES}},
 'dual_blade': {'dual_draw':'draw','dual_guard':'defend_upper','dual_run':'run_hold',
   'dual_stance':'static_idle','dual_left_1':'attack_1_upper','dual_left_2':'attack2_upper',
   'dual_right_1':'attack4_upper','dual_right_2':'attack3_upper','dual_counter':'attack_dual_upper',
   'dual_bash':'guard_bash_upper'},
 'bow': {'bow_draw':'draw_bow','bow_put_away':'put_away_bow','bow_bash':'bow_bash_upper',
   **{'bow_'+s+'_'+dst:'bow_'+s+'_'+src+'_upper' for s in ['rapid','snipe']
       for dst,src in [('idle','idle'),('charge','draw'),('release','release')]}}
}

def vec(node):
    if isinstance(node,list): return [float(v) for v in node]
    for key in ['post','vector','pre']:
        if key in node: return vec(node[key])
    raise ValueError('Not a finite vec3: '+repr(node))

class Channel:
    def __init__(self, value, fallback):
        self.fallback=fallback
        self.frames=[] if value is None else [(0,value)] if isinstance(value,list) else sorted((float(t),v) for t,v in value.items())
        self.times=[t for t,v in self.frames]
    def at(self,t):
        if not self.frames:return self.fallback
        i=bisect.bisect_right(self.times,t)
        if i==0:return vec(self.frames[0][1])
        if i==len(self.frames):return vec(self.frames[-1][1])
        ta,na=self.frames[i-1];tb,nb=self.frames[i]
        a=vec(na);b=vec(nb.get('pre',nb) if isinstance(nb,dict) else nb)
        u=(t-ta)/(tb-ta); mode=nb.get('lerp_mode','linear') if isinstance(nb,dict) else 'linear'
        if mode=='step':return a
        if mode!='catmullrom' or len(self.frames)<3:return [x+(y-x)*u for x,y in zip(a,b)]
        p=vec(self.frames[max(0,i-2)][1]);n=vec(self.frames[min(len(self.frames)-1,i+1)][1])
        return [.5*(2*x+(-v+y)*u+(2*v-5*x+4*y-w)*u*u+(-v+3*x-3*y+w)*u*u*u) for v,x,y,w in zip(p,a,b,n)]

def matmul(a,b):return [[sum(a[i][k]*b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
def xyz_from_bedrock(degrees):
    # Bedrock -> Gecko: (-rx,-ry,+rz). Rotate Gecko space 180Y to FS space.
    x,y,z=map(math.radians,[degrees[0],-degrees[1],-degrees[2]])
    cx,sx=math.cos(x),math.sin(x);cy,sy=math.cos(y),math.sin(y);cz,sz=math.cos(z),math.sin(z)
    m=matmul([[cz,-sz,0],[sz,cz,0],[0,0,1]],matmul([[cy,0,sy],[0,1,0],[-sy,0,cy]],[[1,0,0],[0,cx,-sx],[0,sx,cx]]))
    ey=math.asin(max(-1,min(1,m[0][2])))
    if abs(math.cos(ey))>1e-7:ex=math.atan2(-m[1][2],m[2][2]);ez=math.atan2(-m[0][1],m[0][0])
    else:ex=math.atan2(m[2][1],m[1][1]);ez=0
    return [round(math.degrees(v),6) for v in [ex,ey,ez]]

def fsposition(v):return [round(v[0]*6.25,6),round(v[1]*6.25,6),round(-v[2]*6.25,6)]
def payload(row,*values):return row+'\t'+'\t'.join(json.dumps(v,ensure_ascii=False,separators=(',',':')) for v in values)

def authored_scale_min(channel):
    values=[]
    for _,node in channel.frames:
        values.extend(vec(node))
        if isinstance(node,dict) and 'pre' in node:values.extend(vec(node['pre']))
    return min(values,default=1.)

def positive_scale(value):
    # 0.6.0 forbids zero scale. These legacy keys mean "hidden"; Catmull-Rom
    # can dip below zero while both surrounding authored keys are zero.
    # Authored negative scales are rejected separately, never silently mirrored away.
    return [round(max(HIDDEN_SCALE_EPSILON,v),6) for v in value]

def qmul(a,b):
    x,y,z,w=a;X,Y,Z,W=b
    return [w*X+x*W+y*Z-z*Y,w*Y-x*Z+y*W+z*X,w*Z+x*Y-y*X+z*W,w*W-x*X-y*Y-z*Z]
def qaxis(angle,i):
    a=math.radians(angle)*.5;q=[0.,0.,0.,math.cos(a)];q[i]=math.sin(a);return q
def qfs(v):return qmul(qaxis(v[0],0),qmul(qaxis(v[1],1),qaxis(v[2],2)))
def qslerp(a,b,u):
    dot=sum(x*y for x,y in zip(a,b))
    if dot<0:b=[-v for v in b];dot=-dot
    if dot>.9995:
        q=[x+(y-x)*u for x,y in zip(a,b)];length=math.sqrt(sum(v*v for v in q));return [v/length for v in q]
    theta=math.acos(max(-1,min(1,dot)));den=math.sin(theta)
    return [(x*math.sin((1-u)*theta)+y*math.sin(u*theta))/den for x,y in zip(a,b)]

def adaptive_frames(ch,base,times):
    cached={}
    def at(t):
        if t not in cached:
            r=ch.get('rotation',Channel(None,[0,0,0])).at(t)
            cached[t]={'time':t,'rotation':xyz_from_bedrock([x+y for x,y in zip(r,base)]),
                'position':fsposition(ch.get('position',Channel(None,[0,0,0])).at(t)),
                'scale':positive_scale(ch.get('scale',Channel(None,[1,1,1])).at(t))}
        return cached[t]
    kept=set(times)
    def refine(ta,tb,depth):
        a,b=at(ta),at(tb);qa,qb=qfs(a['rotation']),qfs(b['rotation'])
        bad=False
        for u in [.25,.5,.75]:
            t=ta+(tb-ta)*u;actual=at(t);q=qfs(actual['rotation']);expected=qslerp(qa,qb,u)
            angle=math.degrees(2*math.acos(min(1,abs(sum(x*y for x,y in zip(q,expected))))))
            distance=math.sqrt(sum((v-(x+(y-x)*u))**2 for v,x,y in zip(actual['position'],a['position'],b['position'])))
            scale=max(abs(v-(x+(y-x)*u)) for v,x,y in zip(actual['scale'],a['scale'],b['scale']))
            if angle>.12 or distance>.02 or scale>.001:bad=True;break
        if bad and depth<22 and tb-ta>2e-8:
            mid=(ta+tb)*.5;kept.add(mid);refine(ta,mid,depth+1);refine(mid,tb,depth+1)
    ordered=sorted(times)
    for a,b in zip(ordered,ordered[1:]):refine(a,b,0)
    return [at(t) for t in sorted(kept)]

def import_model(source, kind, fp, hz):
    single = kind!='dual_blade'
    stem=('fp_singlesword' if single else 'fp_doublesword') if fp else ('ysm_player' if single else 'ysm_player_dual')
    gd=source/'models'/f'{stem}.geo.json'; ad=source/'animations'/f'{stem}.animation.json'
    geo=json.loads(gd.read_text(encoding='utf-8-sig'))['minecraft:geometry'][0]
    clips=json.loads(ad.read_text(encoding='utf-8-sig'))['animations']
    names=FP_SINGLE if fp and single else FP_DUAL if fp else TP_NAMES
    prefix='legacy_'+kind; filename=prefix+('_fp' if fp else '')+'.fsvoxel'
    bones={b['name']:b for b in geo['bones'] if not (fp and single and (b['name'].startswith('daoguang') or b['name'].startswith('spark')))}
    output=['FSVOXEL\t1\tmodel',payload('summary',{'kind':'model','purpose':'legacy_011_import','status':'verified_source'}),
        payload('id','fsrpg.'+prefix+('_fp' if fp else '')),payload('name','旧版 0.1.1 · '+kind+(' 第一人称' if fp else ' 第三人称')+' / '+str(hz)+'Hz'),
        payload('type','actor'),payload('units',{'voxel_cm':1})]
    for b in bones.values():
        parent=b.get('parent');pivot=b.get('pivot',[0,0,0]);pp=bones[parent]['pivot'] if parent else [0,0,0]
        output.append(payload('part',names.get(b['name'],b['name']),{'parent':names.get(parent,parent) or '',
            'transform':{'position':fsposition([p-q for p,q in zip(pivot,pp)]),
              'rotation':xyz_from_bedrock(b.get('rotation',[0,0,0])),'scale':[1,1,1]}}))
    # Mesh-free TP bindings use the player's own skin, armor and official held items.
    # FP skin cubes are imported separately with the original 64x64 skin UVs.
    if fp and kind!='bow':
        output.append(payload('material','skin',{'color':'#ffffff','texture':'minecraft:textures/entity/player/wide/steve.png','texture_size':[64,64]}))
        allowed={'right_arm','right_forearm','right_hand','left_arm','left_forearm','left_hand'} if not single else {'dissipate_view_0','right_forearm'}
        for b in bones.values():
            target=names.get(b['name'],b['name'])
            if target not in allowed:continue
            for cube_index,cube in enumerate(b.get('cubes',[])):
                # Native FS voxel set operations subtract overlaps. Skin/sleeve cubes
                # are independent transparent layers, so each receives its own node.
                mesh_part=target+'_skin_'+str(cube_index)
                output.append(payload('part',mesh_part,{'parent':target,'transform':{'position':[0,0,0],'rotation':[0,0,0],'scale':[1,1,1]}}))
                p=b['pivot'];o=cube['origin'];sz=cube['size'];inf=cube.get('inflate',0)
                lo=[o[0]-p[0]-inf,o[1]-p[1]-inf,-o[2]-sz[2]+p[2]-inf]
                size=[v+2*inf for v in sz]
                original_uv=cube.get('uv',[0,0]);w,h,d=sz
                if isinstance(original_uv,dict):
                    uv={({'north':'south','south':'north'}.get(face,face)):deepcopy(rect) for face,rect in original_uv.items()}
                else:
                    u,v=original_uv
                    # Signed rectangles preserve the Bedrock cube unwrap under the Z reflection.
                    rects={'east':[u+d+w+d,v+d,-d,h],'west':[u,v+d,d,h],
                      'up':[u+d,v,w,d],'down':[u+d+w,v+d,w,-d],
                      'south':[u+d,v+d,w,h],'north':[u+d+w+d+w,v+d,-w,h]}
                    uv={face:{'uv':r[:2],'uv_size':r[2:]} for face,r in rects.items()}
                if cube.get('mirror'): uv={face:{'uv':[r['uv'][0]+r['uv_size'][0],r['uv'][1]],'uv_size':[-r['uv_size'][0],r['uv_size'][1]]} for face,r in uv.items()}
                output.append(payload('box',mesh_part,'set','skin',[round(x*6.25,6) for x in lo],[round(x*6.25,6) for x in size],uv))
    selected=set(COMMON)
    if kind=='rapier':selected.update(RAPIER+DODGES);selected.update(n+'_upper' for n in RAPIER if n.startswith('attack') or n.startswith('rapier_thrust'))
    elif kind=='dual_blade':selected.update(DUAL);selected.update(n+'_upper' for n in DUAL if n.startswith('attack') or n.startswith('guard'))
    else:selected.update(BOW)
    if fp:
        if kind=='bow':selected={n for n in clips if n.startswith('fp_bow_')}|{'draw','put_away'}
        elif kind=='dual_blade':selected.update(['attack1','attack1_upper','attack2','attack2_upper','attack3','attack3_upper','attack4','attack4_upper','attack_dual','attack_dual_upper','static_pose','defend_1'])
        else:selected.update(['static_pose','defend','defend_1'])
    imported={};mapping=[];scale_normalization=[]
    for name in sorted(selected & clips.keys()):
        source_clip=clips[name];duration=float(source_clip.get('animation_length',1)); duration=max(duration,1/hz)
        ts={round(i/hz,9) for i in range(math.ceil(duration*hz))}|{duration}
        channels={bone:{c:Channel(v,[1,1,1] if c=='scale' else [0,0,0]) for c,v in ch.items()} for bone,ch in source_clip.get('bones',{}).items() if bone in bones}
        # Discontinuous pre/post boundaries need both sides even between the 60Hz keys.
        for ch in channels.values():
            for c in ch.values():
                for t,node in c.frames:
                    if 0<t<duration and isinstance(node,dict) and ('pre' in node or node.get('lerp_mode')=='step'):
                        ts.update([max(0,t-1e-7),t])
        tracks={}
        for bone,ch in channels.items():
            scale=ch.get('scale')
            if scale is not None and authored_scale_min(scale)<0:
                raise ValueError(f'Authored negative scale needs an explicit mirrored node: {stem}/{name}/{bone}')
            frames=adaptive_frames(ch,bones[bone].get('rotation',[0,0,0]),ts)
            tracks[names.get(bone,bone)]=frames
            if scale is not None:
                raw=[min(scale.at(f['time'])) for f in frames]
                if min(raw)<=0:
                    scale_normalization.append({'sourceClip':name,'sourceBone':bone,'targetBone':names.get(bone,bone),
                        'authoredMinimum':authored_scale_min(scale),'sampledMinimum':min(raw),
                        'zeroSamples':sum(v==0 for v in raw),'negativeOvershootSamples':sum(v<0 for v in raw),
                        'normalizedSamples':sum(v<HIDDEN_SCALE_EPSILON for v in raw),'epsilon':HIDDEN_SCALE_EPSILON,
                        'semantics':'hidden locator; nonnegative authored keys, Catmull-Rom undershoot'})
        action={'id':prefix+'_'+name,'name':name,'duration':duration,'layer':'upper' if name.endswith('_upper') else 'full',
            'loop':'loop' if source_clip.get('loop') is True else 'once','mode':'delta','rotation_mode':'quaternion',
            'interpolation':'linear','root_policy':'preserve','root_end':'hold','transition':0,
            'tags':['legacy_011','source:'+name,'source_hz:'+str(hz),'legacy_kind:'+kind], 'tracks':tracks}
        imported[name]=action;mapping.append({'action':action['id'],'sourceClip':name,'duration':duration,'layer':action['layer'],'alias':False})
    alias=deepcopy(ALIASES[kind])
    if fp:
        # Every TP intent has a matching FP projection; the authored rigs are independent.
        for key,name in list(alias.items()):
            if kind=='bow':
                alias[key]='fp_bow_'+key.removeprefix('bow_').replace('charge','draw') if key not in ['bow_draw','bow_put_away','bow_bash'] else {'bow_draw':'draw','bow_put_away':'put_away','bow_bash':'fp_bow_bash'}[key]
            elif name=='attack_1_upper':alias[key]='attack1_upper'
            elif name.endswith('_upper') and name.removesuffix('_upper') in imported:alias[key]=name.removesuffix('_upper')
            elif name=='defend':alias[key]='defend_1'
        alias.update({prefix+'_'+name:(name if name in imported else name.removesuffix('_upper')) for name in selected if name not in imported and name.removesuffix('_upper') in imported})
        if kind=='dual_blade':
            alias['dual_guard']='defend_1'
            alias[prefix+'_attack_1']='attack1'
            alias[prefix+'_attack_1_upper']='attack1_upper'
    if kind=='dual_blade':
        for l in range(3):
            for r in range(3):alias['dual_idle_'+str(l)+str(r)]='static_idle'
    for action in imported.values():output.append(payload('action',action))
    for key,name in sorted(alias.items()):
        if name not in imported:continue
        action=deepcopy(imported[name]);action['id']=key
        # Preserve the hand actually left by each old attack, rather than snapping READY to static_idle.
        if kind=='dual_blade' and key.startswith('dual_idle_'):
            l,r=map(int,key[-2:])
            for side,index,source_names in [('left',l,['attack_1_upper','attack4_upper']),('right',r,['attack2_upper','attack3_upper'])]:
                source_name=source_names[index-1] if index else None
                if fp and source_name=='attack_1_upper':source_name='attack1_upper'
                if source_name in imported:
                    for part,frames in imported[source_name]['tracks'].items():
                        if part.startswith(side+'_'):
                            value=deepcopy(frames[-1])
                            ready_times=sorted({round(i/hz,9) for i in range(math.ceil(action['duration']*hz))}|{action['duration']})
                            action['tracks'][part]=[{**value,'time':t} for t in ready_times]
        output.append(payload('action',action));mapping.append({'action':key,'sourceClip':name,'duration':action['duration'],'layer':action['layer'],'alias':True})
    return filename,'\n'.join(output)+'\n',{'model':filename,'kind':kind,'perspective':'first_person' if fp else 'third_person',
        'geometry':str(gd),'animation':str(ad),'sourceSha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [gd,ad]},
        'boneMap':{name:names.get(name,name) for name in bones},'scaleNormalization':scale_normalization,'actions':mapping}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--source',type=Path,default=Path(r'C:/Users/34003/Desktop/actioncombat_legacy_20260923/src/main/resources/assets/actioncombat/geckolib'))
    parser.add_argument('--hz',type=int,default=60);args=parser.parse_args()
    if args.hz<30:parser.error('Imported actions require at least 30Hz')
    manifest={'format':'fsrpg.legacy-motion-import.v1','sourceVersion':'0.1.1','samplingHz':args.hz,
       'adaptiveSampling':{'rotationErrorDegrees':.12,'positionErrorCm':.02,'scaleError':.001},
       'scalePolicy':{'hiddenEpsilon':HIDDEN_SCALE_EPSILON,'zero':'positive epsilon','negativeInterpolation':'clamp only nonnegative authored scale tracks','negativeAuthored':'reject; require explicit mirrored node'},
       'coordinates':'Bedrock Rz*Ry*Rx(-X,-Y,+Z) -> 180Y basis -> FS XYZ, 6.25cm/unit; hierarchy/locators retained',
       'meshPolicy':'TP uses the real player skin/armor; FP retains skin cubes; no weapon cubes are imported', 'models':[]}
    dest=ROOT/'src/main/resources/assets/fsrpg/fsvoxel/models';config=ROOT/'run/client/config/fsrpg/models'
    for kind in ['rapier','dual_blade','bow']:
        for fp in [False,True]:
            name,data,report=import_model(args.source,kind,fp,args.hz)
            for folder in [dest,config]:folder.mkdir(parents=True,exist_ok=True);(folder/name).write_bytes(data.encode('utf-8'))
            report['outputSha256']=hashlib.sha256(data.encode()).hexdigest();manifest['models'].append(report)
            print(name,len(data.encode()),'bytes',len(report['actions']),'actions')
    docs=ROOT/'docs';docs.mkdir(exist_ok=True);(docs/'legacy-0.1.1-animation-map.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

if __name__=='__main__':main()
