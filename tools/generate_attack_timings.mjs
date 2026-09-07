#!/usr/bin/env node
// Records source phases and the base interval; runtime latches each swing's interval.
//
// Why measure rather than hand-tune: each authored swing has its own windup. Using a
// single ratio means a slow heavy clip connects too early and a fast one too late.
// The blade's angular speed is the signal — the contact frames are where the weapon
// is actually travelling fastest.
//
// For each attack clip:
//   activeStart/activeEnd  base runtime contact phase (40% and 60% of the interval)
//   authoredActiveStart/authoredActiveEnd/authoredLength  original phase boundaries
//   comboStart/comboEnd    final pre-input/recovery span, ending with the clip
//   cancelStart            when another action may interrupt the recovery
//
// Output: src/main/resources/actioncombat/attack_timings.json
// Run: node tools/generate_attack_timings.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ANIM_DIR = path.join(ROOT, 'src/main/resources/assets/actioncombat/geckolib/animations');
const OUT_FILE = path.join(ROOT, 'src/main/resources/actioncombat/attack_timings.json');

/**
 * The blade's motion comes from the whole arm chain, not one bone: the shoulder
 * carries most of a horizontal slash while the wrist carries a thrust. Summing the
 * chain's angular change approximates the weapon's own speed well enough to find
 * contact frames, and is far more stable than trusting any single bone.
 */
const BLADE_CHAIN = [
  'upper_body',
  'upper_arm_r', 'forearm_r', 'hand_r', 'hand_item_r',
  'upper_arm_l', 'forearm_l', 'hand_l', 'hand_item_l',
];
const FIRST_PERSON_BLADE_CHAIN = ['left', 'right', 'elucidator'];

/** Contact frames are where blade speed is at least this share of its peak. */
const ACTIVE_SPEED_SHARE = 0.40;

/** Resampling step in seconds; keyframes are sparse and unevenly spaced. */
const SAMPLE_STEP = 1 / 60;

/** Contact cannot occur in this opening share of a clip (windup). */
const MIN_WINDUP_SHARE = 0.12;

const constants = fs.readFileSync(path.join(ROOT, 'src/main/java/com/actioncombat/CombatConstants.java'), 'utf8');
function tuning(name) {
  const match = constants.match(new RegExp(`\\b${name}\\s*=\\s*([0-9.]+)F\\s*;`));
  if (!match) throw new Error(`Missing numeric CombatConstants.${name}`);
  return Number(match[1]);
}
const MIN_ATTACK_INTERVAL = tuning('ATTACK_INTERVAL_SECONDS');
const INPUT_BUFFER_SECONDS = tuning('INPUT_BUFFER_SECONDS');
const CANCEL_TAIL_SECONDS = tuning('ATTACK_CANCEL_TAIL_SECONDS');
const RECOVERY_POSE_SHARE = tuning('ATTACK_RECOVERY_POSE_SHARE');
const SLASH_SECONDS = tuning('ATTACK_SLASH_SECONDS');

function runtimeTiming() {
  const duration = round(MIN_ATTACK_INTERVAL + CANCEL_TAIL_SECONDS);
  return {
    activeStart: tuning('ATTACK_ACTIVE_START_SECONDS'),
    activeEnd: tuning('ATTACK_ACTIVE_END_SECONDS'),
    comboStart: round(Math.max(0, MIN_ATTACK_INTERVAL - INPUT_BUFFER_SECONDS)),
    comboEnd: duration,
    cancelStart: MIN_ATTACK_INTERVAL,
    length: duration,
    recoveryPoseShare: RECOVERY_POSE_SHARE,
  };
}

const FILES = {
  third_person_singlesword: ['attack1', 'attack2', 'attack3', 'attack4', 'attack_dual'],
  third_person_doublesword: ['attack_1', 'attack2', 'attack3', 'attack4', 'attack_dual'],
};

function round(v, digits = 4) {
  const p = 10 ** digits;
  return Math.round(v * p) / p;
}

/** Keyframe times and rotation vectors of one channel, sorted by time. */
function samples(channel) {
  if (!channel || Array.isArray(channel)) return [];
  const out = [];
  for (const [t, v] of Object.entries(channel)) {
    const vec = Array.isArray(v) ? v : (v?.post ?? v?.pre ?? v?.vector);
    if (!Array.isArray(vec)) continue;
    const nums = vec.map(n => (typeof n === 'number' && Number.isFinite(n) ? n : 0));
    if (nums.length < 3) continue;
    out.push({ t: parseFloat(t), v: nums });
  }
  return out.sort((a, b) => a.t - b.t);
}

/** Linear sample of a keyframe list at time t (clamped at both ends). */
function sampleAt(points, t) {
  if (!points.length) return null;
  if (t <= points[0].t) return points[0].v;
  if (t >= points[points.length - 1].t) return points[points.length - 1].v;
  for (let i = 1; i < points.length; i++) {
    if (points[i].t >= t) {
      const a = points[i - 1];
      const b = points[i];
      const f = (t - a.t) / (b.t - a.t);
      return [0, 1, 2].map(k => a.v[k] + (b.v[k] - a.v[k]) * f);
    }
  }
  return points[points.length - 1].v;
}

/**
 * Blade angular speed resampled on a fixed grid, summed across the arm chain.
 * Uniform sampling matters: raw keyframe spacing varies by an order of magnitude
 * within one clip, so per-interval speeds are not comparable to each other.
 */
function bladeProfile(clip, length, boneNames = BLADE_CHAIN, exactKeyframes = false) {
  const chain = boneNames
    .map(bone => ({ bone, points: samples(clip.bones?.[bone]?.rotation) }))
    .filter(c => c.points.length >= 2);
  if (!chain.length) return null;

  const steps = Math.max(2, Math.ceil(length / SAMPLE_STEP));
  const dt = length / steps;
  const times = exactKeyframes
    ? [...new Set([0, length, ...chain.flatMap(c => c.points.map(p => p.t))])]
      .filter(t => t >= 0 && t <= length).sort((a, b) => a - b)
    : null;
  const spans = [];

  for (let i = 0; i < (times ? times.length - 1 : steps); i++) {
    const t0 = times ? times[i] : i * dt;
    const t1 = times ? times[i + 1] : t0 + dt;
    let delta = 0;
    for (const { points } of chain) {
      const a = sampleAt(points, t0);
      const b = sampleAt(points, t1);
      if (!a || !b) continue;
      delta += Math.hypot(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
    }
    spans.push({ start: t0, end: t1, speed: delta / (times ? t1 - t0 : dt) });
  }

  return { bone: chain.map(c => c.bone).join('+'), spans };
}

function analyseFirstPerson(name, clip) {
  const length = clip?.animation_length;
  if (!(length > 0)) throw new Error(`fp_singlesword/${name}: missing animation_length`);
  // These independent clips mark windup, slash and recovery explicitly. Compare
  // their actual intervals so the short main slash is not widened into a held pose.
  const profile = bladeProfile(clip, length, FIRST_PERSON_BLADE_CHAIN, true);
  if (!profile?.spans.length) throw new Error(`fp_singlesword/${name}: missing hand/weapon rotation keys`);
  const { spans, bone } = profile;
  let peak = 0;
  for (let i = 1; i < spans.length; i++) {
    if (spans[i].speed > spans[peak].speed) peak = i;
  }
  const threshold = spans[peak].speed * ACTIVE_SPEED_SHARE;
  let lo = peak;
  let hi = peak;
  while (lo > 0 && spans[lo - 1].speed >= threshold) lo--;
  while (hi < spans.length - 1 && spans[hi + 1].speed >= threshold) hi++;
  if (!(spans[lo].start > 0 && spans[hi].end < length)) {
    return {
      authoredActiveStart: round(SLASH_SECONDS * 0.40),
      authoredActiveEnd: round(SLASH_SECONDS * 0.60),
      authoredLength: round(length),
      source: `copied-third-person:${bone}`,
    };
  }
  return {
    authoredActiveStart: round(spans[lo].start),
    authoredActiveEnd: round(spans[hi].end),
    authoredLength: round(length),
    source: `keyframe-speed:${bone}`,
  };
}

function analyse(name, clip) {
  const length = clip.animation_length;
  if (!(length > 0)) throw new Error(`${name}: missing animation_length`);

  const profile = bladeProfile(clip, length);
  if (!profile) {
    // no usable rotation data: fall back to the old fixed ratios
    return {
      ...runtimeTiming(),
      authoredActiveStart: round(length * 0.30),
      authoredActiveEnd: round(length * 0.60),
      authoredLength: round(length),
      source: 'fallback-ratio',
    };
  }

  const { bone, spans } = profile;

  // Several clips open mid-motion, continuing the previous swing's pose, which leaves
  // a large discontinuity in the opening frames. A swing cannot connect on frame 0, so
  // the peak search skips the windup; the window may still widen back into it.
  const searchFrom = spans.findIndex(s => s.start >= length * MIN_WINDUP_SHARE);
  const searchStart = searchFrom < 0 ? 0 : searchFrom;

  let peak = 0;
  let peakIdx = searchStart;
  for (let i = searchStart; i < spans.length; i++) {
    if (spans[i].speed > peak) { peak = spans[i].speed; peakIdx = i; }
  }
  const threshold = peak * ACTIVE_SPEED_SHARE;

  // widen from the fastest span while neighbours stay above the threshold, so the
  // active window is one contiguous swing rather than every fast moment in the clip
  let lo = peakIdx;
  let hi = peakIdx;
  while (lo > 0 && spans[lo - 1].speed >= threshold) lo--;
  while (hi < spans.length - 1 && spans[hi + 1].speed >= threshold) hi++;

  let activeStart = spans[lo].start;
  let activeEnd = spans[hi].end;
  const MIN_ACTIVE = 0.08;
  const slashEnd = Math.min(SLASH_SECONDS, length);
  activeStart = Math.max(activeStart, length * MIN_WINDUP_SHARE);
  activeStart = Math.min(activeStart, slashEnd - MIN_ACTIVE);
  activeEnd = Math.min(activeEnd, slashEnd);
  if (activeEnd - activeStart < MIN_ACTIVE) {
    activeEnd = Math.min(slashEnd, activeStart + MIN_ACTIVE);
  }

  return {
    ...runtimeTiming(),
    authoredActiveStart: round(activeStart),
    authoredActiveEnd: round(activeEnd),
    authoredLength: round(length),
    source: `bone:${bone}`,
  };
}

const output = { _comment: 'Generated by tools/generate_attack_timings.mjs — do not edit by hand.' };
const firstPersonAnims = JSON.parse(fs.readFileSync(
  path.join(ANIM_DIR, 'fp_singlesword.animation.json'), 'utf8')).animations;

for (const [fileId, clips] of Object.entries(FILES)) {
  const file = path.join(ANIM_DIR, `${fileId}.animation.json`);
  const anims = JSON.parse(fs.readFileSync(file, 'utf8')).animations;

  const perClip = {};
  for (const name of clips) {
    if (!anims[name]) throw new Error(`${fileId}: attack clip '${name}' missing`);
    perClip[name] = analyse(name, anims[name]);
    if (fileId === 'third_person_singlesword') {
      perClip[name].firstPerson = analyseFirstPerson(name, firstPersonAnims[name]);
    }
  }
  output[fileId] = perClip;

  console.log(`ok ${fileId}`);
  for (const [name, t] of Object.entries(perClip)) {
    console.log(`   ${name.padEnd(9)} active ${t.activeStart}..${t.activeEnd}s`
      + `  combo ${t.comboStart}..${t.comboEnd}s  cancel ${t.cancelStart}s`
      + `  len ${t.length}s  authored ${t.authoredActiveStart}..${t.authoredActiveEnd}/${t.authoredLength}s (${t.source})`);
  }
}

fs.mkdirSync(path.dirname(OUT_FILE), { recursive: true });
fs.writeFileSync(OUT_FILE, JSON.stringify(output, null, 2) + '\n');
console.log(`wrote ${path.relative(ROOT, OUT_FILE)}`);
