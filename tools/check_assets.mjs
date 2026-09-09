#!/usr/bin/env node
// Asset regression check, runs before processResources in build.gradle.
// Parses WeaponCategory.java (the single source of truth for asset references),
// then verifies every referenced model/animation/clip actually ships and that
// one-shot clips stay non-looping. Exits non-zero on any mismatch.
// Run: node tools/check_assets.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ASSETS = path.join(ROOT, 'src/main/resources/assets/actioncombat');
const MODID = 'actioncombat';

let failures = 0;
function fail(msg) {
  console.error('FAIL: ' + msg);
  failures++;
}

// ---------- parse WeaponCategory.java ----------
const catSrc = fs.readFileSync(
  path.join(ROOT, 'src/main/java/com/actioncombat/combat/WeaponCategory.java'), 'utf8');

function enumConstantBlock(name) {
  const start = catSrc.indexOf(name + '(');
  if (start < 0) throw new Error(`enum constant ${name} not found`);
  let depth = 0;
  let i = catSrc.indexOf('(', start);
  for (; i < catSrc.length; i++) {
    if (catSrc[i] === '(') depth++;
    else if (catSrc[i] === ')') { depth--; if (depth === 0) break; }
  }
  return catSrc.slice(start + name.length + 1, i);
}

function quotedStrings(block) {
  return [...block.matchAll(/"([^"]+)"/g)].map(m => m[1]);
}

function floatArrays(block) {
  return [...block.matchAll(/new float\[\]\{([^}]*)\}/g)]
    .map(m => m[1].split(',').map(s => parseFloat(s.trim())));
}

/**
 * Each side of the alternating combo is a `new SwingChain(new String[]{...}, ...)`.
 * Parsed separately from the stance clips so the two chains stay distinguishable.
 */
function parseSwingChains(block) {
  return [...block.matchAll(/new SwingChain\(/g)].map(m => {
    let depth = 0;
    let i = block.indexOf('(', m.index);
    let j = i;
    for (; j < block.length; j++) {
      if (block[j] === '(') depth++;
      else if (block[j] === ')') { depth--; if (depth === 0) break; }
    }
    const inner = block.slice(i + 1, j);
    const arrays = floatArrays(inner);
    return {
      clips: quotedStrings(inner),
      durations: arrays[0] ?? [],
      damages: arrays[1] ?? [],
    };
  });
}

// Field order: modelId, animationsId, idle, inspect, runStart, runHold, runEnd,
//              draw, putAway, defend, aiming,
//              walkStart, walkHold, walkEnd, crouchIdle, jump, fall, hurt,
//              SwingChain left, SwingChain right
const STANCE_COUNT = 18; // modelId..hurt
function parseCategory(name) {
  const block = enumConstantBlock(name);
  const chains = parseSwingChains(block);
  // strings inside the SwingChain(...) calls must not be counted as stance clips
  const chainStrings = new Set(chains.flatMap(c => c.clips));
  const firstChainAt = block.indexOf('new SwingChain(');
  const stanceBlock = firstChainAt >= 0 ? block.slice(0, firstChainAt) : block;
  const strings = quotedStrings(stanceBlock);

  if (strings.length !== STANCE_COUNT) {
    fail(`${name}: expected ${STANCE_COUNT} stance string literals, found ${strings.length}`);
  }
  if (chains.length !== 2) {
    fail(`${name}: expected 2 SwingChain literals (left, right), found ${chains.length}`);
  }
  void chainStrings;
  return {
    modelId: strings[0],
    animationsId: strings[1],
    clips: strings.slice(2, 11),       // idle..aiming
    locomotion: strings.slice(11, 18), // walkStart..hurt
    chains,
    attacks: [...chains.flatMap(c => c.clips), 'attack_dual'],
  };
}

const categories = ['SINGLE', 'DOUBLE', 'KATANA', 'SPEAR'].map(parseCategory);

// clips that must NOT loop (the state machine needs them to end)
const ONE_SHOT = new Set(['draw', 'put_away', 'run_start', 'run_end', 'walk_start', 'walk_end']);
// clips that must loop
const LOOPING = new Set([
  'static_idle', 'inspect', 'run_hold', 'walk_hold', 'crouch_idle', 'fall',
  'walk_hold_left', 'walk_hold_right', 'walk_hold_back',
  'run_hold_left', 'run_hold_right', 'run_hold_back',
]);
// clip field index -> role, matching the enum literal order
const CLIP_ROLES = [
  'idle', 'inspect', 'runStart', 'runHold', 'runEnd', 'draw', 'putAway', 'defend', 'aiming',
];

for (const cat of categories) {
  const label = `${cat.modelId} (${cat.animationsId})`;

  // ----- geo -----
  const geoPath = path.join(ASSETS, 'geckolib/models', `${cat.modelId}.geo.json`);
  if (!fs.existsSync(geoPath)) {
    fail(`${label}: missing geckolib/models/${cat.modelId}.geo.json`);
    continue;
  }
  const geo = JSON.parse(fs.readFileSync(geoPath, 'utf8'))['minecraft:geometry'][0];
  const expectedId = `geometry.${MODID}.${cat.modelId}`;
  if (geo.description.identifier !== expectedId) {
    fail(`${label}: geo identifier is ${geo.description.identifier}, expected ${expectedId}`);
  }
  const boneNames = new Set(geo.bones.map(b => b.name));
  if (!boneNames.has('hand_item_r')) fail(`${label}: geo has no 'hand_item_r' bone`);

  // ----- animations -----
  const animPath = path.join(ASSETS, 'geckolib/animations', `${cat.animationsId}.animation.json`);
  if (!fs.existsSync(animPath)) {
    fail(`${label}: missing geckolib/animations/${cat.animationsId}.animation.json`);
    continue;
  }
  const anims = JSON.parse(fs.readFileSync(animPath, 'utf8')).animations;

  const allNames = [...cat.clips, ...cat.locomotion, ...cat.attacks];
  for (const name of allNames) {
    if (!anims[name]) fail(`${label}: animation '${name}' missing from ${cat.animationsId}`);
  }
  for (const extra of [
    'walk_hold_left', 'walk_hold_right', 'walk_hold_back',
    'run_hold_left', 'run_hold_right', 'run_hold_back', 'jump_start',
  ]) {
    if (!anims[extra]) fail(`${label}: locomotion clip '${extra}' missing`);
  }
  if (!anims.put_away) fail(`${label}: generated 'put_away' clip missing`);

  cat.clips.forEach((name, idx) => {
    if (!anims[name]) return;
    const loop = anims[name].loop;
    const role = CLIP_ROLES[idx];
    const oneShot = ONE_SHOT.has(name) || role === 'runStart';
    const looping = LOOPING.has(name);
    if (oneShot && loop !== false) {
      fail(`${label}: one-shot '${name}' has loop=${JSON.stringify(loop)}, expected false`);
    } else if (looping && loop !== true) {
      fail(`${label}: looping clip '${name}' has loop=${JSON.stringify(loop)}, expected true`);
    }
  });
  // generated locomotion clips: walk cycle mirrors the run cycle's semantics
  for (const name of cat.locomotion) {
    if (!anims[name]) continue;
    const loop = anims[name].loop;
    if (ONE_SHOT.has(name) && loop !== false) {
      fail(`${label}: one-shot '${name}' has loop=${JSON.stringify(loop)}, expected false`);
    } else if (LOOPING.has(name) && loop !== true) {
      fail(`${label}: looping clip '${name}' has loop=${JSON.stringify(loop)}, expected true`);
    }
  }
  for (const name of cat.attacks) {
    if (anims[name] && anims[name].loop !== false) {
      fail(`${label}: one-shot attack '${name}' has loop=${JSON.stringify(anims[name].loop)}, expected false`);
    }
    const overlay = `${name}_upper`;
    if (!anims[overlay]) {
      fail(`${label}: upper overlay '${overlay}' missing`);
    } else if (anims[overlay].loop !== false) {
      fail(`${label}: overlay '${overlay}' has loop=${JSON.stringify(anims[overlay].loop)}, expected false`);
    }
  }

  // the two chains must alternate cleanly: no clip may appear on both sides
  const [left, right] = cat.chains;
  for (const chain of cat.chains) {
    if (chain.clips.length !== chain.durations.length
        || chain.durations.length !== chain.damages.length) {
      fail(`${label}: chain clips(${chain.clips.length})/durations(${chain.durations.length})`
        + `/damages(${chain.damages.length}) mismatch`);
    }
  }
  if (left && right) {
    const overlap = left.clips.filter(c => right.clips.includes(c));
    if (overlap.length) {
      fail(`${label}: clip(s) ${overlap.join(', ')} appear in both swing chains`);
    }
    if (left.clips.length === 0 || right.clips.length === 0) {
      fail(`${label}: both swing chains must have at least one clip`);
    }
  }

  // every upper overlay must omit hips/legs, or it would fight the locomotion layer
  const LOWER_BODY = ['root', 'lower_body', 'leg_r', 'leg_l',
    'lower_leg_r', 'lower_leg_l', 'foot_r', 'foot_l'];
  for (const name of Object.keys(anims)) {
    if (!name.endsWith('_upper')) continue;
    const bones = Object.keys(anims[name].bones ?? {});
    const leaked = bones.filter(b => LOWER_BODY.includes(b));
    if (leaked.length) {
      fail(`${label}: layered clip '${name}' animates lower-body bone(s) ${leaked.join(', ')}`
        + ' (would fight the locomotion layer)');
    }
  }

  // anim bones should exist in the geo (unknown bones silently do nothing)
  const animBones = new Set();
  for (const anim of Object.values(anims)) {
    for (const bone of Object.keys(anim?.bones ?? {})) animBones.add(bone);
  }
  for (const bone of animBones) {
    if (!boneNames.has(bone)) console.warn(`WARN: ${label}: animation bone '${bone}' not in geo (ignored at runtime)`);
  }

  console.log(`ok ${label}: ${allNames.length} clips referenced, ${geo.bones.length} geo bones`);
}

// ----- item texture -----
const texPath = path.join(ASSETS, 'textures/item/longsword.png');
if (!fs.existsSync(texPath)) {
  fail('missing textures/item/longsword.png');
} else {
  console.log('ok textures/item/longsword.png');
}

// ---------- parse FpWeaponClips.java (first-person viewmodel clip sets) ----------
// Args are positional (nulls mean "clip absent from this rig"), so split the
// `new Clips(...)` argument lists on depth-0 commas.
const fpSrc = fs.readFileSync(
  path.join(ROOT, 'src/main/java/com/actioncombat/combat/FpWeaponClips.java'), 'utf8');

function splitTopLevelCommas(block) {
  const args = [];
  let depth = 0, cur = '';
  for (const ch of block) {
    if (ch === '(' || ch === '{' || ch === '[') depth++;
    else if (ch === ')' || ch === '}' || ch === ']') depth--;
    if (ch === ',' && depth === 0) { args.push(cur.trim()); cur = ''; }
    else cur += ch;
  }
  if (cur.trim()) args.push(cur.trim());
  return args;
}

function fpClipArgs(startIdx) {
  let i = fpSrc.indexOf('(', startIdx);
  let depth = 0, j = i;
  for (; j < fpSrc.length; j++) {
    if (fpSrc[j] === '(') depth++;
    else if (fpSrc[j] === ')') { depth--; if (depth === 0) break; }
  }
  return splitTopLevelCommas(fpSrc.slice(i + 1, j));
}

/**
 * Bone names BladeVfx drives, read out of the Java so the two can never disagree.
 * Java is the source of truth for every name this checker validates.
 */
function vfxBoneNames() {
  const src = fs.readFileSync(
    path.join(ROOT, 'src/main/java/com/actioncombat/client/BladeVfx.java'), 'utf8');
  const names = [];
  const arrayBlock = /private static final String\[\] (?:TRAIL_BONES|SPARK_BONES) = \{([^}]*)\}/g;
  for (const m of src.matchAll(arrayBlock)) {
    for (const raw of m[1].split(',')) {
      const name = raw.trim().replace(/^"|"$/g, '');
      if (name) names.push(name);
    }
  }
  const rootMatch = src.match(/private static final String SPARK_ROOT = "([^"]+)"/);
  if (rootMatch) names.push(rootMatch[1]);
  if (names.length === 0) fail('BladeVfx.java: could not parse any VFX bone names');
  return names;
}

function fpArgValue(arg) {
  if (arg === 'null') return null;
  if (arg.startsWith('new String[]')) {
    return [...arg.matchAll(/"([^"]+)"/g)].map(m => m[1]);
  }
  if (arg.startsWith('new float[]')) {
    return [...arg.matchAll(/-?[\d.]+/g)].map(m => parseFloat(m[0]));
  }
  const str = arg.match(/^"([^"]+)"$/);
  if (str) return str[1];
  if (/^-?[\d.]+F?$/.test(arg)) return parseFloat(arg);
  throw new Error('unparsed FpWeaponClips arg: ' + arg);
}

// Field order: modelId, animationsId, idle, inspect, runStart, runHold, runEnd,
//              walkStart, walkHold, walkEnd, draw, putAway, defend, aiming,
//              leftAttacks, rightAttacks, drawSeconds, leftAttackSeconds, rightAttackSeconds
const FP_CLIP_ROLES = [
  'modelId', 'animationsId', 'idle', 'inspect', 'runStart', 'runHold', 'runEnd',
  'walkStart', 'walkHold', 'walkEnd', 'draw', 'putAway', 'defend', 'aiming',
  'leftAttacks', 'rightAttacks',
];
const FP_ONE_SHOT = new Set(['draw', 'put_away', 'run_start', 'run_end',
  'walk_start', 'walk_end']);
const FP_LOOPING = new Set(['static_idle', 'inspect', 'run_hold', 'walk_hold']);
const FP_HOLD = new Set(['static_pose']);

const fpClips = [...fpSrc.matchAll(/new Clips\(/g)].map(m => {
  const args = fpClipArgs(m.index).map(fpArgValue);
  const fp = {};
  FP_CLIP_ROLES.forEach((role, i) => { fp[role] = args[i]; });
  fp.attacks = [...(fp.leftAttacks ?? []), ...(fp.rightAttacks ?? []), 'attack_dual'];
  fp.drawSeconds = args[FP_CLIP_ROLES.length];
  fp.leftAttackSeconds = args[FP_CLIP_ROLES.length + 1];
  fp.rightAttackSeconds = args[FP_CLIP_ROLES.length + 2];
  return fp;
});

if (fpClips.length !== 4) fail(`FpWeaponClips: expected 4 Clips instances, found ${fpClips.length}`);

// bones that must exist so the viewmodel actually carries the blade/arms
const FP_BONES = {
  fp_singlesword: ['root', 'knife_R', 'right', 'left', 'elucidator'],
  fp_doublesword: ['right_arm', 'left_arm', 'right_arm_item', 'left_arm_item'],
};

for (const fp of fpClips) {
  const label = `fp ${fp.modelId} (${fp.animationsId})`;

  const geoPath = path.join(ASSETS, 'geckolib/models', `${fp.modelId}.geo.json`);
  if (!fs.existsSync(geoPath)) {
    fail(`${label}: missing geckolib/models/${fp.modelId}.geo.json`);
    continue;
  }
  const geo = JSON.parse(fs.readFileSync(geoPath, 'utf8'))['minecraft:geometry'][0];
  const expectedId = `geometry.${MODID}.${fp.modelId}`;
  if (geo.description.identifier !== expectedId) {
    fail(`${label}: geo identifier is ${geo.description.identifier}, expected ${expectedId}`);
  }
  const boneNames = new Set(geo.bones.map(b => b.name));
  for (const bone of FP_BONES[fp.modelId] ?? []) {
    if (!boneNames.has(bone)) fail(`${label}: geo has no '${bone}' bone`);
  }

  const animPath = path.join(ASSETS, 'geckolib/animations', `${fp.animationsId}.animation.json`);
  if (!fs.existsSync(animPath)) {
    fail(`${label}: missing geckolib/animations/${fp.animationsId}.animation.json`);
    continue;
  }
  const anims = JSON.parse(fs.readFileSync(animPath, 'utf8')).animations;

  const fpStances = [fp.idle, fp.inspect, fp.runStart, fp.runHold, fp.runEnd,
    fp.walkStart, fp.walkHold, fp.walkEnd, fp.draw, fp.putAway, fp.defend, fp.aiming];
  for (const clip of fpStances) {
    if (clip && !anims[clip]) fail(`${label}: clip '${clip}' missing from ${fp.animationsId}`);
  }
  if (!anims.static_pose) fail(`${label}: 'static_pose' clip missing from ${fp.animationsId}`);
  for (const attack of fp.attacks) {
    if (!anims[attack]) fail(`${label}: attack clip '${attack}' missing from ${fp.animationsId}`);
  }
  if ((fp.leftAttacks ?? []).length !== 2 || (fp.rightAttacks ?? []).length !== 2) {
    fail(`${label}: expected 2 left and 2 right attack clips`);
  }
  if (fp.leftAttacks?.[0] && fp.rightAttacks?.[0] && fp.leftAttacks[0] === fp.rightAttacks[0]) {
    fail(`${label}: left and right swings share clip '${fp.leftAttacks[0]}'`);
  }

  for (const clip of [...fpStances, 'static_pose', ...fp.attacks]) {
    if (!clip || !anims[clip]) continue;
    const loop = anims[clip].loop;
    const role = FP_CLIP_ROLES.find(r => fp[r] === clip);
    if (FP_ONE_SHOT.has(clip) || fp.attacks.includes(clip) || role === 'draw'
        || role === 'runStart' || role === 'runEnd'
        || role === 'walkStart' || role === 'walkEnd') {
      if (loop !== false) fail(`${label}: one-shot '${clip}' has loop=${JSON.stringify(loop)}, expected false`);
    } else if (FP_LOOPING.has(clip) || role === 'runHold' || role === 'walkHold') {
      if (loop !== true) fail(`${label}: looping clip '${clip}' has loop=${JSON.stringify(loop)}, expected true`);
    } else if (FP_HOLD.has(clip) || role === 'defend' || role === 'aiming') {
      if (loop !== 'hold_on_last_frame') fail(`${label}: hold clip '${clip}' has loop=${JSON.stringify(loop)}, expected hold_on_last_frame`);
    }
  }

  if (!(fp.drawSeconds > 0)) fail(`${label}: drawSeconds must be positive`);

  // The controller retimes FP attack clips onto the authoritative swing using these
  // lengths, so a stale value would desync the viewmodel from the third-person body.
  for (const [clipsRole, secondsRole] of [
    ['leftAttacks', 'leftAttackSeconds'],
    ['rightAttacks', 'rightAttackSeconds'],
  ]) {
    const clips = fp[clipsRole] ?? [];
    const declared = fp[secondsRole] ?? [];
    if (clips.length !== declared.length) {
      fail(`${label}: ${clipsRole}(${clips.length}) / ${secondsRole}(${declared.length}) mismatch`);
      continue;
    }
    clips.forEach((clip, i) => {
      if (!clip || !anims[clip]) return;
      const actual = anims[clip].animation_length;
      if (!(declared[i] > 0)) {
        fail(`${label}: ${secondsRole}[${i}] must be positive`);
      } else if (Math.abs(declared[i] - actual) > 0.001) {
        fail(`${label}: ${secondsRole}[${i}]=${declared[i]} but '${clip}' is ${actual}s long`);
      }
    });
  }
  if (fp.drawSeconds > 0 && anims[fp.draw]) {
    const actualDraw = anims[fp.draw].animation_length;
    if (Math.abs(fp.drawSeconds - actualDraw) > 0.001) {
      fail(`${label}: drawSeconds=${fp.drawSeconds} but '${fp.draw}' is ${actualDraw}s long`);
    }
  }
  // BladeVfx poses these bones by name every frame. A rename in the geo would silently
  // stop the trail and sparks from ever showing, so the names are pinned here.
  const vfxBones = vfxBoneNames();
  const geoBoneNames = new Set(geo.bones.map(b => b.name));
  const presentVfx = vfxBones.filter(name => geoBoneNames.has(name));
  if (presentVfx.length > 0 && presentVfx.length !== vfxBones.length) {
    fail(`${label}: rig has some BladeVfx bones but is missing ` +
      vfxBones.filter(name => !geoBoneNames.has(name)).join(', '));
  }
  const vfxNote = presentVfx.length === vfxBones.length
    ? `, ${presentVfx.length} vfx bones`
    : '';

  console.log(`ok ${label}: ${fp.attacks.length} attacks, ${geo.bones.length} geo bones, draw=${fp.drawSeconds}s${vfxNote}`);
}

if (failures > 0) {
  console.error(`check_assets FAILED: ${failures} problem(s)`);
  process.exit(1);
}
console.log('check_assets PASSED');
