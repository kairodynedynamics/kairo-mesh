# Doğrulama

Tekrar üretmek için: `npm test` ve `npm run bench` (Node 24, bağımlılık yok); Android tarafı için `android/` altındaki betikler. Yönlendirme rakamları **simülasyondur**; gerçek telefonla saha testi yapılmadı, Bluetooth yalnız Android emülatörlerinin sanal radyosunda denendi.

## Yöntem

- "Bitchat kuralı", Bitchat kodunun kendisi değil; whitepaper'daki kurye parametrelerinin (bütçe 4, tavan 8, yarısını devret) benim yeniden uygulamamdır.
- Her politika aynı tohumla aynı hareket ve aynı mesaj trafiği üzerinde koşar. 20 tohum, ortalama ± standart sapma.
- Hareket modeli: 80 kişi, 6 mahalle; kişi zamanının %80'ini kendi mahallesinde, %15'ini düzenli gittiği ikinci yerde geçirir. Menzil 30 m, yürüme hızı 1–1,5 m/sn. İlk saat ısınma, 300 mesaj, 3 saat ömür.
- Tek ayarlanan parametre (odaklanma eşiği 0,3) ayrı tohumlarda (101–110) seçildi, sonuçlar 1–20 tohumlarında raporlandı.

## Sonuç 1: kurye yönlendirme

L = bir mesajın aynı anda depolanan en çok kopya sayısı.

### 80 kişi, 2×2 km

| Politika | Teslim oranı | Medyan gecikme (dk) | İletim / mesaj |
|---|---|---|---|
| Bitchat kuralı (spray-and-wait, L=4) | %89,8 ± 4,2 | 33 | 2,9 |
| **Kairo, L=4** | %99,5 ± 0,7 | 34 | 5,7 |
| spray-and-wait, L=8 (Bitchat üst sınırı) | %97,5 ± 1,6 | 27 | 6,4 |
| **Kairo, L=8** | %100,0 ± 0,1 | 28 | 10,2 |
| spray-and-wait, L=16 | %99,8 ± 0,2 | 24 | 12,4 |
| Salgın (teorik tavan) | %100,0 | 20 | 39,6 |

### Aynı nüfus, 4×4 km (daha seyrek)

| Politika | Teslim oranı | Medyan gecikme (dk) | İletim / mesaj |
|---|---|---|---|
| Bitchat kuralı (spray-and-wait, L=4) | %68,6 ± 7,7 | 60 | 2,9 |
| **Kairo, L=4** | %85,1 ± 8,0 | 68 | 5,8 |
| spray-and-wait, L=8 (Bitchat üst sınırı) | %85,1 ± 6,1 | 55 | 6,5 |
| **Kairo, L=8** | %94,1 ± 5,0 | 60 | 10,0 |
| spray-and-wait, L=16 | %95,2 ± 3,6 | 51 | 12,6 |
| Salgın (teorik tavan) | %100,0 | 44 | 40,6 |

**Dürüst okuma:**

- Bitchat'in varsayılan bütçesine karşı teslim oranı 10 ila 17 puan yüksek, ama iletim sayısı iki katı. Bu kazancın çoğu "daha çok çalışmaktan" geliyor.
- Adil karşılaştırma eşit maliyette yapılır: Kairo, **yarı sayıda depolanan kopyayla**, iki kat kopya kullanan spray-and-wait ile aynı teslim oranına %10–20 daha az iletimle ulaşıyor. Asıl kazanç depolama: kurye kutusu Bitchat'te kıt kaynak (40 yuva).
- Bedeli: medyan gecikme %5–25 daha uzun.
- Kazanç, insanların düzenli hareket ettiği varsayımına dayanır. Tamamen rastgele harekette (ör. ilk kez gidilen festival alanı) puanın bilgi değeri yoktur ve fark kapanır; bunu ölçmedim.

### Saldırıya dayanıklı kuralın bedeli

Aşağıdaki güvenlik testlerinden sonra düğüm kuralı sertleştirildi: puan yalnız kendi kişilerin için ve 0–3 arası verilir, yabancı en çok kopyaların yarısını alır, son kopya yalnız kişiye geçer, gönderici kendi son kopyasını hiç bırakmaz. Bu, simülasyonda sosyal trafikte (insanlar tanıdıklarına yazar) teslim oranını düşürdü; puana "alıcıyı tanıyorum" kanıtı eklemek kaybın çoğunu geri aldı.

| Kural (Kairo, sosyal trafik) | 2×2 km, L=4 | 4×4 km, L=4 | 2×2 km, L=8 | 4×4 km, L=8 |
|---|---|---|---|---|
| Sertleştirilmemiş | %99,4 | %88,8 | | |
| Sertleştirilmiş | %97,9 | %84,4 | | |
| **Sertleştirilmiş + tanıma kanıtı (uygulamadaki kural)** | **%99,5** | **%88,3** | **%100,0** | **%94,3** |

## Sonuç 2: parçalı aktarım

64 parça (~30 KB). Gönderilmesi/duyulması gereken paket sayısı: ortalama / %95'lik.

| Senaryo | Düz parçalar | Kairo kodlu parçalar |
|---|---|---|
| Tek gönderici, %10 kayıp, geri bildirimsiz | 145 / 212 | 73 / 79 |
| Tek gönderici, %30 kayıp, geri bildirimsiz | 260 / 382 | 94 / 105 |
| Tek gönderici, %50 kayıp, geri bildirimsiz | 441 / 677 | 131 / 152 |
| Koordinasyonsuz çoklu röle, kayıpsız | 304 / 453 | 66 / 69 |

**Dürüst okuma:** Kodlu parçalar 2 ila 4,6 kat daha az paketle tamamlanıyor ve kuyruk çok daha dar. Ancak karşılaştırma "geri bildirimsiz düz parça" ile yapıldı. Bitchat pratikte bağlı GATT hattında bağlantı katmanı onayı ve 15 saniyelik GCS eşitlemesi kullanır; gerçek fark bu yüzden daha küçük olur. Kodlamanın kesin üstünlüğü, geri bildirimin pahalı ya da imkânsız olduğu yerdedir: kısa temas pencereleri, yayın (broadcast) ve kurye üzerinden medya.

## Testler

`npm test`: 18 test, hepsi geçiyor.

- İşlev: fountain kodun 0–40.000 baytlık mesajları kayıp altında birebir geri kurduğu ve bozuk paketi reddettiği; kimliğin her 15 dakikada değiştiği ve yalnız anahtarla tanındığı; zarfı yalnız alıcının açtığı ve değiştirilen zarfın reddedildiği; birbirini hiç görmeyen iki kişi arasında kuryenin mesajı taşıdığı, içeriği okuyamadığı ve makbuzdan sonra kopyasını sildiği; 20 KB'lık mesajın %30 çerçeve kaybında ulaştığı; simülatörün belirlenimci olduğu.
- Saldırı (`test/attacks.test.js`): her test, düğümün önceki bir sürümünde **işe yarayan** bir saldırıdır ve şimdi başarısız olduğunu doğrular.

| Saldırı | Önceki sürümde ne oluyordu | Kapatan değişiklik |
|---|---|---|
| İzleme | HELLO'daki mesaj kimlikleri kimlik değişse de aynıydı; cihaz bağlanabiliyordu | Her alan o anki geçici kimlikle körleştirilir |
| Kara delik | "Alıcıyı sık görüyorum" diyen sahte kurye göndericinin son kopyasını alıyordu | Puan yalnız kişilere; son kopya hiç bırakılmaz |
| Sahte onay | Devri dinleyen biri onay uydurup kuryeye kopya sildirebiliyordu | Onay, zarfın tamamına sahip olunduğunun kanıtını ister |
| Alıcı taklidi | "Alıcı benim" diyene kurye kopyasını siliyordu | Silme yalnız alıcının üretebileceği makbuzla |
| Depo doldurma | Yabancıların çöp zarfları kişilerin postasını siliyordu | Yabancılara deponun yarısı |
| Yarım aktarım doldurma | Tamamlanmayan aktarımlar yeni aktarımı engelliyordu | Yabancılara yarım aktarımların yarısı |
| Yük katlama | Tek sahte çerçeve yüzlerce gönderim tetikliyordu | Her 2 sn'de sınırlı SCORE/DATA bütçesi, mesaj başına tek servis |
| Eski mesaj | Bir günden eski ya da gelecekten gelen zarf teslim ediliyordu | Zarfın içinde mühürlü gönderim zamanı |
| Yeniden başlatma sonrası tekrar | Havadan kaydedilen çerçeveler, uygulama yeniden açılınca mesajı ikinci kez gösteriyordu | Teslim edilenler listesi diske yazılır (`delivered`) |
| Zaman damgası taşması (Kotlin) | Kötü niyetli bir kişi, "şimdi + 2^63" mühürlediğinde tazelik kontrolü taşıyor ve mesaj süresiz tekrar oynatılabilir oluyordu | Çıkarma yerine sınırlarla karşılaştırma; üç uygulamada da test |

## Android

`android/` altında protokolün Kotlin sürümü ve en küçük bir sohbet uygulaması (minSdk 33). Derleme Windows'ta Gradle 8.9, AGP 8.5.2, JDK 17 ile.

- **Birlikte çalışma:** `./gradlew testDebugUnitTest`, 10 test. JavaScript'in ürettiği test vektörleri (zarf, kimlik, fountain paketleri) Kotlin'de birebir aynı çıkar; Kotlin'in mühürlediği zarfı JavaScript açar (`node android/interop-check.mjs`). İki düğüm aynı ağda konuşabilir.
- **Düğüm:** kurye senaryosu, rastgele çerçeve dayanıklılığı ve yeniden başlatma sonrası tekrar saldırısı Kotlin'de de test edilir.
- **Üç emülatörde uçtan uca:** `bash android/emulators.sh` üç sanal telefon açar, `bash android/emulator-test.sh` gerçek uygulamayı sanal Bluetooth (netsim) üzerinden sınar:
  1. Ali ve Bora açıkken doğrudan mesaj ulaşır.
  2. Bora kapalıyken Ali mesajı Kurye'ye bırakır, Ali kapanır, Bora açılır: mesaj Kurye üzerinden ulaşır, Kurye makbuzla kopyasını siler ve mesajı hiçbir zaman okuyamaz.
  3. Kurye ana ekrana döner ve Android arka plan süreçlerini öldürür (`am kill`): ağ ön plan servisinde (`MeshService`) çalıştığı için Kurye mesajı taşımaya devam eder.
  4. Telefonda saklanan özel anahtar şifrelidir: dışa aktarılamayan bir Android Keystore anahtarıyla AES-GCM ile sarılır; eski sürümlerin şifresiz anahtarı ilk açılışta taşınır.
  Son çalıştırma: hepsi geçti.

## iOS

`ios/` altında protokolün Swift sürümü (`KairoCore`, Swift paketi). Apple cihazlarında CryptoKit'i, diğer sistemlerde aynı arayüzlü swift-crypto'yu kullanır; bu sayede çekirdek Mac olmadan da derlenip test edilir.

- Bu bilgisayarda: Windows içindeki Ubuntu'da (WSL) Swift 6.0.3, `D:\Android\swift` altında. Çalıştırma: `wsl -e bash -c ". /mnt/d/Android/swift/env.sh && cd /mnt/d/Kairodyne-Dynamics/ios && swift test"`.
- 10 test geçiyor: JavaScript test vektörleriyle bayt bayt aynı çıktı, JavaScript'in mühürlediği zarfı açma ve JavaScript düğümünün mesajını alma; ters yönde JavaScript, Swift düğümünün çerçevelerini okur (`node android/interop-check.mjs ios/.build/interop-from-swift.json`). Kurye, rastgele çerçeve (Swift'te dizi taşması çökme demektir, bu yüzden her uzunluk sınanır), yeniden başlatma sonrası tekrar ve zaman damgası taşması testleri.
- iPhone uygulaması (`ios/App`, SwiftUI + CoreBluetooth): Android ile aynı Bluetooth düzeni, kimlik Keychain'de (yalnız bu cihaz), teslim edilenler gösterilmeden önce kaydedilir.
- Bulut testi (`.github/workflows/ci.yml`, her gönderimde): GitHub'ın macOS makinesinde çekirdek CryptoKit ile test edilir, uygulama derlenir ve **iPhone 16 Pro ile iPad Pro simülatörlerinde** hem 10 çekirdek testi hem arayüz testi geçer. Arayüz testi: eşleşme kodunun gösterilmesi, hatalı kodun reddi, mesajın mühürlenip kuyruğa alınması, uygulama yeniden açılınca kimliğin korunması.
- Doğrulanmayan: iPhone'lar arası Bluetooth. iOS simülatörlerinde Bluetooth yoktur; gerçek iPhone gerekir.

## Doğrulanmayanlar

- Gerçek BLE davranışı: emülatörün sanal radyosu kayıpsız ve sınırsız menzilli; gerçek telefonlarda bağlantı süresi, arka plan kısıtları, pil tüketimi ve bant genişliği ölçülmedi.
- Arka plan çalışması emülatörde sınandı; üreticilerin agresif pil tasarrufu (Xiaomi, Samsung vb.) gerçek cihazda ayrıca denenmeli.
- Mesajı sessizce düşüren kurye engellenemez, yalnız etkisi sınırlanır: göndericinin kopyası kalır, diğer kopyalar başka kuryelerdedir.
- 7 atlama sınırı ve kutu kapasitesi simülatörde modellenmedi (tüm politikaları eşit etkiler).
- Güvenlik: bağımsız denetim yok.
