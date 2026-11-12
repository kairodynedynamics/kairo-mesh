#!/usr/bin/env node
// Chat over the local network (UDP multicast stands in for the phone radio).
//   node src/cli.js [profile.json]
//   /id                 show my pairing code
//   /add <name> <code>  add a contact
//   <name> <text>       send a message
import dgram from 'node:dgram';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { createIdentity } from './crypto.js';
import { createNode } from './node.js';

const GROUP = '239.77.65.73', PORT = 47474;
const file = process.argv[2] ?? 'kairo-profile.json';
// shortcut: the private key sits unencrypted in this file; use the OS keystore before real use.
const saved = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : { contacts: {} };
const identity = createIdentity(saved.secret && Buffer.from(saved.secret, 'base64'));
const save = () => writeFileSync(file, JSON.stringify({ ...saved, secret: identity.secret.toString('base64') }, null, 2), { mode: 0o600 });
save();

const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
const node = createNode({
  identity,
  contacts: new Map(Object.entries(saved.contacts).map(([n, c]) => [n, Buffer.from(c, 'base64')])),
  send: (frame) => socket.send(frame, PORT, GROUP),
  onMessage: (from, text) => console.log(`\n[${from}] ${text}`),
});
socket.on('message', (frame) => node.receive(frame));
socket.bind(PORT, () => {
  socket.addMembership(GROUP);
  socket.setMulticastLoopback(true);
  setInterval(() => node.tick(), 2000);
  console.log(`Kairo ready. Pairing code: ${identity.pub.toString('base64')}`);
});

createInterface({ input: process.stdin }).on('close', () => process.exit(0)).on('line', (line) => {
  const [head, ...rest] = line.trim().split(/\s+/);
  try {
    if (head === '/id') console.log(identity.pub.toString('base64'));
    else if (head === '/add') {
      node.addContact(rest[0], Buffer.from(rest[1] ?? '', 'base64'));
      saved.contacts[rest[0]] = rest[1];
      save();
      console.log(`added ${rest[0]}`);
    } else if (head) { node.sendMessage(head, rest.join(' ')); console.log('queued'); }
  } catch (e) { console.log(`error: ${e.message}`); }
});
