// Real-world courier situations, played out on the simulated radio in world.js.
import test from 'node:test';
import assert from 'node:assert/strict';
import { world } from './world.js';

const meet = async (w, pairs, rounds = 3) => {
  for (const [a, b] of pairs) w.link(a, b);
  await w.run(rounds);
  for (const [a, b] of pairs) w.link(a, b, false);
};

// ali hands the message to every courier, then leaves.
async function loaded(names, couriers, text = 'okul bahçesi') {
  const w = world(names);
  w.nodes.ali.sendMessage('bora', text);
  for (const k of couriers) await meet(w, [['ali', k]]);
  for (const k of couriers) assert.equal(w.nodes[k].carrying, 1, `${k} did not take the message`);
  return w;
}

test('scenario: two couriers reach the recipient at the same moment', async () => {
  const w = await loaded(['ali', 'k1', 'k2', 'bora'], ['k1', 'k2']);
  await meet(w, [['k1', 'bora'], ['k2', 'bora']]);
  assert.deepEqual(w.inbox.bora, [['ali', 'okul bahçesi']]);
  assert.equal(w.nodes.k1.carrying, 0);
  assert.equal(w.nodes.k2.carrying, 0);
});

test('scenario: a crowd of couriers all in range of the recipient delivers once', async () => {
  const couriers = ['k1', 'k2', 'k3', 'k4', 'k5', 'k6'];
  const w = world(['ali', ...couriers, 'bora']);
  w.nodes.ali.sendMessage('bora', 'x');
  // the copies spread courier to courier, as in a crowd
  await meet(w, couriers.map((k) => ['ali', k]).concat(couriers.slice(1).map((k, i) => [couriers[i], k])), 6);
  const holders = couriers.filter((k) => w.nodes[k].carrying);
  assert.ok(holders.length >= 2, 'the message should be on several couriers');
  await meet(w, couriers.map((k) => [k, 'bora']));
  assert.deepEqual(w.inbox.bora, [['ali', 'x']]);
  for (const k of couriers) assert.equal(w.nodes[k].carrying, 0, `${k} still carries a delivered message`);
});

test('scenario: a second courier arriving later is told the message is already delivered', async () => {
  const w = await loaded(['ali', 'k1', 'k2', 'bora'], ['k1', 'k2']);
  await meet(w, [['k1', 'bora']]);
  await meet(w, [['k2', 'bora']]);
  assert.equal(w.inbox.bora.length, 1);
  assert.equal(w.nodes.k2.carrying, 0);
});

test('scenario: the message hops courier to courier to reach someone none of them started near', async () => {
  const w = world(['ali', 'k1', 'k2', 'bora']);
  w.nodes.ali.sendMessage('bora', 'zincir');
  await meet(w, [['ali', 'k1']]);
  await meet(w, [['k1', 'k2']]);
  assert.equal(w.nodes.k2.carrying, 1);
  await meet(w, [['k2', 'bora']]);
  assert.deepEqual(w.inbox.bora, [['ali', 'zincir']]);
});

test('scenario: news of the delivery reaches couriers that never meet the recipient', async () => {
  const w = await loaded(['ali', 'k1', 'k2', 'bora'], ['k1', 'k2']);
  await meet(w, [['k1', 'bora']]);
  await meet(w, [['k1', 'k2']]);
  assert.equal(w.nodes.k2.carrying, 0, 'k2 keeps spending battery on a delivered message');
  assert.equal(w.nodes.k1.carrying, 0, 'k1 took a delivered message back');
});

test('scenario: the sender learns its message was delivered and stops offering it', async () => {
  const w = await loaded(['ali', 'k1', 'bora'], ['k1']);
  await meet(w, [['k1', 'bora']]);
  assert.equal(w.nodes.ali.carrying, 1);
  await meet(w, [['ali', 'k1']]);
  assert.equal(w.nodes.ali.carrying, 0);
});

test('scenario: one courier carries several senders\' mail and the replies back', async () => {
  const w = world(['ali', 'cem', 'deniz', 'k', 'bora']);
  for (const s of ['ali', 'cem', 'deniz']) { w.nodes[s].sendMessage('bora', `${s} burada`); await meet(w, [[s, 'k']]); }
  assert.equal(w.nodes.k.carrying, 3);
  await meet(w, [['k', 'bora']]);
  assert.deepEqual(w.inbox.bora.map(([f]) => f).sort(), ['ali', 'cem', 'deniz']);
  w.nodes.bora.sendMessage('ali', 'iyiyim');
  await meet(w, [['k', 'bora']]);
  await meet(w, [['k', 'ali']]);
  assert.deepEqual(w.inbox.ali, [['bora', 'iyiyim']]);
});

test('scenario: a courier that carries mail past its lifetime drops it', async () => {
  const w = await loaded(['ali', 'k1', 'bora'], ['k1']);
  w.clock.t += 25 * 3600 * 1000;
  await w.run(1);
  assert.equal(w.nodes.k1.carrying, 0);
  await meet(w, [['k1', 'bora']]);
  assert.deepEqual(w.inbox.bora, []);
});

test('scenario: the recipient walks away mid-transfer and the courier finishes later', async () => {
  const w = await loaded(['ali', 'k1', 'bora'], ['k1'], 'y'.repeat(30000));
  w.link('k1', 'bora');
  await w.run(1); // first burst only
  w.link('k1', 'bora', false);
  await w.run(5);
  await meet(w, [['k1', 'bora']]);
  assert.equal(w.inbox.bora.length, 1);
  assert.equal(w.nodes.k1.carrying, 0);
});

test('scenario: a phone on low battery stops carrying other people\'s mail but keeps its own', async () => {
  const w = world(['ali', 'k1', 'bora']);
  w.nodes.k1.relay = false;
  w.nodes.ali.sendMessage('bora', 'başkasının');
  await meet(w, [['ali', 'k1']]);
  assert.equal(w.nodes.k1.carrying, 0, 'a low-battery phone took on a courier job');
  w.nodes.k1.sendMessage('bora', 'kendi mesajım');
  w.nodes.ali.sendMessage('k1', 'sana');
  await meet(w, [['ali', 'k1']]);
  assert.deepEqual(w.inbox.k1, [['ali', 'sana']]);
  await meet(w, [['k1', 'bora']]);
  assert.deepEqual(w.inbox.bora, [['k1', 'kendi mesajım']]);
  w.nodes.k1.relay = true;
  await meet(w, [['ali', 'k1']]);
  assert.equal(w.nodes.k1.carrying, 1, 'back on charge it carries again');
});

test('scenario: couriers walking together hear about the delivery as soon as one of them makes it', async () => {
  const w = await loaded(['ali', 'k1', 'k2', 'bora'], ['k1', 'k2']);
  w.link('k1', 'k2');
  await w.run(3);
  w.link('k1', 'bora');
  await w.run(2);
  w.link('k1', 'bora', false);
  await w.run(2);
  assert.equal(w.inbox.bora.length, 1);
  assert.equal(w.nodes.k1.carrying, 0);
  assert.equal(w.nodes.k2.carrying, 0, 'k2 was told nothing because k1 answered from a stale list');
});
