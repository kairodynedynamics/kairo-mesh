// Emergency mail: a phone without signal reaches the authority through strangers and any phone that has internet.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createIdentity, createSigner, seal, addWork } from '../src/crypto.js';
import { createAuthority, gatewayServer, httpUplink, httpDownlink } from '../src/kurum.js';
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
  const env = addWork(seal(s, kurum.pub, Buffer.concat([Buffer.alloc(16, 7), sentNow(), Buffer.from(JSON.stringify(RAPOR))])));
  const r1 = kurum.receive(env), r2 = kurum.receive(Buffer.from(env));
  assert.deepEqual(r1, Buffer.alloc(16, 7));
  assert.deepEqual(r2, r1);
  assert.equal(kurum.reports.length, 1);
  const bad = Buffer.from(env);
  bad[bad.length - 1] ^= 1;
  assert.equal(kurum.receive(bad), null);
  const other = addWork(seal(s, createIdentity().pub, Buffer.concat([Buffer.alloc(16), sentNow(), Buffer.from('{}')])));
  assert.equal(kurum.receive(other), null);
  const lazy = Buffer.concat([seal(s, kurum.pub, Buffer.concat([Buffer.alloc(16, 9), sentNow(), Buffer.from('{}')])), Buffer.alloc(8)]);
  assert.equal(kurum.receive(lazy), null, 'no proof of work: refused');
});

test('acil: end to end over HTTP, as a phone with internet would upload it', async () => {
  let w;
  const kurum = createAuthority({ identity: createIdentity(), now: () => w.clock.t });
  const server = gatewayServer(kurum, { token: 'gizli-anahtar' }).listen(0);
  await new Promise((res) => server.once('listening', res));
  const url = `http://127.0.0.1:${server.address().port}/v1/acil`;
  try {
    w = world(['ayse', 'gecit'], 0, 1, { authority: kurum.pub, uplink: httpUplink(url) });
    w.online.add('gecit');
    w.nodes.ayse.sendEmergency(RAPOR);
    w.link('ayse', 'gecit');
    for (let i = 0; i < 20 && !w.delivered.ayse.length; i++) { await w.run(1); await settle(); }
    assert.equal(w.delivered.ayse.length, 1);
    assert.equal((await fetch(url)).status, 401, 'reports need the dispatcher token');
    const list = await (await fetch(url, { headers: { authorization: 'Bearer gizli-anahtar' } })).json();
    assert.equal(list.length, 1);
    assert.deepEqual(list[0].report.konum, RAPOR.konum);
    assert.equal(list[0].receipt, undefined, 'receipts stay on the server');
  } finally {
    server.close();
  }
});

test('acil: the operator door asks for the dashboard key; a wrong or missing key never shows calls', async () => {
  const server = gatewayServer(createAuthority({ identity: createIdentity() }), { token: 'gizli-anahtar' }).listen(0);
  await new Promise((res) => server.once('listening', res));
  const kok = `http://127.0.0.1:${server.address().port}`;
  try {
    const giris = await fetch(kok + '/');
    assert.equal(giris.status, 200);
    assert.match(await giris.text(), /action="\/operator\/pano"/);
    assert.equal((await fetch(kok + "/operator/pano?t=gizli-anahtar")).status, 200, "also under the site path");
    const yanlis = await fetch(kok + '/pano?t=yanlis');
    assert.equal(yanlis.status, 401);
    const y = await yanlis.text();
    assert.match(y, /Anahtar yanlış/);
    assert.doesNotMatch(y, /kurum panosu<\/title>/);
    assert.doesNotMatch(await (await fetch(kok + '/pano')).text(), /Anahtar yanlış/, 'no key yet is not a wrong key');
    assert.match(await (await fetch(kok + '/pano?t=gizli-anahtar')).text(), /kurum panosu<\/title>/);
  } finally {
    server.close();
  }
});

test('acil: a malformed report cannot crash the dashboard, nor after a restart, nor jump the queue', async () => {
  const { mkdtempSync } = await import('node:fs');
  const { join } = await import('node:path');
  const { tmpdir } = await import('node:os');
  const log = join(mkdtempSync(join(tmpdir(), 'kurum-temiz-')), 'cagrilar.jsonl');
  const id = createIdentity(), s = createIdentity();
  const zarf = (r, n) => addWork(seal(s, id.pub, Buffer.concat([Buffer.alloc(16, n), sentNow(), Buffer.from(JSON.stringify(r))])));
  const a = createAuthority({ identity: id, log });
  assert.ok(a.receive(zarf({ ...RAPOR, aciliyet: 1 }, 1)));
  // the review's proof of concept: "ozel" as a string crashed /pano; a negative urgency sorted first
  assert.ok(a.receive(zarf({ tur: 'enkaz', aciliyet: -1, ozel: 'x', kisi: 'çok', metin: 7, konum: { enlem: 999, boylam: 'a' } }, 2)));
  const bak = async (kurum) => {
    const server = gatewayServer(kurum, { token: 't' }).listen(0);
    await new Promise((res) => server.once('listening', res));
    try {
      const r = await fetch(`http://127.0.0.1:${server.address().port}/pano?t=t`);
      assert.equal(r.status, 200);
      return await r.text();
    } finally { server.close(); }
  };
  await bak(a);
  const b = createAuthority({ identity: id, log }); // reloaded from the log, as after a restart
  await bak(b);
  assert.equal(b.reports[0].report.aciliyet, 1, 'the real life-threatening call stays first');
  const kotu = b.reports[1].report;
  assert.equal(kotu.aciliyet, 2);
  assert.equal(kotu.ozel, undefined);
  assert.equal(kotu.kisi, undefined);
  assert.equal(kotu.konum, undefined);
});

test('acil: warning signs mark forged urgency, floods, copied text and withdrawn calls; none is hidden', async () => {
  const id = createIdentity();
  const a = createAuthority({ identity: id });
  const gonder = (s, r, n) => a.receive(addWork(seal(s, id.pub, Buffer.concat([Buffer.alloc(16, n), sentNow(-n * 1000), Buffer.from(JSON.stringify(r))]))));
  const durust = createIdentity(), sahte = createIdentity(), tekrar = createIdentity();
  const konum = { enlem: 36.2, boylam: 36.16, dogruluk_m: 12 };
  gonder(durust, { v: 1, tur: 'enkaz', aciliyet: 1, ozel: [], konum }, 1);
  // a modified app: "I am safe" ranked life-threatening (the official app cannot produce this)
  gonder(sahte, { v: 1, tur: 'guvende', aciliyet: 1, ozel: [], konum }, 2);
  for (let i = 0; i < 4; i++) gonder(tekrar, { v: 1, tur: 'enkaz', aciliyet: 1, ozel: [], konum }, 10 + i);
  for (let i = 0; i < 3; i++) gonder(createIdentity(), { v: 1, tur: 'enkaz', aciliyet: 1, metin: 'Atatürk Cd 5 enkaz 20 kişi', konum }, 20 + i);
  gonder(durust, { v: 1, tur: 'iptal', aciliyet: 4, iptal_ref: 'x' }, 0); // later: "sent by mistake"
  const bul = (s) => a.reports.filter((e) => e.sender === s.pub.toString('hex'));
  const kodlar = (e) => e.sinyaller.map((s) => s.kod);
  assert.deepEqual(kodlar(bul(sahte)[0]), ['kural-disi']);
  assert.ok(bul(tekrar).every((e) => kodlar(e).includes('cok-cagri')));
  assert.ok(a.reports.filter((e) => e.report.metin).every((e) => kodlar(e).includes('kopya-metin')));
  const geri = bul(durust).find((e) => e.report.tur === 'enkaz');
  assert.ok(kodlar(geri).includes('iptal-edildi'));
  assert.equal(a.reports.length, 10, 'every call is still listed (1 + 1 + 4 + 3 + 1)');
  const birler = a.reports.filter((e) => e.report.aciliyet === 1);
  assert.equal(birler.at(-1), birler.find((e) => kodlar(e).includes('iptal-edildi')), 'a withdrawn call sinks within its level');
});

test('acil: a correction withdraws only the call just before it; the dispatcher sees what was tapped and what the phone read', async () => {
  const id = createIdentity();
  const a = createAuthority({ identity: id });
  const s = createIdentity();
  const gonder = (r, n) => a.receive(addWork(seal(s, id.pub, Buffer.concat([Buffer.alloc(16, n), sentNow(-n * 1000), Buffer.from(JSON.stringify(r))]))));
  // the user's own test: a first call, a second call, then "sent by mistake" for the second
  gonder({ v: 1, tur: 'enkaz', aciliyet: 1, ozel: ['hamile'] }, 300);
  gonder({ v: 1, tur: 'enkaz', aciliyet: 1, kisi: 2, ozel: ['cocuk', 'yasli'], metin: 'üsküdar yavuztürk mahallesi',
    secim: { tur: 'enkaz', kisi: 2, ozel: ['cocuk', 'yasli'], evil: '<script>' },
    okuma: { model: 'kodlayici', ms: 140, tur: 'enkaz', aciliyet: 1, il: 'İstanbul', mahalle: 'Yavuztürk', ozel: [], fazla: 'x'.repeat(9) } }, 200);
  gonder({ v: 1, tur: 'iptal', aciliyet: 4, iptal_ref: 'x' }, 100);
  const cagri = a.reports.filter((e) => e.report.tur === 'enkaz');
  const iptalli = cagri.filter((e) => e.sinyaller.some((x) => x.kod === 'iptal-edildi'));
  assert.equal(iptalli.length, 1, 'only one call withdrawn');
  assert.equal(iptalli[0].report.kisi, 2, 'the second call, the one just before the correction');
  const r = iptalli[0].report;
  assert.deepEqual(r.secim, { tur: 'enkaz', kisi: 2, ozel: ['cocuk', 'yasli'] }, 'unknown fields are dropped');
  assert.equal(r.okuma.model, 'kodlayici');
  assert.equal(r.okuma.mahalle, 'Yavuztürk');
  assert.equal(r.okuma.fazla, undefined);
  const html = await (async () => {
    const server = gatewayServer(a, { token: 't' }).listen(0);
    await new Promise((ok) => server.once('listening', ok));
    try { return await (await fetch(`http://127.0.0.1:${server.address().port}/pano?t=t`)).text(); } finally { server.close(); }
  })();
  assert.match(html, /Telefonun yazıdan anladığı:<\/b> Enkaz altında · aciliyet 1 · adres: İstanbul \/ Yavuztürk/);
  assert.match(html, /Kişinin seçtiği:<\/b> Enkaz altında · 2 kişi · çocuk, yaşlı/);
  assert.doesNotMatch(html, /<script>/);
});

test('acil: the dashboard shows the sending device, its call count, a later location fix, and one device on its own', async () => {
  const id = createIdentity();
  const a = createAuthority({ identity: id });
  const ben = createIdentity(), baska = createIdentity();
  const gonder = (s, r, n) => a.receive(addWork(seal(s, id.pub, Buffer.concat([Buffer.alloc(16, n), sentNow(-n * 1000), Buffer.from(JSON.stringify(r))]))));
  const cihaz = { model: 'Samsung SM-A546E', sistem: 'Android 14', uygulama: '0.5.0', pil: 12, sarj: false };
  gonder(ben, { v: 1, tur: 'enkaz', aciliyet: 1, ozel: [], cihaz }, 300); // no GPS fix yet
  gonder(ben, { v: 1, tur: 'konum', aciliyet: 4, konum: { enlem: 36.2, boylam: 36.16, dogruluk_m: 8, zaman: Date.now() } }, 200);
  gonder(baska, { v: 1, tur: 'yangin', aciliyet: 1, ozel: [], konum: { enlem: 37, boylam: 35 } }, 100);
  const enkaz = a.reports.find((e) => e.report.tur === 'enkaz');
  assert.ok(!enkaz.sinyaller.some((s) => s.kod === 'konum-yok'), 'the later fix counts as a location');
  const server = gatewayServer(a, { token: 't' }).listen(0);
  await new Promise((ok) => server.once('listening', ok));
  const pano = async (q = '') => (await fetch(`http://127.0.0.1:${server.address().port}/pano?t=t${q}`)).text();
  try {
    const hepsi = await pano();
    assert.match(hepsi, /Gelen yardım çağrıları \(2\)/, 'the location follow-up is not a call of its own');
    assert.match(hepsi, /Güncel konum/);
    assert.match(hepsi, /Samsung SM-A546E · Android 14 · KairoMesh 0\.5\.0/);
    assert.match(hepsi, /Pil %12/);
    const tek = await pano(`&cihaz=${ben.pub.toString('hex').slice(0, 8)}`);
    assert.match(tek, /Cihaz [0-9a-f]{8}: 1 kayıt/);
    assert.doesNotMatch(tek, /Yangın/);
  } finally {
    server.close();
  }
});

test('acil: text the phone read as no call for help is flagged and sinks within its level, but stays listed', () => {
  const id = createIdentity();
  const a = createAuthority({ identity: id });
  const gonder = (r, n) => a.receive(addWork(seal(createIdentity(), id.pub, Buffer.concat([Buffer.alloc(16, n), sentNow(-n * 1000), Buffer.from(JSON.stringify(r))]))));
  const konum = { enlem: 41, boylam: 29 };
  const okuma = (tur, ek = {}) => ({ model: 'kodlayici', ms: 100, tur, aciliyet: 4, ozel: [], ...ek });
  // the user's own fake: "another emergency" + "pregnant" tapped, and a joke typed
  gonder({ v: 1, tur: 'yardim', aciliyet: 1, ozel: ['hamile'], metin: 'şaka', konum, secim: { tur: 'yardim', ozel: ['hamile'] }, okuma: okuma('alakasiz') }, 300);
  gonder({ v: 1, tur: 'yardim', aciliyet: 1, ozel: ['hamile'], metin: 'fıkra', konum, secim: { tur: 'yardim', ozel: ['hamile'] }, okuma: okuma('bilgi') }, 250);
  // a real call that only gives an address: not flagged
  gonder({ v: 1, tur: 'enkaz', aciliyet: 1, ozel: [], metin: 'yavuztürk mah', konum, secim: { tur: 'enkaz' }, okuma: okuma('bilgi', { mahalle: 'Yavuztürk' }) }, 200);
  // tapped "rubble", wrote about a fire: both are shown
  gonder({ v: 1, tur: 'enkaz', aciliyet: 1, ozel: [], metin: 'yangın var', konum, secim: { tur: 'enkaz' }, okuma: okuma('yangin') }, 100);
  const kod = (m) => a.reports.find((e) => e.report.metin === m).sinyaller.map((s) => s.kod);
  assert.deepEqual(kod('şaka'), ['metin-ilgisiz']);
  assert.deepEqual(kod('fıkra'), ['metin-istek-yok']);
  assert.deepEqual(kod('yavuztürk mah'), []);
  assert.deepEqual(kod('yangın var'), ['tur-farki']);
  assert.equal(a.reports.length, 4, 'nothing is hidden');
  assert.equal(a.reports.filter((e) => e.report.aciliyet === 1).at(-1).report.metin, 'şaka', 'the joke sinks to the bottom of level 1');
});

test('acil: an envelope the authority refuses for good (422) is dropped by the gateway, not re-uploaded all day', async () => {
  let yukleme = 0;
  const w = world(['ayse', 'gecit'], 0, 1, { authority: createIdentity().pub, uplink: async () => { yukleme++; return Buffer.alloc(0); } });
  w.online.add('gecit');
  w.nodes.ayse.sendEmergency(RAPOR);
  w.link('ayse', 'gecit');
  for (let i = 0; i < 6; i++) { await w.run(1); await settle(); }
  w.link('ayse', 'gecit', false); // ayse walks away; only the gateway's copy is left to test
  const once = yukleme;
  for (let i = 0; i < 6; i++) { await w.run(1); await settle(); }
  assert.ok(once >= 1, 'it was uploaded');
  assert.equal(yukleme, once, 'and not uploaded again after the refusal');
  assert.equal(w.nodes.gecit.carrying, 0, 'the gateway no longer carries it');
});

function sentNow(offset = 0) {
  const b = Buffer.alloc(8);
  b.writeBigUInt64BE(BigInt(Date.now() + offset));
  return b;
}

test('acil: couriers refuse mail sealed to the authority without proof of work, and carry real calls', async () => {
  const { w, kurum } = setup(['ayse', 'kurye']);
  // a cheap forgery: sealed to the authority's key like a real call, but without the work
  w.nodes.ayse.addContact('sahte', kurum.pub);
  w.nodes.ayse.sendMessage('sahte', JSON.stringify(RAPOR));
  await meet(w, [['ayse', 'kurye']]);
  assert.equal(w.nodes.kurye.carrying, 0, 'forgery dropped');
  w.nodes.ayse.sendEmergency(RAPOR);
  await meet(w, [['ayse', 'kurye']]);
  assert.equal(w.nodes.kurye.carrying, 1, 'real call carried');
});

test('duyuru: a signed announcement spreads from a phone with internet to phones without; forged ones do not', async () => {
  let w;
  const signer = createSigner();
  const kurum = createAuthority({ identity: createIdentity(), signer, now: () => w.clock.t });
  const gelen = { ayse: [], bora: [], gecit: [] };
  w = world(['gecit', 'ayse', 'bora'], 0, 1, { authority: kurum.pub, uplink: async (e) => kurum.receive(e),
    authoritySign: signer.pub, downlink: async () => kurum.notices.map((n) => n.frame),
    onAnnouncement: (n, d) => gelen[n].push(d.text) });
  kurum.announce('Toplanma alanı: Atatürk Parkı. Su dağıtımı 14:00.');
  w.online.add('gecit');
  await w.run(1);
  await settle();
  assert.deepEqual(gelen.gecit, ['Toplanma alanı: Atatürk Parkı. Su dağıtımı 14:00.']);
  await meet(w, [['gecit', 'ayse']], 35);
  await meet(w, [['ayse', 'bora']], 35); // bora never meets the phone with internet
  assert.equal(gelen.bora.length, 1);
  // a forgery signed by someone else, and a genuine one that has expired, are both ignored
  const sahte = createAuthority({ identity: createIdentity(), signer: createSigner(), now: () => w.clock.t });
  w.nodes.bora.receive(sahte.announce('Sahte: toplanma alanı değişti'));
  w.nodes.bora.receive(kurum.announce('Eski duyuru', -1000));
  assert.equal(gelen.bora.length, 1);
});

test('kurum: reports survive a restart of the server', async () => {
  const { mkdtempSync } = await import('node:fs');
  const { join } = await import('node:path');
  const { tmpdir } = await import('node:os');
  const log = join(mkdtempSync(join(tmpdir(), 'kurum-')), 'kayit.jsonl');
  const id = createIdentity(), s = createIdentity();
  const env = addWork(seal(s, id.pub, Buffer.concat([Buffer.alloc(16, 3), sentNow(), Buffer.from(JSON.stringify(RAPOR))])));
  const a = createAuthority({ identity: id, log });
  assert.ok(a.receive(env));
  const b = createAuthority({ identity: id, log });
  assert.equal(b.reports.length, 1);
  assert.deepEqual(b.receive(env), Buffer.alloc(16, 3), 'the same receipt after restart');
});

test('kurum aracı: anahtar üretir, kurum kodunu basar, mevcut anahtarın üzerine yazmaz', async () => {
  const { execFileSync } = await import('node:child_process');
  const { mkdtempSync, readFileSync } = await import('node:fs');
  const { join } = await import('node:path');
  const { tmpdir } = await import('node:os');
  const k = join(mkdtempSync(join(tmpdir(), 'kurum-cli-')), 'anahtar');
  const cli = new URL('../src/kurum-cli.js', import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1');
  const out = execFileSync(process.execPath, [cli, 'anahtar', k, 'https://kurum.example.gov.tr/'], { encoding: 'utf8' });
  const kod = out.trim().split('\n').pop().split(';');
  assert.equal(kod.length, 3);
  assert.equal(Buffer.from(kod[0], 'base64').length, 32);
  assert.equal(Buffer.from(kod[1], 'base64').length, 32);
  assert.equal(kod[2], 'https://kurum.example.gov.tr');
  const kayit = JSON.parse(readFileSync(join(k, 'kurum-anahtar.json'), 'utf8'));
  assert.equal(kayit.muhurAcik, kod[0]);
  assert.throws(() => execFileSync(process.execPath, [cli, 'anahtar', k, 'https://x.example'], { stdio: 'pipe' }), 'refuses to overwrite');
  assert.deepEqual(JSON.parse(readFileSync(join(k, 'kurum-anahtar.json'), 'utf8')), kayit, 'key file unchanged');
});

test('kurum kodu: yalnız kökün onayladığı kod kabul edilir (sahte kod çağrıları saldırgana yönlendiremez)', async () => {
  const { readFileSync } = await import('node:fs');
  const { kurumKoduAc } = await import('../src/kurum.js');
  const o = JSON.parse(readFileSync(new URL('./kurum-kodu-ornek.json', import.meta.url), 'utf8'));
  const kok = Buffer.from(o.kok, 'base64');
  const k = kurumKoduAc(o.gecerli, [kok]);
  assert.ok(k);
  assert.equal(k.adres, 'https://kurum.example');
  assert.equal(k.ad, 'Test Kurumu (yalnız test)');
  for (const [ad, kod] of Object.entries(o.gecersiz)) assert.equal(kurumKoduAc(kod, [kok]), null, ad);
  assert.equal(kurumKoduAc(o.gecerli, [Buffer.alloc(32, 9)]), null, 'unknown root');
});

test('kurum aracı: kök üretir ve kurumun kodunu onaylar', async () => {
  const { execFileSync } = await import('node:child_process');
  const { mkdtempSync } = await import('node:fs');
  const { join } = await import('node:path');
  const { tmpdir } = await import('node:os');
  const { kurumKoduAc } = await import('../src/kurum.js');
  const d = mkdtempSync(join(tmpdir(), 'kurum-kok-'));
  const cli = new URL('../src/kurum-cli.js', import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1');
  const son = (args) => execFileSync(process.execPath, [cli, ...args], { encoding: 'utf8' }).trim().split('\n').pop();
  const kok = Buffer.from(son(['kok', join(d, 'kok')]), 'base64');
  son(['anahtar', join(d, 'kurum'), 'https://afad.example.gov.tr']);
  const kod = son(['onayla', join(d, 'kok'), join(d, 'kurum'), 'AFAD Hatay']);
  const k = kurumKoduAc(kod, [kok]);
  assert.equal(k?.ad, 'AFAD Hatay');
  assert.throws(() => execFileSync(process.execPath, [cli, 'kok', join(d, 'kok')], { stdio: 'pipe' }), 'refuses to overwrite the root');
});

test('acil: a call from a phone with a wrong clock is kept and marked, not refused; it is placed by arrival', () => {
  const id = createIdentity();
  let t = Date.now();
  const kurum = createAuthority({ identity: id, now: () => t });
  const zarf = (s, r, ofs, n) => addWork(seal(s, id.pub, Buffer.concat([Buffer.alloc(16, n), sentNow(ofs), Buffer.from(JSON.stringify(r))])));
  const once = createIdentity(), saatsiz = createIdentity();
  assert.ok(kurum.receive(zarf(once, { ...RAPOR, aciliyet: 1 }, 0, 1)));
  t += 60000;
  // a drained phone whose clock went back to the epoch: its call still arrives
  assert.ok(kurum.receive(zarf(saatsiz, { ...RAPOR, aciliyet: 1 }, -Date.now(), 2)));
  const [ilk, ikinci] = kurum.reports;
  assert.equal(ilk.sender, once.pub.toString('hex'), 'placed by when it arrived, not by its 1970 time');
  assert.ok(ikinci.sinyaller.some((s) => s.kod === 'saat-supheli'));
  assert.ok(!ilk.sinyaller.some((s) => s.kod === 'saat-supheli'));
});

test('acil: reports past the retention period are deleted from memory and from the log', async () => {
  const { mkdtempSync, readFileSync } = await import('node:fs');
  const { join } = await import('node:path');
  const { tmpdir } = await import('node:os');
  const log = join(mkdtempSync(join(tmpdir(), 'kurum-saklama-')), 'cagrilar.jsonl');
  const id = createIdentity(), s = createIdentity();
  let t = Date.now();
  const zarf = (n) => addWork(seal(s, id.pub, Buffer.concat([Buffer.alloc(16, n), sentNow(t - Date.now()), Buffer.from(JSON.stringify(RAPOR))])));
  const a = createAuthority({ identity: id, log, now: () => t, saklamaMs: 30 * 86400000 });
  assert.ok(a.receive(zarf(1)));
  t += 31 * 86400000;
  assert.ok(a.receive(zarf(2)));
  assert.equal(a.reports.length, 1, 'the 31-day-old call is gone');
  assert.equal(readFileSync(log, 'utf8').trim().split('\n').length, 1, 'and gone from the log');
  const b = createAuthority({ identity: id, log, now: () => t, saklamaMs: 30 * 86400000 });
  assert.equal(b.reports.length, 1);
});

test('acil: a dashboard of 10,000 calls is built in well under a second', async () => {
  const { mkdtempSync, writeFileSync } = await import('node:fs');
  const { join } = await import('node:path');
  const { tmpdir } = await import('node:os');
  // 10,000 calls as a restarted server finds them in its log (sealing them one by one, with proof of work, would
  // time the test, not the board)
  const log = join(mkdtempSync(join(tmpdir(), 'kurum-yuk-')), 'cagrilar.jsonl');
  const t0 = Date.now();
  writeFileSync(log, Array.from({ length: 10000 }, (_, i) => JSON.stringify({
    key: i.toString(16).padStart(64, '0'), receipt: '00'.repeat(16), sender: (i % 50).toString(16).padStart(64, '0'),
    sentAt: t0 - i * 1000, receivedAt: t0, report: { ...RAPOR, metin: `enkaz altindayiz ${i % 300}` } }) + '\n').join(''));
  const kurum = createAuthority({ identity: createIdentity(), log });
  const bas = performance.now();
  const liste = kurum.reports;
  const sure = performance.now() - bas;
  assert.equal(liste.length, 10000);
  assert.ok(sure < 1000, `${Math.round(sure)} ms`);
  assert.equal(kurum.reports, liste, 'unchanged reports are not recomputed');
  assert.ok(liste.some((e) => e.sinyaller.some((s) => s.kod === 'cok-cagri')), 'the signs are still worked out');
});
