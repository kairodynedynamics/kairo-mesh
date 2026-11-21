#!/usr/bin/env node
// Kurum (112, AFAD, belediye, pilot ortağı) ve kök onay makamı için komutlar. Kurumun kendi bilgisayarında çalışır:
// özel anahtarlar bu makineden hiç çıkmaz.
//
//   node src/kurum-cli.js anahtar <klasör> <sunucu adresi>
//       Mühür (X25519) ve duyuru imza (Ed25519) anahtarlarını üretir, <klasör>/kurum-anahtar.json'a yazar
//       (yalnız sahibi okuyabilir) ve telefonlara verilecek "kurum kodu"nu basar.
//   node src/kurum-cli.js kok <klasör>
//       Kök onay anahtarını üretir (yetkili makam; ör. AFAD). Açık yarısı uygulamaya gömülür. Özel yarısı
//       çevrimdışı saklanır: elinde olan, telefonların güveneceği kurum kodlarını üretebilir.
//   node src/kurum-cli.js onayla <kök klasörü> <kurum klasörü> "<kurum adı>"
//       Kurumun kodunu kökle onaylar; telefonlar yalnız onaylı kodu kabul eder. Kod kurum klasörüne de yazılır.
//   node src/kurum-cli.js sunucu <klasör> [port]
//       Kurum sunucusunu başlatır: çağrılar <klasör>/cagrilar.jsonl'a yazılır, pano /pano adresindedir.
//       Pano anahtarı KURUM_PANO_ANAHTARI ortam değişkeninden okunur; yoksa üretilip bir kez basılır.
//       TLS (https) önündeki ters vekil sunucunun (nginx, Caddy) işidir.
import { mkdirSync, readFileSync, writeFileSync, existsSync, chmodSync } from 'node:fs';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';
import { createIdentity, createSigner } from './crypto.js';
import { createAuthority, gatewayServer, kurumKoduOnayla } from './kurum.js';

const [komut, klasor, arg] = process.argv.slice(2);
const dosya = (k) => join(k, 'kurum-anahtar.json');
const kokDosya = (k) => join(k, 'kok-anahtar.json');
const gizliYaz = (yol, nesne) => {
  writeFileSync(yol, JSON.stringify(nesne, null, 1), { mode: 0o600 });
  try { chmodSync(yol, 0o600); } catch { /* Windows: dosya izinlerini klasör ACL'si belirler */ }
};
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
  gizliYaz(dosya(klasor), kayit);
  console.log(`Anahtarlar yazıldı: ${dosya(klasor)}  (yedekleyin; kaybolursa telefonlardaki kod değişmek zorunda kalır)\n`);
  console.log('Telefonlar bu kodu ancak kök onayından sonra kabul eder. Onay (kök anahtarın sahibi çalıştırır):');
  console.log(`  node src/kurum-cli.js onayla <kök klasörü> ${klasor} "<kurum adı>"\n`);
  console.log('Onaysız kod:\n');
  console.log(kod({ muhurAcik: muhur.pub, imzaAcik: imza.pub }, kayit.adres));
} else if (komut === 'kok' && klasor) {
  mkdirSync(klasor, { recursive: true });
  if (existsSync(kokDosya(klasor))) throw new Error(`${kokDosya(klasor)} zaten var: mevcut kökün üzerine yazılmaz`);
  const kok = createSigner();
  gizliYaz(kokDosya(klasor), { surum: 1, kokGizli: b64(kok.secret), kokAcik: b64(kok.pub), olusturma: new Date().toISOString() });
  console.log(`Kök anahtar yazıldı: ${kokDosya(klasor)}  (çevrimdışı yedekleyin; ağa bağlı makinede kalmamalı)\n`);
  console.log('Uygulamaya gömülecek kök açık anahtarı:\n');
  console.log(b64(kok.pub));
} else if (komut === 'onayla' && klasor && arg && process.argv[5]) {
  const k = JSON.parse(readFileSync(kokDosya(klasor), 'utf8'));
  const kurumYolu = dosya(arg), kayit = JSON.parse(readFileSync(kurumYolu, 'utf8'));
  const onayli = kurumKoduOnayla({ muhur: Buffer.from(kayit.muhurAcik, 'base64'), imza: Buffer.from(kayit.imzaAcik, 'base64'),
    adres: kayit.adres, ad: process.argv[5] }, createSigner(Buffer.from(k.kokGizli, 'base64')));
  gizliYaz(kurumYolu, { ...kayit, onayliKod: onayli });
  console.log('Telefonlara verilecek onaylı kurum kodu:\n');
  console.log(onayli);
} else if (komut === 'sunucu' && klasor) {
  const k = JSON.parse(readFileSync(dosya(klasor), 'utf8'));
  const kurum = createAuthority({
    identity: createIdentity(Buffer.from(k.muhurGizli, 'base64')),
    signer: createSigner(Buffer.from(k.imzaGizli, 'base64')),
    log: join(klasor, 'cagrilar.jsonl'),
    // KVKK: calls are deleted after KURUM_SAKLAMA_GUN days (30 unless set; 0 keeps everything)
    saklamaMs: Number(process.env.KURUM_SAKLAMA_GUN ?? 30) * 86400000 || null,
    onReport: (e) => console.log(`${new Date().toISOString()} ÇAĞRI aciliyet=${e.report.aciliyet ?? '?'} tür=${e.report.tur ?? '?'} kişi=${e.report.kisi ?? '?'}`),
  });
  let token = process.env.KURUM_PANO_ANAHTARI;
  if (!token) { token = randomBytes(18).toString('base64url'); console.log(`Pano anahtarı (bir kez gösterilir): ${token}`); }
  const port = Number(arg ?? 8080);
  gatewayServer(kurum, { token }).listen(port, () => {
    console.log(`Kurum sunucusu ${port} portunda. Pano: http://localhost:${port}/pano?t=<pano anahtarı>`);
    console.log(k.onayliKod ? `Kurum kodu: ${k.onayliKod}` : 'Kurum kodu henüz kökle onaylanmadı (onayla komutu); telefonlar onaysız kodu kabul etmez.');
    console.log(`Kayıtlı çağrı: ${kurum.reports.length}`);
  });
} else {
  console.log('kullanım:\n  node src/kurum-cli.js anahtar <klasör> <sunucu adresi>\n  node src/kurum-cli.js kok <klasör>\n'
    + '  node src/kurum-cli.js onayla <kök klasörü> <kurum klasörü> "<kurum adı>"\n  node src/kurum-cli.js sunucu <klasör> [port]');
  process.exit(2);
}
