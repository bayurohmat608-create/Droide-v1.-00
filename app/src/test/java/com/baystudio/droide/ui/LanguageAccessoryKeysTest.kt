package com.baystudio.droide.ui

import com.baystudio.droide.core.LanguageRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageAccessoryKeysTest {
    @Test
    fun everyRegisteredLanguageHasExplicitQuickKeyProfile() {
        val missing = LanguageRegistry.all.map { it.id }.filterNot { it in LanguageAccessoryKeys.supportedLanguageIds() }
        assertTrue("Missing language accessory profiles: $missing", missing.isEmpty())
    }

    @Test
    fun javascriptAndCssUseDifferentLanguageSpecificActions() {
        val js = LanguageAccessoryKeys.forLanguage("javascript").map { it.label }
        val css = LanguageAccessoryKeys.forLanguage("css").map { it.label }
        assertTrue(listOf("const", "let", "function").all { it in js })
        assertTrue(listOf("rule", "display", "@media").all { it in css })
        assertTrue("function" !in css)
    }

    @Test
    fun structuralActionsCarryRealSnippets() {
        val function = LanguageAccessoryKeys.forLanguage("javascript")
            .filterIsInstance<EditorAccessoryAction.Snippet>()
            .first { it.label == "function" }
        assertTrue("${'$'}{1:name}" in function.source)
        assertTrue("${'$'}0" in function.source)
    }
}
