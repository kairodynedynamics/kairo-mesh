import CoreLocation
import Foundation
import KairoCore
import Network
import Security
import UIKit

/// The phone's node: identity in the Keychain, contacts and delivered set in UserDefaults, frames over BleTransport.
/// Everything runs on the main thread (the Bluetooth managers, network callbacks and timers deliver there), so the
/// node needs no lock.
///
/// Emergency relay: with an authority code set, the phone carries everyone's emergency calls, and whenever it has
/// internet it uploads them to the authority in the background and fetches the authority's signed announcements.
final class Mesh: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var log: [String] = []
    @Published private(set) var status = ""
    @Published private(set) var acilDurum = ""
    @Published private(set) var duyurular: [String] = []
    @Published private(set) var kurumVar = false
    @Published private(set) var kurumAd = ""
    @Published private(set) var yakin = 0
    @Published private(set) var internet = false
    /// The id of the caller's last call, for "I sent this by mistake".
    @Published private(set) var sonCagriId: String?
    @Published private(set) var kurumAldi = false
    let code: String
    private let identity: Identity
    private var node: Node!
    private var transport: BleTransport!
    private var timer: Timer?
    private let defaults = UserDefaults.standard
    private let path = NWPathMonitor()
    private var online = false
    private let location = CLLocationManager()
    private lazy var triyaj: Triyaj? = {
        guard let m = Bundle.main.url(forResource: "triyaj", withExtension: "json"),
              let w = Bundle.main.url(forResource: "triyaj", withExtension: "bin") else { return nil }
        // the encoder makes the message-level decisions; without it the character model reads the message alone
        let kod = Bundle.main.url(forResource: "kodlayici", withExtension: "json").flatMap { km in
            Bundle.main.url(forResource: "kodlayici", withExtension: "bin").flatMap { kw in
                Bundle.main.url(forResource: "kodlayici-kelime", withExtension: "bin").flatMap { kk in
                    try? Kodlayici(meta: Data(contentsOf: km), weights: Data(contentsOf: kw, options: .mappedIfSafe),
                                   kelimeler: Data(contentsOf: kk))
                }
            }
        }
        return try? Triyaj(meta: Data(contentsOf: m), weights: Data(contentsOf: w), kodlayici: kod)
    }()

    override init() {
        // no identity means no mesh; refusing to start beats inventing a key that is not kept safely
        do { identity = try Identity.create(Keychain.secret()) } catch { fatalError("cannot load identity from the Keychain: \(error)") }
        code = Data(identity.pub).base64EncodedString()
        super.init()
        // a frame from a nearby phone also drives the node: the timer stops while the app is in the background, but
        // Bluetooth events still wake it, so the caller's own call keeps being offered with the screen locked
        transport = BleTransport(onFrame: { [weak self] in self?.node.receive($0); self?.uyanikTick() }, log: { [weak self] in self?.show($0) })
        // debug builds come with a demo authority approved by the public test root, so the app can be tried at once
        #if DEBUG
        // the authority's code is built into the app: nobody has to find and paste one (release builds get the real
        // authority's root-approved code when there is a contract). "-demoKurumYok": the UI test of a build without one.
        let argumanlar = ProcessInfo.processInfo.arguments
        if argumanlar.contains("-demoKurumYok") { defaults.removeObject(forKey: "kurum") }
        // UI tests: an authority approved by the test root at an address that does not exist, so a simulator with
        // internet never puts test calls on the live operator demo's dashboard
        else if argumanlar.contains("-testKurumu") { defaults.set(Mesh.testKurumu, forKey: "kurum") }
        else if defaults.string(forKey: "kurum")?.contains("demo.kairodynedynamics.com") ?? true {
            defaults.set(Mesh.demoKurum, forKey: "kurum") // the old demo authority had no server: move to the operator demo
        }
        #endif
        buildNode()
        show("READY code=\(code)")
        path.pathUpdateHandler = { [weak self] p in DispatchQueue.main.async { self?.online = p.status == .satisfied } }
        path.start(queue: DispatchQueue(label: "kairo.path"))
        location.delegate = self
        location.requestWhenInUseAuthorization()
        location.desiredAccuracy = kCLLocationAccuracyBest
        UIDevice.current.isBatteryMonitoringEnabled = true
        tick()
    }

    /// (Re)creates the node, e.g. when the authority code changes. Mail queued in memory is not kept across it.
    private func buildNode() {
        let k = defaults.string(forKey: "kurum").flatMap(Kurum.init(code:))
        kurumVar = k != nil
        kurumAd = k?.ad ?? ""
        let delivered = (defaults.stringArray(forKey: "delivered") ?? []).compactMap { entry -> (String, String)? in
            let parts = entry.split(separator: ":")
            return parts.count == 2 ? (String(parts[0]), String(parts[1])) : nil
        }
        let uplink: ((Bytes, @escaping (Bytes?) -> Void) -> Void)? = k.map { kk in { env, done in kk.upload(env, done) } }
        let downlink: ((@escaping ([Bytes]) -> Void) -> Void)? = k.map { kk in { done in kk.announcements(done) } }
        node = Node(identity: identity, send: { [weak self] in self?.transport.send($0) }, onMessage: { [weak self] from, text in
            guard let self else { return }
            // on disk before it is shown, so a restart cannot be used to replay it
            self.defaults.set(self.node.delivered.map { "\($0.0):\($0.1)" }, forKey: "delivered")
            self.show("RECV from=\(from) text=\(text)")
        }, delivered: delivered,
        authority: k?.pub, online: { [weak self] in self?.online ?? false },
        uplink: uplink,
        onEmergencyDelivered: { [weak self] id in
            guard let self else { return }
            // only a receipt for this caller's pending call clears it (an older call's receipt must not)
            if (self.defaults.stringArray(forKey: "bekleyenIdler") ?? []).contains(id) { self.defaults.removeObject(forKey: "bekleyenAcil") }
            self.kurumAldi = true
            self.acilDurum = "✓ KURUM ÇAĞRINIZI ALDI (\(Mesh.saat())).\nYardım ekipleri bilgilendirildi. Mümkünse yerinizde kalın, sesli sinyali kullanın."
            self.show("ACIL_ULASTI id=\(id)")
        },
        authoritySign: k?.signPub,
        downlink: downlink,
        onAnnouncement: { [weak self] n in
            self?.duyurular.insert(n.text, at: 0)
            self?.show("DUYURU \(n.text)")
        })
        for (name, code) in contacts { if let pub = Data(base64Encoded: code) { try? node.addContact(name, Bytes(pub)) } }
        // A call the authority has not confirmed survives a restart of the app or the phone: it is sent again. The
        // authority may then receive it twice; a duplicate costs less than a lost call.
        if k != nil, defaults.string(forKey: "bekleyenAcil") != nil {
            yenidenGonder()
            acilDurum = "Önceki çağrınız yeniden gönderildi; kurum henüz almadı."
        }
    }

    /// Sends the pending call again: after a restart, and every 6 hours while unconfirmed (the mesh drops an
    /// envelope after a day). The authority may receive it twice; a duplicate costs less than a lost call.
    private func yenidenGonder() {
        guard let bekleyen = defaults.string(forKey: "bekleyenAcil"), let id = try? node.sendEmergency(bekleyen) else { return }
        defaults.set(Date().timeIntervalSince1970, forKey: "bekleyenZaman")
        defaults.set((defaults.stringArray(forKey: "bekleyenIdler") ?? []) + [id], forKey: "bekleyenIdler")
        sonCagriId = id
        show("ACIL_YENIDEN id=\(id)")
    }

    private var sonTick = Date.distantPast
    private func uyanikTick() { if Date().timeIntervalSince(sonTick) >= 2 { timer?.invalidate(); tick() } }

    private func tick() {
        sonTick = Date()
        let device = UIDevice.current
        let charging = device.batteryState == .charging || device.batteryState == .full
        // batteryLevel is -1 where it is unknown (the simulator): treat that as enough
        let level = device.batteryLevel < 0 ? 100 : Int(device.batteryLevel * 100)
        // Carrying for others is a favour: it stops when the owner needs the battery, and resumes on the charger.
        node.relay = charging || (!ProcessInfo.processInfo.isLowPowerModeEnabled && level > 20)
        node.tick()
        status = "STATUS peers=\(transport.peerCount) carrying=\(node.carrying) relay=\(node.relay) online=\(online)"
        yakin = max(node.yakin(), transport.peerCount) // heard from, or connected to
        internet = online
        if kurumVar, defaults.string(forKey: "bekleyenAcil") != nil,
           Date().timeIntervalSince1970 - defaults.double(forKey: "bekleyenZaman") > 6 * 3600 { yenidenGonder() }
        // HELLOs only matter to phones in range, and only often while there is mail to offer them
        let next: TimeInterval = transport.peerCount > 0 && node.carrying > 0 ? 2 : 10
        timer = Timer.scheduledTimer(withTimeInterval: next, repeats: false) { [weak self] _ in self?.tick() }
    }

    private var contacts: [String: String] {
        get { defaults.dictionary(forKey: "contacts") as? [String: String] ?? [:] }
        set { defaults.set(newValue, forKey: "contacts") }
    }

    func addContact(_ name: String, _ code: String) {
        guard !name.isEmpty, let pub = Data(base64Encoded: code), (try? node.addContact(name, Bytes(pub))) != nil else {
            return show("ERROR bad pairing code for \(name)")
        }
        contacts[name] = code
        show("PAIRED \(name)")
    }

    func send(_ name: String, _ text: String) {
        do {
            try node.sendMessage(name, text)
            show("QUEUED to=\(name)")
        } catch { show("ERROR \(error)") }
    }

    func setKurum(_ code: String) {
        guard let k = Kurum(code: code) else {
            acilDurum = "Bu kurum kodu yetkili makamca onaylanmamış ya da bozuk; kaydedilmedi. Kodu yalnızca kurumun resmî kanalından alın."
            return show("ERROR kurum kodu geçersiz ya da yetkili makamca onaylanmamış")
        }
        defaults.set(k.code, forKey: "kurum")
        buildNode()
        acilDurum = "Çağrılarınız şu kuruma gider: \(k.ad)"
        show("KURUM \(k.url) ad=\(k.ad)")
    }

    /// What the caller tapped always wins; the on-device model fills in what is missing from the text.
    func emergency(tur secilen: String?, kisi: Int?, ozel: Set<String>, metin: String) {
        guard kurumVar else { return kurumYok() }
        let loc = location.location // the freshest fix: asked for when the screen opened and when send was pressed
        acilDurum = "Çağrı hazırlanıyor…"
        kurumAldi = false
        let model = triyaj
        // reading the text and the proof of work take a moment: the text off the main thread, then the node on it
        DispatchQueue.global(qos: .userInitiated).async {
            let t0 = Date()
            let k = metin.trimmingCharacters(in: .whitespaces).isEmpty ? nil : model?.coz(metin)
            let ms = Int(Date().timeIntervalSince(t0) * 1000)
            DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let gecerli = Set(Cagri.turler.map(\.0)), ozelGecerli = Set(Cagri.ozel.map(\.0))
            // a message the model could not place still goes out, as "another emergency" (urgency 2), never as
            // "under rubble" (see MeshService.kt)
            let tur = secilen ?? k.flatMap { gecerli.contains($0.tur) ? $0.tur : nil } ?? "yardim"
            // what the caller tapped, and what the phone's model read from the text (see Kurum.kt)
            var secimSozluk: [String: Any] = ["ozel": ozel.sorted()]
            if let secilen { secimSozluk["tur"] = secilen }
            if let kisi { secimSozluk["kisi"] = kisi }
            var okumaSozluk: [String: Any]? = nil
            if let o = k {
                var d: [String: Any] = ["model": model?.modelAdi ?? "", "ms": ms, "tur": o.tur, "aciliyet": o.aciliyet, "ozel": o.ozel]
                if let v = o.kisi { d["kisi"] = v }
                let adres: [(String, String?)] = [("il", o.il), ("ilce", o.ilce), ("mahalle", o.mahalle), ("sokak", o.sokak), ("bina", o.bina)]
                for (a, v) in adres { if let v, !v.isEmpty { d[a] = v } }
                okumaSozluk = d
            }
            let json = Cagri.rapor(tur: tur, kisi: kisi ?? k?.kisi, ozel: ozel.union((k?.ozel ?? []).filter(ozelGecerli.contains)), metin: metin,
                                   enlem: loc?.coordinate.latitude, boylam: loc?.coordinate.longitude,
                                   dogruluk: loc?.horizontalAccuracy, zaman: loc?.timestamp ?? Date(),
                                   adres: ["il": k?.il, "ilce": k?.ilce, "mahalle": k?.mahalle, "sokak": k?.sokak, "bina": k?.bina],
                                   // typed on the emergency screen: the model may not rank it below 2 (the encoder
                                   // still ranks ~3% of life-threatening calls too low; triyaj/model/kodlayici-olcum.json)
                                   aciliyet: k.map { secilen == nil ? min($0.aciliyet, 2) : $0.aciliyet },
                                   model: k != nil && secilen == nil,
                                   secim: secimSozluk, okuma: okumaSozluk, cihaz: Mesh.cihaz())
            do {
                let id = try self.node.sendEmergency(json)
                self.defaults.set(json, forKey: "bekleyenAcil") // kept until the authority's receipt arrives
                self.defaults.set([id], forKey: "bekleyenIdler")
                self.defaults.set(Date().timeIntervalSince1970, forKey: "bekleyenZaman")
                self.sonCagriId = id
                self.acilDurum = "Çağrınız gönderildi (\(Mesh.saat())).\nYakındaki telefonlar taşıyor; internete ulaşan ilk telefon kuruma iletecek. Uygulamayı kapatmayın."
                self.show("ACIL_GONDERILDI id=\(id) konum=\(loc == nil ? "yok" : "var")")
                self.konumTakip(loc)
            } catch { self.acilDurum = "Çağrı gönderilemedi: \(error)"; self.show("ERROR \(error)") }
            }
        }
    }

    func durumYaz(_ yazi: String) { acilDurum = yazi }

    func iptalBildir() { acilDurum = "Çağrı iptal edildi; hiçbir şey gönderilmedi." }

    func kurumYok() {
        acilDurum = "Çağrı gönderilemez: bu sürümde çağrıların gideceği kurum tanımlı değil (kurumla sözleşme öncesi sürüm)."
        show("ERROR kurum kodu yok")
    }

    /// Ask for a fresh fix (one-shot): GPS works without a network, and is not left running to save the battery.
    func konumIste() {
        if location.authorizationStatus == .authorizedWhenInUse || location.authorizationStatus == .authorizedAlways { location.requestLocation() }
    }

    /// The caller sent a call by mistake, or no longer needs help: a correction for the earlier call.
    func duzelt() {
        guard kurumVar, let ref = sonCagriId else { return }
        do {
            let id = try node.sendEmergency(Cagri.duzeltme(ref: ref))
            defaults.removeObject(forKey: "bekleyenAcil") // the earlier call is no longer resent
            acilDurum = "Düzeltme gönderildi (\(Mesh.saat())): kuruma önceki çağrının yanlışlıkla gönderildiği bildiriliyor. "
                + "Yine de yardım gerekiyorsa çağrıyı yeniden gönderin."
            sonCagriId = nil
            show("DUZELTME_GONDERILDI id=\(id) ref=\(ref)")
        } catch { show("ERROR \(error)") }
    }

    #if DEBUG
    static let testKurumu = "rgExj/KgKZhYcPxK/NqXSk3/nmndBd5JIsybQuPvEXg=;9sbrD4AbOZNuhvB+CDzzHS+lxvMaqOmg5eulfRvSH7k=;https://kurum.example;Test Kurumu (yalnız test);epNYkhZFP0A8TRWKthNdQg0KDvNv8fwfdUA2DI5r/v6Uz+3wXYe2jrn/WFPJ8+9MpXv1/zJNzndXsG1N5tPXBA==" // test/kurum-kodu-ornek.json
    static let demoKurum = "mdUshEx3g4fN93giVHNo85Sj514ZAnJmwzN2mBU/bTU=;tIp0lLyJX6b7Co+dC7Ipt5BjJBNZXUzbnk9UVuDqQy4=;https://kairodynedynamics.com/operator;KairoMesh Operatör Demo - test;W5eevjIWZV2sLHFwyYrsWO9EjfyGC6fZYSf/Zm94HxcKPMr/1DnYSCW4QQpiTL/hm/fHl6EhtlcIXO6VMlWCBA=="
    #endif

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        // one fix now; never left running (that drains the battery under rubble)
        if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways { manager.requestLocation() }
    }

    /// The sending phone, for the dispatcher: model, system, app version, battery.
    static func cihaz() -> [String: Any] {
        var u = utsname(); uname(&u)
        let makine = withUnsafeBytes(of: &u.machine) { b in String(decoding: b.prefix { $0 != 0 }, as: UTF8.self) } // e.g. iPhone15,2
        UIDevice.current.isBatteryMonitoringEnabled = true
        var c: [String: Any] = ["model": "Apple \(makine)", "sistem": "iOS \(UIDevice.current.systemVersion)",
                                "uygulama": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""]
        let pil = UIDevice.current.batteryLevel
        let durum = UIDevice.current.batteryState
        if pil >= 0 { c["pil"] = Int(pil * 100); c["sarj"] = durum == .charging || durum == .full }
        return c
    }

    private var konumTakipBitis: Date?
    private var ilkKonum: CLLocation?
    private var konumEsik: CLLocationAccuracy = .greatestFiniteMagnitude

    /// A call must never stay without a location: with none, an old or a coarse one, the phone listens to GPS for a
    /// few minutes and sends the first good fix as a follow-up the dashboard shows with the call.
    private func konumTakip(_ ilk: CLLocation?) {
        guard location.authorizationStatus == .authorizedWhenInUse || location.authorizationStatus == .authorizedAlways else {
            acilDurum += "\n\nKonum izni kapalı: kurtarıcılar yerinizi bilemez. Ayarlar > KairoMesh > Konum'u açın."
            return show("KONUM_IZNI_YOK")
        }
        let taze = ilk.map { Date().timeIntervalSince($0.timestamp) < 60 && $0.horizontalAccuracy >= 0 } ?? false
        if taze, let ilk, ilk.horizontalAccuracy <= 50 { return }
        // decided now, not when a fix arrives: only a clearly more precise fix is worth a follow-up
        konumEsik = taze ? (ilk?.horizontalAccuracy ?? 0) * 0.7 : .greatestFiniteMagnitude
        if ilk == nil { acilDurum += "\n\nKonum henüz alınamadı; telefon aramayı sürdürüyor ve bulunca kuruma ayrıca gönderecek." }
        ilkKonum = ilk
        konumTakipBitis = Date().addingTimeInterval(300)
        location.startUpdatingLocation()
        DispatchQueue.main.asyncAfter(deadline: .now() + 300) { [weak self] in self?.konumTakipBitir() }
    }

    private func konumTakipBitir() { konumTakipBitis = nil; location.stopUpdatingLocation() }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let bitis = konumTakipBitis, Date() < bitis, let l = locations.last, l.horizontalAccuracy >= 0 else { return }
        if l.horizontalAccuracy >= konumEsik { return }
        konumTakipBitir()
        do {
            _ = try node.sendEmergency(Cagri.konumGuncel(l))
            acilDurum += "\n\n✓ Konumunuz bulundu ve kuruma gönderildi (\(Mesh.saat()))."
            show("KONUM_GUNCELLENDI")
        } catch { show("ERROR konum güncellemesi gönderilemedi: \(error)") }
    }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}

    static func saat() -> String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f.string(from: Date())
    }

    private func show(_ line: String) {
        NSLog("KairoMesh %@", line)
        log.append(line)
    }
}

/// The private key, kept in the Keychain on this device only and readable after the first unlock,
/// so the mesh can keep carrying mail while the phone is locked.
enum Keychain {
    struct Failure: Error { let status: OSStatus }
    private static let item: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                              kSecAttrService as String: "com.kairodyne.mesh", kSecAttrAccount as String: "identity"]

    static func secret() throws -> Bytes {
        var out: CFTypeRef?
        let found = SecItemCopyMatching(item.merging([kSecReturnData as String: true]) { $1 } as CFDictionary, &out)
        if found == errSecSuccess, let data = out as? Data { return Bytes(data) }
        guard found == errSecItemNotFound else { throw Failure(status: found) }
        let secret = try Identity.create().secret
        let added = SecItemAdd(item.merging([kSecValueData as String: Data(secret),
                                             kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]) { $1 } as CFDictionary, nil)
        guard added == errSecSuccess else { throw Failure(status: added) }
        return secret
    }
}
