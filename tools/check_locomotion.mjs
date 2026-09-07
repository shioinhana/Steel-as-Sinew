#!/usr/bin/env node
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const lower = ['root', 'lower_body', 'leg_r', 'leg_l', 'lower_leg_r', 'lower_leg_l', 'foot_r', 'foot_l'];
const directions = ['', '_left', '_right', '_back'];

function vectors(clip, bone, channel = 'rotation') {
  const keys = clip.bones?.[bone]?.[channel];
  if (Array.isArray(keys)) return [keys];
  return Object.entries(keys ?? {}).sort(([a], [b]) => Number(a) - Number(b))
    .map(([, value]) => Array.isArray(value) ? value : value.post ?? value.pre ?? value.vector);
}

function span(values, axis) {
  return Math.max(...values.map(value => value[axis])) - Math.min(...values.map(value => value[axis]));
}

let checked = 0;
for (const fileId of ['third_person_singlesword', 'third_person_doublesword']) {
  const file = path.join(root, 'src/main/resources/assets/actioncombat/geckolib/animations', `${fileId}.animation.json`);
  const animations = JSON.parse(fs.readFileSync(file, 'utf8')).animations;
  const names = ['jump', 'fall', ...['walk_hold', 'run_hold', 'crouch_walk'].flatMap(base =>
    directions.map(direction => base + direction))];
  for (const name of names) {
    const clip = animations[name];
    const label = `${fileId}/${name}`;
    assert(clip, `${label}: missing locomotion clip`);
    assert.equal(clip.loop, true, `${label}: motion must keep cycling under an action`);
    for (const bone of ['leg_r', 'leg_l', 'lower_leg_r', 'lower_leg_l']) {
      const values = vectors(clip, bone);
      assert(values.length > 2 && values.every(value => value?.length === 3 && value.every(Number.isFinite)),
        `${label}/${bone}: missing numeric leg animation`);
      assert(span(values, 0) > 3 || span(values, 2) > 8, `${label}/${bone}: frozen lower body`);
      assert.deepEqual(values[0], values.at(-1), `${label}/${bone}: discontinuous cycle`);
    }
    if (name.includes('_left') || name.includes('_right')) {
      for (const bone of ['leg_r', 'leg_l']) {
        assert(span(vectors(clip, bone), 2) > 16, `${label}/${bone}: side steps must move laterally`);
      }
      assert(vectors(clip, 'root').every(value => value[1] === 0), `${label}: root yaw turns the whole face`);
      const expectedSign = name.endsWith('_left') ? 1 : -1;
      assert(vectors(clip, 'lower_body').every(value => value[1] * expectedSign >= 10),
        `${label}: missing directional hip stance`);
    }
    checked++;
  }

  for (const base of ['walk_hold', 'run_hold', 'crouch_walk']) {
    const signatures = directions.map(direction => JSON.stringify(
      lower.map(bone => animations[base + direction].bones[bone])));
    assert.equal(new Set(signatures).size, 4, `${fileId}/${base}: directions reuse the same lower-body motion`);
  }
  const standingHeight = vectors(animations.walk_hold, 'root', 'position')[0][1];
  for (const name of ['crouch_idle', ...directions.map(direction => `crouch_walk${direction}`)]) {
    assert(vectors(animations[name], 'root', 'position').every(value => value[1] < standingHeight - 2),
      `${fileId}/${name}: crouch loses its lowered stance`);
  }
  for (const [name, clip] of Object.entries(animations)) {
    if (!name.endsWith('_upper') && name !== 'defend_end') continue;
    assert(!lower.some(bone => bone in (clip.bones ?? {})), `${fileId}/${name}: action overwrites the lower layer`);
  }
}
console.log(`locomotion: ${checked} moving clips, directional steps, crouch clearance and upper-layer isolation passed`);
