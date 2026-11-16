"""KairoMesh marka dosyaları: tek kaynaktan logo, uygulama simgeleri, site simgeleri ve paylaşım görseli.

    python site/marka/uret.py

İşaret: enkaz altındaki telefondan (kırmızı) çıkan çağrı, taşıyıcı telefonlardan geçip internete ulaşan telefona
(yeşil) atlar. Koyu arduvaz zemin her ana ekranda seçilir. Yazı biçimi: "Kairo" mürekkep, "Mesh" kırmızı, bitişik.
Çıktılar: site/marka/*.svg|png, site/statik/ (favicon, logo, OG), Android mipmap, iOS AppIcon.
"""
from pathlib import Path

from playwright.sync_api import sync_playwright

KOK = Path(__file__).resolve().parents[2]
MARKA = KOK / "site" / "marka"
STATIK = KOK / "site" / "statik"
ANDROID_RES = KOK / "android" / "app" / "src" / "main" / "res"
IOS_IKON = KOK / "ios" / "App" / "Assets.xcassets" / "AppIcon.appiconset"
FONT = (STATIK / "fonts" / "inter-latin.woff2").as_uri()
FONT2 = (STATIK / "fonts" / "inter-latinext.woff2").as_uri()

KIRMIZI, YESIL, MUREKKEP = "#e5483d", "#3fd19b", "#14202b"

# 512 x 512 işaret. Düğümler: çağıran (kırmızı), üç taşıyıcı, internetli telefon (yeşil); en üstte iletim yayları.
ISARET = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
  <defs>
    <linearGradient id="z" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2b3b46"/><stop offset="1" stop-color="#121c22"/></linearGradient>
    <linearGradient id="p" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffffff" stop-opacity=".16"/><stop offset=".5" stop-color="#ffffff" stop-opacity="0"/></linearGradient>
  </defs>
  <rect width="512" height="512" rx="114" fill="url(#z)"/>
  <rect width="512" height="512" rx="114" fill="url(#p)"/>
  <g fill="none" stroke-linecap="round" stroke-linejoin="round">
    <path d="M132 382 L214 286 L300 318 L376 196" stroke="#ffffff" stroke-opacity=".22" stroke-width="30"/>
    <path d="M132 382 L214 286 L300 318 L376 196" stroke="#ffffff" stroke-opacity=".9" stroke-width="12"/>
    <path d="M214 286 L282 214 M300 318 L382 336" stroke="#ffffff" stroke-opacity=".28" stroke-width="10"/>
    <path d="M352 136 A 48 48 0 0 1 424 152 M336 104 A 82 82 0 0 1 452 128" stroke="{YESIL}" stroke-width="14"/>
  </g>
  <circle cx="132" cy="382" r="58" fill="{KIRMIZI}" fill-opacity=".22"/>
  <circle cx="132" cy="382" r="34" fill="{KIRMIZI}"/>
  <circle cx="214" cy="286" r="22" fill="#eef3f6"/>
  <circle cx="300" cy="318" r="22" fill="#eef3f6"/>
  <circle cx="282" cy="214" r="13" fill="#eef3f6" fill-opacity=".45"/>
  <circle cx="382" cy="336" r="13" fill="#eef3f6" fill-opacity=".45"/>
  <circle cx="376" cy="196" r="30" fill="{YESIL}"/>
</svg>"""

YAZI = f"""@font-face {{ font-family: Inter; font-weight: 400 800; src: url({FONT}) format("woff2"); }}
@font-face {{ font-family: Inter; font-weight: 400 800; src: url({FONT2}) format("woff2");
  unicode-range: U+0100-02BA, U+02BD-02C5, U+02C7-02CC, U+02CE-02D7, U+02DD-02FF; }}
html, body {{ margin: 0; background: transparent; }}"""


def logo_yatay(acik=False):
    """İşaret + yazı biçimi (yatay). acik: koyu zemin üstü için açık renkli yazı."""
    renk = "#eef4f6" if acik else MUREKKEP
    isaret = ISARET.replace('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">', '<svg x="0" y="0" width="120" height="120" viewBox="0 0 512 512">')
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 560 120">{isaret}
  <text x="142" y="84" font-family="Inter, Segoe UI, sans-serif" font-size="72" font-weight="800" letter-spacing="-2.5">
    <tspan fill="{renk}">Kairo</tspan><tspan fill="{KIRMIZI if not acik else '#ff6b5e'}">Mesh</tspan></text></svg>"""


def og():
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>{YAZI}
body {{ width: 1200px; height: 630px; overflow: hidden; font-family: Inter, sans-serif;
  background: radial-gradient(ellipse 80% 90% at 20% 30%, #1d2b34, #091116); color: #eef4f6; }}
.ic {{ position: absolute; left: 80px; top: 92px; width: 1040px; }}
.logo {{ width: 470px; }}
h1 {{ font-size: 64px; line-height: 1.08; letter-spacing: -2px; margin: 46px 0 22px; font-weight: 800; max-width: 980px; }}
p {{ font-size: 28px; color: #c2d0d7; margin: 0; max-width: 960px; line-height: 1.35; }}
.ag {{ position: absolute; right: 0; bottom: 0; width: 520px; height: 320px; opacity: .55; }}
</style></head><body>
<svg class="ag" viewBox="0 0 520 320"><g stroke="#7fd8b6" stroke-opacity=".35" stroke-width="2" fill="none">
<path d="M40 300 L140 220 L240 250 L330 150 L430 170 L500 60"/><path d="M140 220 L200 130 L330 150"/><path d="M240 250 L360 290 L430 170"/></g>
<g fill="#7fd8b6"><circle cx="140" cy="220" r="6"/><circle cx="240" cy="250" r="6"/><circle cx="200" cy="130" r="5"/><circle cx="360" cy="290" r="5"/><circle cx="430" cy="170" r="6"/></g>
<circle cx="40" cy="300" r="11" fill="#ff6b5e"/><circle cx="330" cy="150" r="9" fill="#3fd19b"/><circle cx="500" cy="60" r="9" fill="#f2c46b"/></svg>
<div class="ic"><div class="logo">{logo_yatay(acik=True)}</div>
<h1>Şebeke çöktüğünde de yardım çağrısı kuruma ulaşır.</h1>
<p>Telefondan telefona, internetsiz, şifreli. İnternete ulaşan ilk telefon çağrıyı 112'ye ve afet birimine iletir.</p></div>
</body></html>"""


def main():
    MARKA.mkdir(exist_ok=True)
    (MARKA / "kairomesh-isaret.svg").write_text(ISARET, encoding="utf-8")
    (MARKA / "kairomesh-logo.svg").write_text(logo_yatay(), encoding="utf-8")
    (MARKA / "kairomesh-logo-acik.svg").write_text(logo_yatay(acik=True), encoding="utf-8")
    with sync_playwright() as p:
        t = p.chromium.launch()
        s = t.new_page()

        def ciz(html, yol, w, h, saydam=True):
            s.set_viewport_size({"width": w, "height": h})
            # opened from a file (not set_content): only then may the page load the local Inter font files
            gecici = MARKA / "_cizim.html"
            gecici.write_text(f"<!doctype html><html><head><meta charset='utf-8'><style>{YAZI} svg{{display:block;width:{w}px;height:{h}px}}</style></head><body>{html}</body></html>", encoding="utf-8")
            s.goto(gecici.as_uri())
            s.evaluate("document.fonts.ready")
            s.wait_for_timeout(150)
            Path(yol).parent.mkdir(parents=True, exist_ok=True)
            s.screenshot(path=str(yol), omit_background=saydam)

        for boyut, ad in ((512, "icon-512.png"), (192, "icon-192.png"), (180, "apple-touch-icon.png"), (64, "favicon.png")):
            ciz(ISARET, STATIK / ad, boyut, boyut)
        ciz(ISARET, STATIK / "logo.png", 120, 120)
        ciz(ISARET, MARKA / "kairomesh-isaret-1024.png", 1024, 1024)
        ciz(logo_yatay(), MARKA / "kairomesh-logo.png", 1120, 240)
        ciz(logo_yatay(acik=True), MARKA / "kairomesh-logo-acik.png", 1120, 240)
        for klasor, boyut in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
            ciz(ISARET, ANDROID_RES / f"mipmap-{klasor}" / "ic_launcher.png", boyut, boyut)
        # Android adaptive icon: the mark without its square on a 108 dp canvas, inside the 72 dp safe zone; the
        # launcher supplies the shape (circle, squircle) and the slate background below
        on = ISARET.replace('<rect width="512" height="512" rx="114" fill="url(#z)"/>', "").replace(
            '<rect width="512" height="512" rx="114" fill="url(#p)"/>', "").replace('viewBox="0 0 512 512"', 'viewBox="-136 -136 784 784"')
        for klasor, boyut in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
            ciz(on, ANDROID_RES / f"mipmap-{klasor}" / "ic_launcher_on.png", boyut, boyut)
        (ANDROID_RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
        (ANDROID_RES / "mipmap-anydpi-v26" / "ic_launcher.xml").write_text("""<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ikon_zemin" />
    <foreground android:drawable="@mipmap/ic_launcher_on" />
    <monochrome android:drawable="@mipmap/ic_launcher_on" />
</adaptive-icon>
""", encoding="utf-8")
        (ANDROID_RES / "values").mkdir(parents=True, exist_ok=True)
        (ANDROID_RES / "values" / "renkler.xml").write_text("""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ikon_zemin">#1E2B34</color>
</resources>
""", encoding="utf-8")
        # iOS: one 1024 icon, opaque (App Store refuses transparency); the rounded corners come from iOS itself
        kare = ISARET.replace('rx="114"', 'rx="0"')
        ciz(kare, IOS_IKON / "AppIcon-1024.png", 1024, 1024, saydam=False)
        s.set_viewport_size({"width": 1200, "height": 630})
        (MARKA / "_cizim.html").write_text(og(), encoding="utf-8")
        s.goto((MARKA / "_cizim.html").as_uri())
        s.evaluate("document.fonts.ready")
        s.wait_for_timeout(300)
        s.screenshot(path=str(STATIK / "og-kairo-mesh.png"))
        t.close()
    (MARKA / "_cizim.html").unlink(missing_ok=True)
    (IOS_IKON / "Contents.json").write_text(
        '{"images":[{"filename":"AppIcon-1024.png","idiom":"universal","platform":"ios","size":"1024x1024"}],"info":{"author":"xcode","version":1}}\n',
        encoding="utf-8")
    (IOS_IKON.parent / "Contents.json").write_text('{"info":{"author":"xcode","version":1}}\n', encoding="utf-8")
    print("marka dosyaları yazıldı")


if __name__ == "__main__":
    main()
