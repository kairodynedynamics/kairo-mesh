// Writes test vectors from the reference (JavaScript) implementation for the Kotlin port to check against.
//   node android/vectors.mjs
import { writeFileSync } from 'node:fs';
import { rng } from '../src/rng.js';
import { encoder } from '../src/fountain.js';
import { ephemeralId } from '../src/ids.js';
import { createIdentity, seal, sha256, recipientTag, recognitionKey } from '../src/crypto.js';
import { createNode } from '../src/node.js';

const T = 1_800_000_123_456;
const hex = (b) => Buffer.from(b).toString('hex');
const ali = createIdentity(Buffer.from('11'.repeat(32), 'hex'));
const bora = createIdentity(Buffer.from('22'.repeat(32), 'hex'));

const r = rng(0xdeadbeef);
const message = Buffer.from(Array.from({ length: 1000 }, (_, i) => (i * 7 + 3) & 255));
const enc = encoder(message);

// A real exchange: Ali's node hands a message to Bora after Bora answers "that one is mine".
const text = 'deprem sonrası buluşma: okul bahçesi ✓';
const frames = [];
const node = createNode({ identity: ali, now: () => T, contacts: new Map([['bora', bora.pub]]), send: (f) => frames.push(f) });
node.sendMessage('bora', text);
node.tick();
const aliEid = frames[0].subarray(1, 9), handle = frames[0].subarray(10, 18);
const boraEid = ephemeralId(recognitionKey(bora.pub), T);
node.receive(Buffer.concat([Buffer.from([2]), boraEid, aliEid, handle, Buffer.from([4]), Buffer.alloc(8)]));

writeFileSync(new URL('./app/src/test/resources/vectors.json', import.meta.url), JSON.stringify({
  rng: { seed: 0xdeadbeef, out: Array.from({ length: 5 }, () => r()) },
  fountain: { message: hex(message), k: enc.k, packets: [0, 1, 2, enc.k, enc.k + 5, 123456789, 0xffffffff].map((s) => ({ seed: s, data: hex(enc.packet(s).data) })) },
  ids: { key: hex(sha256('k')), time: T, eid: hex(ephemeralId(sha256('k'), T)) },
  keys: { aliSecret: hex(ali.secret), aliPub: hex(ali.pub), boraSecret: hex(bora.secret), boraPub: hex(bora.pub),
    boraTag: hex(recipientTag(bora.pub, T)), boraRk: hex(recognitionKey(bora.pub)) },
  seal: { body: hex(Buffer.from('merhaba')), envelope: hex(seal(ali, bora.pub, Buffer.from('merhaba'))) },
  exchange: { time: T, text, frames: frames.filter((f) => f[0] === 3).map(hex) },
}, null, 1));
console.log('wrote vectors.json');
