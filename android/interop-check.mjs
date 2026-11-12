// Checks frames produced by the Kotlin port (written by InteropTest) against the JavaScript node.
//   node android/interop-check.mjs
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createIdentity, open } from '../src/crypto.js';
import { createNode } from '../src/node.js';

const x = JSON.parse(readFileSync(new URL('./app/build/interop-from-kotlin.json', import.meta.url), 'utf8'));
const ali = createIdentity(Buffer.from('11'.repeat(32), 'hex'));
const bora = createIdentity(Buffer.from('22'.repeat(32), 'hex'));

const opened = open(bora, Buffer.from(x.envelope, 'hex'));
assert.deepEqual(opened.senderPub, ali.pub);
assert.equal(opened.body.toString(), 'merhaba');

const inbox = [], out = [];
const node = createNode({ identity: bora, now: () => x.time, contacts: new Map([['ali', ali.pub]]),
  send: (f) => out.push(f), onMessage: (from, text) => inbox.push([from, text]) });
for (const f of x.frames) node.receive(Buffer.from(f, 'hex'));
assert.deepEqual(inbox, [['ali', x.text]]);
assert.ok(out.some((f) => f[0] === 4), 'delivery receipt sent');
console.log('interop ok: JavaScript opened the Kotlin envelope and received the Kotlin node\'s message');
