// sim/sonuc-afet.jsonl -> Markdown tablo (docs/06) ve HTML tablo (site). Rakamlar yalnız buradan çıkar.
//   node sim/rapor.js md     node sim/rapor.js html
import { readFileSync } from 'node:fs';

const satirlar = readFileSync(new URL('./sonuc-afet.jsonl', import.meta.url), 'utf8').split('\n').filter(Boolean)
  .flatMap((s) => { try { return [JSON.parse(s)]; } catch { return []; } })
  .sort((a, b) => a.yayginlik - b.yayginlik);
const SURE = ['30 dk', '1 saat', '3 saat', '6 saat', '12 saat'];
const y = (x) => '%' + Math.round(100 * x);
const dk = (x) => (x == null ? '—' : x < 90 ? `${Math.round(x)} dk` : `${(x / 60).toFixed(1).replace('.', ',')} saat`);

const kayit = [];
for (const r of satirlar) kayit.push([`Kairo Mesh, uygulama nüfusun %${Math.round(r.yayginlik * 100)}'inde`, ...r.kairo.map(y), dk(r.medyanKairo), r.cagri]);
if (satirlar.length) {
  const b = satirlar[0];
  kayit.push(['Bugün: yalnız kendi telefonu şebeke bulursa', ...b.bugun.map(y), dk(b.medyanBugun), b.cagri]);
}
const baslik = ['Koşul', ...SURE, 'Ulaşanlarda medyan süre', 'Çağrı'];

if (process.argv[2] === 'html') {
  console.log('<div class="tablo"><table><thead><tr>' + baslik.map((h) => `<th>${h}</th>`).join('') + '</tr></thead><tbody>'
    + kayit.map((r, i) => `<tr${i < kayit.length - 1 ? ' class="vurgu"' : ''}>` + r.map((c) => `<td>${c}</td>`).join('') + '</tr>').join('')
    + '</tbody></table></div>');
} else {
  console.log('| ' + baslik.join(' | ') + ' |\n|' + baslik.map(() => '---').join('|') + '|');
  for (const r of kayit) console.log('| ' + r.join(' | ') + ' |');
}
