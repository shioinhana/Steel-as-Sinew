"""Import the supplied static Blockbench object without changing its source geometry/PNG."""
from pathlib import Path
import argparse, base64, hashlib, json, math, shutil
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/fsrpg'
TARGET = 'silver_adventurer_badge'
FACES = {
    'down': [(0,0,1),(0,0,0),(1,0,0),(1,0,1)], 'up': [(0,1,0),(0,1,1),(1,1,1),(1,1,0)],
    'north': [(1,1,0),(1,0,0),(0,0,0),(0,1,0)], 'south': [(0,1,1),(0,0,1),(1,0,1),(1,1,1)],
    'west': [(0,1,0),(0,0,0),(0,0,1),(0,1,1)], 'east': [(1,1,1),(1,0,1),(1,0,0),(1,1,0)]}

def rotation(v):
    x,y,z=np.radians(v); cx,sx=math.cos(x),math.sin(x);cy,sy=math.cos(y),math.sin(y);cz,sz=math.cos(z),math.sin(z)
    return np.array([[cz,-sz,0],[sz,cz,0],[0,0,1]]) @ np.array([[cy,0,sy],[0,1,0],[-sy,0,cy]]) @ np.array([[1,0,0],[0,cx,-sx],[0,sx,cx]])

def dump(path, value, pretty=False):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes((json.dumps(value,ensure_ascii=False,indent=2 if pretty else None,separators=None if pretty else (',',':'))+'\n').encode('utf8'))

def preview(model, path):
    from PIL import Image, ImageDraw
    texture=np.asarray(Image.open(ASSETS / model['textures'][0]['id'].split(':',1)[1]).convert('RGBA'))
    faces=[]; worlds=[]
    for node in model['nodes']:
        m=np.eye(4);m[:3,:3]=rotation(node['rotation']);m[:3,3]=node['position']
        if node['parent']>=0:m=worlds[node['parent']] @ m
        worlds.append(m)
        for f in node['faces']:
            v=np.array(f['vertices']); xyz=(m @ np.c_[v[:,:3],np.ones(4)].T).T[:,:3]
            faces.append((xyz,v[:,3:5]))
    output=Image.new('RGB',(1500,540),(30,39,51)); draw=ImageDraw.Draw(output)
    for panel,(yaw,pitch,label) in enumerate([(35,22,'Source front / side'),(155,18,'Source rear / side'),(35,68,'Source top')]):
        view=rotation([pitch,yaw,0]); allv=np.concatenate([p @ view.T for p,uv in faces]);lo=allv.min(axis=0);hi=allv.max(axis=0)
        scale=min(440/(hi[0]-lo[0]),440/(hi[1]-lo[1]));center=(lo+hi)/2
        image=np.zeros((500,500,4),dtype=np.uint8);image[:]=[30,39,51,255];depth=np.full((500,500),-np.inf)
        for xyz,uv in faces:
            p=xyz @ view.T; xy=(p[:,:2]-center[:2])*scale+250;xy[:,1]=500-xy[:,1]
            for indices in [(0,1,2),(0,2,3)]:
                v=xy[list(indices)];z=p[list(indices),2];t=uv[list(indices)]
                x0=max(0,int(np.floor(v[:,0].min())));x1=min(499,int(np.ceil(v[:,0].max())));y0=max(0,int(np.floor(v[:,1].min())));y1=min(499,int(np.ceil(v[:,1].max())))
                if x1<x0 or y1<y0:continue
                den=(v[1,1]-v[2,1])*(v[0,0]-v[2,0])+(v[2,0]-v[1,0])*(v[0,1]-v[2,1])
                if abs(den)<1e-8:continue
                yy,xx=np.mgrid[y0:y1+1,x0:x1+1];xx=xx+.5;yy=yy+.5
                a=((v[1,1]-v[2,1])*(xx-v[2,0])+(v[2,0]-v[1,0])*(yy-v[2,1]))/den
                b=((v[2,1]-v[0,1])*(xx-v[2,0])+(v[0,0]-v[2,0])*(yy-v[2,1]))/den;c=1-a-b
                zz=a*z[0]+b*z[1]+c*z[2]; mask=(a>=-1e-6)&(b>=-1e-6)&(c>=-1e-6)&(zz>depth[y0:y1+1,x0:x1+1])
                tx=np.clip(((a*t[0,0]+b*t[1,0]+c*t[2,0])*texture.shape[1]).astype(int),0,texture.shape[1]-1)
                ty=np.clip(((a*t[0,1]+b*t[1,1]+c*t[2,1])*texture.shape[0]).astype(int),0,texture.shape[0]-1)
                pixels=texture[ty,tx]; mask &= pixels[:,:,3]>0
                image[y0:y1+1,x0:x1+1][mask]=pixels[mask];depth[y0:y1+1,x0:x1+1][mask]=zz[mask]
        output.paste(Image.fromarray(image).convert('RGB'),(panel*500,20));draw.text((panel*500+20,12),label,fill=(235,235,235))
    draw.text((20,520),'Original 169 cuboids / original 64 x 64 embedded texture / no added geometry or animation',fill=(210,215,225))
    path.parent.mkdir(parents=True,exist_ok=True);output.save(path)

def main():
    retained_source=ROOT/'artwork/imports/white_adventurer/白色冒险者.bbmodel'
    parser=argparse.ArgumentParser();parser.add_argument('source',nargs='?',default=str(retained_source) if retained_source.is_file() else 'C:/Users/34003/Downloads/白色冒险者.bbmodel');parser.add_argument('--preview',type=Path);args=parser.parse_args()
    source=Path(args.source);raw=source.read_bytes();data=json.loads(raw.decode('utf-8-sig'))
    if any(any(g.get('rotation',[0,0,0])) for g in data.get('groups',[])):
        raise ValueError('Native item export requires the supplied unrotated groups')
    retained=ROOT/'artwork/imports/white_adventurer'/source.name;retained.parent.mkdir(parents=True,exist_ok=True)
    if source.resolve()!=retained.resolve():shutil.copyfile(source,retained)
    textures=[]
    for i,t in enumerate(data['textures']):
        png=base64.b64decode(t['source'].split(',',1)[1]);rel=f'textures/item/{TARGET}_{i}.png';target=ASSETS/rel;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(png)
        textures.append({'id':'fsrpg:'+rel,'width':t.get('uv_width',t['width']),'height':t.get('uv_height',t['height']),'sha256':hashlib.sha256(png).hexdigest()})
    groups={g['uuid']:g for g in data.get('groups',[])};elements={e['uuid']:e for e in data['elements']};nodes=[]
    def visit(uid,parent=-1,parent_origin=None,children=None):
        if uid in groups:
            g=groups[uid];origin=np.array(g.get('origin',[0,0,0]),dtype=float);index=len(nodes)
            nodes.append({'id':uid,'name':g['name'],'parent':parent,'position':((origin-(parent_origin if parent_origin is not None else np.zeros(3)))/16).tolist(),'rotation':g.get('rotation',[0,0,0]),'faces':[]})
            for child in children or []:
                visit(child if isinstance(child,str) else child['uuid'],index,origin,None if isinstance(child,str) else child.get('children',[]))
            return
        e=elements[uid]
        if not e.get('export',True):return
        if e.get('type','cube')!='cube':raise ValueError('Only supplied cuboids are supported')
        if parent<0:
            parent=len(nodes);parent_origin=np.zeros(3);nodes.append({'id':'root_'+uid,'name':'root','parent':-1,'position':[0,0,0],'rotation':[0,0,0],'faces':[]})
        lo=np.array(e['from'])-e.get('inflate',0);hi=np.array(e['to'])+e.get('inflate',0);origin=np.array(e.get('origin',[0,0,0]));r=rotation(e.get('rotation',[0,0,0]))
        for side,f in e['faces'].items():
            if f.get('texture') is None or f['texture']==-1:continue
            tex=int(f['texture']);u0,v0,u1,v1=f['uv'];uv=[(u0,v0),(u0,v1),(u1,v1),(u1,v0)];turn=int(f.get('rotation',0)/90)%4;uv=uv[turn:]+uv[:turn]
            points=[r@(np.where(np.array(corner),hi,lo)-origin)+origin for corner in FACES[side]];normal=np.cross(points[1]-points[0],points[2]-points[0]);area=np.linalg.norm(normal)
            if area<1e-12:continue
            normal/=area;vertices=[]
            for point,uvpoint in zip(points,uv):vertices.append([*((point-parent_origin)/16).tolist(),uvpoint[0]/textures[tex]['width'],uvpoint[1]/textures[tex]['height'],*normal.tolist()])
            nodes[parent]['faces'].append({'texture':tex,'vertices':vertices})
    for n in data['outliner']:visit(n if isinstance(n,str) else n['uuid'],children=None if isinstance(n,str) else n.get('children',[]))
    matrices=[];points=[]
    for n in nodes:
        m=np.eye(4);m[:3,:3]=rotation(n['rotation']);m[:3,3]=n['position']
        if n['parent']>=0:m=matrices[n['parent']]@m
        matrices.append(m)
        for f in n['faces']:
            for v in f['vertices']:points.append((m@np.array([*v[:3],1]))[:3])
    bounds=[np.min(points,axis=0).tolist(),np.max(points,axis=0).tolist()]
    model={'id':TARGET,'source':source.name,'sourceSha256':hashlib.sha256(raw).hexdigest(),'textures':textures,'nodes':nodes,'clips':[],'bounds':bounds}
    # Minecraft 1.21.11 supports per-element Euler XYZ rotations natively, as used by
    # the previously imported gold/cobalt/copper/silver adventurer badges.
    native=[]
    for e in data['elements']:
        if not e.get('export',True):continue
        faces={}
        for side,f in e['faces'].items():
            if f.get('texture') is None or f['texture']==-1:continue
            i=int(f['texture']);t=textures[i];uv=f['uv']
            face={'uv':[uv[0]*16/t['width'],uv[1]*16/t['height'],uv[2]*16/t['width'],uv[3]*16/t['height']],'texture':f'#{i}'}
            if f.get('rotation'):face['rotation']=f['rotation']
            faces[side]=face
        cube={'from':e['from'],'to':e['to'],'faces':faces}
        r=e.get('rotation',[0,0,0])
        if any(r):cube['rotation']={'origin':e.get('origin',[8,8,8]),'x':r[0],'y':r[1],'z':r[2],'rescale':e.get('rescale',False)}
        if not e.get('shade',True):cube['shade']=False
        native.append(cube)
    dimensions=(np.array(bounds[1])-bounds[0])*16
    center=(np.array(bounds[0])+bounds[1])*8;scale=min(1,14/max(dimensions[[0,2]]))
    gui_rotation=np.array([[1,0,0],[0,0,-1],[0,1,0]])
    display=dict(data.get('display',{}));display['gui']={'rotation':[90,0,0],'translation':(-gui_rotation@(center-8)*scale).round(6).tolist(),'scale':[scale]*3}
    # The source has no held/drop transforms. Identity made this 24px plaque a
    # body-sized item and left its first-person face below the viewport.
    # These context transforms change presentation only, never the source cubes/UVs.
    for hand in ['righthand','lefthand']:
        display[f'firstperson_{hand}']={'rotation':[90,0,0],'translation':[0,4.5,0],'scale':[.30,.30,.30]}
        # PlayerModel.translateToHand is the shoulder/arm-part matrix. Keep the
        # layer's -10px local-Z offset to reach the hand; -2px hangs the badge
        # below the fingers. With the living renderer's outer Y180, local +Y
        # moves the broad face in front of the sleeve rather than behind it.
        display[f'thirdperson_{hand}']={'rotation':[0,0,0],'translation':[-1,1.5,-2],'scale':[.18,.18,.18]}
    display['ground']={'rotation':[0,0,0],'translation':[0,3,0],'scale':[.22,.22,.22]}
    display['fixed']={'rotation':[90,0,0],'translation':(-gui_rotation@(center-8)*.45).round(6).tolist(),'scale':[.45,.45,.45]}
    native_textures={str(i):t['id'].replace(':textures/',':',1).removesuffix('.png') for i,t in enumerate(textures)}
    dump(ASSETS/f'models/item/{TARGET}.json',{'textures':{**native_textures,'particle':'#0'},'elements':native,'display':display,'gui_light':'front'},True)
    dump(ASSETS/f'items/{TARGET}.json',{'model':{'type':'minecraft:model','model':f'fsrpg:item/{TARGET}'}},True)
    manifest={'item':'fsrpg:'+TARGET,'label':'银色冒险者牌','replacementTargetConfirmedByUser':True,'source':source.name,'sourceSha256':model['sourceSha256'],'format':data['meta'],'elements':len(elements),'groups':len(groups),'animations':len(data.get('animations',[])),'faces':sum(len(n['faces']) for n in nodes),'boundsBlocks':bounds,'dimensionsPixels':dimensions.tolist(),'textures':textures,'scope':'Replace the existing silver_adventurer_badge appearance. Preserve its registered ID, name, stack size and item group. Preserve source cuboids, Euler XYZ rotations, per-face UV and original embedded PNG bytes. No new item, player/mob replacement, AI, stats, currency value or invented animation.'}
    dump(retained.parent/'manifest.json',manifest)
    dump(ASSETS/f'art/item_replacements/{TARGET}.json',manifest)
    if args.preview:preview(model,args.preview)
    print(json.dumps(manifest,ensure_ascii=True,indent=2))

if __name__=='__main__':main()
