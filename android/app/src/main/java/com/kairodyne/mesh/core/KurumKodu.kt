package com.kairodyne.mesh.core

import java.util.Base64

/**
 * An authority code, accepted only when a root key built into the app approved it (see src/kurum.js).
 * Without this, anyone spreading a fake code ("AFAD's new code") would receive the calls and locations of the
 * phones that entered it, and could sign announcements those phones trust.
 *   <sealing key> ; <announcement key> ; <https server> ; <authority name> ; <root signature over the first four>
 */
class KurumKodu(val pub: ByteArray, val signPub: ByteArray, val url: String, val ad: String) {
    companion object {
        private val ONEK = "kairo-kurum-1\n".toByteArray()

        /** The fields, or null when the code is malformed or no root in [kokler] signed it. */
        fun ac(code: String, kokler: List<ByteArray>): KurumKodu? = runCatching {
            val p = code.trim().split(";")
            if (p.size != 5 || !p[2].startsWith("http") || p[3].isEmpty()) return null
            val d = Base64.getDecoder()
            val pub = d.decode(p[0]); val signPub = d.decode(p[1]); val sig = d.decode(p[4])
            if (pub.size != 32 || signPub.size != 32) return null
            val msg = ONEK + p.subList(0, 4).joinToString(";").toByteArray()
            if (kokler.none { verifySignature(it, msg, sig) }) return null
            KurumKodu(pub, signPub, p[2].trimEnd('/'), p[3])
        }.getOrNull()
    }
}
