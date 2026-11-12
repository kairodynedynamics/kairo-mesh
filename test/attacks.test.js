// Each test is an attack that worked against an earlier version of the node.
import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { createIdentity, seal, sha256, recipientTag } from '../src/crypto.js';
import { createNode } from '../src/node.js';
import { encoder, decoder } from '../src/fountain.js';
import { EPOCH_MS } from '../src/ids.js';

const SYM = 400, T0 = 1_800_000_000_000;

// One honest node on a bench: we see every frame it transmits and can inject any frame we like.
function bench(contactNames = ['bora']) {
  const clock = { t: T0 }, out = [], inbox = [];
  const ids = Object.fromEntries(['self', ...contactNames].map((n) => [n, createIdentity()]));
  const node = createNode({
    identity: ids.self, now: () => clock.t, send: (f) => out.push(f), onMessage: (from, text) => inbox.push([from, text]),
    contacts: new Map(contactNames.map((n) => [n, ids[n].pub])),
  });
  const tick = (ms = 2000) => { clock.t += ms; out.length = 0; node.tick(); };
  const hello = () => out.findLast((f) => f[0] === 1);
  return { node, ids, clock, out, inbox, tick, hello, eid: () => hello().subarray(1, 9) };
}

const u8 = (...v) => Buffer.from(v);
const scoreFrame = (from, to, handle, q, proof = randomBytes(8)) => Buffer.concat([u8(2), from, to, handle, u8(q), proof]);
const ackFrame = (from, to, id, proof) => Buffer.concat([u8(4), from, to, id, proof]);
function dataFrames(from, to, env, tag, rh = randomBytes(16), give = 1) {
  const enc = encoder(env, SYM), id = sha256(env, rh).subarray(0, 8);
  const head = Buffer.concat([u8(3), from, to, id, tag, rh, u8(give, enc.k >> 8, enc.k & 255)]);
  return Array.from({ length: enc.k }, (_, i) => {
    const seed = Buffer.alloc(4);
    seed.writeUInt32BE(i);
    return Buffer.concat([head, seed, enc.packet(i).data]);
  });
}

// The attacker asks for the first announced message under a fresh identity and tries to complete the hand-over.
function grab(b, q, honestAck = true) {
  b.tick();
  const me = randomBytes(8), victim = b.eid(), handle = b.hello().subarray(10, 18);
  b.node.receive(scoreFrame(me, victim, handle, q));
  const frames = b.out.filter((f) => f[0] === 3 && f.subarray(9, 17).equals(me));
  if (!frames.length) return 0;
  const dec = decoder(frames[0].readUInt16BE(58), SYM);
  for (const f of frames) dec.add({ seed: f.readUInt32BE(60), data: f.subarray(64) });
  const env = dec.result(), id = frames[0].subarray(17, 25);
  b.node.receive(ackFrame(me, victim, id, honestAck ? sha256(env, 'ack', me).subarray(0, 16) : randomBytes(16)));
  return frames.length;
}

test('tracking: what a device announces cannot be linked across identifier rotations', () => {
  const b = bench();
  b.node.sendMessage('bora', 'x');
  b.tick();
  const before = b.hello();
  b.tick(EPOCH_MS + 1000);
  const after = b.hello();
  assert.equal(before.length, after.length);
  for (let i = 1; i + 8 <= before.length; i += 8)
    assert.ok(!after.includes(before.subarray(i, i + 8)), 'an 8-byte field survived the rotation');
});

test('blackhole: lying about meeting the recipient cannot take the sender\'s last copy', () => {
  const b = bench();
  b.node.sendMessage('bora', 'x');
  assert.equal(b.node.copies, 4);
  for (let i = 0; i < 6; i++) grab(b, 3);
  assert.equal(b.node.carrying, 1);
  assert.ok(b.node.copies >= 1);
  // each stranger got at most half of what was left: 4 -> 2 -> 1, then nothing
  assert.equal(b.node.copies, 1);
});

test('forged ack: overhearing a hand-over is not enough to make a carrier give up copies', () => {
  const b = bench();
  b.node.sendMessage('bora', 'x');
  for (let i = 0; i < 3; i++) assert.ok(grab(b, 0, false) > 0);
  assert.equal(b.node.copies, 4);
});

test('claiming to be the recipient yields ciphertext but no deletion', () => {
  const b = bench();
  b.node.sendMessage('bora', 'x');
  assert.ok(grab(b, 4) > 0);
  assert.equal(b.node.copies, 4);
});

test('flooding: junk from strangers cannot crowd out mail we send or carry for contacts', () => {
  const b = bench();
  b.tick();
  const attacker = createIdentity();
  for (let i = 0; i < 60; i++) {
    const env = seal(attacker, attacker.pub, randomBytes(40));
    for (const f of dataFrames(randomBytes(8), b.eid(), env, randomBytes(16))) b.node.receive(f);
  }
  assert.equal(b.node.carrying, 20); // strangers are held to half the store
  for (let i = 0; i < 20; i++) b.node.sendMessage('bora', `mine ${i}`);
  assert.equal(b.node.carrying, 40);
  b.node.sendMessage('bora', 'one more'); // displaces junk, not our own mail
  assert.equal(b.node.carrying, 40);
});

test('flooding: open transfers from strangers cannot block a new one', () => {
  const b = bench();
  b.tick();
  const big = () => seal(createIdentity(), createIdentity().pub, randomBytes(3000));
  for (let i = 0; i < 40; i++) b.node.receive(dataFrames(randomBytes(8), b.eid(), big(), randomBytes(16))[0]);
  const env = seal(createIdentity(), createIdentity().pub, Buffer.from('late arrival'));
  for (const f of dataFrames(randomBytes(8), b.eid(), env, randomBytes(16))) b.node.receive(f);
  assert.equal(b.node.carrying, 1);
});

test('amplification: forged frames cannot make a node transmit without bound', () => {
  const b = bench();
  b.tick();

  b.node.receive(Buffer.concat([u8(1), randomBytes(8), u8(255), randomBytes(255 * 16)]));
  assert.equal(b.out.filter((f) => f[0] === 2).length, 0, 'oversized hello must be ignored');
  for (let i = 0; i < 100; i++) b.node.receive(Buffer.concat([u8(1), randomBytes(8), u8(24), randomBytes(24 * 16)]));
  assert.ok(b.out.filter((f) => f[0] === 2).length <= 48);

  b.node.sendMessage('bora', randomBytes(10000).toString('hex'));
  b.tick();
  const handle = b.hello().subarray(10, 18), me = randomBytes(8);
  for (let i = 0; i < 50; i++) b.node.receive(scoreFrame(me, b.eid(), handle, 4));
  const once = b.out.filter((f) => f[0] === 3).length;
  for (let i = 0; i < 50; i++) b.node.receive(scoreFrame(randomBytes(8), b.eid(), handle, 4));
  assert.ok(once > 0 && b.out.filter((f) => f[0] === 3).length <= 400);
});

test('replay: an old envelope is not delivered again after the recipient restarts', () => {
  const sender = createIdentity(), b = bench(['ali']);
  b.node.addContact('ali', sender.pub);
  b.tick();
  const stamp = (t) => { const s = Buffer.alloc(8); s.writeBigUInt64BE(BigInt(t)); return s; };
  const deliver = (sentAt, text) => {
    const receipt = randomBytes(16);
    const env = seal(sender, b.ids.self.pub, Buffer.concat([receipt, stamp(sentAt), Buffer.from(text)]));
    for (const f of dataFrames(randomBytes(8), b.eid(), env, recipientTag(b.ids.self.pub, b.clock.t), sha256(receipt).subarray(0, 16)))
      b.node.receive(f);
  };
  deliver(b.clock.t, 'fresh');
  deliver(b.clock.t - 26 * 3600000, 'stale');
  deliver(b.clock.t + 2 * 3600000, 'from the future');
  assert.deepEqual(b.inbox, [['ali', 'fresh']]);
});

test('replay: frames recorded on the air are not delivered again after the recipient app restarts', () => {
  const sender = createIdentity(), b = bench(['ali']);
  b.node.addContact('ali', sender.pub);
  b.tick();
  const sentAt = Buffer.alloc(8);
  sentAt.writeBigUInt64BE(BigInt(b.clock.t));
  const receipt = randomBytes(16);
  const env = seal(sender, b.ids.self.pub, Buffer.concat([receipt, sentAt, Buffer.from('bir kez')]));
  const recorded = dataFrames(randomBytes(8), b.eid(), env, recipientTag(b.ids.self.pub, b.clock.t), sha256(receipt).subarray(0, 16));
  for (const f of recorded) b.node.receive(f);
  const inbox = [];
  const restarted = createNode({ identity: b.ids.self, now: () => b.clock.t, send: () => {}, onMessage: (from, text) => inbox.push(text),
    contacts: new Map([['ali', sender.pub]]), delivered: b.node.delivered });
  for (const f of recorded) restarted.receive(f);
  assert.deepEqual(b.inbox, [['ali', 'bir kez']]);
  assert.deepEqual(inbox, []);
});
