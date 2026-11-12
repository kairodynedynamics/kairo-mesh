#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

// Port of android/.../core/Bytes.kt.

public typealias Bytes = [UInt8]

extension Array where Element == UInt8 {
    func sub(_ from: Int, _ to: Int) -> Bytes { Array(self[from..<to]) }
    public var hex: String {
        let digits: [Character] = Array<Character>("0123456789abcdef")
        return String(flatMap { [digits[Int($0 >> 4)], digits[Int($0 & 15)]] })
    }
    func u16(_ at: Int) -> Int { Int(self[at]) << 8 | Int(self[at + 1]) }
    func u32(_ at: Int) -> UInt64 { (0..<4).reduce(0) { $0 << 8 | UInt64(self[at + $1]) } }
    func u64(_ at: Int) -> UInt64 { (0..<8).reduce(0) { $0 << 8 | UInt64(self[at + $1]) } }
}

extension String {
    /// Bytes from a hex string; nil on odd length or a non-hex digit.
    public var unhex: Bytes? {
        let chars = Array(utf8)
        guard chars.count % 2 == 0 else { return nil }
        var out = Bytes()
        out.reserveCapacity(chars.count / 2)
        for i in stride(from: 0, to: chars.count, by: 2) {
            guard let b = UInt8(String(decoding: chars[i...i + 1], as: UTF8.self), radix: 16) else { return nil }
            out.append(b)
        }
        return out
    }
}

func cat(_ parts: Bytes...) -> Bytes { parts.flatMap { $0 } }
func u8(_ v: Int...) -> Bytes { v.map { UInt8(truncatingIfNeeded: $0) } }
func be32(_ v: UInt64) -> Bytes { (0..<4).map { UInt8(truncatingIfNeeded: v >> (24 - 8 * UInt64($0))) } }
func be64(_ v: UInt64) -> Bytes { (0..<8).map { UInt8(truncatingIfNeeded: v >> (56 - 8 * UInt64($0))) } }

/// A part of a hash input: raw bytes or a UTF-8 string, matching the reference `sha256(...parts)`.
public protocol HashPart { var bytes: Bytes { get } }
extension Array: HashPart where Element == UInt8 { public var bytes: Bytes { self } }
extension String: HashPart { public var bytes: Bytes { Array(utf8) } }

public func sha256(_ parts: HashPart...) -> Bytes {
    var h = SHA256()
    for p in parts { h.update(data: p.bytes) }
    return Array(h.finalize())
}

func hmac(_ key: Bytes, _ data: Bytes...) -> Bytes {
    var h = HMAC<SHA256>(key: SymmetricKey(data: key))
    for d in data { h.update(data: d) }
    return Array(h.finalize())
}

/// Constant-time comparison.
func same(_ a: Bytes, _ b: Bytes) -> Bool {
    guard a.count == b.count else { return false }
    return zip(a, b).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
}
