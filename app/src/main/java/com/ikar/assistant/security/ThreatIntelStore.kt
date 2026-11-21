package com.ikar.assistant.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local URLhaus indicator cache. The Auth-Key is encrypted with an Android Keystore AES key.
 * No API key or scanned URL is sent anywhere except the documented URLhaus endpoint.
 */
object ThreatIntelStore {
    private const val PREFS = "ikar_threat_intel"
    private const val KEY_ALIAS = "ikar_urlhaus_auth_key_v1"
    private const val ENCRYPTED_KEY = "encrypted_auth_key"
    private const val URLS = "active_malware_urls"
    private const val UPDATED_AT = "updated_at"
    private const val COUNT = "indicator_count"

    fun saveAuthKey(context: Context, authKey: String) {
        require(authKey.trim().length >= 16) { "La clé URLhaus semble trop courte." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(authKey.trim().toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ENCRYPTED_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    fun hasAuthKey(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(ENCRYPTED_KEY)

    fun readAuthKey(context: Context): String? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ENCRYPTED_KEY, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > 12)
            val iv = payload.copyOfRange(0, 12)
            val ciphertext = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    fun replaceActiveUrls(context: Context, urls: Set<String>) {
        val normalized = urls.asSequence().map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct().take(5000).toList()
        val array = JSONArray()
        normalized.forEach(array::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(URLS, array.toString())
            .putLong(UPDATED_AT, System.currentTimeMillis())
            .putInt(COUNT, normalized.size)
            .apply()
    }

    fun isKnownMalicious(context: Context, rawUrl: String): Boolean {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(URLS, null)
            ?: return false
        val parsed = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val host = parsed.host?.lowercase() ?: return false
        val candidates = runCatching { JSONArray(stored) }.getOrNull() ?: return false
        for (i in 0 until candidates.length()) {
            val known = candidates.optString(i)
            if (known.equals(rawUrl.trim(), ignoreCase = true)) return true
            val knownUri = runCatching { URI(known) }.getOrNull() ?: continue
            val knownHost = knownUri.host?.lowercase()
            val rootDomainIndicator = knownUri.rawPath.isNullOrBlank() ||
                knownUri.rawPath == "/" && knownUri.rawQuery.isNullOrBlank()
            if (rootDomainIndicator && knownHost != null && knownHost == host) return true
        }
        return false
    }

    fun isKnownMaliciousHost(context: Context, rawHost: String): Boolean {
        val host = rawHost.trim().trimEnd('.').lowercase()
        if (host.isBlank()) return false
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(URLS, null)
            ?: return false
        val candidates = runCatching { JSONArray(stored) }.getOrNull() ?: return false
        for (i in 0 until candidates.length()) {
            val knownUri = runCatching { URI(candidates.optString(i)) }.getOrNull() ?: continue
            val knownHost = knownUri.host?.lowercase()?.trimEnd('.')
            val rootDomainIndicator = knownUri.rawPath.isNullOrBlank() ||
                knownUri.rawPath == "/" && knownUri.rawQuery.isNullOrBlank()
            if (rootDomainIndicator && knownHost != null && (host == knownHost || host.endsWith(".$knownHost"))) return true
        }
        return false
    }

    fun lastUpdatedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(UPDATED_AT, 0L)

    fun indicatorCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(COUNT, 0)

    private fun getOrCreateSecretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
