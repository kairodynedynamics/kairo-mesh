// sim/sonuc-afet.jsonl -> Markdown tablo (docs/06) ve HTML tablo (site). Rakamlar yalnız buradan çıkar.
//   node sim/rapor.js md     node sim/rapor.js html
import { readFileSync } from 'node:fs';

const oku = (ad) => { try { return readFileSync(new URL(ad, import.meta.url), 'utf8'); } catch { return ''; } };
// sonuc-afet-ekip.jsonl: `--ekip-ag-gecidi` koşusu (kurtarma ekiplerinde sürekli bağlı telefon)
const satirlar = (oku('./sonuc-afet.jsonl') + '\n' + oku('./sonuc-afet-ekip.jsonl')).split('\n').filter(Boolean)
  .flatMap((s) => { try { return [JSON.parse(s)]; } catch { return []; } })
  .sort((a, b) => a.yayginlik - b.yayginlik || (a.ekipAgGecidi ? 1 : 0) - (b.ekipAgGecidi ? 1 : 0));
// Türkçe ek: %5'inde, %10'unda, %20'sinde
const ek = (n) => ({ 5: "'inde", 10: "'unda", 20: "'sinde", 50: "'sinde" }[n] ?? "'inde");
const SURE = ['30 dk', '1 saat', '3 saat', '6 saat', '12 saat'];
const y = (x) => '%' + Math.round(100 * x);
const dk = (x) => (x == null ? '—' : x < 90 ? `${Math.round(x)} dk` : `${(x / 60).toFixed(1).replace('.', ',')} saat`);

const kayit = [];
for (const r of satirlar) kayit.push([`Kairo Mesh, uygulama nüfusun %${Math.round(r.yayginlik * 100)}${ek(Math.round(r.yayginlik * 100))}${r.ekipAgGecidi ? ' + ekiplerde uydu bağlantılı telefon' : ''}`, ...r.kairo.map(y), dk(r.medyanKairo), r.cagri]);
if (satirlar.length) {
  const b = satirlar[0];
  kayit.push(['Bugün: yalnız kendi telefonu şebeke bulursa', ...b.bugun.map(y), dk(b.medyanBugun), b.cagri]);
}
const baslik = ['Koşul', ...SURE, 'Ulaşanlarda medyan süre', 'Çağrı'];

if (process.argv[2] === 'grafik') {
  // ana sayfadaki kaydırmayla dolan çubuklar: 3. saat ve 12. saat
  const satir = (ad, d, sinif) => `<div class="cizelge-satir ${sinif}"><span class="cizelge-ad">${ad}</span>`
    + [2, 4].map((i) => `<span class="cizelge-cubuk" style="--deger:${Math.round(d[i] * 100)}"><i></i><b>%${Math.round(d[i] * 100)}</b></span>`).join('') + '</div>';
  const b = satirlar[0];
  let out = '<div class="cizelge" aria-label="Enkaz altından gelen çağrıların kuruma ulaşma oranı">'
    + '<div class="cizelge-satir baslik"><span></span><span>3 saatte</span><span>12 saatte</span></div>';
  if (b) out += satir('Bugün', b.bugun, 'bugun');
  for (const r of satirlar) out += satir(`Kairo Mesh, nüfusun %${Math.round(r.yayginlik * 100)}${ek(Math.round(r.yayginlik * 100))}${r.ekipAgGecidi ? ' + ekipte uydu telefonu' : ''}`, r.kairo, r.ekipAgGecidi ? 'ekip' : 'kairo');
  console.log(out + '</div>');
} else if (process.argv[2] === 'html') {
  console.log('<div class="tablo"><table><thead><tr>' + baslik.map((h) => `<th>${h}</th>`).join('') + '</tr></thead><tbody>'
    + kayit.map((r, i) => `<tr${i < kayit.length - 1 ? ' class="vurgu"' : ''}>` + r.map((c) => `<td>${c}</td>`).join('') + '</tr>').join('')
    + '</tbody></table></div>');
} else {
  console.log('| ' + baslik.join(' | ') + ' |\n|' + baslik.map(() => '---').join('|') + '|');
  for (const r of kayit) console.log('| ' + r.join(' | ') + ' |');
}
