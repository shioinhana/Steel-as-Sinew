#!/usr/bin/env node
// Prevent a camera-space rig or an attack alias from changing the authored slash.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { bladePoint } from './fp_projection.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const assets = path.join(root, 'src/main/resources/assets/actioncombat/geckolib');
const read = name => JSON.parse(fs.readFileSync(path.join(assets, name), 'utf8'));
const thirdGeo = read('models/player_doublesword.geo.json')['minecraft:geometry'][0];
const firstGeo = read('models/fp_doublesword.geo.json')['minecraft:geometry'][0];
const third = read('animations/third_person_doublesword.animation.json').animations;
const firstFile = read('animations/fp_doublesword.animation.json');
const first = firstFile.animations;

const bones = {
  root: 'root', lower_body: 'lower_body', upper_body: 'upper_body',
  upper_arm_r: 'right_arm', forearm_r: 'right_sleve_bottom',
  hand_r: 'hand_r', hand_item_r: 'right_arm_item',
  upper_arm_l: 'left_arm', forearm_l: 'left_arm_bottom',
  hand_l: 'hand_l', hand_item_l: 'left_arm_item',
};
const attacks = {
  attack1: 'attack_1', attack2: 'attack2', attack3: 'attack3', attack4: 'attack4',
  attack_dual: 'attack_dual',
};
const geometryByName = geometry => new Map(geometry.bones.map(bone => [bone.name, bone]));
const thirdBones = geometryByName(thirdGeo);
const firstBones = geometryByName(firstGeo);

for (const [sourceName, name] of Object.entries(bones)) {
  const sourceBone = thirdBones.get(sourceName);
  const bone = firstBones.get(name);
  assert.ok(bone, `FP bone missing: ${name}`);
  assert.deepEqual(bone.pivot, sourceBone.pivot, `${name}: same 3P joint pivot`);
  assert.deepEqual(bone.rotation, sourceBone.rotation, `${name}: no extra rest rotation`);
  assert.equal(bone.parent, bones[sourceBone.parent], `${name}: same parent hierarchy`);
}
for (const name of ['root', 'lower_body', 'upper_body', 'right_arm_item', 'left_arm_item']) {
  assert.equal(firstBones.get(name).cubes, undefined, `${name}: no body or fake weapon mesh`);
}
assert.equal(firstBones.has('head'), false, 'head must not occlude the first-person camera');

for (const [fpName, sourceName] of Object.entries(firstFile.actioncombat_fp_sources.clips)) {
  assert.ok(third[sourceName], `${fpName}: source clip exists`);
  const expectedBones = Object.fromEntries(Object.entries(third[sourceName].bones)
    .filter(([name]) => Object.hasOwn(bones, name))
    .map(([name, channels]) => [bones[name], channels]));
  assert.deepEqual(first[fpName].bones, expectedBones, `${fpName}: every 3P channel and keyframe copied exactly`);
  assert.equal(first[fpName].animation_length, third[sourceName].animation_length, `${fpName}: unchanged duration`);
}
for (const [fpName, sourceName] of Object.entries(attacks)) {
  for (const suffix of ['', '_upper']) {
    assert.equal(firstFile.actioncombat_fp_sources.clips[fpName + suffix], sourceName + suffix,
      `${fpName + suffix}: authoritative hand and direction`);
  }
  assert.equal(first[fpName].loop, false, `${fpName}: one-shot`);
  assert.equal(first[fpName + '_upper'].bones.root, undefined, `${fpName}: overlay leaves root to locomotion`);
  assert.equal(first[fpName + '_upper'].bones.lower_body, undefined, `${fpName}: overlay leaves hips to locomotion`);
}
for (const clip of ['static_idle', 'inspect', 'draw', 'put_away', 'defend_upper', 'aiming_upper',
  'defend_end', 'crouch_idle', 'jump_start', 'jump', 'fall', 'hurt_upper',
  'walk_hold', 'walk_hold_left', 'walk_hold_right', 'walk_hold_back',
  'run_hold', 'run_hold_left', 'run_hold_right', 'run_hold_back']) {
  assert.equal(firstFile.actioncombat_fp_sources.clips[clip], clip, `${clip}: shared state clip`);
}

// Sample composed gait + upper-body attacks. This catches a lost torso ancestor
// even when the copied shoulder/wrist rotations themselves look plausible.
let samples = 0;
for (const stance of ['static_idle', 'walk_hold_left', 'run_hold', 'crouch_idle']) {
  for (const [fpName, sourceName] of Object.entries(attacks)) {
    const thirdClip = { bones: { ...third[stance].bones, ...third[sourceName + '_upper'].bones } };
    const firstClip = { bones: { ...first[stance].bones, ...first[fpName + '_upper'].bones } };
    for (let time = 0; time <= third[sourceName].animation_length; time += 1 / 60) {
      for (const [sourceHand, fpHand] of [['hand_item_l', 'left_arm_item'], ['hand_item_r', 'right_arm_item']]) {
        const expected = bladePoint(thirdBones, thirdClip, sourceHand, time);
        const actual = bladePoint(firstBones, firstClip, fpHand, time);
        assert.ok(Math.hypot(...actual.map((v, axis) => v - expected[axis])) < 1e-8,
          `${stance}/${fpName}/${fpHand}@${time}: same composed blade path`);
        samples++;
      }
    }
  }
}

console.log(`check_fp_third_person PASSED: ${Object.keys(first).length} clips, exact joints/keyframes, ${samples} composed blade samples`);

if (process.argv.includes('--framing')) {
  const offsetZ = Number(process.argv.find(arg => arg.startsWith('--offset-z='))?.split('=')[1] ?? -0.25);
  const aspect = Number(process.argv.find(arg => arg.startsWith('--aspect='))?.split('=')[1] ?? 16 / 9);
  const cameraPoint = (clip, hand, time, length) => bladePoint(firstBones, clip, hand, time, length)
    .map((v, axis) => v / 16 + [0, -1.62, offsetZ][axis]);
  for (const hand of ['left_arm_item', 'right_arm_item']) {
    const positions = [0, 16].map(length => cameraPoint(first.static_idle, hand, 0, length)
      .map(v => Number(v.toFixed(3))));
    console.log(`${hand} camera-space idle hilt/tip (no model yaw): ${JSON.stringify(positions)}`);
  }
  function segmentVisible(start, end) {
    const tanY = Math.tan(70 * Math.PI / 360);
    const tanX = tanY * aspect;
    const planes = [[0, 0, 1, 0.05], [1, 0, tanX, 0], [-1, 0, tanX, 0],
      [0, 1, tanY, 0], [0, -1, tanY, 0]];
    let low = 0, high = 1;
    for (const plane of planes) {
      const at = point => plane[3] + point.reduce((sum, v, axis) => sum + plane[axis] * v, 0);
      const a = at(start), delta = at(end) - a;
      if (Math.abs(delta) < 1e-10) {
        if (a > 0) return false;
      } else if (delta > 0) high = Math.min(high, -a / delta);
      else low = Math.max(low, -a / delta);
    }
    return low <= high;
  }
  for (const [name, hand] of [['attack1', 'left_arm_item'], ['attack3', 'right_arm_item'],
    ['attack2', 'left_arm_item'], ['attack4', 'right_arm_item']]) {
    const clip = { bones: { ...first.static_idle.bones, ...first[name + '_upper'].bones } };
    const points = [];
    let visible = 0;
    for (let frame = 0; frame <= 30; frame++) {
      const time = frame / 60;
      const hilt = cameraPoint(clip, hand, time, 0);
      const tip = cameraPoint(clip, hand, time, 16);
      if (segmentVisible(hilt, tip)) visible++;
      points.push({ hilt, tip });
    }
    const bounds = key => [0, 1, 2].map(axis => [
      Math.min(...points.map(point => point[key][axis])),
      Math.max(...points.map(point => point[key][axis])),
    ].map(v => Number(v.toFixed(3))));
    console.log(`${name} first 0.5s hilt XYZ bounds ${JSON.stringify(bounds('hilt'))}, tip ${JSON.stringify(bounds('tip'))}, visible blade ${visible}/31 frames at 70deg/aspect=${aspect}`);
  }
}
