# 09 — Sahte ihbar: değiştirilmiş uygulamayla ne yapılabilir, kurum bunu nasıl anlar?

Bu belge tek bir soruya cevap verir: **Bir saldırgan KairoMesh'in açık kaynak kodunu değiştirip kendi telefonundan yanlış aciliyetle, yalan konum ya da yalan bilgiyle çağrı gönderirse ne olur? Çağrıyı ve makbuzu alan taraf bunu nasıl anlar?**

Kısa cevap şöyle. Yalan içerikli çağrı göndermek mümkündür; resmî uygulamayla bile mümkündür, tıpkı 112'yi asılsız aramak gibi. Kriptografi bir çağrının **kimden geldiğini, değiştirilmediğini ve kuruma ulaştığını** kanıtlar. **Doğru olduğunu** kanıtlayamaz. Bu yüzden sistem dört şey yapar:
1. Saldırganın başkalarına zarar verme yollarını kapatır.
2. Sahte çağrı üretmeyi pahalılaştırır.
3. Sahte ya da kötüye kullanım belirtilerini kurum panosunda görevlinin önüne koyar.
4. Hiçbir çağrıyı gizlemez; karar görevlinindir.

Her iddianın kodu ve testi belirtilmiştir.

## 1. Saldırganın elinde ne var?

Saldırgan kodu değiştirip kendi telefonuna kurar. Kendi telefonunda istediği her şeyi yazabilir:

| Saldırganın yapabildiği | Neden engellenemez |
|---|---|
| Durum türünü, aciliyeti, kişi sayısını, özel durumu, metni yalan yazmak | Bu bilgiler gönderenin beyanıdır; uzaktan doğrulanabilecek bir gerçeklik kaynağı yoktur. |
| Sahte GPS konumu göndermek | Telefon konumu kendi ölçer; sahte konum üretmek bir ayar kadar kolaydır. |
| Kısa sürede çok sayıda çağrı göndermek | Yeni anahtar üretmek bedavadır; her çağrı yalnız küçük bir iş kanıtı ister. |
| Resmî uygulamadaki kuralları atlamak | Örneğin "Güvendeyim" çağrısını "hayati" (1) aciliyetle göndermek. Resmî uygulama bunu yapamaz, değiştirilmiş uygulama yapabilir. |

## 2. Saldırganın yapamadıkları

| Saldırı | Sonuç | Neden | Kanıt |
|---|---|---|---|
| Başka birinin çağrısını okumak | Okuyamaz | Çağrı kurumun anahtarına Noise X ile mühürlenir; taşıyan telefonlar yalnız şifreli bayt görür. | `src/crypto.js`, `test/noise-x-vector.json`, `test/attacks.test.js` |
| Başka birinin çağrısını değiştirmek | Değiştiremez; değişen zarf reddedilir | Şifreli zarf bütünlük etiketi taşır; tek bit değişse kurum açamaz. | `test/acil.test.js` ("tampered or foreign envelopes are refused") |
| Başka bir telefon adına çağrı göndermek | Gönderemez | Noise X gönderenin kalıcı anahtarını doğrular. Kurum her çağrıda gerçek gönderen anahtarını görür. | `src/kurum.js` (`sender`) |
| Gerçek bir çağrıyı daha az acil göstermek | Gösteremez | Çağrının içeriğini yalnız gönderen belirler. Telefondaki model bile çağrıyı yalnız daha acil yapabilir. | `MeshService.kt`, `Mesh.swift`, `docs/05` |
| Sahte "kurum aldı" makbuzu üretmek | Üretemez | Makbuz, çağrının içine gizlenmiş ve yalnız kurumun açabildiği bir sırdır. Telefon makbuzu çağrıdaki özetle karşılaştırır. | `src/node.js` (`settle`) |
| Sahte bir "AFAD kodu" yayıp çağrıları kendine çekmek | Çekemez | Kurum kodu uygulamaya gömülü kök anahtarla imzalı olmalıdır; onaysız kod reddedilir ve kaydedilmez. | `docs/07`, `test/kurum-kodu-ornek.json` (JS, Kotlin, Swift) |
| Sahte imzalı duyuru göndermek ("toplanma alanı şurası") | Gönderemez | Duyurular kurumun Ed25519 anahtarıyla imzalıdır; telefon imzayı doğrular. | `test/acil.test.js` (duyuru testleri) |
| Kurum sunucusunu bozuk bir raporla çökertmek | Çökertemez | Rapor alanları beyaz listeyle temizlenir; hiçbir istek sunucuyu durduramaz. | `test/acil.test.js` ("malformed report cannot crash") |

Not: Ed25519 imzası Android 14'ün kendi kripto sağlayıcısında bulunmuyor. Bu yüzden kurum kodu ve duyuru imzaları, sağlayıcı desteklemiyorsa bağımlılıksız bir RFC 8032 doğrulayıcısıyla kontrol edilir (`core/Ed25519.kt`, RFC 8032 test vektörleriyle sınanır). Bu düzeltmeden önce imzalı her şey gerçek Android 14 telefonlarında reddediliyordu.

## 3. Makbuzu alan taraf ne anlar?

Bir çağrının yolunda üç taraf vardır. Hiçbiri tek başına "bu çağrı doğru" diyemez. Her biri ise farklı bir şeyi kesin bilir:

| Taraf | Kesin bildiği | Bilemediği |
|---|---|---|
| **Gönderenin telefonu** (makbuzu geri alan) | Çağrısının değiştirilmeden **kuruma ulaştığını**. Makbuz, kurumun çağrıyı açtığının kanıtıdır. | Kurumun çağrıyı doğru bulup bulmadığını. Makbuz "aldık" demektir, "inandık" demek değildir. |
| **Taşıyan telefonlar** (makbuzu da taşırlar) | Makbuzun gerçek olduğunu; kopyalarını ancak o zaman silerler. | Çağrının içeriğini. Okuyamazlar, bu yüzden sahte olup olmadığını da bilemezler. |
| **Kurum** (makbuzu üreten) | Çağrının hangi anahtardan geldiğini, ne zaman mühürlendiğini, içeriğini, iş kanıtını. | İçeriğin gerçek olup olmadığını. Bunun için aşağıdaki **uyarı işaretlerine** bakar. |

### Kurum panosundaki uyarı işaretleri (çalışıyor)

Her çağrı panoda bir "Dikkat" sütunuyla gelir (`src/kurum.js`, `sinyaller`). İşaretler hiçbir çağrıyı silmez, gizlemez ya da aşağı atmaz. Tek istisna: gönderenin kendisinin geri çektiği çağrı, kendi aciliyet düzeyinin en altına iner.

| İşaret | Ne zaman çıkar | Ne anlama gelir |
|---|---|---|
| **Kurala aykırı aciliyet** | Dokunarak gönderilmiş bir çağrının aciliyeti, durum tablosunun verdiğiyle tutmuyor. Örneğin "Güvendeyim" + aciliyet 1. | Resmî uygulama bunu üretemez. Çağrı büyük olasılıkla **değiştirilmiş bir uygulamadan** geliyor. Bu işaret kesindir, tahmin değildir. |
| **Bilinmeyen durum türü** | Uygulamada olmayan bir tür ("bomba", "x"). | Değiştirilmiş uygulama ya da bozuk gönderim. |
| **Aynı cihazdan çok çağrı** | Aynı anahtardan bir saat içinde 4 ve daha fazla çağrı. | Sel saldırısı ya da panikle tekrar basma; görevli tek kayıt gibi değerlendirebilir. |
| **Aynı metin birçok cihazdan** | Aynı metin yarım saat içinde 3 ve daha fazla farklı cihazdan. | Toplu sahte çağrı, ya da gerçek bir mesajın elden ele yayılması. İkisinde de tek olay. |
| **Gönderen geri çekti** | Aynı cihaz sonradan "yanlışlıkla gönderdim / yardım gerekmiyor" gönderdi. | Yanlış dokunuş ya da kurtarılma. Çağrı listede kalır. |
| **Konum yok / belirsiz** | Konum hiç yok ya da doğruluğu 1000 metreden kötü. | Enkaz altında olağandır; tek başına sahtelik işareti değildir, adres metnine bakılır. |

Bu işaretler `test/acil.test.js` içinde sınanır ("warning signs mark forged urgency…"):
- Sahte "Güvendeyim + 1" çağrısı **kural dışı** işaretlenir.
- Dört kez gönderen cihazın çağrılarının hepsi **çok çağrı** işaretlenir.
- Üç farklı cihazdan gelen aynı metin **kopya metin** işaretlenir.
- Geri çekilen çağrı işaretlenir ve kendi düzeyinde en alta iner.
- Hiçbir çağrı listeden düşmez.

## 4. Senaryolar: ne olur?

| Senaryo | Ne olur | Kurum ne görür | Sonuç |
|---|---|---|---|
| Biri resmî uygulamadan şaka çağrısı yapar ("Enkaz altındayım", yanlış konum) | Çağrı ulaşır, makbuz döner | Normal görünen bir çağrı. İşaret çıkmaz; resmî uygulamadan gelmiştir. | 112'yi asılsız aramakla aynıdır. Gönderenin anahtarı kayıtlıdır; hukuki yaptırım için kurumda kalır. |
| Değiştirilmiş uygulamadan "Güvendeyim" aciliyet 1 | Çağrı ulaşır | **Kurala aykırı aciliyet** | Görevli değiştirilmiş uygulama olduğunu görür. |
| Bir kişi 200 sahte anahtarla aynı metni gönderir | Her çağrı 18 bitlik iş kanıtı ister: telefonda ve bu projenin Node.js kodunda zarf başına yaklaşık 0,4 saniye (çekirdek başına saatte ~8.700 zarf). Yerel kodla ya da ekran kartıyla çalışan bir saldırgan bunun onlarca, yüzlerce katını üretebilir. İş kanıtı seli durdurmaz, yalnız yavaşlatır. | **Kopya metin** (200 cihaz) | Görevli bunu tek olay olarak görür. Gerçek çağrılar listede kalır. |
| Aynı sel, ağı doldurup gerçek çağrıyı düşürmeye çalışır | Her telefon başkalarının en çok 24 acil zarfını taşır; kota dolunca en eski zarf atılır. | — | **Açık risk** (bkz. 5). İş kanıtı henüz güne bağlı değil; afetten önce üretilmiş çöp zarflar kullanılabilir. |
| Kullanıcı yanlışlıkla dokunur | 5 saniyelik geri sayımda İPTAL'e basar; hiçbir şey gönderilmez. Gönderdiyse "yanlışlıkla gönderdim" der. | Hiçbir şey ya da **gönderen geri çekti** | Kurumun yükü azalır. |
| Sahte "AFAD kodu" yayılır | Telefon onaysız kodu reddeder ve kaydetmez. | — | Çağrılar saldırgana gitmez. |

## 5. Açık riskler ve planlanan önlemler

Bunlar yapılmadı. Dürüstçe listeliyoruz:

1. **Cihaz doğrulaması (Play Integrity / App Attest).** Telefon internete bağlıyken anahtarının resmî, değiştirilmemiş uygulamaya ait olduğuna dair bir belge alır; çağrı bu belgeyi taşır. Panoda "doğrulanmış uygulama" işareti çıkar. Bu, "kurala aykırı" işaretinin göremediği, kuralı taklit eden değiştirilmiş uygulamaları da ayırır. Google ve Apple geliştirici hesabı gerekir.
2. **İş kanıtını güne ve kuruma bağlamak.** Afetten önce çöp zarf biriktirilemesin. Üç platformda protokol değişikliği gerekir (güvenlik incelemesi bulgusu).
3. ~~**Kalıcı ret.**~~ **Yapıldı:** kurum bir zarfı kalıcı olarak reddederse (422) ağ geçidi telefon onu bırakır, bir gün boyunca yeniden yüklemez (üç platform; `test/acil.test.js`).
4. **Kota boşaltma kuralı.** Kota dolunca en eskiyi değil, en çok kopyası olanı ya da iş kanıtı en zayıf olanı atmak.
5. ~~**Saklama süresi** (KVKK).~~ **Yapıldı:** kurum sunucusu çağrıları 30 gün sonra hem bellekten hem kayıt dosyasından siler (`KURUM_SAKLAMA_GUN`). Pano 10.000 çağrıda bir saniyenin altında hazırlanır (`test/acil.test.js`). IP başına oran sınırı **bilerek konmadı**: bir ağ geçidi telefon onlarca kişinin çağrısını tek IP'den yükler, operatörler de çok kullanıcıyı tek IP'nin arkasında toplar (CGNAT). Böyle bir sınır gerçek çağrıları düşürür.
6. ~~**Saati yanlış telefon.**~~ **Yapıldı:** çağrıdaki saat bir günden eski ya da bir saatten ileriyse kurum çağrıyı artık reddetmez. Pili bitip saati sıfırlanan bir telefonun gerçek çağrısı da böyle gelir. Panoda **saat şüpheli** işaretiyle, kuruma ulaştığı ana göre sıralanır. **Bedeli:** eskiden kurum bir günden eski zarfı reddettiği için önceden biriktirilmiş çöp zarfların ömrü bir gündü. Artık bu zarflar da kabul edilir; yalnız aynı işareti alırlar. Bu yüzden iş kanıtını güne bağlamak (madde 2) daha da önemli hâle geldi. Saklama süresi dolup silinen bir çağrının zarfı (onu taşımış bir telefondan) yeniden gelirse kurum onu kalıcı olarak reddeder: silinen çağrının yalnız zarf özeti (SHA-256, kişisel veri değil) tutulur.
7. **Hukuk.** Asılsız ihbarın yaptırımı ve göndericinin anahtarının bu amaçla kullanılabilirliği avukatla netleştirilmeli (`internal/kosgeb/04-hukuk-sorulari.md`).

## 6. Neden kaynak kodu açık olduğu hâlde güvenli?

Saldırganın değiştirebileceği tek şey **kendi telefonudur**. Başkalarının çağrısını okuma, değiştirme ya da düşürme, sahte makbuz, sahte kurum, sahte duyuru üretme gibi gerçek zararlar koda değil **anahtarlara** dayanır. Anahtarlar da kodda değildir:
- Kurumun özel anahtarı kurumda kalır.
- Kök anahtar çevrimdışı tutulur.

Kapalı kod bu saldırıları zorlaştırmaz; yalnızca denetlenmelerini zorlaştırır.
