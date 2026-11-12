package com.kairodyne.mesh.core

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun ByteArray.sub(from: Int, to: Int): ByteArray = copyOfRange(from, to)
internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
internal fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
internal fun cat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var i = 0
    for (p in parts) { p.copyInto(out, i); i += p.size }
    return out
}
internal fun u8(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }
internal fun ByteArray.u16(at: Int): Int = ((this[at].toInt() and 255) shl 8) or (this[at + 1].toInt() and 255)
internal fun ByteArray.u32(at: Int): Long = (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (this[at + i].toLong() and 255) }
internal fun ByteArray.u64(at: Int): Long = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (this[at + i].toLong() and 255) }
internal fun be32(v: Long): ByteArray = ByteArray(4) { (v ushr (24 - 8 * it)).toByte() }
internal fun be64(v: Long): ByteArray = ByteArray(8) { (v ushr (56 - 8 * it)).toByte() }

/** SHA-256 over byte arrays and UTF-8 strings, matching the reference `sha256(...parts)`. */
fun sha256(vararg parts: Any): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) md.update(if (p is String) p.toByteArray(Charsets.UTF_8) else p as ByteArray)
    return md.digest()
}

internal fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    for (d in data) mac.update(d)
    return mac.doFinal()
}

internal fun same(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
