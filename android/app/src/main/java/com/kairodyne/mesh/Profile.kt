package com.kairodyne.mesh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.kairodyne.mesh.core.Identity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Identity, contacts and delivered set in app-private storage. The private key is stored encrypted under an AES key
 * that lives in the Android Keystore and cannot be exported, so a copy of the app's files does not reveal it.
 */
class Profile(context: Context) {
    private val prefs = context.getSharedPreferences("kairo", Context.MODE_PRIVATE)
    val identity: Identity

    init {
        val wrapped = prefs.getString("secret_enc", null)
        // builds before the Keystore move kept the key in the clear under "secret": migrate it once
        val secret = wrapped?.let { unwrap(b64(it)) } ?: prefs.getString("secret", null)?.let(::b64)
        identity = Identity.create(secret)
        if (wrapped == null) prefs.edit().putString("secret_enc", b64(wrap(identity.secret))).remove("secret").commit()
    }

    val code: String get() = b64(identity.pub)

    val contacts: List<Pair<String, ByteArray>>
        get() = prefs.getStringSet("contacts", emptySet())!!.map { it.split("=", limit = 2) }.map { it[0] to b64(it[1]) }

    fun addContact(name: String, code: String) {
        prefs.edit().putStringSet("contacts", prefs.getStringSet("contacts", emptySet())!! + "$name=$code").apply()
    }

    var delivered: List<Pair<String, String>>
        get() = prefs.getString("delivered", "")!!.split(",").filter { it.isNotEmpty() }.map { it.substringBefore(":") to it.substringAfter(":") }
        // commit, not apply: the message is shown only once its id is on disk, so a crash cannot reopen a replay
        set(value) { prefs.edit().putString("delivered", value.joinToString(",") { "${it.first}:${it.second}" }).commit() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun unwrap(box: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, box, 0, 12))
            doFinal(box, 12, box.size - 12)
        }

    private companion object {
        const val ALIAS = "kairo-identity-wrap"
        fun b64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
        fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    }
}
