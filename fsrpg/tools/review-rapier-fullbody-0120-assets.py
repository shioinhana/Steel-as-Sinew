"""Independent exported-FK audit; no authoring solver is imported or invoked.

The review imports only the previous independent audit's file reader and matrix
utilities. It checks full-body contacts against the documented server travel,
waist closure, arms, feet, official grip, native sampling and clip handoffs.
This is asset evidence, not a claim of real Minecraft visual verification.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import math
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
load = importlib.util.spec_from_file_location('old_audit', ROOT/'tools/review-rapier-0100-assets.py')
fk = importlib.util.module_from_spec(load);load.loader.exec_module(fk)
TAG = 'rapier_fullbody:0120'


def smooth(t):
    return np.clip(t, 0, 1)**2*(3-2*np.clip(t, 0, 1))


def travel(t):
    if t < .26:
        return .2*smooth(t/.26)
    if t < .86:
        return .2+.65*smooth((t-.26)/.60)
    return .85+.15*smooth((t-.86)/.14)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT/'verification/rapier-fullbody-0120-assets')
    parser.add_argument('--plots', action='store_true')
    args = parser.parse_args();args.output.mkdir(parents=True, exist_ok=True)
    failures, models, plots = [], [], []
    checks = 0
    def check(ok, text):
        nonlocal checks
        checks += 1
        if not ok and len(failures) < 200:
            failures.append(text)
    manifest = json.loads((ROOT/'docs/rapier-fullbody-0.12.0-manifest.json').read_text(encoding='utf8'))
    for fp in (False, True):
        path = ROOT/'src/main/resources/assets/fsrpg/fsvoxel/models'/('rapier_fullbody_fp.fsvoxel' if fp else 'rapier_fullbody.fsvoxel')
        source = path.with_name('legacy_rapier_fp.fsvoxel' if fp else 'legacy_rapier.fsvoxel')
        parts, clips = fk.read(path)
        source_parts, _ = fk.read(source)
        check(parts == source_parts, str(path.name)+': rig geometry/locators changed')
        source_lines = [s for s in source.read_text(encoding='utf8').splitlines() if not s.startswith(('action\t', 'name\t', 'summary\t'))]
        lines = [s for s in path.read_text(encoding='utf8').splitlines() if not s.startswith(('action\t', 'name\t', 'summary\t'))]
        check(source_lines == lines, str(path.name)+': non-animation geometry or official carrier changed')
        check(path.read_bytes() == (ROOT/'config/fsrpg/models'/path.name).read_bytes(), path.name+': template mismatch')
        m = next(x for x in manifest['assets'] if x['source'] == source.name)
        check(hashlib.sha256(source.read_bytes()).hexdigest() == m['sourceSha256'], source.name+': original source changed')
        rows = []
        ids = [f'legacy_rapier_attack{i}_upper' for i in range(1, 6)]+['legacy_rapier_rapier_thrust_hit_upper', 'rapier_riposte', 'rapier_reprise', 'rapier_parry_return', 'rapier_parry', 'rapier_guard', 'rapier_guard_heavy', 'rapier_charge', 'rapier_full_charge']
        for id in ids:
            clip = clips[id]
            check(TAG in clip['tags'] and clip['layer'] == 'full', id+': not whole body')
            times = [k['time'] for k in clip['tracks']['right_arm']]
            check(max(np.diff(times)) <= 1/120+1e-8, id+': sampling under 120 Hz')
            check(clip['mode'] == 'delta' and clip['rotation_mode'] == 'quaternion', id+': incompatible interpolation')
            distance = float(next(x.split(':', 1)[1] for x in clip['tags'] if x.startswith('motion_distance:')))
            previous, maxstep, min_knee, max_knee, max_wrist, max_drop = None, 0., 180., 0., 0., 0.
            frames, support_errors = [], []
            for at in times:
                local, world = fk.frame(parts, clip, at)
                frames.append(world)
                normalized = at/clip['duration']
                hand = world['right_item' if fp else 'right_hand'][:3, 3]
                shoulder, elbow = world['right_arm'][:3, 3], world['right_forearm'][:3, 3]
                blade = world['right_item'][:3, :3]@fk.official_axis(fp)
                check(abs(np.linalg.norm(elbow-shoulder)-37.5) < 1e-5, id+': upper arm disconnected')
                check(abs(np.linalg.norm(hand-elbow)-37.5) < 1e-5, id+': forearm disconnected')
                check(10 <= 180-fk.bend(shoulder-elbow, hand-elbow) <= 135, id+': elbow range')
                flex = fk.bend(hand-elbow, blade);max_wrist = max(max_wrist, flex)
                check(flex <= 34.0001, id+': wrist exceeds bounded official grip')
                if not fp:
                    check(np.linalg.norm(world['right_item'][:3, 3]-hand) < 1e-6, id+': official item leaves palm')
                    waist = fk.point(world['body'], [0, -31.25, 0])
                    hips = [world[side+'_leg'][:3, 3] for side in ('right', 'left')]
                    check(np.linalg.norm((hips[0]+hips[1])/2-waist) < 2e-5, id+': waist/hip split')
                    check(abs(np.linalg.norm(hips[0]-hips[1])-25) < 2e-5, id+': pelvis width changes')
                    check(np.max(np.abs(world['root'][:3, 3])) < 1e-7, id+': root duplicates entity movement')
                    max_drop = max(max_drop, 75-waist[1])
                    for side in ('right', 'left'):
                        hip, knee, ankle = [world[side+bone][:3, 3] for bone in ('_leg', '_foreleg', '_foot')]
                        check(abs(np.linalg.norm(knee-hip)-37.5) < 1e-5, id+': thigh length changes')
                        check(abs(np.linalg.norm(ankle-knee)-25) < 1e-5, id+': shin length changes')
                        bend = 180-fk.bend(hip-knee, ankle-knee);min_knee = min(min_knee, bend);max_knee = max(max_knee, bend)
                        check(5 < bend < 145, id+': knee range '+str(bend))
                        up = world[side+'_foot'][:3, :3]@np.array([0, 1, 0])
                        check(np.linalg.norm(up-[0, 1, 0]) < 2e-6, id+': sole loses level support')
                        check(ankle[1] >= 12.5-2e-5, id+': foot below floor')
                        # At the explicit planted intervals the entity's travel
                        # exactly cancels local foot retraction, with no extra root.
                        index = ids.index(id) if id.startswith('legacy_rapier_attack') else 0
                        lead = (side == 'right') == (index % 2 == 0)
                        if distance > 0 and ((lead and .50 < normalized < .86) or (not lead and 0 < normalized < .50)):
                            expected = (-1 if side == 'right' else 1)*-14 + (distance*100*.85 if lead else 0)
                            actual = ankle[2]+distance*100*travel(normalized)
                            error = abs(expected-actual);support_errors.append(error)
                            check(error < 2e-4, id+': planted foot drift '+str(error))
                    inverse = np.linalg.inv(world['body'])
                    for side in ('right', 'left'):
                        s, e, w = [world[side+x][:3, 3] for x in ('_arm', '_forearm', '_hand')]
                        for label, a, b in [('upper', s+(e-s)*.22, e), ('forearm', e, w)]:
                            a, b = fk.point(inverse, a), fk.point(inverse, b)
                            check(not fk.intersects_box(a, b, np.array([-34.375, -53.125, -21.875]), np.array([34.375, 43.75, 21.875])), id+': '+side+' '+label+' intersects torso')
                            check(not fk.intersects_box(a, b, np.array([-34.375, 43.75, -34.375]), np.array([34.375, 103.125, 34.375])), id+': '+side+' '+label+' intersects head')
                    blade_tip = fk.point(world['right_item'], fk.official_axis(fp)*75)
                    a, b = fk.point(inverse, hand), fk.point(inverse, blade_tip)
                    check(not fk.intersects_box(a, b, np.array([-26, -44.75, -13.5]), np.array([26, 42.75, 13.5])), id+': official blade intersects torso')
                    check(not fk.intersects_box(a, b, np.array([-26, 43.75, -26]), np.array([26, 94.75, 26])), id+': official blade intersects head')
                if previous is not None:
                    for bone in ('right_arm', 'right_forearm', 'right_item')+(('right_leg', 'right_foreleg', 'left_leg', 'left_foreleg') if not fp else ()):
                        rot = previous[bone][:3, :3].T@world[bone][:3, :3]
                        step = math.degrees(math.acos(float(np.clip((np.trace(rot)-1)/2, -1, 1))));maxstep = max(maxstep, step)
                        check(step < 24, id+': source frame rotation jump '+bone+' '+str(step))
                previous = world
            rows.append(dict(action=id, frames=len(times), maxJointStepDeg=maxstep, maxWristDeg=max_wrist, kneeRangeDeg=[min_knee, max_knee] if not fp else None, maxPelvisDropCm=max_drop, plantedFootDriftCm=max(support_errors, default=0)))
            if not fp and id in ids[:5]+['rapier_parry']:
                plots.append((id, frames, times, clip, distance))
        for stage in range(1, 6):
            a = clips[f'legacy_rapier_attack{stage}_upper'];b = clips[f'legacy_rapier_attack{stage%5+1}_upper']
            aw, _ = fk.frame(parts, a, round(a['duration']*.86, 9));bw, _ = fk.frame(parts, b, 0)
            for bone in a['tracks']:
                check(np.max(np.abs(aw[bone]-bw[bone])) < 2e-5, f'{stage}: queued handoff jumps {bone}')
            idle = clips['legacy_rapier_static_idle']
            end, _ = fk.frame(parts, a, a['duration']);rest, _ = fk.frame(parts, idle, 0)
            for bone in a['tracks']:
                check(np.max(np.abs(end[bone]-rest[bone])) < 2e-5, f'{stage}: independent recovery does not return to READY: {bone}')
        models.append(dict(path=str(path), sha256=hashlib.sha256(path.read_bytes()).hexdigest(), clips=rows))
    if args.plots:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        fig, axes = plt.subplots(2, 6, figsize=(21, 9), constrained_layout=True)
        for column, (id, frames, times, clip, distance) in enumerate(plots):
            for row, (x, y) in enumerate([(2, 1), (0, 1)]):
                ax = axes[row, column]
                for phase, color in [(.26, '#39669d'), (.56, '#d96b3b'), (.86, '#3f8767')]:
                    world = frames[times.index(round(clip['duration']*phase, 9))]
                    displacement = np.array([0, 0, distance*100*travel(phase)])
                    for side in ('right', 'left'):
                        points = [world[side+s][:3, 3]+displacement for s in ('_leg', '_foreleg', '_foot')]
                        points = np.array(points);ax.plot(points[:, x], points[:, y], '-o', color=color, markersize=3, alpha=1 if side == 'right' else .6)
                    waist = fk.point(world['body'], [0, -31.25, 0])+displacement
                    head = world['head'][:3, 3]+displacement
                    ax.plot([waist[x], head[x]], [waist[y], head[y]], '-', color=color)
                    arm = np.array([world[b][:3, 3]+displacement for b in ('right_arm', 'right_forearm', 'right_hand')])
                    ax.plot(arm[:, x], arm[:, y], '-', color=color)
                ax.axhline(12.5, color='#777', linewidth=.5);ax.set_title(id.replace('legacy_rapier_', ''));ax.axis('equal');ax.grid(alpha=.2)
        fig.suptitle('0.12.0 exported whole-body skeletons: windup / contact / handoff; includes planned entity travel')
        fig.savefig(args.output/'whole-body-contact-sheets.png', dpi=145);plt.close(fig)
    report = dict(format='fsrpg.fullbody-asset-audit.v1', checks=checks, passed=not failures, failures=failures, models=models, boundary='Exported-asset FK only; not Minecraft runtime evidence')
    (args.output/'asset-audit.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf8')
    print(json.dumps(dict(checks=checks, passed=not failures, failures=failures[:20], output=str(args.output)), indent=2))
    raise SystemExit(bool(failures))


if __name__ == '__main__':
    main()
