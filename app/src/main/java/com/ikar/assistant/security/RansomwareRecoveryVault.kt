package com.ikar.assistant.security

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted, app-private recovery copy for files the user explicitly selects.
 *
 * This protects the recovery copy from ordinary writes by other apps through
 * Android's application sandbox. It does NOT intercept or deny writes to the
 * original shared-storage document.
 */
object RansomwareRecoveryVault {
    private const val KEY_ALIAS = "ikar_ransomware_recovery_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val MAX_BACKUP_BYTES = 100L * 1024L * 1024L
    private const val MAX_VAULT_BYTES = 512L * 1024L * 1024L

    private fun storedBytes(context: Context): Long =
        vaultDir(context).listFiles()?.filter { it.isFile && it.extension == "ikv" }
            ?.sumOf { it.length() } ?: 0L

    data class Backup(
        val id: String,
        val originalName: String,
        val sizeBytes: Long,
        val storedAt: Long
    )

    private fun vaultDir(context: Context): File =
        File(context.noBackupFilesDir, "ransomware-recovery").apply { mkdirs() }

    private fun key(): javax.crypto.SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    /** Makes an encrypted recovery copy of a user-authorized SAF document. */
    @Synchronized
    fun backup(context: Context, documentUri: Uri): Backup {
        val resolver = context.contentResolver
        val document = DocumentFile.fromSingleUri(context, documentUri)
            ?: throw IllegalArgumentException("Document non accessible.")
        val name = document.name?.take(180)?.ifBlank { "document" } ?: "document"
        val id = java.util.UUID.randomUUID().toString()
        val destination = File(vaultDir(context), "$id.ikv")
        val temporary = File(vaultDir(context), "$id.tmp")
        val existingBytes = storedBytes(context)
        if (existingBytes >= MAX_VAULT_BYTES) {
            throw IllegalStateException("Coffre plein (512 Mo). Supprime des anciennes sauvegardes avant d'en ajouter.")
        }
        if (vaultDir(context).usableSpace < 16L * 1024L * 1024L) {
            throw IllegalStateException("Espace libre insuffisant pour créer une sauvegarde protégée.")
        }
        var total = 0L
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            require(iv.size == IV_BYTES) { "Paramètres de chiffrement inattendus." }
            resolver.openInputStream(documentUri)?.use { input ->
                temporary.outputStream().buffered().use { raw ->
                    raw.write(byteArrayOf('I'.code.toByte(), 'K'.code.toByte(), 'V'.code.toByte(), 1))
                    raw.write(iv)
                    javax.crypto.CipherOutputStream(raw, cipher).use { encrypted ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_BACKUP_BYTES) { "Limite de sauvegarde par fichier : 100 Mo." }
                            require(existingBytes + total <= MAX_VAULT_BYTES) {
                                "Coffre plein (512 Mo). Supprime des anciennes sauvegardes avant d'en ajouter."
                            }
                            require(vaultDir(context).usableSpace >= 8L * 1024L * 1024L) {
                                "Espace libre insuffisant : sauvegarde interrompue pour protéger le stockage."
                            }
                            encrypted.write(buffer, 0, count)
                        }
                    }
                }
            } ?: throw IllegalStateException("Impossible de lire le document.")
            if (!temporary.renameTo(destination)) {
                throw IllegalStateException("Impossible de finaliser la copie protégée.")
            }
            // Keep the original display name in app-private metadata so restoration
            // does not silently turn every recovered document into an opaque .bin file.
            File(vaultDir(context), "$id.meta").writeText(name, Charsets.UTF_8)
            return Backup(id, name, total, System.currentTimeMillis())
        } catch (error: Exception) {
            temporary.delete()
            destination.delete()
            File(vaultDir(context), "$id.meta").delete()
            throw error
        }
    }

    /** Decrypts a recovery copy to a NEW SAF document; never overwrites the source. */
    @Synchronized
    fun restoreToNewDocument(context: Context, backupId: String, destinationFolder: Uri): Uri {
        require(backupId.matches(Regex("[0-9a-fA-F-]{36}"))) { "Identifiant de sauvegarde invalide." }
        val encryptedFile = File(vaultDir(context), "$backupId.ikv")
        require(encryptedFile.isFile) { "Copie de récupération introuvable." }
        val folder = DocumentFile.fromTreeUri(context, destinationFolder)
            ?: throw IllegalArgumentException("Dossier de destination inaccessible.")
        require(folder.isDirectory && folder.canWrite()) { "Dossier non accessible en écriture." }

        encryptedFile.inputStream().buffered().use { input ->
            val header = ByteArray(4)
            require(input.read(header) == 4 && header.contentEquals(byteArrayOf(73, 75, 86, 1))) {
                "Format de sauvegarde invalide."
            }
            val iv = ByteArray(IV_BYTES)
            require(input.read(iv) == IV_BYTES) { "En-tête de sauvegarde incomplet." }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            val storedName = File(vaultDir(context), "$backupId.meta")
                .takeIf { it.isFile }
                ?.readText(Charsets.UTF_8)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val safeName = (storedName ?: "ikar-restored-$backupId.bin")
                .replace('/', '_')
                .replace('\\\\', '_')
                .replace(Regex("[\\\\p{Cntrl}]"), "_")
                .take(180)
                .ifBlank { "ikar-restored-$backupId.bin" }
            val outputDoc = folder.createFile("application/octet-stream", safeName)
                ?: throw IllegalStateException("Impossible de créer le nouveau fichier restauré.")
            try {
                context.contentResolver.openOutputStream(outputDoc.uri, "w")?.use { output ->
                    javax.crypto.CipherInputStream(input, cipher).use { decrypted ->
                        decrypted.copyTo(output, 32 * 1024)
                    }
                } ?: throw IllegalStateException("Impossible d'écrire le fichier restauré.")
            } catch (error: Exception) {
                outputDoc.delete()
                throw error
            }
            return outputDoc.uri
        }
    }

    fun list(context: Context): List<String> =
        vaultDir(context).listFiles()
            ?.filter { it.isFile && it.extension == "ikv" }
            ?.sortedBy { it.lastModified() }
            ?.map { it.nameWithoutExtension }
            .orEmpty()
}
