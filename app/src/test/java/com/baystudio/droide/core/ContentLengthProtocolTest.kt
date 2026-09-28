package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ContentLengthProtocolTest {
    @Test fun roundTripsUtf8Payload() {
        val output = ByteArrayOutputStream()
        val message = "{\"text\":\"halo 世界\"}"
        ContentLengthProtocol.writeMessage(output, message)
        assertEquals(message, ContentLengthProtocol.readMessage(ByteArrayInputStream(output.toByteArray())))
    }

    @Test fun returnsNullAtCleanEof() {
        assertNull(ContentLengthProtocol.readMessage(ByteArrayInputStream(byteArrayOf())))
    }

    @Test fun rejectsDuplicateOrOversizedHeaders() {
        val duplicate = "Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}".toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            ContentLengthProtocol.readMessage(ByteArrayInputStream(duplicate))
        }
        val oversized = "Content-Length: ${ContentLengthProtocol.MAX_MESSAGE_BYTES + 1}\r\n\r\n".toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            ContentLengthProtocol.readMessage(ByteArrayInputStream(oversized))
        }
    }
}
