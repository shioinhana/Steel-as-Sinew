// Numeric projection helpers for first-person asset regression checks.
export function vector(node) {
  if (Array.isArray(node)) return node;
  return vector(node.post ?? node.vector ?? node.pre);
}

export function sample(channel, time, fallback) {
  if (!channel) return fallback;
  if (Array.isArray(channel)) return channel;
  const keys = Object.entries(channel).map(([t, value]) => ({ time: Number(t), value }))
    .sort((a, b) => a.time - b.time);
  if (time <= keys[0].time) return vector(keys[0].value);
  if (time >= keys.at(-1).time) return vector(keys.at(-1).value);
  const end = keys.findIndex(key => key.time >= time);
  const start = end - 1;
  const u = (time - keys[start].time) / (keys[end].time - keys[start].time);
  const a = vector(keys[start].value);
  const b = vector(keys[end].value);
  if (keys[end].value.lerp_mode !== 'catmullrom') return a.map((v, axis) => v + (b[axis] - v) * u);
  const prev = vector(keys[Math.max(0, start - 1)].value);
  const next = vector(keys[Math.min(keys.length - 1, end + 1)].value);
  return a.map((v, axis) => 0.5 * ((2 * v) + (-prev[axis] + b[axis]) * u
    + (2 * prev[axis] - 5 * v + 4 * b[axis] - next[axis]) * u * u
    + (-prev[axis] + 3 * v - 3 * b[axis] + next[axis]) * u * u * u));
}

export function rotate(point, degrees) {
  let [x, y, z] = point;
  const [rx, ry, rz] = degrees.map(v => v * Math.PI / 180);
  [y, z] = [y * Math.cos(rx) - z * Math.sin(rx), y * Math.sin(rx) + z * Math.cos(rx)];
  [x, z] = [x * Math.cos(ry) + z * Math.sin(ry), -x * Math.sin(ry) + z * Math.cos(ry)];
  [x, y] = [x * Math.cos(rz) - y * Math.sin(rz), x * Math.sin(rz) + y * Math.cos(rz)];
  return [x, y, z];
}

export function posePoint(geo, clip, name, time, point) {
  const geckoPosition = value => [-value[0], value[1], value[2]];
  point = geckoPosition(point);
  for (let bone = geo.get(name); bone; bone = geo.get(bone.parent)) {
    const channels = clip.bones[bone.name] ?? {};
    const pivot = geckoPosition(bone.pivot);
    const scale = sample(channels.scale, time, [1, 1, 1]);
    const rotation = sample(channels.rotation, time, [0, 0, 0]);
    const position = geckoPosition(sample(channels.position, time, [0, 0, 0]));
    point = point.map((v, axis) => (v - pivot[axis]) * scale[axis]);
    point = rotate(point, rotation.map((v, axis) => (v + (bone.rotation?.[axis] ?? 0)) * (axis < 2 ? -1 : 1)));
    point = point.map((v, axis) => v + pivot[axis] + position[axis]);
  }
  return point;
}

export function bladePoint(geo, clip, name, time, bladeLength = 16) {
  const hand = geo.get(name);
  const point = hand.pivot.map((v, axis) => v - (axis === 2 ? bladeLength : 0));
  return posePoint(geo, clip, name, time, point);
}
