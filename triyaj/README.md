# Triyaj: yardım mesajını yapılandırılmış kayda çevirme

Cihazda çalışan model, aceleyle yazılmış bir yardım mesajını kurumun işleyebileceği bir kayda çevirir ve aciliyete göre önceliklendirir. Kayıt Bluetooth paketine sığacak kadar kısadır (ortalama ~60 bayt).

## Veri

`python uret.py` (bir dakika sürer) şunları üretir:

| Bölüm | Örnek | Ne ölçer |
|---|---|---|
| `veri/egitim.jsonl` | 1.000.000 | eğitim |
| `veri/dogrulama.jsonl` | ~10.000 | eğitim ifadeleriyle; eğitimdeki girdilerle birebir çakışan elenir |
| `veri/test.jsonl` | 10.000 | eğitimde hiç geçmeyen olay ifadeleri, mahalle, sokak, bina ve kişi adları |
| `veri/test_agir_gurultu.jsonl` | 5.000 | aynı, en ağır yazım bozukluğuyla |
| `veri/ayar.jsonl` (`--ayar`) | 5.000 | görülmemiş ifadeler, testten ayrı örnekler; yalnız aciliyet eşiği seçimi |

Mesajların %70'i bileşenlidir: her türün ifade havuzundan bir olay ifadesi, konum, kişi sayısı, özel durum ve çağrı ekleriyle rastgele sırada birleşir. Kalanı sabit şablonlardan gelir. `|T` ile biten ifadeler yalnız testte kullanılır. İlk sürüm yalnız şablon kullanıyordu ve model şablonları ezberledi.

Telefon yazımı gürültüsü: küçük/büyük harf, aksansız yazım, Türkçe Q klavyede komşu tuş, harf atlama, yer değiştirme, çift basma, sesli harf düşürme, kısaltmalar (mh, sk, apt, kş, yrdm), boşluk yutma ve çift boşluk, harf uzatma (acilll), yazıyla sayı, etiketler ve emojiler.

Etiket kuralı: `il` ve `ilce` kapalı sözlükten kanonik yazımla (bozuk yazılmış olsa da düzeltilebilir); `mahalle`, `sokak`, `bina` ve `ad` mesajda nasıl geçiyorsa öyle (açık uçlu adların doğru yazımı mesajdan çıkarılamaz). Öz-denetim her örnekte etiketin mesajdan geri bulunabildiğini doğrular.

**Sınır:** Veri tamamen sentetiktir. Gerçek mesajların çeşitliliğini taşımaz. Gerçek mesajlardan elle etiketlenmiş bir test kümesi kurulmadan modelin sahadaki başarısı bilinemez.

## Ölçüm

`python degerlendir.py veri/test.jsonl tahmin.jsonl` (ya da `--kural`). Asıl ölçüt `acil_kacirma`: aciliyeti 1–2 olan mesajın 3–4 sanılma oranı. Bu hata bir hayata mal olabilir; tersi (`fazla_acil`) yalnız fazladan iş çıkarır.

| Görülmemiş ifadeler (test, 10.000) | tür | acil kaçırma | fazla acil | kişi | il | ilçe |
|---|---|---|---|---|---|---|
| Kural tabanlı | %39,5 | %50,9 | — | %82,4 | %95,4 | %71,8 |
| Model | %64,8 | %13,2 | %32,6 | %91,2 | %96,7 | %87,0 |
| Model + acil ekranı kuralı | | **%0** | | | | |

Ağır gürültüde (5.000) model: tür %62,3, acil kaçırma %13,6, il %96,0. Kaynak: `model/olcum.json`, `model/kural-*.json`.

**Ne demek:** Model, hiç görmediği bir olay ifadesini ("harabenin altındayız") çoğu zaman doğru türe koyamıyor; bunu kendinden emin yapıyor, bu yüzden aciliyet eşiğiyle düzeltilemiyor (`model/esik.json`: %3 hedefi hiçbir eşikte tutmadı). Uygulamada acil ekranından yalnız yazıyla gelen çağrı en az 2 (acil) gönderilir; model türü, adresi ve kişi sayısını doldurur. Gerçek düzeltme daha geniş bir ifade dağarcığı ve pilotta toplanacak gerçek mesajlardır.

## Model

`model.py`: harf gömme + büyük/küçük harf özelliği, 2 katmanlı çift yönlü GRU (839.201 parametre), harf başına alan etiketi (BIO) ve tür, aciliyet, özel durum, il, ilçe başları.

```
python model.py egit                 # TRIYAJ_CIHAZ=cuda ile GPU; TRIYAJ_DONEM (varsayılan 2)
python model.py olc veri/test.jsonl
python model.py esik                 # veri/ayar.jsonl üzerinde aciliyet eşiği
python model.py agirlik              # telefon/: triyaj.bin (3,4 MB), triyaj.json, altin.json
```

Telefonda bağımlılıksız çalışır: `android/.../core/Triyaj.kt`, `ios/Sources/KairoCore/Triyaj.swift`. İkisi de `telefon/altin.json`daki PyTorch çıktılarını 2e-3 içinde üretir (TriyajTest). Güvenlik kuralı: model bir çağrıyı kural tablosundan daha az acil sayamaz.

Kairo dil modeli (v3) taban olarak denendi mi? Hayır. Ön eğitim henüz erken aşamada ve telefonda yüzlerce MB gerektirir. Görülmemiş ifade sorununu çözmek için önce daha geniş ifade dağarcığı ve gerçek mesajlarla eğitim denenecek.
