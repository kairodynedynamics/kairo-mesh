package com.kairodyne.mesh.core

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature verification (RFC 8032, section 5.1.7), with no dependency. The platform's provider does not
 * offer Ed25519 on every Android version this app supports (seen missing on API 34): without this, authority codes
 * and signed announcements would be refused on those phones. Verification only; phones never sign with Ed25519.
 * Plain BigInteger arithmetic: a few milliseconds per signature, which is rare (a code once, an announcement a day).
 */
object Ed25519 {
    private val P = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)
    private val L = BigInteger.TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")
    private val D = BigInteger.valueOf(-121665).mod(P) * BigInteger.valueOf(121666).modInverse(P) % P
    private val I = BigInteger.TWO.modPow((P - BigInteger.ONE) / BigInteger.valueOf(4), P) // sqrt(-1)
    private val BY = BigInteger.valueOf(4) * BigInteger.valueOf(5).modInverse(P) % P
    private val B = punto(xBul(BY, false)!!, BY)

    /** A point in extended coordinates (X, Y, Z, T) with x = X/Z, y = Y/Z, xy = T/Z. */
    private class Nokta(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private fun punto(x: BigInteger, y: BigInteger) = Nokta(x, y, BigInteger.ONE, x * y % P)

    private fun topla(a: Nokta, b: Nokta): Nokta {
        val aa = (a.y - a.x) * (b.y - b.x) % P
        val bb = (a.y + a.x) * (b.y + b.x) % P
        val cc = BigInteger.TWO * a.t * b.t % P * D % P
        val dd = BigInteger.TWO * a.z * b.z % P
        val e = bb - aa; val f = dd - cc; val g = dd + cc; val h = bb + aa
        return Nokta(e * f % P, g * h % P, f * g % P, e * h % P)
    }

    private fun carp(s: BigInteger, p: Nokta): Nokta {
        var q = Nokta(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        var n = p
        for (i in 0 until s.bitLength()) {
            if (s.testBit(i)) q = topla(q, n)
            n = topla(n, n)
        }
        return q
    }

    private fun esit(a: Nokta, b: Nokta) =
        (a.x * b.z - b.x * a.z).mod(P).signum() == 0 && (a.y * b.z - b.y * a.z).mod(P).signum() == 0

    private fun xBul(y: BigInteger, isaret: Boolean): BigInteger? {
        if (y >= P) return null
        val u = (y * y - BigInteger.ONE).mod(P)
        val v = (D * y * y + BigInteger.ONE).mod(P)
        val x2 = u * v.modInverse(P) % P
        if (x2.signum() == 0) return if (isaret) null else BigInteger.ZERO
        var x = x2.modPow((P + BigInteger.valueOf(3)) / BigInteger.valueOf(8), P)
        if ((x * x - x2).mod(P).signum() != 0) x = x * I % P
        if ((x * x - x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != isaret) x = P - x
        return x
    }

    private fun kucukUcSayi(b: ByteArray) = BigInteger(1, b.reversedArray())

    private fun coz(b: ByteArray): Nokta? {
        val y = kucukUcSayi(b.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() })
        val x = xBul(y, (b[31].toInt() and 0x80) != 0) ?: return null
        return punto(x, y)
    }

    fun dogrula(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        if (pub.size != 32 || sig.size != 64) return false
        val a = coz(pub) ?: return false
        val r = coz(sig.copyOfRange(0, 32)) ?: return false
        val s = kucukUcSayi(sig.copyOfRange(32, 64))
        if (s >= L) return false // malleability: reject non-canonical S
        val h = MessageDigest.getInstance("SHA-512").run { update(sig, 0, 32); update(pub); update(msg); digest() }
        val k = kucukUcSayi(h).mod(L)
        // [S]B = R + [k]A (cofactorless check, as RFC 8032 permits)
        return esit(carp(s, B), topla(r, carp(k, a)))
    }
}
