"""Kairo Mesh triyaj veri seti üreteci (v1, sentetik).

    python uret.py                 -> veri/*.jsonl, öz-denetimle
    python uret.py --egitim 200000 -> daha küçük eğitim bölümü

Görev: şebeke yokken telefondan aceleyle yazılmış Türkçe yardım mesajını
yapılandırılmış kayda çevirmek. Kayıt hem önceliklendirmede (aciliyet,
112'ye aktarma) hem de Bluetooth paketine sığdırmada (`sikistir`) kullanılır.

Etiket kuralları (modelin öğrenebileceği şey neyse etiket odur):
- `il`, `ilce`: kapalı sözlükten, kanonik yazımla. Mesajda bozuk yazılmış olsa
  da (antkya, HATAY, kahramanmras) etiket kanoniktir: sözlük sınırlı olduğu
  için düzeltme öğrenilebilir.
- `mahalle`, `sokak`, `bina`, `ad`: mesajda NASIL geçiyorsa öyle (çıkarım).
  Açık uçlu adların doğru yazımı mesajdan çıkarılamaz; düzeltme coğrafi
  eşleştirme katmanının işidir. Bu alanlara yazım hatası uygulanmaz, yalnız
  mesajın genel biçimi (küçük harf, aksansız...) uygulanır.
- `kisi`, `tel`: sayı; mesajda rakamla ya da yazıyla geçer. `tel` yalnız rakam.

Test bölümleri eğitimde HİÇ görülmeyen şablonlar, mahalle/sokak/bina ve
kişi adlarıyla kurulur: ölçülen şey ezber değil genellemedir. Gerçek
mesajlarla ölçüm ayrı, elle etiketlenmiş bir kümeyle yapılmalıdır.
"""
import argparse
import json
import random
import re
from pathlib import Path

TOHUM = 20261116

# ---------------------------------------------------------------- yer adları --
ILCELER = {
    "Hatay": ["Antakya", "Defne", "İskenderun", "Samandağ", "Kırıkhan", "Arsuz", "Reyhanlı", "Dörtyol", "Hassa", "Altınözü"],
    "Kahramanmaraş": ["Onikişubat", "Dulkadiroğlu", "Elbistan", "Pazarcık", "Türkoğlu", "Afşin", "Göksun", "Nurhak"],
    "Adıyaman": ["Merkez", "Gölbaşı", "Besni", "Kahta", "Tut", "Çelikhan"],
    "Malatya": ["Battalgazi", "Yeşilyurt", "Doğanşehir", "Akçadağ", "Darende", "Pütürge"],
    "Gaziantep": ["Nurdağı", "İslahiye", "Şahinbey", "Şehitkamil", "Nizip"],
    "Osmaniye": ["Merkez", "Düziçi", "Kadirli", "Bahçe", "Toprakkale"],
    "Diyarbakır": ["Bağlar", "Kayapınar", "Yenişehir", "Sur", "Ergani"],
    "Şanlıurfa": ["Haliliye", "Eyyübiye", "Birecik", "Siverek", "Viranşehir"],
    "Adana": ["Seyhan", "Çukurova", "Yüreğir", "Ceyhan", "Sarıçam", "Kozan"],
    "Kilis": ["Merkez", "Musabeyli"],
    "Elazığ": ["Merkez", "Sivrice", "Maden"],
    "İstanbul": ["Avcılar", "Zeytinburnu", "Kadıköy", "Esenyurt", "Bakırköy", "Küçükçekmece", "Fatih", "Bağcılar", "Beylikdüzü", "Üsküdar"],
    "İzmir": ["Bayraklı", "Bornova", "Karşıyaka", "Konak", "Buca", "Çiğli"],
    "Düzce": ["Merkez", "Kaynaşlı", "Gölyaka", "Akçakoca"],
    "Bursa": ["Osmangazi", "Nilüfer", "Yıldırım", "Gemlik"],
    "Kocaeli": ["İzmit", "Gölcük", "Derince", "Körfez"],
    "Sakarya": ["Adapazarı", "Serdivan", "Akyazı"],
    "Bolu": ["Merkez", "Gerede"],
    "Van": ["İpekyolu", "Tuşba", "Erciş", "Edremit"],
    "Erzincan": ["Merkez", "Refahiye"],
    "Bingöl": ["Merkez", "Genç", "Solhan"],
    "Muğla": ["Bodrum", "Marmaris", "Fethiye"],
    "Denizli": ["Merkezefendi", "Pamukkale"],
    "Ankara": ["Çankaya", "Keçiören", "Mamak", "Yenimahalle"],
}
# (eğitim, test) — ayrık
MAHALLE = (["Cumhuriyet", "Atatürk", "Yeni", "Fatih", "Bahçelievler", "Güzelyalı", "Kurtuluş", "Akevler", "Odabaşı",
            "Ürgenpaşa", "Sümerler", "Ekinci", "Emek", "Barbaros", "Hürriyet", "Karşıyaka", "Yıldırım Beyazıt", "Şehitler",
            "Namık Kemal", "İstiklal", "Zafer", "Menderes", "Saray", "Gülbahçe", "Toki", "Esentepe", "Şeyh Ali", "Pirireis",
            "Mimar Sinan", "Kazım Karabekir", "Yavuz Selim", "Akdeniz", "Kanuni", "Mevlana", "Hacı Bayram", "Orhangazi",
            "Selçuklu", "Bağlarbaşı", "Kocatepe", "Gazipaşa", "Sakarya", "Malazgirt", "Hamidiye", "Necip Fazıl"],
           ["Gazi", "Çamlık", "Aksaray", "Kanatlı", "Yunus Emre", "Haraparası", "Dörtayak", "Turunçlu", "Sağlık",
            "Kuyulu", "Ulucami", "Pınarbaşı", "Kırmızıdere", "Serinyol", "Narlıca", "Armutlu"])
SOKAK = (["Atatürk Caddesi", "İnönü Caddesi", "Gül Sokak", "Lale Sokak", "Okul Sokak", "Cami Sokak", "Mehmet Akif Caddesi",
          "Çınar Sokak", "Fevzi Çakmak Caddesi", "Şehit Ali Sokak", "Menekşe Sokak", "1. Sokak", "12. Sokak", "105. Sokak",
          "Kurtuluş Caddesi", "Hastane Caddesi", "Park Sokak", "Zambak Sokak", "Erguvan Sokak", "Bülbül Sokak",
          "Cumhuriyet Bulvarı", "Gazi Bulvarı", "Pazar Sokak", "Değirmen Yolu", "Karakol Sokak", "Sanayi Caddesi"],
         ["Kışla Caddesi", "Papatya Sokak", "Yavuz Selim Caddesi", "İpek Sokak", "38. Sokak", "Nergis Sokak",
          "Liman Caddesi", "Çarşı Sokak", "Hükümet Caddesi"])
BINA = (["Güneş Apartmanı", "Yıldız Apartmanı", "Huzur Sitesi A Blok", "Doğa Apartmanı", "Çiçek Apartmanı",
         "Barış Sitesi C Blok", "Özlem Apartmanı", "Umut Apartmanı", "Ata Sitesi 2. Blok", "Kardelen Apartmanı",
         "Ebrar Sitesi", "Galeria Sitesi B Blok", "Akasya Apartmanı", "Bahar Apartmanı", "Yeşil Vadi Sitesi",
         "Hanımeli Apartmanı", "Seda Apartmanı", "Polat Apartmanı", "Toki 14. Blok", "Rana Apartmanı"],
        ["Defne Apartmanı", "Rönesans Sitesi B Blok", "Erdem Apartmanı", "Gökkuşağı Sitesi D Blok", "Mavi Apartmanı",
         "Asude Apartmanı", "Liman Sitesi E Blok"])
AD = (["Ayşe Yılmaz", "Mehmet Kaya", "Fatma Demir", "Ali Çelik", "Zeynep Şahin", "Mustafa Öztürk", "Elif Aydın",
       "Hasan Arslan", "Emine Doğan", "İbrahim Kılıç", "Hatice Koç", "Yusuf Polat", "Ahmet Kurt", "Merve Aslan",
       "Hüseyin Özdemir", "Gülsüm Yıldız", "Murat Şimşek", "Esra Erdoğan", "Abdullah Aksoy", "Songül Çetin",
       "Ramazan Kara", "Derya Uçar", "Halil Bozkurt", "Büşra Kaplan", "Mahmut Yavuz"],
      ["Sevgi Aktaş", "Ömer Güneş", "Meryem Bulut", "Kemal Erdem", "Nurcan Tekin", "Burak Sezer", "Dilan Acar",
       "Recep Turan"])
AKRABA = ["annem", "babam", "kardeşim", "ablam", "abim", "teyzem", "dayım", "amcam", "halam", "eşim", "kızım",
          "oğlum", "dedem", "anneannem", "babaannem", "kuzenim", "yeğenim", "komşumuz", "arkadaşım"]

SAYI_YAZI = {1: "bir", 2: "iki", 3: "üç", 4: "dört", 5: "beş", 6: "altı", 7: "yedi", 8: "sekiz", 9: "dokuz",
             10: "on", 12: "on iki", 15: "on beş", 20: "yirmi"}

OZEL = {"cocuk": ["çocuk var", "çocuklar da var", "küçük çocuklar var", "çocuk da var içerde"],
        "bebek": ["bebek var", "6 aylık bebek var", "kucağında bebeği var", "yeni doğmuş bebek var"],
        "yasli": ["yaşlı var", "yaşlı annem var", "dedem yanımızda", "yaşlı bir teyze var"],
        "yarali": ["yaralı var", "kanaması var", "bacağı kırık", "başından yaralı", "kolu sıkıştı"],
        "hamile": ["hamile kadın var", "eşim hamile", "hamile biri var"],
        "engelli": ["engelli var", "yürüyemiyor", "tekerlekli sandalyeli", "yatalak hasta var"],
        "ses_var": ["sesleri geliyor", "ses var", "bağırıyorlar duyuyoruz", "hâlâ mesaj atıyor", "duvara vuruyor"],
        "ses_yok": ["ses gelmiyor artık", "ulaşamıyoruz", "saatlerdir haber yok", "telefonu kapalı"]}

# (tür, aciliyet, ihtiyaçlar, şablonlar). {K}=konum {N}=kişi {O}=özel {A}=ad {R}=akraba
# `|T` ile biten şablon YALNIZ testte kullanılır.
TUR = [
 ("enkaz", 1, ["arama_kurtarma"], [
   "{K} enkaz altındayız {N} kişiyiz {O} acil yardım",
   "ACİL {K} bina çöktü {N} kişi enkaz altında {O}",
   "{A} enkaz altında {K} {O} lütfen ekip gönderin",
   "enkaz altında {N} kişi var {O} adres {K} vinç lazım",
   "ailem göçük altında {N} kişiler {K} {O} kimse gelmedi",
   "{R} enkazda kaldı {K} {O} yardım edin",
   "{K} bina yıkıldı içinde {N} kişi kaldı {O}",
   "yardım göçük altındayız {K} {N} kişiyiz {O}",
   "{K} enkazdan ses geliyor {N} kişi olabilir",
   "{R} ve ailesi molozun altında {K} {O}",
   "binamız çöktü {K} {R} hala içerde {O}",
   "{K} kat çöktü {N} kişi mahsur kaldı {O}",
   "{K} tamamen yıkıldı altında {N} kişi kaldı {O} duyan yayılsın|T",
   "kurtarma ekibi yok hâlâ. {A} ve ailesi molozun altında, {N} kişi. {K}. {O}|T",
   "göçükte {R} var {K} ekipler bekleniyor {O}|T"]),
 ("saglik", 2, ["ambulans"], [
   "{K} ağır yaralı var {N} kişi ambulans gelmiyor {O}",
   "{A} için acil ambulans {K} {O}",
   "diyaliz hastası var ilaç bitti {K} {N} kişi",
   "{K} insülin lazım şeker hastası {O}",
   "{R} kalp krizi geçiriyor {K} ambulans",
   "{K} doğum başladı ambulans lazım {O}",
   "{K} solunum cihazına bağlı hasta var elektrik yok",
   "{R} bayıldı nefes almıyor {K}",
   "doktor lazım {K} {N} yaralıyı taşıyamıyoruz {O}|T",
   "{K} kalp hastası babam ilaçsız kaldı, {O}, sağlık ekibi yollayın|T"]),
 ("yangin", 1, ["itfaiye"], [
   "{K} yangın çıktı içeride {N} kişi var {O}",
   "doğalgaz kokusu ve yangın {K} itfaiye yok {O}",
   "{K} duman var içeride insanlar var",
   "{K} trafo patladı yangın var",
   "{K} bina yanıyor enkazın yanında {N} kişi mahsur|T"]),
 ("barinma", 3, ["cadir", "battaniye"], [
   "{K} {N} kişi dışarıdayız çadır yok çok soğuk {O}",
   "çadır ve battaniye lazım {K} {N} kişi {O}",
   "{K} arabada yatıyoruz {N} kişi ısıtıcı lazım {O}",
   "{K} yağmurda kaldık {N} kişi çadır lazım {O}",
   "evimiz hasarlı giremiyoruz {K} {N} kişiyiz {O}",
   "{N} aile açıkta kaldı {K} barınacak yer yok {O}|T"]),
 ("gida_su", 3, ["su", "gida"], [
   "{K} su ve yemek yok {N} kişiyiz {O}",
   "içme suyu bitti {K} {N} kişi {O}",
   "{K} bebek maması ve bez lazım {O}",
   "{K} iki gündür yemek yemedik {N} kişi {O}",
   "{K} köyümüze hiç yardım gelmedi {N} kişi var",
   "ekmek su hiçbir şey gelmedi {K} köyde {N} kişi var {O}|T"]),
 ("kayip", 2, ["bilgi"], [
   "{A} dan haber alamıyoruz {K} oturuyordu gören var mı",
   "kayıp: {A} en son {K} görüldü {O}",
   "{A} isimli yakınımı arıyorum {K} bilgisi olan yazsın",
   "{R} {A} kayıp {K} bilen ulaşsın",
   "{A} hastaneye mi kaldırıldı {K} bilgi lazım",
   "{K} adresindeki {A} hayatta mı bilen var mı {O}|T"]),
 ("lojistik", 2, ["vinc", "jenerator"], [
   "{K} vinç ve kepçe lazım enkazda {N} kişi var",
   "jeneratör ve projektör lazım {K} gece çalışamıyoruz",
   "{K} iş makinesi lazım ekip var ama makine yok",
   "{K} akaryakıt bitti jeneratör durdu",
   "{K} ekip var ama iş makinesi yok kırıcı hilti lazım|T"]),
 ("guvende", 4, [], [
   "ben iyiyim {K} dayız güvendeyiz {N} kişi",
   "{A} güvende {K} toplanma alanında",
   "ailecek dışarı çıktık iyiyiz {K}",
   "{R} ile birlikteyiz sağlığımız yerinde {K}",
   "kurtarıldık hastanedeyiz {K}",
   "merak etmeyin {A} sağ salim, {K} okul bahçesindeyiz|T"]),
 ("bilgi", 4, [], [
   "{K} yolu kapalı köprü çökmüş geçiş yok",
   "{K} toplanma alanı açıldı çorba dağıtılıyor",
   "{K} eczane açık ilaç veriyorlar",
   "{K} su dağıtımı var saat 3te",
   "{K} hastane hasarlı hastaları başka yere taşıyorlar",
   "dikkat {K} tarafında binalar hasarlı girmeyin|T"]),
 ("alakasiz", 4, [], [
   "geçmiş olsun herkese Allah yardımcımız olsun",
   "bağış kampanyası başlattık linkte",
   "deprem kaç şiddetindeydi bilen var mı",
   "artçı devam ediyor dikkatli olun",
   "herkes evinde mi",
   "çok korktuk hala titriyorum",
   "bu kadar ihmal olur mu sorumlular hesap versin|T",
   "haberleri izliyorum çok üzücü|T"]),
]
KISA = {"enkaz": "E", "saglik": "S", "yangin": "Y", "barinma": "B", "gida_su": "G", "kayip": "K",
        "lojistik": "L", "guvende": "V", "bilgi": "I", "alakasiz": "X"}

# ----------------------------------------------------------------- gürültü --
ASCII = str.maketrans("çğıöşüÇĞİÖŞÜâÂîû", "cgiosuCGIOSUaAiu")
# Türkçe Q klavyede komşu tuşlar (yazım hatası üretmek için)
SATIRLAR = ["qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç"]
KOMSU = {}
for _i, _s in enumerate(SATIRLAR):
    for _j, _c in enumerate(_s):
        k = {_s[x] for x in (_j - 1, _j + 1) if 0 <= x < len(_s)}
        for _d in (-1, 1):
            if 0 <= _i + _d < len(SATIRLAR):
                k |= {SATIRLAR[_i + _d][x] for x in (_j - 1, _j, _j + 1) if 0 <= x < len(SATIRLAR[_i + _d])}
        KOMSU[_c] = "".join(sorted(k))
KISALT = {"mahallesi": ["mah", "mh", "mah.", "mh."], "sokak": ["sk", "sok", "sk."], "caddesi": ["cd", "cad", "cd."],
          "apartmanı": ["apt", "ap", "apt."], "sitesi": ["st", "sit."], "kişi": ["kişi", "kisi", "kş"],
          "lütfen": ["lütfen", "lutfen", "ltfn", "lütfn"], "yardım": ["yardım", "yardim", "yrdm"],
          "acil": ["acil", "ACİL", "acill"], "var": ["var", "va"], "için": ["için", "icin", "içn"]}


def bicim(r):
    """Mesajın genel biçimi: telefon klavyesi alışkanlıkları. Varlıklara da uygulanır."""
    return {"aksansiz": r.random() < 0.35, "kip": r.choices(["olagan", "kucuk", "buyuk", "ilk"], [45, 35, 12, 8])[0]}


def uygula(b, s):
    if b["aksansiz"]:
        s = s.translate(ASCII)
    if b["kip"] == "kucuk":
        s = s.replace("I", "ı").replace("İ", "i").lower()
    elif b["kip"] == "buyuk":
        s = s.replace("i", "İ").replace("ı", "I").upper()
    return s  # "ilk" (cümle başı büyük) yalnız mesajın başına, gurultule içinde


def yazim_hatasi(r, w, siddet):
    """Tek kelimeye karakter düzeyinde hata. Rakamlı kelimelere dokunulmaz."""
    if len(w) < 3 or any(ch.isdigit() for ch in w):
        return w
    for _ in range(r.choice([1, 1, 1, 2]) if siddet > 0.5 else 1):
        i = r.randrange(len(w))
        islem = r.random()
        c = w[i].lower()
        if islem < 0.30 and c in KOMSU:                       # komşu tuş
            w = w[:i] + r.choice(KOMSU[c]) + w[i + 1:]
        elif islem < 0.50:                                    # harf atlama
            w = w[:i] + w[i + 1:]
        elif islem < 0.65 and i < len(w) - 1:                 # yer değiştirme
            w = w[:i] + w[i + 1] + w[i] + w[i + 2:]
        elif islem < 0.80:                                    # çift basma
            w = w[:i] + w[i] + w[i:]
        else:                                                 # sesli harf düşürme (mesaj dili)
            w = w[:i] + re.sub(r"[aeıioöuü]", "", w[i:i + 2], count=1) + w[i + 2:]
        if len(w) < 2:
            break
    return w


def gurultule(r, parcalar, b, siddet):
    """parcalar: [(metin, koruma[, alan])]. True: yalnız biçim; "hafif": yazım hatası olur ama kelime düşmez
    (etiketi taşıyan özel durum ifadeleri); False: tam gürültü."""
    out, araliklar, konum_ = [], [], 0

    def ekle(metin, alan):
        nonlocal konum_
        if alan and metin.strip():
            bas = konum_ + len(metin) - len(metin.lstrip())
            araliklar.append((alan, bas, konum_ + len(metin.rstrip())))
        out.append(metin)
        konum_ += len(metin)

    for parca in parcalar:
        metin, korumali = parca[0], parca[1]
        alan = parca[2] if len(parca) > 2 else None
        metin = uygula(b, metin)
        if korumali is True or not metin.strip():
            ekle(metin, alan)
            continue
        kel = metin.split(" ")
        yeni = []
        for w in kel:
            lw = w.lower()
            if lw in KISALT and r.random() < 0.35 * (0.5 + siddet):
                w = r.choice(KISALT[lw])
            if r.random() < 0.10 * siddet:
                w = yazim_hatasi(r, w, siddet)
            if w and korumali != "hafif" and r.random() < 0.04 * siddet:  # kelime düşme
                continue
            yeni.append(w)
        metin = " ".join(yeni)
        if r.random() < 0.15 * siddet:                        # boşluk yutma
            metin = re.sub(r" ", "", metin, count=r.randint(1, 2))
        if r.random() < 0.2 * siddet:                         # "acilll", "yardımmm", "lütfeeen"
            metin = re.sub(r"([a-zçğıöşü])\b", lambda m: m.group(1) * r.randint(2, 5), metin, count=1, flags=re.I)
        if r.random() < 0.10:                                 # çift boşluk (korumalı parçaya dokunmaz)
            metin = metin.replace(" ", "  ", r.randint(1, 3))
        ekle(metin, alan)
    s = "".join(out)
    if b["kip"] == "ilk" and s[:1].islower():
        s = s[:1].replace("i", "İ").upper() + s[1:]
    if r.random() < 0.25:
        on = r.choice(["LÜTFEN YAYIN ", "acil yardım!! ", "duyan olsun ", "#deprem ", "RT ", "!!! ", "yardımmm "])
        s = on + s
        araliklar = [(a, x + len(on), y + len(on)) for a, x, y in araliklar]
    if r.random() < 0.25:
        s += r.choice([" #deprem", " lütfen paylaşın", " !!!", " yardım edin", " 🙏", " 😭😭", " ...", " ??", " #enkazaltındayım"])
    kirp = len(s) - len(s.lstrip())
    return s.strip(), {a: [x - kirp, y - kirp] for a, x, y in araliklar}


# --------------------------------------------------------------- üretim --
def sayi_yaz(r, n):
    if n in SAYI_YAZI and r.random() < 0.35:
        return SAYI_YAZI[n]
    return str(n)


def konum(r, test, b, c):
    """[(metin, korumali)] parçaları; etiketleri c'ye yazar."""
    il = r.choice(list(ILCELER))
    e = {"il": il, "ilce": r.choice(ILCELER[il]), "mahalle": r.choice(MAHALLE[test]),
         "sokak": r.choice(SOKAK[test]), "bina": r.choice(BINA[test])}
    for alan, p in (("bina", 0.3), ("sokak", 0.3), ("mahalle", 0.15), ("ilce", 0.15), ("il", 0.15)):
        if r.random() < p:
            e[alan] = None
    if not any(e.values()):
        e["il"] = il
    parca, yuzey = [], {}
    # il/ilçe kanonik etiket, metinde yazım hatası olabilir (kapalı sözlük: düzeltilebilir)
    for alan in ("il", "ilce"):
        if e[alan]:
            parca.append([(e[alan], False, alan)])
    if e["mahalle"]:
        ek = r.choice([" Mahallesi", " mahallesi", " mah", " mh", " mh.", " Mah.", ""])
        parca.append([(e["mahalle"], True, "mahalle"), (ek, False)])
        yuzey["mahalle"] = e["mahalle"]
    if e["sokak"]:
        sk = e["sokak"]
        if r.random() < 0.3:
            sk = sk.replace(" Sokak", " sk").replace(" Caddesi", " cd").replace(" Bulvarı", " bulv")
        parca.append([(sk, True, "sokak")])
        yuzey["sokak"] = sk
    if e["bina"]:
        bn = e["bina"]
        if r.random() < 0.25:
            bn = bn.replace(" Apartmanı", " apt").replace(" Sitesi", " sit")
        p = [(bn, True, "bina")]
        yuzey["bina"] = bn
        if r.random() < 0.5:
            p.append((r.choice([" no ", " no:", " numara ", " "]), False))
            p.append((str(r.randint(1, 120)), True))
        if r.random() < 0.3:
            p.append((r.choice([" kat ", " ", " "]) + str(r.randint(1, 9)) + r.choice([". kat", " kat", ""]), True))
        parca.append(p)
    if r.random() < 0.3:
        parca.reverse()
    ayrac = r.choice([" ", ", ", " / ", " - ", ","])
    out = []
    for i, p in enumerate(parca):
        if i:
            out.append((ayrac, False))
        out += p
    c["il"], c["ilce"] = e["il"], e["ilce"]
    for alan, m in yuzey.items():  # etiket = metinde geçtiği biçim
        c[alan] = uygula(b, m)
    return out


# Bileşenli üretim (v2): sabit şablonlar modelin şablonu ezberlemesine yol açtı (görülmemiş kalıplarda olay türü
# doğruluğu %50'ye düştü). Bu yüzden mesajların çoğu, her tür için ayrı ifade havuzundan seçilen olay ifadesi ile
# konum, kişi sayısı, özel durum ve çağrı eklerinin rastgele sırada birleşmesiyle kurulur. `|T` ile biten ifadeler
# YALNIZ testte kullanılır: test, eğitimde hiç geçmeyen ifadelerle ölçülür.
OLAY = {
    "enkaz": ["enkaz altındayız", "bina çöktü altında kaldık", "göçük altındayız", "molozun altındayız",
              "binamız yıkıldı içerdeyiz", "çıkamıyoruz enkazdayız", "kat çöktü mahsur kaldık", "duvar üstümüze çöktü",
              "apartman yıkıldı altındayız", "enkazın içindeyiz nefes alabiliyoruz", "binanın altında kaldık",
              "bodrumda sıkıştık bina çöktü", "evimiz başımıza yıkıldı", "{R} enkaz altında", "{R} göçükte kaldı",
              "yıkılan binada mahsuruz", "merdiven çöktü çıkamıyoruz", "beton altında kaldık", "üstümüzde moloz var",
              "bina yan yattı içindeyiz", "{R} binanın altında kaldı",
              "harabenin altındayız|T", "tavan çöktü üstümüze|T", "yıkıntının arasındayız|T", "{R} yıkılan evin altında|T",
              "kolonlar çöktü altında kaldık|T", "çökmüş binanın içindeyiz|T"],
    "saglik": ["ağır yaralı var", "yaralıyız ambulans lazım", "kanaması durmuyor", "kalp krizi geçiriyor", "nefes alamıyor",
               "bayıldı ayılmıyor", "diyaliz hastası var", "insülin bitti", "ilaçları bitti hasta", "doğum sancısı başladı",
               "bacağı kırıldı", "başından yaralandı", "doktor lazım acil", "ambulans gelmiyor", "{R} çok kötü hastanelik",
               "şeker hastası fenalaştı", "astım krizi var", "yanık var tedavi lazım", "oksijen cihazı çalışmıyor",
               "yatalak hasta ilaçsız", "{R} yaralandı kan kaybediyor",
               "tansiyonu düştü bilinci yok|T", "kolu ezildi çok kanıyor|T", "{R} sara nöbeti geçiriyor|T",
               "sağlık ekibi lazım hemen|T", "kırık var hareket edemiyor|T", "zehirlendi kusuyor|T"],
    "yangin": ["yangın çıktı", "bina yanıyor", "her yer duman", "doğalgaz kokusu var yangın", "alevler yükseliyor",
               "trafo patladı yanıyor", "ev alev aldı", "duman var içeride insanlar var", "itfaiye lazım yangın", "çatı yanıyor",
               "araç yanıyor binaya sıçradı", "mutfakta yangın başladı", "yangın büyüyor",
               "kıvılcım sıçradı tutuştu|T", "ateş büyüyor söndüremiyoruz|T", "yanık kokusu ve duman|T", "dükkan yanıyor yanına sıçrıyor|T"],
    "barinma": ["çadır yok dışarıdayız", "açıkta kaldık çok soğuk", "battaniye lazım", "arabada yatıyoruz", "evimize giremiyoruz",
                "barınacak yer yok", "yağmurda kaldık", "ısıtıcı lazım donuyoruz", "sokakta kaldık", "çocuklar üşüyor çadır lazım",
                "konteyner lazım", "ev hasarlı dışarıdayız", "çadır verin",
                "kalacak yerimiz kalmadı|T", "parkta sabahladık|T", "naylon altındayız|T", "geceyi dışarda geçirdik üşüyoruz|T"],
    "gida_su": ["su yok", "içme suyu bitti", "yemek yok", "ekmek yok", "bebek maması lazım", "iki gündür aç", "gıda yardımı lazım",
                "su ve yemek gelmedi", "hiç yardım gelmedi aç kaldık", "bez ve mama lazım", "temiz su lazım", "erzak bitti",
                "susuz kaldık|T", "yiyecek bir şey kalmadı|T", "aç ve susuzuz|T", "kumanya lazım|T"],
    "kayip": ["{A} dan haber alamıyoruz", "{A} kayıp", "{A} ı arıyorum", "{A} ya ulaşamıyoruz", "{R} kayıp gören var mı",
              "{A} hayatta mı bilen var mı", "{A} hangi hastanede", "{R} {A} kayıp bilgisi olan yazsın",
              "{A} nereye götürüldü bilen yok|T", "{R} {A} den ses seda yok|T", "{A} enkazdan çıkarıldı mı|T"],
    "lojistik": ["vinç lazım", "kepçe lazım", "iş makinesi lazım", "jeneratör lazım", "projektör lazım", "kırıcı hilti lazım",
                 "akaryakıt bitti", "kurtarma ekibi var makine yok", "beton kesme makinesi lazım",
                 "kaldırma aracı gerekiyor|T", "ağır makine şart|T", "ışık yok gece çalışamıyoruz|T"],
    "guvende": ["iyiyiz", "güvendeyiz", "sağ salim çıktık", "kurtarıldık", "{R} ile iyiyiz", "toplanma alanındayız iyiyiz",
                "merak etmeyin iyiyiz", "hastanedeyiz durumumuz iyi", "{A} güvende", "dışarı çıktık bir şeyimiz yok",
                "korkmayın sağlıklıyız|T", "sorun yok hepimiz iyiyiz|T", "{A} sağ ve iyi|T", "yara almadan kurtulduk|T"],
    "bilgi": ["yol kapalı", "köprü çökmüş", "eczane açık", "su dağıtımı var", "toplanma alanı açıldı", "hastane hasarlı",
              "çorba dağıtılıyor", "binalar hasarlı girmeyin", "elektrik kesik", "artçı için dikkat",
              "geçiş yok yol çökmüş|T", "aşevi kuruldu|T", "bu bölgede gaz kaçağı var uzak durun|T"],
    "alakasiz": ["geçmiş olsun", "Allah yardımcımız olsun", "bağış linki", "deprem kaç şiddetindeydi", "herkes iyi mi",
                 "çok korktuk", "haberleri izliyorum", "yardım kampanyası başlattık", "sorumlular hesap versin", "dua edelim",
                 "milletçe yanınızdayız|T", "sabırlar diliyorum|T", "büyüklüğü ne kadarmış|T"],
}
KISI_IFADE = ["{N} kişiyiz", "{N} kişi var", "içeride {N} kişi var", "{N} kişi mahsur", "toplam {N} kişi", "{N} kişilik aileyiz",
              "{N} can var|T", "biz {N} kişiyiz|T"]
EK_IFADE = ["acil yardım", "lütfen yardım edin", "ekip gönderin", "kimse gelmedi", "duyan yayılsın", "saatlerdir bekliyoruz",
            "acil", "yardım", "çabuk gelin|T", "imdat|T"]
KISI_TURLERI = {"enkaz", "saglik", "yangin", "barinma", "gida_su", "lojistik", "guvende"}
OZEL_TURLERI = {"enkaz", "saglik", "yangin", "barinma", "gida_su"}


def bilesen_sablon(r, tur, test):
    """Bir türün ifadesi + isteğe bağlı konum, kişi, özel durum ve ek; rastgele sırada, rastgele ayraçla."""
    sec = lambda havuz: r.choice([x for x in havuz if x.endswith("|T") == test] or havuz).removesuffix("|T")  # noqa: E731
    olay = sec(OLAY[tur])
    parca = [olay]
    acil = tur in OZEL_TURLERI or tur == "lojistik"
    if r.random() < (0.85 if acil else 0.5):
        parca.append("{K}")
    if tur in KISI_TURLERI and "{N}" not in olay and r.random() < 0.55:
        parca.append(sec(KISI_IFADE))
    if tur in OZEL_TURLERI and r.random() < 0.45:
        parca.append("{O}")
    if acil and r.random() < 0.4:
        parca.append(sec(EK_IFADE))
    yol = r.random()
    if yol < 0.4 and "{K}" in parca:  # adres önce
        parca.remove("{K}")
        parca.insert(0, "{K}")
    elif yol > 0.8:
        r.shuffle(parca)
    ayrac = r.choice([" ", " ", ", ", ". ", " - "])
    return ayrac.join(parca)


def ornek(r, test, siddet):
    tur, aciliyet, ihtiyac, sablonlar = r.choice(TUR)
    if r.random() < 0.7:
        s = bilesen_sablon(r, tur, test)
    else:
        s = r.choice([x for x in sablonlar if x.endswith("|T") == test]).removesuffix("|T")
    b = bicim(r)
    c = {"tur": tur, "aciliyet": aciliyet, "kisi": None, "il": None, "ilce": None, "mahalle": None,
         "sokak": None, "bina": None, "ozel": [], "ihtiyac": list(ihtiyac), "ad": None, "tel": None}
    parcalar = []
    for tok in re.split(r"(\{[KNOAR]\})", s):
        if tok == "{K}":
            parcalar += konum(r, test, b, c)
        elif tok == "{N}":
            c["kisi"] = r.choice([1, 2, 2, 3, 3, 4, 5, 6, 8, 10, 12, 15, 20])
            parcalar.append((sayi_yaz(r, c["kisi"]), True, "kisi"))
        elif tok == "{A}":
            ad = r.choice(AD[test])
            if r.random() < 0.3:
                ad = ad.split(" ")[0]
            c["ad"] = uygula(b, ad)
            parcalar.append((ad, True, "ad"))
        elif tok == "{R}":
            parcalar.append((r.choice(AKRABA), False))
        elif tok == "{O}":
            secilen = r.sample(list(OZEL), r.choice([0, 1, 1, 2]))
            if "ses_var" in secilen and "ses_yok" in secilen:
                secilen.remove("ses_yok")
            c["ozel"] = sorted(secilen)
            parcalar.append((" ".join(r.choice(OZEL[o]) for o in secilen), "hafif"))
        elif tok:
            parcalar.append((tok, False))
    if aciliyet > 1 and tur not in ("guvende", "bilgi", "alakasiz") and set(c["ozel"]) & {"bebek", "yarali", "hamile"}:
        c["aciliyet"] = aciliyet - 1
    if tur != "alakasiz" and r.random() < 0.3:
        no = "5%d%07d" % (r.randint(30, 59), r.randint(0, 9_999_999))
        c["tel"] = "0" + no
        yuzey = r.choice(["0" + no, "0%s %s %s %s" % (no[:3], no[3:6], no[6:8], no[8:]),
                          "%s %s %s %s" % (no[:3], no[3:6], no[6:8], no[8:]), "0" + no[:3] + " " + no[3:]])
        parcalar.append((r.choice([" tel ", " iletişim ", " ", " numara ", " ara "]), False))
        parcalar.append((yuzey, True, "tel"))
    girdi, aralik = gurultule(r, parcalar, b, siddet)
    return {"girdi": girdi, "cikti": c, "aralik": aralik}


def denetle(o):
    c, g = o["cikti"], o["girdi"]
    for alan, (x, y) in o["aralik"].items():
        if alan in ("mahalle", "sokak", "bina", "ad"):
            assert g[x:y] == c[alan], (alan, g[x:y], o)
        assert 0 <= x < y <= len(g), (alan, o)
    rakam = re.sub(r"\D", "", g)
    if c["tel"]:
        assert c["tel"].lstrip("0") in rakam, o
    if c["kisi"] is not None:
        yazi = SAYI_YAZI.get(c["kisi"], "\0")
        assert str(c["kisi"]) in g or yazi in uygula_hepsi(g) or yazi.translate(ASCII) in uygula_hepsi(g), o
    for alan in ("mahalle", "sokak", "bina", "ad"):
        if c[alan]:
            assert c[alan] in g, (alan, o)
    assert 1 <= c["aciliyet"] <= 4 and c["tur"] in KISA


def uygula_hepsi(g):
    """Türkçe küçük harfe çevir, bir de aksansız hâli: yazıyla sayı her iki biçimde aranır."""
    k = g.replace("İ", "i").replace("I", "ı").lower()
    return k + " " + k.translate(ASCII)


def sikistir(c):
    """Havaya çıkacak tel biçimi: tür+aciliyet|kişi|il/ilçe/mahalle/sokak/bina|özel|ad|tel."""
    yer = "/".join(c[a] or "" for a in ("il", "ilce", "mahalle", "sokak", "bina")).rstrip("/")
    return "|".join([KISA[c["tur"]] + str(c["aciliyet"]), str(c["kisi"] or ""), yer,
                     ",".join(c["ozel"]), c["ad"] or "", c["tel"] or ""]).rstrip("|")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--egitim", type=int, default=1_000_000)
    a = ap.parse_args()
    # bölüm: (adet, test_mi, gürültü şiddeti aralığı)
    BOLUM = {"egitim": (a.egitim, False, (0.0, 1.0)), "dogrulama": (10_000, False, (0.0, 1.0)),
             "test": (10_000, True, (0.0, 1.0)), "test_agir_gurultu": (5_000, True, (0.9, 1.0))}
    cikis = Path(__file__).parent / "veri"
    cikis.mkdir(exist_ok=True)
    r = random.Random(TOHUM)
    gorulen = set()
    for bolum, (n, test, (lo, hi)) in BOLUM.items():
        say = {}
        with open(cikis / (bolum + ".jsonl"), "w", encoding="utf-8") as f:
            for _ in range(n):
                o = ornek(r, test, r.uniform(lo, hi))
                denetle(o)
                if bolum == "egitim":
                    gorulen.add(o["girdi"])
                elif o["girdi"] in gorulen:
                    continue  # birebir eğitimde görülen girdi değerlendirmeye girmez
                say[o["cikti"]["tur"]] = say.get(o["cikti"]["tur"], 0) + 1
                f.write(json.dumps(o, ensure_ascii=False) + "\n")
        print("%-18s %8d örnek  türler %s" % (bolum, sum(say.values()), dict(sorted(say.items()))))
    assert not (set(MAHALLE[0]) | set(SOKAK[0]) | set(AD[0]) | set(BINA[0])) & \
        (set(MAHALLE[1]) | set(SOKAK[1]) | set(AD[1]) | set(BINA[1])), "test adları eğitimle çakışıyor"
    print("öz-denetim tamam")


if __name__ == "__main__":
    main()
