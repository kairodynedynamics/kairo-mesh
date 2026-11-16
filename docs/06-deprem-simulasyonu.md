# 06 — Deprem simülasyonu

Bu bölüm tek bir soruyu yanıtlar: **Enkaz altındaki birinin yardım çağrısı kuruma ne kadar sürede ulaşır?**

Simülasyon soyut bir model kullanmaz. Telefonlarda ürünün kendi düğüm kodu (`src/node.js`), karşı tarafta gerçek kurum sunucusu (`src/kurum.js`) çalışır. Şifreleme, iş kanıtı, kota, kurye seçimi ve makbuz ürünle birebir aynıdır. Simülasyona ait olan yalnızca hareket, radyo menzili ve şebeke modelidir.

```
node sim/afet.js                                         # %5, %10, %20 yaygınlık × 5 tohum, 12 saat
node sim/afet.js --yayginlik 0.1 --ekip-ag-gecidi        # kurtarma ekiplerinde sürekli bağlı telefon
node sim/rapor.js md                                     # aşağıdaki tablo
```

## Varsayımlar

Her varsayım `senaryo()` içinde bir parametredir. Değerler 6 Şubat 2023 sonrası raporlara ve BLE ölçümlerine göre makul seçildi, **sahada ölçülmedi.**

- **Alan ve nüfus:** 2×2 km yerleşim, 4.000 kişi. Uygulama nüfusun `yayginlik` oranındaki telefonda yüklü.
- **Şebeke:** Hasarlı çekirdekte (merkezden 900 m içi) baz istasyonu yok. Çevrede şebeke var ama sıkışık: bir telefon her dakika %35 olasılıkla veri bağlantısı kurabilir. 8. saatte çekirdeğin ortasına mobil baz istasyonu gelir (500 m yarıçap, aynı sıkışıklık).
- **Bluetooth menzili:** açıkta 30 m, enkaz altındaki telefon için 8 m.
- **Enkaz altındakiler:** 30 kişi. Çağrıyı ilk 60 dakika içinde yazarlar; telefonlarının pili 4 ile 24 saat arasında biter.
- **Hayatta kalanlar:**
  - %70'i 15–60 dakika içinde en yakın toplanma alanına gider ve orada kalır.
  - %15'i ev, toplanma alanı ve hastane arasında dolaşır.
  - %15'i 1–3 saat içinde bölgeden çıkar.
  - Enkazların başında gönüllüler bekler: her enkazda 0–4 kişi, 30 m içinde.
- **Kurtarma ekipleri:** 6 ekip enkazdan enkaza dolaşır. Ekip ağ geçidi senaryosunda ekiplerin telefonu uydu terminali gibi kendi bağlantısıyla sürekli çevrimiçidir.
- **Karşılaştırma ("bugün"):** KairoMesh olmadan çağrı, yalnızca gönderenin kendi telefonu şebekeye kavuşursa ulaşır.

## Sonuçlar

Her satır 5 tohumun toplamıdır (5 × 30 = 150 çağrı). Hücreler, çağrıların o süre içinde kuruma ulaşan payını gösterir.

<!-- node sim/rapor.js md çıktısı -->
| Koşul | 30 dk | 1 saat | 3 saat | 6 saat | 12 saat | Ulaşanlarda medyan süre | Çağrı |
|---|---|---|---|---|---|---|---|
| KairoMesh, uygulama nüfusun %5'inde | %4 | %12 | %29 | %29 | %85 | 7,3 saat | 150 |
| KairoMesh, uygulama nüfusun %10'unda | %9 | %26 | %53 | %53 | %92 | 1,9 saat | 150 |
| KairoMesh, uygulama nüfusun %10'unda + ekiplerde uydu bağlantılı telefon | %23 | %40 | %71 | %79 | %92 | 74 dk | 150 |
| KairoMesh, uygulama nüfusun %20'sinde | %13 | %41 | %71 | %71 | %95 | 71 dk | 150 |
| Bugün: yalnız kendi telefonu şebeke bulursa | %0 | %0 | %0 | %0 | %34 | 7,5 saat | 150 |

## Okuma

- **Bugün:** Çekirdekteki enkaz altındaki birinin çağrısı, mobil baz istasyonu gelene kadar hiçbir yere gitmez. 12 saatte ulaşan pay yalnızca %34'tür, çünkü birçok telefonun pili o zamana kadar biter.
- **KairoMesh:** Çağrı, enkaz başındaki gönüllülere ve oradan toplanma alanlarına, şebekeye kavuşan ilk telefona taşınır. %10 yaygınlıkta çağrıların yarısı, %20 yaygınlıkta %71'i 3 saat içinde kurumdadır.
- **3 ile 6 saat arasındaki düzlük:** Çekirdekte şebeke olmadığı sürece bazı enkazların çevresinden kimse geçmez. 8. saatte mobil baz istasyonu gelince bu çağrılar da ulaşır.
- **Ekip ağ geçidi:** Bu düzlüğü kısaltan en ucuz önlem, kurtarma ekiplerinde sürekli bağlı tek bir telefon bulundurmaktır. İlk 30 dakikada ulaşan pay %9'dan %23'e, 3 saatte ulaşan pay %53'ten %71'e çıkar. Bu, uygulamanın nüfusun %20'sine yayılmasıyla aynı etkiyi verir (3 saatte ikisi de %71). Altı uydu terminali, yüz binlerce ek kurulumdan çok daha ucuzdur. Kurumlarla pilotta ilk önerilecek kurulum budur.

## Sınırlar

- Bütün parametreler tahmindir: menzil, sıkışıklık, insan hareketi. Doğrulanmaları için gerçek telefonlarla saha tatbikatı gerekir (yol haritası, Faz 2).
- Radyo modeli basittir: menzil içindeki herkes çerçeveyi alır; çakışma ve parazit modellenmez. Kalabalık toplanma alanlarında bunun etkisi ancak sahada ölçülebilir.
- Simülasyonda çağrılar yapılandırılmış olarak yazılır. Serbest metinden ayrıştırma (`triyaj/`) ayrı ölçülür.
