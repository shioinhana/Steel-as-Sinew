"""Export the supplied Blockbench assets to native Minecraft 1.21.11 item models."""
import base64,hashlib,json,math,subprocess,sys
import numpy as np
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=root/'artwork/imports/261001'
assets=root/'src/main/resources/assets/fsrpg'
entries=[('money_pouch','钱袋','Money Pouch','qiandai.bbmodel',None),
 ('gold_coin','金币','Gold Coin','硬币.bbmodel',None),
 ('silver_coin','银币','Silver Coin','硬币.bbmodel',0),
 ('crystal_coin','晶币','Crystal Coin','金币.bbmodel',None),
 ('nether_coin','下界币','Nether Coin','下届币.bbmodel',None),
 ('gold_adventurer_badge','金色冒险者牌','Gold Adventurer Badge','金色冒险者牌.bbmodel',None),
 ('cobalt_adventurer_badge','钴色冒险者牌','Cobalt Adventurer Badge','钴色冒险者牌.bbmodel',None),
 ('copper_adventurer_badge','铜色冒险者牌','Copper Adventurer Badge','铜色冒险者牌.bbmodel',None),
 ('silver_adventurer_badge','银色冒险者牌','Silver Adventurer Badge','银色冒险者牌.bbmodel',None)]
def save(path,data):
 path.parent.mkdir(parents=True,exist_ok=True)
 path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
manifest=[]
for ident,zh,en,file,override in entries:
 d=json.loads((source/file).read_text(encoding='utf-8'))
 assert not any(any(g.get('rotation',[0,0,0])) for g in d.get('groups',[])),'Individual model has a transformed group'
 textures={};elements=[]
 for i,t in enumerate(d['textures']):
  raw=base64.b64decode(t['source'].split(',',1)[1]);assert raw.startswith(b'\x89PNG\r\n\x1a\n')
  path=assets/f'textures/item/{ident}_{i}.png';path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(raw)
  textures[str(i)]=f'fsrpg:item/{ident}_{i}'
 for e in d['elements']:
  if not e.get('export',True):continue
  assert e.get('type','cube')=='cube'
  assert all(-16<=v<=32 for k in ('from','to') for v in e[k])
  faces={}
  for face,f in e['faces'].items():
   if f.get('texture') is None:continue
   i=int(f['texture']) if override is None else override;t=d['textures'][i]
   uv=f['uv'];faces[face]={'uv':[uv[0]*16/t['width'],uv[1]*16/t['height'],uv[2]*16/t['width'],uv[3]*16/t['height']],'texture':f'#{i}'}
   if f.get('rotation'):faces[face]['rotation']=f['rotation']
  if not faces:continue
  cube={'from':e['from'],'to':e['to'],'faces':faces}
  rot=e.get('rotation',[0,0,0])
  if any(rot):cube['rotation']={'origin':e.get('origin',[8,8,8]),'x':rot[0],'y':rot[1],'z':rot[2],'rescale':e.get('rescale',False)}
  if not e.get('shade',True):cube['shade']=False
  elements.append(cube)
 textures['particle']=next(iter(elements[0]['faces'].values()))['texture']
 display=dict(d.get('display',{}))
 if ident.endswith('badge') or ident=='nether_coin':
  points=[]
  for e in elements:
   r=e.get('rotation',{});angles=[math.radians(r.get(a,0)) for a in ('x','y','z')];cx,sx=math.cos(angles[0]),math.sin(angles[0]);cy,sy=math.cos(angles[1]),math.sin(angles[1]);cz,sz=math.cos(angles[2]),math.sin(angles[2])
   rot=np.array([[cz,-sz,0],[sz,cz,0],[0,0,1]])@np.array([[cy,0,sy],[0,1,0],[-sy,0,cy]])@np.array([[1,0,0],[0,cx,-sx],[0,sx,cx]])
   origin=np.array(r.get('origin',[8,8,8]));rescale=1/np.max(np.abs(rot),axis=0) if r.get('rescale') else np.ones(3)
   points.extend(rot@((np.array([x,y,z])-origin)*rescale)+origin for x in [e['from'][0],e['to'][0]] for y in [e['from'][1],e['to'][1]] for z in [e['from'][2],e['to'][2]])
  points=np.array(points);center=(points.min(axis=0)+points.max(axis=0))/2;scale=min(1,14/max((points.max(axis=0)-points.min(axis=0))[[0,2]]))
  # Flat badges/coins need their face presented to the inventory camera.
  guiRotation=np.array([[1,0,0],[0,0,-1],[0,1,0]])
  display['gui']={'rotation':[90,0,0],'translation':(-guiRotation@(center-8)*scale).round(6).tolist(),'scale':[scale]*3}
 save(assets/f'models/item/{ident}.json',{'textures':textures,'elements':elements,'display':display,'gui_light':'front'})
 save(assets/f'items/{ident}.json',{'model':{'type':'minecraft:model','model':f'fsrpg:item/{ident}'}})
 manifest.append({'id':f'fsrpg:{ident}','source':file,'sourceSha256':hashlib.sha256((source/file).read_bytes()).hexdigest(),'elements':len(elements),'textureOverride':override,'label':zh})
 for lang,label in [('zh_cn',zh),('en_us',en)]:
  p=assets/f'lang/{lang}.json';data=json.loads(p.read_text(encoding='utf-8-sig')) if p.exists() else {};data[f'item.fsrpg.{ident}']=label;save(p,data)
save(source/'import-manifest.json',{'minecraft':'1.21.11','nativeEulerXYZ':True,'items':manifest,'layoutSource':'综合.bbmodel','notes':'Individual source files are retained. Gold-coin geometry also uses the supplied silver texture as silver_coin. The jingbi-textured source named 金币 is crystal_coin.'})
print(f'Imported {len(manifest)} native item models, original PNGs and Chinese/English labels')
# The later user-confirmed silver-badge replacement has its own source/manifest.
# Rebuilding the older archive must retain that appearance on the same item ID.
replacement=root/'artwork/imports/white_adventurer/白色冒险者.bbmodel'
if replacement.is_file():subprocess.run([sys.executable,str(root/'tools/import-white-adventurer.py'),str(replacement)],check=True)
