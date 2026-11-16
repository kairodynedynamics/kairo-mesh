import Foundation

/// An authority code, accepted only when a root key built into the app approved it (see src/kurum.js). Without
/// this, anyone spreading a fake code ("AFAD's new code") would receive the calls and locations of the phones that
/// entered it, and could sign announcements those phones trust. Same format and check as Android's KurumKodu.kt:
///   <sealing key> ; <announcement key> ; <https server> ; <authority name> ; <root signature over the first four>
public struct KurumKodu: Equatable {
    public let pub: Bytes, signPub: Bytes, url: String, ad: String

    /// Nil when the code is malformed or no root in `kokler` signed it.
    public init?(_ code: String, kokler: [Bytes]) {
        let p = code.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ";", omittingEmptySubsequences: false).map(String.init)
        guard p.count == 5, p[2].hasPrefix("http"), !p[3].isEmpty,
              let a = Data(base64Encoded: p[0]), a.count == 32, let s = Data(base64Encoded: p[1]), s.count == 32,
              let sig = Data(base64Encoded: p[4]) else { return nil }
        let msg = Bytes(("kairo-kurum-1\n" + p[0..<4].joined(separator: ";")).utf8)
        guard kokler.contains(where: { verifySignature($0, msg, Bytes(sig)) }) else { return nil }
        pub = Bytes(a); signPub = Bytes(s); ad = p[3]
        url = p[2].hasSuffix("/") ? String(p[2].dropLast()) : p[2]
    }
}
