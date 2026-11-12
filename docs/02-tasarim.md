# Kairo Mesh: Tasarım

## Ne ve neden

Kairo Mesh, başka uygulamaların içine gömülen bir **gecikmeye dayanıklı mesh mesajlaşma motoru**. Bitchat'in fikrini (telefonlar birbirine röle olur) alır, Bitchat'in kendi whitepaper'ında açık bıraktığı üç alanı kapatır. Ürün bir tüketici uygulaması değil; lisanslanan protokol ve kütüphanedir.

## Hedefler

1. Alıcı o an erişilemezken mesajın ulaşma oranını, aynı depolama bütçesiyle yükseltmek.
2. Büyük içeriği (görsel, harita, ses) kayıplı ve çok röleli ortamda tek tek parça takibi olmadan taşımak.
3. Havadaki kimlikleri döndürerek pasif izlemeyi zorlaştırmak.
4. Her iddiayı tekrar üretilebilir bir ölçümle desteklemek.

## Hedef olmayanlar

- Tüketiciye dönük sohbet uygulaması (Bitchat ile ücretsiz ürün yarışı).
- Yeni kriptografi icat etmek. Oturum şifrelemesi için Noise aynen kullanılır.
- Yoğun kalabalıkta yayın seli. Bitchat'in alt küme + iptal kuralı burada zaten iyi; ölçülmüş bir iyileştirmem yok.

## Kaçınılacak sonuç

"Bitchat'ten daha güvenli" gibi ölçülmemiş bir iddiayla satış yapmak. Bridgefy tam bunu yaptı ve 2020'de açıklarıyla anıldı. Denetimden önce güvenlik iddiası yok.

## Üç algoritma

### 1. Karşılaşma puanlı kurye yönlendirme (`src/sim.js`)

Bitchat mesajı rastgele karşılaştığı kuryelere böler ve sonra bekler. Kairo iki şey ekler:

- **Orantılı bölme:** Kopyalar, alıcıyla daha sık karşılaşan tarafa daha çok verilir.
- **Odaklanma:** Son kopya beklemez; alıcıya belirgin biçimde daha yakın (puan farkı > 0,3) birine *devredilir*, kopyalanmaz.

Puan, klasik PRoPHET kuralıdır: karşılaşmada artar, zamanla (2 saat yarı ömür) söner, ortak tanıdık üzerinden geçişlidir. Bileşenler literatürde bilinir (PRoPHET, Spray-and-Focus, EBR); yeni olan, bunların dönen kimliklerle çalışan bir telefon protokolünde birleştirilmesi ve Bitchat kuralına karşı ölçülmesidir. **Patentlenebilir bir buluş iddiam yok.**

Puan sorgusu alıcının günlük dönen etiketi üzerinden yapılır ve 2 bite yuvarlanır. Bu, kuryeye "bu etiketle sık karşılaşıyorum" bilgisini sızdırır; denetimde ele alınacak açık bir ödünleşimdir.

### 2. Fountain kodlu parçalar (`src/fountain.js`)

Mesaj k parçaya bölünür; ilk k paket düz parçalardır, sonrakiler rastgele XOR birleşimleridir (GF(2) üzerinde rastgele doğrusal kod). Alıcı hangi röleden gelirse gelsin yaklaşık k+2 farklı paketle mesajı kurar. Sonuç: eksik parça takibi, yeniden isteme ve röleler arası koordinasyon gerekmez; kuryeler medya taşıyabilir.

### 3. Dönen hava kimlikleri (`src/ids.js`)

Havadaki 8 baytlık kimlik 15 dakikada bir `HMAC(tanıma anahtarı, dönem)` ile yenilenir. Tanıma anahtarı yalnız eşleşmede (QR) paylaşılır; anahtarı olmayan iki dönemi aynı cihaza bağlayamaz. Statik anahtar açık yayınlanmaz.

Sınır: BLE MAC adresi ve radyo parmak izi bu katmanın dışındadır; işletim sisteminin MAC rastgeleleştirmesine güvenilir.

### 4. Teslim makbuzu (`src/node.js`)

Gönderici zarfın içine rastgele bir sır koyar, dışına bu sırrın özetini yazar. Sırrı yalnız zarfı açabilen alıcı öğrenir ve teslimde yayınlar. Kurye özeti doğrularsa kopyasını siler; makbuz başka kuryelere de aktarılabilir. Böylece "alıcı benim" diyen bir yabancı kuryelere mesaj düşürtemez, yalnız şifreli metni alır.

### Düğümün simülasyondan farkı

Simülatörde her düğüm herkesle karşılaşma puanı tutar ve puanlar tanıdıklar üzerinden yayılır. Gerçek düğümde kimlikler döndüğü için bir cihaz yalnız **kendi kişilerini** tanıyabilir; puan yalnız alıcıyı tanıyan kuryelerde sıfırdan farklıdır ve geçişlilik yoktur. Bu daha gizli ama daha az bilgili bir kuraldır; teslim oranına etkisini ölçmedim. Ölçüm tabloları simülatör kuralına aittir.

Mühürlü zarf Noise X kalıbını izleyen, elle yazılmış ve **denetlenmemiş** bir yapıdır (X25519, HKDF-SHA256, ChaCha20-Poly1305). Saklanan zarflarda ileri gizlilik yoktur; Bitchat'in kurye zarflarıyla aynı sınır.

## Şu an var olan / olmayan

| Parça | Durum |
|---|---|
| Kurye yönlendirme kuralı + simülatör | Var, ölçüldü |
| Fountain kodlayıcı/çözücü | Var, test edildi, gerçek bayt üzerinde çalışır |
| Dönen kimlik | Var, test edildi |
| Çalışan düğüm: çerçeve formatı, mühürlü zarf, kurye devri, teslim makbuzu (`src/node.js`, `src/crypto.js`) | Var, test edildi |
| Yerel ağ üzerinden sohbet (`src/cli.js`, UDP çoklu yayın) | Var, iki süreçle denendi |
| Canlı Noise oturumu, ön anahtarlı ileri gizlilik | Yok (yol haritası) |
| BLE / Wi-Fi Aware taşıması, iOS/Android SDK | Yok (yol haritası) |
| Gerçek telefonlarla saha testi | Yok |
| Bağımsız güvenlik denetimi | Yok |

## Kurucuya bırakılan teknik kararlar (benim tercihlerim)

- Çekirdek dil: şimdilik bağımsız Node.js (bu makinede Rust yok). Mobil SDK için çekirdeğin Rust'a taşınıp Swift/Kotlin bağlamalarıyla sunulması öneririm.
- Lisans: AGPL-3.0 + ticari lisans (çift lisans).
- İkinci taşıma: BLE'den sonra Wi-Fi Aware / Multipeer.
