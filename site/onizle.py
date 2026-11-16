"""Sitenin kaydırma durumlarını gerçek tarayıcıda görüntüler (Playwright + Chromium).

    python site/yap.py && python site/onizle.py [çıktı_klasörü]

site/cikti'yi yerel bir sunucudan açar, sayfayı gerçekten kaydırır, sönümlü hareketin oturmasını bekler ve
her durumun ekran görüntüsünü alır. Ayrıca "hareketi azalt" ve dar ekran (390 px) görünümlerini çeker,
yatay taşma ve konsol hatası varsa bildirir.
"""
import http.server
import sys
import threading
from functools import partial
from pathlib import Path

from playwright.sync_api import sync_playwright

CIKTI = Path(__file__).resolve().parent / "cikti"
HEDEF = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent / "_onizleme"

# (ad, sayfa, hedef seçici, seçicinin üstünden kaç ekran aşağı)
DURUMLAR = [
    ("01-acilis", "index.html", None, 0),
    ("02-sorun", "index.html", "#sorun", -0.05),
    ("03-yazilir", "index.html", "#yolculuk", 0.5),
    ("04-muhurlenir", "index.html", "#yolculuk", 1.5),
    ("05-tasinir", "index.html", "#yolculuk", 2.4),
    ("06-iletilir", "index.html", "#yolculuk", 3.25),
    ("06b-uygulama", "index.html", "#uygulama", -0.02),
    ("07-olcum", "index.html", "#olcum", -0.02),
    ("08-triyaj", "index.html", "#triyaj", -0.05),
    ("09-guvenlik", "index.html", ".bento", -0.3),
    ("10-dogrulama", "dogrulama.html", None, 0),
    ("11-simulasyon", "simulasyon.html", None, 0),
]


def main():
    HEDEF.mkdir(exist_ok=True)
    sunucu = http.server.ThreadingHTTPServer(("127.0.0.1", 0),
                                             partial(http.server.SimpleHTTPRequestHandler, directory=str(CIKTI)))
    threading.Thread(target=sunucu.serve_forever, daemon=True).start()
    kok = f"http://127.0.0.1:{sunucu.server_port}/"
    sorunlar = []
    with sync_playwright() as p:
        tarayici = p.chromium.launch()
        for kip, boyut, azalt in (("masaustu", {"width": 1440, "height": 900}, False),
                                  ("telefon", {"width": 390, "height": 844}, False),
                                  ("azalt", {"width": 1440, "height": 900}, True)):
            ctx = tarayici.new_context(viewport=boyut, reduced_motion="reduce" if azalt else "no-preference",
                                       device_scale_factor=1)
            sayfa = ctx.new_page()
            sayfa.on("console", lambda m: m.type == "error" and sorunlar.append(f"{kip} konsol: {m.text}"))
            sayfa.on("pageerror", lambda e: sorunlar.append(f"{kip} hata: {e}"))
            for ad, yol, secici, dy in DURUMLAR:
                if kip != "masaustu" and ad not in ("01-acilis", "04-muhurlenir", "06b-uygulama", "07-olcum"):
                    continue
                sayfa.goto(kok + yol)
                sayfa.wait_for_timeout(1200)
                if secici:
                    y = sayfa.evaluate("([s, dy]) => { const e = document.querySelector(s);"
                                       " return e.getBoundingClientRect().top + scrollY + dy * innerHeight; }", [secici, dy])
                    # gerçek kullanıcı gibi adım adım kaydır (görününce açılan öğeler tetiklensin)
                    for ara in range(1, 9):
                        sayfa.evaluate("y => scrollTo(0, y)", y * ara / 8)
                        sayfa.wait_for_timeout(120)
                    sayfa.wait_for_timeout(1800)
                genislik = sayfa.evaluate("document.documentElement.scrollWidth - innerWidth")
                if genislik > 0:
                    sorunlar.append(f"{kip}/{ad}: yatay taşma {genislik}px")
                sayfa.screenshot(path=str(HEDEF / f"{kip}-{ad}.png"))
            ctx.close()
        tarayici.close()
    sunucu.shutdown()
    print("\n".join(sorunlar) or "sorun yok")
    print("görüntüler:", HEDEF)


if __name__ == "__main__":
    main()
