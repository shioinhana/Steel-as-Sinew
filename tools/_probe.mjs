import fs from 'node:fs';
import path from 'node:path';
import { posePoint, sample } from './fp_projection.mjs';
const ROOT = path.resolve(import.meta.dirname, '..');
const A = path.join(ROOT, 'src/main/resources/assets/actioncombat/geckolib');
const g = new Map(JSON.parse(fs.readFileSync(A + '/models/fp_singlesword.geo.json'))['minecraft:geometry'][0].bones.map(b => [b.name, b]));
const anims = JSON.parse(fs.readFileSync(A + '/animations/fp_singlesword.animation.json')).animations;
const OFF = [0, -0.65, -0.65];
function clip(name, t) {
  const src = anims[name]; const c = { bones: {} };
  for (const [b, ch] of Object.entries(src.bones)) {
    c.bones[b] = Object.fromEntries(Object.entries(ch).map(([channel, v]) => [channel, [...sample(v, t, channel === 'scale' ? [1, 1, 1] : [0, 0, 0])]]));
  }
  return c;
}
const cam = (c, bone, p) => { const q = posePoint(g, c, bone, 0, p); return [q[0] / 16 + OFF[0], q[1] / 16 + OFF[1], q[2] / 16 + OFF[2]]; };
const f = v => v.map(x => x.toFixed(3).padStart(7)).join(' ');
const el = g.get('fp_forearm_r').pivot, ha = g.get('elucidator').pivot;
for (const [name, t] of [['static_idle', 0], ['attack1', 0.19], ['attack2', 0.19], ['attack3', 0.19], ['attack4', 0.19], ['rapier_thrust_hit', 0.14], ['rapier_thrust_hit', 0.25]]) {
  const c = clip(name, t);
  console.log(name.padEnd(20), 't=' + t,
    '\n   shoulder', f(cam(c, 'right', g.get('right').pivot)),
    '\n   elbow   ', f(cam(c, 'fp_forearm_r', el)),
    '\n   hand    ', f(cam(c, 'elucidator', ha)),
    '\n   tip     ', f(cam(c, 'elucidator', [ha[0], ha[1] + 15, ha[2]])));
}
