# Kurum kurulumu (pilot ve resmî kullanım)

Bu belge, Kairo Mesh'i kendi anahtarı ve sunucusuyla işletecek kurum (il afet birimi, belediye, 112, pilot ortağı) içindir. Özel anahtarlar kurumun bilgisayarında üretilir ve oradan çıkmaz; Kairodyne Dynamics çağrı içeriğine erişemez.

## Gerekenler

- Yurt içinde bir sunucu (sanal makine yeterli), Node.js 22 veya üstü.
- Bir alan adı ve TLS sertifikası. Sunucunun önüne ters vekil sunucu (nginx ya da Caddy) konur; uygulama HTTPS üzerinden bağlanır.

## 1. Anahtar üretimi (bir kez)

```bash
node src/kurum-cli.js anahtar /guvenli/klasor https://acil.kurum.gov.tr
```

Komut `kurum-anahtar.json` dosyasını yazar (yalnız sahibi okuyabilir) ve telefonlara verilecek **kurum kodunu** basar. Dosya şifreli bir ortamda yedeklenmelidir: kaybolursa yeni anahtar ve yeni kod gerekir; eski koddaki telefonların çağrıları açılamaz. Mevcut bir anahtarın üzerine yazılmaz.

## 2. Sunucuyu çalıştırma

```bash
KURUM_PANO_ANAHTARI='<uzun rastgele değer>' node src/kurum-cli.js sunucu /guvenli/klasor 8080
```

- Çağrılar `/guvenli/klasor/cagrilar.jsonl` dosyasına eklenir; sunucu yeniden başlarsa geri yüklenir. Saklama süresi ve silme kurumun KVKK politikasına göre ayrıca uygulanır.
- Pano: `https://acil.kurum.gov.tr/pano` (`Authorization: Bearer <pano anahtarı>` ya da `?t=<pano anahtarı>`). Çağrılar aciliyete, sonra zamana göre sıralıdır; 15 saniyede bir yenilenir.
- Duyuru yayımlama: `POST /v1/duyuru` (pano anahtarıyla), gövdede en çok 600 baytlık metin. Duyuru kurumun imza anahtarıyla imzalanır, 12 saat geçerlidir.
- Telefonların kullandığı uçlar herkese açıktır: `POST /v1/acil` (zarf yükleme) ve `GET /v1/duyuru`. Zarf iş kanıtı taşımıyorsa, kuruma ait değilse, değiştirilmişse ya da bir günden eskiyse reddedilir.

Sistem hizmeti olarak çalıştırmak için (systemd):

```ini
[Service]
Environment=KURUM_PANO_ANAHTARI=<değer>
ExecStart=/usr/bin/node /opt/kairo-mesh/src/kurum-cli.js sunucu /guvenli/klasor 8080
Restart=always
User=kairo
```

## 3. Telefonlara dağıtım

- Kurum adına derlenen uygulamada kurum kodu gömülü gelir. Pilotta kod, uygulamanın **Ayarlar → Kurum kodu** alanına yapıştırılır.
- Kod üç parçadır: mühür anahtarı, imza anahtarı, sunucu adresi. Kodun kendisi gizli değildir; gizli olan, sunucudaki `kurum-anahtar.json` dosyasıdır.

## 4. Doğrulama

Kurulumdan sonra bir telefonda acil çağrı gönderilir. Telefonun interneti varsa çağrı birkaç saniye içinde panoda görünür, telefonda "Kurum çağrınızı aldı" yazar. İnternetsiz telefondan gönderilen çağrı, internetli bir telefonla karşılaşınca panoya düşer.
