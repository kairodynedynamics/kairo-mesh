import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createIdentity, noiseXWrite, noiseXRead } from '../src/crypto.js';

// The Kotlin and Swift ports check the same published bytes through android/vectors.mjs.
const raw = JSON.parse(readFileSync(new URL('./noise-x-vector.json', import.meta.url)));
const v = Object.fromEntries(Object.entries(raw).filter(([k]) => k !== 'source').map(([k, x]) => [k, Buffer.from(x, 'hex')]));

test('noise: the envelope is Noise_X_25519_ChaChaPoly_SHA256, matching a published test vector', () => {
  const init = createIdentity(v.initStatic), resp = createIdentity(v.respStatic);
  const out = noiseXWrite(init, resp.pub, v.payload, v.prologue, createIdentity(v.initEphemeral));
  assert.equal(out.toString('hex'), raw.ciphertext);
  const got = noiseXRead(resp, v.ciphertext, v.prologue);
  assert.deepEqual(got.senderPub, init.pub);
  assert.deepEqual(got.payload, v.payload);
});

test('noise: the prologue is bound, so an envelope from another protocol version does not open', () => {
  const resp = createIdentity(v.respStatic);
  assert.throws(() => noiseXRead(resp, v.ciphertext, Buffer.from('another protocol')));
});
