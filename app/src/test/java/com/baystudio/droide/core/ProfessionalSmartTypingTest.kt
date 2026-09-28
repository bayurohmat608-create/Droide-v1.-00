package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class ProfessionalSmartTypingTest {
    @Test fun structuralPairOvertypeNeedsAnUnmatchedOpen() {
        assertTrue(ProfessionalSmartTyping.shouldOvertypeClosing("call(x)", 6, ')', "kotlin"))
        assertFalse(ProfessionalSmartTyping.shouldOvertypeClosing("x)", 1, ')', "kotlin"))
        assertFalse(ProfessionalSmartTyping.shouldOvertypeClosing("x)", 1, ')', "markdown"))
    }

    @Test fun quotesOvertypeOnlyWhenCurrentLineHasOpenQuote() {
        assertTrue(ProfessionalSmartTyping.shouldOvertypeClosing("\"abc\"", 4, '"', "kotlin"))
        assertFalse(ProfessionalSmartTyping.shouldOvertypeClosing("abc\"", 3, '"', "kotlin"))
        assertFalse(ProfessionalSmartTyping.shouldAutoPairQuote("dont", 4, '\'', "javascript", false))
        assertTrue(ProfessionalSmartTyping.shouldAutoPairQuote("", 0, '\'', "javascript", false))
    }

    @Test fun emptyPairDeletionIsLanguageAware() {
        assertEquals(0..1, ProfessionalSmartTyping.pairedDeleteRange("()", 1, "kotlin"))
        assertEquals(0..1, ProfessionalSmartTyping.pairedDeleteRange("\"\"", 1, "json"))
        assertNull(ProfessionalSmartTyping.pairedDeleteRange("<>", 1, "kotlin"))
    }

    @Test fun indentationIsConservativeAndLanguageAware() {
        assertEquals(4, ProfessionalSmartTyping.indentAdvance("kotlin", "if (ok) {"))
        assertEquals(4, ProfessionalSmartTyping.indentAdvance("python", "if ok:"))
        assertEquals(4, ProfessionalSmartTyping.indentAdvance("yaml", "server:"))
        assertEquals(0, ProfessionalSmartTyping.indentAdvance("python", "# note:"))
        assertEquals(0, ProfessionalSmartTyping.indentAdvance("kotlin", "return value"))
    }

    @Test fun smartEnterRecognizesOnlyConfiguredMatchingPairs() {
        assertTrue(ProfessionalSmartTyping.betweenMatchingPair("kotlin", "if (x) {", "}"))
        assertTrue(ProfessionalSmartTyping.betweenMatchingPair("json", "[", "]"))
        assertFalse(ProfessionalSmartTyping.betweenMatchingPair("markdown", "{", "}"))
        assertFalse(ProfessionalSmartTyping.betweenMatchingPair("kotlin", "<", ">"))
    }
}
