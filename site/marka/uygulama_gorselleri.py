"""KairoMesh uygulama tanıtım görselleri (mağaza ve site). Gerçek ekranın (android MainActivity.kt, iOS
KairoMeshApp.swift) renkleri, metinleri ve düzeniyle çizilir; ekran görüntüsü değildir, daha belirgin olsun diye
büyütülmüş ve kırpılmıştır.

    python site/marka/uygulama_gorselleri.py   ->  site/statik/uygulama/*.png (1080x1920) ve medya/uygulama/
"""
import shutil
from pathlib import Path

from playwright.sync_api import sync_playwright

KOK = Path(__file__).resolve().parents[2]
CIKTI = KOK / "site" / "statik" / "uygulama"
MEDYA = KOK / "medya" / "uygulama"
FONT = (KOK / "site" / "statik" / "fonts" / "inter-latin.woff2").as_uri()
FONT2 = (KOK / "site" / "statik" / "fonts" / "inter-latinext.woff2").as_uri()
ISARET = (KOK / "site" / "marka" / "kairomesh-isaret.svg").read_text(encoding="utf-8")

CSS = f"""
@font-face {{ font-family: Inter; font-weight: 400 800; src: url({FONT}) format("woff2"); }}
@font-face {{ font-family: Inter; font-weight: 400 800; src: url({FONT2}) format("woff2");
  unicode-range: U+0100-02BA, U+02BD-02C5, U+02C7-02CC, U+02CE-02D7, U+02DD-02FF; }}
* {{ box-sizing: border-box; }}
body {{ margin: 0; width: 1080px; height: 1920px; overflow: hidden; font-family: Inter, sans-serif;
  background: radial-gradient(ellipse 90% 60% at 50% 0%, #2b3b46, #0d161b 70%); color: #eef4f6; }}
.ust {{ padding: 92px 90px 0; }}
.ust .marka {{ display: flex; align-items: center; gap: 18px; font-weight: 800; font-size: 40px; letter-spacing: -1px; }}
.ust .marka svg {{ width: 64px; height: 64px; }}
.ust .marka b {{ color: #ff6b5e; }}
.ust h1 {{ font-size: 76px; line-height: 1.04; letter-spacing: -2.5px; margin: 40px 0 18px; font-weight: 800; }}
.ust p {{ font-size: 34px; line-height: 1.35; color: #c2d0d7; margin: 0; max-width: 880px; }}
.tel {{ position: absolute; left: 50%; top: 680px; width: 720px; height: 1480px; margin-left: -360px; border-radius: 96px;
  background: #0b1115; padding: 22px; box-shadow: 0 40px 120px -20px rgba(0,0,0,.7), inset 0 0 0 3px #2c3a44; }}
.ekran {{ width: 100%; height: 100%; border-radius: 76px; overflow: hidden; position: relative;
  background: linear-gradient(#eceff2, #d6dce1); color: #0e1a22; font-size: 25px; }}
.kamera {{ position: absolute; top: 22px; left: 50%; width: 120px; height: 34px; margin-left: -60px; border-radius: 20px; background: #0b1115; z-index: 3; }}
.ic {{ padding: 84px 30px 30px; }}
.bas {{ display: flex; align-items: center; gap: 12px; font-size: 42px; font-weight: 800; letter-spacing: -1px; }}
.bas i {{ width: 20px; height: 20px; border-radius: 50%; background: #b3261e; display: inline-block; }}
.kurum {{ color: #3a4853; font-size: 22px; margin-top: 6px; }}
.ag {{ font-weight: 700; font-size: 23px; margin: 10px 0 16px; }}
.ag.yesil {{ color: #0b6e4f; }} .ag.amber {{ color: #8a5a00; }}
.kart {{ background: #f8f9fa; border: 2px solid #c0c8cf; border-radius: 26px; padding: 24px; box-shadow: 0 3px 8px rgba(0,0,0,.06); }}
.acilb {{ color: #b3261e; font-size: 34px; font-weight: 800; }}
.acik {{ color: #3a4853; font-size: 23px; margin: 6px 0 14px; line-height: 1.3; }}
.izgara {{ display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }}
.tur {{ border: 3px solid #d68c84; border-radius: 22px; padding: 18px; min-height: 150px; background: #f8f9fa; }}
.tur b {{ color: #b3261e; font-size: 30px; display: block; line-height: 1.12; }}
.tur span {{ color: #3a4853; font-size: 20px; display: block; margin-top: 6px; line-height: 1.25; }}
.tur.secili {{ background: #b3261e; border-color: #b3261e; }}
.tur.secili b, .tur.secili span {{ color: #fff; }}
.secim {{ display: flex; align-items: center; justify-content: space-between; margin-top: 18px; font-size: 27px; font-weight: 800; color: #b3261e; }}
.secim em {{ font-style: normal; border: 3px solid #b3261e; border-radius: 16px; padding: 12px 22px; font-size: 24px; }}
.alt {{ font-weight: 700; font-size: 24px; margin: 18px 0 8px; }}
.sayac {{ display: flex; align-items: center; gap: 22px; }}
.sayac div {{ width: 110px; height: 86px; border: 3px solid #c0c8cf; border-radius: 18px; display: grid; place-items: center; font-size: 46px; font-weight: 700; background: #f8f9fa; }}
.sayac strong {{ font-size: 44px; width: 60px; text-align: center; }}
.cipler {{ display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }}
.cip {{ border: 3px solid #c0c8cf; border-radius: 40px; padding: 16px 6px; text-align: center; font-weight: 700; font-size: 23px; background: #f8f9fa; }}
.cip.secili {{ background: #26343e; border-color: #26343e; color: #fff; }}
.yaz {{ background: #fff; border: 3px solid #c0c8cf; border-radius: 18px; padding: 16px; font-size: 24px; min-height: 92px; color: #0e1a22; }}
.not {{ color: #8a5a00; font-weight: 700; font-size: 22px; margin-top: 10px; line-height: 1.3; }}
.ozet {{ background: #ecf0f3; border: 2px solid #c0c8cf; border-radius: 18px; padding: 16px 18px; font-size: 22px; line-height: 1.45; margin-top: 16px; }}
.gonder {{ background: #b3261e; color: #fff; text-align: center; font-weight: 800; font-size: 36px; border-radius: 20px; padding: 30px 0; margin-top: 16px; }}
.durum {{ border-radius: 20px; padding: 22px; margin-top: 16px; font-weight: 700; font-size: 25px; line-height: 1.35; }}
.durum.kirmizi {{ background: #fbe4e1; }} .durum.yesil {{ background: #ddf0e7; color: #0b4f39; }}
.duzelt {{ background: #f8f9fa; border: 2px solid #c0c8cf; border-radius: 16px; padding: 16px; text-align: center; font-size: 22px; margin-top: 14px; }}
.sinyal {{ background: #26343e; color: #fff; text-align: center; font-weight: 800; font-size: 26px; border-radius: 20px; padding: 24px 0; margin-top: 16px; }}
.guvende {{ background: #ddf0e7; color: #0b6e4f; border: 3px solid #0b6e4f; text-align: center; font-weight: 800; font-size: 26px; border-radius: 20px; padding: 22px 0; margin-top: 12px; }}
.katman {{ position: absolute; inset: 0; background: rgba(14,26,34,.97); display: flex; flex-direction: column; justify-content: center; padding: 40px; gap: 30px; z-index: 2; }}
.katman h2 {{ color: #fff; font-size: 46px; text-align: center; margin: 0 0 10px; line-height: 1.2; }}
.katman .iptal {{ background: #fff; color: #b3261e; font-size: 62px; font-weight: 800; text-align: center; border-radius: 26px; padding: 44px 0; }}
.katman .hemen {{ background: #b3261e; color: #fff; border: 3px solid #fff; font-size: 32px; font-weight: 800; text-align: center; border-radius: 22px; padding: 26px 0; }}
.rozet {{ position: absolute; right: 40px; top: 110px; z-index: 4; background: #ff6b5e; color: #fff; font-weight: 800; font-size: 26px; padding: 10px 20px; border-radius: 40px; }}
"""


def turlar(secili=None):
    adlar = [("enkaz", "Enkaz altındayım", "Bina çöktü, çıkamıyorum"), ("saglik", "Yaralı / hasta var", "Acil sağlık yardımı gerekiyor"),
             ("yangin", "Yangın / gaz", "Ateş, duman ya da gaz kokusu"), ("yardim", "Başka acil durum", "Ne olduğunu yazarak anlatın")]
    return '<div class="izgara">' + "".join(
        f'<div class="tur{" secili" if k == secili else ""}"><b>{ad}</b><span>{"✓ SEÇİLİ — kaldırmak için tekrar dokunun" if k == secili else alt}</span></div>'
        for k, ad, alt in adlar) + "</div>"


def ekran(icerik, ag="● İnternet yok · yakında 3 telefon taşıyabilir", agrenk="yesil", ust_ekle=""):
    return f"""<div class="kamera"></div>{ust_ekle}<div class="ic">
<div class="bas"><i></i>KairoMesh</div><div class="kurum">Çağrılarınız şu kuruma gider: AFAD Hatay</div>
<div class="ag {agrenk}">{ag}</div>{icerik}</div>"""


def ozet(durum, kisi="belirtilmedi", yan="belirtilmedi", mesaj=None):
    m = f"<br>• Mesaj: \"{mesaj}\"" if mesaj else ""
    return f'<div class="ozet">Gönderilecek:<br>• Durum: {durum}<br>• Kişi sayısı: {kisi}<br>• Yanınızda: {yan}{m}<br>• Konum: izin verildiyse GPS eklenir</div>'


EKRANLAR = [
    ("1-internetsiz", "İnternet yokken de çalışır.",
     "Baz istasyonu çöktüğünde yardım çağrınız yakındaki telefonlardan geçer; internete ulaşan ilk telefon kuruma iletir.",
     ekran('<div class="kart"><div class="acilb">ACİL YARDIM ÇAĞRISI</div><div class="acik">İnternet olmasa da çalışır. 1) Durumunuza dokunun ya da yazın, 2) Gönder\'e basın.</div>'
           + turlar() + '<div class="secim" style="color:#3a4853">Henüz durum seçmediniz.</div></div>', ag="● İnternet yok · yakında 3 telefon taşıyabilir")),
    ("2-tek-dokunus", "Tek dokunuşla seçin.",
     "Dört büyük düğme yalnızca hayati durumlar için. Seçiminiz yazıyla gösterilir; yanlışsa tek dokunuşla kaldırırsınız.",
     ekran('<div class="kart"><div class="acilb">ACİL YARDIM ÇAĞRISI</div><div class="acik">İnternet olmasa da çalışır.</div>' + turlar("enkaz")
           + '<div class="secim">Seçiminiz: Enkaz altındayım <em>Kaldır</em></div><div class="alt">Kaç kişisiniz?</div>'
           + '<div class="sayac"><div>−</div><strong>3</strong><div>+</div></div><div class="alt">Yanınızda:</div>'
           + '<div class="cipler"><div class="cip secili">✓ Bebek</div><div class="cip">Çocuk</div><div class="cip">Yaşlı</div></div></div>')),
    ("3-bes-saniye", "Yanlış dokunuşa karşı 5 saniye.",
     "Gönder'e basınca büyük bir İPTAL çıkar; hiçbir şey gönderilmeden vazgeçebilirsiniz. Gönderdikten sonra da düzeltme yapılabilir.",
     ekran('<div class="kart"><div class="acilb">ACİL YARDIM ÇAĞRISI</div>' + turlar("enkaz") + "</div>")
     + '<div class="katman"><h2>Çağrı 4 saniye içinde gönderilecek</h2><div class="iptal">İPTAL</div><div class="hemen">Hemen gönder</div></div>'),
    ("4-yazin-anlasin", "Yazın, telefon anlasın.",
     "Panikte bozuk yazılmış mesajdan durum, kişi sayısı ve adres telefonda çıkarılır. İnternet gerekmez.",
     ekran('<div class="kart"><div class="alt" style="margin-top:0">Ya da yazın (isteğe bağlı):</div>'
           + '<div class="yaz">antakya ürgenpaşa mh enkazdayız 3 kş bebek var</div>'
           + '<div class="not">Durum seçmediniz: yazdığınızdan durum telefonda anlaşılacak ve çağrı acil olarak gönderilecek.</div>'
           + ozet("yazdığınızdan anlaşılacak", mesaj="antakya ürgenpaşa mh enkazdayız 3 kş bebek var") + '<div class="gonder">ÇAĞRIYI GÖNDER</div></div>')),
    ("5-kurum-aldi", "Kurum aldığında bilirsiniz.",
     "Kurumun makbuzu ağdan size geri döner: “Kurum çağrınızı aldı.” Sesli sinyal, kurtarıcıların sizi bulmasına yardım eder.",
     ekran('<div class="kart"><div class="acilb">ACİL YARDIM ÇAĞRISI</div>' + ozet("Enkaz altındayım", "3", "Bebek")
           + '<div class="gonder">ÇAĞRIYI GÖNDER</div><div class="durum yesil">✓ KURUM ÇAĞRINIZI ALDI (14:32).<br>Yardım ekipleri bilgilendirildi. Mümkünse yerinizde kalın, sesli sinyali kullanın.</div></div>'
           + '<div class="sinyal">♪ Sesli sinyal (kurtarıcılar sizi duysun)</div><div class="guvende">Güvendeyim — kuruma bildir</div>',
           ag="● İnternet yok · yakında 2 telefon taşıyabilir")),
]


def main():
    CIKTI.mkdir(parents=True, exist_ok=True)
    MEDYA.mkdir(parents=True, exist_ok=True)
    isaret = ISARET.replace('viewBox="0 0 512 512"', 'viewBox="0 0 512 512" width="64" height="64"')
    with sync_playwright() as p:
        t = p.chromium.launch()
        s = t.new_page(viewport={"width": 1080, "height": 1920})
        for ad, baslik, alt, ic in EKRANLAR:
            html = (f'<!doctype html><html><head><meta charset="utf-8"><style>{CSS}</style></head><body>'
                    f'<div class="ust"><div class="marka">{isaret}<span>Kairo<b>Mesh</b></span></div><h1>{baslik}</h1><p>{alt}</p></div>'
                    f'<div class="tel"><div class="ekran">{ic}</div></div></body></html>')
            gecici = CIKTI / f"_{ad}.html"
            gecici.write_text(html, encoding="utf-8")
            s.goto(gecici.as_uri())
            s.evaluate("document.fonts.ready")
            s.wait_for_timeout(200)
            s.screenshot(path=str(CIKTI / f"{ad}.png"))
            gecici.unlink()
            shutil.copy(CIKTI / f"{ad}.png", MEDYA / f"{ad}.png")
        t.close()
    print("uygulama görselleri:", CIKTI)


if __name__ == "__main__":
    main()
