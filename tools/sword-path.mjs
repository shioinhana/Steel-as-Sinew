// Offline sword-arm planning. Palm trajectories stay exact; a clip is solved as a
// whole so an elbow cannot jump between individually plausible circle solutions.
const RAD = Math.PI / 180;
const UPPER = 37.5, FOREARM = 25, FIST = 6.25, PALM_LINK = FOREARM + FIST;
// Same actual official sprite point used by WeaponAssetTest: hilt pixel 3.5 to tip pixel 14.
const SWORD_TIP_CM = (14 - 3.5) / 16 * Math.SQRT2 * .85 * 100;
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const scale = (a, n) => [a[0] * n, a[1] * n, a[2] * n];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const norm = a => { const n = Math.hypot(...a); if (n < 1e-10) throw new Error('Degenerate sword frame'); return scale(a, 1 / n); };
const clamp = (n, a = -1, b = 1) => Math.max(a, Math.min(b, n));
const transpose = a => [a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]];
const mul = (a, b) => Array.from({ length: 9 }, (_, i) => { const r = Math.floor(i / 3), c = i % 3; return a[r * 3] * b[c] + a[r * 3 + 1] * b[c + 3] + a[r * 3 + 2] * b[c + 6]; });
const transform = (a, b) => [dot(a.slice(0, 3), b), dot(a.slice(3, 6), b), dot(a.slice(6, 9), b)];
const matrix = (x, y, z) => [x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2]];
const euler = m => (Math.abs(m[2]) > 1 - 1e-12
  ? [Math.atan2(m[7], m[4]), Math.asin(clamp(m[2])), 0]
  : [Math.atan2(-m[5], m[8]), Math.asin(clamp(m[2])), Math.atan2(-m[1], m[0])]).map(v => v / RAD);
function rotation(angles) {
  const [x, y, z] = angles.map(v => v * RAD), cx = Math.cos(x), sx = Math.sin(x), cy = Math.cos(y), sy = Math.sin(y), cz = Math.cos(z), sz = Math.sin(z);
  return [cy * cz, -cy * sz, sy, cx * sz + sx * sy * cz, cx * cz - sx * sy * sz, -sx * cy,
    sx * sz - cx * sy * cz, sx * cz + cx * sy * sz, cx * cy];
}
function forward(query, pose) {
  const s = query.side, upper = mul(query.parent, rotation(pose[s + '_arm'].rotation));
  const elbow = add(query.shoulder, transform(upper, [0, -UPPER, 0]));
  const lower = mul(upper, rotation(pose[s + '_forearm'].rotation));
  const wrist = add(elbow, transform(lower, [0, -FOREARM, 0]));
  const hand = mul(lower, rotation(pose[s + '_hand'].rotation));
  return { elbow, wrist, palm: add(wrist, transform(hand, [0, -FIST, 0])),
    foreAxis: transform(lower, [0, -1, 0]), handAxis: transform(hand, [0, -1, 0]) };
}
const sameInput = (a, b) => a.side === b.side && ['shoulder', 'palm', 'pole', 'parent', 'blade'].every(k => a[k].every((v, i) => Math.abs(v - b[k][i]) < 1e-7));
const qdot = (a, b) => Math.min(1, Math.abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]));
const degreesBetween = (a, b) => 2 * Math.acos(qdot(a, b)) / RAD;

function quaternion(m) {
  const trace = m[0] + m[4] + m[8]; let x, y, z, w;
  if (trace > 0) { const s = Math.sqrt(trace + 1) * 2; w = s / 4; x = (m[7] - m[5]) / s; y = (m[2] - m[6]) / s; z = (m[3] - m[1]) / s; }
  else if (m[0] > m[4] && m[0] > m[8]) { const s = Math.sqrt(1 + m[0] - m[4] - m[8]) * 2; w = (m[7] - m[5]) / s; x = s / 4; y = (m[1] + m[3]) / s; z = (m[2] + m[6]) / s; }
  else if (m[4] > m[8]) { const s = Math.sqrt(1 + m[4] - m[0] - m[8]) * 2; w = (m[2] - m[6]) / s; x = (m[1] + m[3]) / s; y = s / 4; z = (m[5] + m[7]) / s; }
  else { const s = Math.sqrt(1 + m[8] - m[0] - m[4]) * 2; w = (m[3] - m[1]) / s; x = (m[2] + m[6]) / s; y = (m[5] + m[7]) / s; z = s / 4; }
  const n = Math.hypot(x, y, z, w); return [x / n, y / n, z / n, w / n];
}

function options(input = {}) {
  const value = { angleStepDegrees: 3, maxStepDegrees: 25, maxWristRollDegrees: 90,
    maxBladeErrorDegrees: 25, maxElbowStepCm: 20, poseCostWeight: .08, loop: false, ...input };
  if (!(value.angleStepDegrees > 0 && value.angleStepDegrees <= 4)) throw new Error('Sword grid must be in (0,4] degrees');
  if (!(value.maxStepDegrees > 0 && value.maxStepDegrees <= 25)) throw new Error('Sword rotation step limit must be in (0,25] degrees');
  if (!(value.maxWristRollDegrees > 0 && value.maxWristRollDegrees <= 90)) throw new Error('Sword wrist roll limit must be in (0,90] degrees');
  if (!(value.maxBladeErrorDegrees > 0 && value.maxBladeErrorDegrees <= 25)) throw new Error('Sword projection limit must be in (0,25] degrees');
  return value;
}

function geometry(query) {
  for (const [key, length] of Object.entries({ shoulder: 3, palm: 3, pole: 3, parent: 9, blade: 3 }))
    if (!Array.isArray(query[key]) || query[key].length !== length || !query[key].every(Number.isFinite)) throw new Error(`Invalid sword query ${key}`);
  if (!['left', 'right'].includes(query.side)) throw new Error('Invalid sword query side');
  const delta = sub(query.palm, query.shoulder), distance = Math.hypot(...delta);
  if (distance <= Math.abs(UPPER - PALM_LINK) + .01 || distance >= UPPER + PALM_LINK - .01)
    throw new Error(`Sword palm unreachable (${query.side}, shoulder-palm ${distance.toFixed(3)} cm)`);
  const axis = scale(delta, 1 / distance);
  const along = (distance * distance + UPPER * UPPER - PALM_LINK * PALM_LINK) / (2 * distance);
  const center = add(query.shoulder, scale(axis, along)), radius = Math.sqrt(UPPER * UPPER - along * along);
  let preferred = sub(query.pole, scale(axis, dot(query.pole, axis)));
  if (Math.hypot(...preferred) < 1e-8) {
    const reference = [[1, 0, 0], [0, 1, 0], [0, 0, 1]].sort((a, b) => Math.abs(dot(a, axis)) - Math.abs(dot(b, axis)))[0];
    preferred = sub(reference, scale(axis, dot(reference, axis)));
  }
  preferred = norm(preferred);
  return { query, axis, center, radius, preferred, tangent: norm(cross(axis, preferred)), blade: norm(query.blade), parentInverse: transpose(query.parent) };
}

function candidate(g, theta, config, hingeSign = 1) {
  const { query: q, center, radius, preferred, tangent, axis, blade, parentInverse } = g;
  const normal = add(scale(preferred, Math.cos(theta)), scale(tangent, Math.sin(theta)));
  const elbow = add(center, scale(normal, radius));
  const upperAxis = norm(sub(elbow, q.shoulder)), handAxis = norm(sub(q.palm, elbow));
  const error = Math.asin(clamp(Math.abs(dot(handAxis, blade)), 0, 1)) / RAD;
  if (error > config.maxBladeErrorDegrees + 1e-7) return null;
  const hinge = scale(norm(cross(axis, normal)), hingeSign);
  const upperY = scale(upperAxis, -1), lowerY = scale(handAxis, -1);
  const upper = matrix(hinge, upperY, norm(cross(hinge, upperY)));
  const lower = matrix(hinge, lowerY, norm(cross(hinge, lowerY)));
  const bladeZ = norm(sub(blade, scale(lowerY, dot(blade, lowerY))));
  const hand = matrix(norm(cross(lowerY, bladeZ)), lowerY, bladeZ);
  const localUpper = mul(parentInverse, upper), localLower = mul(transpose(upper), lower), localHand = mul(transpose(lower), hand);
  const roll = Math.atan2(localHand[2], localHand[0]) / RAD;
  if (Math.abs(roll) >= config.maxWristRollDegrees - 1e-7) return null;
  const poleError = Math.acos(clamp(dot(normal, preferred))) / RAD;
  const anatomicalElbow = transform(parentInverse, sub(elbow, q.shoulder));
  const sign = q.side === 'left' ? 1 : -1;
  const inward = Math.max(0, 12 - sign * (sign * 37.5 + anatomicalElbow[0]));
  const raised = Math.max(0, anatomicalElbow[1]);
  const rollBarrier = Math.max(0, Math.abs(roll) - 65) / 25;
  const localCost = .45 * (roll / 90) ** 2 + 3 * rollBarrier ** 2 + 1.5 * (error / 25) ** 2 + .8 * (poleError / 180) ** 2
    + 1.2 * (inward / 20) ** 2 + 2 * (raised / 15) ** 2;
  return { theta, hingeSign, elbow, wrist: add(elbow, scale(handAxis, FOREARM)), palm: q.palm, handAxis, bladeZ, error, roll, localCost,
    worldQ: [quaternion(upper), quaternion(lower), quaternion(hand)], handQ: quaternion(localHand),
    pose: { [q.side + '_arm']: { rotation: euler(localUpper) },
      [q.side + '_forearm']: { rotation: euler(localLower) },
      [q.side + '_hand']: { rotation: [0, roll, 0] } } };
}

function candidates(query, config) {
  const g = geometry(query), count = Math.ceil(360 / config.angleStepDegrees), step = Math.PI * 2 / count;
  const angles = Array.from({ length: count }, (_, i) => i * step);
  // Add exact feasible-band boundaries, so a narrow legal interval is not missed by the grid.
  const bx = dot(g.preferred, g.blade), by = dot(g.tangent, g.blade), amplitude = g.radius * Math.hypot(bx, by);
  if (amplitude > 1e-9) {
    const middle = dot(sub(query.palm, g.center), g.blade), bound = PALM_LINK * Math.sin(config.maxBladeErrorDegrees * RAD);
    for (const level of [(middle - bound) / amplitude, (middle + bound) / amplitude])
      if (Math.abs(level) <= 1) { const phase = Math.atan2(by, bx), offset = Math.acos(level); angles.push(phase + offset, phase - offset); }
  }
  const values = angles.flatMap(theta => [candidate(g, theta, config, 1), candidate(g, theta, config, -1)]).filter(Boolean);
  if (!values.length) throw new Error(`No anatomical sword pose (${query.side}): palm=${query.palm}, blade=${query.blade}; roll < ${config.maxWristRollDegrees}°, blade error <= ${config.maxBladeErrorDegrees}°`);
  values.sort((a, b) => a.localCost - b.localCost || a.theta - b.theta || a.hingeSign - b.hingeSign);
  let best = values[0], lo = best.theta - step, hi = best.theta + step;
  // Deterministic local refinement gives identical static endpoints to every clip using this input.
  for (let i = 0; i < 20; i++) {
    const a = lo + (hi - lo) / 3, b = hi - (hi - lo) / 3;
    const ca = candidate(g, a, config, best.hingeSign), cb = candidate(g, b, config, best.hingeSign);
    if ((ca?.localCost ?? Infinity) <= (cb?.localCost ?? Infinity)) hi = b; else lo = a;
    if (ca && ca.localCost < best.localCost) best = ca;
    if (cb && cb.localCost < best.localCost) best = cb;
  }
  return { values: [best, ...values.filter(value => value.hingeSign !== best.hingeSign || Math.abs(value.theta - best.theta) > 1e-9)], best };
}

function linkCost(a, b, threshold, maxElbowStep) {
  const u = qdot(a.worldQ[0], b.worldQ[0]); if (u < threshold) return Infinity;
  const l = qdot(a.worldQ[1], b.worldQ[1]); if (l < threshold) return Infinity;
  const h = qdot(a.worldQ[2], b.worldQ[2]); if (h < threshold) return Infinity;
  const local = qdot(a.handQ, b.handQ); if (local < threshold) return Infinity;
  const elbowDistance = Math.hypot(...sub(a.elbow, b.elbow)); if (elbowDistance > maxElbowStep) return Infinity;
  return 4 * ((1 - u * u) + 1.5 * (1 - l * l) + 2 * (1 - h * h) + (1 - local * local))
    + .75 * (elbowDistance / 10) ** 2;
}

export function solveSwordStatic(query, inputOptions = {}) {
  return candidates(query, options(inputOptions)).best.pose;
}

export function planSwordPathDetailed(queries, inputOptions = {}) {
  if (!Array.isArray(queries) || !queries.length) throw new Error('Sword path needs at least one frame');
  const config = options(inputOptions);
  if (queries.some(query => query.side !== queries[0].side)) throw new Error('One sword path may contain only one side');
  if (config.loop && !sameInput(queries[0], queries.at(-1))) throw new Error('A loop must repeat its first query at the last frame');
  const pools = queries.map((query, i) => {
    try { const pool = candidates(query, config); return i === 0 || i === queries.length - 1 ? [pool.best] : pool.values; }
    catch (error) { throw new Error(`Sword frame ${i}: ${error.message}`); }
  });
  const threshold = Math.cos(config.maxStepDegrees * RAD / 2), previousIndices = [];
  let costs = new Float64Array([pools[0][0].localCost * config.poseCostWeight]);
  for (let frame = 1; frame < pools.length; frame++) {
    const previous = pools[frame - 1], current = pools[frame], next = new Float64Array(current.length).fill(Infinity);
    const parents = new Int32Array(current.length).fill(-1);
    for (let j = 0; j < current.length; j++) for (let i = 0; i < previous.length; i++) {
      if (!Number.isFinite(costs[i])) continue;
      const cost = costs[i] + linkCost(previous[i], current[j], threshold, config.maxElbowStepCm) + current[j].localCost * config.poseCostWeight;
      if (cost < next[j]) { next[j] = cost; parents[j] = i; }
    }
    if (!next.some(Number.isFinite)) {
      let nearest = Infinity;
      for (let i = 0; i < previous.length; i++) if (Number.isFinite(costs[i])) for (const c of current)
        nearest = Math.min(nearest, Math.max(...previous[i].worldQ.map((q, k) => degreesBetween(q, c.worldQ[k])), degreesBetween(previous[i].handQ, c.handQ)));
      throw new Error(`Sword path discontinuity at frame ${frame}: nearest legal pose requires ${nearest.toFixed(2)}° (limit ${config.maxStepDegrees}°); revise palm/blade trajectory or its carry endpoint`);
    }
    previousIndices.push(parents); costs = next;
  }
  const selected = new Array(pools.length); let index = 0;
  for (let frame = pools.length - 1; frame >= 0; frame--) {
    selected[frame] = pools[frame][index]; if (frame > 0) index = previousIndices[frame - 1][index];
  }
  const diagnostics = { side: queries[0].side, frames: selected.length, maxWorldStepDegrees: [0, 0, 0],
    maxLocalWristStepDegrees: 0, maxElbowStepCm: 0, maxWristRollDegrees: 0, maxBladeProjectionErrorDegrees: 0,
    wristSwingDegrees: 0, maxUpperLengthErrorCm: 0, maxForearmLengthErrorCm: 0, maxPalmErrorCm: 0 };
  selected.forEach((pose, i) => {
    const fk = forward(queries[i], pose.pose);
    diagnostics.maxWristRollDegrees = Math.max(diagnostics.maxWristRollDegrees, Math.abs(pose.roll));
    diagnostics.maxBladeProjectionErrorDegrees = Math.max(diagnostics.maxBladeProjectionErrorDegrees, pose.error);
    diagnostics.maxUpperLengthErrorCm = Math.max(diagnostics.maxUpperLengthErrorCm, Math.abs(Math.hypot(...sub(fk.elbow, queries[i].shoulder)) - UPPER));
    diagnostics.maxForearmLengthErrorCm = Math.max(diagnostics.maxForearmLengthErrorCm, Math.abs(Math.hypot(...sub(fk.wrist, fk.elbow)) - FOREARM));
    diagnostics.maxPalmErrorCm = Math.max(diagnostics.maxPalmErrorCm, Math.hypot(...sub(fk.palm, queries[i].palm)));
    diagnostics.wristSwingDegrees = Math.max(diagnostics.wristSwingDegrees, Math.acos(clamp(dot(fk.foreAxis, fk.handAxis))) / RAD);
    if (i) {
      diagnostics.maxWorldStepDegrees = diagnostics.maxWorldStepDegrees.map((v, k) => Math.max(v, degreesBetween(selected[i - 1].worldQ[k], pose.worldQ[k])));
      diagnostics.maxLocalWristStepDegrees = Math.max(diagnostics.maxLocalWristStepDegrees, degreesBetween(selected[i - 1].handQ, pose.handQ));
      diagnostics.maxElbowStepCm = Math.max(diagnostics.maxElbowStepCm, Math.hypot(...sub(selected[i - 1].elbow, pose.elbow)));
    }
  });
  return { poses: selected.map(pose => pose.pose), diagnostics };
}

export function planSwordPath(queries, inputOptions = {}) {
  const result = planSwordPathDetailed(queries, inputOptions);
  Object.defineProperty(result.poses, 'diagnostics', { value: result.diagnostics });
  return result.poses;
}

export function runSwordPathSelfTest() {
  const parent = [1, 0, 0, 0, 1, 0, 0, 0, 1], queries = [];
  for (let i = 0; i <= 60; i++) {
    const t = i / 60, wave = Math.sin(t * Math.PI * 2);
    queries.push({ side: 'right', parent, shoulder: [-37.5, 150, 0], palm: [-42 + wave * 5, 113 + wave * 2, 36 + wave * 3],
      pole: [-.75, -1, -.45], blade: [-.5, .45 + wave * .05, .75] });
  }
  const result = planSwordPathDetailed(queries, { loop: true });
  const d = result.diagnostics;
  if (d.maxUpperLengthErrorCm > 1e-9 || d.maxForearmLengthErrorCm > 1e-9 || d.maxPalmErrorCm > 1e-9
      || d.maxWristRollDegrees >= 90 || d.maxBladeProjectionErrorDegrees > 25 + 1e-7
      || Math.max(...d.maxWorldStepDegrees, d.maxLocalWristStepDegrees) > 25 + 1e-7)
    throw new Error('Sword path self-test failed anatomical or continuity constraints');
  for (const id of ['right_arm', 'right_forearm', 'right_hand'])
    if (result.poses[0][id].rotation.some((v, i) => Math.abs(v - result.poses.at(-1)[id].rotation[i]) > 1e-8))
      throw new Error('Sword path self-test failed deterministic loop endpoint');
  const reversed = { ...queries[0], blade: scale(queries[0].blade, -1) };
  let rejected = false;
  try { planSwordPathDetailed([queries[0], reversed]); } catch (error) { rejected = error.message.includes('discontinuity'); }
  if (!rejected) throw new Error('Sword path self-test accepted an impossible one-frame hilt reversal');
  return { passed: true, impossibleFrameRejected: true, ...d };
}

if (process.argv[1]?.replaceAll('\\', '/').endsWith('/sword-path.mjs') && process.argv.includes('--self-test'))
  console.log(JSON.stringify(runSwordPathSelfTest(), null, 2));


// Direct closed-grip gesture authoring. The official hilt is inclined 50 degrees
// from wrist-to-knuckles. A negative local-X elbow bend brings the fist forwards;
// the wrist stays straight. Cut-plane roll rotates the complete arm about the
// blade's direction, rather than turning the held item inside a stationary fist.
function gesture(query) {
 const {side,parent,shoulder,azimuth,elevation,elbowFlex,bladeRoll=0,local=false}=query;
 if(!['left','right'].includes(side)||![azimuth,elevation,elbowFlex,bladeRoll,...parent,...shoulder].every(Number.isFinite))throw new Error('Invalid sword gesture');
 if(elbowFlex<10||elbowFlex>110||Math.abs(bladeRoll)>90)throw new Error('Sword gesture outside anatomical elbow/whole-arm plane range');
 const rx=d=>{const c=Math.cos(d*RAD),s=Math.sin(d*RAD);return[1,0,0,0,c,-s,0,s,c];};
 const ry=d=>{const c=Math.cos(d*RAD),s=Math.sin(d*RAD);return[c,0,s,0,1,0,-s,0,c];};
 const rz=d=>{const c=Math.cos(d*RAD),s=Math.sin(d*RAD);return[c,-s,0,s,c,0,0,0,1];};
 const bladeFrame=mul(mul(ry(azimuth),rx(-elevation)),rz(bladeRoll));
 let hand=mul(bladeFrame,rx(-40));
 if(local)hand=mul(parent,hand);
 const upper=mul(hand,rx(elbowFlex)),fore=hand;
 const elbow=add(shoulder,transform(upper,[0,-UPPER,0]));
 const wrist=add(elbow,transform(fore,[0,-FOREARM,0]));
 const palm=add(wrist,transform(hand,[0,-FIST,0]));
 const pose={ [side+'_arm']:{rotation:euler(mul(transpose(parent),upper))},
  [side+'_forearm']:{rotation:[-elbowFlex,0,0]},[side+'_hand']:{rotation:[0,0,0]} };
 return {pose,worldQ:[quaternion(upper),quaternion(fore),quaternion(hand)],elbow,wrist,palm,
  tip:add(palm,transform(hand,[0,-Math.cos(50*RAD)*SWORD_TIP_CM,Math.sin(50*RAD)*SWORD_TIP_CM])),bladeNormal:transform(hand,[1,0,0])};
}
export function solveSwordGesture(query){return gesture(query).pose;}
// Authoring inspection uses the exact same chain as the exported animation.
export function evaluateSwordGesture(query){return gesture(query);}
// A slash is authored by the grip travelling across the target, not by rotating
// a blade vector while the hand lunges forwards. This exact two-link chain uses
// an elbow hint to choose its anatomical plane; it never shifts an unreachable
// palm or folds the wrist to make a requested trajectory fit. Bounded forearm
// pronation turns the real blade together with the straight fist.
export function evaluateSwordArc(query,elbowAngle=0,pronation=0) {
 const {side,parent,shoulder,palm,elbowHint}=query;
 if(!['left','right'].includes(side)||![...parent,...shoulder,...palm,...elbowHint].every(Number.isFinite))throw new Error('Invalid sword grip arc');
 const delta=sub(palm,shoulder),distance=Math.hypot(...delta);
 if(distance>=UPPER+PALM_LINK-.01||distance<=Math.abs(UPPER-PALM_LINK)+.01)throw new Error(`Unreachable slash grip ${side}: ${distance.toFixed(3)}cm`);
 const axis=scale(delta,1/distance),along=(distance*distance+UPPER*UPPER-PALM_LINK*PALM_LINK)/(2*distance);
 const center=add(shoulder,scale(axis,along)),radius=Math.sqrt(UPPER*UPPER-along*along);
 const towards=sub(elbowHint,center),projected=sub(towards,scale(axis,dot(towards,axis)));
 if(Math.hypot(...projected)<1e-5)throw new Error('Slash elbow hint lies on its ambiguous shoulder-to-palm axis');
 const preferred=norm(projected),radial=add(scale(preferred,Math.cos(elbowAngle)),scale(cross(axis,preferred),Math.sin(elbowAngle)));
 const elbow=add(center,scale(radial,radius)),u=norm(sub(elbow,shoulder)),f=norm(sub(palm,elbow));
 const hinge=norm(cross(f,u)),upper=matrix(hinge,scale(u,-1),norm(cross(hinge,scale(u,-1))));
 const fore=matrix(hinge,scale(f,-1),norm(cross(hinge,scale(f,-1))));
 const hand=mul(fore,rotation([0,pronation,0]));
 const bend=Math.acos(clamp(dot(u,f)))/RAD;
 if(bend<10||bend>110)throw new Error(`Slash elbow flexion outside 10..110 degrees: ${bend}`);
 const wrist=add(elbow,scale(f,FOREARM));
 return {pose:{[side+'_arm']:{rotation:euler(mul(transpose(parent),upper))},[side+'_forearm']:{rotation:[-bend,pronation,0]},[side+'_hand']:{rotation:[0,0,0]}},
  worldQ:[quaternion(upper),quaternion(hand),quaternion(hand)],handQ:[0,0,0,1],elbow,wrist,palm,
  tip:add(palm,transform(hand,[0,-Math.cos(50*RAD)*SWORD_TIP_CM,Math.sin(50*RAD)*SWORD_TIP_CM])),bladeNormal:transform(hand,[1,0,0]),pronation,elbowAngle};
}
function planGripArc(queries,times,maxStepDegrees) {
 const pools=queries.map((q,i)=>{
  try{evaluateSwordArc(q);}catch(error){throw new Error(`Grip arc ${q.side} t=${times[i]} palm=${q.palm}: ${error.message}`);}
  const velocity=sub(queries[Math.min(i+1,queries.length-1)].palm,queries[Math.max(0,i-1)].palm),speed=Math.hypot(...velocity);
  const tangent=speed>1e-7?scale(velocity,1/speed):[0,0,0],contact=times[i]>=.30&&times[i]<=.57;
  const endpoint=i===0||i===queries.length-1;
  const angles=endpoint?[0]:Array.from({length:60},(_,k)=>k*Math.PI/30);
  return angles.flatMap(angle=>(endpoint?[0]:[-30,-20,-10,0,10,20,30]).map(pronation=>{
   const v=evaluateSwordArc(q,angle,pronation),localElbow=transform(transpose(q.parent),sub(v.elbow,q.shoulder));
   const elbowX=localElbow[0]+(q.side==='left'?37.5:-37.5),inside=Math.max(0,26-Math.abs(elbowX));
   const chestOverlap=Math.max(0,21-localElbow[2])*inside/26;
   const raised=Math.max(0,localElbow[1]),blade=norm(sub(v.tip,v.palm));
   v.localCost=.4*(pronation/25)**2+.0002*Math.hypot(...sub(v.elbow,q.elbowHint))**2+2*(chestOverlap/10)**2+2*(raised/10)**2;
   if(contact) {
    v.localCost+=8*dot(v.bladeNormal,tangent)**2+2*(Math.max(0,10-v.tip[2])/25)**2;
    if(times[i]<.34)v.localCost+=20*(Math.max(0,v.tip[0]*q.cutSign+20)/20)**2;
    if(times[i]>.54)v.localCost+=20*(Math.max(0,55-v.tip[0]*q.cutSign)/20)**2;
    const desiredHeight=185-(times[i]-.30)/.27*100;
    v.localCost+=.005*(v.tip[1]-desiredHeight)**2;
   }
   return v;
  }));
 });
 const threshold=Math.cos(maxStepDegrees*RAD/2),parents=[];
 let costs=new Float64Array([pools[0][0].localCost]);
 for(let frame=1;frame<pools.length;frame++) {
  const before=pools[frame-1],after=pools[frame],next=new Float64Array(after.length).fill(Infinity),prev=new Int32Array(after.length).fill(-1);
  for(let j=0;j<after.length;j++)for(let i=0;i<before.length;i++)if(Number.isFinite(costs[i])) {
   let connection=linkCost(before[i],after[j],threshold,20)*8;
   if(Number.isFinite(connection)&&times[frame]>=.30&&times[frame]<=.57) {
    const a=before[i],b=after[j],v=sub(scale(add(b.tip,b.palm),.5),scale(add(a.tip,a.palm),.5)),speed=Math.hypot(...v);
    if(speed>1e-6) {
     const velocity=scale(v,1/speed),normal=norm(add(a.bladeNormal,b.bladeNormal)),axis=norm(add(sub(a.tip,a.palm),sub(b.tip,b.palm)));
     connection+=25*dot(normal,velocity)**2+10*dot(axis,velocity)**2;
     connection+=8*Math.max(0,-v[0]*queries[frame].cutSign)**2+4*Math.max(0,v[1])**2;
    }
   }
   const cost=costs[i]+connection+after[j].localCost*.1;
   if(cost<next[j]){next[j]=cost;prev[j]=i;}
  }
  if(!next.some(Number.isFinite))throw new Error(`Grip arc has no continuous anatomical elbow path at ${times[frame]} seconds; revise the palm arc or elbow hint`);
  parents.push(prev);costs=next;
 }
 const selected=new Array(pools.length);let index=0;
 for(let frame=pools.length-1;frame>=0;frame--){selected[frame]=pools[frame][index];if(frame)index=parents[frame-1][index];}
 // Smooth the selected joint-plane angles, never the hand trajectory. This
 // removes grid quantisation without projecting or moving the authored palm.
 const angles=selected.map(v=>v.elbowAngle),pronation=selected.map(v=>v.pronation);
 for(let i=1;i<angles.length;i++){while(angles[i]-angles[i-1]>Math.PI)angles[i]-=2*Math.PI;while(angles[i]-angles[i-1]<-Math.PI)angles[i]+=2*Math.PI;}
 const smooth=(a,i)=>{
  const average=radius=>{let sum=0,weight=0;for(let offset=-radius;offset<=radius;offset++){const k=Math.max(0,Math.min(a.length-1,i+offset)),w=radius+1-Math.abs(offset);sum+=a[k]*w;weight+=w;}return sum/weight;};
  const ramp=x=>{x=clamp(x,0,1);return x*x*(3-2*x);},extra=ramp((times[i]-.20)/.07)*ramp((.70-times[i])/.10);
  return average(2)*(1-extra)+average(3)*extra;
 };
 return selected.map((v,i)=>i===0||i===selected.length-1?v:evaluateSwordArc(queries[i],smooth(angles,i),smooth(pronation,i)));
}
export function planSwordGesture(queries,{loop=false,maxStepDegrees=25,times=[]}={}) {
 const values=queries.every(q=>q.palm)?planGripArc(queries,times,maxStepDegrees):queries.map((q,i)=>{try{return q.palm?evaluateSwordArc(q):gesture(q);}catch(error){throw new Error(`Sword ${q.side} frame ${i}, time ${times[i]}, palm ${q.palm}: ${error.message}`);}}),d={method:queries.some(q=>q.palm)?'exact grip slash arc; negative-X elbow plus bounded forearm pronation; neutral wrist; fixed 50-degree grip':'closed-grip blade arc and elbow flexion',frames:values.length,
  side:queries[0].side,maxWorldStepDegrees:[0,0,0],maxLocalWristStepDegrees:0,maxWristRollDegrees:0,
  wristSwingDegrees:0,maxForearmPronationDegrees:Math.max(...values.map(v=>Math.abs(v.pronation??0))),maxBladeProjectionErrorDegrees:0,maxElbowStepCm:0,maxUpperLengthErrorCm:0,maxForearmLengthErrorCm:0,maxPalmErrorCm:0};
 for(let i=1;i<values.length;i++) {
  for(let k=0;k<3;k++)d.maxWorldStepDegrees[k]=Math.max(d.maxWorldStepDegrees[k],degreesBetween(values[i-1].worldQ[k],values[i].worldQ[k]));
  d.maxElbowStepCm=Math.max(d.maxElbowStepCm,Math.hypot(...sub(values[i].elbow,values[i-1].elbow)));
 }
 const contact=[];
 for(let i=1;i<values.length-1;i++)if(!queries[i].local&&times[i]>=.32&&times[i]<=.54){
  const velocity=sub(values[i+1].tip,values[i-1].tip),speed=Math.hypot(...velocity);
  const midVelocity=sub(scale(add(values[i+1].tip,values[i+1].palm),.5),scale(add(values[i-1].tip,values[i-1].palm),.5)),midSpeed=Math.hypot(...midVelocity),blade=norm(sub(values[i].tip,values[i].palm));
  const axial=midSpeed>1e-6?Math.abs(dot(scale(midVelocity,1/midSpeed),blade)):0;
  if(speed>1e-6)contact.push({time:times[i],bladeFaceSlip:Math.abs(dot(scale(velocity,1/speed),values[i].bladeNormal)),palmHeightCm:values[i].palm[1],midpointAxialFraction:axial,midpointTransverseFraction:Math.sqrt(1-axial*axial)});
 }
 if(contact.length){d.contact={samples:contact.length,maximumBladeFaceSlip:Math.max(...contact.map(x=>x.bladeFaceSlip)),meanBladeFaceSlip:contact.reduce((a,x)=>a+x.bladeFaceSlip,0)/contact.length,minimumPalmHeightCm:Math.min(...contact.map(x=>x.palmHeightCm)),meanMidpointAxialFraction:contact.reduce((a,x)=>a+x.midpointAxialFraction,0)/contact.length,meanMidpointTransverseFraction:contact.reduce((a,x)=>a+x.midpointTransverseFraction,0)/contact.length,frames:contact};}
 if(times.length&&!queries.every(q=>q.local))d.contactGeometry=values.flatMap((v,i)=>times[i]>=.27&&times[i]<=.60?[{time:times[i],elbow:v.elbow,palm:v.palm,tip:v.tip,pronation:v.pronation??0}]:[]);
 if(times.length&&!queries.every(q=>q.local))d.keyGeometry=[0,.23,.30,.40,.48,.57,.82,1].map(t=>{
  const i=times.reduce((best,time,index)=>Math.abs(time-t)<Math.abs(times[best]-t)?index:best,0),v=values[i];
  return{time:times[i],shoulder:queries[i].shoulder,parent:queries[i].parent,elbow:v.elbow,palm:v.palm,tip:v.tip,bladeNormal:v.bladeNormal,pronation:v.pronation??0};
 });
 if(Math.max(...d.maxWorldStepDegrees)>maxStepDegrees)throw new Error(`Sword gesture exceeds ${maxStepDegrees} deg/frame: ${d.maxWorldStepDegrees}`);
 if(loop)for(const id of [queries[0].side+'_arm',queries[0].side+'_forearm',queries[0].side+'_hand'])
  if(values[0].pose[id].rotation.some((v,i)=>Math.abs(v-values.at(-1).pose[id].rotation[i])>1e-7))throw new Error('Sword gesture loop does not close');
 const poses=values.map(x=>x.pose);Object.defineProperty(poses,'diagnostics',{value:d});return poses;
}
