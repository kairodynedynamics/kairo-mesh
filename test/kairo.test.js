import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { encoder, decoder } from '../src/fountain.js';
import { ephemeralId, recognise, EPOCH_MS } from '../src/ids.js';
import { simulate } from '../src/sim.js';

test('fountain: rebuilds the message from any mix of packets, ignoring garbage', () => {
  for (const size of [0, 1, 465, 466, 5000, 40000]) {
    const msg = randomBytes(size);
    const enc = encoder(msg);
    const dec = decoder(enc.k);
    assert.equal(dec.add({ seed: 1, data: Buffer.alloc(3) }), false);
    assert.equal(dec.add(null), false);
    // drop every third plain fragment, then feed coded packets until complete
    for (let s = 0; s < enc.k; s++) if (s % 3) dec.add(enc.packet(s));
    let extra = 0;
    for (let s = 1e6; !dec.done; s++, extra++) dec.add(enc.packet(s));
    assert.ok(extra <= Math.ceil(enc.k / 3) + 12, `overhead too high: ${extra}`);
    assert.deepEqual(dec.result(), msg);
  }
});

test('fountain: refuses to decode early', () => {
  const enc = encoder(randomBytes(2000));
  const dec = decoder(enc.k);
  dec.add(enc.packet(0));
  assert.throws(() => dec.result());
  assert.throws(() => decoder(0));
});

test('ids: rotate each epoch, recognisable only with the key', () => {
  const key = randomBytes(32), t = 1_800_000_000_000;
  const contacts = new Map([['ayse', key]]);
  assert.notDeepEqual(ephemeralId(key, t), ephemeralId(key, t + EPOCH_MS));
  assert.equal(recognise(ephemeralId(key, t), contacts, t), 'ayse');
  assert.equal(recognise(ephemeralId(key, t), contacts, t + EPOCH_MS), 'ayse'); // clock skew
  assert.equal(recognise(ephemeralId(key, t), contacts, t + 3 * EPOCH_MS), null);
  assert.equal(recognise(ephemeralId(randomBytes(32), t), contacts, t), null);
  assert.equal(recognise(Buffer.alloc(2), contacts, t), null);
});

test('sim: deterministic, and flooding everyone is the delivery ceiling', () => {
  const small = { hours: 3, sendUntilH: 2, messages: 60, seed: 5 };
  const focus = simulate({ ...small, policy: 'focus' });
  assert.deepEqual(focus, simulate({ ...small, policy: 'focus' }));
  const wait = simulate({ ...small, policy: 'wait' });
  const epidemic = simulate({ ...small, policy: 'epidemic' });
  assert.ok(epidemic.delivery >= focus.delivery && epidemic.delivery >= wait.delivery);
  assert.ok(wait.txPerMessage <= 3); // L=4 spray can never hand over more than 3 times
});
