"""Create separate 0.12.0 whole-body sword assets; never overwrite legacy inputs.

Upper limbs retain the independently checked 0.10.0 anatomical IK. Feet are
authored against the server's phase-relative travel, with a shared actual waist
and two-segment legs. The root contains no entity X/Z travel.
"""
from __future__ import annotations
from copy import deepcopy
from pathlib import Path
import hashlib
import importlib.util
import json
import math

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / 'src/main/resources/assets/fsrpg/fsvoxel/models'
loader = importlib.util.spec_from_file_location('rapier0100', ROOT / 'tools/rework-rapier-fencing-0100.py')
upper = importlib.util.module_from_spec(loader)
loader.loader.exec_module(upper)
add, sub, mul, dot, cross, unit = upper.add, upper.sub, upper.mul, upper.dot, upper.cross, upper.unit
mm, mv, transpose, xyz, euler, columns = upper.mm, upper.mv, upper.transpose, upper.xyz, upper.euler, upper.columns
TAG = 'rapier_fullbody:0120'
DISTANCES = [.45, .32, .40, .25, .85]
PHASES = [0, .14, .26, .56, .72, .86, 1]
EXTRA_PHASES = [.04, .50, .93]


def smooth(x):
    x = max(0., min(1., x))
    return x*x*(3-2*x)


def travel(t):
    if t <= .26:
        return .20*smooth(t/.26)
    if t <= .86:
        return .20+.65*smooth((t-.26)/.60)
    return .85+.15*smooth((t-.86)/.14)


def foot_progress(t, lead):
    """Return foot advance (entity distance fractions), lift cm, support flag."""
    start, end = (.04, .50) if lead else (.50, .86)
    if t < .86:
        u = (t-start)/(end-start)
        return .85*smooth(u), (8 if lead else 6)*math.sin(math.pi*max(0., min(1., u)))**2, not 0 < u < 1
    start, end = (.86, .93) if lead else (.93, 1.)
    u = (t-start)/(end-start)
    return .85+.15*smooth(u), 2*math.sin(math.pi*max(0., min(1., u)))**2, not 0 < u < 1


def leg(hip, ankle, pole):
    """Independent thigh/shin lengths from the unchanged official actor rig."""
    d = sub(ankle, hip)
    distance = math.sqrt(dot(d, d))
    assert 13 < distance < 62.499, ('unreachable leg', distance, hip, ankle)
    axis = unit(d)
    p = unit(sub(pole, mul(axis, dot(pole, axis))))
    along = (37.5**2-25**2+distance**2)/(2*distance)
    knee = add(add(hip, mul(axis, along)), mul(p, math.sqrt(max(0, 37.5**2-along**2))))
    thigh, shin = unit(sub(knee, hip)), unit(sub(ankle, knee))
    hinge = unit(cross(thigh, shin))  # positive knee flexion, unlike elbow
    a = columns(hinge, mul(thigh, -1), unit(cross(hinge, mul(thigh, -1))))
    b = columns(hinge, mul(shin, -1), unit(cross(hinge, mul(shin, -1))))
    return a, mm(transpose(a), b), b


def lower_pose(spec, t, distance, lead_right=True, stationary=False):
    body_world = mm(xyz([0, 65, 0]), xyz(spec['body']))
    pelvis_yaw = 32.5 + spec['body'][1]*.45
    pelvis = xyz([0, pelvis_yaw, 0])
    # Keep the anatomically checked shoulder/hand world orientations intact.
    body_local = mm(transpose(pelvis), body_world)
    waist_from_body = mv(body_world, [0, -31.25, 0])
    envelope = math.sin(math.pi*max(0, min(1, t/.86)))**2 if t < .86 and not stationary else 0
    hip_shift = [spec['body'][1]*.12, -7-3*envelope-max(0, -spec['body'][0]-.6)*1.2, 3*envelope]
    feet = {}
    for side, sign in [('right', -1), ('left', 1)]:
        progress, lift, support = foot_progress(t, (side == 'right') == lead_right)
        if stationary:
            progress, lift, support = 0, 0, True
        feet[side] = [sign*16, 12.5+lift, -sign*14+distance*100*(progress-travel(t))]
    # The actual vanilla waist is 31.25 cm below this imported body centre.
    waist0 = add(add([0, 106.25, 0], hip_shift), waist_from_body)
    # Lower the *whole waist*, never independently translate an upper-only layer.
    # Resolve reach before solving knees, with 0.5 cm of extension reserve.
    required_drop = 0
    for side, sign in [('right', -1), ('left', 1)]:
        hip = add(waist0, mv(pelvis, [sign*12.5, 0, 0]))
        f = feet[side]
        radius2 = (hip[0]-f[0])**2+(hip[2]-f[2])**2
        assert radius2 < 61.8**2, ('foot horizontal reach', side, t, distance, radius2)
        max_height = f[1]+math.sqrt(61.8**2-radius2)
        required_drop = max(required_drop, hip[1]-max_height)
    hip_shift[1] -= max(0, required_drop)
    waist = add(add([0, 106.25, 0], hip_shift), waist_from_body)
    out = {'root': ([0, 0, 0], [0, 0, 0]),
           'hip': (euler(pelvis), hip_shift),
           'body': (euler(body_local), [0, 0, 0])}
    for side, sign in [('right', -1), ('left', 1)]:
        h = add(waist, mv(pelvis, [sign*12.5, 0, 0]))
        a, b, shin = leg(h, feet[side], [0, 0, 1])
        foot_world = xyz([0, 12 if side == 'right' else -10, 0])
        out[side+'_leg'] = (euler(a), sub(h, [sign*12.5, 75, 0]))
        out[side+'_foreleg'] = (euler(b), [0, 0, 0])
        out[side+'_foot'] = (euler(mm(transpose(shin), foot_world)), [0, 0, 0])
    return out


def author(template, points, fp, phrase, duration, distance=0, index=0, stationary=False, loop='once'):
    clip = deepcopy(template)
    clip.update(duration=duration, transition=0, loop=loop, interpolation='linear', rotation_mode='quaternion', layer='full')
    clip['tags'] = [tag for tag in clip.get('tags', []) if not tag.startswith(('rapier_rework:', 'rapier_recoil:', 'rapier_fencing:', 'rapier_phrase:', 'rapier_guard:', 'rapier_charge:', 'rapier_fullbody:', 'source_hz:', 'motion_distance:'))]
    clip['tags'] += [TAG, 'source_hz:120', 'rapier_fencing:0100', 'rapier_phrase:'+phrase, 'motion_distance:'+str(distance)]
    clip['tracks'] = {}
    count = math.ceil(duration*120)
    previous = {}
    for t in sorted(set([n/count for n in range(count+1)]+PHASES+EXTRA_PHASES)):
        spec = upper.spec_at(points, t)
        pose = {bone: (angles, [0, 0, 0]) for bone, angles in upper.pose_at(spec, fp).items()}
        if not fp:
            pose.update(lower_pose(spec, t, distance, index % 2 == 0, stationary))
        for bone, (angles, position) in pose.items():
            if bone in previous:
                angles = [v+360*round((previous[bone][i]-v)/360) for i, v in enumerate(angles)]
            previous[bone] = angles
            clip['tracks'].setdefault(bone, []).append(dict(time=round(duration*t, 9), rotation=[round(v, 6) for v in angles], position=[round(v, 6) for v in position], scale=[1, 1, 1]))
    return clip


def main():
    results = []
    for fp in (False, True):
        old_name = 'legacy_rapier_fp.fsvoxel' if fp else 'legacy_rapier.fsvoxel'
        name = 'rapier_fullbody_fp.fsvoxel' if fp else 'rapier_fullbody.fsvoxel'
        baseline = (MODELS/old_name).read_bytes()
        lines = baseline.decode('utf8').splitlines()
        clips = {a['id']: a for line in lines if line.startswith('action\t') for a in [json.loads(line.split('\t', 1)[1])]}
        authored = {}
        for i in range(5):
            for id in (f'legacy_rapier_attack{i+1}', f'legacy_rapier_attack{i+1}_upper', f'rapier_attack{i+1}'):
                authored[id] = author(clips[id], upper.stages(i), fp, upper.NAMES[i], .60 if i == 4 else .45, DISTANCES[i], i)
        for id, clip in clips.items():
            if id in {'legacy_rapier_static_idle', 'legacy_rapier_idle_upper', 'rapier_idle', 'rapier_stance'}:
                idle = author(clip, [upper.GUARD]*7, fp, 'ready', clip['duration'], stationary=True, loop='loop')
                idle['tags'] = [t for t in idle['tags'] if t != TAG and not t.startswith(('rapier_fencing:', 'rapier_phrase:'))]+['rapier_guard:0100']
                authored[id] = idle
            if 'rapier_thrust_hit' in id or 'rapier_thrust_miss' in id:
                authored[id] = author(clip, upper.branch_stages('charged-thrust'), fp, 'charged-thrust', .55, .65)
            if id in {'rapier_counter', 'legacy_rapier_attack_dual', 'legacy_rapier_attack_dual_upper'}:
                authored[id] = author(clip, upper.branch_stages('riposte'), fp, 'riposte', .65, .35)
        template = deepcopy(clips['legacy_rapier_attack1_upper'])
        for id, duration, distance, phrase in [('rapier_riposte', .65, .35, 'riposte'), ('rapier_reprise', .90, .40, 'reprise'), ('rapier_parry_return', .55, .55, 'riposte')]:
            own = deepcopy(clips.get(id, template));own['id'] = id;own['name'] = id
            authored[id] = author(own, upper.branch_stages(phrase), fp, phrase, duration, distance)
        # Held guard and charge have one coherent body, never an unrelated gait.
        for id in ('rapier_charge', 'rapier_full_charge', 'rapier_guard', 'rapier_guard_heavy'):
            if id == 'rapier_charge':
                points = [upper.blend(upper.GUARD, upper.CHAMBER, smooth(t)) for t in PHASES]
            elif id == 'rapier_full_charge':
                points = [upper.CHAMBER]*7
            else:
                points = [upper.GUARD]*7
            authored[id] = author(clips[id], points, fp, id, .8 if 'charge' in id else .5, stationary=True, loop='once' if id == 'rapier_charge' else 'loop')
            # Existing charge clock must remain distinguishable from an attack.
            if 'charge' in id:
                authored[id]['tags'] = [t for t in authored[id]['tags'] if not t.startswith('rapier_fencing:')]
                authored[id]['tags'].append('rapier_charge:0100')
        parry = upper.state([-30, 10, 82], -13, 15, [-1, 4, .5], -22)
        absorb = upper.state([-25, 7, 78], -9, 12, [-2.4, 2, .3], -16)
        points = [upper.GUARD, upper.blend(upper.GUARD, parry, .75), parry, absorb, upper.blend(absorb, upper.GUARD, .55), upper.GUARD, upper.GUARD]
        own = deepcopy(template);own['id'] = 'rapier_parry';own['name'] = 'Rapier full-body parry'
        authored['rapier_parry'] = author(own, points, fp, 'parry', .8, stationary=True)
        output, written = [], set()
        for line in lines:
            if line.startswith('summary\t'):
                line = 'summary\t'+json.dumps(dict(kind='model', purpose='rapier_fullbody_0120', status='authored_candidate'), separators=(',', ':'))
            elif line.startswith('name\t'):
                line = 'name\t'+json.dumps('FSRPG 0.12.0 whole-body sword '+('FP' if fp else 'TP')+' / 120 Hz')
            elif line.startswith('action\t'):
                id = json.loads(line.split('\t', 1)[1])['id']
                if id in authored:
                    line = 'action\t'+json.dumps(authored[id], ensure_ascii=False, separators=(',', ':'));written.add(id)
            output.append(line)
        for id in sorted(set(authored)-written):
            output.append('action\t'+json.dumps(authored[id], ensure_ascii=False, separators=(',', ':')))
        encoded = ('\n'.join(output)+'\n').encode('utf8')
        (MODELS/name).write_bytes(encoded)
        (ROOT/'config/fsrpg/models'/name).write_bytes(encoded)
        assert (MODELS/old_name).read_bytes() == baseline
        results.append(dict(path=(MODELS/name).relative_to(ROOT).as_posix(), source=old_name, sourceSha256=hashlib.sha256(baseline).hexdigest(), sha256=hashlib.sha256(encoded).hexdigest(), authoredActions=sorted(authored)))
    manifest = dict(format='fsrpg.rapier-fullbody-assets.v1', version='0.12.0', samplingHz=120, tag=TAG,
                    sourcePhase=dict(windupEnd=.26, contact=.56, activeEnd=.86), entityTravel=[0, .20, .85, 1],
                    rootTranslationXZ=False, bodyCentreCm=43.75, visibleWaistLocal=[0, -31.25, 0],
                    legSegmentsCm=[37.5, 25], ankleGroundCm=12.5, geometryUnchanged=True, officialItemGripUnchanged=True, assets=results)
    (ROOT/'docs/rapier-fullbody-0.12.0-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf8')
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
