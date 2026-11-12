#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

// Port of android/.../core/Crypto.kt: X25519 + HKDF-SHA256 + ChaCha20-Poly1305. Same envelope bytes on all ports. Unaudited.

public let epochMs: Int64 = 15 * 60 * 1000
public let dayMs: Int64 = 86_400_000

private let zeroNonce = try! ChaChaPoly.Nonce(data: Bytes(repeating: 0, count: 12)) // every key is derived from a fresh ephemeral, so each is used once

public struct Identity {
    let privateKey: Curve25519.KeyAgreement.PrivateKey
    public let pub: Bytes
    public let secret: Bytes

    /// A new identity, or the one belonging to a stored 32-byte secret.
    public static func create(_ secret: Bytes? = nil) throws -> Identity {
        let key = try secret.map { try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: $0) } ?? Curve25519.KeyAgreement.PrivateKey()
        return Identity(privateKey: key, pub: Array(key.publicKey.rawRepresentation), secret: Array(key.rawRepresentation))
    }
}

private func dh(_ privateKey: Curve25519.KeyAgreement.PrivateKey, _ pub: Bytes) throws -> Bytes {
    let shared = try privateKey.sharedSecretFromKeyAgreement(with: Curve25519.KeyAgreement.PublicKey(rawRepresentation: pub))
    return shared.withUnsafeBytes { Array($0) }
}

private func kdf(_ secret: Bytes, _ salt: Bytes, _ info: String) -> Bytes { hmac(hmac(salt, secret), Array(info.utf8), u8(1)) }

private func aead(_ key: Bytes, _ plain: Bytes, _ aad: Bytes = []) throws -> Bytes {
    let box = try ChaChaPoly.seal(plain, using: SymmetricKey(data: key), nonce: zeroNonce, authenticating: aad)
    return Array(box.ciphertext) + Array(box.tag)
}

private func unaead(_ key: Bytes, _ box: Bytes, _ aad: Bytes = []) throws -> Bytes {
    guard box.count >= 16 else { throw KairoError.malformed }
    let sealed = try ChaChaPoly.SealedBox(nonce: zeroNonce, ciphertext: box.sub(0, box.count - 16), tag: box.sub(box.count - 16, box.count))
    return Array(try ChaChaPoly.open(sealed, using: SymmetricKey(data: key), authenticating: aad))
}

private func pad(_ body: Bytes) -> Bytes {
    var size = 256
    while size < body.count + 4 { size *= 2 }
    return cat(be32(UInt64(body.count)), body) + Bytes(repeating: 0, count: size - body.count - 4)
}

public func seal(_ sender: Identity, _ recipientPub: Bytes, _ body: Bytes) throws -> Bytes {
    let eph = try Identity.create()
    let salt = cat(eph.pub, recipientPub)
    let es = try dh(eph.privateKey, recipientPub)
    let who = try aead(kdf(es, salt, "kairo-seal-1"), sender.pub)
    let ss = try dh(sender.privateKey, recipientPub)
    return cat(eph.pub, who, try aead(kdf(cat(es, ss), salt, "kairo-seal-2"), pad(body), who))
}

public struct Opened { public let senderPub: Bytes; public let body: Bytes }

/// Throws if the envelope is not for us or was altered.
public func open(_ recipient: Identity, _ envelope: Bytes) throws -> Opened {
    guard envelope.count >= 32 + 48 + 16 + 256 else { throw KairoError.malformed }
    let ephPub = envelope.sub(0, 32)
    let who = envelope.sub(32, 80)
    let salt = cat(ephPub, recipient.pub)
    let es = try dh(recipient.privateKey, ephPub)
    let senderPub = try unaead(kdf(es, salt, "kairo-seal-1"), who)
    let ss = try dh(recipient.privateKey, senderPub)
    let padded = try unaead(kdf(cat(es, ss), salt, "kairo-seal-2"), envelope.sub(80, envelope.count), who)
    let len = padded.u32(0)
    guard len <= UInt64(padded.count - 4) else { throw KairoError.malformed }
    return Opened(senderPub: senderPub, body: padded.sub(4, 4 + Int(len)))
}

private func period(_ timeMs: Int64, _ ms: Int64) -> Bytes {
    let q = timeMs / ms - (timeMs % ms < 0 ? 1 : 0) // floor division, as in the reference
    return Array(String(q).utf8)
}

public func recipientTag(_ pub: Bytes, _ timeMs: Int64) -> Bytes { hmac(sha256(pub), period(timeMs, dayMs)).sub(0, 16) }
public func recognitionKey(_ pub: Bytes) -> Bytes { sha256("kairo-rk", pub) }
public func ephemeralId(_ key: Bytes, _ timeMs: Int64) -> Bytes { hmac(key, period(timeMs, epochMs)).sub(0, 8) }

/// Which contact (by recognition key) is using `eid`, tolerating one epoch of clock skew.
public func recognise(_ eid: Bytes, _ contacts: [(String, Bytes)], _ timeMs: Int64) -> String? {
    guard eid.count == 8 else { return nil }
    for (name, key) in contacts {
        for skew in [0, -epochMs, epochMs] where same(eid, ephemeralId(key, timeMs + skew)) { return name }
    }
    return nil
}
