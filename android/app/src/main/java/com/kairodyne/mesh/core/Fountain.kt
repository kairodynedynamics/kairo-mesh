package com.kairodyne.mesh.core

// Port of src/rng.js and src/fountain.js; the test vectors check that both produce identical packets.

/** mulberry32, returning the raw 32-bit output (the reference divides it by 2^32). */
internal class Rng(seed: Long) {
    private var a = seed.toInt()
    fun next(): Int {
        a += 0x6d2b79f5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        return t xor (t ushr 14)
    }
}

const val SYMBOL_SIZE = 469
private const val MAX_K = 4096

private fun coeffs(seed: Long, k: Int): IntArray {
    val c = IntArray((k + 31) / 32)
    if (seed < k) {
        c[(seed ushr 5).toInt()] = 1 shl (seed.toInt() and 31)
        return c
    }
    val r = Rng(seed)
    for (i in c.indices) c[i] = r.next()
    if (k and 31 != 0) c[c.size - 1] = c[c.size - 1] and ((1 shl (k and 31)) - 1)
    if (lowestBit(c) < 0) c[0] = 1
    return c
}

private fun lowestBit(c: IntArray): Int {
    for (i in c.indices) if (c[i] != 0) return i * 32 + Integer.numberOfTrailingZeros(c[i])
    return -1
}

private fun xorInto(dst: ByteArray, src: ByteArray, srcOff: Int = 0) {
    for (i in dst.indices) dst[i] = (dst[i].toInt() xor src[srcOff + i].toInt()).toByte()
}
private fun xorInto(dst: IntArray, src: IntArray) { for (i in dst.indices) dst[i] = dst[i] xor src[i] }
private fun IntArray.has(bit: Int) = this[bit ushr 5] and (1 shl (bit and 31)) != 0

class Encoder(message: ByteArray, private val symbolSize: Int = SYMBOL_SIZE) {
    val k = maxOf(1, (message.size + 4 + symbolSize - 1) / symbolSize)
    private val buf: ByteArray

    init {
        require(k <= MAX_K) { "message too large" }
        buf = ByteArray(k * symbolSize)
        be32(message.size.toLong()).copyInto(buf)
        message.copyInto(buf, 4)
    }

    /** One coded packet; `seed` is an unsigned 32-bit value. */
    fun packet(seed: Long): ByteArray {
        val c = coeffs(seed, k)
        val data = ByteArray(symbolSize)
        for (i in 0 until k) if (c.has(i)) xorInto(data, buf, i * symbolSize)
        return data
    }
}

class Decoder(private val k: Int, private val symbolSize: Int = SYMBOL_SIZE) {
    private class Row(val c: IntArray, val d: ByteArray)
    private val rows = arrayOfNulls<Row>(k)
    var rank = 0
        private set
    val done get() = rank == k

    init { require(k in 1..MAX_K) { "bad k" } }

    /** Returns true if the packet added new information; malformed packets are ignored. */
    fun add(seed: Long, data: ByteArray): Boolean {
        if (seed !in 0..0xffffffffL || data.size != symbolSize) return false
        val c = coeffs(seed, k)
        val d = data.copyOf()
        while (true) {
            val p = lowestBit(c)
            if (p < 0) return false
            val row = rows[p]
            if (row == null) { rows[p] = Row(c, d); rank++; return true }
            xorInto(c, row.c)
            xorInto(d, row.d)
        }
    }

    fun result(): ByteArray {
        check(rank == k) { "not enough packets" }
        val r = rows.map { it!! }
        for (p in k - 1 downTo 1) for (q in 0 until p)
            if (r[q].c.has(p)) { xorInto(r[q].c, r[p].c); xorInto(r[q].d, r[p].d) }
        val buf = cat(*r.map { it.d }.toTypedArray())
        val len = buf.u32(0)
        check(len <= buf.size - 4) { "corrupt length" }
        return buf.sub(4, 4 + len.toInt())
    }
}
