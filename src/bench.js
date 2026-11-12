// Prints the validation tables (markdown) used in docs/04-dogrulama.md.
import { simulate } from './sim.js';
import { encoder, decoder } from './fountain.js';
import { rng } from './rng.js';

const SEEDS = 20;
const mean = (xs) => xs.reduce((a, b) => a + b, 0) / xs.length;
const sd = (xs) => { const m = mean(xs); return Math.sqrt(mean(xs.map((x) => (x - m) ** 2))); };

function routing(title, world) {
  console.log(`\n### ${title}\n`);
  console.log('| Politika | Teslim oranı | Medyan gecikme (dk) | İletim / mesaj |');
  console.log('|---|---|---|---|');
  const rows = [['wait', 4, 'Bitchat kuralı (spray-and-wait, L=4)'], ['focus', 4, '**Kairo, L=4**'],
    ['wait', 8, 'spray-and-wait, L=8 (Bitchat üst sınırı)'], ['focus', 8, '**Kairo, L=8**'],
    ['wait', 16, 'spray-and-wait, L=16'], ['epidemic', 0, 'Salgın (teorik tavan)']];
  for (const [policy, L, label] of rows) {
    const rs = Array.from({ length: SEEDS }, (_, i) => simulate({ policy, L, world, seed: i + 1 }));
    const d = rs.map((r) => r.delivery * 100);
    console.log(`| ${label} | %${mean(d).toFixed(1)} ± ${sd(d).toFixed(1)} | ${mean(rs.map((r) => r.medianLatencyMin)).toFixed(0)} | ${mean(rs.map((r) => r.txPerMessage)).toFixed(1)} |`);
  }
}

// Packets a sender must emit before a receiver behind a lossy link (no feedback) has the whole message.
function lossy(k, loss, coded, r) {
  const dec = decoder(k, 1), got = new Set(), data = Buffer.alloc(1);
  for (let sent = 1; ; sent++) {
    if (r() < loss) continue;
    if (coded) dec.add({ seed: sent - 1 < k ? sent - 1 : k + sent, data }); else got.add((sent - 1) % k);
    if (coded ? dec.done : got.size === k) return sent;
  }
}

// Packets a receiver must hear when several relays each send fragments without coordinating.
function uncoordinated(k, coded, r) {
  const dec = decoder(k, 1), got = new Set(), data = Buffer.alloc(1);
  for (let heard = 1; ; heard++) {
    if (coded) dec.add({ seed: k + Math.floor(r() * 0xfffff000), data }); else got.add(Math.floor(r() * k));
    if (coded ? dec.done : got.size === k) return heard;
  }
}

function fragments() {
  const k = 64, trials = 2000, r = rng(7);
  const stat = (f) => { const xs = Array.from({ length: trials }, f).sort((a, b) => a - b); return `${mean(xs).toFixed(0)} / ${xs[Math.floor(trials * 0.95)]}`; };
  console.log(`\n### Parçalı aktarım: ${k} parça (~30 KB), ortalama / %95 paket sayısı\n`);
  console.log('| Senaryo | Düz parçalar (Bitchat tarzı) | Kairo kodlu parçalar |');
  console.log('|---|---|---|');
  for (const loss of [0.1, 0.3, 0.5])
    console.log(`| Tek gönderici, %${loss * 100} kayıp, geri bildirimsiz | ${stat(() => lossy(k, loss, false, r))} | ${stat(() => lossy(k, loss, true, r))} |`);
  console.log(`| Koordinasyonsuz çoklu röle, kayıpsız | ${stat(() => uncoordinated(k, false, r))} | ${stat(() => uncoordinated(k, true, r))} |`);
}

routing('Kurye yönlendirme: 80 kişi, 6 mahalle, 2×2 km, 8 saat', 2000);
routing('Kurye yönlendirme: aynı nüfus, 4×4 km (daha seyrek)', 4000);
fragments();
