import fs from 'node:fs';
import path from 'node:path';
import {authorThirdPerson, authorFirstPerson} from './author_rapier.mjs';
const ROOT=path.resolve(import.meta.dirname,'..');
const A=path.join(ROOT,'src/main/resources/assets/actioncombat/geckolib/animations');
for (const [file,author] of [['third_person_singlesword',authorThirdPerson],['fp_singlesword',authorFirstPerson]]) {
    const p=path.join(A,file+'.animation.json');
    const doc=JSON.parse(fs.readFileSync(p));
    author(doc,ROOT);
    fs.writeFileSync(p,JSON.stringify(doc,null,2)+'\n');
}
for (const file of ['third_person_doublesword', 'fp_doublesword']) {
    const doc = JSON.parse(fs.readFileSync(path.join(A, file + '.animation.json')));
    const base = structuredClone(doc.animations.defend_upper || doc.animations.defend_1 || doc.animations.static_idle);
    base.loop = false;
    base.animation_length = .25;
    // A short torso/forearm drive while the guard is raised.
    for (const bone of ['upper_body', 'upper_arm_r', 'upper_arm_l'])
        base.bones[bone] = { rotation: { '0.00': [0, 0, 0], '0.08': [18, 0, 0], '0.25': [0, 0, 0] } };
    if (file === 'fp_doublesword') {
        const map = {
            upper_arm_r: 'right_arm', upper_arm_l: 'left_arm',
            forearm_r: 'right_sleve_bottom', forearm_l: 'left_arm_bottom',
            hand_item_r: 'right_arm_item', hand_item_l: 'left_arm_item'
        };
        base.bones = Object.fromEntries(Object.entries(base.bones).filter(([b]) => b !== 'head')
            .map(([b, c]) => [map[b] || b, c]));
    }
    doc.animations.guard_bash = base;
    doc.animations.guard_bash_upper = structuredClone(base);
    fs.writeFileSync(path.join(A, file + '.animation.json'), JSON.stringify(doc, null, 2) + '\n');
}

function fpReport(label, right, pos, elbow, blade) {
    const hilt = fpCam(right, pos, elbow, blade, 'elucidator', [0, 0, 0]);
    const tip = fpCam(right, pos, elbow, blade, 'elucidator', [0, 15, 0]);
    const hand = fpCam(right, pos, elbow, blade, 'fp_forearm_r', [0, -6, 0]);
    return { label, hilt, tip, handHilt: Math.hypot(...hand.map((v, i) => v - hilt[i])) };
}
console.log('Rapier: articulated centred FP, edge-aligned cuts, chest gather/thrust, linear left-hip sheath');
