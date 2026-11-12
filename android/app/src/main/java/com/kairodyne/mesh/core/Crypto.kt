package com.kairodyne.mesh.core

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Port of src/crypto.js: X25519 + HKDF-SHA256 + ChaCha20-Poly1305. Same envelope bytes on both sides. Unaudited.

const val EPOCH_MS = 15 * 60 * 1000L
const val DAY_MS = 86_400_000L

private val SPKI = "302a300506032b656e032100".unhex()
private val PKCS8 = "302e020100300506032b656e04220420".unhex()
private val NONCE = ByteArray(12) // every key is derived from a fresh ephemeral, so each is used once

class Identity private constructor(internal val privateKey: PrivateKey, val pub: ByteArray, val secret: ByteArray) {
    companion object {
        fun create(secret: ByteArray? = null): Identity {
            // Android's XDH key generator takes no curve parameter, so new keys come from 32 random bytes on both platforms.
            val raw32 = secret ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            val key = KeyFactory.getInstance("XDH").generatePrivate(PKCS8EncodedKeySpec(cat(PKCS8, raw32)))
            val raw = key.encoded.let { it.sub(it.size - 32, it.size) }
            // The JDK and Android cannot derive a public key from a private one directly: it is DH(secret, basepoint).
            return Identity(key, dh(key, u8(9, *IntArray(31))), raw)
        }
    }
}

private fun dh(privateKey: PrivateKey, pub: ByteArray): ByteArray {
    val publicKey = KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(cat(SPKI, pub)))
    return KeyAgreement.getInstance("XDH").run { init(privateKey); doPhase(publicKey, true); generateSecret() }
}

private fun kdf(secret: ByteArray, salt: ByteArray, info: String): ByteArray =
    hmac(hmac(salt, secret), info.toByteArray(Charsets.UTF_8), u8(1))

private fun cipher(mode: Int, key: ByteArray, aad: ByteArray): Cipher =
    Cipher.getInstance("ChaCha20-Poly1305").apply {
        init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(NONCE))
        updateAAD(aad)
    }
private fun aead(key: ByteArray, plain: ByteArray, aad: ByteArray = ByteArray(0)) = cipher(Cipher.ENCRYPT_MODE, key, aad).doFinal(plain)
private fun unaead(key: ByteArray, box: ByteArray, aad: ByteArray = ByteArray(0)) = cipher(Cipher.DECRYPT_MODE, key, aad).doFinal(box)

private fun pad(body: ByteArray): ByteArray {
    var size = 256
    while (size < body.size + 4) size *= 2
    return cat(be32(body.size.toLong()), body).copyOf(size)
}

fun seal(sender: Identity, recipientPub: ByteArray, body: ByteArray): ByteArray {
    val eph = Identity.create()
    val salt = cat(eph.pub, recipientPub)
    val es = dh(eph.privateKey, recipientPub)
    val who = aead(kdf(es, salt, "kairo-seal-1"), sender.pub)
    val ss = dh(sender.privateKey, recipientPub)
    return cat(eph.pub, who, aead(kdf(cat(es, ss), salt, "kairo-seal-2"), pad(body), who))
}

class Opened(val senderPub: ByteArray, val body: ByteArray)

/** Throws if the envelope is not for us or was altered. */
fun open(recipient: Identity, envelope: ByteArray): Opened {
    require(envelope.size >= 32 + 48 + 16 + 256) { "short envelope" }
    val ephPub = envelope.sub(0, 32)
    val who = envelope.sub(32, 80)
    val salt = cat(ephPub, recipient.pub)
    val es = dh(recipient.privateKey, ephPub)
    val senderPub = unaead(kdf(es, salt, "kairo-seal-1"), who)
    val ss = dh(recipient.privateKey, senderPub)
    val padded = unaead(kdf(cat(es, ss), salt, "kairo-seal-2"), envelope.sub(80, envelope.size), who)
    val len = padded.u32(0)
    require(len <= padded.size - 4) { "bad padding" }
    return Opened(senderPub, padded.sub(4, 4 + len.toInt()))
}

private fun period(timeMs: Long, ms: Long) = Math.floorDiv(timeMs, ms).toString().toByteArray(Charsets.UTF_8)

fun recipientTag(pub: ByteArray, timeMs: Long): ByteArray = hmac(sha256(pub), period(timeMs, DAY_MS)).sub(0, 16)
fun recognitionKey(pub: ByteArray): ByteArray = sha256("kairo-rk", pub)
fun ephemeralId(key: ByteArray, timeMs: Long): ByteArray = hmac(key, period(timeMs, EPOCH_MS)).sub(0, 8)

/** Which contact (by recognition key) is using `eid`, tolerating one epoch of clock skew. */
fun recognise(eid: ByteArray, contacts: Map<String, ByteArray>, timeMs: Long): String? {
    if (eid.size != 8) return null
    for ((name, key) in contacts) for (skew in longArrayOf(0, -EPOCH_MS, EPOCH_MS))
        if (same(eid, ephemeralId(key, timeMs + skew))) return name
    return null
}
