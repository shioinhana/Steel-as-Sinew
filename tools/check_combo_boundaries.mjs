#!/usr/bin/env node
// 双刀连击 must connect: every dual attack clip opens and settles on the idle stance,
// so consecutive swings never jump between two different opening poses. Each clip used
// to end on its own start pose, which put a 70-135 degree pop between swings.
// Run: node tools/check_combo_boundaries.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ANIM_DIR = path.join(ROOT, 'src/main/resources/assets/actioncombat/geckolib/animations');

/** Pose values are copied, not interpolated, so the only slack is float printing. */
const TOLERANCE = 0.05;

const FILES = [
  { file: 'third_person_doublesword.animation.json', clips: ['attack_1', 'attack2', 'attack3', 'attack4', 'attack_dual'] },
  { file: 'fp_doublesword.animation.json', clips: ['attack1', 'attack2', 'attack3', 'attack4', 'attack_dual'] },
  { file: 'ysm_player_dual.animation.json', clips: ['attack_1', 'attack2', 'attack3', 'attack4', 'attack_dual'] },
];

function fail(message) {
  console.error(`check_combo_boundaries: ${message}`);
  process.exit(1);
}

function keysOf(channel) {
  if (!channel || Array.isArray(channel) || typeof channel !== 'object') return [];
  return Object.keys(channel)
    .filter(k => Number.isFinite(parseFloat(k)))
    .map(k => ({ key: k, time: parseFloat(k) }))
    .sort((a, b) => a.time - b.time);
}

function vecAt(channel, t) {
  if (!channel || Array.isArray(channel) || typeof channel !== 'object') return null;
  const keys = keysOf(channel);
  if (!keys.length) return null;
  let best = keys[0];
  for (const entry of keys) {
    if (Math.abs(entry.time - t) <= Math.abs(best.time - t)) best = entry;
  }
  const node = channel[best.key];
  const vec = Array.isArray(node) ? node : node?.post ?? node?.pre ?? node?.vector ?? null;
  return Array.isArray(vec) ? vec : null;
}

function poseOf(clip, t) {
  const out = new Map();
  for (const [bone, channels] of Object.entries(clip?.bones ?? {})) {
    if (!channels || typeof channels !== 'object') continue;
    for (const channel of ['rotation', 'position', 'scale']) {
      const vec = vecAt(channels[channel], t);
      if (vec) out.set(`${bone}/${channel}`, vec);
    }
  }
  return out;
}

function distance(a, b) {
  if (!a || !b) return 0;
  let worst = 0;
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    worst = Math.max(worst, Math.abs((a[i] ?? 0) - (b[i] ?? 0)));
  }
  return worst;
}

function compare(name, left, right, leftPose, rightPose) {
  let worst = { value: 0, channel: null };
  for (const [channel, vec] of leftPose) {
    const other = rightPose.get(channel);
    if (!other) continue;
    const delta = distance(vec, other);
    if (delta > worst.value) worst = { value: delta, channel };
  }
  if (worst.value > TOLERANCE) {
    fail(`${name}: ${left} and ${right} differ by ${worst.value.toFixed(2)} on ${worst.channel}`
      + ` (tolerance ${TOLERANCE})`);
  }
  return worst.value;
}

let checked = 0;
for (const spec of FILES) {
  const file = path.join(ANIM_DIR, spec.file);
  if (!fs.existsSync(file)) fail(`${spec.file} is missing`);
  const animations = JSON.parse(fs.readFileSync(file, 'utf8')).animations ?? {};
  const idle = animations.static_idle ?? animations.idle;
  if (!idle) fail(`${spec.file}: no static_idle clip to settle on`);
  const idlePose = poseOf(idle, 0);

  for (const clip of spec.clips) {
    if (!animations[clip]) fail(`${spec.file}: missing ${clip}`);
    const end = animations[clip].animation_length;
    const opening = poseOf(animations[clip], 0);
    const closing = poseOf(animations[clip], end);
    compare(spec.file, `${clip}@0`, `${clip}@${end}`, opening, closing);
    compare(spec.file, `${clip}@0`, 'idle@0', opening, idlePose);

    const upper = `${clip}_upper`;
    if (!animations[upper]) fail(`${spec.file}: missing ${upper}`);
    const upperEnd = animations[upper].animation_length;
    const upperOpening = poseOf(animations[upper], 0);
    const upperClosing = poseOf(animations[upper], upperEnd);
    compare(spec.file, `${upper}@0`, `${upper}@${upperEnd}`, upperOpening, upperClosing);
    compare(spec.file, `${upper}@0`, `${clip}@0`, upperOpening, opening);
    checked += 4;
  }
}

console.log(`combo boundaries: ${checked} dual clip edges settle on the idle stance`);
