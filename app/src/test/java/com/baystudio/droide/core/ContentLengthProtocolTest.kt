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

    @Test fun malformedUtf8IsRejectedWithoutChangingProtocolIdentifiers() {
        val input = ByteArrayInputStream("Content-Length: 2\r\n\r\n".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28))
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { ContentLengthProtocol.readMessage(input) }
    }

    @Test fun zeroLengthBulkReadDoesNotSpinOrSkipPayloadBytes() {
        val input = object : ByteArrayInputStream("Content-Length: 2\r\n\r\n{}".toByteArray()) {
            var first = true
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (first) { first = false; return 0 }
                return super.read(buffer, offset, length)
            }
        }
        assertEquals("{}", ContentLengthProtocol.readMessage(input))
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
