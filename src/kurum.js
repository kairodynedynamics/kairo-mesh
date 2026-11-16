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

// `identity`: the sealing key (X25519). `signer`: the announcement key (Ed25519). `log`: a JSON-lines file the
// reports are appended to and reloaded from on restart (a real deployment backs it with its own database).
export function createAuthority({ identity, signer = null, now = Date.now, onReport = () => {}, log = null }) {
  const reports = new Map(); // envelope hash -> entry: a report forwarded by many gateways counts once
  const notices = [];
  if (log && existsSync(log))
    for (const line of readFileSync(log, 'utf8').split('\n').filter(Boolean)) {
      const e = JSON.parse(line);
      reports.set(e.key, { ...e, receipt: Buffer.from(e.receipt, 'hex') });
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
    try { report = JSON.parse(msg.body.subarray(24).toString('utf8')); } catch { return null; }
    if (typeof report !== 'object' || report === null) return null;
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

  // Most urgent first, then oldest first: the order a dispatcher works through.
  const sorted = () => [...reports.values()].sort((a, b) =>
    ((a.report.aciliyet ?? 4) - (b.report.aciliyet ?? 4)) || (a.sentAt - b.sentAt));

  return {
    pub: identity.pub, signPub: signer?.pub ?? null, receive, announce,
    get reports() { return sorted(); },
    get notices() { return notices.filter((n) => n.expires > now()); },
  };
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
      + `<td>${new Date(e.sentAt).toLocaleString('tr-TR')}<br><small>alındı ${new Date(e.receivedAt).toLocaleString('tr-TR')}</small></td></tr>`;
  }).join('');
  return `<!doctype html><html lang="tr"><head><meta charset="utf-8"><meta http-equiv="refresh" content="15">
<title>Kairo Mesh — kurum panosu</title><style>
body{font:15px system-ui,sans-serif;margin:20px;color:#14202b;background:#f6f5f1}table{border-collapse:collapse;width:100%;background:#fff}
td,th{border-bottom:1px solid #ddd;padding:8px;text-align:left;vertical-align:top}th{background:#ecebe5}
tr.a1 td:first-child{background:#b3261e;color:#fff;font-weight:700}tr.a2 td:first-child{background:#e8a33d;font-weight:700}
small{color:#5d6a76}</style></head><body><h1>Gelen yardım çağrıları (${authority.reports.length})</h1>
<p>Aciliyete, sonra gönderim zamanına göre sıralı. Sayfa 15 saniyede bir yenilenir.</p>
<table><thead><tr><th>Aciliyet</th><th>Tür</th><th>Kişi</th><th>Yer</th><th>Özel durum</th><th>Mesaj</th><th>Zaman</th></tr></thead>
<tbody>${rows}</tbody></table></body></html>`;
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
  const body = (req, done) => {
    const parts = [];
    let size = 0;
    req.on('data', (c) => { size += c.length; if (size > MAX_REPORT) req.destroy(); else parts.push(c); });
    req.on('end', () => done(Buffer.concat(parts)));
  };
  return createServer((req, res) => {
    const path = new URL(req.url, 'http://x').pathname;
    res.setHeader('X-Content-Type-Options', 'nosniff');
    if (path === '/v1/acil' && req.method === 'POST')
      return body(req, (env) => {
        const receipt = authority.receive(env);
        if (!receipt) return res.writeHead(422).end();
        res.writeHead(200, { 'content-type': 'application/octet-stream' }).end(receipt);
      });
    if (path === '/v1/duyuru' && req.method === 'GET') {
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify(authority.notices.map((n) => n.frame.toString('hex'))));
    }
    if (!authorised(req)) return res.writeHead(path === '/pano' || path.startsWith('/v1/') ? 401 : 404).end();
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
      });
    res.writeHead(404).end();
  });
}

// What a phone does once it has internet: post the envelope, return the receipt (or null to retry later)...
export const httpUplink = (url) => async (envelope) => {
  const res = await fetch(url, { method: 'POST', body: envelope, headers: { 'content-type': 'application/octet-stream' } });
  return res.ok ? Buffer.from(await res.arrayBuffer()) : null;
};
// ...and fetch the current announcements to carry into the mesh.
export const httpDownlink = (url) => async () => {
  const res = await fetch(url);
  return res.ok ? (await res.json()).map((h) => Buffer.from(h, 'hex')) : [];
};
