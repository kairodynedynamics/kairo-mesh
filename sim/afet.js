// Deprem senaryosu: telefonlarda çalışacak düğüm kodu (src/node.js) ve kurum sunucusu (src/kurum.js) ile,
// soyut bir model değil. Soru: enkaz altındaki birinin yardım çağrısı kuruma ne kadar sürede ulaşır?
//
//   node sim/afet.js                       # varsayılan: 3 yaygınlık oranı x 5 tohum, docs/06 tablosu
//   node sim/afet.js --yayginlik 0.1 --tohum 3 --saat 12
//
// Varsayımlar (her biri parametre; değerler 6 Şubat sonrası raporlardan ve BLE ölçümlerinden makul seçildi,
// sahada ölçülmedi):
// - 2×2 km yerleşim, 4000 kişi. Uygulama `yayginlik` oranındaki telefonda yüklü.
// - Hasarlı çekirdek (merkezden 900 m): baz istasyonu yok. Çevrede şebeke var ama sıkışık: telefon her
//   dakika %35 olasılıkla veri bağlantısı kurabilir. 8. saatte çekirdeğin ortasına mobil baz istasyonu
//   gelir (500 m yarıçap, aynı sıkışıklık).
// - Bluetooth menzili açıkta 30 m, enkaz altındaki telefon için 8 m.
// - Enkaz altında 30 kişi; çağrıyı ilk 60 dakikada yazar; telefonun pili 4–24 saat arasında biter.
// - Hayatta kalanlar: %70'i 15–60 dakikada en yakın toplanma alanına gider ve orada kalır; %15'i ev,
//   toplanma alanı ve hastane arasında dolaşır; %15'i 1–3 saatte bölgeden çıkar (şebekeye kavuşur).
//   Enkazların çevresinde gönüllüler toplanır (her enkazda 0–4 kişi, 30 m içinde).
// - 6 arama-kurtarma ekibi enkazdan enkaza dolaşır; telefonları sıradan telefondur.
// - Karşılaştırma ("bugün"): KairoMesh olmadan çağrı yalnız gönderenin kendi telefonu şebekeye
//   kavuşursa (mobil baz istasyonu) ulaşır.
import { createIdentity } from '../src/crypto.js';
import { createNode } from '../src/node.js';
import { createAuthority } from '../src/kurum.js';
import { rng } from '../src/rng.js';

const ALAN = 2000, MERKEZ = 1000, CEKIRDEK = 900, COW_YARICAP = 500;
const TOPLANMA = [[300, 300], [1700, 300], [300, 1700], [1700, 1700], [1000, 1000]];
const HASTANE = [1500, 1050];

export async function senaryo({ yayginlik = 0.1, tohum = 1, saat = 12, nufus = 4000, enkaz = 30, kairo = true,
  adimSn = 10, cowSaat = 8, sikisiklik = 0.35, bleAcik = 30, bleEnkaz = 8, ekipAgGecidi = false } = {}) {
  const r = rng(tohum);
  const saat0 = 1_800_000_000_000;
  let simdi = saat0;
  const kurum = createAuthority({ identity: createIdentity(), now: () => simdi });
  const rastgele = (a, b) => a + (b - a) * r();
  const uzak = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);
  const kapsama = (p, t) => {
    if (uzak(p, [MERKEZ, MERKEZ]) > CEKIRDEK) return true;
    return t >= cowSaat * 3600 && uzak(p, [MERKEZ, MERKEZ]) < COW_YARICAP;
  };

  // --- insanlar ---------------------------------------------------------
  const kisiler = [];
  const telefonSayisi = Math.round(nufus * yayginlik);
  for (let i = 0; i < enkaz; i++) {           // enkaz altındakiler: hepsinde uygulama var (çağrıyı yazan onlar)
    const a = rastgele(0, 2 * Math.PI), d = Math.sqrt(r()) * CEKIRDEK * 0.95;
    const p = [MERKEZ + d * Math.cos(a), MERKEZ + d * Math.sin(a)];
    kisiler.push({ tur: 'enkaz', p, menzil: bleEnkaz, pilBiter: rastgele(4, 24) * 3600, yazar: rastgele(60, 3600) });
  }
  const enkazlar = kisiler.map((k) => k.p);
  for (const e of enkazlar)                     // enkaz başında bekleyen gönüllüler
    for (let j = Math.floor(r() * 5); j > 0 && kisiler.length < enkaz + telefonSayisi; j--)
      kisiler.push({ tur: 'gonullu', p: [e[0] + rastgele(-30, 30), e[1] + rastgele(-30, 30)], hedefler: [], menzil: bleAcik });
  for (let i = 0; i < 6 && kisiler.length < enkaz + telefonSayisi; i++)
    kisiler.push({ tur: 'ekip', p: [rastgele(0, ALAN), rastgele(0, ALAN)], hedefler: [...enkazlar].sort(() => r() - 0.5), menzil: bleAcik });
  while (kisiler.length < enkaz + telefonSayisi) {
    const ev = [rastgele(0, ALAN), rastgele(0, ALAN)], z = r();
    const yakin = TOPLANMA.reduce((a, b) => (uzak(ev, a) < uzak(ev, b) ? a : b));
    const k = { tur: z < 0.7 ? 'toplanma' : z < 0.85 ? 'gezgin' : 'tahliye', p: ev, menzil: bleAcik };
    if (k.tur === 'toplanma') k.hedefler = [[yakin[0] + rastgele(-60, 60), yakin[1] + rastgele(-60, 60)]], k.basla = rastgele(900, 3600);
    if (k.tur === 'gezgin') k.hedefler = [yakin, HASTANE, ev, yakin, ev, HASTANE].map((h) => [h[0] + rastgele(-40, 40), h[1] + rastgele(-40, 40)]);
    if (k.tur === 'tahliye') {
      const yon = Math.atan2(ev[1] - MERKEZ, ev[0] - MERKEZ);
      k.hedefler = [[MERKEZ + 1400 * Math.cos(yon), MERKEZ + 1400 * Math.sin(yon)]];
      k.basla = rastgele(3600, 3 * 3600);
    }
    kisiler.push(k);
  }

  // --- düğümler (ürün kodu) -----------------------------------------------
  const kuyruk = [];
  let sonCevrim = -1;
  kisiler.forEach((k, i) => {
    k.cevrimici = false;
    k.dugum = createNode({
      identity: (k.kimlik = createIdentity()), now: () => simdi,
      authority: kurum.pub, online: () => kairo && k.cevrimici,
      uplink: async (env) => kurum.receive(env),
      send: (f) => kuyruk.push([i, f]),
      onEmergencyDelivered: () => { k.makbuz ??= simdi; },
    });
  });

  const izgara = new Map();
  const hucre = (p) => `${Math.floor(p[0] / 40)},${Math.floor(p[1] / 40)}`;
  const komsular = (i) => {
    const k = kisiler[i], out = [];
    const [cx, cy] = [Math.floor(k.p[0] / 40), Math.floor(k.p[1] / 40)];
    for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++)
      for (const j of izgara.get(`${cx + dx},${cy + dy}`) ?? [])
        if (j !== i && !kisiler[j].olu && uzak(k.p, kisiler[j].p) <= Math.min(k.menzil, kisiler[j].menzil)) out.push(j);
    return out;
  };

  const bitis = saat * 3600;
  for (let t = 0; t <= bitis; t += adimSn) {
    simdi = saat0 + t * 1000;
    // hareket: 1,2 m/sn yürüyüş
    for (const k of kisiler) {
      if (k.tur === 'enkaz') { if (t >= k.pilBiter) k.olu = true; continue; }
      if (k.basla && t < k.basla) continue;
      const h = k.hedefler?.[0];
      if (!h) continue;
      const d = uzak(k.p, h), adim = 1.2 * adimSn;
      if (d <= adim) {
        k.p = [...h];
        if (k.tur !== 'toplanma' && k.tur !== 'tahliye') {
          k.bekle = (k.bekle ?? 0) + adimSn;
          if (k.bekle >= (k.tur === 'ekip' ? 5400 : 1800)) { k.hedefler.shift(); k.bekle = 0; }
        }
      } else k.p = [k.p[0] + (h[0] - k.p[0]) * adim / d, k.p[1] + (h[1] - k.p[1]) * adim / d];
    }
    // şebeke: kapsama içinde dakikada bir bağlantı denemesi
    // `ekipAgGecidi`: rescue teams carry a phone with its own link (e.g. a satellite terminal), online throughout
    if (t % 60 === 0) for (const k of kisiler) k.cevrimici = !k.olu && ((ekipAgGecidi && k.tur === 'ekip') || (kapsama(k.p, t) && r() < sikisiklik));
    // çağrılar
    for (const k of kisiler)
      if (k.tur === 'enkaz' && !k.gonderdi && t >= k.yazar && !k.olu) {
        k.gonderdi = simdi;
        k.dugum.sendEmergency({ tur: 'enkaz', aciliyet: 1, kisi: 1, konum: { enlem: k.p[0], boylam: k.p[1] } });
        // "bugün" karşılaştırması: kendi telefonu şebeke bulursa ulaşırdı
      }
    izgara.clear();
    kisiler.forEach((k, i) => { if (!k.olu) { const c = hucre(k.p); if (!izgara.has(c)) izgara.set(c, []); izgara.get(c).push(i); } });
    // kendi telefonuyla ulaşma (iki koşulda da geçerli: telefon şebeke bulunca çağrı zaten gider)
    for (const k of kisiler) if (k.gonderdi && !k.dogrudan && k.cevrimici) k.dogrudan = simdi;
    if (kairo) {
      kisiler.forEach((k) => { if (!k.olu) k.dugum.tick(); });
      // radyo: çerçeveler aynı adımda menzildekilere ulaşır; cevaplar da aynı adımda işlenir
      for (let tur = 0; tur < 6 && kuyruk.length; tur++) {
        const bu = kuyruk.splice(0);
        for (const [i, f] of bu) for (const j of komsular(i)) kisiler[j].dugum.receive(f);
        await Promise.resolve();
      }
      kuyruk.length = 0;
      await new Promise((res) => setImmediate(res)); // yüklemeler (uplink) tamamlansın
    }
    if (t - sonCevrim >= 3600) sonCevrim = t;
  }

  // a call is matched by its sender's key and its send time: calls written in the same 10 s step must not share a
  // result (an earlier version matched by time alone; review finding, medya/triyaj-simulasyon B5)
  const kayit = new Map(kurum.reports.map((x) => [`${x.sender}|${x.sentAt}`, x.receivedAt]));
  const sonuc = kisiler.filter((k) => k.tur === 'enkaz' && k.gonderdi).map((k) => {
    const kurumda = kayit.get(`${Buffer.from(k.kimlik.pub).toString('hex')}|${k.gonderdi}`);
    const bugun = k.dogrudan;
    return {
      kairo: kurumda ? (kurumda - k.gonderdi) / 60000 : null,
      bugun: bugun ? (bugun - k.gonderdi) / 60000 : null,
      makbuz: k.makbuz ? (k.makbuz - k.gonderdi) / 60000 : null,
    };
  });
  return { yayginlik, tohum, telefon: kisiler.length, sonuc };
}

const oran = (dizi, sinir) => dizi.filter((x) => x !== null && x <= sinir).length / dizi.length;
const medyan = (d) => { const x = d.filter((v) => v !== null).sort((a, b) => a - b); return x.length ? x[Math.floor(x.length / 2)] : null; };

export function ozet(kosular) {
  const k = kosular.flatMap((x) => x.sonuc);
  const sat = (alan) => [30, 60, 180, 360, 720].map((dk) => oran(k.map((s) => s[alan]), dk));
  return { cagri: k.length, kairo: sat('kairo'), bugun: sat('bugun'), makbuz: sat('makbuz'),
    medyanKairo: medyan(k.map((s) => s.kairo)), medyanBugun: medyan(k.map((s) => s.bugun)) };
}

if (import.meta.url === `file://${process.argv[1].replace(/\\/g, '/').replace(/^([A-Z]):/, '/$1:')}` || process.argv[1]?.endsWith('afet.js')) {
  const arg = (ad, v) => { const i = process.argv.indexOf(ad); return i > 0 ? +process.argv[i + 1] : v; };
  const oranlar = process.argv.includes('--yayginlik') ? [arg('--yayginlik')] : [0.05, 0.1, 0.2];
  const ekipAgGecidi = process.argv.includes('--ekip-ag-gecidi');
  const tohumlar = Array.from({ length: arg('--tohum-sayisi', 5) }, (_, i) => arg('--tohum', 1) + i);
  const tum = {};
  for (const y of oranlar) {
    const kosular = [];
    for (const t of tohumlar) {
      const t0 = Date.now();
      kosular.push(await senaryo({ yayginlik: y, tohum: t, saat: arg('--saat', 12), ekipAgGecidi }));
      console.error(`yayginlik ${y} tohum ${t}: ${((Date.now() - t0) / 1000).toFixed(0)} sn`);
    }
    tum[y] = ozet(kosular);
    console.log(JSON.stringify({ yayginlik: y, ...(ekipAgGecidi ? { ekipAgGecidi: true } : {}), ...tum[y] }));
  }
}
