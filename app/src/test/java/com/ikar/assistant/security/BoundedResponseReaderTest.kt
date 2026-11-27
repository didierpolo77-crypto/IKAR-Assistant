package com.ikar.assistant.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class BoundedResponseReaderTest {
    @Test
    fun readsUtf8ResponseWithinLimit() {
        val result = BoundedResponseReader.readUtf8(
            ByteArrayInputStream("IKAR sécurité".toByteArray(Charsets.UTF_8)),
            128,
            "test"
        )
        assertEquals("IKAR sécurité", result)
    }

    @Test
    fun acceptsResponseExactlyAtLimit() {
        val result = BoundedResponseReader.readUtf8(
            ByteArrayInputStream("12345".toByteArray()),
            5,
            "test"
        )
        assertEquals("12345", result)
    }

    @Test
    fun rejectsResponseLargerThanLimit() {
        assertThrows(IOException::class.java) {
            BoundedResponseReader.readUtf8(
                ByteArrayInputStream("123456".toByteArray()),
                5,
                "test"
            )
        }
    }
}
