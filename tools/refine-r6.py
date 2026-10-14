from pathlib import Path
import json
p=Path('tools/weapon-choreography.json');j=json.loads(p.read_text(encoding='utf8'));j['dualFollowArc'][2][8]=129;p.write_text(json.dumps(j,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
