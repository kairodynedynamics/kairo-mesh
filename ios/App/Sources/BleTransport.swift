import CoreBluetooth
import KairoCore

/// Same GATT layout as android/.../BleTransport.kt, so iPhones and Android phones form one mesh: every phone advertises
/// one service with one writable characteristic, connects to every phone it sees, and `send` writes to all of them.
final class BleTransport: NSObject, CBCentralManagerDelegate, CBPeripheralManagerDelegate, CBPeripheralDelegate {
    static let service = CBUUID(string: "6b616972-6f00-4d65-7368-000000000001")
    static let frame = CBUUID(string: "6b616972-6f00-4d65-7368-000000000002")
    private static let largestFrame = 464
    private static let maxQueue = 600
    private static let maxPeers = 7 // each open connection wakes the radio; a crowd would drain the battery for little gain

    private final class Peer {
        let peripheral: CBPeripheral
        var characteristic: CBCharacteristic?
        var queue: [Data] = []
        init(_ p: CBPeripheral) { peripheral = p }
    }
    /// CoreBluetooth never gives up on a connection attempt by itself: a phone that walked away (or changed its
    /// Bluetooth address) would hold a slot for ever, and after `maxPeers` of them nobody new could be reached.
    private static let baglanmaSuresi: TimeInterval = 15

    private let onFrame: (Bytes) -> Void
    private let log: (String) -> Void
    /// Called with false when Bluetooth is off or not allowed, true when it is on: the screen tells the caller.
    var onDurum: (Bool) -> Void = { _ in }
    private var central: CBCentralManager!
    private var server: CBPeripheralManager!
    private var peers: [UUID: Peer] = [:]

    var peerCount: Int { peers.values.filter { $0.characteristic != nil }.count }
    /// Connections being set up count too, but only until `baglanmaSuresi` runs out.
    private var bekleyen: Int { peers.values.filter { $0.characteristic == nil }.count }

    init(onFrame: @escaping (Bytes) -> Void, log: @escaping (String) -> Void) {
        self.onFrame = onFrame
        self.log = log
        super.init()
        // restore identifiers: if iOS ends the app in the background, a Bluetooth event relaunches it with its
        // connections and scan, so carrying goes on (UIBackgroundModes bluetooth-central / -peripheral)
        central = CBCentralManager(delegate: self, queue: .main, options: [CBCentralManagerOptionRestoreIdentifierKey: "kairo.central"])
        server = CBPeripheralManager(delegate: self, queue: .main, options: [CBPeripheralManagerOptionRestoreIdentifierKey: "kairo.peripheral"])
    }

    func send(_ frame: Bytes) {
        for peer in peers.values where peer.characteristic != nil {
            if peer.queue.count >= Self.maxQueue { peer.queue.removeFirst() } // the fountain code tolerates loss
            peer.queue.append(Data(frame))
            pump(peer)
        }
    }

    private func pump(_ peer: Peer) {
        guard let c = peer.characteristic else { return }
        while !peer.queue.isEmpty && peer.peripheral.canSendWriteWithoutResponse {
            peer.peripheral.writeValue(peer.queue.removeFirst(), for: c, type: .withoutResponse)
        }
    }

    // MARK: client side

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        guard c.state == .poweredOn else {
            log(c.state == .unauthorized ? "BLUETOOTH_IZNI_YOK" : "BLUETOOTH_KAPALI")
            return onDurum(false)
        }
        c.scanForPeripherals(withServices: [Self.service])
        log("transport started")
        onDurum(true)
    }

    func centralManager(_ c: CBCentralManager, willRestoreState dict: [String: Any]) {
        for p in dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? [] {
            p.delegate = self
            peers[p.identifier] = Peer(p)
            if p.state == .connected { p.discoverServices([Self.service]) } else { zamanAsimi(p) }
        }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        guard peers[p.identifier] == nil, peerCount + bekleyen < Self.maxPeers else { return }
        peers[p.identifier] = Peer(p)
        p.delegate = self
        c.connect(p)
        log("connecting \(p.identifier)")
        zamanAsimi(p)
    }

    /// Gives up on a connection that is not ready in `baglanmaSuresi`, so it frees its slot.
    private func zamanAsimi(_ p: CBPeripheral) {
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.baglanmaSuresi) { [weak self] in
            guard let self, let peer = self.peers[p.identifier], peer.characteristic == nil else { return }
            self.central.cancelPeripheralConnection(p)
            self.peers[p.identifier] = nil
        }
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) { p.discoverServices([Self.service]) }
    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) { peers[p.identifier] = nil }
    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        peers[p.identifier] = nil
        log("lost \(p.identifier)")
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = p.services?.first(where: { $0.uuid == Self.service }) else { return central.cancelPeripheralConnection(p) }
        p.discoverCharacteristics([Self.frame], for: s)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        guard let c = s.characteristics?.first(where: { $0.uuid == Self.frame }) else { return central.cancelPeripheralConnection(p) }
        guard p.maximumWriteValueLength(for: .withoutResponse) >= Self.largestFrame else {
            log("mtu too small for \(p.identifier)")
            return central.cancelPeripheralConnection(p)
        }
        peers[p.identifier]?.characteristic = c
        log("peer ready \(p.identifier)")
    }

    func peripheralIsReady(toSendWriteWithoutResponse p: CBPeripheral) { peers[p.identifier].map(pump) }

    // MARK: server side

    func peripheralManager(_ m: CBPeripheralManager, willRestoreState dict: [String: Any]) {}

    func peripheralManagerDidUpdateState(_ m: CBPeripheralManager) {
        guard m.state == .poweredOn else { return }
        m.removeAllServices() // after a restore the service may already be there
        let s = CBMutableService(type: Self.service, primary: true)
        s.characteristics = [CBMutableCharacteristic(type: Self.frame, properties: [.write, .writeWithoutResponse], value: nil, permissions: [.writeable])]
        m.add(s)
        m.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [Self.service]])
    }

    func peripheralManager(_ m: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for r in requests where r.offset == 0 { r.value.map { onFrame(Bytes($0)) } }
        requests.first.map { m.respond(to: $0, withResult: .success) }
    }
}
