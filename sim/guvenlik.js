// Security simulations: can a modified app's fake calls keep real ones from reaching the authority?
// Runs the real node/crypto code through sim/afet.js; one configuration per run, so runs can go in parallel.
//
//   KAIRO_SIM_POW_BITS=1 node sim/guvenlik.js --ad taban
//   KAIRO_SIM_POW_BITS=1 node sim/guvenlik.js --ad sel-cekirdek --tohum-sayisi 3 --saat 4
//
// The proof of work is lowered only to make thousands of fakes quickly; it does not change who carries what.
// Prints one JSON line: share of real calls at the authority by 30/60/180/360/720 min, fakes uploaded.
import { senaryo, ozet } from './afet.js';

const arg = (ad, v) => { const i = process.argv.indexOf(ad); return i > 0 ? process.argv[i + 1] : v; };
const SEL = { saldirgan: 10, saldiriHizi: 6 };
const CEKIRDEK = { ...SEL, saldirganYeri: 'cekirdek' };
export const KOSULAR = {
  taban: {},
  'sel-toplanma': SEL,
  'sel-cekirdek': CEKIRDEK,
  'cekirdek-kaynak3': { ...CEKIRDEK, ayar: { kaynakSiniri: 3 } },
  'cekirdek-akilli-kaynak3': { ...CEKIRDEK, akilli: true, ayar: { kaynakSiniri: 3 } },
  'cekirdek-rastgele': { ...CEKIRDEK, ayar: { tahliye: 'rastgele' } },
  'cekirdek-kota48': { ...CEKIRDEK, ayar: { acilKota: 48 } },
  'cekirdek-agir': { saldirgan: 30, saldiriHizi: 10, akilli: true, saldirganYeri: 'cekirdek' },
};

const ad = arg('--ad', 'taban');
if (!KOSULAR[ad]) throw new Error(`bilinmeyen koşu: ${ad} (${Object.keys(KOSULAR).join(', ')})`);
const saat = +arg('--saat', 4), n = +arg('--tohum-sayisi', 3);
const kosular = [];
for (let t = 1; t <= n; t++) kosular.push(await senaryo({ yayginlik: 0.1, tohum: t, saat, ...KOSULAR[ad] }));
const o = ozet(kosular);
console.log(JSON.stringify({ ad, saat, tohum: n, cagri: o.cagri, kairo: o.kairo.map((x) => +x.toFixed(3)),
  medyan: o.medyanKairo && +o.medyanKairo.toFixed(1),
  sahteKurumda: kosular.reduce((s, k) => s + k.sahteKurumda, 0), sahteUretilen: kosular.reduce((s, k) => s + k.sahteUretilen, 0) }));
