package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfessionalSnippetsTest {
    @Test fun builtInsExposeRealTextMateMarkers() {
        val funSnippet = ProfessionalSnippets.builtIns("kotlin", "fu").first { it.label == "fun" }
        assertTrue(funSnippet.isSnippet)
        assertTrue(funSnippet.insertText.contains("${'$'}{1:name}"))
        assertTrue(funSnippet.insertText.contains("${'$'}0"))
    }

    @Test fun shellBackticksAreAlwaysLiteralizedBeforeSora() {
        val safe = ProfessionalSnippets.sanitizeForSora("val x = `do-not-run`\n${'$'}{1:name}${'$'}0")
        assertTrue(safe.contains("\\`do-not-run\\`"))
        assertFalse(safe.contains("val x = `do-not-run`"))
    }

    @Test fun malformedSnippetFallbackNeverLeaksControlMarkers() {
        val broken = "call(${'$'}{1:arg ${'$'}UNFINISHED and ${'$'}2)"
        val plain = ProfessionalSnippets.plainTextFallback(broken)
        assertFalse(plain.contains("${'$'}{"))
        assertFalse(Regex("\\$[A-Za-z0-9_]").containsMatchIn(plain))
    }

    @Test fun autoImportBeforePrimaryRangeShiftsSnippetOffsets() {
        val original = "package demo\n\nfu\n"
        val primary = TextRangeEdit(3, 1, 3, 3, "snippet")
        val importEdit = TextRangeEdit(2, 1, 2, 1, "import demo.Foo\n")
        val plan = ProfessionalSnippets.planSnippetEdits(original, primary, listOf(importEdit))
        assertEquals(original.indexOf("fu") + "import demo.Foo\n".length, plan.adjustedStart)
        assertEquals(plan.adjustedStart + 2, plan.adjustedEnd)
    }

    @Test fun overlappingAdditionalEditIsRejected() {
        val original = "fun main() {}"
        val primary = TextRangeEdit(1, 1, 1, 4, "snippet")
        val result = runCatching {
            ProfessionalSnippets.planSnippetEdits(original, primary, listOf(TextRangeEdit(1, 2, 1, 2, "x")))
        }
        assertTrue(result.isFailure)
    }
}
