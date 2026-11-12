# Bitchat Araştırması

Tarih: 12 Kasım 2026. Kaynaklar en altta. Rakamların çoğu geliştiricinin kendi beyanı veya tek kaynaklı analiz verisidir; bağımsız doğrulanmış sayılmamalı.

## Nedir

Jack Dorsey ve ekibinin Temmuz 2025'te duyurduğu, internet, SIM, hesap ve sunucu gerektirmeyen şifreli mesajlaşma uygulaması. Telefonlar Bluetooth Low Energy (BLE) üzerinden birbirine bağlanır; her telefon hem kullanıcı hem röledir. İnternet varken Nostr röleleri üzerinden uzak mesafeye de ulaşır. iOS (Swift, kamu malı) ve Android (Kotlin, MIT) açık kaynak.

## İnsanlar neden istiyor

| Durum | Örnek |
|---|---|
| Devlet internet kesintisi | Nepal (8 Eylül 2025'te tek günde ~50.000 indirme), Madagaskar (bir haftada ~70.000), Uganda ve İran (Ocak 2026), Hindistan (Temmuz 2026: küresel indirmelerin %74–85'i, 23 Temmuz'da 330.000+ aktif kullanıcı) |
| Afet | Şebekenin çöktüğü deprem, sel, kasırga |
| Kalabalık | Festival, stadyum, miting: baz istasyonu tıkanır |
| Kapsama yok | Dağ, deniz, uçak, maden, yeraltı |
| Mahremiyet | Telefon numarası ve hesap vermeden yazışma |

Ortak nokta: **talep süreklilik göstermiyor, kriz anında patlıyor.** Uygulama önceden yüklü değilse kriz anında indirilemiyor (Bitchat bu yüzden Temmuz 2026'da Android'e çevrimdışı APK paylaşımı ekledi).

## Nasıl çalışıyor (whitepaper v2.0)

- **Taşıma:** Her cihaz aynı anda GATT merkez ve çevre birimi. Wi-Fi taşıması yok.
- **Yayılım:** Kontrollü sel. TTL 7; yoğun ağda (≥6 bağlantı) 5'e iner. Yayınlar bağlantıların ~log₂(derece) kadarına gider, 10–220 ms rastgele gecikme, kopya başka röleden gelirse iptal.
- **Tekilleştirme:** 1000 kayıtlık, 5 dakikalık LRU.
- **Yönlendirme:** Duyurular 10 komşu kimliği taşır; doğrulanmış yol varsa kaynak yönlendirme, yoksa sel.
- **Parçalama:** ~469 baytlık parçalar bağımsız yayılır; 30 sn içinde hepsi gelmezse mesaj düşer. 1 MiB üst sınır.
- **Sakla-ilet (kurye):** Mesaj en çok 3 kuryeye bırakılır; "spray-and-wait", bütçe 4 (tavan 8), kurye bütçesinin yarısını devreder. Zarf en çok 16 KiB, 24 saat, **yalnız metin**. Kurye kutusu 40 yuva.
- **Eşitleme:** 1000 açık paket, ~15 sn'de bir GCS filtresiyle uzlaştırma.
- **Şifreleme:** Canlı oturumda Noise XX (ileri gizlilik var). Kurye zarflarında Noise X (ileri gizlilik **yok**).
- **Kimlik:** 8 baytlık eş kimliği statik anahtarın özeti; **hiç değişmez**.

## Kendi kabul ettiği zayıflıklar

1. **Üstveri en zayıf nokta.** Gönderici kimliği dönmüyor; duyurular statik anahtarı ve takma adı açık yayınlıyor. Pasif bir dinleyici cihazları izleyebilir. Komşu listeleri kimin kimin yanında olduğunu açığa veriyor.
2. Kurye ve Nostr zarflarında ileri gizlilik yok.
3. Dolgu yalnız Noise paketlerinde; çoğu trafikte mesaj uzunluğu görünür.
4. Kuryeler mesajı düşürebilir; yedeklilik bunu kısmen telafi eder.
5. Kuryeler medya taşıyamaz.

## Dışarıdan eleştiriler

- Yayın sonrası günler içinde kimlik taklidi açığı bulundu (Alex Radocea); Noise'a geçilerek kapatıldı. **Hâlâ bağımsız güvenlik denetimi yok**; rakip Briar'ın Cure53 denetimi var.
- Pil: rölelik yapan herkes başkasının trafiği için pil harcıyor; iOS ve Android arka plan BLE'yi sert kısıtlıyor.
- Menzil: atlama başına ~30 m; etrafta yeterli kullanıcı yoksa mesaj geç gelir ya da hiç gelmez.
- Hukuki risk: Çin App Store'dan kaldırıldı (Nisan 2026); Hindistan önce GitHub deposunun engellenmesini istedi (Temmuz 2026), sonra uygulama mağazalardan kaldırıldı (3 Ekim 2026).
- Sistematik bir kullanıcı yorumu derlemesi bulamadım; yukarıdaki şikâyetler ağırlıkla güvenlik yazarlarından ve analistlerden geliyor.

## Rakipler ve para

| Ürün | Durum | Gelir modeli |
|---|---|---|
| Bitchat | Ücretsiz, açık kaynak, Block destekli | Yok |
| Briar | Denetimli, bakım modunda | Hibe |
| Bridgefy | Tüketici uygulaması + SDK | **SDK lisansı (aylık aktif kullanıcı başına, fiyat gizli)** |
| Meshtastic | LoRa donanımı | Donanım satışı |
| Berty | Açık kaynak | Hibe / DAO |

Bu alanda para kazanan tek model **başka uygulamalara SDK lisanslamak**. Bridgefy'nin 2020'de yayımlanmış ciddi güvenlik açıkları var; "denetlenmiş ve ölçülmüş" bir alternatif için boşluk burada.

## Çıkarımlar

- Tüketici uygulaması olarak Bitchat ile yarışmak anlamsız: ücretsiz, ünlü sahibi var, gelir yok, yasaklanma riski var.
- Bitchat'in açık bıraktığı teknik alanlar net: seyrek ağda teslim, kayıplı/çok kaynaklı ortamda büyük içerik, üstveri gizliliği.
- Ödeyecek müşteri kriz anındaki birey değil, kullanıcısına "şebeke yokken de çalışır" demek isteyen kurumdur.

## Kaynaklar

- [Bitchat whitepaper](https://github.com/permissionlesstech/bitchat/blob/main/WHITEPAPER.md)
- [Wikipedia: BitChat](https://en.wikipedia.org/wiki/BitChat)
- [Rest of World: Hindistan'ın GitHub engelleme talebi](https://restofworld.org/2026/india-github-geoblock-jack-dorsey-bitchat/)
- [BISI: Bluetooth mesh ağları ve internet kesintileri](https://bisi.org.uk/reports/bitchat-bluetooth-mesh-networks-and-internet-shutdowns)
- [TechCrunch: güvenlik testi yapılmadı](https://techcrunch.com/2025/07/09/jack-dorsey-says-his-secure-new-bitchat-app-has-not-been-tested-for-security)
- [Kaspersky: mesh mesajlaşma uygulamaları](https://www.kaspersky.com/blog/mesh-messengers/54192/)
- [braindetox: Bitchat nerede kırılıyor](https://braindetox.kr/en/posts/bitchat_radicle_p2p_messaging_2026.html)
- [Bridgefy SDK fiyatlandırma SSS](https://bridgefy.notion.site/Bridgefy-SDK-Pricing-22cf443f5ef680039882cd88cedac706)
- [TechTarget: What is Bitchat](https://www.techtarget.com/whatis/feature/What-is-Bitchat)
