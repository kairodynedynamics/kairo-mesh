#!/usr/bin/env node
// Kurum (112, AFAD, belediye, pilot ortağı) için iki komut. Kurumun kendi bilgisayarında çalışır:
// özel anahtarlar bu makineden hiç çıkmaz.
//
//   node src/kurum-cli.js anahtar <klasör> <sunucu adresi>
//       Mühür (X25519) ve duyuru imza (Ed25519) anahtarlarını üretir, <klasör>/kurum-anahtar.json'a yazar
//       (yalnız sahibi okuyabilir) ve telefonlara verilecek "kurum kodu"nu basar.
//   node src/kurum-cli.js sunucu <klasör> [port]
//       Kurum sunucusunu başlatır: çağrılar <klasör>/cagrilar.jsonl'a yazılır, pano /pano adresindedir.
//       Pano anahtarı KURUM_PANO_ANAHTARI ortam değişkeninden okunur; yoksa üretilip bir kez basılır.
//       TLS (https) önündeki ters vekil sunucunun (nginx, Caddy) işidir.
import { mkdirSync, readFileSync, writeFileSync, existsSync, chmodSync } from 'node:fs';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';
import { createIdentity, createSigner } from './crypto.js';
import { createAuthority, gatewayServer } from './kurum.js';

const [komut, klasor, arg] = process.argv.slice(2);
const dosya = (k) => join(k, 'kurum-anahtar.json');
const b64 = (b) => Buffer.from(b).toString('base64');

function kod(anahtar, adres) {
  return `${b64(anahtar.muhurAcik)};${b64(anahtar.imzaAcik)};${adres}`;
}

if (komut === 'anahtar' && klasor && arg) {
  if (!/^https?:\/\//.test(arg)) throw new Error('sunucu adresi http(s):// ile başlamalı');
  mkdirSync(klasor, { recursive: true });
  if (existsSync(dosya(klasor))) throw new Error(`${dosya(klasor)} zaten var: mevcut anahtarın üzerine yazılmaz`);
  const muhur = createIdentity(), imza = createSigner();
  const kayit = { surum: 1, adres: arg.replace(/\/$/, ''), muhurGizli: b64(muhur.secret), imzaGizli: b64(imza.secret),
    muhurAcik: b64(muhur.pub), imzaAcik: b64(imza.pub), olusturma: new Date().toISOString() };
  writeFileSync(dosya(klasor), JSON.stringify(kayit, null, 1), { mode: 0o600 });
  try { chmodSync(dosya(klasor), 0o600); } catch { /* Windows: dosya izinlerini klasör ACL'si belirler */ }
  console.log(`Anahtarlar yazıldı: ${dosya(klasor)}  (yedekleyin; kaybolursa telefonlardaki kod değişmek zorunda kalır)\n`);
  console.log('Telefonlara verilecek kurum kodu:\n');
  console.log(kod({ muhurAcik: muhur.pub, imzaAcik: imza.pub }, kayit.adres));
} else if (komut === 'sunucu' && klasor) {
  const k = JSON.parse(readFileSync(dosya(klasor), 'utf8'));
  const kurum = createAuthority({
    identity: createIdentity(Buffer.from(k.muhurGizli, 'base64')),
    signer: createSigner(Buffer.from(k.imzaGizli, 'base64')),
    log: join(klasor, 'cagrilar.jsonl'),
    onReport: (e) => console.log(`${new Date().toISOString()} ÇAĞRI aciliyet=${e.report.aciliyet ?? '?'} tür=${e.report.tur ?? '?'} kişi=${e.report.kisi ?? '?'}`),
  });
  let token = process.env.KURUM_PANO_ANAHTARI;
  if (!token) { token = randomBytes(18).toString('base64url'); console.log(`Pano anahtarı (bir kez gösterilir): ${token}`); }
  const port = Number(arg ?? 8080);
  gatewayServer(kurum, { token }).listen(port, () => {
    console.log(`Kurum sunucusu ${port} portunda. Pano: http://localhost:${port}/pano?t=<pano anahtarı>`);
    console.log(`Kurum kodu: ${kod({ muhurAcik: Buffer.from(k.muhurAcik, 'base64'), imzaAcik: Buffer.from(k.imzaAcik, 'base64') }, k.adres)}`);
    console.log(`Kayıtlı çağrı: ${kurum.reports.length}`);
  });
} else {
  console.log('kullanım:\n  node src/kurum-cli.js anahtar <klasör> <sunucu adresi>\n  node src/kurum-cli.js sunucu <klasör> [port]');
  process.exit(2);
}
