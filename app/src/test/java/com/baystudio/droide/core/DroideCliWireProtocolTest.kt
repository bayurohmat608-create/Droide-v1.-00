package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DroideCliWireProtocolTest {
    @Test fun requestRoundTripPreservesUnicodeAndQuotingAsLiteralArgv() {
        val expected = listOf("pkg", "info", "runtime.swift@6.4.0 β", "literal ' quote")
        val bytes = ByteArrayOutputStream().also { DroideCliWireProtocol.writeRequest(it, expected) }.toByteArray()
        assertEquals(expected, DroideCliWireProtocol.readRequest(ByteArrayInputStream(bytes)))
    }

    @Test fun structuredResponseRoundTripPreservesExitAndStreams() {
        val expected = DroideCliExecutionResult(4, stdout = "progress ✓\n", stderr = "transaction gagal β\n")
        val bytes = ByteArrayOutputStream().also { DroideCliWireProtocol.writeResponse(it, expected) }.toByteArray()
        assertEquals(expected, DroideCliWireProtocol.readResponse(ByteArrayInputStream(bytes)))
    }

    @Test fun malformedUtf8IsRejected() {
        val bytes = byteArrayOf(
            'D'.code.toByte(), 'R'.code.toByte(), 'Q'.code.toByte(), '1'.code.toByte(),
            0, 0, 0, 1,
            0, 0, 0, 2,
            0xC3.toByte(), 0x28,
        )
        assertThrows(Exception::class.java) { DroideCliWireProtocol.readRequest(ByteArrayInputStream(bytes)) }
    }

    @Test fun oversizedRequestIsRejectedBeforeAllocationEscapesProtocolLimit() {
        val huge = "x".repeat(DroideCliWireProtocol.MAX_ARGUMENT_BYTES)
        val args = listOf("pkg", huge, huge, huge, huge)
        assertThrows(IllegalArgumentException::class.java) {
            DroideCliWireProtocol.writeRequest(ByteArrayOutputStream(), args)
        }
    }

    @Test fun oversizedOutputIsUtf8SafeAndBounded() {
        val value = "🙂".repeat(30_000)
        val bytes = ByteArrayOutputStream().also {
            DroideCliWireProtocol.writeResponse(it, DroideCliExecutionResult(0, stdout = value))
        }.toByteArray()
        val decoded = DroideCliWireProtocol.readResponse(ByteArrayInputStream(bytes))
        assertTrue(decoded.stdout.toByteArray(Charsets.UTF_8).size <= DroideCliWireProtocol.MAX_STREAM_BYTES)
        assertTrue(decoded.stdout.endsWith("[output truncated]\n"))
    }
}
