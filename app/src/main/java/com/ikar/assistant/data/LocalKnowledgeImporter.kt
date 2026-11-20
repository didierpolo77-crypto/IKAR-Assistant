package com.ikar.assistant.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.roundToInt

data class ImportedKnowledgeDocument(
    val title: String,
    val text: String,
    val pagesRead: Int? = null
)

/**
 * Imports user-selected text and PDF files into the private local knowledge store.
 * PDFs are OCR-processed locally with ML Kit; no document content is uploaded.
 */
object LocalKnowledgeImporter {
    private const val MAX_TEXT_BYTES = 512_000
    private const val MAX_PDF_BYTES = 25L * 1024L * 1024L
    private const val MAX_PDF_PAGES = 20
    private const val MAX_EXTRACTED_CHARS = 100_000
    private const val MAX_RENDER_WIDTH = 1200

    fun importDocument(context: Context, uri: Uri): ImportedKnowledgeDocument {
        val resolver = context.contentResolver
        val title = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.takeIf { it.isNotBlank() } ?: "Document sans nom"
        val mime = resolver.getType(uri).orEmpty().lowercase()
        val isPdf = mime == "application/pdf" || title.endsWith(".pdf", ignoreCase = true)

        return if (isPdf) importPdf(context, uri, title) else importText(context, uri, title)
    }

    private fun importText(context: Context, uri: Uri, title: String): ImportedKnowledgeDocument {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_TEXT_BYTES) {
                    throw IllegalArgumentException("Ce document texte dépasse la limite de 512 Ko.")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw FileNotFoundException("Impossible de lire ce document.")
        val text = String(bytes, StandardCharsets.UTF_8)
            .replace("\u0000", "")
            .trim()
        if (text.isBlank()) throw IllegalArgumentException("Ce document ne contient aucun texte lisible.")
        return ImportedKnowledgeDocument(title, text.take(MAX_EXTRACTED_CHARS))
    }

    private fun importPdf(context: Context, uri: Uri, title: String): ImportedKnowledgeDocument {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("Impossible d'ouvrir ce PDF.")
        descriptor.use { pfd ->
            if (pfd.statSize > MAX_PDF_BYTES) {
                throw IllegalArgumentException("Ce PDF dépasse la limite de 25 Mo.")
            }
            val renderer = PdfRenderer(pfd)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                if (renderer.pageCount <= 0) throw IllegalArgumentException("Ce PDF ne contient aucune page.")
                val pagesToRead = minOf(renderer.pageCount, MAX_PDF_PAGES)
                val extracted = StringBuilder()
                for (index in 0 until pagesToRead) {
                    renderer.openPage(index).use { page ->
                        val scale = minOf(1f, MAX_RENDER_WIDTH.toFloat() / max(1, page.width))
                        val width = max(1, (page.width * scale).roundToInt())
                        val height = max(1, (page.height * scale).roundToInt())
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            val matrix = Matrix().apply { postScale(scale, scale) }
                            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val image = InputImage.fromBitmap(bitmap, 0)
                            val result = Tasks.await(recognizer.process(image))
                            if (result.text.isNotBlank()) {
                                extracted.append("\n\n[Page ").append(index + 1).append("]\n")
                                    .append(result.text)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    if (extracted.length >= MAX_EXTRACTED_CHARS) break
                }
                if (extracted.isBlank()) {
                    throw IllegalArgumentException("Aucun texte n'a pu être extrait de ce PDF. Il est peut-être vide ou illisible.")
                }
                if (renderer.pageCount > pagesToRead) {
                    extracted.append("\n\n[Import partiel : seules les ").append(pagesToRead)
                        .append(" premières pages sur ").append(renderer.pageCount).append(" ont été lues.]")
                }
                return ImportedKnowledgeDocument(title, extracted.toString().take(MAX_EXTRACTED_CHARS), pagesToRead)
            } finally {
                recognizer.close()
                renderer.close()
            }
        }
    }
}
