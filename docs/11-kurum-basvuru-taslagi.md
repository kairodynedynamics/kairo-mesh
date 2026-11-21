# 11 — Kurum başvuru taslağı

Pilot kurum (AFAD il müdürlüğü, 112 merkezi veya belediye afet birimi) ile ilk görüşme için hazırlanan taslak. Teknik kurulum: `docs/07`.

## Kısa tanıtım (e-posta veya yüz yüze)

> Sayın yetkili,
>
> Kairodyne Dynamics olarak, afetlerde baz istasyonları ve internet çöktüğünde vatandaşların
> yardım çağrılarını **telefondan telefona Bluetooth ile taşıyan** açık kaynaklı bir uygulama
> geliştirdik: **KairoMesh**.
>
> Çağrı, kurumunuzun anahtarıyla uçtan uca şifrelenir; taşıyan telefonlar içeriği okuyamaz.
> İnternete ulaşan ilk telefon çağrıyı kurumunuzun sunucusuna iletir. Pano üzerinden konum,
> kişi sayısı ve durum bilgisini görebilirsiniz.
>
> Teknik altyapı hazırdır (Android + iOS, 116 otomatik test, bağımsız güvenlik simülasyonu).
> Mağaza yayınından önce bir kurumla pilot anlaşma gereklidir; uygulamaya gömülecek kurum
> kodunu ancak yetkili bir makamın kök anahtarı onaylayabilir.
>
> Size bir demo göstermek ve pilot koşullarını konuşmak isteriz. Tanıtım sürümünü canlı olarak
> kairodynedynamics.com/operator adresinden deneyebilirsiniz.

## Sıkça sorulan sorular (görüşmede hazır bulunması gereken)

| Soru | Yanıt |
|---|---|
| Çağrıyı kim görür? | Yalnız kurum: uçtan uca şifreleme (Noise X, X25519, ChaCha20-Poly1305). |
| Asılsız çağrı? | 112'yi asılsız aramakla aynı; gönderenin anahtarı kayıtlıdır (docs/09). |
| Altyapı ne gerektirir? | Bir sunucu (VM yeterli), Node.js, TLS sertifikası. Kurulum: docs/07. |
| Maliyet? | Uygulama açık kaynak ve ücretsiz. Sunucu maliyeti kurumun; yıllık ~100–500 TL (küçük VM). |
| KVKK? | Gizlilik metni hazır (kairodynedynamics.com/gizlilik). Veri sorumlusu kurumdur. |
| Mağaza yayını? | Kurum kodu gömülünce Play Store ve App Store'a başvurulur (docs/10). |
| Bluetooth menzili? | ~30–80 m açık alanda; telefonlar birbirine aktararak mesafe uzar. |
| Pil tüketimi? | BLE düşük güç; ölçüm: 8 saatte ~%3–5 (cihaza göre değişir). |

## Pilot için minimum gereksinim

1. Kurumun bir sunucu tahsis etmesi (veya Kairodyne'ın işletmesine izin vermesi)
2. Kök anahtarın yetkili makamca üretilmesi veya Kairodyne'a yetki verilmesi
3. En az bir masaüstü tatbikatı (3+ telefon, biri internet bağlantısız)
4. Geri bildirim süreci (pilot süresince)
