// Asset pipeline for both sword rigs (run: node tools/convert_assets.mjs):
//   - singlesword: bbmodel -> Bedrock geo.json (X-mirrored pivots from the double rig reference)
//   - doublesword: reference geo.json copied with the mod's identifier
//   - both: animation surgery (one-shot loop fixes, put_away rebuilt as time-reversed draw)
//   - item texture exported from the first-person bbmodel (or placeholder)
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const ROOT = path.resolve(import.meta.dirname, '..');
const SRC = path.resolve(ROOT, '../sword_first');
const ASSETS = path.join(ROOT, 'src/main/resources/assets/actioncombat');
const GEO_DIR = path.join(ASSETS, 'geckolib/models');
const ANIM_DIR = path.join(ASSETS, 'geckolib/animations');

// ---------- geo conversion ----------
// The singlesword bbmodel is the double_swords rig mirrored on X (right limbs at +X).
// Bones are unnamed in the bbmodel; each group directly holds one cube whose element
// name is the bone name (the sword cube is "cube" -> rename to hand_item_r).
// Pivots come from the reference geo with x negated.
const SWORD_CUBE_NAME = 'cube';
const SWORD_BONE_NAME = 'hand_item_r';

function convertGeo() {
  const bb = JSON.parse(fs.readFileSync(path.join(SRC, 'sword_single_third.bbmodel'), 'utf8'));
  const ref = JSON.parse(fs.readFileSync(path.join(SRC, 'double_swords_third.geo.json'), 'utf8'))['minecraft:geometry'][0];

  const refPivot = new Map(ref.bones.map(b => [b.name, b.pivot]));
  const elements = new Map(bb.elements.map(e => [e.uuid, e]));

  const bones = [];
  function walk(group, parentName) {
    const childCubes = (group.children ?? []).filter(c => typeof c === 'string');
    const childGroups = (group.children ?? []).filter(c => typeof c === 'object');

    let name;
    if (parentName === null) {
      name = 'root'; // top-most unnamed group
    } else {
      const firstCube = elements.get(childCubes[0]);
      name = firstCube && firstCube.name === SWORD_CUBE_NAME ? SWORD_BONE_NAME : firstCube?.name;
      if (!name) throw new Error('group without cube child cannot be named');
    }

    // mirror pivot x (rig is X-flipped relative to the reference)
    const refp = refPivot.get(name === SWORD_BONE_NAME ? 'hand_item_r' : name);
    if (!refp) throw new Error('no reference pivot for bone ' + name);
    const pivot = [-refp[0], refp[1], refp[2]];

    const bone = { name, parent: parentName ?? undefined, pivot };
    if (name === SWORD_BONE_NAME) bone.parent = parentName; // keep explicit parent
    bone.cubes = childCubes.map(uuid => {
      const e = elements.get(uuid);
      const size = e.to.map((v, i) => v - e.from[i]);
      const uv = e.uv_offset ?? [0, 0];
      return { origin: e.from.slice(), size, uv };
    });
    bones.push(bone);

    for (const g of childGroups) walk(g, name);
  }

  for (const top of bb.outliner.filter(g => typeof g === 'object')) walk(top, null);

  const geo = {
    format_version: '1.12.0',
    'minecraft:geometry': [{
      description: {
        identifier: 'geometry.actioncombat.player_longsword',
        texture_width: bb.resolution.width,
        texture_height: bb.resolution.height,
        visible_bounds_width: 2,
        visible_bounds_height: 3.5,
        visible_bounds_offset: [0, 1.25, 0],
      },
      bones,
    }],
  };

  const out = path.join(GEO_DIR, 'player_longsword.geo.json');
  fs.mkdirSync(GEO_DIR, { recursive: true });
  fs.writeFileSync(out, JSON.stringify(geo, null, 2));
  console.log('geo ->', out, `(${bones.length} bones)`);
}

function convertDoubleGeo() {
  const src = JSON.parse(fs.readFileSync(path.join(SRC, 'double_swords_third.geo.json'), 'utf8'));
  src['minecraft:geometry'][0].description.identifier = 'geometry.actioncombat.player_doublesword';
  const out = path.join(GEO_DIR, 'player_doublesword.geo.json');
  fs.writeFileSync(out, JSON.stringify(src, null, 2));
  console.log('geo ->', out,
    `(${src['minecraft:geometry'][0].bones.length} bones)`);
}

// ---------- animation surgery ----------
function lengthOf(anim) {
  let max = 0;
  for (const bone of Object.values(anim.bones ?? {})) {
    for (const chan of Object.values(bone)) {
      if (!chan || typeof chan !== 'object') continue;
      for (const t of Object.keys(chan)) {
        const v = parseFloat(t);
        if (!Number.isNaN(v)) max = Math.max(max, v);
      }
    }
  }
  return max;
}

function reverseAnim(anim) {
  const L = anim.animation_length;
  const bones = {};
  for (const [boneName, bone] of Object.entries(anim.bones ?? {})) {
    const nb = {};
    for (const [chan, keyframes] of Object.entries(bone)) {
      // constant channel shorthand ([x,y,z] or scalar) is time-invariant: copy as-is
      if (!keyframes || typeof keyframes !== 'object' || Array.isArray(keyframes)) {
        nb[chan] = keyframes;
        continue;
      }
      const nk = {};
      for (const [t, kf] of Object.entries(keyframes)) {
        // some sources carry keyframes beyond animation_length; they never play
        if (parseFloat(t) > L || parseFloat(t) < 0) continue;
        const nt = (L - parseFloat(t)).toFixed(6).replace(/\.?0+$/, '') || '0';
        // GeckoLib 5 only parses object-form keyframes in a channel map; normalize
        // bare scalars/arrays to {post}. Objects swap pre/post so the curve stays
        // continuous when played backwards.
        let swapped;
        if (kf && typeof kf === 'object' && !Array.isArray(kf)) {
          swapped = { ...kf };
          if (kf.pre !== undefined && kf.post !== undefined) {
            swapped.pre = kf.post;
            swapped.post = kf.pre;
          }
        } else {
          swapped = { post: Array.isArray(kf) ? kf : [kf, kf, kf] };
        }
        nk[nt] = swapped;
      }
      nb[chan] = nk;
    }
    bones[boneName] = nb;
  }
  return { ...anim, bones };
}

// One-shot clips must end cleanly so control returns to the idle/movement predicate;
// the sources ship some attacks looping and run_start/run_end as hold_on_last_frame.
function isOneShot(name) {
  return /^attack/.test(name) || ['draw', 'run_start', 'run_end'].includes(name);
}

function surgery(anims, label) {
  for (const [name, anim] of Object.entries(anims)) {
    if (anim && isOneShot(name)) anim.loop = false;
  }
  // put_away ships as a static pose (or is missing entirely): rebuild it as a
  // time-reversed draw so sheathing actually animates.
  const draw = anims.draw;
  if (!draw) throw new Error(label + ': no draw animation to reverse');
  anims.put_away = { ...reverseAnim(draw), loop: false, animation_length: draw.animation_length };
}

function fixAnimations(srcFile, outFile) {
  const file = JSON.parse(fs.readFileSync(path.join(SRC, srcFile), 'utf8'));
  surgery(file.animations, srcFile);
  fs.mkdirSync(ANIM_DIR, { recursive: true });
  const out = path.join(ANIM_DIR, outFile);
  fs.writeFileSync(out, JSON.stringify(file, null, 2));
  console.log('anims ->', out, Object.keys(file.animations).join(', '));
}

// ---------- item texture (from the first-person sword bbmodel, if embedded) ----------
function pngChunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crcTable = [];
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crcTable[n] = c >>> 0; }
  let crc = 0xffffffff;
  for (const b of body) crc = crcTable[(crc ^ b) & 0xff] ^ (crc >>> 8);
  crc = (crc ^ 0xffffffff) >>> 0;
  const crcBuf = Buffer.alloc(4); crcBuf.writeUInt32BE(crc);
  return Buffer.concat([len, body, crcBuf]);
}

function encodePng(width, height, rgba) {
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; ihdr[9] = 6; // 8-bit RGBA
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width * 4 + 1)] = 0;
    rgba.copy(raw, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  }
  const idat = zlib.deflateSync(raw);
  return Buffer.concat([sig, pngChunk('IHDR', ihdr), pngChunk('IDAT', idat), pngChunk('IEND', Buffer.alloc(0))]);
}

function exportItemTexture() {
  const out = path.join(ASSETS, 'textures/item/longsword.png');
  const bbPath = path.join(SRC, 'sword_single.bbmodel');
  if (fs.existsSync(bbPath)) {
    const bb = JSON.parse(fs.readFileSync(bbPath, 'utf8'));
    const tex = (bb.textures ?? []).find(t => t.source?.startsWith('data:image/png;base64,'));
    if (tex) {
      fs.writeFileSync(out, Buffer.from(tex.source.split(',')[1], 'base64'));
      console.log('item texture from bbmodel ->', out, `${tex.width}x${tex.height}`);
      return;
    }
  }
  // fallback: 16x16 placeholder katana so the item model resolves
  const w = 16, h = 16;
  const px = Buffer.alloc(w * h * 4);
  const put = (x, y, r, g, b, a = 255) => {
    const i = (y * w + x) * 4; px[i] = r; px[i + 1] = g; px[i + 2] = b; px[i + 3] = a;
  };
  // diagonal blade from bottom-left to top-right
  for (let i = 0; i < 10; i++) {
    const x = 3 + i, y = 12 - i;
    put(x, y, 210, 215, 225);
    put(x, Math.min(15, y + 1), 150, 155, 170);
  }
  put(3, 13, 90, 60, 30); put(2, 14, 90, 60, 30); // grip
  put(12, 3, 190, 160, 60); // guard accent
  fs.writeFileSync(out, encodePng(w, h, px));
  console.log('item texture placeholder ->', out);
}

// ---------- first-person packs ----------
// FP singlesword rig (sword_single.bbmodel): Tacz-style sword-only viewmodel authored
// directly in Bedrock first-person space -> no X mirroring (unlike the third-person rig).
// Helper groups (camera element, views/*, positioning/*) carry no cubes and no keyframes.
const FP_EXCLUDED_GROUPS = new Set(['camera', 'views', 'positioning']);
const FP_SINGLE_GEO_ID = 'geometry.actioncombat.fp_singlesword';

// The bbmodel ships no blade mesh (elucidator is an animated empty bone); inject a
// placeholder katana (blade + guard + grip) parented to it so the viewmodel reads.
const FP_BLADE_CUBES = [
  { origin: [-0.25, 0, -0.75], size: [0.5, 15, 1.5], uv: {
    north: { uv: [0, 0], uv_size: [16, 48] }, east: { uv: [0, 0], uv_size: [16, 48] },
    south: { uv: [0, 0], uv_size: [16, 48] }, west: { uv: [0, 0], uv_size: [16, 48] },
    up: { uv: [0, 48], uv_size: [16, 16] }, down: { uv: [0, 48], uv_size: [16, 16] } } },
  { origin: [-0.625, -1.25, -1.125], size: [1.25, 1.25, 2.25], uv: {
    north: { uv: [20, 0], uv_size: [8, 8] }, east: { uv: [20, 0], uv_size: [8, 8] },
    south: { uv: [20, 0], uv_size: [8, 8] }, west: { uv: [20, 0], uv_size: [8, 8] },
    up: { uv: [20, 8], uv_size: [8, 8] }, down: { uv: [20, 8], uv_size: [8, 8] } } },
  { origin: [-0.375, -4.25, -0.625], size: [0.75, 3, 1.25], uv: {
    north: { uv: [28, 0], uv_size: [8, 12] }, east: { uv: [28, 0], uv_size: [8, 12] },
    south: { uv: [28, 0], uv_size: [8, 12] }, west: { uv: [28, 0], uv_size: [8, 12] },
    up: { uv: [28, 12], uv_size: [8, 8] }, down: { uv: [28, 12], uv_size: [8, 8] } } },
];

function fpSingleBones(bb) {
  const groupsByUuid = new Map(bb.groups.map(g => [g.uuid, g]));
  const elements = new Map(bb.elements.map(e => [e.uuid, e]));
  const bones = [];
  // Hierarchy + cube membership live on the outliner nodes; names/pivots/rotations
  // live in bb.groups (their own children arrays are empty).
  function walk(node, parentName) {
    const g = groupsByUuid.get(node.uuid);
    if (!g) return;
    const name = g.name;
    if (FP_EXCLUDED_GROUPS.has(name)) return;

    const cubes = (node.children ?? []).filter(c => typeof c === 'string')
      .map(u => elements.get(u)).filter(e => e?.type === 'cube');

    const bone = { name };
    if (parentName) bone.parent = parentName;
    bone.pivot = g.origin.slice();
    if (Array.isArray(g.rotation) && g.rotation.some(v => v !== 0)) bone.rotation = g.rotation.slice();
    if (cubes.length) {
      bone.cubes = cubes.map(e => {
        const cube = { origin: e.from.slice(), size: e.to.map((v, i) => v - e.from[i]) };
        if (e.inflate) cube.inflate = e.inflate;
        const uv = {};
        for (const face of ['north', 'east', 'south', 'west', 'up', 'down']) {
          const f = e.faces?.[face];
          if (!f || f.texture == null) continue;
          uv[face] = { uv: [f.uv[0], f.uv[1]], uv_size: [f.uv[2] - f.uv[0], f.uv[3] - f.uv[1]] };
        }
        if (Object.keys(uv).length) cube.uv = uv;
        return cube;
      });
    }
    bones.push(bone);
    for (const c of node.children ?? []) if (typeof c === 'object') walk(c, name);
  }
  for (const top of bb.outliner.filter(g => typeof g === 'object')) walk(top, null);
  return bones;
}

function convertFpSingleGeo() {
  const bb = JSON.parse(fs.readFileSync(path.join(SRC, 'sword_single.bbmodel'), 'utf8'));
  const bones = fpSingleBones(bb);
  const elucidator = bones.find(b => b.name === 'elucidator');
  if (!elucidator) throw new Error('fp_singlesword: no elucidator bone to attach blade to');
  elucidator.cubes = [...(elucidator.cubes ?? []), ...FP_BLADE_CUBES];
  const geo = {
    format_version: '1.12.0',
    'minecraft:geometry': [{
      description: {
        identifier: FP_SINGLE_GEO_ID,
        texture_width: bb.resolution.width,
        texture_height: bb.resolution.height,
        visible_bounds_width: 2,
        visible_bounds_height: 3.5,
        visible_bounds_offset: [0, 1.25, 0],
      },
      bones,
    }],
  };
  const out = path.join(GEO_DIR, 'fp_singlesword.geo.json');
  fs.mkdirSync(GEO_DIR, { recursive: true });
  fs.writeFileSync(out, JSON.stringify(geo, null, 2));
  console.log('geo ->', out, `(${bones.length} bones)`);
}

const trimTime = t => t.toFixed(6).replace(/\.?0+$/, '') || '0';

// bbmodel flat keyframe lists -> Bedrock per-bone channel maps. Drops bones without
// keyframes and bones not present in the output geo (phantom animators, camera).
function bbAnimsToBedrock(bb, boneNames) {
  const anims = {};
  for (const a of bb.animations) {
    const bones = {};
    for (const animator of Object.values(a.animators ?? {})) {
      if (animator.type !== 'bone' || !boneNames.has(animator.name)) continue;
      const channels = {};
      for (const kf of animator.keyframes ?? []) {
        const points = (kf.data_points ?? []).map(p => [parseFloat(p.x), parseFloat(p.y), parseFloat(p.z)]);
        if (!points.length) continue;
        const value = points.length >= 2
          ? { pre: points[0], post: points[1] }
          : { post: points[0] };
        if (kf.interpolation === 'catmullrom') value.lerp_mode = 'catmullrom';
        (channels[kf.channel] ??= {})[trimTime(kf.time)] = value;
      }
      if (Object.keys(channels).length) bones[animator.name] = channels;
    }
    anims[a.name] = {
      loop: a.loop === 'loop' ? true : a.loop === 'hold' ? 'hold_on_last_frame' : false,
      animation_length: a.length,
      bones,
    };
  }
  return anims;
}

// Pose clip: earliest keyframe per bone/channel, retimed to t=0 and held
// (GUI / non-combat item rendering).
function firstFramePose(anim) {
  const bones = {};
  for (const [boneName, bone] of Object.entries(anim?.bones ?? {})) {
    const channels = {};
    for (const [chan, keyframes] of Object.entries(bone)) {
      if (!keyframes || typeof keyframes !== 'object' || Array.isArray(keyframes)) continue;
      const entries = Object.entries(keyframes);
      if (!entries.length) continue;
      const earliest = entries.reduce((a, b) => parseFloat(a[0]) <= parseFloat(b[0]) ? a : b)[1];
      channels[chan] = { '0': earliest && typeof earliest === 'object' && !Array.isArray(earliest)
        ? { ...earliest }
        : { post: Array.isArray(earliest) ? earliest : [earliest, earliest, earliest] } };
    }
    if (Object.keys(channels).length) bones[boneName] = channels;
  }
  return { loop: 'hold_on_last_frame', animation_length: 0.0333, bones };
}

function convertFpSingleAnims() {
  const bb = JSON.parse(fs.readFileSync(path.join(SRC, 'sword_single.bbmodel'), 'utf8'));
  const boneNames = new Set(fpSingleBones(bb).map(b => b.name));
  const src = bbAnimsToBedrock(bb, boneNames);
  const take = (name, outName, loop) => {
    if (!src[name]) throw new Error(`fp_singlesword: missing source clip ${name}`);
    return [outName, { ...src[name], loop }];
  };
  const run = src.run;
  if (!run) throw new Error('fp_singlesword: missing run clip');
  const animations = Object.fromEntries([
    take('static_idle', 'static_idle', true),
    take('inspect', 'inspect', true),
    take('draw', 'draw', false),
    take('aiming', 'aiming', 'hold_on_last_frame'),
    take('defend_1', 'defend_1', 'hold_on_last_frame'),
    take('run', 'run_start', false),
    take('run', 'run_hold', true),
    ['run_end', { ...reverseAnim(run), loop: false }],
    take('attack', 'attack1', false),
    take('attack2', 'attack2', false),
    ['put_away', { ...reverseAnim(src.draw), loop: false, animation_length: src.draw.animation_length }],
    ['static_pose', firstFramePose(src.static_idle)],
  ]);
  const out = path.join(ANIM_DIR, 'fp_singlesword.animation.json');
  fs.mkdirSync(ANIM_DIR, { recursive: true });
  fs.writeFileSync(out, JSON.stringify({ format_version: '1.8.0', animations }, null, 2));
  console.log('anims ->', out, Object.keys(animations).join(', '));
}

function convertFpDoubleAssets() {
  const geo = JSON.parse(fs.readFileSync(path.join(SRC, 'animation_sword.geo.json'), 'utf8'));
  geo.format_version = '1.12.0'; // source ships a bogus '1.21.110' that GeckoLib warns on
  geo['minecraft:geometry'][0].description.identifier = 'geometry.actioncombat.fp_doublesword';
  const geoOut = path.join(GEO_DIR, 'fp_doublesword.geo.json');
  fs.writeFileSync(geoOut, JSON.stringify(geo, null, 2));
  console.log('geo ->', geoOut,
    `(${geo['minecraft:geometry'][0].bones.length} bones)`);

  const file = JSON.parse(fs.readFileSync(path.join(SRC, 'sword.animation.json'), 'utf8'));
  const a = file.animations;
  const staticIdle = { ...a['animation.idle'], loop: true };
  const animations = {
    draw: { ...a['animation.takeout'], loop: false },
    static_idle: staticIdle,
    inspect: { ...a.jianshi, loop: true },
    attack1: { ...a['animation.attack1'], loop: false },
    attack2: { ...a['animation.attack2'], loop: false },
    static_pose: firstFramePose(staticIdle),
  };
  const animOut = path.join(ANIM_DIR, 'fp_doublesword.animation.json');
  fs.writeFileSync(animOut, JSON.stringify({ format_version: file.format_version ?? '1.8.0', animations }, null, 2));
  console.log('anims ->', animOut, Object.keys(animations).join(', '));
}

// The FP rigs ship with no textures at all; generate dark placeholders so the
// viewmodels read correctly until real textures are exported from Blockbench.
function generateFpTextures() {
  const texDir = path.join(ASSETS, 'textures/entity');
  fs.mkdirSync(texDir, { recursive: true });

  const w = 128, h = 128;
  const px = Buffer.alloc(w * h * 4);
  const put = (x, y, r, g, b) => {
    const i = (y * w + x) * 4;
    px[i] = r; px[i + 1] = g; px[i + 2] = b; px[i + 3] = 255;
  };
  for (let y = 0; y < h; y++) {
    const t = y / (h - 1);
    const r = Math.round(38 + (72 - 38) * t);
    const g = Math.round(42 + (78 - 42) * t);
    const b = Math.round(50 + (90 - 50) * t);
    for (let x = 0; x < w; x++) {
      const n = (x * 7 + y * 13) % 5 - 2;
      put(x, y, r + n, g + n, b + n);
    }
  }
  // injected-blade UV zones (see FP_BLADE_CUBES): sides [0,0]-[16,48],
  // tip [0,48]-[16,64], guard [20,0]-[28,16], grip [28,0]-[36,20]
  for (let y = 0; y < 48; y++) {
    const t = y / 47;
    for (let x = 0; x < 16; x++) {
      if (x === 4) put(x, y, 205, 210, 222);
      else if (x === 5) put(x, y, 232, 236, 244);
      else if (x === 6) put(x, y, 160, 166, 180);
      else {
        const v = 60 + Math.round(24 * t) + (x * 7 + y * 13) % 5 - 2;
        put(x, y, v, v + 3, v + 9);
      }
    }
  }
  for (let y = 48; y < 64; y++) {
    for (let x = 0; x < 16; x++) {
      const v = 150 + (x * 3 + y * 7) % 9;
      put(x, y, v + 40, v + 45, v + 55);
    }
  }
  for (let y = 0; y < 16; y++) {
    for (let x = 20; x < 28; x++) {
      const n = (x * 11 + y * 5) % 7 - 3;
      put(x, y, 122 + n, 88 + n, 34 + n);
    }
  }
  for (let y = 0; y < 20; y++) {
    for (let x = 28; x < 36; x++) {
      const band = y % 4 < 2;
      const n = (x * 5 + y * 11) % 5 - 2;
      put(x, y, (band ? 84 : 62) + n, (band ? 58 : 42) + n, (band ? 36 : 26) + n);
    }
  }
  for (let y = 0; y <= 104; y++) { // daoguang (blade-glow quads) zone
    for (let x = 94; x < 128; x++) put(x, y, 235, 245, 250);
  }
  for (const [sx, sy] of [[100, 20], [110, 40], [96, 60]]) { // sparks
    put(sx, sy, 255, 255, 255); put(sx + 1, sy, 255, 255, 255);
    put(sx, sy + 1, 255, 255, 255); put(sx + 1, sy + 1, 255, 250, 240);
  }
  const singleOut = path.join(texDir, 'fp_singlesword.png');
  fs.writeFileSync(singleOut, encodePng(w, h, px));
  console.log('texture ->', singleOut);

  const w2 = 64, h2 = 64;
  const px2 = Buffer.alloc(w2 * h2 * 4);
  for (let y = 0; y < h2; y++) {
    for (let x = 0; x < w2; x++) {
      const i = (y * w2 + x) * 4;
      const seam = y % 8 === 0;
      const n = (x * 5 + y * 11) % 5 - 2;
      px2[i] = (seam ? 58 : 45) + n;
      px2[i + 1] = (seam ? 50 : 38) + n;
      px2[i + 2] = (seam ? 44 : 34) + n;
      px2[i + 3] = 255;
    }
  }
  const doubleOut = path.join(texDir, 'fp_doublesword.png');
  fs.writeFileSync(doubleOut, encodePng(w2, h2, px2));
  console.log('texture ->', doubleOut);
}

convertGeo();
convertDoubleGeo();
fixAnimations('third_person_singlesword.json', 'third_person_singlesword.animation.json');
fixAnimations('third_person_doublesword.json', 'third_person_doublesword.animation.json');
exportItemTexture();
convertFpSingleGeo();
convertFpSingleAnims();
convertFpDoubleAssets();
generateFpTextures();
