"""Kairo Mesh resmî sitesini derler ve iki yayına yazar.

    python site/yap.py                # yalnız site/cikti/ altına derler
    python site/yap.py --yayinla      # + kairodynedynamics.com deposu ve HF Space klasörü

Kaynak: site/sayfalar/*.html (gövde), site/gelismeler.json (gelişmeler),
site/stil.css. Her sayfa aynı üst çubuk, alt bilgi ve SEO başlıklarıyla sarılır.

Yayın hedefleri:
- kairodynedynamics.com: Netlify'ın yayımladığı GitHub deposu (commit + push ayrı adım).
- HF Space: WSL'deki `site/` klasörü; oradaki `_canli_yukle.py` değişen dosyayı
  saniyeler içinde Space'e yükler. Kaldırılan eski dosyaları silmek için
  `--hf-temizle` ayrı çalıştırılır.
"""
import argparse
import html
import json
import shutil
import subprocess
from datetime import date
from pathlib import Path

KOK = Path(__file__).resolve().parent
CIKTI = KOK / "cikti"
URUN = KOK.parent
ALAN = "https://kairodynedynamics.com"
GITHUB_SITE = Path(r"C:\Users\Emre\Documents\GitHub\kairodynedynamics.com")
HF_SITE = r"\\wsl.localhost\Ubuntu-22.04\home\emre\private-llm\geliştirme\site"

# (dosya, menü adı, başlık, açıklama)
SAYFALAR = [
    ("index", "Ana sayfa", "Kairo Mesh — şebeke çöktüğünde de yardım çağrısı kuruma ulaşır",
     "Kairo Mesh, telefonların internet ve baz istasyonu olmadan Bluetooth ile birbirinin şifreli mesajını taşıdığı yerli afet haberleşme sistemidir. Yardım çağrısı, internete ulaşan ilk telefon üzerinden 112 ve afet birimlerine iletilir."),
    ("nasil-calisir", "Nasıl çalışır", "Nasıl çalışır — Kairo Mesh",
     "Taşıyıcı seçimi, çeşme kodlu parçalı aktarım, Noise X şifreleme, taklit edilemez makbuz ve izlenemeyen cihaz kimliği."),
    ("acil-aktarim", "Acil aktarım", "Acil aktarım: şebekesiz telefondan kuruma — Kairo Mesh",
     "Yardım çağrısı kurum anahtarına mühürlenir, çevredeki telefonlarca taşınır ve internete ulaşan ilk telefon tarafından arka planda 112 ya da afet birimine iletilir."),
    ("dogrulama", "Doğrulama", "Ölçümler ve testler — Kairo Mesh",
     "Simülasyon yöntemi ve sonuçları, parçalı aktarım ölçümleri, Türkçe yardım mesajı anlamlandırma taban çizgisi, saldırı testleri ve platform testleri."),
    ("kurumlar", "Kurumlar için", "Kurumlar için: lisans ve pilot — Kairo Mesh",
     "Kamu kurumları, belediyeler, arama-kurtarma kuruluşları ve operatörler için lisans modeli, pilot süreci ve kamu taahhütleri."),
    ("simulasyon", "Simülasyon", "Simülasyonu tarayıcıda çalıştırın — Kairo Mesh",
     "80 kişilik bir mahalle simülasyonunu ürünle aynı kodla tarayıcınızda çalıştırın ve taşıyıcı kurallarını karşılaştırın."),
    ("gelismeler", "Gelişmeler", "Gelişmeler — Kairo Mesh",
     "Kairo Mesh'in tarihli geliştirme kayıtları."),
    ("en", "English", "Kairo Mesh — when the network is down, the call for help still reaches the authority",
     "Kairo Mesh carries encrypted emergency calls from phone to phone over Bluetooth, with no internet or cell towers; the first phone that reaches the internet delivers them to the emergency authority."),
]
# Önceki dil modeli projesinin adresleri: ana sayfaya kalıcı yönlendirme.
ESKI = ["modeller", "algoritma", "rla", "omniprint", "egitim", "asama", "haberler", "notlar", "rapor"]


def gelismeler_govdesi():
    kayit = json.loads((KOK / "gelismeler.json").read_text(encoding="utf-8"))
    oge = "\n".join(
        f'<li><time datetime="{e["tarih"]}">{tarih_tr(e["tarih"])}</time><h3>{html.escape(e["baslik"])}</h3>'
        f'<p>{html.escape(e["metin"])}</p></li>' for e in kayit)
    return f"""<section class="bolum ilk"><div class="sar">
<span class="etiket">Gelişmeler</span><h1>Geliştirme kayıtları</h1>
<p class="lede">Ürüne eklenen her önemli değişiklik tarihiyle burada yayımlanır. Ayrıntılı değişiklik geçmişi <a href="https://github.com/kairodynedynamics/kairo-mesh/commits/main">kaynak deposundadır</a>.</p>
<ol class="zaman">{oge}</ol></div></section>"""


AYLAR = ["Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"]


def tarih_tr(t):
    y, a, g = t.split("-")
    return f"{int(g)} {AYLAR[int(a) - 1]} {y}"


def sar(dosya, baslik, aciklama, govde):
    url = ALAN + "/" + ("" if dosya == "index" else dosya)
    secili = ' aria-current="page"'
    menu = "\n".join(
        f'<a href="{d + ".html" if d != "index" else "./"}"{secili if d == dosya else ""}>{ad}</a>'
        for d, ad, _, _ in SAYFALAR)
    ld = json.dumps({"@context": "https://schema.org", "@graph": [
        {"@type": "Organization", "@id": ALAN + "/#org", "name": "Kairodyne Dynamics", "url": ALAN + "/",
         "logo": ALAN + "/icon-512.png", "sameAs": ["https://github.com/kairodynedynamics"]},
        {"@type": "SoftwareApplication", "@id": ALAN + "/#kairo-mesh", "name": "Kairo Mesh",
         "applicationCategory": "CommunicationApplication", "operatingSystem": "Android, iOS",
         "description": SAYFALAR[0][3], "creator": {"@id": ALAN + "/#org"}, "inLanguage": "tr",
         "license": "https://www.gnu.org/licenses/agpl-3.0.html"},
        {"@type": "WebPage", "url": url, "name": baslik, "description": aciklama, "inLanguage": "en" if dosya == "en" else "tr"}]},
        ensure_ascii=False)
    e = html.escape
    dil = "en" if dosya == "en" else "tr"
    return f"""<!doctype html>
<html lang="{dil}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>{e(baslik)}</title>
<meta name="description" content="{e(aciklama)}">
<link rel="canonical" href="{url}">
<meta name="theme-color" content="#0b6e4f">
<meta property="og:type" content="website">
<meta property="og:site_name" content="Kairo Mesh — Kairodyne Dynamics">
<meta property="og:locale" content="tr_TR">
<meta property="og:title" content="{e(baslik)}">
<meta property="og:description" content="{e(aciklama)}">
<meta property="og:url" content="{url}">
<meta property="og:image" content="{ALAN}/og-kairo-mesh.png">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta name="twitter:card" content="summary_large_image">
<link rel="icon" type="image/png" href="favicon.png">
<link rel="apple-touch-icon" href="apple-touch-icon.png">
<link rel="stylesheet" href="stil.css">
<script type="application/ld+json">{ld}</script>
</head>
<body>
<a class="atla" href="#icerik">İçeriğe geç</a>
<header class="ust"><div class="sar">
<a class="marka" href="./"><img src="logo.png" alt="" width="30" height="30"><span>Kairo Mesh<small>Kairodyne Dynamics</small></span></a>
<nav class="ana" aria-label="Ana menü">
{menu}
</nav>
</div></header>
<main id="icerik">
{govde}
</main>
<footer class="alt"><div class="sar">
<div>
<h3>Kairo Mesh</h3>
<p>Şebeke çöktüğünde de çalışan, yerli geliştirilmiş afet haberleşme sistemi. Araştırma prototipidir; saha tatbikatı ve bağımsız güvenlik denetimi henüz yapılmamıştır.</p>
<p>© {date.today().year} Kairodyne Dynamics. Protokol çekirdeği <a href="https://github.com/kairodynedynamics/kairo-mesh/blob/main/LICENSE">AGPL-3.0</a>; ticari ve kurumsal lisans ayrıca verilir.</p>
</div>
<div><h3>Ürün</h3><ul>
<li><a href="nasil-calisir.html">Nasıl çalışır</a></li><li><a href="acil-aktarim.html">Acil aktarım</a></li>
<li><a href="dogrulama.html">Doğrulama</a></li><li><a href="simulasyon.html">Simülasyon</a></li></ul></div>
<div><h3>Kurum</h3><ul>
<li><a href="kurumlar.html">Lisans ve pilot</a></li><li><a href="gelismeler.html">Gelişmeler</a></li>
<li><a href="https://github.com/kairodynedynamics/kairo-mesh">Kaynak kod</a></li></ul>
<p class="kucuk">Bu site çerez ve izleme kullanmaz.</p></div>
</div></footer>
</body>
</html>
"""


def yonlendirme(hedef="./"):
    return f"""<!doctype html><html lang="tr"><head><meta charset="utf-8">
<meta http-equiv="refresh" content="0; url={hedef}"><link rel="canonical" href="{ALAN}/">
<meta name="robots" content="noindex"><title>Kairo Mesh</title></head>
<body><p>Bu sayfa kaldırıldı. <a href="{hedef}">Kairo Mesh ana sayfası</a></p></body></html>
"""


def derle():
    if CIKTI.exists():
        shutil.rmtree(CIKTI)
    CIKTI.mkdir()
    for dosya, _, baslik, aciklama in SAYFALAR:
        govde = gelismeler_govdesi() if dosya == "gelismeler" else (KOK / "sayfalar" / f"{dosya}.html").read_text(encoding="utf-8")
        (CIKTI / f"{dosya}.html").write_text(sar(dosya, baslik, aciklama, govde), encoding="utf-8")
    for eski in ESKI:
        (CIKTI / f"{eski}.html").write_text(yonlendirme(), encoding="utf-8")
    (CIKTI / "404.html").write_text(sar("404", "Sayfa bulunamadı — Kairo Mesh", "Aradığınız sayfa bulunamadı.",
        '<section class="bolum ilk"><div class="sar"><h1>Sayfa bulunamadı</h1><p><a href="./">Ana sayfaya dönün</a>.</p></div></section>'),
        encoding="utf-8")
    shutil.copy(KOK / "stil.css", CIKTI / "stil.css")
    shutil.copytree(KOK / "statik", CIKTI, dirs_exist_ok=True)
    (CIKTI / "sim").mkdir(exist_ok=True)
    for f in ("sim.js", "rng.js"):
        shutil.copy(URUN / "src" / f, CIKTI / "sim" / f)
    bugun = date.today().isoformat()
    (CIKTI / "sitemap.xml").write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n' +
        "".join(f'  <url><loc>{ALAN}/{"" if d == "index" else d}</loc><lastmod>{bugun}</lastmod></url>\n' for d, *_ in SAYFALAR) +
        "</urlset>\n", encoding="utf-8")
    (CIKTI / "robots.txt").write_text(f"User-agent: *\nAllow: /\n\nSitemap: {ALAN}/sitemap.xml\n", encoding="utf-8")
    (CIKTI / "llms.txt").write_text(
        "# Kairo Mesh — Kairodyne Dynamics\n\n> " + SAYFALAR[0][3] + "\n\n"
        "Durum: araştırma prototipi. Simülasyon ölçümleri ve otomatik testler yayımlandı; gerçek telefonlarla saha tatbikatı ve bağımsız güvenlik denetimi henüz yapılmadı.\n\n"
        "Kaynak kod: https://github.com/kairodynedynamics/kairo-mesh (AGPL-3.0, ayrıca ticari lisans)\n\n## Sayfalar\n\n" +
        "".join(f"- [{ad}]({ALAN}/{'' if d == 'index' else d}): {ack}\n" for d, ad, _, ack in SAYFALAR), encoding="utf-8")
    (CIKTI / "netlify.toml").write_text(
        '[build]\n  publish = "."\n\n[build.processing.html]\n  pretty_urls = true\n\n' +
        "".join(f'[[redirects]]\n  from = "/{e}"\n  to = "/"\n  status = 301\n\n' for e in ESKI) +
        '[[headers]]\n  for = "/*"\n  [headers.values]\n    X-Content-Type-Options = "nosniff"\n    Referrer-Policy = "strict-origin-when-cross-origin"\n'
        '    Content-Security-Policy = "default-src \'self\'; script-src \'self\' \'unsafe-inline\'; style-src \'self\' \'unsafe-inline\'; img-src \'self\' data:; frame-ancestors \'self\' https://huggingface.co"\n\n'
        '[[headers]]\n  for = "/*.txt"\n  [headers.values]\n    Content-Type = "text/plain; charset=utf-8"\n', encoding="utf-8")
    (CIKTI / "README.md").write_text(
        "---\ntitle: Kairo Mesh\nemoji: 📡\ncolorFrom: green\ncolorTo: gray\nsdk: static\npinned: true\nlicense: agpl-3.0\n"
        "short_description: Şebeke çöktüğünde de yardım çağrısı kuruma ulaşır\n---\n\n"
        "# Kairo Mesh\n\n" + SAYFALAR[0][3] + "\n\nResmî sayfa: https://kairodynedynamics.com · Kaynak kod: https://github.com/kairodynedynamics/kairo-mesh\n",
        encoding="utf-8")
    print("derlendi:", len(list(CIKTI.rglob("*"))), "dosya")


# Hedefte kaldırılacak eski dil modeli dosyaları (yayın klasörü yalnız bu sitenin dosyalarını taşısın).
ESKI_DOSYALAR = ["assets", "data", "docs", "state", "og-kairodyne.jpg"]


def yayinla():
    """GitHub deposuna yazar (commit/push ayrı); HF Space'i derlemeyle birebir aynalar (fazla dosyayı siler)."""
    for ad in ESKI_DOSYALAR:
        p = GITHUB_SITE / ad
        if p.is_dir():
            shutil.rmtree(p)
        elif p.exists():
            p.unlink()
    shutil.copytree(CIKTI, GITHUB_SITE, dirs_exist_ok=True)
    print("yazildi:", GITHUB_SITE)
    # HF: token WSL'deki kairo/.env'de kalır; derleme /mnt/d üzerinden okunur.
    betik = (
        "import sys; sys.path.insert(0, '.'); from _hf_yayinla import _env_token; from huggingface_hub import HfApi; "
        "api = HfApi(token=_env_token()); "
        "r = api.upload_folder(repo_id='kairodynedynamics/KairodyneDynamics', repo_type='space', "
        "folder_path='/mnt/d/Kairodyne-Dynamics/site/cikti', delete_patterns=['*'], "
        "commit_message='Kairo Mesh resmi sitesi'); print('hf:', r)")
    subprocess.run(["wsl.exe", "-d", "Ubuntu-22.04", "--", "bash", "-c",
                    f"cd /home/emre/private-llm/geli*tirme && python3 -c \"{betik}\""], check=True,
                   env={**__import__("os").environ, "MSYS_NO_PATHCONV": "1"})


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--yayinla", action="store_true")
    a = ap.parse_args()
    derle()
    if a.yayinla:
        yayinla()
