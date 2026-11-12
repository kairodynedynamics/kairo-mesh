# Doğrulama

Tekrar üretmek için: `npm test` ve `npm run bench` (Node 24, bağımlılık yok). Tüm rakamlar **simülasyondur**; gerçek telefonla saha testi yapılmadı.

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

`npm test`: 8 test, hepsi geçiyor. Fountain kodun 0–40.000 baytlık mesajları kayıp altında birebir geri kurduğu, bozuk paketi reddettiği; kimliğin her dönemde değiştiği ve yalnız anahtarla tanındığı; simülatörün belirlenimci olduğu doğrulanır.

Düğüm testleri: zarfı yalnız alıcının açtığı, göndericinin doğrulandığı ve değiştirilen zarfın reddedildiği; birbirini hiç görmeyen iki kişi arasında kuryenin mesajı taşıdığı, kuryenin içeriği okuyamadığı ve makbuzdan sonra kopyasını sildiği; 20 KB'lık mesajın %30 çerçeve kaybında ulaştığı; sahte ve rastgele çerçevelerin kuryeyi çökertmediği ve mesaj düşürtmediği.

Elle deneme: aynı bilgisayarda iki `src/cli.js` süreci UDP çoklu yayın üzerinden eşleşip mesajlaştı; alıcıda geri çağrı bir kez çalıştı. İki ayrı bilgisayar arasında denenmedi.

## Doğrulanmayanlar

- Gerçek BLE davranışı: bağlantı kurma süresi, arka plan kısıtları, pil tüketimi, bant genişliği sınırı.
- Kötü niyetli kurye (mesaj düşürme, puan şişirme). Puan şişirme bu tasarımda yeni bir saldırı yüzeyidir.
- 7 atlama sınırı ve kutu kapasitesi simülatörde modellenmedi (tüm politikaları eşit etkiler).
- Güvenlik: bağımsız denetim yok.
