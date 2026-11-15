# Triyaj: yardım mesajını yapılandırılmış kayda çevirme

Cihazda çalışan model, aceleyle yazılmış bir yardım mesajını kurumun işleyebileceği bir kayda çevirir ve aciliyete göre önceliklendirir. Kayıt Bluetooth paketine sığacak kadar kısadır (ortalama ~60 bayt).

## Veri

`python uret.py` (bir dakika sürer) şunları üretir:

| Bölüm | Örnek | Ne ölçer |
|---|---|---|
| `veri/egitim.jsonl` | 1.000.000 (%92,5'i tekil) | eğitim |
| `veri/dogrulama.jsonl` | ~9.200 | eğitimdeki girdilerle birebir çakışan elenir |
| `veri/test.jsonl` | 10.000 | eğitimde hiç geçmeyen şablon, mahalle, sokak, bina ve kişi adları |
| `veri/test_agir_gurultu.jsonl` | 5.000 | aynı, en ağır yazım bozukluğuyla |

Telefon yazımı gürültüsü: küçük/büyük harf, aksansız yazım, Türkçe Q klavyede komşu tuş, harf atlama, yer değiştirme, çift basma, sesli harf düşürme, kısaltmalar (mh, sk, apt, kş, yrdm), boşluk yutma ve çift boşluk, harf uzatma (acilll), yazıyla sayı, etiketler ve emojiler.

Etiket kuralı: `il` ve `ilce` kapalı sözlükten kanonik yazımla (bozuk yazılmış olsa da düzeltilebilir); `mahalle`, `sokak`, `bina` ve `ad` mesajda nasıl geçiyorsa öyle (açık uçlu adların doğru yazımı mesajdan çıkarılamaz). Öz-denetim her örnekte etiketin mesajdan geri bulunabildiğini doğrular.

**Sınır:** Veri tamamen sentetiktir. Gerçek mesajların çeşitliliğini taşımaz. Gerçek mesajlardan elle etiketlenmiş bir test kümesi kurulmadan modelin sahadaki başarısı bilinemez.

## Ölçüm

`python degerlendir.py veri/test.jsonl tahmin.jsonl`. Asıl ölçüt `acil_kacirma`: aciliyeti 1–2 olan mesajın 3–4 sanılma oranı. Bu hata bir hayata mal olabilir; tersi yalnız fazladan iş çıkarır.

Kural tabanlı taban çizgisi (`--kural`), modelin geçmesi gereken çıta:

| | tür | aciliyet | acil kaçırma | kişi | il | ilçe | mahalle | tam eşleşme |
|---|---|---|---|---|---|---|---|---|
| test | %80,5 | %82,8 | %5,4 | %91,0 | %94,8 | %67,2 | %23,2 | %10,1 |
| ağır gürültü | %76,4 | %78,9 | %9,7 | %88,2 | %90,3 | %60,2 | %24,9 | %10,9 |

## Model planı

1. **Taban:** Kairo v3 ön eğitimi (0,33 milyar parametre, MoE). Telefonda 4-bit ile ~170 MB, 8-bit ile ~340 MB.
2. **Genel SFT:** Kairo'nun planlanan talimat eğitimi. Triyajdan bağımsız olarak zaten yapılacak; modelin görmediği ifadeleri anlama gücünü artırması bekleniyor.
3. **Triyaj ince ayarı:** bu veriyle, çıktı JSON.
4. **Karşılaştırma:** genel SFT atlanıp doğrudan tabandan ince ayar da yapılacak. Genel SFT ölçülebilir kazanç getirmiyorsa daha basit yol seçilir.

Kural tabanlı çözüm telefonda yedek olarak kalır: model çalışmazsa ya da bellek yetmezse ham metin ve kural çıktısı yine gönderilir.

GPU v3 ön eğitimindeyken (bitiş ~24 Kasım 2026) ince ayar yapılmaz.
