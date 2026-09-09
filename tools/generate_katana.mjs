#!/usr/bin/env node
// Katana (左手拔刀) animation set.
//
// Donor: singlesword locomotion / inspect / guard / FP rig. Stance and the four
// two-handed slashes are authored here. Output:
//   third_person_katana.animation.json  (player_longsword geo)
//   fp_katana.animation.json            (fp_singlesword geo)
//
// FP keyframes are plain arrays — GeckoLib 5.4.5 drops bedrock {post, lerp_mode}
// objects on the viewmodel path. 3P uses catmullrom for a heavier body.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const A = path.join(ROOT, 'src/main/resources/assets/actioncombat/geckolib/animations');

const UPPER = new Set(['upper_body', 'head', 'upper_arm_r', 'forearm_r', 'hand_r',
    'hand_item_r', 'upper_arm_l', 'forearm_l', 'hand_l', 'hand_item_l']);

function overlay(c) {
    return {
        ...structuredClone(c),
        bones: Object.fromEntries(Object.entries(c.bones).filter(([b]) => UPPER.has(b))),
    };
}

function rotTrack(kfs) {
    const out = {};
    for (const t of Object.keys(kfs).map(Number).sort((a, b) => a - b)) {
        out[t.toFixed(2)] = { post: kfs[t].map(n => Math.round(n * 1000) / 1000), lerp_mode: 'catmullrom' };
    }
    return out;
}

function plain(kfs, digits = 2) {
    const out = {};
    for (const t of Object.keys(kfs).map(Number).sort((a, b) => a - b)) {
        out[t.toFixed(digits)] = kfs[t].map(n => Math.round(n * 1000) / 1000);
    }
    return out;
}

// ---------------------------------------------------------------------------
// Third-person: 中段構え, both hands on the tsuka, blade tipped forward.
// ---------------------------------------------------------------------------

const STANCE = {
    root: [0, 0, 0], lower_body: [0, 0, 0],
    upper_body: [6, -16, 0], head: [-2, 16, 0],
    upper_arm_r: [-58, 26, 10], forearm_r: [-46, 0, 0], hand_r: [0, 0, 0], hand_item_r: [76, 6, 0],
    upper_arm_l: [-62, -24, -30], forearm_l: [-58, 0, 0], hand_l: [10, 0, 0],
    leg_r: [-12, -18, 0], lower_leg_r: [16, 0, 0], foot_r: [0, 0, 0],
    leg_l: [8, -22, 0], lower_leg_l: [10, 0, 0], foot_l: [0, 0, 0],
};

function tpClip(length, poses) {
    const bones = {};
    const times = new Set([0, length]);
    for (const keys of Object.values(poses)) for (const t of Object.keys(keys)) times.add(Number(t));
    const sorted = [...times].sort((a, b) => a - b);
    for (const bone of Object.keys(STANCE)) {
        const keys = poses[bone] || {};
        const track = {};
        for (const t of sorted) {
            let v = STANCE[bone], best = -1;
            if (t < length - 1e-9) {
                for (const [kt, kv] of Object.entries(keys)) {
                    const n = Number(kt);
                    if (n <= t + 1e-9 && n > best) { best = n; v = kv; }
                }
            }
            if (keys[t] !== undefined) v = keys[t];
            track[t.toFixed(2)] = { post: v, lerp_mode: 'catmullrom' };
        }
        bones[bone] = { rotation: track };
    }
    return { loop: false, animation_length: length, bones };
}

// 袈裟斬り: chamber over the right shoulder, cut diagonally down-left.
const K1 = tpClip(0.40, {
    upper_body: { 0.10: [-10, -38, 10], 0.16: [14, 6, -8], 0.26: [10, 0, -4] },
    head: { 0.10: [6, 28, -4], 0.16: [-8, 8, 4], 0.26: [-4, 12, 2] },
    upper_arm_r: { 0.10: [-150, 42, 18], 0.16: [-18, 8, -12], 0.26: [-28, 12, -6] },
    forearm_r: { 0.10: [-20, 0, 0], 0.16: [-8, 0, 0], 0.26: [-16, 0, 0] },
    hand_item_r: { 0.10: [64, 12, 8], 0.16: [88, -6, -10], 0.26: [82, 0, -4] },
    upper_arm_l: { 0.10: [-138, -8, -22], 0.16: [-24, -28, -18], 0.26: [-34, -26, -22] },
    forearm_l: { 0.10: [-28, 0, 0], 0.16: [-36, 0, 0], 0.26: [-44, 0, 0] },
    lower_body: { 0.10: [-4, 8, 0], 0.16: [6, -8, 0], 0.26: [4, -4, 0] },
    leg_r: { 0.10: [-8, -18, 0], 0.16: [-36, -18, 0], 0.26: [-28, -18, 0] },
    lower_leg_r: { 0.10: [12, 0, 0], 0.16: [40, 0, 0], 0.26: [32, 0, 0] },
    leg_l: { 0.10: [12, -22, 0], 0.16: [22, -22, 0], 0.26: [16, -22, 0] },
    lower_leg_l: { 0.10: [8, 0, 0], 0.16: [4, 0, 0], 0.26: [6, 0, 0] },
});

// 横一文字: coil right, cut horizontally through the torso line.
const K2 = tpClip(0.40, {
    upper_body: { 0.10: [2, -48, 12], 0.16: [4, 18, -10], 0.26: [4, 8, -4] },
    head: { 0.10: [0, 36, -6], 0.16: [0, 4, 6], 0.26: [0, 10, 2] },
    upper_arm_r: { 0.10: [-70, 62, 8], 0.16: [-64, -18, -8], 0.26: [-60, -6, 0] },
    forearm_r: { 0.10: [-38, 0, 0], 0.16: [-6, 0, 0], 0.26: [-18, 0, 0] },
    hand_item_r: { 0.10: [74, 16, 6], 0.16: [80, -12, -8], 0.26: [78, 0, 0] },
    upper_arm_l: { 0.10: [-64, -40, -36], 0.16: [-56, 8, -16], 0.26: [-58, -8, -22] },
    forearm_l: { 0.10: [-62, 0, 0], 0.16: [-24, 0, 0], 0.26: [-40, 0, 0] },
    lower_body: { 0.10: [0, 8, 0], 0.16: [0, -8, 0], 0.26: [0, -4, 0] },
    leg_r: { 0.10: [-10, -18, 0], 0.16: [-32, -18, 0], 0.26: [-24, -18, 0] },
    lower_leg_r: { 0.10: [14, 0, 0], 0.16: [36, 0, 0], 0.26: [28, 0, 0] },
    leg_l: { 0.10: [10, -22, 0], 0.16: [24, -22, 0], 0.26: [16, -22, 0] },
    lower_leg_l: { 0.10: [8, 0, 0], 0.16: [4, 0, 0], 0.26: [6, 0, 0] },
});

// 逆袈裟: from a low left chamber, cut rising to the upper right.
const K3 = tpClip(0.40, {
    upper_body: { 0.09: [12, 8, -8], 0.15: [-10, -28, 8], 0.24: [-6, -20, 4] },
    head: { 0.09: [8, 8, 4], 0.15: [-6, 22, -2], 0.24: [-4, 18, 0] },
    upper_arm_r: { 0.09: [-16, 4, -16], 0.15: [-132, 30, 12], 0.24: [-118, 28, 8] },
    forearm_r: { 0.09: [-62, 0, 0], 0.15: [-12, 0, 0], 0.24: [-20, 0, 0] },
    hand_item_r: { 0.09: [96, -10, -8], 0.15: [70, 10, 8], 0.24: [74, 6, 4] },
    upper_arm_l: { 0.09: [-18, -32, -18], 0.15: [-118, -16, -28], 0.24: [-104, -20, -28] },
    forearm_l: { 0.09: [-70, 0, 0], 0.15: [-30, 0, 0], 0.24: [-40, 0, 0] },
    lower_body: { 0.09: [6, -4, 0], 0.15: [-4, 4, 0], 0.24: [-2, 2, 0] },
    leg_r: { 0.09: [-22, -18, 0], 0.15: [-8, -18, 0], 0.24: [-12, -18, 0] },
    lower_leg_r: { 0.09: [28, 0, 0], 0.15: [12, 0, 0], 0.24: [16, 0, 0] },
    leg_l: { 0.09: [16, -22, 0], 0.15: [6, -22, 0], 0.24: [8, -22, 0] },
    lower_leg_l: { 0.09: [10, 0, 0], 0.15: [6, 0, 0], 0.24: [8, 0, 0] },
});

// 真向斬り: both hands overhead, straight vertical chop.
const K4 = tpClip(0.40, {
    upper_body: { 0.10: [-12, -12, 0], 0.16: [16, -12, 0], 0.26: [10, -14, 0] },
    head: { 0.10: [8, 14, 0], 0.16: [-10, 14, 0], 0.26: [-6, 16, 0] },
    upper_arm_r: { 0.10: [-168, 18, 8], 0.16: [-22, 22, 6], 0.26: [-34, 24, 8] },
    forearm_r: { 0.10: [-8, 0, 0], 0.16: [-4, 0, 0], 0.26: [-18, 0, 0] },
    hand_item_r: { 0.10: [58, 0, 0], 0.16: [92, 0, 0], 0.26: [84, 4, 0] },
    upper_arm_l: { 0.10: [-160, -16, -18], 0.16: [-26, -22, -24], 0.26: [-38, -22, -26] },
    forearm_l: { 0.10: [-12, 0, 0], 0.16: [-18, 0, 0], 0.26: [-36, 0, 0] },
    lower_body: { 0.10: [-4, 0, 0], 0.16: [6, 0, 0], 0.26: [4, 0, 0] },
    leg_r: { 0.10: [-8, -18, 0], 0.16: [-30, -18, 0], 0.26: [-22, -18, 0] },
    lower_leg_r: { 0.10: [12, 0, 0], 0.16: [34, 0, 0], 0.26: [26, 0, 0] },
    leg_l: { 0.10: [10, -22, 0], 0.16: [20, -22, 0], 0.26: [14, -22, 0] },
    lower_leg_l: { 0.10: [8, 0, 0], 0.16: [4, 0, 0], 0.26: [6, 0, 0] },
});

// 居合: blade flashes out in a wide arc, then both hands settle on the hilt.
const DRAW = tpClip(0.7333, {
    upper_body: { 0.00: [0, -28, 0], 0.22: [0, -46, 8], 0.38: [8, 10, -8], 0.55: [6, -10, -2] },
    head: { 0.00: [0, 24, 0], 0.22: [0, 36, -4], 0.38: [-4, 6, 4], 0.55: [-2, 14, 0] },
    upper_arm_r: { 0.00: [-28, 40, 8], 0.22: [-62, 58, 10], 0.38: [-72, -8, -6], 0.55: [-60, 16, 6] },
    forearm_r: { 0.00: [-72, 0, 0], 0.22: [-40, 0, 0], 0.38: [-8, 0, 0], 0.55: [-32, 0, 0] },
    hand_item_r: { 0.00: [64, 0, 0], 0.22: [80, 10, 8], 0.38: [90, -8, -6], 0.55: [78, 4, 0] },
    upper_arm_l: { 0.00: [-16, -30, -36], 0.22: [-48, -36, -40], 0.38: [-58, 4, -16], 0.55: [-60, -18, -26] },
    forearm_l: { 0.00: [-52, 0, 0], 0.22: [-70, 0, 0], 0.38: [-22, 0, 0], 0.55: [-48, 0, 0] },
    lower_body: { 0.22: [0, 6, 0], 0.38: [0, -6, 0], 0.55: [0, -2, 0] },
    leg_r: { 0.22: [-10, -18, 0], 0.38: [-30, -18, 0], 0.55: [-16, -18, 0] },
    lower_leg_r: { 0.22: [14, 0, 0], 0.38: [34, 0, 0], 0.55: [20, 0, 0] },
    leg_l: { 0.22: [10, -22, 0], 0.38: [22, -22, 0], 0.55: [10, -22, 0] },
});

const PUT_AWAY = tpClip(0.7333, {
    upper_body: { 0.20: [4, -24, 0], 0.45: [2, -30, 0] },
    upper_arm_r: { 0.20: [-36, 34, 8], 0.45: [-24, 40, 6] },
    forearm_r: { 0.20: [-60, 0, 0], 0.45: [-70, 0, 0] },
    hand_item_r: { 0.20: [70, 4, 0], 0.45: [62, 0, 0] },
    upper_arm_l: { 0.20: [-36, -28, -32], 0.45: [-22, -30, -36] },
    forearm_l: { 0.20: [-62, 0, 0], 0.45: [-52, 0, 0] },
});

const single = JSON.parse(fs.readFileSync(path.join(A, 'third_person_singlesword.animation.json'), 'utf8'));
const katana = structuredClone(single);
katana.animations.static_idle = {
    loop: true,
    animation_length: 3.2,
    bones: Object.fromEntries(Object.entries(STANCE).map(([bone, v]) => [bone, {
        rotation: rotTrack({ 0: v, 1.6: v.map((n, i) => n + (i === 0 ? 1.4 : 0)), 3.2: v }),
    }])),
};
katana.animations.draw = DRAW;
katana.animations.put_away = PUT_AWAY;
for (const [name, c] of Object.entries({ attack1: K1, attack2: K2, attack3: K3, attack4: K4 })) {
    katana.animations[name] = c;
    katana.animations[name + '_upper'] = overlay(c);
}

// Walk/run/guard keep the two-handed grip on top of the copied gait.
for (const [name, c] of Object.entries(katana.animations)) {
    if (!/^(walk|run|crouch|jump|fall|idle_upper|aiming|defend|inspect)/.test(name)) continue;
    c.bones ??= {};
    for (const bone of UPPER) {
        if (!STANCE[bone]) continue;
        c.bones[bone] = { rotation: { '0.00': STANCE[bone] } };
    }
}

fs.writeFileSync(path.join(A, 'third_person_katana.animation.json'), JSON.stringify(katana, null, 2));

// ---------------------------------------------------------------------------
// First-person: both hands stay on the hilt. Plain arrays only.
// ---------------------------------------------------------------------------

const FP_IDLE_R = [-50, -6, 4];
const FP_IDLE_L = [-44, 16, -10];
const FP_IDLE_L_POS = [-3.4, -3.0, 0.8];
const FP_IDLE_B = [20, 4, 0];

function fpClip(length, rightRot, rightPos, leftRot, leftPos, bladeRot, bladePos) {
    return {
        loop: false,
        animation_length: length,
        bones: {
            right: {
                rotation: plain({ 0: FP_IDLE_R, ...rightRot, [length]: FP_IDLE_R }),
                position: plain({ 0: [0, 0, 0], ...rightPos, [length]: [0, 0, 0] }),
            },
            left: {
                rotation: plain({ 0: FP_IDLE_L, ...leftRot, [length]: FP_IDLE_L }),
                position: plain({ 0: FP_IDLE_L_POS, ...leftPos, [length]: FP_IDLE_L_POS }),
            },
            elucidator: {
                rotation: plain({ 0: FP_IDLE_B, ...bladeRot, [length]: FP_IDLE_B }),
                position: plain({ 0: [0, 0, 0], ...bladePos, [length]: [0, 0, 0] }),
            },
        },
    };
}

const FP_IDLE = {
    loop: true,
    animation_length: 3.2,
    bones: {
        right: {
            rotation: plain({ 0: FP_IDLE_R, 1.6: [-51.2, -6, 4], 3.2: FP_IDLE_R }),
            position: { '0.00': [0, 0, 0] },
        },
        left: {
            rotation: plain({ 0: FP_IDLE_L, 1.6: [-45.2, 16, -10], 3.2: FP_IDLE_L }),
            position: { '0.00': FP_IDLE_L_POS },
        },
        elucidator: {
            rotation: plain({ 0: FP_IDLE_B, 1.6: [21.2, 4, 0], 3.2: FP_IDLE_B }),
            position: { '0.00': [0, 0, 0] },
        },
    },
};

// 袈裟: raise both hands, cut down-left across the view.
const F1 = fpClip(0.40,
    { 0.10: [-18, 18, 8], 0.16: [-88, -16, -10], 0.26: [-70, -8, -4] },
    { 0.10: [-8, 14, -12], 0.16: [8, -10, 22], 0.26: [3, -4, 8] },
    { 0.10: [-14, 28, -6], 0.16: [-80, 4, -16], 0.26: [-62, 10, -12] },
    { 0.10: [-8, 10, -8], 0.16: [4, -8, 18], 0.26: [-1, -2, 6] },
    { 0.10: [36, 10, 8], 0.16: [-8, -12, -8], 0.26: [8, -4, -2] },
    { 0.10: [-4, 10, -10], 0.16: [4, -8, 24], 0.26: [1, -3, 10] });

// 横斩: both hands sweep right-to-left.
const F2 = fpClip(0.40,
    { 0.10: [-62, -28, -8], 0.16: [-48, 28, 10], 0.26: [-52, 10, 4] },
    { 0.10: [-14, 6, -8], 0.16: [14, -6, 16], 0.26: [6, -2, 6] },
    { 0.10: [-56, -4, -16], 0.16: [-40, 32, -4], 0.26: [-46, 20, -8] },
    { 0.10: [-12, 4, -6], 0.16: [10, -4, 14], 0.26: [2, -1, 4] },
    { 0.10: [28, -16, 0], 0.16: [8, 18, 0], 0.26: [16, 6, 0] },
    { 0.10: [-10, 4, -8], 0.16: [10, -4, 18], 0.26: [4, -2, 6] });

// 逆袈裟: low left, rip up-right.
const F3 = fpClip(0.40,
    { 0.09: [-28, 22, 8], 0.15: [-96, -18, -8], 0.24: [-72, -8, -2] },
    { 0.09: [10, -10, -8], 0.15: [-10, 10, 18], 0.24: [-4, 4, 8] },
    { 0.09: [-22, 30, -6], 0.15: [-88, 0, -14], 0.24: [-64, 8, -10] },
    { 0.09: [6, -8, -6], 0.15: [-8, 8, 16], 0.24: [-2, 3, 6] },
    { 0.09: [32, 14, 4], 0.15: [-12, -14, -4], 0.24: [8, -4, 0] },
    { 0.09: [8, -8, -8], 0.15: [-8, 8, 22], 0.24: [-3, 3, 10] });

// 真向: both hands high, chop straight down.
const F4 = fpClip(0.40,
    { 0.10: [-12, 0, 0], 0.16: [-96, 0, 0], 0.26: [-68, -4, 0] },
    { 0.10: [-2, 16, -14], 0.16: [4, -12, 26], 0.26: [1, -4, 10] },
    { 0.10: [-8, 18, -8], 0.16: [-88, 12, -10], 0.26: [-60, 14, -10] },
    { 0.10: [-2, 12, -10], 0.16: [2, -10, 22], 0.26: [-1, -3, 8] },
    { 0.10: [40, 0, 0], 0.16: [-16, 0, 0], 0.26: [6, 0, 0] },
    { 0.10: [0, 12, -12], 0.16: [0, -10, 28], 0.26: [0, -4, 12] });

const fpSingle = JSON.parse(fs.readFileSync(path.join(A, 'fp_singlesword.animation.json'), 'utf8'));
const fpKatana = structuredClone(fpSingle);
fpKatana.animations.static_idle = FP_IDLE;
fpKatana.animations.attack1 = F1;
fpKatana.animations.attack2 = F2;
fpKatana.animations.attack3 = F3;
fpKatana.animations.attack4 = F4;
fs.writeFileSync(path.join(A, 'fp_katana.animation.json'), JSON.stringify(fpKatana, null, 2) + '\n');

console.log('katana animation sets written');
