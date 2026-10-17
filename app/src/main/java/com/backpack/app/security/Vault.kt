package com.backpack.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 1.9.0 (Alex): private data encrypted on the phone, always: My places,
 * recent searches, recorded trips, the trip to resume, your routing key.
 * AES-256-GCM with a key that lives in the phone's secure hardware
 * (Android Keystore) and never leaves it; nothing is sent anywhere.
 * Values written before 1.9.0 (plain) are read once and moved in here.
 */
object Vault {

    private const val ALIAS = "backpack_vault_v1"
    private const val PREFS = "backpack_vault"
    /** Marks an encrypted file (older plain files have no such start). */
    private val FILE_MAGIC = "BPV1".toByteArray()
    private const val PLAIN = "p:"

    @Volatile private var cachedKey: SecretKey? = null

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            val k = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
                generateKey()
            }
            cachedKey = k
            return k
        }
    }

    /** iv (12 bytes) + ciphertext with its tag. */
    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }

    // ---------- small values ----------

    fun getString(c: Context, name: String): String? {
        val v = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return null
        if (v.startsWith(PLAIN)) return v.substring(PLAIN.length)
        return try { String(open(Base64.decode(v, Base64.NO_WRAP)), Charsets.UTF_8) } catch (e: Exception) { null }
    }

    fun putString(c: Context, name: String, value: String?) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (value == null) p.remove(name)
        // A phone whose secure hardware refuses (rare): kept as before rather than lost.
        else p.putString(name, try { Base64.encodeToString(seal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) } catch (e: Exception) { PLAIN + value })
        p.apply()
    }

    /** A value kept in plain [prefsName] before 1.9.0: moved in here (and removed there). */
    fun migrate(c: Context, prefsName: String, oldKey: String, name: String) {
        val old = c.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val v = old.getString(oldKey, null) ?: return
        if (getString(c, name) == null) putString(c, name, v)
        // Only dropped once the encrypted copy reads back.
        if (getString(c, name) != null) old.edit().remove(oldKey).apply()
    }

    // ---------- files ----------

    fun writeFile(f: File, text: String) {
        val data = try { FILE_MAGIC + seal(text.toByteArray(Charsets.UTF_8)) } catch (e: Exception) { text.toByteArray(Charsets.UTF_8) }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(f)) { f.writeBytes(data); tmp.delete() }
    }

    /** Encrypted or (written before 1.9.0) plain. */
    fun readFile(f: File): String {
        val bytes = f.readBytes()
        if (bytes.size > FILE_MAGIC.size && bytes.copyOfRange(0, FILE_MAGIC.size).contentEquals(FILE_MAGIC)) {
            return String(open(bytes.copyOfRange(FILE_MAGIC.size, bytes.size)), Charsets.UTF_8)
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** A plain file from before 1.9.0: encrypted in place. */
    fun sealFileIfPlain(f: File) {
        try {
            val bytes = f.readBytes()
            if (bytes.size >= FILE_MAGIC.size && bytes.copyOfRange(0, FILE_MAGIC.size).contentEquals(FILE_MAGIC)) return
            writeFile(f, String(bytes, Charsets.UTF_8))
        } catch (e: Exception) { }
    }
}
