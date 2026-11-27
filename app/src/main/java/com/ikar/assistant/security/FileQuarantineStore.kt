package com.ikar.assistant.security

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted, app-private quarantine for a user-authorized SAF document.
 * The original is never removed by quarantine(); removal is a separate,
 * explicit operation that verifies the source still matches the encrypted copy.
 */
object FileQuarantineStore {
    private const val KEY_ALIAS = "ikar_file_quarantine_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAX_FILE_BYTES = 100L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L
    private const val MAGIC_0 = 73 // I
    private const val MAGIC_1 = 75 // K
    private const val MAGIC_2 = 81 // Q
    private const val MAGIC_3 = 1

    data class Item(val id: String, val name: String, val sizeBytes: Long, val sha256: String) {
        val size: Long get() = sizeBytes
    }
    data class QuarantineOutcome(val item: Item, val originalRemoved: Boolean)

    private fun directory(context: Context): File =
        File(context.noBackupFilesDir, "ikar-file-quarantine").apply { mkdirs() }

    private fun secretKey(): javax.crypto.SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
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

    private fun digestSource(context: Context, uri: Uri): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                require(size <= MAX_FILE_BYTES) { "Limite de quarantaine : 100 Mo par fichier." }
                digest.update(buffer, 0, count)
            }
        } ?: throw IllegalStateException("Impossible de lire le fichier source.")
        return size to digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun totalStoredBytes(context: Context): Long =
        directory(context).listFiles()?.filter { it.isFile && it.extension == "ikq" }
            ?.sumOf { it.length() } ?: 0L

    @Synchronized
    fun quarantine(context: Context, sourceUri: Uri): Item {
        val document = DocumentFile.fromSingleUri(context, sourceUri)
            ?: throw IllegalArgumentException("Document source inaccessible.")
        val name = document.name?.take(180)?.ifBlank { "fichier" } ?: "fichier"
        val id = UUID.randomUUID().toString()
        val dir = directory(context)
        val encryptedFile = File(dir, "$id.ikq")
        val temporary = File(dir, "$id.tmp")
        val metadata = File(dir, "$id.json")
        val existing = totalStoredBytes(context)
        require(existing < MAX_TOTAL_BYTES) { "Quarantaine pleine (512 Mo). Libère de l'espace avant de continuer." }
        require(dir.usableSpace >= 16L * 1024L * 1024L) { "Espace libre insuffisant pour créer la copie chiffrée." }

        var total = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            require(iv.size == IV_BYTES) { "Paramètres de chiffrement inattendus." }

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                temporary.outputStream().buffered().use { raw ->
                    raw.write(byteArrayOf(MAGIC_0.toByte(), MAGIC_1.toByte(), MAGIC_2.toByte(), MAGIC_3.toByte()))
                    raw.write(iv)
                    CipherOutputStream(raw, cipher).use { encrypted ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_FILE_BYTES) { "Limite de quarantaine : 100 Mo par fichier." }
                            require(existing + total <= MAX_TOTAL_BYTES) { "Quarantaine pleine (512 Mo)." }
                            require(dir.usableSpace >= 8L * 1024L * 1024L) { "Espace libre insuffisant ; copie interrompue." }
                            digest.update(buffer, 0, count)
                            encrypted.write(buffer, 0, count)
                        }
                    }
                }
            } ?: throw IllegalStateException("Impossible d'ouvrir le fichier source.")

            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            require(temporary.renameTo(encryptedFile)) { "Impossible de finaliser la copie chiffrée." }

            // Verify authentication tag, byte count and plaintext hash before registering the item.
            val verified = verifyEncryptedFile(encryptedFile, hash, total)
            require(verified) { "La vérification de la copie chiffrée a échoué." }

            val item = Item(id, name, total, hash)
            metadata.writeText(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("size", item.sizeBytes)
                    .put("sha256", item.sha256)
                    .put("created_at", System.currentTimeMillis())
                    .toString(),
                Charsets.UTF_8
            )
            return item
        } catch (error: Exception) {
            temporary.delete()
            encryptedFile.delete()
            metadata.delete()
            throw error
        }
    }

    /**
     * Copies and verifies first, then attempts SAF deletion only if the original
     * still has the same size and SHA-256. A failed delete leaves the backup intact.
     */
    @Synchronized
    fun quarantineAndRemove(
        context: Context,
        sourceUri: Uri,
        expectedSha256: String? = null
    ): QuarantineOutcome {
        val item = quarantine(context, sourceUri)
        if (expectedSha256 != null && !item.sha256.equals(expectedSha256, ignoreCase = true)) {
            return QuarantineOutcome(item, false)
        }
        val current = digestSource(context, sourceUri)
        if (current.first != item.sizeBytes || current.second != item.sha256) {
            return QuarantineOutcome(item, false)
        }
        val document = DocumentFile.fromSingleUri(context, sourceUri)
            ?: return QuarantineOutcome(item, false)
        val removed = runCatching { document.delete() }.getOrDefault(false)
        return QuarantineOutcome(item, removed)
    }

    private fun verifyEncryptedFile(file: File, expectedHash: String, expectedSize: Long): Boolean {
        file.inputStream().buffered().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4 || !magic.contentEquals(
                    byteArrayOf(MAGIC_0.toByte(), MAGIC_1.toByte(), MAGIC_2.toByte(), MAGIC_3.toByte())
                )
            ) return false
            val iv = ByteArray(IV_BYTES)
            if (input.read(iv) != IV_BYTES) return false
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            CipherInputStream(input, cipher).use { decrypted ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = decrypted.read(buffer)
                    if (count < 0) break
                    size += count
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            return size == expectedSize && hash == expectedHash
        }
    }

    fun list(context: Context): List<Item> =
        directory(context).listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText(Charsets.UTF_8))
                    Item(
                        id = json.getString("id"),
                        name = json.optString("name", "fichier"),
                        sizeBytes = json.optLong("size", 0L),
                        sha256 = json.optString("sha256", "")
                    ).takeIf { it.id.matches(Regex("[0-9a-fA-F-]{36}")) && File(directory(context), "${it.id}.ikq").isFile }
                }.getOrNull()
            }
            ?.sortedByDescending { File(directory(context), "${it.id}.json").lastModified() }
            .orEmpty()

    fun restore(context: Context, itemId: String, destinationTree: Uri): Uri {
        require(itemId.matches(Regex("[0-9a-fA-F-]{36}"))) { "Identifiant de quarantaine invalide." }
        val item = list(context).firstOrNull { it.id == itemId }
            ?: throw IllegalArgumentException("Copie chiffrée introuvable.")
        val folder = DocumentFile.fromTreeUri(context, destinationTree)
            ?: throw IllegalArgumentException("Dossier de destination inaccessible.")
        require(folder.isDirectory && folder.canWrite()) { "Dossier de destination non accessible en écriture." }
        val encryptedFile = File(directory(context), "$itemId.ikq")
        val output = folder.createFile("application/octet-stream", safeName(item.name))
            ?: throw IllegalStateException("Impossible de créer le fichier restauré.")
        try {
            encryptedFile.inputStream().buffered().use { input ->
                val magic = ByteArray(4)
                require(input.read(magic) == 4 && magic.contentEquals(
                    byteArrayOf(MAGIC_0.toByte(), MAGIC_1.toByte(), MAGIC_2.toByte(), MAGIC_3.toByte())
                )) { "Format de quarantaine invalide." }
                val iv = ByteArray(IV_BYTES)
                require(input.read(iv) == IV_BYTES) { "En-tête de quarantaine incomplet." }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                context.contentResolver.openOutputStream(output.uri, "w")?.use { target ->
                    CipherInputStream(input, cipher).use { decrypted ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = decrypted.read(buffer)
                            if (count < 0) break
                            size += count
                            digest.update(buffer, 0, count)
                            target.write(buffer, 0, count)
                        }
                    }
                } ?: throw IllegalStateException("Impossible d'écrire le fichier restauré.")
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                require(size == item.sizeBytes && hash == item.sha256) { "La vérification du fichier restauré a échoué." }
            }
            return output.uri
        } catch (error: Exception) {
            runCatching { output.delete() }
            throw error
        }
    }

    private fun safeName(value: String): String =
        value.replace("/", "_").replace("\\", "_")
            .replace(Regex("[\\p{Cntrl}]"), "_")
            .take(180).ifBlank { "fichier-restaure.bin" }
}
