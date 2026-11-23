package com.ikar.assistant

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted recovery copies kept in IKAR's private storage.
 * This is a user-created backup, not automatic ransomware rollback.
 */
object RansomwareRecoveryVault {
    data class Backup(
        val id: String,
        val originalName: String,
        val sizeBytes: Long
    )

    private const val PREFS = "ikar_recovery_vault"
    private const val ITEMS = "items"
    private const val KEY_ALIAS = "ikar_recovery_vault_aes"
    private const val TAG = "IKAR_VAULT"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun directory(context: Context): File =
        File(context.filesDir, "ikar_recovery_vault").apply { mkdirs() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun list(context: Context): List<Backup> {
        val raw = prefs(context).getString(ITEMS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Backup(
                    item.optString("id"),
                    item.optString("name", "fichier"),
                    item.optLong("size", 0L)
                ).takeIf { it.id.isNotBlank() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun backup(context: Context, sourceUri: Uri): Backup {
        val resolver = context.contentResolver
        val name = queryName(context, sourceUri) ?: "fichier"
        val id = UUID.randomUUID().toString()
        val destination = File(directory(context), "$id.enc")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        var size = 0L
        try {
            resolver.openInputStream(sourceUri)?.use { input ->
                BufferedInputStream(input).use { bufferedInput ->
                    FileOutputStream(destination).use { fileOutput ->
                        fileOutput.write(cipher.iv)
                        CipherOutputStream(BufferedOutputStream(fileOutput), cipher).use { encrypted ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = bufferedInput.read(buffer)
                                if (count < 0) break
                                size += count
                                encrypted.write(buffer, 0, count)
                            }
                        }
                    }
                }
            } ?: throw IllegalStateException("Impossible d'ouvrir le fichier source.")
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
        val record = Backup(id, name, size)
        val array = JSONArray()
        list(context).forEach { existing ->
            array.put(JSONObject().put("id", existing.id).put("name", existing.originalName).put("size", existing.sizeBytes))
        }
        array.put(JSONObject().put("id", record.id).put("name", record.originalName).put("size", record.sizeBytes))
        prefs(context).edit().putString(ITEMS, array.toString()).apply()
        return record
    }

    fun restoreToNewDocument(context: Context, backupId: String, targetTree: Uri): Uri {
        val record = list(context).firstOrNull { it.id == backupId }
            ?: throw IllegalArgumentException("Copie de récupération introuvable.")
        val encryptedFile = File(directory(context), "$backupId.enc")
        if (!encryptedFile.exists()) throw IllegalStateException("Fichier de récupération absent.")
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            record.originalName.substringAfterLast('.', "").lowercase()
        ) ?: "application/octet-stream"
        val safeName = record.originalName.ifBlank { "fichier_restauré" }
        val parentId = DocumentsContract.getTreeDocumentId(targetTree)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(targetTree, parentId)
        val created = DocumentsContract.createDocument(context.contentResolver, parentUri, mime, safeName)
            ?: throw IllegalStateException("Impossible de créer le fichier de destination.")
        try {
            FileInputStream(encryptedFile).use { fileInput ->
                val iv = ByteArray(IV_BYTES)
                if (fileInput.read(iv) != IV_BYTES) throw IllegalStateException("Copie chiffrée invalide.")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(TAG_BITS, iv))
                CipherInputStream(fileInput, cipher).use { decrypted ->
                    context.contentResolver.openOutputStream(created, "w")?.use { output ->
                        decrypted.copyTo(output)
                    } ?: throw IllegalStateException("Impossible d'écrire le fichier restauré.")
                }
            }
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, created) }
            throw error
        }
        return created
    }

    private fun queryName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }
}
