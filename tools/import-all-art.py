"""Deterministic source-art import; combat balance and world generation are not invented here."""
from pathlib import Path
import base64,collections,hashlib,json,math,re,shutil,subprocess
import numpy as np
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'src/main/resources/assets/fsrpg'
OUT=ASSETS/'art'
SOURCE=ROOT/'artwork/imports'
GIT='C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\IDE\\CommonExtensions\\Microsoft\\TeamFoundation\\Team Explorer\\Git\\cmd\\git.exe'
def dump(p,v):
 p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8')
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def rot(v):
 x,y,z=np.radians(v);cx,sx=math.cos(x),math.sin(x);cy,sy=math.cos(y),math.sin(y);cz,sz=math.cos(z),math.sin(z)
 return np.array([[cz,-sz,0],[sz,cz,0],[0,0,1]])@np.array([[cy,0,sy],[0,1,0],[-sy,0,cy]])@np.array([[1,0,0],[0,cx,-sx],[0,sx,cx]])
# Exact face order used by Minecraft's FaceInfo; UVs follow the supplied face rectangles.
FACES={'down':[(0,0,1),(0,0,0),(1,0,0),(1,0,1)],'up':[(0,1,0),(0,1,1),(1,1,1),(1,1,0)],
 'north':[(1,1,0),(1,0,0),(0,0,0),(0,1,0)],'south':[(0,1,1),(0,0,1),(1,0,1),(1,1,1)],
 'west':[(0,1,0),(0,0,0),(0,0,1),(0,1,1)],'east':[(1,1,1),(1,0,1),(1,0,0),(1,1,0)]}
def canonical(s):return s.lower().replace("'",'').replace('denfend','defend')
creatures=[];profiles=collections.OrderedDict();rawFiles=[]
for path in sorted((SOURCE/'creatures').rglob('*.bbmodel')):
 d=json.loads(path.read_text(encoding='utf-8'));ident=re.sub(r' - Converted$','',path.stem).lower()
 key=ident.removesuffix('_inner').removesuffix('_outer');key='ender_dragon' if key=='dragon' else key
 textures=[]
 for i,t in enumerate(d['textures']):
  raw=base64.b64decode(t['source'].split(',',1)[1]);target=ASSETS/f'textures/art/creatures/{ident}_{i}.png';target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
  textures.append({'id':f'fsrpg:textures/art/creatures/{ident}_{i}.png','width':t.get('uv_width',t['width']),'height':t.get('uv_height',t['height']),'sha256':hashlib.sha256(raw).hexdigest()})
 groups={g['uuid']:g for g in d.get('groups',[])};elements={e['uuid']:e for e in d['elements']};nodes=[];indices={};bounds=[]
 def node(uid,parent=-1,parentOrigin=None,children=None):
  if uid in groups:
   g=groups[uid]
   if not g.get('export',True):return
   origin=np.array(g.get('origin',[0,0,0]),dtype=float);i=len(nodes);indices[uid]=i
   nodes.append({'id':uid,'name':g['name'],'parent':parent,'position':((origin-(parentOrigin if parentOrigin is not None else np.zeros(3)))/16).tolist(),'rotation':g.get('rotation',[0,0,0]),'faces':[]})
   for child in children or []:
    if isinstance(child,str):node(child,i,origin)
    else:node(child['uuid'],i,origin,child.get('children',[]))
  elif uid in elements:
   e=elements[uid]
   if not e.get('export',True):return
   assert e.get('type','cube')=='cube',f'Non-cube in {path}'
   if parent<0:
    parent=len(nodes);nodes.append({'id':'root_'+uid,'name':'root','parent':-1,'position':[0,0,0],'rotation':[0,0,0],'faces':[]});parentOrigin=np.zeros(3)
   lo=np.array(e['from'],dtype=float)-e.get('inflate',0);hi=np.array(e['to'],dtype=float)+e.get('inflate',0)
   origin=np.array(e.get('origin',[0,0,0]));rotation=rot(e.get('rotation',[0,0,0]))
   for face,f in e['faces'].items():
    if f.get('texture') is None or f['texture']==-1:continue
    texture=int(f['texture']);uv=f['uv'];u0,v0,u1,v1=uv
    uvpoints=[(u0,v0),(u0,v1),(u1,v1),(u1,v0)];turn=int(f.get('rotation',0)/90)%4;uvpoints=uvpoints[turn:]+uvpoints[:turn]
    points=[rotation@(np.where(np.array(c),hi,lo)-origin)+origin for c in FACES[face]]
    normal=np.cross(points[1]-points[0],points[2]-points[0]);area=np.linalg.norm(normal)
    if area<1e-12:continue # zero-thickness planes have invisible, degenerate edge faces
    normal/=area
    vertices=[]
    for p,uvp in zip(points,uvpoints):
     bounds.append(p/16);local=(p-parentOrigin)/16
     vertices.append([*local,uvp[0]/textures[texture]['width'],uvp[1]/textures[texture]['height'],*normal])
    nodes[parent]['faces'].append({'texture':texture,'vertices':vertices})
 for n in d['outliner']:
  if isinstance(n,str):node(n)
  else:node(n['uuid'],children=n.get('children',[]))
 clips=[]
 for a in d.get('animations',[]):
  tracks=[]
  for uid,animator in a.get('animators',{}).items():
   if uid not in indices:continue
   for channel in ['rotation','position','scale']:
    frames=[]
    for k in sorted((k for k in animator.get('keyframes',[]) if k['channel']==channel),key=lambda k:k['time']):
     points=[[float(p.get(axis,1 if channel=='scale' else 0)) for axis in ['x','y','z']] for p in k['data_points']]
     frames.append({'time':k['time'],'pre':points[0],'post':points[-1],'interpolation':k.get('interpolation','linear')})
    if frames:tracks.append({'node':indices[uid],'channel':channel,'frames':frames})
  clips.append({'id':canonical(a['name']),'sourceName':a['name'],'length':max(.001,a.get('length',1)),'loop':a.get('loop')=='loop','tracks':tracks})
 # Fit the actual rest hierarchy, including the artist's nonzero bone rotations.
 matrices=[];bounds=[]
 for n in nodes:
  local=np.eye(4);local[:3,:3]=rot(n['rotation']);local[:3,3]=n['position']
  world=matrices[n['parent']]@local if n['parent']>=0 else local;matrices.append(world)
  for f in n['faces']:
   for v in f['vertices']:bounds.append((world@np.array([*v[:3],1]))[:3])
 b=np.array(bounds);bound=[b.min(axis=0).tolist(),b.max(axis=0).tolist()]
 model={'id':ident,'entity':f'minecraft:{key}','source':path.name,'sourceSha256':digest(path),'textures':textures,'nodes':nodes,'clips':clips,'bounds':bound}
 dump(OUT/f'creatures/{ident}.json',model)
 row={'id':ident,'entity':model['entity'],'source':path.name,'nodes':len(nodes),'faces':sum(len(n['faces']) for n in nodes),'clips':[{'id':c['id'],'name':c['sourceName'],'length':c['length']} for c in clips],'bounds':bound}
 creatures.append(row);p=profiles.setdefault(key,{'id':key,'entity':f'minecraft:{key}','models':[],'clips':[],'bounds':bound,'boss':key in ['ender_dragon','wither','ravager']})
 p['models'].append(ident);p['clips']=list(dict.fromkeys([*p['clips'],*[c['id'] for c in clips]]));p['bounds']=[np.minimum(p['bounds'][0],bound[0]).tolist(),np.maximum(p['bounds'][1],bound[1]).tolist()]
hud=[]
for path in sorted((SOURCE/'hud').rglob('*.png')):
 cat=path.relative_to(SOURCE/'hud').parts[0];ident=f'{cat}_{len(hud):02d}';target=ASSETS/f'textures/art/hud/{ident}.png';target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,target)
 with Image.open(path) as im:width,height=im.size
 hud.append({'id':ident,'label':cat+' '+path.stem,'texture':f'fsrpg:textures/art/hud/{ident}.png','width':width,'height':height,'sourceSha256':digest(path)})

catalog={'version':1,'creatures':creatures,'profiles':list(profiles.values()),'hud':hud}
dump(OUT/'catalog.json',catalog)
for lang in ['zh_cn','en_us']:
 p=ASSETS/f'lang/{lang}.json'
 labels=json.loads(subprocess.check_output([GIT,'show',f'HEAD:src/main/resources/assets/fsrpg/lang/{lang}.json']).decode('utf-8-sig'))
 labels['key.fsrpg.art_gallery']='生物与HUD素材图鉴' if lang=='zh_cn' else 'Creature and HUD Gallery'
 p.write_text(json.dumps(labels,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
for path in sorted(SOURCE.rglob('*')):
 if path.is_file() and path.relative_to(SOURCE).parts[0] in ['creatures','hud']:rawFiles.append({'path':str(path.relative_to(SOURCE)).replace('\\','/'),'sha256':digest(path)})
archives=Path('C:/Users/34003/Desktop/xy001003')
dump(SOURCE/'all-assets-manifest.json',{'scope':'Only the five explicitly supplied creature/HUD archives; other-project forging/structure resources excluded. Existing weapons and 261001 items retained.','counts':{'models':len(creatures),'entities':len(profiles),'clips':sum(len(c['clips']) for c in creatures),'creatureTextures':sum(len(json.loads((OUT/f"creatures/{c['id']}.json").read_text(encoding='utf8'))['textures']) for c in creatures),'hudImages':len(hud)},'creatures':creatures,'sourceFiles':rawFiles,'archives':[{'path':str(archives/name),'sha256':digest(archives/name)} for name in ['生命条素材.zip','生物动作素材.zip','体力条素材x.rar','经验条素材.zip','灵力条素材.zip']]})
print(json.dumps({'models':len(creatures),'entities':len(profiles),'clips':sum(len(c['clips']) for c in creatures),'hudImages':len(hud)}))
