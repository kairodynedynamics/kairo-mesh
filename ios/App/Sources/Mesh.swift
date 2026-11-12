import Foundation
import KairoCore
import Security
import UIKit

/// The phone's node: identity in the Keychain, contacts and delivered set in UserDefaults, frames over BleTransport.
/// Everything runs on the main thread (the Bluetooth managers deliver there too), so the node needs no lock.
final class Mesh: ObservableObject {
    @Published private(set) var log: [String] = []
    @Published private(set) var status = ""
    let code: String
    private var node: Node!
    private var transport: BleTransport!
    private var timer: Timer?
    private let defaults = UserDefaults.standard

    init() {
        let identity: Identity
        // no identity means no mesh; refusing to start beats inventing a key that is not kept safely
        do { identity = try Identity.create(Keychain.secret()) } catch { fatalError("cannot load identity from the Keychain: \(error)") }
        code = Data(identity.pub).base64EncodedString()
        let delivered = (defaults.stringArray(forKey: "delivered") ?? []).compactMap { entry -> (String, String)? in
            let parts = entry.split(separator: ":")
            return parts.count == 2 ? (String(parts[0]), String(parts[1])) : nil
        }
        transport = BleTransport(onFrame: { [weak self] in self?.node.receive($0) }, log: { [weak self] in self?.show($0) })
        node = Node(identity: identity, send: { [weak self] in self?.transport.send($0) }, onMessage: { [weak self] from, text in
            guard let self else { return }
            // on disk before it is shown, so a restart cannot be used to replay it
            self.defaults.set(self.node.delivered.map { "\($0.0):\($0.1)" }, forKey: "delivered")
            self.show("RECV from=\(from) text=\(text)")
        }, delivered: delivered)
        for (name, code) in contacts { if let pub = Data(base64Encoded: code) { try? node.addContact(name, Bytes(pub)) } }
        show("READY code=\(code)")
        UIDevice.current.isBatteryMonitoringEnabled = true
        tick()
    }

    private func tick() {
        let device = UIDevice.current
        let charging = device.batteryState == .charging || device.batteryState == .full
        // batteryLevel is -1 where it is unknown (the simulator): treat that as enough
        let level = device.batteryLevel < 0 ? 100 : Int(device.batteryLevel * 100)
        // Carrying for others is a favour: it stops when the owner needs the battery, and resumes on the charger.
        node.relay = charging || (!ProcessInfo.processInfo.isLowPowerModeEnabled && level > 20)
        node.tick()
        status = "STATUS peers=\(transport.peerCount) carrying=\(node.carrying) relay=\(node.relay)"
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
