#!/usr/bin/env node
// The dual viewmodel is the third-person arm hierarchy with the body hidden.
// Copy its actual clips and pivots so hand, direction and timing stay identical.
// The legacy single-sword viewmodel retains its authored swing conversion.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ANIM_DIR = path.join(ROOT, 'src/main/resources/assets/actioncombat/geckolib/animations');
const MODEL_DIR = path.join(ROOT, 'src/main/resources/assets/actioncombat/geckolib/models');



function round(v, digits = 5) {
  const p = 10 ** digits;
  return Math.round(v * p) / p;
}

function timeKey(seconds) {
  const v = round(seconds, 4);
  return Number.isInteger(v) ? v.toFixed(1) : v.toString();
}

function mapVector(vec, fn) {
  if (!Array.isArray(vec) || vec.length < 3) return vec;
  return fn(vec.map(n => (typeof n === 'number' && Number.isFinite(n) ? n : 0)));
}

function mapKeyframe(node, fn) {
  if (Array.isArray(node)) return mapVector(node, fn);
  if (node && typeof node === 'object') {
    const out = { ...node };
    if (out.post) {
      out.post = Array.isArray(out.post)
        ? mapVector(out.post, fn)
        : (out.post.vector ? { ...out.post, vector: mapVector(out.post.vector, fn) } : out.post);
    }
    if (out.pre) out.pre = mapVector(out.pre, fn);
    if (out.vector) out.vector = mapVector(out.vector, fn);
    return out;
  }
  return node;
}

function remapChannel(channel, oldLen, newLen, mapper) {
  if (!channel || Array.isArray(channel)) return channel;
  const out = {};
  for (const [key, value] of Object.entries(channel)) {
    const t = parseFloat(key);
    if (!Number.isFinite(t)) {
      out[key] = value;
      continue;
    }
    const nt = round((t / oldLen) * newLen, 5);
    out[timeKey(nt)] = mapper ? mapKeyframe(value, mapper) : value;
  }
  return out;
}

function transformClip(src, newLen, { swap = null, mirror = false } = {}) {
  const oldLen = src.animation_length || 1;
  const bones = {};
  const mirrorPos = vec => [round(-vec[0], 4), vec[1], vec[2]];
  const mirrorRot = vec => [vec[0], round(-vec[1], 4), round(-vec[2], 4)];
  for (const [name, channels] of Object.entries(src.bones ?? {})) {
    const dest = swap?.[name] ?? name;
    const next = { ...channels };
    const rotMap = mirror ? mirrorRot : null;
    const posMap = mirror ? mirrorPos : null;
    if (channels.rotation) {
      next.rotation = remapChannel(channels.rotation, oldLen, newLen, rotMap);
    }
    if (channels.position) {
      next.position = remapChannel(channels.position, oldLen, newLen, posMap);
    }
    if (channels.scale) {
      next.scale = remapChannel(channels.scale, oldLen, newLen, null);
    }
    bones[dest] = next;
  }
  return {
    loop: false,
    animation_length: round(newLen, 5),
    bones,
  };
}

const DUAL_BONES = {
  root: 'root',
  lower_body: 'lower_body',
  upper_body: 'upper_body',
  upper_arm_r: 'right_arm',
  forearm_r: 'right_sleve_bottom',
  hand_r: 'hand_r',
  hand_item_r: 'right_arm_item',
  upper_arm_l: 'left_arm',
  forearm_l: 'left_arm_bottom',
  hand_l: 'hand_l',
  hand_item_l: 'left_arm_item',
};

// FpWeaponClips indexes each direction by hand, matching WeaponCategory.DOUBLE.
const DUAL_ATTACK_SOURCES = {
  attack1: 'attack_1',
  attack2: 'attack2',
  attack3: 'attack3',
  attack4: 'attack4',
  attack_dual: 'attack_dual',
};

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function writeJson(file, value) {
  fs.writeFileSync(file, JSON.stringify(value, null, 1) + '\n');
}

function armUv(cube, left, sleeve) {
  const u = left ? (sleeve ? 48 : 32) : 40;
  const v = left ? 48 : (sleeve ? 32 : 16);
  const offset = 24 - cube.origin[1] - cube.size[1];
  const height = cube.size[1];
  return {
    north: { uv: [u + 4, v + 4 + offset], uv_size: [4, height] },
    east: { uv: [u, v + 4 + offset], uv_size: [4, height] },
    south: { uv: [u + 12, v + 4 + offset], uv_size: [4, height] },
    west: { uv: [u + 8, v + 4 + offset], uv_size: [4, height] },
    up: { uv: [u + 4, v], uv_size: [4, 4] },
    down: { uv: [u + 8, v + 4], uv_size: [4, -4] },
  };
}

function generateDualViewmodel() {
  const sourceGeo = readJson(path.join(MODEL_DIR, 'player_doublesword.geo.json'))['minecraft:geometry'][0];
  const modelPath = path.join(MODEL_DIR, 'fp_doublesword.geo.json');
  const bones = sourceGeo.bones.filter(bone => Object.hasOwn(DUAL_BONES, bone.name)).map(sourceBone => {
    const bone = structuredClone(sourceBone);
    bone.name = DUAL_BONES[sourceBone.name];
    if (sourceBone.parent) bone.parent = DUAL_BONES[sourceBone.parent];
    const armMesh = /^(forearm|hand)_[rl]$/.test(sourceBone.name);
    if (armMesh) {
      const left = sourceBone.name.endsWith('_l');
      bone.cubes = sourceBone.cubes.flatMap(sourceCube => {
        const cube = structuredClone(sourceCube);
        delete cube.mirror;
        cube.uv = armUv(cube, left, false);
        const sleeve = { ...structuredClone(cube), inflate: 0.25, uv: armUv(cube, left, true) };
        return [cube, sleeve];
      });
    } else {
      // Ancestor transforms stay in the rig, while held ItemStacks supply blades.
      delete bone.cubes;
    }
    return bone;
  });
  // Each upper arm joins the animated elbow to an off-camera shoulder anchor.
  // The source shoulder bones remain transform-only parents of forearm and hand.
  for (const side of ['r', 'l']) {
    const sourceCube = sourceGeo.bones.find(bone => bone.name === `upper_arm_${side}`).cubes[0];
    bones.push({
      name: `fp_upper_arm_${side}`,
      pivot: [0, 0, 0],
      cubes: [
        { origin: [-2, 0, -2], size: [4, 1, 4], uv: armUv(sourceCube, side === 'l', false) },
        { origin: [-2.25, 0, -2.25], size: [4.5, 1, 4.5], uv: armUv(sourceCube, side === 'l', true) },
      ],
    });
  }
  writeJson(modelPath, {
    format_version: '1.12.0',
    'minecraft:geometry': [{
      description: { ...sourceGeo.description, identifier: 'geometry.actioncombat.fp_doublesword' },
      bones,
    }],
  });

  const source = readJson(path.join(ANIM_DIR, 'third_person_doublesword.animation.json'));
  const sources = {};
  for (const name of Object.keys(source.animations)) {
    if (!name.startsWith('attack')) sources[name] = name;
  }
  for (const [name, sourceName] of Object.entries(DUAL_ATTACK_SOURCES)) {
    sources[name] = sourceName;
    sources[`${name}_upper`] = `${sourceName}_upper`;
  }
  sources.static_pose = 'static_idle';
  sources.defend_1 = 'defend_upper';

  const animations = {};
  for (const [name, sourceName] of Object.entries(sources)) {
    const sourceClip = source.animations[sourceName];
    if (!sourceClip) throw new Error(`third_person_doublesword: missing ${sourceName}`);
    const clip = structuredClone(sourceClip);
    clip.bones = Object.fromEntries(Object.entries(clip.bones ?? {})
      .filter(([bone]) => Object.hasOwn(DUAL_BONES, bone))
      .map(([bone, channels]) => [DUAL_BONES[bone], channels]));
    if (name === 'static_pose' || name === 'defend_1') clip.loop = 'hold_on_last_frame';
    animations[name] = clip;
  }
  writeJson(path.join(ANIM_DIR, 'fp_doublesword.animation.json'), {
    format_version: source.format_version,
    actioncombat_fp_sources: {
      model: 'player_doublesword.geo.json',
      animations: 'third_person_doublesword.animation.json',
      bones: DUAL_BONES,
      clips: sources,
    },
    animations,
  });
  console.log(`fp_doublesword: ${bones.length} bones and ${Object.keys(animations).length} clips from third person`);
}

generateDualViewmodel();
console.log('generate_fp_swings done');
