"""Triyaj ölçümü: tahmin dosyasını etiketlerle karşılaştırır.

    python degerlendir.py veri/test.jsonl tahmin.jsonl
    python degerlendir.py veri/test.jsonl --kural        # kural tabanlı taban çizgisi

Tahmin dosyası: girdiyle aynı sırada, her satırda bir `cikti` sözlüğü (JSON).

Asıl ölçüt güvenlik ölçütüdür: aciliyeti 1-2 olan mesajı 3-4 diye kaçırmak
(yanlış negatif) bir hayata mal olabilir; tersi yalnız fazladan iş çıkarır.
Bu yüzden `acil_kacirma` ayrı raporlanır ve modelin hedefi önce onu sıfıra
yaklaştırmaktır.
"""
import json
import re
import sys

ALANLAR: tuple = ("tur", "aciliyet", "kisi", "il", "ilce", "mahalle", "sokak", "bina", "ad", "tel")
ASCII = str.maketrans("çğıöşüÇĞİÖŞÜâÂîû", "cgiosuCGIOSUaAiu")


def norm(v):
    """Biçim farkı (büyük/küçük harf, aksan, fazla boşluk) hata sayılmaz."""
    if v is None:
        return None
    v = str(v).replace("İ", "i").replace("I", "ı").lower().translate(ASCII)
    return " ".join(v.split())


def olc(gercek, tahmin):
    n = len(gercek)
    assert n == len(tahmin), "satır sayısı tutmuyor"
    dogru = {a: 0 for a in ALANLAR}
    ozel_tp = ozel_fp = ozel_fn = 0
    acil = acil_kacirma = 0
    tam = 0
    for g, t in zip(gercek, tahmin):
        hepsi = True
        for a in ALANLAR:
            ok = norm(g.get(a)) == norm(t.get(a))
            dogru[a] += ok
            hepsi &= ok
        gs, ts = set(g.get("ozel") or []), set(t.get("ozel") or [])
        ozel_tp += len(gs & ts)
        ozel_fp += len(ts - gs)
        ozel_fn += len(gs - ts)
        hepsi &= gs == ts
        tam += hepsi
        if g["aciliyet"] <= 2:
            acil += 1
            acil_kacirma += (t.get("aciliyet") or 4) > 2
    p = ozel_tp / max(1, ozel_tp + ozel_fp)
    r = ozel_tp / max(1, ozel_tp + ozel_fn)
    return {
        "ornek": n,
        "tam_eslesme": round(tam / n, 4),
        "acil_kacirma": round(acil_kacirma / max(1, acil), 4),
        **{a: round(dogru[a] / n, 4) for a in ALANLAR},
        "ozel_f1": round(2 * p * r / max(1e-9, p + r), 4),
    }


# ------------------------------------------------- kural tabanlı taban çizgisi --
from uret import ILCELER, KISA, OZEL, SAYI_YAZI, TUR  # noqa: E402

ANAHTAR = {  # tür -> anahtar kök (aksansız, küçük harf)
    "enkaz": ["enkaz", "gocuk", "yikil", "cokt", "moloz", "mahsur kal", "kat cok"],
    "yangin": ["yangin", "yaniyor", "duman", "trafo"],
    "saglik": ["ambulans", "yarali", "doktor", "insulin", "diyaliz", "kalp", "dogum", "bayil", "solunum", "ilac"],
    "barinma": ["cadir", "battaniye", "isitici", "acikta", "disaridayiz", "arabada yat", "giremiyoruz"],
    "gida_su": ["su ", "suyu", "yemek", "mama", "ekmek", "gida"],
    "kayip": ["haber alamiyoruz", "kayip", "ariyorum", "hayatta mi", "bilen ulas", "bilgi lazim", "goren var"],
    "lojistik": ["vinc", "kepce", "jenerator", "is makinesi", "projektor", "akaryakit", "hilti", "kirici"],
    "guvende": ["iyiyiz", "guvende", "iyiyim", "sag salim", "kurtarildik", "saglig"],
    "bilgi": ["yolu kapali", "toplanma alani", "eczane", "dagitim", "hasarli", "dikkat"],
}


def kural(girdi):
    g = " " + girdi.replace("İ", "i").replace("I", "ı").lower().translate(ASCII) + " "
    tur = "alakasiz"
    for t in ("enkaz", "yangin", "saglik", "kayip", "lojistik", "barinma", "gida_su", "guvende", "bilgi"):
        if any(k in g for k in ANAHTAR[t]):
            tur = t
            break
    temel = {t: a for t, a, _, _ in TUR}
    c: dict = {a: None for a in ALANLAR}
    c["tur"], c["aciliyet"] = tur, temel[tur]
    m = re.search(r"(\d+)\s*(kisi|ks|aile|yarali)", g)
    if m:
        c["kisi"] = int(m.group(1))
    else:
        for n, y in sorted(SAYI_YAZI.items(), key=lambda x: -len(x[1])):
            if " %s kisi" % y.translate(ASCII) in g:
                c["kisi"] = n
                break
    for il, ilceler in ILCELER.items():
        if norm(il) in g:
            c["il"] = il
            for ilce in ilceler:
                if " " + norm(ilce) in g:
                    c["ilce"] = ilce
            break
    tel = re.search(r"0?5\d{2}\s?\d{3}\s?\d{2}\s?\d{2}", girdi)
    if tel:
        c["tel"] = "0" + re.sub(r"\D", "", tel.group()).lstrip("0")
    c["ozel"] = sorted(o for o, ifadeler in OZEL.items()
                       if any(norm(i).split(" ")[0] in g for i in ifadeler))
    if c["aciliyet"] > 1 and tur not in ("guvende", "bilgi", "alakasiz") and set(c["ozel"]) & {"bebek", "yarali", "hamile"}:
        c["aciliyet"] -= 1
    assert c["tur"] in KISA
    return c


def main():
    kaynak = [json.loads(s) for s in open(sys.argv[1], encoding="utf-8")]
    gercek = [o["cikti"] for o in kaynak]
    if sys.argv[2] == "--kural":
        tahmin = [kural(o["girdi"]) for o in kaynak]
    else:
        tahmin = [json.loads(s) for s in open(sys.argv[2], encoding="utf-8")]
    print(json.dumps(olc(gercek, tahmin), ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
