package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class DroideCliTest {
    @Test fun preservesQuotedAndEscapedArguments() {
        assertEquals(listOf("open", "folder/a b.py"), DroideCli.parse("droide open 'folder/a b.py'"))
        assertEquals(listOf("open", "a b.py"), DroideCli.parse("droide open a\\ b.py"))
        assertEquals(listOf("pkg", "search", ""), DroideCli.parse("droide pkg search \"\""))
    }

    @Test fun doesNotStripCommandPrefixesOrExpandShellSyntax() {
        assertEquals(listOf("droide-other", "open"), DroideCli.parse("droide-other open"))
        assertEquals(listOf("open", "\$(touch x)"), DroideCli.parse("droide open '\$(touch x)'"))
    }

    @Test fun rejectsIncompleteQuotesAndMultilineCommands() {
        for (text in listOf("droide open 'bad", "droide open bad\\", "droide pkg list\nother", "a\u0000b", "x".repeat(16_385))) {
            try { DroideCli.parse(text); fail("Expected parser rejection") } catch (_: IllegalArgumentException) { }
        }
    }
}
