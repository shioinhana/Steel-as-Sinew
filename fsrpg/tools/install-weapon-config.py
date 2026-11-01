"""Merge newly enabled styles/skills, keeping existing rapier values and backups."""
from pathlib import Path
import json,shutil
root=Path(__file__).resolve().parents[1]
directory=root/'run/client/config/fsrpg'
for name in ('skills','styles','weapons'):
 path=directory/(name+'.json');backup=path.with_suffix('.json.pre-0.3.0.bak')
 if not backup.exists():shutil.copy2(path,backup)
 current=json.loads(path.read_text(encoding='utf-8-sig'));defaults=json.loads((root/f'src/main/resources/defaults/{name}.json').read_text(encoding='utf-8'))
 if name=='skills':
  ids={s['id'] for s in current};current += [s for s in defaults if s['id'] not in ids]
 elif name=='styles':
  for i,s in enumerate(current):
   if s['id'] in ('spear','dual_blade') and not s['enabled']:current[i]=next(d for d in defaults if d['id']==s['id'])
 else:
  for group in ('spear','dual_blade'):
   for key in ('mainItems','offhandItems'):
    for item in defaults['groups'][group][key]:
     if item not in current['groups'][group][key]:current['groups'][group][key].append(item)
  # Trident was only a reserved placeholder; actual long spear uses the official 1.21.11 spear family.
  current['groups']['spear']['mainItems']=[x for x in current['groups']['spear']['mainItems'] if x!='minecraft:trident']
 path.write_text(json.dumps(current,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
for name in ('dual_blade','spear'):
 path=directory/f'models/{name}.fsvoxel'
 if path.exists() and not path.with_suffix('.fsvoxel.pre-0.3.0.bak').exists():shutil.copy2(path,path.with_suffix('.fsvoxel.pre-0.3.0.bak'))
 shutil.copy2(root/f'src/main/resources/assets/fsrpg/fsvoxel/models/{name}.fsvoxel',path)
