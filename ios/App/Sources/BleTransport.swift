import CoreBluetooth
import KairoCore

/// Same GATT layout as android/.../BleTransport.kt, so iPhones and Android phones form one mesh: every phone advertises
/// one service with one writable characteristic, connects to every phone it sees, and `send` writes to all of them.
final class BleTransport: NSObject, CBCentralManagerDelegate, CBPeripheralManagerDelegate, CBPeripheralDelegate {
    static let service = CBUUID(string: "6b616972-6f00-4d65-7368-000000000001")
    static let frame = CBUUID(string: "6b616972-6f00-4d65-7368-000000000002")
    private static let largestFrame = 464
    private static let maxQueue = 600

    private final class Peer {
        let peripheral: CBPeripheral
        var characteristic: CBCharacteristic?
        var queue: [Data] = []
        init(_ p: CBPeripheral) { peripheral = p }
    }

    private let onFrame: (Bytes) -> Void
    private let log: (String) -> Void
    private var central: CBCentralManager!
    private var server: CBPeripheralManager!
    private var peers: [UUID: Peer] = [:]

    var peerCount: Int { peers.values.filter { $0.characteristic != nil }.count }

    init(onFrame: @escaping (Bytes) -> Void, log: @escaping (String) -> Void) {
        self.onFrame = onFrame
        self.log = log
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
        server = CBPeripheralManager(delegate: self, queue: .main)
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
        guard c.state == .poweredOn else { return log("bluetooth unavailable (\(c.state.rawValue))") }
        c.scanForPeripherals(withServices: [Self.service])
        log("transport started")
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        guard peers[p.identifier] == nil else { return }
        peers[p.identifier] = Peer(p)
        p.delegate = self
        c.connect(p)
        log("connecting \(p.identifier)")
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

    func peripheralManagerDidUpdateState(_ m: CBPeripheralManager) {
        guard m.state == .poweredOn else { return }
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
