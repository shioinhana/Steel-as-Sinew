"""One-time authoring migration. Runtime upgrades use the Java field-preserving migration."""
import json, pathlib, shutil
root=pathlib.Path(__file__).resolve().parents[1]
defaults=root/'src/main/resources/defaults'
baseline=root/'verification/legacy-skills-20261012-001758/before/src/main/resources/defaults'
backup=defaults/'legacy-0.4'
backup.mkdir(exist_ok=True)
for name in ('skills.json','styles.json','weapons.json'):
    shutil.copy2(baseline/name,backup/name)
skills=json.loads((baseline/'skills.json').read_text(encoding='utf-8-sig'))
byid={s['id']:s for s in skills}
for i,id in enumerate(('sword_left','rapier_downcut','rapier_crosscut','rapier_light_thrust'),1):
    byid[id].update(label=f'西洋剑 · 旧版第{i}刀',model='legacy_rapier.fsvoxel',action=f'legacy_rapier_attack{i}_upper',
                    windupTicks=3 if i<4 else 4,activeTicks=4 if i<4 else 5,recoveryTicks=1,
                    cooldownTicks=0,damage=(6,6,7,9)[i-1],reach=4,arcDegrees=70,knockback=0)
byid['sword_thrust'].update(label='西洋剑 · 突刺',model='legacy_rapier.fsvoxel',action='legacy_rapier_rapier_thrust_hit_upper',
                         windupTicks=7,activeTicks=2,recoveryTicks=2,cooldownTicks=20,damage=9,reach=4,arcDegrees=45,
                         knockback=0,blockable=True,parryable=True,clashable=False)
for id,clip,damage in [('dual_ll','attack_1',4),('dual_rl','attack2',4),('dual_rr','attack3',4),('dual_lr','attack4',5)]:
    byid[id].update(label={'dual_ll':'双刀 · 左手左斩','dual_rl':'双刀 · 右手左斩','dual_rr':'双刀 · 右手右斩','dual_lr':'双刀 · 左手右斩'}[id],
                   model='legacy_dual_blade.fsvoxel',action='legacy_dual_blade_'+clip+'_upper',
                   windupTicks=3,activeTicks=6,recoveryTicks=1,cooldownTicks=0,damage=damage,reach=2.25,arcDegrees=220)
def extra(id,label,weapon,input,action,damage=0,cooldown=0):
    value=dict(byid['dual_ll'] if weapon=='dual_blade' else byid['sword_thrust'])
    value.update(id=id,label=label,weapon=weapon,input=input,action=action,
                 model='legacy_dual_blade.fsvoxel' if weapon=='dual_blade' else 'legacy_bow.fsvoxel',
                 windupTicks=3 if weapon=='dual_blade' else 0,activeTicks=5 if weapon=='dual_blade' else 1,
                 recoveryTicks=0,cooldownTicks=cooldown,damage=damage,staminaCost=12 if weapon=='dual_blade' else 0,
                 spiritCost=0,knockback=.35 if weapon=='dual_blade' else 0,reach=2.25 if weapon=='dual_blade' else 2.5)
    skills.append(value)
extra('dual_combined','双刀 · 联动斩','dual_blade','SPECIAL','legacy_dual_blade_attack_dual_upper',8,100)
extra('bow_bash','弓 · 弓身击退','bow','LEFT','bow_bash',4,7)
for stance,label in [('rapid','速射'),('snipe','狙击')]:
    extra(f'bow_{stance}_charge',f'弓 · {label}拉弓','bow','RIGHT',f'bow_{stance}_charge')
    extra(f'bow_{stance}_release',f'弓 · {label}放箭','bow','SPECIAL',f'bow_{stance}_release')
styles=json.loads((baseline/'styles.json').read_text(encoding='utf-8-sig'))
for style in styles:
    if style['id']=='rapier':
        style['leftCombo']=[{'skillId':id} for id in ('sword_left','rapier_downcut','rapier_crosscut','rapier_light_thrust')]
        style['techniques']={'riposteSkill':'','riposteWindowTicks':0,'repriseSkill':'','repriseWindowTicks':0}
    elif style['id']=='dual_blade':style['controls']['mode']='legacy_dual'
guard=dict(styles[0]['guard']);guard.update(arcDegrees=0,perfectGuardTicks=0,staminaCost=0,perfectGuardStaminaCost=0)
for stance,label in [('rapid','速射'),('snipe','狙击')]:
    styles.append(dict(id='bow_'+stance,label='弓 · '+label,weaponGroup='bow',enabled=True,leftCombo=[{'skillId':'bow_bash'}],
                       rightSkill='bow_'+stance+'_charge',guard=guard))
weapons=json.loads((baseline/'weapons.json').read_text(encoding='utf-8-sig'))
weapons['groups']['bow']={'mainItems':['minecraft:bow'],'offhandItems':[]}
for name,value in [('skills.json',skills),('styles.json',styles),('weapons.json',weapons)]:
    (defaults/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Authored legacy combat defaults:',len(skills),'skills;',len(styles),'stances')
