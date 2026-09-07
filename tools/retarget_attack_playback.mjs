#!/usr/bin/env node
// Rebuild each attack from the 023010 restore without inventing slash poses.
// Linear 1.6s→0.3s compression also sped up the original recovery, so the blade
// only occupied a flicker of the clip. Keep the authored windup/slash/follow-through,
// spend the 0.3s budget as slow chamber → fast contact → impact hold, then the
// requested 0.1s settle. Combo/pre-input clocks stay 0.3s / 0.2s.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ANIM_DIR = path.join(ROOT, 'src/main/resources/assets/actioncombat/geckolib/animations');
const RESTORE_DIR = path.join(ROOT, '.restore-023010');

const SLASH_SECONDS = 0.3;
const RECOVERY_SECONDS = 0.1;
const TOTAL_SECONDS = round(SLASH_SECONDS + RECOVERY_SECONDS, 4);
const WINDUP_DST = 0.12;
const CONTACT_DST = 0.18;
const SAMPLE_STEP = 1 / 60;
const ACTIVE_SPEED_SHARE = 0.40;

const THIRD_PERSON_CHAIN = [
  'upper_body',
  'upper_arm_r', 'forearm_r', 'hand_r', 'hand_item_r',
  'upper_arm_l', 'forearm_l', 'hand_l', 'hand_item_l',
];
const FIRST_PERSON_CHAIN = ['left', 'right', 'elucidator'];
const FILES = {
  'third_person_singlesword.animation.json': {
    clips: ['attack1', 'attack2', 'attack3', 'attack4', 'attack_dual'],
    chain: THIRD_PERSON_CHAIN,
    upper: true,
  },
  'third_person_doublesword.animation.json': {
    clips: ['attack_1', 'attack2', 'attack3', 'attack4', 'attack_dual'],
    chain: THIRD_PERSON_CHAIN,
    upper: true,
  },
  'fp_singlesword.animation.json': {
    clips: ['attack1', 'attack2', 'attack3', 'attack4', 'attack_dual'],
    chain: FIRST_PERSON_CHAIN,
    upper: false,
  },
};

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

function lerpVec(a, b, t) {
  const n = Math.max(a.length, b.length, 3);
  const out = [];
  for (let i = 0; i < n; i++) {
    const av = a[i] ?? 0;
    const bv = b[i] ?? 0;
    out[i] = round(av + (bv - av) * t, 4);
  }
  return out;
}

function makeNode(vec, lerpMode) {
  return { post: vec, lerp_mode: lerpMode };
}

function timesOf(channel) {
  if (!channel || Array.isArray(channel) || typeof channel !== 'object') return [];
  return Object.keys(channel).filter(isTimeKey).map(parseFloat).sort((a, b) => a - b);
}

function nodeAt(channel, t) {
  return channel[timeKey(t)] ?? channel[String(t)] ?? channel[t];
}

function samples(channel) {
  if (!channel || Array.isArray(channel)) return [];
  const out = [];
  for (const t of timesOf(channel)) {
    const vec = vecOf(nodeAt(channel, t) ?? channel[t]);
    if (!Array.isArray(vec) || vec.length < 3) continue;
    out.push({ t, v: vec.map(n => (typeof n === 'number' && Number.isFinite(n) ? n : 0)) });
  }
  return out;
}

function sampleAt(points, t) {
  if (!points.length) return null;
  if (t <= points[0].t) return points[0].v;
  if (t >= points[points.length - 1].t) return points[points.length - 1].v;
  for (let i = 1; i < points.length; i++) {
    if (points[i].t >= t) {
      const a = points[i - 1];
      const b = points[i];
      const f = (t - a.t) / Math.max(1e-6, b.t - a.t);
      return [0, 1, 2].map(k => a.v[k] + (b.v[k] - a.v[k]) * f);
    }
  }
  return points[points.length - 1].v;
}

function sampleChannelAt(channel, t) {
  const points = samples(channel);
  const vec = sampleAt(points, t);
  return vec ? [...vec] : null;
}

function bladeProfile(clip, length, boneNames) {
  const chain = boneNames
    .map(bone => ({ bone, points: samples(clip.bones?.[bone]?.rotation) }))
    .filter(c => c.points.length >= 2);
  if (!chain.length) return null;
  const steps = Math.max(2, Math.ceil(length / SAMPLE_STEP));
  const dt = length / steps;
  const spans = [];
  for (let i = 0; i < steps; i++) {
    const t0 = i * dt;
    const t1 = t0 + dt;
    let delta = 0;
    for (const { points } of chain) {
      const a = sampleAt(points, t0);
      const b = sampleAt(points, t1);
      if (!a || !b) continue;
      delta += Math.hypot(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
    }
    spans.push({ start: t0, end: t1, speed: delta / dt });
  }
  return spans;
}

function detectPhases(clip, boneNames) {
  const length = clip.animation_length;
  const spans = bladeProfile(clip, length, boneNames);
  if (!spans?.length) {
    return {
      windup: round(length * 0.28, 4),
      contact: round(length * 0.42, 4),
      follow: round(length * 0.58, 4),
    };
  }
  const minWindup = Math.min(0.08, length * 0.10);
  const searchStart = Math.max(0, spans.findIndex(s => s.start >= minWindup));
  let peak = 0;
  for (let i = searchStart; i < spans.length; i++) {
    if (spans[i].speed > peak) peak = spans[i].speed;
  }
  // Prefer the first real slash, not a later flourish that happens to be slightly faster.
  const strong = peak * 0.88;
  let peakIdx = searchStart;
  for (let i = searchStart; i < spans.length; i++) {
    if (spans[i].speed >= strong) {
      peakIdx = i;
      break;
    }
  }
  const threshold = peak * ACTIVE_SPEED_SHARE;
  let lo = peakIdx;
  let hi = peakIdx;
  while (lo > 0 && spans[lo - 1].speed >= threshold) lo--;
  while (hi < spans.length - 1 && spans[hi + 1].speed >= threshold) {
    const next = spans[hi + 1];
    if (next.start - spans[peakIdx].start > 0.28) break;
    hi++;
  }
  let windup = Math.max(spans[lo].start, minWindup);
  let contact = Math.max(windup + 0.04, spans[hi].end);
  let followIdx = hi;
  const followFloor = peak * 0.12;
  while (followIdx < spans.length - 1 && spans[followIdx + 1].speed >= followFloor) {
    const next = spans[followIdx + 1];
    if (next.start - spans[peakIdx].start > 0.32) break;
    if (next.speed > spans[followIdx].speed * 1.35 && spans[followIdx].speed < threshold) break;
    followIdx++;
  }
  const slashSpan = Math.max(0.04, contact - windup);
  let follow = spans[followIdx].end;
  follow = Math.min(length, Math.max(contact + 0.06, Math.min(follow, contact + slashSpan * 2.4)));
  follow = Math.min(follow, length * 0.72);
  if (follow <= contact) follow = Math.min(length, contact + 0.08);
  if (contact <= windup) contact = Math.min(follow - 0.03, windup + 0.06);
  return {
    windup: round(windup, 4),
    contact: round(contact, 4),
    follow: round(follow, 4),
  };
}

function easeIn(u) {
  return u * u;
}

function easeOut(u) {
  return 1 - (1 - u) * (1 - u);
}

function mapAuthoredTime(t, phases) {
  const src = [0, phases.windup, phases.contact, phases.follow];
  const dst = [0, WINDUP_DST, CONTACT_DST, SLASH_SECONDS];
  const eased = [null, easeIn, (u) => u, easeOut];
  if (t <= 0) return 0;
  if (t >= phases.follow) return SLASH_SECONDS;
  for (let i = 1; i < src.length; i++) {
    if (t <= src[i] || i === src.length - 1) {
      const span = Math.max(1e-6, src[i] - src[i - 1]);
      const u = Math.min(1, Math.max(0, (t - src[i - 1]) / span));
      return round(dst[i - 1] + eased[i](u) * (dst[i] - dst[i - 1]), 4);
    }
  }
  return SLASH_SECONDS;
}

function remapChannel(channel, phases) {
  if (!channel || Array.isArray(channel) || typeof channel !== 'object') return channel;
  const times = timesOf(channel);
  if (!times.length) return channel;
  const keyed = new Map();
  for (const t of times) {
    if (t > phases.follow + 1e-4) continue;
    const vec = vecOf(nodeAt(channel, t) ?? channel[t]);
    if (!vec) continue;
    keyed.set(mapAuthoredTime(t, phases), vec.map(n => (typeof n === 'number' ? round(n, 4) : n)));
  }
  const start = sampleChannelAt(channel, times[0]) ?? vecOf(nodeAt(channel, times[0]) ?? channel[times[0]]);
  const windup = sampleChannelAt(channel, phases.windup) ?? start;
  const impact = sampleChannelAt(channel, phases.contact) ?? windup;
  const end = sampleChannelAt(channel, phases.follow) ?? impact;
  if (!start || !end) {
    const out = {};
    for (const t of [...keyed.keys()].sort((a, b) => a - b)) {
      out[timeKey(t)] = makeNode(keyed.get(t), 'catmullrom');
    }
    return out;
  }
  keyed.set(0, start);
  keyed.set(WINDUP_DST, windup);
  keyed.set(CONTACT_DST, impact);
  keyed.set(SLASH_SECONDS, end);
  // Linger on the impact pose, then ease back to the opening pose.
  keyed.set(round(SLASH_SECONDS + 0.04, 4), lerpVec(end, start, 0.22));
  keyed.set(round(SLASH_SECONDS + 0.08, 4), lerpVec(end, start, 0.72));
  keyed.set(TOTAL_SECONDS, start);
  const out = {};
  for (const t of [...keyed.keys()].sort((a, b) => a - b)) {
    out[timeKey(t)] = makeNode(keyed.get(t), 'catmullrom');
  }
  return out;
}

function retargetClip(clip, boneNames) {
  const oldLen = clip.animation_length;
  if (!(oldLen > 0)) throw new Error('attack clip missing animation_length');
  const phases = detectPhases(clip, boneNames);
  const bones = {};
  for (const [name, channels] of Object.entries(clip.bones ?? {})) {
    if (!channels || typeof channels !== 'object') {
      bones[name] = channels;
      continue;
    }
    const next = { ...channels };
    for (const channel of ['rotation', 'position', 'scale']) {
      if (channels[channel]) next[channel] = remapChannel(channels[channel], phases);
    }
    bones[name] = next;
  }
  return {
    clip: {
      loop: false,
      animation_length: TOTAL_SECONDS,
      bones,
    },
    phases,
  };
}

function upperBodyOnly(clip) {
  const lower = new Set([
    'root', 'lower_body',
    'leg_r', 'leg_l', 'lower_leg_r', 'lower_leg_l', 'foot_r', 'foot_l',
  ]);
  return {
    ...clip,
    bones: Object.fromEntries(Object.entries(clip.bones ?? {}).filter(([name]) => !lower.has(name))),
  };
}

for (const [file, spec] of Object.entries(FILES)) {
  const restorePath = path.join(RESTORE_DIR, file);
  const livePath = path.join(ANIM_DIR, file);
  const source = JSON.parse(fs.readFileSync(fs.existsSync(restorePath) ? restorePath : livePath, 'utf8'));
  const live = JSON.parse(fs.readFileSync(livePath, 'utf8'));
  for (const name of spec.clips) {
    const src = source.animations[name];
    if (!src) throw new Error(`${file}: missing ${name} in restore/source`);
    const { clip, phases } = retargetClip(src, spec.chain);
    live.animations[name] = clip;
    if (spec.upper) live.animations[`${name}_upper`] = upperBodyOnly(clip);
    console.log(`  ${file} ${name}: src windup ${phases.windup}s contact ${phases.contact}s follow ${phases.follow}s -> 0-0.12 / 0.12-0.18 / 0.18-0.30 + 0.10 settle`);
  }
  if (live.actioncombat_fp_source) delete live.actioncombat_fp_source;
  fs.writeFileSync(livePath, JSON.stringify(live, null, 1) + '\n');
}

console.log('retarget_attack_playback done');
