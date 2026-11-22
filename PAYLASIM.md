# Ne paylaşılır, ne paylaşılmaz

Bu depo (github.com/kairodynedynamics/kairo-mesh) **herkese açıktır**. Buraya giren her şey kopyalanabilir, arşivlenir
ve silinse bile geri getirilemez. Kural basit: **telefonun çalıştırdığı kod ve bir çağrının nasıl korunduğunu
denetlemek için gereken her şey açık; anahtarlar, kişisel veri, iş belgeleri ve kurumsal ürün kapalı.** Güvenlik
gizli koda değil anahtarlara dayanır.

Kurallar `.githooks/pre-commit` ile her commit'te, CI'da (`sh .githooks/pre-commit --tum`) her push'ta denetlenir.

## Klasör düzeni

Her şey `D:\Kairodyne-Dynamics` altındadır. Dört ayrı git deposu vardır:

| Klasör | Ne | Depo | Görünürlük |
|---|---|---|---|
| `src/` `test/` `sim/` | JS çekirdek, kurum sunucusu, testler, deprem simülasyonu | kairo-mesh | **açık** |
| `android/` `ios/` | Uygulamalar ve Kotlin/Swift çekirdekleri | kairo-mesh | **açık** |
| `triyaj/` | Sentetik veri üreteci, model, ölçümler, telefon ağırlıkları | kairo-mesh | **açık** |
| `site/` | kairodynedynamics.com kaynakları | kairo-mesh | **açık** |
| `docs/` `.github/` `.githooks/` | Belgeler, CI, koruma kancaları | kairo-mesh | **açık** |
| `internal/` | İş planı, KOSGEB, sözleşmeler, sunum, **operatör demosu** (`internal/operator/baslat.sh`), **anahtarlar** (`internal/anahtarlar/`, o depoda da git dışı) | kairo-business | özel |
| `kurumsal/` | Kurumsal ürün (gelişmiş pano, 112/AFAD bağdaştırıcıları) | kairo-kurumsal | özel |
| `yayin/kairodynedynamics.com/` | Sitenin yayımlanan derlemesi (Netlify bu depodan yayımlar) | kairodynedynamics.com | özel |
| `dagitim/` | Dağıtılan APK'lar | yok | yalnız bu bilgisayar |
| `medya/` | Görseller, güvenlik çalışması, tanıtım malzemesi | yok | yalnız bu bilgisayar |
| `yerel/` | Makineye özel ayarlar ve çalışma dosyaları (cloudflared, operatör günlükleri, HF yolu) | yok | yalnız bu bilgisayar |

## Herkese açık depoya GİREBİLİR

- Protokol ve şifreleme çekirdekleri (JS, Kotlin, Swift), Android ve iOS uygulama kaynakları.
- Referans kurum sunucusu, panosu ve kurulum aracı (`src/kurum*.js`). Anahtarları değil, **anahtar üreten** kodu içerir.
- Testler, test vektörleri ve **yalnız test** kökü `test/kok-test/kok-anahtar.json`. Bu anahtar bilerek açıktır.
  Onu yalnız hata ayıklama ve demo sürümleri kabul eder. Mağaza sürümleri yalnız üretim kökünü kabul eder.
- Demo kurumunun **açık** kodu (`android/app/build.gradle.kts` içindeki `DEMO_KURUM`). Açık anahtar ve imzadır; gizli değildir.
- Sentetik triyaj verisi üreteci, model ağırlıkları, ölçümler.
- Site kaynakları ve ölçüm rakamları, belgeler.

## Herkese açık depoya ASLA GİRMEZ

| Ne | Nerede durur |
|---|---|
| Üretim kök anahtarı, kurum anahtarları (`kokGizli`, `muhurGizli`, `imzaGizli`) | `internal/anahtarlar/` (git dışı); üretim kökü çevrimdışı yedekte |
| Android imza anahtarı ve parolaları (`*.jks`, `imza.properties`) | `internal/anahtarlar/android/` |
| Operatör panosu anahtarı (`pano-anahtari.txt`), gelen çağrılar (`cagrilar.jsonl`) | `internal/anahtarlar/operator-demo/` |
| API anahtarları ve belirteçler (GitHub, Hugging Face, bulut, yapay zekâ servisleri) | `.env` dosyaları (git dışı) ya da sistemin kimlik deposu (`gh auth`) |
| Canlı tünel adresi (`*.trycloudflare.com`, `site/operator-adres.txt`) | `site/operator-adres.txt` (git dışı) |
| Gerçek yardım mesajları, telefon numaraları, konumlar, kişi adları (KVKK) | hiçbir depoda |
| İş planı, bütçe, sözleşmeler, hukuk görüşleri, KOSGEB dosyaları, sunumlar | `internal/` |
| Kurumsal ürün kodu | `kurumsal/` |
| Kişisel e-posta adresleri, ev dizini yolları, makine adları | hiçbir yerde; commit'ler GitHub no-reply adresiyle atılır |
| APK/IPA dosyaları | `dagitim/`; dağıtım GitHub Releases ya da doğrudan gönderimle yapılır |

## Commit kuralları

- Yazar adresi `…@users.noreply.github.com`. Kanca başka bir adresle commit'i reddeder.
- Commit mesajlarında araç ya da asistan atfı yoktur (`Co-Authored-By: …`, `Claude-Session`, `Generated with …`).
  `.githooks/commit-msg` bunları reddeder. Kanca dört depoda da etkindir.
- 25 MB'tan büyük dosya kancada durur. Model ağırlığı gibi bilerek eklenenler gözden geçirilip `--no-verify` ile eklenir.
- Kanca bir şeyi durdurduysa dosyayı `--no-verify` ile zorlamayın. Gizli bilgiyi kaldırın ya da doğru klasöre taşıyın.

## Kurulum (yeni bir bilgisayarda)

```sh
git config core.hooksPath .githooks                                  # bu depo
git -C internal config core.hooksPath "$(pwd)/.githooks"             # özel depolar: atıf kuralı
git -C kurumsal config core.hooksPath "$(pwd)/.githooks"
git -C yayin/kairodynedynamics.com config core.hooksPath "$(pwd)/.githooks"
git config user.email "<id>+kairodynedynamics@users.noreply.github.com"
```

## Bir sızıntı olursa

1. Anahtarı **hemen döndürün** (yenisini üretin, eskisini geçersiz kılın). Geçmişten silmek yetmez: kopyalanmış sayılır.
2. Kurum anahtarıysa yeni kurum kodunu kökle onaylayıp uygulamaları güncelleyin. Kök anahtarıysa yeni kökle yeni sürüm çıkarın.
3. Sonra geçmişi temizleyin (`git filter-repo`) ve GitHub destekten önbelleklerin silinmesini isteyin.
