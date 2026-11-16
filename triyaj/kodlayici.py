"""Önceden eğitilmiş Türkçe kodlayıcıyla (ELECTRA-small, dbmdz, MIT) olay türü / aciliyet / özel durum / il / ilçe.

Neden: harf düzeyindeki küçük model (model.py) adres ve isim kesmede çok iyi, ama eğitimde hiç geçmeyen bir
kelimenin anlamını bilemiyor ("harabenin altındayız" -> enkaz). Büyük Türkçe metinle önceden eğitilmiş bir
kodlayıcı kelime anlamını zaten taşır. Adres aralıkları harf modelinde kalır; bu model yalnız mesaj düzeyindeki
kararları verir.

    python kodlayici.py egit          # KOD_ORNEK (200000), KOD_ADIM, KOD_IS (iş parçacığı)
    python kodlayici.py olc           # test ve test_agir_gurultu: tür, aciliyet, hayati kaçırma, il, ilçe, özel F1
"""
import json
import os
import sys
import time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from transformers import ElectraModel, ElectraTokenizerFast

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model import (CIFTLER, ILLER, MODEL, OZELLER, TURLER, VERI, ACIL_CEZA, kural_aciliyet,  # noqa: E402
                   ornekler)

ONEGITIM = os.path.join(os.path.dirname(os.path.abspath(__file__)), "onegitim", "electra-small-turkish-cased-discriminator")
UZUN = 64
torch.set_num_threads(int(os.environ.get("KOD_IS", "8")))


class Kodlayici(nn.Module):
    def __init__(self):
        super().__init__()
        self.govde = ElectraModel.from_pretrained(ONEGITIM)
        h = self.govde.config.hidden_size
        self.birak = nn.Dropout(0.1)
        self.tur = nn.Linear(2 * h, len(TURLER))
        self.aciliyet = nn.Linear(2 * h, 4)
        self.ozel = nn.Linear(2 * h, len(OZELLER))
        self.il = nn.Linear(2 * h, len(ILLER) + 1)
        self.ilce = nn.Linear(2 * h, len(CIFTLER) + 1)

    def forward(self, ids, maske):
        y = self.govde(input_ids=ids, attention_mask=maske).last_hidden_state
        m = maske.unsqueeze(-1).float()
        p = self.birak(torch.cat([y[:, 0], (y * m).sum(1) / m.sum(1).clamp(min=1)], -1))
        return self.tur(p), self.aciliyet(p), self.ozel(p), self.il(p), self.ilce(p)


def etiketler(c):
    il = ILLER.index(c["il"]) + 1 if c["il"] else 0
    ilce = CIFTLER.index(f'{c["il"]}/{c["ilce"]}') + 1 if c["il"] and c["ilce"] else 0
    return TURLER.index(c["tur"]), c["aciliyet"] - 1, [1.0 if x in c["ozel"] else 0.0 for x in OZELLER], il, ilce


def kume(yol, n=None):
    veri = []
    for i, o in enumerate(ornekler(yol)):
        if n and i >= n:
            break
        veri.append((o["girdi"], etiketler(o["cikti"]), o["cikti"]))
    return veri


def parti(tok, ornek):
    k = tok([g for g, _, _ in ornek], truncation=True, max_length=UZUN, padding=True, return_tensors="pt")
    e = list(zip(*[x for _, x, _ in ornek]))
    return (k["input_ids"], k["attention_mask"], torch.tensor(e[0]), torch.tensor(e[1]),
            torch.tensor(e[2]), torch.tensor(e[3]), torch.tensor(e[4]))


def kayip(m, b):
    ids, maske, tur, ac, ozel, il, ilce = b
    t, a, o, i, c = m(ids, maske)
    l_ac = F.cross_entropy(a, ac) + 0.5 * (F.softmax(a, -1) * ACIL_CEZA[ac]).sum(-1).mean()
    return (F.cross_entropy(t, tur) + l_ac + F.binary_cross_entropy_with_logits(o, ozel)
            + 0.5 * F.cross_entropy(i, il) + 0.5 * F.cross_entropy(c, ilce))


def egit():
    torch.manual_seed(0)
    tok = ElectraTokenizerFast.from_pretrained(ONEGITIM)
    veri = kume(VERI / "egitim.jsonl", int(os.environ.get("KOD_ORNEK", "200000")))
    m = Kodlayici()
    B = 32
    adim_say = int(os.environ.get("KOD_ADIM", str(len(veri) // B)))
    govde = [p for n, p in m.named_parameters() if n.startswith("govde.")]
    bas = [p for n, p in m.named_parameters() if not n.startswith("govde.")]
    opt = torch.optim.AdamW([{"params": govde, "lr": 1e-4}, {"params": bas, "lr": 1e-3}], weight_decay=0.01)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=[1e-4, 1e-3], total_steps=adim_say, pct_start=0.06)
    rng = np.random.default_rng(0)
    t0 = time.time()
    for adim in range(1, adim_say + 1):
        idx = rng.integers(0, len(veri), B)
        m.train()
        l = kayip(m, parti(tok, [veri[j] for j in idx]))
        opt.zero_grad(); l.backward(); nn.utils.clip_grad_norm_(m.parameters(), 1.0); opt.step(); sched.step()
        if adim % 200 == 0:
            print(f"adim {adim}/{adim_say} kayip {l.item():.4f} {(time.time() - t0) / 60:.1f} dk", flush=True)
    torch.save(m.state_dict(), MODEL / "kodlayici.pt")
    print("kaydedildi", flush=True)


@torch.no_grad()
def tahmin(m, tok, metinler):
    m.eval()
    cik = []
    for j in range(0, len(metinler), 64):
        k = tok(metinler[j:j + 64], truncation=True, max_length=UZUN, padding=True, return_tensors="pt")
        cik.append([x.numpy() for x in m(k["input_ids"], k["attention_mask"])])
    return [np.concatenate([c[i] for c in cik]) for i in range(5)]


def olc():
    tok = ElectraTokenizerFast.from_pretrained(ONEGITIM)
    m = Kodlayici()
    m.load_state_dict(torch.load(MODEL / "kodlayici.pt"))
    sonuc = {}
    for ad in ("test", "test_agir_gurultu"):
        veri = kume(VERI / f"{ad}.jsonl")
        t0 = time.time()
        t, a, o, i, c = tahmin(m, tok, [g for g, _, _ in veri])
        ms = (time.time() - t0) / len(veri) * 1000
        n = len(veri); d = {"tur": 0, "aciliyet": 0, "il": 0, "ilce": 0}
        acil = kacir = sakin = fazla = 0
        tp = fp = fn = 0
        for k, (_, (tu, ac, oz, il, ilce), cikti) in enumerate(veri):
            tt = int(t[k].argmax()); oo = [OZELLER[x] for x in np.nonzero(o[k] > 0)[0]]
            aa = min(int(a[k].argmax()) + 1, kural_aciliyet(TURLER[tt], oo))
            d["tur"] += tt == tu; d["aciliyet"] += aa == ac + 1
            d["il"] += int(i[k].argmax()) == il
            # ilçe: yalnız tahmin edilen ile ait ilçeler arasından (Gemini önerisi; ücretsiz)
            ci = c[k].copy()
            il_ad = ILLER[int(i[k].argmax()) - 1] if int(i[k].argmax()) else None
            if il_ad:
                for x in range(1, len(CIFTLER) + 1):
                    if not CIFTLER[x - 1].startswith(il_ad + "/"):
                        ci[x] = -1e9
            d["ilce"] += int(ci.argmax()) == ilce
            if ac + 1 <= 2:
                acil += 1; kacir += aa > 2
            else:
                sakin += 1; fazla += aa <= 2
            gs, ts = set(cikti["ozel"]), set(oo)
            tp += len(gs & ts); fp += len(ts - gs); fn += len(gs - ts)
        p, r = tp / max(1, tp + fp), tp / max(1, tp + fn)
        sonuc[ad] = {"ornek": n, **{x: round(v / n, 4) for x, v in d.items()}, "acil_kacirma": round(kacir / max(1, acil), 4),
                     "fazla_acil": round(fazla / max(1, sakin), 4), "ozel_f1": round(2 * p * r / max(1e-9, p + r), 4), "ms": round(ms, 2)}
        print(ad, sonuc[ad], flush=True)
    (MODEL / "kodlayici-olcum.json").write_text(json.dumps(sonuc, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    {"egit": egit, "olc": olc}[sys.argv[1]]()
