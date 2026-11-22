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
  background: linear-gradient(#f2f2f7, #e4e4ea); color: #1c1c1e; font-size: 25px; }}
.kamera {{ position: absolute; top: 22px; left: 50%; width: 120px; height: 34px; margin-left: -60px; border-radius: 20px; background: #0b1115; z-index: 3; }}
.ic {{ padding: 84px 30px 30px; display: flex; flex-direction: column; gap: 16px; }}
.bas {{ display: flex; align-items: center; gap: 14px; font-size: 44px; font-weight: 800; letter-spacing: -1px; }}
.bas i {{ width: 44px; height: 44px; border-radius: 50%; background: #fdecea; display: grid; place-items: center; }}
.bas i::after {{ content: ""; width: 18px; height: 18px; border-radius: 50%; background: #b3261e; }}
.kurum {{ color: #48484a; font-size: 22px; margin-top: -6px; }}
.ag {{ font-weight: 700; font-size: 22px; padding: 10px 18px; border-radius: 20px; align-self: flex-start; }}
.ag.yesil {{ color: #0b6e4f; background: rgba(11,110,79,.11); }} .ag.amber {{ color: #8a5a00; background: rgba(138,90,0,.11); }}
.baslik {{ font-size: 36px; font-weight: 800; letter-spacing: -.5px; margin-top: 8px; }}
.baslik small {{ display: block; font-size: 22px; font-weight: 400; color: #48484a; letter-spacing: 0; margin-top: 4px; }}
.kart {{ background: #fff; border-radius: 30px; padding: 24px; display: flex; flex-direction: column; gap: 14px; }}
.adim {{ display: flex; align-items: center; gap: 14px; font-size: 28px; font-weight: 700; }}
.adim b {{ width: 38px; height: 38px; border-radius: 50%; background: #1c1c1e; color: #fff; font-size: 21px; display: grid; place-items: center; }}
.izgara {{ display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }}
.tur {{ border-radius: 26px; padding: 20px; min-height: 150px; background: #fdecea; }}
.tur b {{ color: #b3261e; font-size: 28px; display: block; line-height: 1.12; }}
.tur span {{ color: #48484a; font-size: 20px; display: block; margin-top: 6px; line-height: 1.25; }}
.tur.secili {{ background: #b3261e; box-shadow: 0 8px 18px -6px rgba(179,38,30,.55); }}
.tur.secili b, .tur.secili span {{ color: #fff; }}
.secim {{ display: flex; align-items: center; justify-content: space-between; font-size: 25px; font-weight: 700; color: #b3261e; }}
.secim em {{ font-style: normal; background: #fdecea; border-radius: 40px; padding: 12px 26px; font-size: 23px; }}
.sayac {{ display: flex; align-items: center; justify-content: space-between; }}
.sayac strong {{ font-size: 50px; line-height: 1; }} .sayac small {{ display: block; font-size: 21px; color: #48484a; }}
.adimlar {{ display: flex; align-items: center; background: #efeff4; border-radius: 24px; }}
.adimlar span {{ width: 104px; height: 86px; display: grid; place-items: center; font-size: 42px; font-weight: 600; }}
.adimlar i {{ width: 2px; height: 42px; background: #c7c7cc; }}
.cipler {{ display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }}
.cip {{ border-radius: 40px; padding: 18px 6px; text-align: center; font-weight: 600; font-size: 23px; background: #efeff4; }}
.cip.secili {{ background: #fdecea; color: #b3261e; box-shadow: inset 0 0 0 3px #b3261e; }}
.yaz {{ background: #efeff4; border-radius: 22px; padding: 18px; font-size: 24px; min-height: 92px; box-shadow: inset 0 0 0 3px #1c1c1e; }}
.not {{ color: #8a5a00; font-weight: 700; font-size: 22px; line-height: 1.3; }}
.ozet {{ background: #efeff4; border-radius: 22px; padding: 18px 20px; font-size: 22px; line-height: 1.5; }}
.gonder {{ background: #b3261e; color: #fff; text-align: center; font-weight: 800; font-size: 34px; border-radius: 30px; padding: 32px 0;
  box-shadow: 0 12px 24px -8px rgba(179,38,30,.6); }}
.durum {{ border-radius: 24px; padding: 22px; font-weight: 700; font-size: 25px; line-height: 1.35; }}
.durum.yesil {{ background: #ddf0e7; color: #0b4f39; }}
.sinyal {{ background: #fff; color: #1c1c1e; text-align: center; font-weight: 700; font-size: 25px; border-radius: 28px; padding: 28px 0; }}
.guvende {{ background: #ddf0e7; color: #0b6e4f; text-align: center; font-weight: 700; font-size: 25px; border-radius: 28px; padding: 28px 0; }}
.katman {{ position: absolute; inset: 0; background: #0e1a22; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 40px; z-index: 2; }}
.halka {{ width: 250px; height: 250px; border-radius: 50%; border: 6px solid #ff6961; display: grid; place-items: center; color: #fff; font-size: 116px; font-weight: 800; }}
.katman h2 {{ color: #fff; font-size: 38px; font-weight: 600; text-align: center; margin: 36px 0 54px; line-height: 1.25; }}
.katman .iptal {{ align-self: stretch; background: #fff; color: #b3261e; font-size: 48px; font-weight: 800; text-align: center; border-radius: 36px; padding: 46px 0; }}
.katman .hemen {{ align-self: stretch; color: #fff; box-shadow: inset 0 0 0 2px rgba(255,255,255,.6); font-size: 30px; font-weight: 600; text-align: center; border-radius: 36px; padding: 26px 0; margin-top: 20px; }}
"""


def turlar(secili=None):
    adlar = [("enkaz", "Enkaz altındayım", "Bina çöktü, çıkamıyorum"), ("saglik", "Yaralı / hasta var", "Acil sağlık yardımı gerekiyor"),
             ("yangin", "Yangın / gaz", "Ateş, duman ya da gaz kokusu"), ("yardim", "Başka acil durum", "Ne olduğunu yazarak anlatın")]
    return '<div class="izgara">' + "".join(
        f'<div class="tur{" secili" if k == secili else ""}"><b>{ad}</b><span>{"✓ Seçildi · kaldırmak için yine dokunun" if k == secili else alt}</span></div>'
        for k, ad, alt in adlar) + "</div>"


def adim(no, ad):
    return f'<div class="adim"><b>{no}</b>{ad}</div>'


def ekran(icerik, ag="● İnternet yok · yakında çağrınızı taşıyabilecek 3 telefon var", agrenk="yesil", baslik=True):
    b = ('<div class="baslik">Acil yardım çağrısı<small>İnternet olmasa da çalışır. Durumunuzu seçin ya da yazın, sonra gönderin.</small></div>'
         if baslik else "")
    return f"""<div class="kamera"></div><div class="ic">
<div class="bas"><i></i>KairoMesh</div><div class="kurum">Çağrılar şu kuruma gider: KairoMesh Operatör Demo</div>
<div class="ag {agrenk}">{ag}</div>{b}{icerik}</div>"""


def ozet(durum, kisi="belirtilmedi", yan="belirtilmedi", mesaj=None):
    m = f"<br>• Mesaj: \"{mesaj}\"" if mesaj else ""
    return f'<div class="ozet"><b>Gönderilecekler</b><br>• Durum: {durum}<br>• Kişi sayısı: {kisi}<br>• Yanınızda: {yan}{m}<br>• Konum: izin verildiyse GPS eklenir</div>'


SAYAC = ('<div class="sayac"><div><strong>3</strong><small>? = bilinmiyor</small></div>'
         '<div class="adimlar"><span>−</span><i></i><span>+</span></div></div>')
CIPLER = '<div class="cipler"><div class="cip secili">✓ Bebek</div><div class="cip">Çocuk</div><div class="cip">Yaşlı</div></div>'

EKRANLAR = [
    ("1-internetsiz", "İnternet yokken de çalışır.",
     "Baz istasyonu çöktüğünde yardım çağrınız yakındaki telefonlardan geçer; internete ulaşan ilk telefon kuruma iletir.",
     ekran('<div class="kart">' + adim(1, "Ne oldu?") + turlar() + '<div class="secim" style="color:#48484a">Durum seçilmedi</div></div>')),
    ("2-tek-dokunus", "Tek dokunuşla seçin.",
     "Dört büyük düğme yalnızca hayati durumlar için. Seçiminiz yazıyla gösterilir; yanlışsa tek dokunuşla kaldırırsınız.",
     ekran('<div class="kart">' + adim(1, "Ne oldu?") + turlar("enkaz") + '<div class="secim">Seçili: Enkaz altındayım <em>Kaldır</em></div></div>'
           + '<div class="kart">' + adim(2, "Kaç kişisiniz?") + SAYAC + adim(3, "Yanınızda") + CIPLER + "</div>", baslik=False)),
    ("3-bes-saniye", "Yanlış dokunuşa karşı 5 saniye.",
     "Gönder'e basınca büyük bir İptal çıkar; hiçbir şey gönderilmeden vazgeçebilirsiniz. Gönderdikten sonra da düzeltme yapılabilir.",
     ekran('<div class="kart">' + adim(1, "Ne oldu?") + turlar("enkaz") + "</div>")
     + '<div class="katman"><div class="halka">4</div><h2>Çağrı 4 saniye içinde<br>gönderilecek</h2>'
       '<div class="iptal">İptal et</div><div class="hemen">Hemen gönder</div></div>'),
    ("4-yazin-anlasin", "Yazın, telefon anlasın.",
     "Panikte bozuk yazılmış mesajdan durum, kişi sayısı ve adres telefonda çıkarılır. İnternet gerekmez.",
     ekran('<div class="kart">' + adim(4, "Ya da kısaca yazın")
           + '<div class="yaz">antakya ürgenpaşa mh enkazdayız 3 kş bebek var</div>'
           + '<div class="not">Durum seçmediniz: telefon yazınızdan anlayacak ve çağrıyı acil olarak gönderecek.</div></div>'
           + '<div class="kart">' + ozet("yazdığınızdan anlaşılacak", mesaj="antakya ürgenpaşa mh enkazdayız 3 kş bebek var")
           + '<div class="gonder">Çağrıyı gönder</div></div>', baslik=False)),
    ("5-kurum-aldi", "Kurum aldığında bilirsiniz.",
     "Kurumun makbuzu ağdan size geri döner: “Kurum çağrınızı aldı.” Sesli sinyal, kurtarıcıların sizi bulmasına yardım eder.",
     ekran('<div class="kart">' + ozet("Enkaz altındayım", "3", "Bebek")
           + '<div class="gonder">Çağrıyı gönder</div><div class="durum yesil">✓ Kurum çağrınızı aldı (14:32).<br>'
             'Yardım ekipleri bilgilendirildi. Mümkünse yerinizde kalın, sesli sinyali kullanın.</div></div>'
           + '<div class="sinyal">♪ Sesli sinyal · kurtarıcılar sizi duysun</div><div class="guvende">✓ Güvendeyim · kuruma bildir</div>',
           ag="● İnternet yok · yakında çağrınızı taşıyabilecek 2 telefon var", baslik=False)),
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
