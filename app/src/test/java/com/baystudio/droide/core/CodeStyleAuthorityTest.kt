package com.baystudio.droide.core

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeStyleAuthorityTest {
    @Test fun editorConfigWinsAndSupportsNestedOverrides() {
        val root = createTempDirectory("droide-code-style").toFile()
        try {
            File(root, ".editorconfig").writeText(
                """
                root = true
                [*]
                indent_style = tab
                tab_width = 3
                indent_size = tab
                ij_continuation_indent_size = 6
                [*.kt]
                indent_style = space
                indent_size = 2
                """.trimIndent()
            )
            File(root, "src").mkdirs()
            File(root, "src/Main.kt").writeText("fun main() {}\n")
            File(root, "src/main.go").writeText("package main\n")
            val authority = CodeStyleAuthority(root)
            val defaults = CodeStyleDefaults(detectIndentation = false)
            val kotlin = authority.resolve("src/Main.kt", "kotlin", "", defaults)
            assertEquals(IndentStyle.SPACES, kotlin.indentStyle)
            assertEquals(2, kotlin.indentSize)
            assertEquals(3, kotlin.tabWidth)
            assertEquals(6, kotlin.continuationIndent)
            val go = authority.resolve("src/main.go", "go", "", defaults)
            assertEquals(IndentStyle.TABS, go.indentStyle)
            assertEquals(3, go.tabWidth)
        } finally { root.deleteRecursively() }
    }

    @Test fun detectionFindsTwoSpaceAndTabs() {
        assertEquals(IndentStyle.SPACES to 2, CodeStyleAuthority.detect("a\n  b\n    c\n"))
        assertEquals(IndentStyle.TABS to 4, CodeStyleAuthority.detect("a\n\tb\n\t\tc\n"))
    }

    @Test fun builtInSnippetIndentationUsesResolvedProfile() {
        val profile = CodeStyleProfile(IndentStyle.SPACES, 4, 2, 4, "test")
        assertEquals("if (x) {\n  y\n}", CodeStyleSnippets.normalize("if (x) {\n    y\n}", profile))
        val tabs = profile.copy(indentStyle = IndentStyle.TABS)
        assertTrue(CodeStyleSnippets.normalize("if (x) {\n    y\n}", tabs).contains("\n\ty"))
    }
}
