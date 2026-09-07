#!/usr/bin/env node
// Builds attack_dual: both arms slash together (left-hand 左砍 + right-hand 右砍)
// on a shared timeline, with the torso averaged so the body faces the cut instead
// of twisting with only one side. Used after a parry when both buttons are pressed.
// Run: node tools/generate_dual_slash.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ANIM_DIR = path.join(ROOT, 'src/main/resources/assets/actioncombat/geckolib/animations');

const LEFT_3P = new Set(['upper_arm_l', 'forearm_l', 'hand_l', 'hand_item_l']);
const RIGHT_3P = new Set(['upper_arm_r', 'forearm_r', 'hand_r', 'hand_item_r']);
const BODY_3P = new Set(['root', 'lower_body', 'upper_body', 'head']);
const LEFT_FP_SINGLE = new Set(['left']);
const RIGHT_FP_SINGLE = new Set(['right', 'elucidator']);
const BODY_FP_SINGLE = new Set(['root']);

const FILES = [
  { file: 'third_person_singlesword.animation.json', left: 'attack1', right: 'attack2', leftBones: LEFT_3P, rightBones: RIGHT_3P, bodyBones: BODY_3P },
  { file: 'third_person_doublesword.animation.json', left: 'attack_1', right: 'attack3', leftBones: LEFT_3P, rightBones: RIGHT_3P, bodyBones: BODY_3P },
  { file: 'fp_singlesword.animation.json', left: 'attack1', right: 'attack2', leftBones: LEFT_FP_SINGLE, rightBones: RIGHT_FP_SINGLE, bodyBones: BODY_FP_SINGLE },
  // Dual FP is copied from the final 3P result by generate_fp_swings.mjs.
];

function round(v, digits = 5) {
  const p = 10 ** digits;
  return Math.round(v * p) / p;
}

function timeKey(seconds) {
  const v = round(seconds, 4);
  return Number.isInteger(v) ? v.toFixed(1) : String(v);
}

function isTimeKey(k) {
  return Number.isFinite(parseFloat(k));
}

function vecOf(node) {
  if (Array.isArray(node)) return node;
  if (node && typeof node === 'object') return node.post ?? node.pre ?? node.vector ?? null;
  return null;
}

function timesOf(channel) {
  if (!channel || Array.isArray(channel) || typeof channel !== 'object') return [];
  return Object.keys(channel).filter(isTimeKey).map(parseFloat).sort((a, b) => a - b);
}

function sampleChannel(channel, t) {
  if (!channel) return null;
  if (Array.isArray(channel)) return channel;
  const times = timesOf(channel);
  if (!times.length) return null;
  const at = (time) => {
    const key = Object.keys(channel).find(k => isTimeKey(k) && Math.abs(parseFloat(k) - time) < 1e-6)
      ?? String(time);
    return vecOf(channel[key] ?? channel[timeKey(time)]);
  };
  if (t <= times[0]) return at(times[0]);
  if (t >= times[times.length - 1]) return at(times[times.length - 1]);
  let i = 0;
  while (i + 1 < times.length && times[i + 1] < t) i++;
  const a = at(times[i]);
  const b = at(times[i + 1]);
  if (!a || !b) return a ?? b;
  const span = times[i + 1] - times[i];
  const u = span <= 1e-8 ? 0 : (t - times[i]) / span;
  return a.map((n, idx) => round((Number(n) || 0) + ((Number(b[idx]) || 0) - (Number(n) || 0)) * u, 4));
}

function lerpVec(a, b, t) {
  const n = Math.max(a?.length ?? 0, b?.length ?? 0, 3);
  const out = [];
  for (let i = 0; i < n; i++) {
    const av = Number(a?.[i]) || 0;
    const bv = Number(b?.[i]) || 0;
    out.push(round(av + (bv - av) * t, 4));
  }
  return out;
}

function remapChannel(channel, oldLen, newLen) {
  if (!channel) return channel;
  if (Array.isArray(channel) || oldLen <= 1e-8) return channel;
  const out = {};
  for (const [key, value] of Object.entries(channel)) {
    if (!isTimeKey(key)) {
      out[key] = value;
      continue;
    }
    const nt = round((parseFloat(key) / oldLen) * newLen, 4);
    out[timeKey(nt)] = value;
  }
  return out;
}

function remapClip(clip, newLen) {
  const oldLen = clip.animation_length || newLen;
  const bones = {};
  for (const [name, channels] of Object.entries(clip.bones ?? {})) {
    const next = { ...channels };
    if (channels.rotation) next.rotation = remapChannel(channels.rotation, oldLen, newLen);
    if (channels.position) next.position = remapChannel(channels.position, oldLen, newLen);
    if (channels.scale) next.scale = remapChannel(channels.scale, oldLen, newLen);
    bones[name] = next;
  }
  return { loop: false, animation_length: round(newLen, 5), bones };
}

function unionTimes(leftCh, rightCh, length) {
  const set = new Set([0, length]);
  for (const t of timesOf(leftCh)) set.add(t);
  for (const t of timesOf(rightCh)) set.add(t);
  return [...set].filter(t => t >= -1e-6 && t <= length + 1e-6).sort((a, b) => a - b);
}

function averageChannel(leftCh, rightCh, length) {
  if (!leftCh && !rightCh) return undefined;
  if (Array.isArray(leftCh) || Array.isArray(rightCh)) {
    const a = Array.isArray(leftCh) ? leftCh : sampleChannel(leftCh, 0);
    const b = Array.isArray(rightCh) ? rightCh : sampleChannel(rightCh, 0);
    if (!a) return rightCh;
    if (!b) return leftCh;
    return lerpVec(a, b, 0.5);
  }
  const out = {};
  for (const t of unionTimes(leftCh, rightCh, length)) {
    const a = sampleChannel(leftCh, t);
    const b = sampleChannel(rightCh, t);
    const vec = a && b ? lerpVec(a, b, 0.5) : (a ?? b);
    if (!vec) continue;
    out[timeKey(t)] = { post: vec, lerp_mode: 'catmullrom' };
  }
  return out;
}

function mix(leftClip, rightClip, leftBones, rightBones, bodyBones) {
  const duration = Math.min(leftClip.animation_length, rightClip.animation_length);
  const left = remapClip(leftClip, duration);
  const right = remapClip(rightClip, duration);
  const bones = {};

  for (const [name, channels] of Object.entries(left.bones ?? {})) {
    if (!rightBones.has(name) && !bodyBones.has(name)) bones[name] = channels;
  }
  for (const [name, channels] of Object.entries(right.bones ?? {})) {
    if (rightBones.has(name)) bones[name] = channels;
  }
  for (const name of bodyBones) {
    const lb = left.bones?.[name];
    const rb = right.bones?.[name];
    if (!lb && !rb) continue;
    const mixed = {};
    const rot = averageChannel(lb?.rotation, rb?.rotation, duration);
    const pos = averageChannel(lb?.position, rb?.position, duration);
    const scale = averageChannel(lb?.scale, rb?.scale, duration);
    if (rot) mixed.rotation = rot;
    if (pos) mixed.position = pos;
    if (scale) mixed.scale = scale;
    if (Object.keys(mixed).length) bones[name] = mixed;
  }

  return {
    loop: false,
    animation_length: round(duration, 5),
    bones,
  };
}

for (const spec of FILES) {
  const p = path.join(ANIM_DIR, spec.file);
  const json = JSON.parse(fs.readFileSync(p, 'utf8'));
  const anims = json.animations;
  if (!anims[spec.left] || !anims[spec.right]) {
    throw new Error(`${spec.file}: missing ${spec.left}/${spec.right}`);
  }
  anims.attack_dual = mix(anims[spec.left], anims[spec.right], spec.leftBones, spec.rightBones, spec.bodyBones);
  if (spec.file.startsWith('third_person_')) {
    const upperBones = new Set([...LEFT_3P, ...RIGHT_3P, 'upper_body', 'head']);
    anims.attack_dual_upper = {
      ...structuredClone(anims.attack_dual),
      bones: Object.fromEntries(Object.entries(anims.attack_dual.bones)
        .filter(([name]) => upperBones.has(name))),
    };
  }
  fs.writeFileSync(p, JSON.stringify(json, null, 1) + '\n');
  console.log(`${spec.file}: attack_dual ${anims.attack_dual.animation_length}s, bones ${Object.keys(anims.attack_dual.bones).join(',')}`);
}

console.log('generate_dual_slash done');
