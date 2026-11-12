import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { createIdentity, seal, open } from '../src/crypto.js';
import { rng } from '../src/rng.js';
import { world } from './world.js';

test('seal: only the recipient opens it, sender is authenticated, tampering fails', () => {
  const a = createIdentity(), b = createIdentity(), c = createIdentity();
  const env = seal(a, b.pub, Buffer.from('merhaba'));
  const got = open(b, env);
  assert.equal(got.body.toString(), 'merhaba');
  assert.deepEqual(got.senderPub, a.pub);
  assert.throws(() => open(c, env));
  env[env.length - 1] ^= 1;
  assert.throws(() => open(b, env));
  assert.equal(seal(a, b.pub, Buffer.from('x')).length, seal(a, b.pub, Buffer.from('a longer message')).length); // padding
  assert.deepEqual(createIdentity(a.secret).pub, a.pub);
});

test('node: a courier carries a message between two people who never meet', async () => {
  const w = world(['ali', 'kurye', 'bora']);
  w.nodes.ali.sendMessage('bora', 'deprem sonrası buluşma: okul bahçesi');
  w.link('ali', 'kurye');
  await w.run(3);
  assert.equal(w.nodes.kurye.carrying, 1);
  assert.deepEqual(w.inbox.kurye, []); // carrier cannot read it
  w.link('ali', 'kurye', false);
  w.link('kurye', 'bora');
  await w.run(3);
  assert.deepEqual(w.inbox.bora, [['ali', 'deprem sonrası buluşma: okul bahçesi']]);
  assert.equal(w.nodes.kurye.carrying, 0); // receipt purged the courier copy
  await w.run(3);
  assert.equal(w.inbox.bora.length, 1); // no duplicate delivery
});

test('node: a 20 KB message survives 30% frame loss', async () => {
  const w = world(['ali', 'bora'], 0.3, 3), text = randomBytes(10000).toString('hex');
  w.nodes.ali.sendMessage('bora', text);
  w.link('ali', 'bora');
  await w.run(12);
  assert.deepEqual(w.inbox.bora, [['ali', text]]);
});

test('node: forged and garbage frames neither crash a courier nor make it drop mail', async () => {
  const w = world(['ali', 'kurye', 'bora']);
  w.nodes.ali.sendMessage('bora', 'gizli');
  w.link('ali', 'kurye');
  await w.run(3);
  w.link('ali', 'kurye', false);
  assert.equal(w.nodes.kurye.carrying, 1);
  const r = rng(9);
  for (let i = 0; i < 300; i++) w.nodes.kurye.receive(randomBytes(1 + Math.floor(r() * 500)));
  for (const type of [1, 2, 3, 4])
    for (const len of [25, 40, 463]) w.nodes.kurye.receive(Buffer.concat([Buffer.from([type]), randomBytes(len)]));
  w.nodes.kurye.receive('not a buffer');
  assert.equal(w.nodes.kurye.carrying, 1);
  w.link('kurye', 'bora');
  await w.run(3);
  assert.deepEqual(w.inbox.bora, [['ali', 'gizli']]);
});
