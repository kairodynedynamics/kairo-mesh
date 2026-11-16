// The authority side: opens emergency envelopes sealed to its key, answers with their receipts, keeps the reports,
// and signs announcements that phones carry to the public. Runs on the authority's (or a pilot partner's) server,
// never on a phone; a phone with internet only forwards bytes.
//
// What the authority learns per report: the sender's long-term public key, the sealed send time, and the report
// itself (triage fields, GPS location, free text). Couriers and gateways learn none of it.
import { createServer } from 'node:http';
import { appendFileSync, existsSync, readFileSync } from 'node:fs';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { open, sha256, hasWork, DAY_MS, verifySignature } from './crypto.js';

const SKEW_MS = 3600000, MAX_REPORT = 64 * 1024, NOTICE_TEXT = 600;

// --- Kurum kodu ve kök onayı ----------------------------------------------------------------------------------
// Bir telefon çağrılarını hangi kuruma gönderdiğini "kurum kodu"ndan öğrenir. Kod yalnız kurumun kendi anahtarları
// olsaydı, sahte bir kod yayan biri ("AFAD'ın yeni kodu") o telefonların çağrılarını ve konumlarını kendine
// yönlendirebilir, sahte imzalı duyuru gönderebilirdi. Bu yüzden kod, uygulamaya gömülü bir kök anahtarla
// (yetkili makamın, ör. AFAD) imzalanır; telefon onaysız kodu kabul etmez.
//   <mühür, base64> ; <duyuru imzası, base64> ; <https sunucu> ; <kurum adı> ; <kök imzası, base64>
// Kök, ilk dört alanı "kairo-kurum-1\n" önekiyle imzalar.
export const KURUM_ONEK = 'kairo-kurum-1\n';
const b64 = (b) => Buffer.from(b).toString('base64');

export function kurumKoduOnayla({ muhur, imza, adres, ad }, kok) {
  if (!/^https?:\/\//.test(adres)) throw new Error('sunucu adresi http(s):// ile başlamalı');
  if (!ad || /[;\n]/.test(ad) || ad.length > 80) throw new Error('kurum adı 1-80 karakter, ";" içermeden');
  const govde = [b64(muhur), b64(imza), adres.replace(/\/$/, ''), ad].join(';');
  return `${govde};${b64(kok.sign(Buffer.from(KURUM_ONEK + govde)))}`;
}

// Geçerliyse { muhur, imza, adres, ad, kok } döner; kökü `kokler` içinde olmayan ya da bozuk kod için null.
export function kurumKoduAc(kod, kokler) {
  const p = String(kod).trim().split(';');
  if (p.length !== 5 || !/^https?:\/\//.test(p[2]) || !p[3]) return null;
  const muhur = Buffer.from(p[0], 'base64'), imza = Buffer.from(p[1], 'base64'), sig = Buffer.from(p[4], 'base64');
  if (muhur.length !== 32 || imza.length !== 32) return null;
  const msg = Buffer.from(KURUM_ONEK + p.slice(0, 4).join(';'));
  const kok = kokler.find((k) => verifySignature(k, msg, sig));
  return kok ? { muhur, imza, adres: p[2], ad: p[3], kok } : null;
}

// A report comes from an unknown phone: only known fields of the right type and size are kept. Without this one
// envelope with `"ozel": "x"` crashed the dashboard (and, saved in the log, again after every restart), and
// `"aciliyet": -1` put a fake report above every real call. An urgency that is missing or out of range counts as 2:
// below "life-threatening", above everything routine.
const METIN = (v, n) => (typeof v === 'string' ? v.slice(0, n) : undefined);
const SAYI = (v, a, b) => (Number.isFinite(v) && v >= a && v <= b ? v : undefined);
export function raporTemizle(r) {
  if (typeof r !== 'object' || r === null || Array.isArray(r)) return null;
  const k = typeof r.konum === 'object' && r.konum !== null ? r.konum : {};
  const enlem = SAYI(k.enlem, -90, 90), boylam = SAYI(k.boylam, -180, 180);
  const t = {
    v: SAYI(r.v, 0, 99), tur: METIN(r.tur, 32),
    aciliyet: Number.isInteger(r.aciliyet) && r.aciliyet >= 1 && r.aciliyet <= 4 ? r.aciliyet : 2,
    kisi: Number.isInteger(r.kisi) ? SAYI(r.kisi, 0, 10000) : undefined,
    ozel: Array.isArray(r.ozel) ? r.ozel.filter((o) => typeof o === 'string').slice(0, 12).map((o) => o.slice(0, 32)) : undefined,
    metin: METIN(r.metin, 1000), kaynak: METIN(r.kaynak, 32), iptal_ref: METIN(r.iptal_ref, 64),
    il: METIN(r.il, 80), ilce: METIN(r.ilce, 80), mahalle: METIN(r.mahalle, 120), sokak: METIN(r.sokak, 120), bina: METIN(r.bina, 120),
    konum: enlem !== undefined && boylam !== undefined
      ? { enlem, boylam, dogruluk_m: SAYI(k.dogruluk_m, 0, 1e6), zaman: SAYI(k.zaman, 0, 1e14) } : undefined,
    zaman: SAYI(r.zaman, 0, 1e14),
  };
  return JSON.parse(JSON.stringify(t)); // drops undefined fields
}

// `identity`: the sealing key (X25519). `signer`: the announcement key (Ed25519). `log`: a JSON-lines file the
// reports are appended to and reloaded from on restart (a real deployment backs it with its own database).
export function createAuthority({ identity, signer = null, now = Date.now, onReport = () => {}, log = null }) {
  const reports = new Map(); // envelope hash -> entry: a report forwarded by many gateways counts once
  const notices = [];
  if (log && existsSync(log))
    for (const line of readFileSync(log, 'utf8').split('\n').filter(Boolean)) {
      let e;
      try { e = JSON.parse(line); } catch { continue; } // a torn last line after a crash must not stop the server
      const report = raporTemizle(e.report); // logs written before field checks existed are cleaned on load
      if (report && typeof e.key === 'string') reports.set(e.key, { ...e, report, receipt: Buffer.from(String(e.receipt), 'hex') });
    }

  // Returns the 16-byte receipt, or null if the envelope lacks its proof of work, is not for us, altered, stale
  // or malformed.
  function receive(envelope) {
    const key = sha256(envelope).toString('hex');
    const known = reports.get(key);
    if (known) return known.receipt;
    if (!hasWork(envelope)) return null;
    let msg;
    try { msg = open(identity, envelope.subarray(0, -8)); } catch { return null; }
    if (msg.body.length < 24) return null;
    const sentAt = Number(msg.body.readBigUInt64BE(16));
    if (now() - sentAt > DAY_MS + SKEW_MS || sentAt - now() > SKEW_MS) return null;
    let report;
    try { report = raporTemizle(JSON.parse(msg.body.subarray(24).toString('utf8'))); } catch { return null; }
    if (!report) return null;
    const receipt = Buffer.from(msg.body.subarray(0, 16));
    const entry = { key, receipt, report, sender: msg.senderPub.toString('hex'), sentAt, receivedAt: now() };
    reports.set(key, entry);
    if (log) appendFileSync(log, JSON.stringify({ ...entry, receipt: receipt.toString('hex') }) + '\n');
    onReport(entry);
    return receipt;
  }

  // A signed announcement frame for the mesh: [5][id 8][issued 8][expires 8][len 2][text][sig 64].
  function announce(text, validMs = 12 * 3600000) {
    if (!signer) throw new Error('no signing key configured');
    const body = Buffer.from(text, 'utf8');
    if (body.length > NOTICE_TEXT) throw new RangeError(`announcement over ${NOTICE_TEXT} bytes`);
    const head = Buffer.alloc(27);
    head[0] = 5;
    randomBytes(8).copy(head, 1);
    head.writeBigUInt64BE(BigInt(now()), 9);
    head.writeBigUInt64BE(BigInt(now() + validMs), 17);
    head.writeUInt16BE(body.length, 25);
    const unsigned = Buffer.concat([head, body]);
    const frame = Buffer.concat([unsigned, signer.sign(unsigned)]);
    notices.push({ frame, expires: now() + validMs, text });
    return frame;
  }

  // Most urgent first, then oldest first: the order a dispatcher works through. Each entry carries its warning
  // signs (sinyaller); a call its own sender withdrew sinks below the rest of its urgency level.
  const sorted = () => {
    const tum = [...reports.values()];
    return tum.map((e) => ({ ...e, sinyaller: sinyaller(e, tum) })).sort((a, b) =>
      ((a.report.aciliyet ?? 4) - (b.report.aciliyet ?? 4))
      || (a.sinyaller.some((s) => s.kod === 'iptal-edildi') - b.sinyaller.some((s) => s.kod === 'iptal-edildi'))
      || (a.sentAt - b.sentAt));
  };

  return {
    pub: identity.pub, signPub: signer?.pub ?? null, receive, announce,
    get reports() { return sorted(); },
    get notices() { return notices.filter((n) => n.expires > now()); },
  };
}

// --- Warning signs on a report (docs/09-sahte-ihbar.md) ---------------------------------------------------------
// Nothing can prove a call is true: the authority only learns what the sender wrote. These signs put the calls
// that look forged or abusive in front of the dispatcher; none of them deletes or hides a call.
const TEMEL = { enkaz: 1, yangin: 1, saglik: 2, yardim: 2, kayip: 2, barinma: 3, gida_su: 3, guvende: 4, iptal: 4 };
export function kuralAciliyet(tur, ozel = []) {
  const a = TEMEL[tur] ?? 4;
  return a > 1 && !['guvende', 'iptal'].includes(tur) && ozel.some((o) => ['bebek', 'yarali', 'hamile'].includes(o)) ? a - 1 : a;
}
const SAAT = 3600000;
export function sinyaller(e, tum) {
  const r = e.report, out = [];
  const ekle = (kod, metin) => out.push({ kod, metin });
  if (!(r.tur in TEMEL)) ekle('bilinmeyen-tur', `Uygulamada olmayan durum türü: "${r.tur ?? '—'}"`);
  // a call tapped in the official app always carries exactly the table's urgency; only typed calls (the phone's
  // model) may differ, and then only to be more urgent
  else if (r.kaynak !== 'telefon-modeli' && r.tur !== 'iptal' && r.aciliyet !== kuralAciliyet(r.tur, r.ozel ?? []))
    ekle('kural-disi', 'Aciliyet uygulamanın kuralıyla tutmuyor: değiştirilmiş bir uygulamadan gelmiş olabilir');
  const ayni = tum.filter((o) => o !== e && o.sender === e.sender && o.report.tur !== 'iptal' && Math.abs(o.sentAt - e.sentAt) < SAAT);
  if (r.tur !== 'iptal' && ayni.length >= 3) ekle('cok-cagri', `Aynı cihazdan bir saat içinde ${ayni.length + 1} çağrı`);
  if (tum.some((o) => o.sender === e.sender && o.report.tur === 'iptal' && o.sentAt > e.sentAt) && r.tur !== 'iptal')
    ekle('iptal-edildi', 'Gönderen sonradan "yanlışlıkla gönderdim / yardım gerekmiyor" dedi');
  const metin = (r.metin ?? '').trim().toLocaleLowerCase('tr');
  if (metin.length >= 10) {
    const cihaz = new Set(tum.filter((o) => (o.report.metin ?? '').trim().toLocaleLowerCase('tr') === metin
      && Math.abs(o.sentAt - e.sentAt) < SAAT / 2).map((o) => o.sender));
    if (cihaz.size >= 3) ekle('kopya-metin', `Aynı metin yarım saatte ${cihaz.size} farklı cihazdan geldi: toplu sahte çağrı olabilir`);
  }
  if (r.tur !== 'iptal' && !r.konum) ekle('konum-yok', 'Konum yok (izin verilmemiş ya da GPS alınamamış)');
  else if (r.konum?.dogruluk_m > 1000) ekle('konum-belirsiz', `Konum belirsiz (±${Math.round(r.konum.dogruluk_m)} m)`);
  return out;
}

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

// The dispatcher's page: reports by urgency, with location and time. Plain HTML, no outside resources, so it works
// on an isolated network.
function dashboard(authority) {
  const rows = authority.reports.map((e) => {
    const r = e.report, k = r.konum ?? {};
    const yer = [r.il, r.ilce, r.mahalle, r.sokak, r.bina].filter(Boolean).join(' / ');
    return `<tr class="a${esc(r.aciliyet)}"><td>${esc(r.aciliyet)}</td><td>${esc(r.tur)}</td><td>${esc(r.kisi)}</td>`
      + `<td>${esc(yer)}${Number.isFinite(k.enlem) && Number.isFinite(k.boylam)
        ? `<br><small>${esc(k.enlem)}, ${esc(k.boylam)}${k.dogruluk_m ? ` (±${esc(Math.round(k.dogruluk_m))} m)` : ''}`
          + ` · <a href="https://www.openstreetmap.org/?mlat=${k.enlem}&amp;mlon=${k.boylam}#map=18/${k.enlem}/${k.boylam}" target="_blank" rel="noopener">haritada aç</a></small>`
        : ''}</td>`
      + `<td>${esc((r.ozel ?? []).join(', '))}</td><td>${esc(r.metin)}</td>`
      + `<td>${(e.sinyaller ?? []).map((s) => `<span class="s ${esc(s.kod)}">${esc(s.metin)}</span>`).join('')}</td>`
      + `<td>${new Date(e.sentAt).toLocaleString('tr-TR')}<br><small>alındı ${new Date(e.receivedAt).toLocaleString('tr-TR')}</small></td></tr>`;
  }).join('');
  return `<!doctype html><html lang="tr"><head><meta charset="utf-8"><meta http-equiv="refresh" content="15">
<title>KairoMesh — kurum panosu</title><style>
body{font:15px system-ui,sans-serif;margin:20px;color:#14202b;background:#f6f5f1}table{border-collapse:collapse;width:100%;background:#fff}
td,th{border-bottom:1px solid #ddd;padding:8px;text-align:left;vertical-align:top}th{background:#ecebe5}
tr.a1 td:first-child{background:#b3261e;color:#fff;font-weight:700}tr.a2 td:first-child{background:#e8a33d;font-weight:700}
small{color:#5d6a76}.s{display:block;margin:2px 0;padding:2px 6px;border-radius:6px;background:#fbf1dc;color:#6b4500;font-size:13px}
.s.kural-disi,.s.kopya-metin,.s.bilinmeyen-tur{background:#fbe4e1;color:#8c1d16}.s.iptal-edildi{background:#e4e8ec;color:#26343e}
</style></head><body><h1>Gelen yardım çağrıları (${authority.reports.length})</h1>
<p>Aciliyete, sonra gönderim zamanına göre sıralı. Sayfa 15 saniyede bir yenilenir. "Dikkat" sütunu sahte ya da kötüye kullanım
belirtilerini gösterir; hiçbir çağrı gizlenmez, karar görevlinindir.</p>
<table><thead><tr><th>Aciliyet</th><th>Tür</th><th>Kişi</th><th>Yer</th><th>Özel durum</th><th>Mesaj</th><th>Dikkat</th><th>Zaman</th></tr></thead>
<tbody>${rows}</tbody></table></body></html>`;
}

function giris(yanlis) {
  return `<!doctype html><html lang="tr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex"><title>KairoMesh — operatör girişi</title><style>
body{font:17px system-ui,sans-serif;margin:0;min-height:100vh;display:grid;place-items:center;background:#f2f2f7;color:#1c1c1e}
form{background:#fff;padding:28px;border-radius:18px;width:min(380px,90vw);box-shadow:0 1px 3px rgba(0,0,0,.08)}
h1{font-size:22px;margin:0 0 6px}p{color:#48484a;margin:0 0 18px;font-size:15px}
input{width:100%;box-sizing:border-box;font:inherit;padding:12px;border:1px solid #d1d1d6;border-radius:12px}
button{margin-top:12px;width:100%;font:inherit;font-weight:600;padding:13px;border:0;border-radius:12px;background:#b3261e;color:#fff}
.h{color:#b3261e;font-weight:600}</style></head><body><form method="get" action="pano">
<h1>Operatör panosu</h1><p>Gelen yardım çağrılarını görmek için pano anahtarını girin.</p>
${yanlis ? '<p class="h">Anahtar yanlış.</p>' : ''}<input name="t" type="password" autocomplete="current-password" aria-label="Pano anahtarı" required autofocus>
<button>Panoyu aç</button></form></body></html>`;
}

// HTTP front. Gateways (any phone with internet): POST /v1/acil (raw envelope -> raw 16-byte receipt) and
// GET /v1/duyuru (current signed announcements, hex, JSON). Dispatchers, with `token`: GET /pano,
// GET /v1/acil (JSON) and POST /v1/duyuru (text -> signed announcement). TLS belongs to the deployment's
// reverse proxy, not to this reference.
export function gatewayServer(authority, { token = null } = {}) {
  const authorised = (req) => {
    if (!token) return false; // no token configured: dispatcher endpoints stay closed
    const got = Buffer.from(String(req.headers.authorization ?? '').replace(/^Bearer /, '') || new URL(req.url, 'http://x').searchParams.get('t') || '');
    const want = Buffer.from(token);
    return got.length === want.length && timingSafeEqual(got, want);
  };
  const body = (req, done, res) => {
    const parts = [];
    let size = 0;
    req.on('error', () => {});
    req.on('data', (c) => { size += c.length; if (size > MAX_REPORT) req.destroy(); else parts.push(c); });
    req.on('end', () => {
      try { done(Buffer.concat(parts)); } catch (e) {
        console.error(`${new Date().toISOString()} istek hatası: ${e?.message}`);
        if (!res.headersSent) res.writeHead(500).end();
      }
    });
  };
  // No request may stop the server: uploads from every other gateway depend on it.
  return createServer((req, res) => {
    try { yonet(req, res); } catch (e) {
      console.error(`${new Date().toISOString()} istek hatası: ${e?.message}`);
      if (!res.headersSent) res.writeHead(500).end(); else res.end();
    }
  });
  function yonet(req, res) {
    const path = new URL(req.url, 'http://x').pathname;
    res.setHeader('X-Content-Type-Options', 'nosniff');
    if (path === '/v1/acil' && req.method === 'POST')
      return body(req, (env) => {
        const receipt = authority.receive(env);
        if (!receipt) return res.writeHead(422).end();
        res.writeHead(200, { 'content-type': 'application/octet-stream' }).end(receipt);
      }, res);
    if (path === '/v1/duyuru' && req.method === 'GET') {
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify(authority.notices.map((n) => n.frame.toString('hex'))));
    }
    const html = { 'content-type': 'text/html; charset=utf-8' };
    // the operator's door: a form for the dashboard key (a key in the URL also works, for bookmarks)
    if (path === '/' && req.method === 'GET') return res.writeHead(200, html).end(giris(false));
    if (!authorised(req)) {
      if (path === '/pano') return res.writeHead(401, html).end(giris(new URL(req.url, 'http://x').searchParams.has('t')));
      return res.writeHead(path.startsWith('/v1/') ? 401 : 404).end();
    }
    if (path === '/pano') return res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }).end(dashboard(authority));
    if (path === '/v1/acil' && req.method === 'GET') {
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      return res.end(JSON.stringify(authority.reports.map(({ receipt, key, ...r }) => r)));
    }
    if (path === '/v1/duyuru' && req.method === 'POST')
      return body(req, (text) => {
        try {
          const frame = authority.announce(text.toString('utf8'));
          res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ frame: frame.toString('hex') }));
        } catch (e) { res.writeHead(400).end(String(e.message)); }
      }, res);
    res.writeHead(404).end();
  }
}

// What a phone does once it has internet: post the envelope, return the receipt (or null to retry later)...
export const httpUplink = (url) => async (envelope) => {
  const res = await fetch(url, { method: 'POST', body: envelope, headers: { 'content-type': 'application/octet-stream' } });
  // 422: refused for good (empty receipt: drop it); anything else is a network problem (null: retry later)
  if (res.status === 422) return Buffer.alloc(0);
  return res.ok ? Buffer.from(await res.arrayBuffer()) : null;
};
// ...and fetch the current announcements to carry into the mesh.
export const httpDownlink = (url) => async () => {
  const res = await fetch(url);
  return res.ok ? (await res.json()).map((h) => Buffer.from(h, 'hex')) : [];
};
