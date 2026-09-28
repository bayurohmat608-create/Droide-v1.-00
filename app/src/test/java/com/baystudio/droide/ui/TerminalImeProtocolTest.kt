package com.baystudio.droide.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalImeProtocolTest {
    @Test
    fun `delete requests are bounded without shrinking normal keyboard requests`() {
        assertEquals(0, TerminalImeProtocol.boundedDeleteCount(-1))
        assertEquals(0, TerminalImeProtocol.boundedDeleteCount(0))
        assertEquals(1, TerminalImeProtocol.boundedDeleteCount(1))
        assertEquals(12, TerminalImeProtocol.boundedDeleteCount(12))
        assertEquals(
            TerminalImeProtocol.MAX_DELETE_KEYS_PER_CALLBACK,
            TerminalImeProtocol.boundedDeleteCount(Int.MAX_VALUE),
        )
    }

    @Test
    fun `soft keyboard line feeds become terminal carriage returns`() {
        assertEquals("echo hi\rnext", TerminalImeProtocol.normalizeCommittedText("echo hi\nnext"))
        assertEquals("already\rterminal", TerminalImeProtocol.normalizeCommittedText("already\rterminal"))
        assertEquals("", TerminalImeProtocol.normalizeCommittedText(null))
    }

    @Test
    fun `unicode committed text is preserved`() {
        assertEquals("🙂漢字", TerminalImeProtocol.normalizeCommittedText("🙂漢字"))
    }
}
