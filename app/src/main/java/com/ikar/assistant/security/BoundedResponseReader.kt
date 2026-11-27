package com.ikar.assistant.security

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Reads bounded network responses without first allocating an unbounded String. */
internal object BoundedResponseReader {
    fun readUtf8(input: InputStream, maxBytes: Int, sourceName: String): String {
        require(maxBytes > 0) { "La limite de réponse doit être positive." }
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (count > maxBytes - total) {
                throw IOException("Réponse $sourceName trop volumineuse (limite ${maxBytes} octets).")
            }
            output.write(buffer, 0, count)
            total += count
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }
}
