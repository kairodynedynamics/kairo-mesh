# 08 — Acil ekran tasarımı: neler var, neler yok, neden?

KairoMesh'in tek ekranı, enkaz altındaki bir kişi için tasarlandı. Bu kişi büyük olasılıkla şu durumdadır:
- Karanlıktadır; telefonu çatlak, pili azdır.
- Yaralıdır, tek eli kullanılmayabilir, panik içindedir.
- Yaşlı, çocuk ya da okuma yazması zayıf olabilir.

Her karar bu kişiye göre verildi. Kod: `android/.../MainActivity.kt`, `ios/App/Sources/KairoMeshApp.swift` (iki platformda aynı ekran).

## 1. Durumu doğrudan seçmek mantıklı mı?

**Evet, ama yalnızca hayati durumlar için ve yazmaya alternatif olarak.**

- Seçmek yazmaktan çok daha hızlıdır ve yazım hatası olmaz. Panikteki biri için tek dokunuş en güvenli yoldur.
- Seçilen durum kuruma **kesin** bilgi olarak gider. Aciliyeti sabit tablodan hesaplanır; bu kesinlik sahte ihbarı ayırt etmeyi de sağlar (`docs/09`).
- Yazı ise ayrıntıyı (adres, kat, kim var) taşır; seçimi tamamlar. Telefondaki model yazıdan durumu, kişi sayısını ve adresi çıkarır. Yine de yazılan her çağrı en az "acil" gönderilir.
- Hiçbir şey seçmeden yalnızca yazmak da yeterlidir.

## 2. Seçenek analizi

| Seçenek | Karar | Gerekçe |
|---|---|---|
| Enkaz altındayım | **Büyük düğme** | Depremin asıl hayati durumu; aciliyet 1. |
| Yaralı / hasta var | **Büyük düğme** | Enkaz dışında da hayati; aciliyet 2 (bebek, yaralı ya da hamile varsa 1). |
| Yangın / gaz | **Büyük düğme** | Deprem sonrası yangın ve gaz kaçağı ölümcül; aciliyet 1. |
| Başka acil durum | **Büyük düğme** | Listede olmayan hayati durumlar (sel, mahsur kalma). Yazıyla anlatılır; aciliyet 2. "Seçenek yok diye çağrı yapamamak" olmasın. |
| Açıkta kaldık | Küçük düğme | İhtiyaç, hayati değil; aciliyet 3. Büyük düğmeler arasında olursa hayati çağrıların önünde dikkat dağıtır. |
| Su / yiyecek yok | Küçük düğme | İhtiyaç; aciliyet 3. |
| Yakınımı arıyorum | Küçük düğme | Kurtarma ekipleri için değerli bilgi ama başkası adına; aciliyet 2. |
| Güvendeyim | **Ayrı yeşil düğme**, tek dokunuş | Kurtarma ekiplerinin yükünü azaltır. Geri sayımı yoktur, çünkü acil bir şey göndermez. Bekleyen bir yardım çağrısının yerine geçer; yanlışlıkla basıldıysa **"Geri al · hâlâ yardım gerekiyor"** önceki çağrıyı tek dokunuşla hemen yeniden gönderir. |
| Kişi sayısı | +/− düğmeleri, "?" = bilmiyorum | Klavye açmak panikte zordur. Boş bırakılabilir. |
| Yanımda: bebek, çocuk, yaşlı, yaralı, hamile, engelli | Çok seçimli, büyük hap düğmeler | Bebek, yaralı ve hamile aciliyeti bir kademe artırır; kurum ekip ve ekipmanı buna göre seçer. |
| Serbest metin | İsteğe bağlı | Adres ve ayrıntı. Seçim varsa ikisi birlikte gider. |
| Konum | Otomatik (izin verildiyse), en çok 6 sn beklenir | GPS internet istemez. Enkaz altında çoğu zaman alınamaz, bu yüzden çağrıyı geciktirmez. |
| Fotoğraf, ses kaydı | **Yok** | Bluetooth ağı küçük paketler taşır. Fotoğraf çağrıyı saatlerce geciktirir ve pili tüketir. |
| Kan grubu, kronik hastalık, kimlik numarası | **Yok** | Kişisel sağlık verisi (KVKK özel nitelikli). Kurtarmayı hızlandırmaz, sızma riski taşır. |
| Ad soyad | **Yok** (metne yazılabilir) | Zorunlu alan, panikteki kişiyi yavaşlatır. Kurum gönderenin anahtarını zaten görür. |
| Çoklu dil | Sonraki sürüm | İlk sürüm Türkçe. Arapça ve Kürtçe saha ihtiyacına göre eklenecek. |

## 3. Seçim durumu her an görünür

- Seçilen düğme kırmızıya döner ve üzerinde "✓ SEÇİLİ — kaldırmak için tekrar dokunun" yazar.
- Altında "Seçiminiz: Enkaz altındayım" satırı ve büyük bir **Kaldır** düğmesi durur.
- Kullanıcı seçim yaptıktan sonra yazarsa uyarı çıkar: *"Seçtiğiniz 'Enkaz altındayım' de yazdığınızla birlikte gönderilecek. İstemiyorsanız yukarıdan kaldırın."* Kullanıcı seçimin geçerli kalıp kalmadığını bilir ve karar verir.
- Seçim yapmadan yazarsa: *"Durum seçmediniz: yazdığınızdan durum telefonda anlaşılacak ve çağrı acil olarak gönderilecek."*
- Gönder düğmesinin hemen üstünde **"Gönderilecek:"** özeti durur: durum, kişi, yanındakiler, mesaj, konum. Ekranda görünen, gidecek olanın kendisidir.
- Ekran okuyucu (TalkBack, VoiceOver) her seçimi ve durumu sesli okur.

## 4. Gerçek dünya senaryoları

| Senaryo | Ne olur |
|---|---|
| Yanlış düğmeye dokundu | Aynı düğmeye tekrar dokunur ya da "Kaldır"a basar; seçim kalkar. Başka bir düğmeye dokunursa seçim ona geçer (tek seçim). |
| Gönder'e yanlışlıkla bastı | 5 saniyelik tam ekran geri sayım çıkar; büyük **İPTAL** düğmesi hiçbir şey göndermeden kapatır. |
| Gönderdi, sonra vazgeçti ya da kurtarıldı | "Yanlışlıkla gönderdim / artık yardım gerekmiyor" düğmesi tek dokunuşla, beklemeden kuruma düzeltme gönderir. Kurum panosunda çağrı "gönderen geri çekti" diye işaretlenir. Düzeltmenin kendisi yanlışsa **"Geri al"** çağrıyı hemen yeniden gönderir. |
| Düğmeye iki kez bastı | 10 saniye içindeki ikinci basış yeni çağrı açmaz; "Çağrınız zaten gönderiliyor" yazar. |
| Bluetooth kapalı | Çağrı kaydedilir; ekran Bluetooth'u açmasını ister. Açınca kendiliğinden gider. İnternet varsa Bluetooth olmadan da doğrudan kuruma gider. |
| Çağrı yola çıktı mı? | Ekran olanı söyler, "gönderildi" demez: "telefonunuzda hazır, henüz kimseye ulaşmadı", "yakındaki N telefona veriliyor" ya da "kuruma gönderiliyor". Yeşil "Kurum çağrınızı aldı" yalnız kurumun makbuzu gelince görünür; konum güncellemesinin ya da düzeltmenin makbuzu bunu göstermez. |
| Ekran açık kalsın mı? | Çağrı yazılırken ekran kararmaz. Çağrı gittikten 2 dakika sonra kararabilir (pil saatlerce yetmeli); sesli sinyal çalarken açık kalır. |
| Kurum kodu yok | Gönder'den önce açıkça söylenir; çağrı sessizce kaybolmaz. |
| Uygulama kapandı, telefon yeniden başladı | Telefonun kendi çağrısı ve başkaları için taşıdığı çağrılar diske yazılıdır; yeniden açılınca kaldığı yerden taşınır. Android'de hazırlanırken (konum, metin okuma, iş kanıtı: birkaç saniye) kapanan çağrı da açılınca bir kez gönderilir; iOS'ta bu birkaç saniyede kapanırsa yeniden gönderilmesi gerekir. Ağ telefonla birlikte kendiliğinden başlar (Android). |
| Kurum bir gün içinde almadı | Çağrı 6 saatte bir yeniden gönderilir; ağ zarfları bir gün sonra siler. |
| Karanlık, ekran kararıyor | Çağrı yazılırken ekran kararmaz; çağrı gittikten 2 dakika sonra kararabilir, dokununca yine 2 dakika açık kalır. |
| Kurtarıcılar yakında ama göremiyor | **Sesli sinyal**: yüksek perdeden, aralıklı bir düdük sesi (3,1 kHz), sessiz kipte de çalar. |

## 5. Tema

- **Zemin:** Açık gri, hafif parlak bir geçiş (#ECEFF2'den #D6DCE1'e).
- **Kartlar:** Neredeyse beyaz.
- **Yazı:** Neredeyse siyah (#0E1A22). Karttaki kontrast 14:1'in üstünde; çatlak ya da kısık ekranda okunur.
- **Neden ne çok açık ne çok koyu:** Saf beyaz karanlıkta göz alır. Koyu tema güneşte ve kısık parlaklıkta okunmaz.
- **Renk kullanımı:** Kırmızı yalnızca acil öğelerde, yeşil yalnızca olumlu durumlarda (internet var, kurum aldı) kullanılır.
- **Boyutlar:** Dokunma alanları en az 56 dp, büyük durum düğmeleri 100 dp. Yazılar 15–26 sp.
