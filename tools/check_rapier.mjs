import fs from 'node:fs';import assert from 'node:assert/strict';import {bladePoint} from './fp_projection.mjs';
const root=new URL('../',import.meta.url);const load=p=>JSON.parse(fs.readFileSync(new URL(p,root)));
const prefix='src/main/resources/assets/actioncombat/geckolib/';
const single=load(prefix+'models/player_longsword.geo.json')['minecraft:geometry'][0];
const dual=load(prefix+'models/player_doublesword.geo.json')['minecraft:geometry'][0];
const bones=new Map(single.bones.map(b=>[b.name,b]));
for(const id of ['upper_arm_r','forearm_r','hand_r','leg_r']){
 assert.ok(bones.get(id).pivot[0]<0,id+': anatomical right must match the dual rig');
 assert.equal(Math.sign(bones.get(id).pivot[0]),Math.sign(dual.bones.find(b=>b.name===id).pivot[0]));
}
assert.equal(bones.get('hand_item_r').parent,'hand_r');
assert.ok(bones.get('upper_arm_l').pivot[0]>0);
const clips=load(prefix+'animations/third_person_singlesword.animation.json').animations;
for(const [clip,t] of [['static_idle',0],['attack1',.16],['attack2',.16],['attack3',.16],['attack4',.16],['rapier_thrust_hit',0],['rapier_thrust_miss',0]]){
 const h=bladePoint(bones,clips[clip],'hand_item_r',t,0),tip=bladePoint(bones,clips[clip],'hand_item_r',t,16);
 assert.ok(h[0]>0,clip+': sword must be on anatomical right, not the empty left hand');
 assert.ok(tip[2]<h[2]-15.8 && Math.abs(tip[0]-h[0])<.1 && Math.abs(tip[1]-h[1])<.1,clip+': sword must point forward');
}
for(const file of ['third_person_doublesword','fp_doublesword']){
 const geo=load(prefix+'models/'+(file.startsWith('fp_')?'fp_doublesword':'player_doublesword')+'.geo.json')['minecraft:geometry'][0];
 const names=new Set(geo.bones.map(b=>b.name));
 const anim=load(prefix+'animations/'+file+'.animation.json').animations;
 for(const bone of Object.keys(anim.guard_bash_upper.bones))assert.ok(names.has(bone),file+': unknown bash bone '+bone);
}
console.log('Rapier handedness, right-hand attachment, 7 forward-pointing contact poses and bash bones passed');
