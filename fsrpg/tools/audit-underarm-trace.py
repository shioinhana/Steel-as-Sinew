import json,math
from pathlib import Path
import numpy as np
root=Path('verification/spear-underarm-turn-20261014')
for f in range(14,18):
    t=json.loads((root/f'trace-{f}.json').read_text(encoding='utf-8'))
    print('FRAME',f,'AIM',t['aim'])
    for stage in ['previous','world','raw']:
        w={k:np.array(v).reshape(4,4,order='F') for k,v in t[stage].items()}
        body=w['body'];inv=np.linalg.inv(body)
        print(stage,'heading',math.degrees(math.atan2(body[0,2],body[2,2])),'origin',body[:3,3],'up',body[:3,1])
        for side in ['right','left']:
            s=w[side+'_arm'][:3,3];e=w[side+'_forearm'][:3,3];p=(w[side+'_hand']@np.array([0,-6.25,0,1]))[:3]
            local=np.linalg.inv(w[side+'_forearm'])@w[side+'_hand']
            print(side,'local palm',(inv@np.r_[p,1])[:3],'upper',inv[:3,:3]@(e-s),'palm world',p,'twist',math.degrees(math.atan2(local[0,2],local[2,2])),'pole',w[side+'_hand'][:3,2])
