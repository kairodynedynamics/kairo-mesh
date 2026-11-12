// Port of android/.../core/Fountain.kt; the test vectors check that all ports produce identical packets.

/// mulberry32, returning the raw 32-bit output (the reference divides it by 2^32).
struct Rng {
    private var a: UInt32
    init(_ seed: UInt64) { a = UInt32(truncatingIfNeeded: seed) }
    mutating func next() -> UInt32 {
        a = a &+ 0x6d2b79f5
        var t = (a ^ (a >> 15)) &* (1 | a)
        t = (t &+ ((t ^ (t >> 7)) &* (61 | t))) ^ t
        return t ^ (t >> 14)
    }
}

public let symbolSizeDefault = 469
private let maxK = 4096

private func coeffs(_ seed: UInt64, _ k: Int) -> [UInt32] {
    var c = [UInt32](repeating: 0, count: (k + 31) / 32)
    if seed < UInt64(k) {
        c[Int(seed >> 5)] = 1 << UInt32(seed & 31)
        return c
    }
    var r = Rng(seed)
    for i in c.indices { c[i] = r.next() }
    if k & 31 != 0 { c[c.count - 1] &= (1 << UInt32(k & 31)) - 1 }
    if lowestBit(c) < 0 { c[0] = 1 }
    return c
}

private func lowestBit(_ c: [UInt32]) -> Int {
    for i in c.indices where c[i] != 0 { return i * 32 + c[i].trailingZeroBitCount }
    return -1
}

private func has(_ c: [UInt32], _ bit: Int) -> Bool { c[bit >> 5] & (1 << UInt32(bit & 31)) != 0 }

public struct Encoder {
    public let k: Int
    private let symbolSize: Int
    private var buf: Bytes

    public init(_ message: Bytes, symbolSize: Int = symbolSizeDefault) throws {
        self.symbolSize = symbolSize
        k = max(1, (message.count + 4 + symbolSize - 1) / symbolSize)
        guard k <= maxK else { throw KairoError.tooLarge }
        buf = Bytes(repeating: 0, count: k * symbolSize)
        buf.replaceSubrange(0..<4, with: be32(UInt64(message.count)))
        buf.replaceSubrange(4..<4 + message.count, with: message)
    }

    /// One coded packet; `seed` is an unsigned 32-bit value.
    public func packet(_ seed: UInt64) -> Bytes {
        let c = coeffs(seed, k)
        var data = Bytes(repeating: 0, count: symbolSize)
        for i in 0..<k where has(c, i) {
            for j in 0..<symbolSize { data[j] ^= buf[i * symbolSize + j] }
        }
        return data
    }
}

public final class Decoder {
    private let k: Int, symbolSize: Int
    private var rows: [([UInt32], Bytes)?]
    public private(set) var rank = 0
    public var done: Bool { rank == k }

    public init(_ k: Int, symbolSize: Int = symbolSizeDefault) throws {
        guard (1...maxK).contains(k) else { throw KairoError.malformed }
        self.k = k
        self.symbolSize = symbolSize
        rows = Array(repeating: nil, count: k)
    }

    /// Returns true if the packet added new information; malformed packets are ignored.
    @discardableResult
    public func add(_ seed: UInt64, _ data: Bytes) -> Bool {
        guard seed <= 0xffffffff, data.count == symbolSize else { return false }
        var c = coeffs(seed, k), d = data
        while true {
            let p = lowestBit(c)
            if p < 0 { return false }
            guard let row = rows[p] else { rows[p] = (c, d); rank += 1; return true }
            for i in c.indices { c[i] ^= row.0[i] }
            for i in d.indices { d[i] ^= row.1[i] }
        }
    }

    public func result() throws -> Bytes {
        guard rank == k else { throw KairoError.malformed }
        var r = rows.map { $0! }
        for p in stride(from: k - 1, through: 1, by: -1) {
            for q in 0..<p where has(r[q].0, p) {
                for i in r[q].0.indices { r[q].0[i] ^= r[p].0[i] }
                for i in r[q].1.indices { r[q].1[i] ^= r[p].1[i] }
            }
        }
        let buf = r.flatMap { $0.1 }
        let len = buf.u32(0)
        guard len <= UInt64(buf.count - 4) else { throw KairoError.malformed }
        return buf.sub(4, 4 + Int(len))
    }
}

public enum KairoError: Error { case tooLarge, malformed, unknownContact, outboxFull }
