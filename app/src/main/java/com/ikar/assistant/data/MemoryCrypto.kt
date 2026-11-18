package com.ikar.assistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated encryption for Room string fields.
 * The AES-256 key is non-exportable and held by Android Keystore.
 */
object MemoryCrypto {
    private const val KEY_ALIAS = "ikar.memory.aesgcm.v1"
    private const val PREFIX = "IKAR1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        getOrCreateKey()
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    @Synchronized
    fun encrypt(plainText: String): String {
        if (isEncrypted(plainText)) return plainText
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    @Synchronized
    fun decrypt(storedValue: String): String {
        if (!isEncrypted(storedValue)) return storedValue // legacy row; migrated on database open
        val payload = try {
            Base64.decode(storedValue.removePrefix(PREFIX), Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("Mémoire chiffrée illisible.", error)
        }
        require(payload.size > 12 + 16) { "Mémoire chiffrée tronquée." }
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        checkNotNull(appContext) { "MemoryCrypto.initialize(context) doit être appelé avant Room." }
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
