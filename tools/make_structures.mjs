// Generates the empty 5x4x5 stone-floored structure used by gametests.
// Output: src/main/resources/data/actioncombat/structure/combat_arena.nbt (gzip NBT)
import { gzipSync } from 'node:zlib';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

function str(s) {
  const b = Buffer.from(s, 'utf8');
  const head = Buffer.alloc(2);
  head.writeUInt16BE(b.length);
  return Buffer.concat([head, b]);
}
function named(type, name, payload) {
  return Buffer.concat([Buffer.from([type]), str(name), payload]);
}
function i32(v) {
  const b = Buffer.alloc(4);
  b.writeInt32BE(v);
  return b;
}
const TAG = { end: 0, int: 3, string: 8, list: 9, compound: 10 };

function intList(values) {
  return Buffer.concat([
    Buffer.from([TAG.int]),
    i32(values.length),
    ...values.map(i32),
  ]);
}
function compound(body) {
  return Buffer.concat([body, Buffer.from([TAG.end])]);
}

const stone = compound(named(TAG.string, 'Name', str('minecraft:stone')));

const blocks = [];
for (let x = 0; x < 5; x++) {
  for (let z = 0; z < 5; z++) {
    blocks.push(compound(
      Buffer.concat([
        named(TAG.list, 'pos', intList([x, 0, z])),
        named(TAG.int, 'state', i32(0)),
      ])
    ));
  }
}

const rootBody = Buffer.concat([
  named(TAG.list, 'size', intList([5, 4, 5])),
  named(TAG.list, 'entities', Buffer.from([TAG.end, 0, 0, 0, 0])),
  named(TAG.list, 'palette', Buffer.concat([
    Buffer.from([TAG.compound]),
    i32(1),
    stone,
  ])),
  named(TAG.list, 'blocks', Buffer.concat([
    Buffer.from([TAG.compound]),
    i32(blocks.length),
    ...blocks,
  ])),
  named(TAG.int, 'DataVersion', i32(4561)),
]);

const nbt = Buffer.concat([Buffer.from([TAG.compound]), str(''), rootBody, Buffer.from([TAG.end])]);
const out = join(root, 'src/main/resources/data/actioncombat/structure/combat_arena.nbt');
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, gzipSync(nbt));
console.log('wrote', out, nbt.length, 'bytes raw');
