package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class DroideSyntaxSemanticEngineTest {
    @Test fun lexicalScannerTracksMultilineStateAndCommonKinds() {
        val source = """fun greet(name: String) {
            val message = "hello"
            /* block
               comment */
            println(message)
        }""".trimIndent()
        val result = DroideSyntaxSemanticEngine.highlight(source, "kotlin")
        assertFalse(result.skippedForSize)
        assertTrue(result.spans.any { it.line == 0 && it.kind == DroideHighlightKind.KEYWORD })
        assertTrue(result.spans.any { it.line == 0 && it.kind == DroideHighlightKind.FUNCTION })
        assertTrue(result.spans.any { it.line == 1 && it.kind == DroideHighlightKind.STRING })
        assertTrue(result.spans.any { it.line == 2 && it.kind == DroideHighlightKind.COMMENT })
        assertTrue(result.spans.any { it.line == 3 && it.kind == DroideHighlightKind.COMMENT })
    }

    @Test fun semanticTokensCarryCategoryAndModifiersOverLexicalSpan() {
        val result = DroideSyntaxSemanticEngine.highlight(
            "foo(value)",
            "kotlin",
            listOf(LspSemanticToken(0, 0, 3, "parameter", setOf("definition", "deprecated"))),
        )
        assertEquals(DroideHighlightKind.FUNCTION, result.spans.first { !it.semantic && it.start == 0 && it.end == 3 }.kind)
        val semantic = result.spans.first { it.semantic && it.start == 0 && it.end == 3 }
        assertEquals(DroideHighlightKind.VARIABLE, semantic.kind)
        assertTrue(semantic.bold)
        assertTrue(semantic.strike)
    }

    @Test fun oversizedDocumentsSkipLocalHighlightingSafely() {
        val result = DroideSyntaxSemanticEngine.highlight(
            "x".repeat(DroideSyntaxSemanticEngine.MAX_HIGHLIGHT_CHARS + 1),
            "kotlin",
        )
        assertTrue(result.skippedForSize)
        assertTrue(result.spans.isEmpty())
    }
}
