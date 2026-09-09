#!/usr/bin/env node
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { posePoint, sample } from './fp_projection.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const assets = path.join(root, 'src/main/resources/assets/actioncombat/geckolib');
const read = name => JSON.parse(fs.readFileSync(path.join(assets, name), 'utf8'));
const geometry = read('models/fp_doublesword.geo.json')['minecraft:geometry'][0];
const thirdGeometry = read('models/player_doublesword.geo.json')['minecraft:geometry'][0];
const animations = read('animations/fp_doublesword.animation.json').animations;
const sourceMetadata = read('animations/fp_doublesword.animation.json').actioncombat_fp_sources;
const bones = new Map(geometry.bones.map(bone => [bone.name, bone]));
const thirdBones = new Map(thirdGeometry.bones.map(bone => [sourceMetadata.bones[bone.name] ?? bone.name,
  { ...bone, name: sourceMetadata.bones[bone.name] ?? bone.name,
    parent: sourceMetadata.bones[bone.parent] ?? bone.parent }]));
const renderer = fs.readFileSync(path.join(root, 'src/main/java/com/actioncombat/client/FpViewmodelRenderer.java'), 'utf8');
const constant = name => Number(renderer.match(new RegExp(`float ${name} = (-?[\\d.]+)F;`))?.[1]);
const offset = [0, constant('DUAL_OFFSET_Y'), constant('DUAL_OFFSET_Z')];
const shoulder = left => ['X', 'Y', 'Z'].map(axis => constant(`${left ? 'LEFT' : 'RIGHT'}_SHOULDER_${axis}`));
const overlap = constant('ELBOW_OVERLAP');
const armMeshes = new Set(['fp_upper_arm_r', 'fp_upper_arm_l', 'right_sleve_bottom', 'left_arm_bottom', 'hand_r', 'hand_l']);
const renderedMeshes = new Set([...renderer.match(/ARM_MESH_BONES = Set\.of\(([\s\S]*?)\);/)[1].matchAll(/"([^"]+)"/g)].map(match => match[1]));
assert.ok([...offset, ...shoulder(true), ...shoulder(false), overlap].every(Number.isFinite), 'framing constants parse');
assert.deepEqual(new Set(geometry.bones.filter(bone => bone.cubes?.length).map(bone => bone.name)), armMeshes,
  'dual FP contains exactly two upper arms, forearms and hands; no other mesh');
for (const name of armMeshes) assert.ok(renderedMeshes.has(name), `${name}: visible in renderer`);
for (const name of ['root', 'lower_body', 'upper_body', 'head', 'left_arm_item', 'right_arm_item', 'spark1', 'elucidator']) {
  assert.equal(renderedMeshes.has(name), false, `${name}: excluded from FP mesh rendering`);
}
const single = read('models/fp_singlesword.geo.json')['minecraft:geometry'][0];
const visibleSingle = single.bones.filter(bone => bone.cubes?.length && renderedMeshes.has(bone.name));
assert.deepEqual(visibleSingle.map(bone => bone.name).sort(),
  ['dissipate_view_0', 'dissipate_view_452', 'fp_forearm_r'],
  'rapier FP shows the split right arm plus the left counterweight arm');

for (const side of ['r', 'l']) {
  const upper = bones.get(`fp_upper_arm_${side}`);
  const forearm = bones.get(side === 'r' ? 'right_sleve_bottom' : 'left_arm_bottom');
  assert.equal(upper.parent, undefined, 'upper mesh anchor is independent of rotating torso');
  assert.equal(bones.get(side === 'r' ? 'right_arm' : 'left_arm').cubes, undefined, 'finite shoulder cap is replaced');
  for (let layer = 0; layer < 2; layer++) {
    for (const face of ['north', 'east', 'south', 'west']) {
      const a = upper.cubes[layer].uv[face];
      const b = forearm.cubes[layer].uv[face];
      assert.equal(a.uv[0], b.uv[0], `${side}/${layer}/${face}: matching skin column at elbow`);
      assert.equal(a.uv[1] + a.uv_size[1], b.uv[1], `${side}/${layer}/${face}: continuous skin row at elbow`);
    }
    assert.equal(upper.cubes[layer].size[1], 1, 'upper sleeve uses a unit-length render segment');
    assert.equal(upper.cubes[layer].inflate, undefined, 'sleeve inflation cannot extend the elbow longitudinally');
  }
}

const add = (a, b) => a.map((v, i) => v + b[i]);
const sub = (a, b) => a.map((v, i) => v - b[i]);
const mul = (a, n) => a.map(v => v * n);
const dot = (a, b) => a.reduce((sum, v, i) => sum + v * b[i], 0);
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const length = a => Math.hypot(...a);
const norm = a => mul(a, 1 / length(a));
function fromTo(value, from, to) {
  const axis = cross(from, to);
  const cosine = dot(from, to);
  if (cosine < -0.999999) {
    const normal = norm(cross(from, Math.abs(from[0]) < 0.9 ? [1, 0, 0] : [0, 0, 1]));
    return sub(mul(normal, 2 * dot(normal, value)), value);
  }
  return add(add(value, cross(axis, value)), mul(cross(axis, cross(axis, value)), 1 / (1 + cosine)));
}

function sampledClip(name, time) {
  const source = animations[name];
  const clip = { bones: {} };
  const base = name.endsWith('_upper') ? animations.static_idle.bones : {};
  for (const [bone, channels] of Object.entries({ ...base, ...source.bones })) {
    clip.bones[bone] = Object.fromEntries(Object.entries(channels).map(([channel, values]) =>
      [channel, [...sample(values, time, channel === 'scale' ? [1, 1, 1] : [0, 0, 0])]]));
  }
  if (!['draw', 'put_away', 'hurt'].includes(name) && clip.bones.upper_body?.rotation && clip.bones.lower_body?.rotation) {
    clip.bones.upper_body.rotation[1] -= clip.bones.lower_body.rotation[1];
  }
  return clip;
}

function inCamera(geo, clip, name, point) {
  return add(mul(posePoint(geo, clip, name, 0, point), 1 / 16), offset);
}

function upperFrame(clip, left) {
  const elbowBone = bones.get(left ? 'left_arm_bottom' : 'right_sleve_bottom');
  const cube = elbowBone.cubes[0];
  const topCenter = cube.origin.map((v, axis) => v + cube.size[axis] * (axis === 1 ? 1 : 0.5));
  const elbow = inCamera(bones, clip, elbowBone.name, topCenter);
  const localDirection = axis => norm(sub(inCamera(bones, clip, elbowBone.name,
    topCenter.map((v, i) => v + (i === axis ? (axis === 0 ? -1 : 1) : 0))), elbow));
  const forearmAxes = [0, 1, 2].map(localDirection);
  const target = shoulder(left);
  const y = norm(sub(target, elbow));
  const x = norm(fromTo(forearmAxes[0], forearmAxes[1], y));
  const z = norm(fromTo(forearmAxes[2], forearmAxes[1], y));
  const start = sub(elbow, mul(y, overlap));
  const segmentLength = length(sub(target, start));
  const transform = point => add(start, add(add(mul(x, point[0]), mul(y, point[1] * segmentLength)), mul(z, point[2])));
  return { elbow, start, x, y, z, segmentLength, transform, elbowBone, topCenter };
}

let poses = 0;
let caps = 0;
let minCapZ = Infinity;
for (const [name, clip] of Object.entries(animations)) {
  const frames = Math.max(2, Math.ceil(clip.animation_length * 60));
  for (let frame = 0; frame <= frames; frame++) {
    const sampled = sampledClip(name, clip.animation_length * frame / frames);
    for (const left of [false, true]) {
      const sleeve = upperFrame(sampled, left);
      assert.ok(length(sub(sleeve.transform([0, 1, 0]), shoulder(left))) < 1e-9, 'sleeve ends at its camera anchor');
      const elbowInterior = inCamera(bones, sampled, sleeve.elbowBone.name,
        sleeve.topCenter.map((v, axis) => v - (axis === 1 ? 0.25 : 0)));
      const localInterior = sub(elbowInterior, sleeve.start);
      assert.ok(Math.abs(dot(localInterior, sleeve.x)) < 2 / 16
        && Math.abs(dot(localInterior, sleeve.z)) < 2 / 16
        && dot(localInterior, sleeve.y) > 0 && dot(localInterior, sleeve.y) < sleeve.segmentLength,
      `${name}: upper-arm volume overlaps the actual forearm, with no detached elbow`);
      for (const x of [-2.25 / 16, 2.25 / 16]) for (const z of [-2.25 / 16, 2.25 / 16]) {
        const point = sleeve.transform([x, 1, z]);
        minCapZ = Math.min(minCapZ, point[2]);
        for (const fov of [50, 70, 95, 110]) for (const aspect of [4 / 3, 16 / 9, 21 / 9]) {
          const halfY = Math.tan(fov * Math.PI / 360) * -point[2];
          const visible = point[2] < -0.05 && Math.abs(point[0]) < halfY * aspect && Math.abs(point[1]) < halfY;
          assert.equal(visible, false, `${name}: shoulder cap remains outside ${fov}deg/${aspect} frustum`);
          caps++;
        }
      }
      poses++;
    }
  }
}
console.log(`check_fp_geometry PASSED: ${poses} arm poses, ${caps} cap projections, min camera cap Z=${minCapZ.toFixed(3)}m, elbow volume + both skin-layer UV seams, both SINGLE arms`);

const previewIndex = process.argv.indexOf('--preview');
if (previewIndex >= 0) {
  const destination = path.resolve(process.argv[previewIndex + 1]);
  const require = createRequire(import.meta.url);
  const { createCanvas, loadImage } = require('@napi-rs/canvas');
  const minecraftJar = process.env.ACTIONCOMBAT_CLIENT_JAR
    ?? path.join(process.env.HOME, 'Library/Application Support/minecraft/versions/gpt/gpt.jar');
  const skinBytes = execFileSync('unzip', ['-p', minecraftJar, 'assets/minecraft/textures/entity/player/wide/steve.png']);
  const skinImage = await loadImage(skinBytes);
  const skinCanvas = createCanvas(64, 64);
  const skinContext = skinCanvas.getContext('2d');
  skinContext.drawImage(skinImage, 0, 0);
  const skin = skinContext.getImageData(0, 0, 64, 64).data;
  const width = 600, height = 338, rowHeight = height + 38;
  const cases = [['static_idle', 0, 'IDLE'], ['attack1_upper', 0.2, 'LEFT SLASH'],
    ['attack4_upper', 0.14, 'RIGHT SLASH'], ['walk_hold_left', 0.25, 'STRAFE LEFT']];
  const canvas = createCanvas(width * 2, 75 + rowHeight * cases.length);
  const context = canvas.getContext('2d');
  context.fillStyle = '#20252a'; context.fillRect(0, 0, canvas.width, canvas.height);
  context.fillStyle = '#e6e9eb'; context.font = 'bold 20px sans-serif';
  context.fillText('Vanilla Steve skin / 70 FOV / 16:9 / actual segmented cuboids', 22, 28);
  context.font = '16px sans-serif';
  context.fillText('FINITE THIRD-PERSON SHOULDER MESH', 22, 57);
  context.fillText('CAMERA-ANCHORED UPPER ARMS', width + 22, 57);
  const faces = {
    north: [0, 1, 2, 3], south: [5, 4, 7, 6], east: [4, 0, 3, 7], west: [1, 5, 6, 2],
    up: [4, 5, 1, 0], down: [3, 2, 6, 7],
  };
  function panel(clip, anchored) {
    const image = context.createImageData(width, height);
    const depth = new Float64Array(width * height).fill(Infinity);
    for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4;
      const grid = x % 50 === 0 || y % 50 === 0;
      image.data.set(grid ? [68, 79, 85, 255] : [54, 64, 70, 255], i);
    }
    const triangles = [];
    const tanY = Math.tan(70 * Math.PI / 360);
    function triangle(vertices) {
      let polygon = vertices;
      const out = [];
      for (let i = 0; i < polygon.length; i++) {
        const a = polygon[i], b = polygon[(i + 1) % polygon.length];
        const inA = a.p[2] <= -0.05, inB = b.p[2] <= -0.05;
        if (inA) out.push(a);
        if (inA !== inB) {
          const t = (-0.05 - a.p[2]) / (b.p[2] - a.p[2]);
          out.push({ p: add(a.p, mul(sub(b.p, a.p), t)), uv: add(a.uv, mul(sub(b.uv, a.uv), t)) });
        }
      }
      for (let i = 1; i + 1 < out.length; i++) triangles.push([out[0], out[i], out[i + 1]]);
    }
    function cube(cube, transform) {
      const o = cube.origin, s = cube.size, inflation = cube.inflate ?? 0;
      const lo = o.map(v => v - inflation), hi = o.map((v, i) => v + s[i] + inflation);
      const corners = [[lo[0], hi[1], lo[2]], [hi[0], hi[1], lo[2]], [hi[0], lo[1], lo[2]], [lo[0], lo[1], lo[2]],
        [lo[0], hi[1], hi[2]], [hi[0], hi[1], hi[2]], [hi[0], lo[1], hi[2]], [lo[0], lo[1], hi[2]]].map(transform);
      for (const [name, indices] of Object.entries(faces)) {
        const uv = cube.uv[name];
        const [u, v] = uv.uv, [w, h] = uv.uv_size;
        const texture = [[u, v], [u + w, v], [u + w, v + h], [u, v + h]];
        const vertices = indices.map((index, i) => ({ p: corners[index], uv: texture[i] }));
        triangle([vertices[0], vertices[1], vertices[2]]); triangle([vertices[0], vertices[2], vertices[3]]);
      }
    }
    for (const bone of geometry.bones) {
      if (!bone.cubes?.length) continue;
      if (bone.name.startsWith('fp_upper_arm_')) {
        const left = bone.name.endsWith('_l');
        if (anchored) {
          const frame = upperFrame(clip, left);
          for (const mesh of bone.cubes) cube(mesh, point => frame.transform([-point[0] / 16, point[1], point[2] / 16]));
        } else {
          const source = thirdBones.get(left ? 'left_arm' : 'right_arm');
          for (let layer = 0; layer < bone.cubes.length; layer++) {
            const mesh = { ...source.cubes[0], inflate: layer ? 0.25 : 0, uv: bone.cubes[layer].uv };
            cube(mesh, point => inCamera(thirdBones, clip, source.name, point));
          }
        }
      } else for (const mesh of bone.cubes) cube(mesh, point => inCamera(bones, clip, bone.name, point));
    }
    for (const triangle of triangles) {
      const screen = triangle.map(({ p }) => [width / 2 + p[0] / -p[2] * height / 2 / tanY,
        height / 2 - p[1] / -p[2] * height / 2 / tanY]);
      const edge = (a, b, p) => (p[0] - a[0]) * (b[1] - a[1]) - (p[1] - a[1]) * (b[0] - a[0]);
      const area = edge(screen[0], screen[1], screen[2]);
      if (Math.abs(area) < 1e-6) continue;
      const minX = Math.max(0, Math.floor(Math.min(...screen.map(p => p[0]))));
      const maxX = Math.min(width - 1, Math.ceil(Math.max(...screen.map(p => p[0]))));
      const minY = Math.max(0, Math.floor(Math.min(...screen.map(p => p[1]))));
      const maxY = Math.min(height - 1, Math.ceil(Math.max(...screen.map(p => p[1]))));
      const normal = norm(cross(sub(triangle[1].p, triangle[0].p), sub(triangle[2].p, triangle[0].p)));
      const shade = 0.65 + 0.35 * Math.abs(dot(normal, norm([0.2, 0.8, 0.6])));
      for (let y = minY; y <= maxY; y++) for (let x = minX; x <= maxX; x++) {
        const pixel = [x + 0.5, y + 0.5];
        const weights = [edge(screen[1], screen[2], pixel), edge(screen[2], screen[0], pixel), edge(screen[0], screen[1], pixel)].map(v => v / area);
        if (weights.some(v => v < -1e-8)) continue;
        const q = weights.map((w, i) => w / -triangle[i].p[2]);
        const inverseDepth = q.reduce((a, b) => a + b, 0);
        const distance = 1 / inverseDepth;
        const index = y * width + x;
        if (distance >= depth[index]) continue;
        const uv = [0, 1].map(axis => q.reduce((sum, w, i) => sum + w * triangle[i].uv[axis], 0) / inverseDepth);
        const texel = (Math.max(0, Math.min(63, Math.floor(uv[1]))) * 64 + Math.max(0, Math.min(63, Math.floor(uv[0])))) * 4;
        if (skin[texel + 3] < 128) continue;
        for (let channel = 0; channel < 3; channel++) image.data[index * 4 + channel] = skin[texel + channel] * shade;
        image.data[index * 4 + 3] = 255;
        depth[index] = distance;
      }
    }
    return image;
  }
  for (let row = 0; row < cases.length; row++) {
    const [name, time, label] = cases[row];
    const clip = sampledClip(name, time);
    for (const anchored of [false, true]) {
      const x = anchored ? width : 0, y = 75 + row * rowHeight;
      context.putImageData(panel(clip, anchored), x, y);
      context.fillStyle = '#e6e9eb'; context.font = '15px sans-serif';
      context.fillText(`${label} / source ${time.toFixed(3)}s`, x + 14, y + height + 25);
      context.strokeStyle = '#ced8dd'; context.beginPath();
      context.moveTo(x + width / 2 - 5, y + height / 2); context.lineTo(x + width / 2 + 5, y + height / 2);
      context.moveTo(x + width / 2, y + height / 2 - 5); context.lineTo(x + width / 2, y + height / 2 + 5); context.stroke();
    }
  }
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.writeFileSync(destination, canvas.toBuffer('image/png'));
  console.log(`FP cuboid projection preview: ${destination}`);
}
