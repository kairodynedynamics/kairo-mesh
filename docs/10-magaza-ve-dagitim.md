# 10 — Mağaza ve dağıtım: Play Console ve App Store Connect'e girilecekler

Bu belge, mağaza formlarına girilecek yanıtları ve gerekçe metinlerini tek yerde toplar. Yanıtlar uygulamanın 0.6.0 sürümündeki koda göre yazılmıştır. Kod değişirse bu belge de güncellenmelidir. Gizlilik politikası: https://kairodynedynamics.com/gizlilik

## 1. Derlemeler

| Derleme | Komut | Kurum | Kimin için |
|---|---|---|---|
| Android **demo** | `cd android && ./gradlew assembleDemo` | Gömülü operatör demosu (kairodynedynamics.com/operator) | Tanıtım, pilot görüşmeleri, el ile dağıtılan APK |
| Android **release** | `./gradlew bundleRelease` (AAB) | Yok; gerçek kurumun kök onaylı kodu sözleşmeden sonra gömülür | Play Store |
| Android debug | `./gradlew assembleDebug` | Demo + test otomasyonu | Yalnız geliştirme ve emülatör testleri; **dağıtılmaz** |
| iOS | CI: `KairoMesh-ios-ipa-imzasiz` | Debug yapılandırmasında demo kurum | AltStore/Sideloadly ile tanıtım; App Store için Release yapılandırması ve Apple Developer hesabı gerekir |

Android imza anahtarı `internal/anahtarlar/android/` altında durur (git'e girmez). Play App Signing açılınca bu anahtar **yükleme anahtarı** olur. Kaybolursa Play Console'dan sıfırlanabilir. Kaybolursa demo APK'yı güncellemek için kullanıcıların uygulamayı silip yeniden kurması gerekir.

## 2. Google Play

**Kategori:** İletişim. **İçerik derecelendirmesi:** Herkes. Uygulamada kullanıcıdan kullanıcıya serbest iletişim yoktur; çağrılar yalnız kuruma gider.

### Veri güvenliği formu

| Soru | Yanıt |
|---|---|
| Veri topluyor ya da paylaşıyor mu? | Evet, topluyor. Üçüncü taraflarla paylaşılmıyor; çağrı, kullanıcının çağrısını gönderdiği kuruma gider. |
| Aktarımda şifreleniyor mu? | Evet: Noise X (X25519, ChaCha20-Poly1305), ayrıca HTTPS. |
| Kullanıcı verilerin silinmesini isteyebilir mi? | Evet, kuruma başvurarak (gizlilik sayfası). Telefondaki veriler uygulama silinince silinir. |
| Konum → Kesin konum | Toplanıyor. Amaç: uygulama işlevi. Zorunlu değil (izin verilmezse çağrı konumsuz gider). |
| Kişisel bilgiler → Diğer | Hayır (ad, telefon, e-posta toplanmaz). |
| Mesajlar → Diğer uygulama içi mesajlar | Toplanıyor: çağrıya yazılan metin. Amaç: uygulama işlevi. |
| Uygulama bilgileri ve performans → Diğer | Toplanıyor: cihaz modeli, sistem ve uygulama sürümü, pil düzeyi. Amaç: uygulama işlevi. |
| Cihaz ya da diğer kimlikler | Toplanıyor: uygulamanın ürettiği açık anahtar. Amaç: uygulama işlevi. |

### Ön plan hizmeti beyanı (Android 14+)

- **connectedDevice:** "KairoMesh, internet ve şebeke yokken yardım çağrılarını yakındaki telefonlar arasında Bluetooth LE ile taşır. Telefonun ekranı kapalıyken de başkalarının şifreli çağrılarını taşıyabilmesi ve internete ulaştığında kuruma iletebilmesi için Bluetooth bağlantısı sürekli açık kalmalıdır. Kullanıcı bildirim alanında hizmeti görür."
- **location:** "Yalnızca kullanıcı bir yardım çağrısı gönderdikten sonraki 5 dakika boyunca, daha doğru bir GPS konumu bulunursa kuruma ek olarak gönderilir. Bu arama, kullanıcı ekranı kilitlese de sürmelidir; enkaz altındaki birinin yeri buna bağlıdır. Hizmet uygulama ekrandayken başlar ve 5 dakika sonra kendiliğinden kapanır."
- **Video:** Çağrı gönderme, bildirim alanındaki "KairoMesh" hizmeti ve ekran kilitliyken operatör panosuna gelen "Güncel konum". 30–60 saniye yeterlidir.

### İzinler

- **Yakındaki cihazlar** (`BLUETOOTH_SCAN` `neverForLocation`, `CONNECT`, `ADVERTISE`): telefonlar arası taşıma.
- **Konum:** yalnız "uygulama kullanılırken". Arka plan konum izni **istenmez**.
- **Bildirim:** "Kurum çağrınızı aldı" ve kurum duyuruları.
- **Tam zamanlı alarm istenmez.** Uyandırma alarmı esnektir (`setAndAllowWhileIdle`).
- **Pil iyileştirmesi muafiyeti istenmez.** Bilinen sınır: bazı Samsung ve Xiaomi ayarları uygulamayı "uyuyan uygulama" yapabilir. Saha tatbikatında ölçülmeli.

### Mağaza metni

- **Kısa açıklama (80 karakter):** Şebeke çöktüğünde yardım çağrınız telefondan telefona kuruma ulaşır.
- **Uzun açıklama:** "KairoMesh, deprem gibi afetlerde internet ve baz istasyonu çalışmazken yardım çağrınızı Bluetooth ile yakındaki telefonlara verir. Çağrı, internete ulaşan ilk telefon üzerinden kuruma iletilir. Çağrınız kurumun anahtarıyla şifrelenir; taşıyan telefonlar içeriğini okuyamaz. Kurum aldığında telefonunuz bunu size gösterir. Dört büyük düğme, 5 saniyelik iptal, sesli sinyal; yazmak isterseniz telefon Türkçe mesajınızı internet olmadan anlar. Hesap yok, reklam yok, izleme yok."
- **Ekran görüntüleri:** `site/statik/uygulama/*.png` (beş adet). 0.6.0 ekranıyla yenilenmeli.

## 3. App Store

- **Gizlilik bildirimi (PrivacyInfo.xcprivacy):** uygulamada var. App Store Connect'teki "App Privacy" yanıtları bu bildirimle aynı olmalıdır: kesin konum, diğer kullanıcı içeriği, kullanıcı kimliği ve diğer tanılama verileri. Hepsi kullanıcıya bağlı, izleme yok, amaç uygulama işlevi.
- **Şifreleme beyanı:** `ITSAppUsesNonExemptEncryption = YES`. Standart algoritmalar kullanılıyor (X25519, ChaCha20-Poly1305, SHA-256, Ed25519). App Store Connect'teki ihracat soruları buna göre yanıtlanır. Fransa dağıtımı için ayrıca beyan gerekebilir; avukata sorulmalı.
- **App Review notu:** "Arka plan modları: bluetooth-central ve bluetooth-peripheral, şebekesiz ortamda yardım çağrılarını telefonlar arasında taşımak için; location, kullanıcı çağrı gönderdikten sonraki 5 dakika boyunca daha iyi konum aramak için (mavi gösterge görünür, sonra kapanır); audio, kullanıcının açtığı ve kurtarıcıların onu bulması için çalan sesli sinyal için. Test: uygulama sözleşme öncesi tanıtım sürümünde çağrıları bir demo operatör panosuna gönderir; gerçek acil servise gitmez."
- **Yaş derecelendirmesi:** 4+.

## 4. Sahibinin yapacakları

1. **Play Console** hesabı (25 $). Paket adı `com.kairodyne.mesh`. Play App Signing açılmalı, yükleme anahtarı olarak `internal/anahtarlar/android/` verilmeli.
2. **Apple Developer Program** (99 $/yıl). Bundle ID `com.kairodyne.KairoMesh`; imzalama ekibi `project.yml`'ye yazılmalı.
3. **Kurumsal e-posta adresi**: gizlilik sayfasına ve iki mağazaya iletişim adresi olarak.
4. **Kurum kodu**: AFAD, 112 ya da pilot belediyeyle sözleşme. Kurumun kök onaylı kodu release derlemesine gömülür (`docs/07`).
5. **Avukat**: KVKK aydınlatma metninin son hâli, asılsız ihbar sorumluluğu, şifreleme ihracat beyanı.
6. **Gerçek cihaz tatbikatı**: en az iki Android (biri Samsung ya da Xiaomi) ve bir iPhone. Ekran kilitliyken taşıma, yeniden başlatma sonrası taşıma, Android–iPhone arasında arka planda keşif (aşağıdaki sınıra bakın).

## 5. Bilinen sınırlar (dağıtımdan önce bilinmeli)

- **Arka plandaki iPhone'u Android göremez.** iOS, arka plandaki uygulamanın Bluetooth duyurusunu "taşma" alanına koyar ve Android'in hizmet kimliğine göre yaptığı tarama bunu bulmaz. Ekranı açık iPhone'u Android bulur; arka plandaki iki iPhone ise birbirini ancak yavaş bulur. Gerçek cihazda ölçülmeli.
- **Android 12 ve altı desteklenmiyor** (minSdk 33). Bunun nedeni, X25519 için platformun kripto sağlayıcısına dayanılması. Saf Kotlin bir X25519 ile minSdk 26'ya inilebilir.
- **iOS'ta iş kanıtı ve model** gerçek cihazda ölçülmedi.
