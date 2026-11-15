// Emergency mail: a phone without signal reaches the authority through strangers and any phone that has internet.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createIdentity, seal } from '../src/crypto.js';
import { createAuthority, gatewayServer, httpUplink } from '../src/kurum.js';
import { world } from './world.js';

const meet = async (w, pairs, rounds = 4) => {
  for (const [a, b] of pairs) w.link(a, b);
  await w.run(rounds);
  for (const [a, b] of pairs) w.link(a, b, false);
};
const settle = () => new Promise((res) => setTimeout(res, 20)); // let uploads (promises) finish

function setup(names) {
  let w;
  const kurum = createAuthority({ identity: createIdentity(), now: () => w.clock.t });
  w = world(names, 0, 1, { authority: kurum.pub, uplink: async (env) => kurum.receive(env) });
  return { w, kurum };
}

const RAPOR = {
  v: 1, tur: 'enkaz', aciliyet: 1, kisi: 3, ozel: ['bebek'],
  konum: { enlem: 36.2023, boylam: 36.1613, dogruluk_m: 12, zaman: 1_800_000_000_000 },
  metin: 'antakya ürgenpaşa mh enkaz altındayız 3 kişi bebek var',
};

test('acil: a phone in range with internet uploads at once, and the receipt reaches the sender', async () => {
  const { w, kurum } = setup(['ayse', 'gecit']);
  w.online.add('gecit');
  const id = w.nodes.ayse.sendEmergency(RAPOR);
  await meet(w, [['ayse', 'gecit']]);
  await settle();
  await meet(w, [['ayse', 'gecit']], 2);
  assert.equal(kurum.reports.length, 1);
  assert.deepEqual(kurum.reports[0].report, RAPOR);
  assert.equal(kurum.reports[0].sender, w.nodes.ayse.pub.toString('hex'));
  assert.deepEqual(w.delivered.ayse, [id]);
  assert.equal(w.nodes.ayse.carrying, 0);
  assert.equal(w.nodes.gecit.carrying, 0);
});

test('acil: an offline courier carries it until it gets signal, then uploads without its owner doing anything', async () => {
  const { w, kurum } = setup(['ayse', 'kurye', 'baska']);
  w.nodes.ayse.sendEmergency(RAPOR);
  await meet(w, [['ayse', 'kurye']]);
  await meet(w, [['ayse', 'baska']]);
  assert.equal(w.nodes.kurye.carrying, 1);
  assert.equal(w.nodes.baska.carrying, 1);
  assert.equal(kurum.reports.length, 0);
  w.online.add('kurye'); // walks out of the damaged area
  await w.run(2);
  await settle();
  assert.equal(kurum.reports.length, 1);
  assert.equal(w.nodes.kurye.carrying, 0);
  // the news travels back through the other courier, who drops its copy and tells the sender
  await meet(w, [['kurye', 'baska']]);
  assert.equal(w.nodes.baska.carrying, 0);
  await meet(w, [['baska', 'ayse']]);
  assert.equal(w.delivered.ayse.length, 1);
  assert.equal(w.nodes.ayse.carrying, 0);
});

test('acil: emergency mail is announced before ordinary mail when the HELLO is full', async () => {
  const { w, kurum } = setup(['ayse', 'bora', 'gecit']);
  for (let i = 0; i < 30; i++) w.nodes.ayse.sendMessage('bora', `sıradan ${i}`);
  w.nodes.ayse.sendEmergency(RAPOR); // 31st entry: would fall outside 24 HELLO slots in arrival order
  w.online.add('gecit');
  await meet(w, [['ayse', 'gecit']], 1);
  await settle();
  assert.equal(kurum.reports.length, 1);
});

test('acil: a report forwarded by several gateways is recorded once; tampered or foreign envelopes are refused', async () => {
  const kurum = createAuthority({ identity: createIdentity() });
  const s = createIdentity();
  const env = seal(s, kurum.pub, Buffer.concat([Buffer.alloc(16, 7), sentNow(), Buffer.from(JSON.stringify(RAPOR))]));
  const r1 = kurum.receive(env), r2 = kurum.receive(Buffer.from(env));
  assert.deepEqual(r1, Buffer.alloc(16, 7));
  assert.deepEqual(r2, r1);
  assert.equal(kurum.reports.length, 1);
  const bad = Buffer.from(env);
  bad[bad.length - 1] ^= 1;
  assert.equal(kurum.receive(bad), null);
  const other = seal(s, createIdentity().pub, Buffer.concat([Buffer.alloc(16), sentNow(), Buffer.from('{}')]));
  assert.equal(kurum.receive(other), null);
  const stale = seal(s, kurum.pub, Buffer.concat([Buffer.alloc(16), sentNow(-3 * 86400000), Buffer.from('{}')]));
  assert.equal(kurum.receive(stale), null);
});

test('acil: end to end over HTTP, as a phone with internet would upload it', async () => {
  let w;
  const kurum = createAuthority({ identity: createIdentity(), now: () => w.clock.t });
  const server = gatewayServer(kurum).listen(0);
  await new Promise((res) => server.once('listening', res));
  const url = `http://127.0.0.1:${server.address().port}/v1/acil`;
  try {
    w = world(['ayse', 'gecit'], 0, 1, { authority: kurum.pub, uplink: httpUplink(url) });
    w.online.add('gecit');
    w.nodes.ayse.sendEmergency(RAPOR);
    w.link('ayse', 'gecit');
    for (let i = 0; i < 20 && !w.delivered.ayse.length; i++) { await w.run(1); await settle(); }
    assert.equal(w.delivered.ayse.length, 1);
    const list = await (await fetch(url)).json();
    assert.equal(list.length, 1);
    assert.deepEqual(list[0].report.konum, RAPOR.konum);
    assert.equal(list[0].receipt, undefined, 'receipts stay on the server');
  } finally {
    server.close();
  }
});

function sentNow(offset = 0) {
  const b = Buffer.alloc(8);
  b.writeBigUInt64BE(BigInt(Date.now() + offset));
  return b;
}
