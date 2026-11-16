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
        return try? Triyaj(meta: Data(contentsOf: m), weights: Data(contentsOf: w))
    }()

    override init() {
        // no identity means no mesh; refusing to start beats inventing a key that is not kept safely
        do { identity = try Identity.create(Keychain.secret()) } catch { fatalError("cannot load identity from the Keychain: \(error)") }
        code = Data(identity.pub).base64EncodedString()
        super.init()
        transport = BleTransport(onFrame: { [weak self] in self?.node.receive($0) }, log: { [weak self] in self?.show($0) })
        buildNode()
        show("READY code=\(code)")
        path.pathUpdateHandler = { [weak self] p in DispatchQueue.main.async { self?.online = p.status == .satisfied } }
        path.start(queue: DispatchQueue(label: "kairo.path"))
        location.delegate = self
        location.requestWhenInUseAuthorization()
        UIDevice.current.isBatteryMonitoringEnabled = true
        tick()
    }

    /// (Re)creates the node, e.g. when the authority code changes. Mail queued in memory is not kept across it.
    private func buildNode() {
        let k = defaults.string(forKey: "kurum").flatMap(Kurum.init(code:))
        kurumVar = k != nil
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
            self?.defaults.removeObject(forKey: "bekleyenAcil")
            self?.acilDurum = "✓ KURUM ÇAĞRINIZI ALDI (\(Mesh.saat()))."
            self?.show("ACIL_ULASTI id=\(id)")
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
        if k != nil, let bekleyen = defaults.string(forKey: "bekleyenAcil"), let id = try? node.sendEmergency(bekleyen) {
            acilDurum = "Önceki çağrınız yeniden gönderildi; kurum henüz almadı."
            show("ACIL_YENIDEN id=\(id)")
        }
    }

    private func tick() {
        let device = UIDevice.current
        let charging = device.batteryState == .charging || device.batteryState == .full
        // batteryLevel is -1 where it is unknown (the simulator): treat that as enough
        let level = device.batteryLevel < 0 ? 100 : Int(device.batteryLevel * 100)
        // Carrying for others is a favour: it stops when the owner needs the battery, and resumes on the charger.
        node.relay = charging || (!ProcessInfo.processInfo.isLowPowerModeEnabled && level > 20)
        node.tick()
        status = "STATUS peers=\(transport.peerCount) carrying=\(node.carrying) relay=\(node.relay) online=\(online)"
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
        guard kurumVar else {
            acilDurum = "Kurum kodu tanımlı değil: çağrının gideceği kurumun kodunu Ayarlar'a girin."
            return show("ERROR kurum kodu yok")
        }
        let loc = location.location // GPS works without a network
        acilDurum = "Çağrı hazırlanıyor…"
        let model = triyaj
        // reading the text and the proof of work take a moment: the text off the main thread, then the node on it
        DispatchQueue.global(qos: .userInitiated).async {
            let k = metin.trimmingCharacters(in: .whitespaces).isEmpty ? nil : model?.coz(metin)
            DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let gecerli = Set(Cagri.turler.map(\.0)), ozelGecerli = Set(Cagri.ozel.map(\.0))
            // an unrecognised or off-topic message still goes out as a call for rescue: missing a call costs more
            let tur = secilen ?? k.flatMap { gecerli.contains($0.tur) ? $0.tur : nil } ?? "enkaz"
            let json = Cagri.rapor(tur: tur, kisi: kisi ?? k?.kisi, ozel: ozel.union((k?.ozel ?? []).filter(ozelGecerli.contains)), metin: metin,
                                   enlem: loc?.coordinate.latitude, boylam: loc?.coordinate.longitude,
                                   dogruluk: loc?.horizontalAccuracy, zaman: loc?.timestamp ?? Date(),
                                   adres: ["il": k?.il, "ilce": k?.ilce, "mahalle": k?.mahalle, "sokak": k?.sokak, "bina": k?.bina],
                                   // typed on the emergency screen: the model may not rank it below 2 (it misses
                                   // ~13% of life-threatening calls on unseen phrasings; triyaj/model/olcum.json)
                                   aciliyet: k.map { secilen == nil ? min($0.aciliyet, 2) : $0.aciliyet },
                                   model: k != nil && secilen == nil)
            do {
                let id = try self.node.sendEmergency(json)
                self.defaults.set(json, forKey: "bekleyenAcil") // kept until the authority's receipt arrives
                self.acilDurum = "Çağrınız gönderildi (\(Mesh.saat())). Yakındaki telefonlar taşıyor; internete ulaşan ilk telefon kuruma iletecek."
                self.show("ACIL_GONDERILDI id=\(id) konum=\(loc == nil ? "yok" : "var")")
            } catch { self.show("ERROR \(error)") }
            }
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways {
            manager.startUpdatingLocation()
        }
    }

    static func saat() -> String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f.string(from: Date())
    }

    private func show(_ line: String) {
        NSLog("Kairo %@", line)
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
