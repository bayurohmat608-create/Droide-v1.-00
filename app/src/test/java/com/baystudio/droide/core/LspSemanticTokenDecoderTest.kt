package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class LspSemanticTokenDecoderTest {
    private val legend = LspSemanticTokenLegend(
        tokenTypes = listOf("variable", "function"),
        tokenModifiers = listOf("declaration", "deprecated"),
    )

    @Test fun decodesRelativeStreamAndModifiers() {
        val decoded = LspSemanticTokenDecoder.decode(
            listOf(0, 1, 3, 0, 1, 0, 5, 4, 1, 2).map(Int::toLong),
            legend,
        )
        assertEquals(2, decoded.size)
        assertEquals(1, decoded[0].startCharacter)
        assertEquals("variable", decoded[0].type)
        assertTrue("declaration" in decoded[0].modifiers)
        assertEquals(6, decoded[1].startCharacter)
        assertEquals("function", decoded[1].type)
        assertTrue("deprecated" in decoded[1].modifiers)
    }

    @Test fun malformedAndOversizedStreamsFailClosed() {
        assertTrue(LspSemanticTokenDecoder.decode(listOf(0L, 0L, 1L), legend).isEmpty())
        assertTrue(LspSemanticTokenDecoder.decode(listOf(0L, 0L, 1L, 99L, 0L), legend).isEmpty())
        assertTrue(LspSemanticTokenDecoder.decode(listOf(0L, 0L, 1L, 0L, -1L), legend).isEmpty())
        val oversized = ArrayList<Long>((LspSemanticTokenDecoder.MAX_TOKENS + 1) * 5)
        repeat(LspSemanticTokenDecoder.MAX_TOKENS + 1) {
            oversized += 0L; oversized += 1L; oversized += 1L; oversized += 0L; oversized += 0L
        }
        assertTrue(LspSemanticTokenDecoder.decode(oversized, legend).isEmpty())
    }
}
