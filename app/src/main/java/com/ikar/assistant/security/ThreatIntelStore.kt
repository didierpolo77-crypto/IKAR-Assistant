package com.ikar.assistant.security

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
 * URLhaus indicators are stored in indexed SQLite tables without an application-defined
 * row limit. The Auth-Key remains encrypted with Android Keystore AES-GCM.
 */
object ThreatIntelStore {
    private const val PREFS = "ikar_threat_intel"
    private const val KEY_ALIAS = "ikar_urlhaus_auth_key_v1"
    private const val ENCRYPTED_KEY = "encrypted_auth_key"
    private const val URLS = "active_malware_urls"
    private const val HASHES = "known_payload_sha256"
    private const val UPDATED_AT = "updated_at"
    private const val COUNT = "indicator_count"

    @Volatile private var dbHelper: IndicatorDb? = null

    private class IndicatorDb(context: Context) :
        SQLiteOpenHelper(context.applicationContext, "ikar_urlhaus_indicators.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE indicators (
                    indicator TEXT NOT NULL PRIMARY KEY,
                    kind TEXT NOT NULL,
                    host TEXT,
                    is_root INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL
                )"""
            )
            db.execSQL("CREATE INDEX index_indicators_kind_host ON indicators(kind, host)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private fun database(context: Context): SQLiteDatabase {
        val current = dbHelper ?: synchronized(this) {
            dbHelper ?: IndicatorDb(context).also { dbHelper = it }
        }
        val db = current.writableDatabase
        migrateLegacyPreferences(context, db)
        return db
    }

    private fun migrateLegacyPreferences(context: Context, db: SQLiteDatabase) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("sqlite_migrated", false)) return
        val urls = runCatching { JSONArray(prefs.getString(URLS, "[]") ?: "[]") }.getOrNull()
        val hashes = runCatching { JSONArray(prefs.getString(HASHES, "[]") ?: "[]") }.getOrNull()
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            if (urls != null) {
                for (i in 0 until urls.length()) {
                    val value = urls.optString(i).trim()
                    if (value.startsWith("http://") || value.startsWith("https://")) {
                        putUrl(db, value, now)
                    }
                }
            }
            if (hashes != null) {
                for (i in 0 until hashes.length()) {
                    val value = hashes.optString(i).trim().lowercase()
                    if (value.matches(Regex("[a-f0-9]{64}"))) putHash(db, value, now)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        prefs.edit().remove(URLS).remove(HASHES).putBoolean("sqlite_migrated", true).apply()
    }

    private fun putUrl(db: SQLiteDatabase, value: String, now: Long) {
        val uri = runCatching { URI(value) }.getOrNull() ?: return
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return
        val root = (uri.rawPath.isNullOrBlank() || uri.rawPath == "/") && uri.rawQuery.isNullOrBlank()
        val row = ContentValues().apply {
            put("indicator", value)
            put("kind", "url")
            put("host", host)
            put("is_root", if (root) 1 else 0)
            put("updated_at", now)
        }
        db.insertWithOnConflict("indicators", null, row, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun putHash(db: SQLiteDatabase, value: String, now: Long) {
        val row = ContentValues().apply {
            put("indicator", value)
            put("kind", "sha256")
            putNull("host")
            put("is_root", 0)
            put("updated_at", now)
        }
        db.insertWithOnConflict("indicators", null, row, SQLiteDatabase.CONFLICT_REPLACE)
    }

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
            .distinct().toList()
        val db = database(context)
        db.beginTransaction()
        try {
            db.delete("indicators", "kind = ?", arrayOf("url"))
            val now = System.currentTimeMillis()
            normalized.forEach { putUrl(db, it, now) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(UPDATED_AT, System.currentTimeMillis())
            .putInt(COUNT, normalized.size)
            .apply()
    }

    fun replaceKnownSha256(context: Context, hashes: Set<String>) {
        val normalized = hashes.asSequence().map { it.trim().lowercase() }
            .filter { it.matches(Regex("[a-f0-9]{64}")) }.distinct().toList()
        val db = database(context)
        db.beginTransaction()
        try {
            db.delete("indicators", "kind = ?", arrayOf("sha256"))
            val now = System.currentTimeMillis()
            normalized.forEach { putHash(db, it, now) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("hash_count", normalized.size)
            .apply()
    }

    /** Add recent URLhaus payload hashes without deleting previously learned indicators. */
    fun mergeKnownSha256(context: Context, hashes: Set<String>) {
        val normalized = hashes.asSequence().map { it.trim().lowercase() }
            .filter { it.matches(Regex("[a-f0-9]{64}")) }.distinct().toList()
        if (normalized.isEmpty()) return
        val db = database(context)
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            normalized.forEach { putHash(db, it, now) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("hash_count", hashCount(context))
            .apply()
    }

    fun isKnownSha256(context: Context, sha256: String): Boolean {
        val normalized = sha256.trim().lowercase()
        if (!normalized.matches(Regex("[a-f0-9]{64}"))) return false
        return database(context).rawQuery(
            "SELECT 1 FROM indicators WHERE kind = 'sha256' AND indicator = ? LIMIT 1",
            arrayOf(normalized)
        ).use { it.moveToFirst() }
    }

    fun hashCount(context: Context): Int =
        database(context).rawQuery("SELECT COUNT(*) FROM indicators WHERE kind = 'sha256'", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun isKnownMalicious(context: Context, rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        val exact = rawUrl.trim()
        return database(context).rawQuery(
            """SELECT 1 FROM indicators WHERE kind = 'url' AND
                (indicator = ? OR (is_root = 1 AND host = ?)) LIMIT 1""",
            arrayOf(exact, host)
        ).use { it.moveToFirst() }
    }

    fun isKnownMaliciousHost(context: Context, rawHost: String): Boolean {
        val host = rawHost.trim().trimEnd('.').lowercase()
        if (host.isBlank()) return false
        return database(context).rawQuery(
            """SELECT 1 FROM indicators WHERE kind = 'url'
                AND (host = ? OR (is_root = 1 AND ? LIKE '%.' || host)) LIMIT 1""",
            arrayOf(host, host)
        ).use { it.moveToFirst() }
    }

    fun lastUpdatedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(UPDATED_AT, 0L)

    fun indicatorCount(context: Context): Int =
        database(context).rawQuery("SELECT COUNT(*) FROM indicators WHERE kind = 'url'", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun setUpdateStatus(context: Context, status: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("update_status", status.take(160))
            .putLong("last_attempt_at", System.currentTimeMillis())
            .apply()
    }

    fun updateStatus(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("update_status", "Aucune mise à jour effectuée.") ?: "Aucune mise à jour effectuée."

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
