#!/usr/bin/env node
// Generates directional lower-body cycles, airborne motion and upper overlays.
// Authored run/weapon poses supply the upper-body style; walking, lateral steps,
// backpedalling and crouch steps have their own leg motion below the action layer.
//
// Idempotent: generated clips are rebuilt from the authored sources on every run
// and tagged so they are never used as a source for another generated clip.
// Run: node tools/generate_locomotion.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ANIM_DIR = path.join(ROOT, 'src/main/resources/assets/actioncombat/geckolib/animations');

/** Marker so regeneration never treats its own output as authored input. */
const GENERATED = 'actioncombat:generated_locomotion';

const ROT = 'rotation';
const POS = 'position';

// ---------- keyframe plumbing ----------
// Values appear as bare arrays, {post:[...]}, or {pre:[...],post:[...]}; numbers may
// also be molang strings, which are passed through untouched.

function isNum(v) {
  return typeof v === 'number' && Number.isFinite(v);
}

function mapVector(vec, fn) {
  if (!Array.isArray(vec)) return vec;
  return vec.map((v, i) => (isNum(v) ? fn(v, i) : v));
}

function mapKeyValue(value, fn) {
  if (Array.isArray(value)) return mapVector(value, fn);
  if (value && typeof value === 'object') {
    const out = { ...value };
    for (const slot of ['pre', 'post', 'vector']) {
      if (slot in out) out[slot] = mapVector(out[slot], fn);
    }
    return out;
  }
  return value;
}

/** First concrete vector of a channel, used as that channel's reference pose. */
function firstVector(channel) {
  if (!channel) return null;
  if (Array.isArray(channel)) return channel.filter(isNum).length ? channel : null;
  const times = Object.keys(channel).sort((a, b) => parseFloat(a) - parseFloat(b));
  for (const t of times) {
    const v = channel[t];
    const vec = Array.isArray(v) ? v : (v?.post ?? v?.pre ?? v?.vector);
    if (Array.isArray(vec)) return vec;
  }
  return null;
}

function eachKey(channel, fn) {
  if (!channel) return channel;
  if (Array.isArray(channel)) return fn(channel);
  const out = {};
  for (const [t, v] of Object.entries(channel)) out[t] = fn(v);
  return out;
}

/** Multiply every keyframe timestamp (and the clip length) by `factor`. */
function stretchChannel(channel, factor) {
  if (!channel || Array.isArray(channel)) return channel;
  const out = {};
  for (const [t, v] of Object.entries(channel)) {
    out[timeKey(parseFloat(t) * factor)] = v;
  }
  return out;
}

function round(v, digits) {
  const p = 10 ** digits;
  return Math.round(v * p) / p;
}

/**
 * Timestamp as an object key, always carrying a decimal point.
 *
 * JS orders integer-like object keys before string keys, so a key of "1" would be
 * serialized ahead of "0.0" and GeckoLib would read the clip's end as its opening
 * keyframe ("Invalid keyframe data - multiple starting keyframes"). Keeping every
 * key non-integer-like preserves insertion order through JSON.stringify.
 */
function timeKey(seconds) {
  const v = round(seconds, 4);
  return Number.isInteger(v) ? v.toFixed(1) : v.toString();
}

function clone(v) {
  return JSON.parse(JSON.stringify(v));
}

// ---------- clip transforms ----------

/**
 * Pull every channel of `clip` toward the matching channel's reference pose in
 * `restClip`, then stretch time. weight 1 keeps the source, 0 collapses to rest.
 */
function dampToward(clip, restClip, weight, timeFactor) {
  const out = clone(clip);
  out.animation_length = round((clip.animation_length ?? 0) * timeFactor, 4);
  out.bones = {};

  for (const [bone, channels] of Object.entries(clip.bones ?? {})) {
    const restBone = restClip.bones?.[bone] ?? {};
    const outBone = {};
    for (const [channelName, channel] of Object.entries(channels)) {
      const identity = channelName === 'scale' ? 1 : 0;
      const rest = firstVector(restBone[channelName]) ?? [identity, identity, identity];
      const damped = eachKey(channel, (value) =>
        mapKeyValue(value, (v, i) => {
          const ref = isNum(rest[i]) ? rest[i] : identity;
          return round(ref + (v - ref) * weight, 4);
        }));
      outBone[channelName] = stretchChannel(damped, timeFactor);
    }
    out.bones[bone] = outBone;
  }
  out[GENERATED] = true;
  return out;
}

/**
 * Static clip built from the rest pose plus fixed per-bone offsets.
 * offsets: { bone: { rotation?: [x,y,z], position?: [x,y,z] } }
 */
function posedClip(restClip, offsets, { length, loop }) {
  const bones = {};
  const boneNames = new Set([
    ...Object.keys(restClip.bones ?? {}),
    ...Object.keys(offsets),
  ]);

  for (const bone of boneNames) {
    const restBone = restClip.bones?.[bone] ?? {};
    const offset = offsets[bone] ?? {};
    const channels = {};
    for (const channelName of [ROT, POS]) {
      const off = offset[channelName];
      const rest = firstVector(restBone[channelName]);
      if (!off && !rest) continue;
      const base = rest ?? [0, 0, 0];
      const vec = [0, 1, 2].map((i) => {
        const b = isNum(base[i]) ? base[i] : 0;
        const o = off && isNum(off[i]) ? off[i] : 0;
        return round(b + o, 4);
      });
      // two keys so GeckoLib always has a segment to interpolate over
      channels[channelName] = {
        [timeKey(0)]: { post: vec, lerp_mode: 'linear' },
        [timeKey(length)]: { post: vec, lerp_mode: 'linear' },
      };
    }
    if (Object.keys(channels).length) bones[bone] = channels;
  }

  return { loop, animation_length: length, bones, [GENERATED]: true };
}

/**
 * One-shot reaction: rest pose -> offset pose -> rest pose, so it reads as a
 * flinch rather than a snap. `hold` keeps the peak for a beat mid-clip.
 */
function pulseClip(restClip, offsets, { length, peakAt, loop = false }) {
  const posed = posedClip(restClip, offsets, { length, loop });
  const out = { loop, animation_length: length, bones: {}, [GENERATED]: true };

  for (const [bone, channels] of Object.entries(posed.bones)) {
    const restBone = restClip.bones?.[bone] ?? {};
    const outBone = {};
    for (const [channelName, channel] of Object.entries(channels)) {
      const peak = firstVector(channel);
      const rest = firstVector(restBone[channelName])
        ?? [0, 0, 0].map((_, i) => (isNum(peak?.[i]) ? 0 : 0));
      const restVec = [0, 1, 2].map((i) => (isNum(rest[i]) ? rest[i] : 0));
      outBone[channelName] = {
        [timeKey(0)]: { post: restVec, lerp_mode: 'catmullrom' },
        [timeKey(length * peakAt)]: { post: peak, lerp_mode: 'catmullrom' },
        [timeKey(length)]: { post: restVec, lerp_mode: 'catmullrom' },
      };
    }
    out.bones[bone] = outBone;
  }
  return out;
}

// ---------- per-rig generation ----------

// Walk keeps 55% of the run cycle's swing at 1.8x the duration: still clearly a
// stride, but without the run's lean and arm drive.
const WALK_WEIGHT = 0.42;
const WALK_TIME = 1.9;

/** Rigs use different bone names for the same body parts. */
const BONE_SETS = {
  third_person_singlesword: {
    root: 'root',
    lowerBody: 'lower_body',
    upperBody: 'upper_body',
    head: 'head',
    legs: ['leg_r', 'leg_l'],
    lowerLegs: ['lower_leg_r', 'lower_leg_l'],
    feet: ['foot_r', 'foot_l'],
    armsUpper: ['upper_arm_r', 'upper_arm_l'],
  },
  third_person_doublesword: {
    root: 'root',
    lowerBody: 'lower_body',
    upperBody: 'upper_body',
    head: 'head',
    legs: ['leg_r', 'leg_l'],
    lowerLegs: ['lower_leg_r', 'lower_leg_l'],
    feet: ['foot_r', 'foot_l'],
    armsUpper: ['upper_arm_r', 'upper_arm_l'],
  },
};

/** Guard clip differs per rig. */
const DEFEND_CLIP = {
  third_person_singlesword: 'defend',
  third_person_doublesword: 'defence_1',
};

/** Full-body authored swings stripped to upper-body overlays for walk+attack. */
const OVERLAY_CLIPS = {
  third_person_singlesword: ['attack1', 'attack2', 'attack3', 'attack4', 'attack_dual'],
  third_person_doublesword: ['attack_1', 'attack2', 'attack3', 'attack4', 'attack_dual'],
};

function jumpStartOffsets(b) {
  const o = {};
  o[b.root] = { position: [0, -2.2, 0] };
  o[b.lowerBody] = { rotation: [14, 0, 0] };
  o[b.upperBody] = { rotation: [10, 0, 0] };
  o[b.legs[0]] = { rotation: [-42, 8, 0] };
  o[b.legs[1]] = { rotation: [-18, -6, 0] };
  o[b.lowerLegs[0]] = { rotation: [48, 0, 0] };
  o[b.lowerLegs[1]] = { rotation: [22, 0, 0] };
  for (const arm of b.armsUpper) o[arm] = { rotation: [16, 0, 0] };
  return o;
}

function jumpOffsets(b) {
  const o = {};
  o[b.root] = { position: [0, 1.5, 0] };
  o[b.upperBody] = { rotation: [-8, 0, 0] };
  o[b.legs[0]] = { rotation: [-30, 0, 0] };
  o[b.legs[1]] = { rotation: [-12, 0, 0] };
  o[b.lowerLegs[0]] = { rotation: [34, 0, 0] };
  o[b.lowerLegs[1]] = { rotation: [16, 0, 0] };
  for (const arm of b.armsUpper) o[arm] = { rotation: [-22, 0, 0] };
  return o;
}

function fallOffsets(b) {
  const o = {};
  o[b.upperBody] = { rotation: [-12, 0, 0] };
  o[b.head] = { rotation: [10, 0, 0] };
  o[b.legs[0]] = { rotation: [-18, 0, 0] };
  o[b.legs[1]] = { rotation: [14, 0, 0] };
  o[b.lowerLegs[0]] = { rotation: [22, 0, 0] };
  for (const arm of b.armsUpper) o[arm] = { rotation: [-34, 0, 0] };
  return o;
}

/**
 * Upper-body-only copy of a clip: every bone at or below the hips is dropped.
 *
 * The action layer is not additive (these clips are authored as absolute poses, so
 * adding them onto a walk cycle would double-count the rest pose). Instead the held
 * stances simply contain no leg bones, so when the action layer overrides bones it
 * only touches the torso and arms and the locomotion layer's legs keep walking.
 */
function upperBodyOnly(clip, lowerBones) {
  const out = { ...clip, bones: {} };
  for (const [bone, channels] of Object.entries(clip.bones ?? {})) {
    if (lowerBones.has(bone)) continue;
    out.bones[bone] = clone(channels);
  }
  out[GENERATED] = true;
  return out;
}

function isTimeKey(k) {
  return Number.isFinite(parseFloat(k));
}

function vecOf(node) {
  if (Array.isArray(node)) return node;
  if (node && typeof node === 'object') return node.post ?? node.pre ?? node.vector ?? null;
  return null;
}

/** Last concrete vector on a channel (hold pose). */
function lastVector(channel) {
  if (!channel) return null;
  if (Array.isArray(channel)) return channel.filter(isNum).length ? channel : null;
  const times = Object.keys(channel).filter(isTimeKey).map(parseFloat).sort((a, b) => a - b);
  for (let i = times.length - 1; i >= 0; i--) {
    const key = Object.keys(channel).find(k => isTimeKey(k) && Math.abs(parseFloat(k) - times[i]) < 1e-6);
    const vec = vecOf(channel[key] ?? channel[String(times[i])]);
    if (Array.isArray(vec)) return vec;
  }
  return firstVector(channel);
}

/**
 * One-shot upper-body recover: last frame of `fromClip` back to `restClip`.
 * Played when a hold_on_last_frame guard ends so the overlay does not freeze.
 */
function blendToRest(fromClip, restClip, length) {
  const out = {
    loop: false,
    animation_length: round(length, 4),
    bones: {},
  };
  out[GENERATED] = true;
  const names = new Set([
    ...Object.keys(fromClip.bones ?? {}),
    ...Object.keys(restClip.bones ?? {}),
  ]);
  for (const name of names) {
    const fromBone = fromClip.bones?.[name] ?? {};
    const restBone = restClip.bones?.[name] ?? {};
    const bone = {};
    for (const channelName of ['rotation', 'position', 'scale']) {
      const start = lastVector(fromBone[channelName]) ?? firstVector(fromBone[channelName]);
      const end = firstVector(restBone[channelName]) ?? start;
      if (!start && !end) continue;
      const a = start ?? end;
      const b = end ?? start;
      bone[channelName] = {
        '0.0': { post: clone(a), lerp_mode: 'catmullrom' },
        [timeKey(length)]: { post: clone(b), lerp_mode: 'catmullrom' },
      };
    }
    if (Object.keys(bone).length) out.bones[name] = bone;
  }
  return out;
}

function sampledChannel(length, sample) {
  const channel = {};
  for (let frame = 0; frame <= 20; frame++) {
    // Explicitly close the cycle so roundoff cannot leave a loop discontinuity.
    const phase = frame === 20 ? 0 : frame / 20;
    channel[timeKey(length * frame / 20)] = {
      post: sample(phase).map(value => round(value, 4)),
      lerp_mode: 'catmullrom',
    };
  }
  return channel;
}

function sampleBone(clip, bone, channel, sample) {
  clip.bones[bone] ??= {};
  clip.bones[bone][channel] = sampledChannel(clip.animation_length, sample);
}

/** Grounded support occupies 60% of a step; the returning foot lifts and folds. */
function stepPhase(phase) {
  const p = ((phase % 1) + 1) % 1;
  if (p < 0.6) return { stride: 1 - p / 0.3, lift: 0 };
  const swing = (p - 0.6) / 0.4;
  return { stride: -Math.cos(Math.PI * swing), lift: Math.sin(Math.PI * swing) };
}

function groundCycle(source, rest, bones, { direction = 'forward', running = false,
  crouching = false, stationary = false } = {}) {
  const lateral = direction === 'left' || direction === 'right';
  const side = direction === 'left' ? 1 : direction === 'right' ? -1 : 0;
  const forward = lateral ? 0 : direction === 'back' ? -1 : 1;
  const length = stationary ? 1.6 : crouching ? 1.15
    : running ? (lateral ? 0.58 : 0.64) : (direction === 'back' ? 1.05 : 0.95);
  const out = dampToward(source, rest, crouching ? 0.18 : running ? 0.4 : 0.32,
    length / source.animation_length);
  out.loop = true;
  out.animation_length = length;

  // Legs are children of root, while upper_body inherits lower_body. Hip yaw
  // belongs here and on the thighs; rotating root would also turn the face.
  sampleBone(out, bones.root, ROT, () => [0, 0, 0]);
  sampleBone(out, bones.root, POS, p => [0,
    (crouching ? -3.2 : -0.3) + (stationary ? 0.08 : crouching ? 0.13 : running ? 0.48 : 0.24)
      * Math.cos(p * Math.PI * 4), 0]);
  sampleBone(out, bones.lowerBody, ROT, p => [crouching ? 10 : running ? 9 : 4,
    side * (running ? 18 : 14) + (stationary ? 0 : 2 * Math.sin(p * Math.PI * 2)),
    stationary ? 0 : (lateral ? -side * 3 : 1.5) * Math.sin(p * Math.PI * 2)]);
  sampleBone(out, bones.lowerBody, POS, () => [0, 0, crouching ? 0.6 : 0]);

  for (let index = 0; index < bones.legs.length; index++) {
    const legSide = index === 0 ? -1 : 1;
    const footPhase = p => stepPhase(p + (index === (side > 0 ? 1 : 0) ? 0 : 0.5));
    const thigh = p => {
      const { stride, lift } = stationary ? { stride: 0, lift: 0 } : footPhase(p);
      const amplitude = crouching ? 14 : running ? 34 : 24;
      return [(crouching ? -55 : -5) - forward * stride * amplitude
        - lift * (crouching ? 6 : running ? 12 : 8),
      side * (running ? 18 : 14),
      legSide * 2 - side * stride * (crouching ? 11 : running ? 24 : 17)];
    };
    const knee = p => {
      const { lift } = stationary ? { lift: 0 } : footPhase(p);
      return (crouching ? 88 : 10) + lift * (crouching ? 17 : running ? 52 : 30);
    };
    // Replace all inherited lower channels, including any source translations.
    out.bones[bones.legs[index]] = {};
    out.bones[bones.lowerLegs[index]] = {};
    out.bones[bones.feet[index]] = {};
    sampleBone(out, bones.legs[index], ROT, thigh);
    sampleBone(out, bones.lowerLegs[index], ROT, p => [knee(p), 0, 0]);
    sampleBone(out, bones.feet[index], ROT, p => [-thigh(p)[0] - knee(p), 0, -thigh(p)[2]]);
  }
  return out;
}

function airborneCycle(rest, bones, descending) {
  const out = posedClip(rest, descending ? fallOffsets(bones) : jumpOffsets(bones),
    { length: descending ? 0.9 : 0.72, loop: true });
  const wave = p => Math.sin(p * Math.PI * 2);
  sampleBone(out, bones.root, ROT, () => [0, 0, 0]);
  sampleBone(out, bones.root, POS, p => [0, 0.18 * wave(p), 0]);
  sampleBone(out, bones.lowerBody, ROT, p => [descending ? 3 : -3, 1.5 * wave(p), 0]);
  for (let index = 0; index < bones.legs.length; index++) {
    const side = index === 0 ? 1 : -1;
    const thigh = p => (descending ? (index === 0 ? -14 : 12) : (index === 0 ? -28 : -12))
      + side * (descending ? 8 : 6) * wave(p);
    const knee = p => (descending ? 24 : (index === 0 ? 38 : 23)) - side * 7 * wave(p);
    sampleBone(out, bones.legs[index], ROT, p => [thigh(p), side * 3, side * 2]);
    sampleBone(out, bones.lowerLegs[index], ROT, p => [knee(p), 0, 0]);
    sampleBone(out, bones.feet[index], ROT, p => [-8 - 3 * side * wave(p), 0, 0]);
  }
  return out;
}

function hurtOffsets(b) {
  const o = {};
  o[b.root] = { position: [0, 0, -1.2] };
  o[b.lowerBody] = { rotation: [-10, 0, 0] };
  o[b.upperBody] = { rotation: [-16, 0, 6] };
  o[b.head] = { rotation: [-14, 0, 8] };
  for (const arm of b.armsUpper) o[arm] = { rotation: [-12, 0, 0] };
  return o;
}

/**
 * @param fileId    animation file to rewrite in place
 * @param poses     generate the crouch/jump/fall/hurt poses (third-person rigs only;
 *                  the first-person viewmodel has no legs or torso to pose)
 */
function generate(fileId, { poses }) {
  const file = path.join(ANIM_DIR, `${fileId}.animation.json`);
  const doc = JSON.parse(fs.readFileSync(file, 'utf8'));
  const anims = doc.animations;

  const idle = anims.static_idle;
  if (!idle) throw new Error(`${fileId}: static_idle is required as the rest pose`);
  for (const required of ['run_start', 'run_hold', 'run_end']) {
    if (!anims[required]) throw new Error(`${fileId}: missing authored ${required}`);
    if (anims[required][GENERATED]) {
      throw new Error(`${fileId}: ${required} is generated; cannot derive walk from it`);
    }
  }

  const written = [];

  // walk cycle: damped + stretched run cycle
  for (const [target, source] of [
    ['walk_start', 'run_start'],
    ['walk_hold', 'run_hold'],
    ['walk_end', 'run_end'],
  ]) {
    const clip = dampToward(anims[source], idle, WALK_WEIGHT, WALK_TIME);
    // preserve the source's loop semantics; only run_hold cycles
    clip.loop = source === 'run_hold' ? true : false;
    anims[target] = clip;
    written.push(`${target} (${clip.animation_length}s, from ${source})`);
  }

  if (poses) {
    const bones = BONE_SETS[fileId];
    if (!bones) throw new Error(`no bone set configured for ${fileId}`);

    anims.crouch_idle = groundCycle(idle, idle, bones, { crouching: true, stationary: true });
    written.push('crouch_idle (1.6s, loop)');

    anims.jump = airborneCycle(idle, bones, false);
    written.push('jump (0.72s, loop)');

    anims.jump_start = pulseClip(idle, jumpStartOffsets(bones), { length: 0.28, peakAt: 0.55 });
    written.push('jump_start (0.28s, takeoff)');

    anims.fall = airborneCycle(idle, bones, true);
    written.push('fall (0.9s, loop)');

    anims.hurt = pulseClip(idle, hurtOffsets(bones), { length: 0.35, peakAt: 0.32 });
    written.push('hurt (0.35s, one-shot)');

    // Upper-body-only guard/charge stances, so the action layer leaves the legs to
    // the locomotion layer and the character can guard while walking.
    const lower = new Set([
      bones.root, bones.lowerBody,
      ...bones.legs, ...bones.lowerLegs,
      ...(bones.feet ?? []),
    ]);
    for (const [target, source] of [
      ['defend_upper', DEFEND_CLIP[fileId]],
      ['aiming_upper', 'aiming'],
      ['hurt_upper', 'hurt'],
    ]) {
      if (!anims[source]) throw new Error(`${fileId}: missing ${source} for ${target}`);
      anims[target] = upperBodyOnly(anims[source], lower);
      // keep the source's hold/one-shot semantics
      anims[target].loop = anims[source].loop;
      written.push(`${target} (upper body of ${source})`);
    }
    const idleUpper = upperBodyOnly(idle, lower);
    idleUpper.loop = true;
    anims.idle_upper = idleUpper;
    anims.defend_end = blendToRest(anims.defend_upper, idleUpper, 0.22);
    written.push('idle_upper (loop)', 'defend_end (0.22s guard recover)');

    for (const direction of ['forward', 'left', 'right', 'back']) {
      const suffix = direction === 'forward' ? '' : `_${direction}`;
      anims[`walk_hold${suffix}`] = groundCycle(anims.run_hold, idle, bones, { direction });
      anims[`crouch_walk${suffix}`] = groundCycle(anims.run_hold, idle, bones,
        { direction, crouching: true });
      if (direction !== 'forward') {
        anims[`run_hold${suffix}`] = groundCycle(anims.run_hold, idle, bones,
          { direction, running: true });
      }
    }
    written.push('independent walk/run/crouch directions');

    const overlaySources = OVERLAY_CLIPS[fileId] ?? [];
    for (const name of overlaySources) {
      if (!anims[name]) throw new Error(`${fileId}: missing ${name} for upper overlay`);
      const overlay = `${name}_upper`;
      anims[overlay] = upperBodyOnly(anims[name], lower);
      anims[overlay].loop = false;
      written.push(`${overlay} (upper body of ${name})`);
    }
  }

  fs.writeFileSync(file, JSON.stringify(doc, null, 1) + '\n');
  console.log(`ok ${fileId}: ${written.length} clips generated`);
  for (const w of written) console.log(`   - ${w}`);
}

generate('third_person_singlesword', { poses: true });
generate('third_person_doublesword', { poses: true });
// fp_doublesword ships no run cycle at all, so it gets no generated walk either;
// the controller falls back to its idle for that rig.
generate('fp_singlesword', { poses: false });
console.log('generate_locomotion done');
