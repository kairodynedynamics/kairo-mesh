"""Telefonda çalışan triyaj modeli: karakter düzeyinde, küçük, bozuk yazıma dayanıklı.

    python model.py egit                 # veri/ -> model/triyaj.pt (CPU)
    python model.py olc  veri/test.jsonl # tahmin + degerlendir.olc
    python model.py aktar                # model/triyaj.onnx + model/triyaj-int8.onnx

Mimari: karakter gömme + büyük/küçük harf özelliği -> 2 katmanlı çift yönlü GRU.
- Her karaktere BIO etiketi: il, ilçe, mahalle, sokak, bina, ad, kişi, tel aralıkları
  (adres ve ad metinden KESİLİR, uydurulmaz).
- Havuzlanmış temsilden: olay türü, aciliyet, özel durumlar (çok etiketli),
  kanonik il ve il/ilçe çifti.

Güvenlik kuralı (çıkarımda): aciliyet = min(model, kural(tür, özel durum)). Model
bir çağrıyı kuraldan daha az acil sayamaz; kaçırma ancak türü yanlış bulursa olur.
"""
import json
import math
import os
import re
import sys
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

sys.path.insert(0, str(Path(__file__).parent))
from uret import ILCELER, KISA, OZEL, SAYI_YAZI, TUR  # noqa: E402

KOK = Path(__file__).parent
VERI, MODEL = KOK / "veri", KOK / "model"
UZUN = 192
ALANLAR = ["il", "ilce", "mahalle", "sokak", "bina", "ad", "kisi", "tel"]
ETIKET = ["O"] + [f"{o}-{a}" for a in ALANLAR for o in ("B", "I")]
TURLER = list(KISA)
OZELLER = list(OZEL)
ILLER = list(ILCELER)
CIFTLER = [f"{il}/{ilce}" for il, ilceler in ILCELER.items() for ilce in ilceler]
TEMEL = {t: a for t, a, _, _ in TUR}
ASCII = str.maketrans("çğıöşüÇĞİÖŞÜâÂîû", "cgiosuCGIOSUaAiu")


def kucuk(ch):
    return "i" if ch == "İ" else "ı" if ch == "I" else ch.lower()[:1] or ch


def harf_turu(ch):
    return 2 if ch.isupper() else 1 if ch.isalpha() else 0


def kural_aciliyet(tur, ozel):
    a = TEMEL[tur]
    if a > 1 and tur not in ("guvende", "bilgi", "alakasiz") and set(ozel) & {"bebek", "yarali", "hamile"}:
        a -= 1
    return a


class Sozluk:
    def __init__(self, harfler):
        self.harfler = harfler
        self.idx = {c: i + 2 for i, c in enumerate(harfler)}  # 0 dolgu, 1 bilinmeyen

    def kodla(self, metin):
        m = metin[:UZUN]
        return [self.idx.get(kucuk(c), 1) for c in m], [harf_turu(c) for c in m]


class Triyaj(nn.Module):
    def __init__(self, v, gomme=96, gizli=160):
        super().__init__()
        self.harf = nn.Embedding(v, gomme, padding_idx=0)
        self.tip = nn.Embedding(3, 16)
        self.gru = nn.GRU(gomme + 16, gizli, num_layers=2, batch_first=True, bidirectional=True, dropout=0.1)
        h = 2 * gizli
        self.etiket = nn.Linear(h, len(ETIKET))
        self.tur = nn.Linear(2 * h, len(TURLER))
        self.aciliyet = nn.Linear(2 * h, 4)
        self.ozel = nn.Linear(2 * h, len(OZELLER))
        self.il = nn.Linear(2 * h, len(ILLER) + 1)
        self.ilce = nn.Linear(2 * h, len(CIFTLER) + 1)

    def forward(self, harf, tip):
        maske = (harf > 0).unsqueeze(-1).float()
        x = torch.cat([self.harf(harf), self.tip(tip)], -1)
        y, _ = self.gru(x)
        ort = (y * maske).sum(1) / maske.sum(1).clamp(min=1)
        enb = (y - (1 - maske) * 1e4).max(1).values
        p = torch.cat([ort, enb], -1)
        return self.etiket(y), self.tur(p), self.aciliyet(p), self.ozel(p), self.il(p), self.ilce(p)


def ornekler(yol):
    with open(yol, encoding="utf-8") as f:
        for s in f:
            yield json.loads(s)


def hedefler(o, sz):
    harf, tip = sz.kodla(o["girdi"])
    et = [0] * len(harf)
    for alan, (x, y) in o.get("aralik", {}).items():
        if alan not in ALANLAR or x >= UZUN:
            continue
        b = 1 + 2 * ALANLAR.index(alan)
        for i in range(x, min(y, UZUN)):
            et[i] = b if i == x else b + 1
    c = o["cikti"]
    il = ILLER.index(c["il"]) + 1 if c["il"] else 0
    ilce = CIFTLER.index(f'{c["il"]}/{c["ilce"]}') + 1 if c["il"] and c["ilce"] else 0
    ozel = [1 if x in c["ozel"] else 0 for x in OZELLER]
    return harf, tip, et, TURLER.index(c["tur"]), c["aciliyet"] - 1, ozel, il, ilce


def onbellek(bolum, sz):
    yol = VERI / f"{bolum}.npz"
    if yol.exists():
        return dict(np.load(yol))
    n = sum(1 for _ in open(VERI / f"{bolum}.jsonl", encoding="utf-8"))
    H = np.zeros((n, UZUN), np.uint16); T = np.zeros((n, UZUN), np.uint8); E = np.zeros((n, UZUN), np.uint8)
    Y = np.zeros((n, 5), np.int16); O = np.zeros((n, len(OZELLER)), np.uint8)
    for i, o in enumerate(ornekler(VERI / f"{bolum}.jsonl")):
        harf, tip, et, tur, ac, ozel, il, ilce = hedefler(o, sz)
        H[i, :len(harf)] = harf; T[i, :len(tip)] = tip; E[i, :len(et)] = et
        Y[i] = (tur, ac, il, ilce, len(harf)); O[i] = ozel
    d = {"H": H, "T": T, "E": E, "Y": Y, "O": O}
    np.savez(yol, **d)
    return d


def sozluk_kur():
    yol = MODEL / "sozluk.json"
    if yol.exists():
        return Sozluk(json.loads(yol.read_text(encoding="utf-8")))
    sayac = {}
    for i, o in enumerate(ornekler(VERI / "egitim.jsonl")):
        for ch in o["girdi"]:
            k = kucuk(ch)
            sayac[k] = sayac.get(k, 0) + 1
        if i > 300_000:
            break
    harfler = sorted(c for c, n in sayac.items() if n >= 20)
    MODEL.mkdir(exist_ok=True)
    yol.write_text(json.dumps(harfler, ensure_ascii=False), encoding="utf-8")
    return Sozluk(harfler)


def parti(d, idx):
    L = int(d["Y"][idx, 4].max())
    t = lambda a: torch.from_numpy(a[idx, :L].astype(np.int64))  # noqa: E731
    y = torch.from_numpy(d["Y"][idx].astype(np.int64))
    return t(d["H"]), t(d["T"]), t(d["E"]), y[:, 0], y[:, 1], torch.from_numpy(d["O"][idx].astype(np.float32)), y[:, 2], y[:, 3]


# Aciliyet kaybında asimetri: gerçekte 1-2 olanı 3-4 sanmak, tersinden 4 kat pahalı.
ACIL_CEZA = torch.tensor([[0, 1, 4, 4], [1, 0, 4, 4], [1, 1, 0, 1], [1, 1, 1, 0]], dtype=torch.float32)


def kayip(m, b):
    harf, tip, et, tur, ac, ozel, il, ilce = b
    e, t, a, o, i, c = m(harf, tip)
    maske = harf > 0
    l_et = F.cross_entropy(e[maske], et[maske])
    l_ac = F.cross_entropy(a, ac) + 0.5 * (F.softmax(a, -1) * ACIL_CEZA[ac]).sum(-1).mean()
    return (l_et + F.cross_entropy(t, tur) + l_ac + F.binary_cross_entropy_with_logits(o, ozel)
            + 0.5 * F.cross_entropy(i, il) + 0.5 * F.cross_entropy(c, ilce))


def egit():
    torch.set_num_threads(int(os.environ.get("TRIYAJ_IS", "6")))
    torch.manual_seed(0)
    sz = sozluk_kur()
    d, dv = onbellek("egitim", sz), onbellek("dogrulama", sz)
    m = Triyaj(len(sz.harfler) + 2)
    print("parametre", sum(p.numel() for p in m.parameters()), "örnek", len(d["H"]), flush=True)
    opt = torch.optim.AdamW(m.parameters(), lr=2e-3, weight_decay=0.01)
    B, DONEM = 256, int(os.environ.get("TRIYAJ_DONEM", "2"))
    adim_say = DONEM * len(d["H"]) // B
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=2e-3, total_steps=adim_say, pct_start=0.05)
    rng = np.random.default_rng(0)
    adim, t0, en_iyi = 0, time.time(), math.inf
    for donem in range(DONEM):
        sira = rng.permutation(len(d["H"]))
        # benzer uzunlukları bir araya koy: dolgu azalır, CPU'da 2 kat hız
        parcalar = [sira[i:i + B * 50] for i in range(0, len(sira), B * 50)]
        for p in parcalar:
            p = p[np.argsort(d["Y"][p, 4])]
            for i in rng.permutation(len(p) // B):
                m.train()
                l = kayip(m, parti(d, p[i * B:(i + 1) * B]))
                opt.zero_grad(); l.backward(); nn.utils.clip_grad_norm_(m.parameters(), 1.0); opt.step(); sched.step()
                adim += 1
                if adim % 500 == 0:
                    m.eval()
                    with torch.no_grad():
                        lv = float(np.mean([kayip(m, parti(dv, np.arange(j, min(j + 512, len(dv["H"]))))).item()
                                            for j in range(0, len(dv["H"]), 512)]))
                    print(f"donem {donem} adim {adim}/{adim_say} kayip {l.item():.4f} dogrulama {lv:.4f} "
                          f"{(time.time() - t0) / 60:.1f} dk", flush=True)
                    if lv < en_iyi:
                        en_iyi = lv
                        torch.save(m.state_dict(), MODEL / "triyaj.pt")
    print("bitti, en iyi dogrulama", en_iyi, flush=True)


SAYI = {**{v: k for k, v in SAYI_YAZI.items()}, **{v.translate(ASCII): k for k, v in SAYI_YAZI.items()}}


def coz(metin, cikti, sz=None):
    """Model çıktısını kayda çevir (tek örnek, numpy dizileri)."""
    e, t, a, o, i, c = cikti
    etiketler = e.argmax(-1)
    aralik = {}
    j = 0
    while j < len(etiketler):
        k = int(etiketler[j])
        if k and k % 2 == 1:
            alan = ALANLAR[(k - 1) // 2]
            s = j
            j += 1
            while j < len(etiketler) and etiketler[j] == k + 1:
                j += 1
            aralik.setdefault(alan, (s, j))
        else:
            j += 1
    r = {x: None for x in ("il", "ilce", "mahalle", "sokak", "bina", "ad", "kisi", "tel")}
    for alan in ("mahalle", "sokak", "bina", "ad"):
        if alan in aralik:
            r[alan] = metin[slice(*aralik[alan])].strip()
    if "kisi" in aralik:
        k = metin[slice(*aralik["kisi"])].strip()
        kn = k.replace("İ", "i").replace("I", "ı").lower()
        r["kisi"] = int(k) if k.isdigit() else SAYI.get(kn, SAYI.get(kn.translate(ASCII)))
    tel = re.search(r"0?5\d{2}\s?\d{3}\s?\d{2}\s?\d{2}", metin)  # telefon kalıbı kesin: kural yeter
    if tel:
        r["tel"] = "0" + re.sub(r"\D", "", tel.group()).lstrip("0")
    il_i, ilce_i = int(i.argmax()), int(c.argmax())
    r["il"] = ILLER[il_i - 1] if il_i else None
    if ilce_i:
        il2, ilce = CIFTLER[ilce_i - 1].split("/")
        if r["il"] in (None, il2):
            r["il"], r["ilce"] = il2, ilce
    tur = TURLER[int(t.argmax())]
    ozel = sorted(OZELLER[k] for k in np.nonzero(o > 0)[0])
    r.update(tur=tur, ozel=ozel, aciliyet=min(int(a.argmax()) + 1, kural_aciliyet(tur, ozel)))
    return r


def olc(yol):
    sys.path.insert(0, str(KOK))
    from degerlendir import olc as puanla
    sz = sozluk_kur()
    m = Triyaj(len(sz.harfler) + 2)
    m.load_state_dict(torch.load(MODEL / "triyaj.pt"))
    m.eval()
    veri = list(ornekler(yol))
    tahmin = []
    t0 = time.time()
    with torch.no_grad():
        for o in veri:
            h, tp = sz.kodla(o["girdi"])
            cik = [x[0].numpy() for x in m(torch.tensor([h]), torch.tensor([tp]))]
            tahmin.append(coz(o["girdi"], cik))
    sn = (time.time() - t0) / len(veri) * 1000
    sonuc = puanla([o["cikti"] for o in veri], tahmin)
    sonuc["ms_mesaj_basina_cpu_tek_cekirdek_degil"] = round(sn, 2)
    print(json.dumps(sonuc, ensure_ascii=False, indent=1))
    return sonuc


def aktar():
    sz = sozluk_kur()
    m = Triyaj(len(sz.harfler) + 2)
    m.load_state_dict(torch.load(MODEL / "triyaj.pt"))
    m.eval()
    h, tp = sz.kodla("hatay antakya enkaz altındayız 3 kişi")
    torch.onnx.export(m, (torch.tensor([h]), torch.tensor([tp])), MODEL / "triyaj.onnx",
                      input_names=["harf", "tip"], output_names=["etiket", "tur", "aciliyet", "ozel", "il", "ilce"],
                      dynamic_axes={"harf": {0: "n", 1: "uzun"}, "tip": {0: "n", 1: "uzun"}, "etiket": {0: "n", 1: "uzun"}},
                      opset_version=17, dynamo=False)
    from onnxruntime.quantization import QuantType, quantize_dynamic
    quantize_dynamic(str(MODEL / "triyaj.onnx"), str(MODEL / "triyaj-int8.onnx"), weight_type=QuantType.QInt8)
    for f in ("triyaj.onnx", "triyaj-int8.onnx"):
        print(f, round((MODEL / f).stat().st_size / 1e6, 2), "MB")


if __name__ == "__main__":
    {"egit": egit, "olc": lambda: olc(sys.argv[2]), "aktar": aktar}[sys.argv[1]]()
