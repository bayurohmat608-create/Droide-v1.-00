package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfessionalCompletionTest {
    @Test fun extractsPrefixAndTriggerCharacter() {
        assertEquals("crea", ProfessionalCompletion.prefixFromLine("val x = crea", 12))
        assertEquals(".", ProfessionalCompletion.triggerCharacter("service.", 8))
        assertEquals(null, ProfessionalCompletion.triggerCharacter("service", 7))
    }

    @Test fun avoidsLanguageServerWorkForPlainWhitespace() {
        assertFalse(ProfessionalCompletion.shouldQueryLsp("", " "))
        assertTrue(ProfessionalCompletion.shouldQueryLsp("member", null))
        assertTrue(ProfessionalCompletion.shouldQueryLsp("", "."))
    }

    @Test fun localKeywordsAndDocumentWordsAreRankedAndBounded() {
        val kotlin = ProfessionalCompletion.localCandidates(
            languageId = "kotlin",
            documentText = "fun existingThing() = Unit\nval customDocumentIdentifier = 1",
            cursorIndex = 20,
            prefix = "fu",
        )
        assertEquals("fun", kotlin.first().label)
        assertTrue(kotlin.first().isSnippet)
        assertEquals(CompletionOrigin.SNIPPET, kotlin.first().origin)

        val document = ProfessionalCompletion.localCandidates(
            languageId = "kotlin",
            documentText = "val customDocumentIdentifier = 1",
            cursorIndex = 10,
            prefix = "custom",
        )
        assertTrue(document.any { it.label == "customDocumentIdentifier" && it.origin == CompletionOrigin.DOCUMENT })
        assertTrue(document.size <= ProfessionalCompletion.MAX_LOCAL_ITEMS)
    }

    @Test fun camelCaseIntentBeatsCaseInsensitivePrefixAndLspDuplicatesCollapse() {
        val ranked = ProfessionalCompletion.rank(
            listOf(
                CompletionCandidate("createApplication", origin = CompletionOrigin.LSP),
                CompletionCandidate("call", origin = CompletionOrigin.LSP),
            ),
            prefix = "cA",
        )
        assertEquals("createApplication", ranked.first().label)

        val filtered = ProfessionalCompletion.filterLsp(
            listOf(
                LspCompletionItem(label = "format", insertText = "format"),
                LspCompletionItem(label = "fooBar", insertText = "fooBar", preselect = true),
                LspCompletionItem(label = "fooBar", insertText = "fooBar"),
                LspCompletionItem(label = "other", insertText = "other"),
            ),
            prefix = "fo",
        )
        assertEquals("fooBar", filtered.first().label)
        assertEquals(1, filtered.count { it.label == "fooBar" })
        assertFalse(filtered.any { it.label == "other" })
    }

    @Test fun allBuiltInLanguagesHaveAdaptiveLocalProfiles() {
        val builtIns = LanguageRegistry.all.map { it.id }.toSet()
        val covered = ProfessionalCompletion.localProfileLanguageIds()
        assertEquals(builtIns, covered)
    }

    @Test fun tomlHtmlCssAndUnknownLanguageFallbackStayUsefulWithoutLsp() {
        assertEquals("requires-py", ProfessionalCompletion.prefixFromLine("toml", "requires-py", 11))
        val toml = ProfessionalCompletion.localCandidates(
            languageId = "toml",
            documentText = "[project]\nversion = \"0.1\"",
            cursorIndex = 10,
            prefix = "ver",
        )
        assertTrue(toml.any { it.label == "version" })

        val tomlValue = ProfessionalCompletion.localCandidates(
            languageId = "toml",
            documentText = "enabled = ",
            cursorIndex = 10,
            prefix = "",
            triggerCharacter = "=",
        )
        assertTrue(tomlValue.any { it.label == "true" && it.origin == CompletionOrigin.CONTEXT })

        val html = ProfessionalCompletion.localCandidates("html", "<d", 2, "d")
        assertTrue(html.any { it.label == "div" })
        val css = ProfessionalCompletion.localCandidates("css", "display: f", 10, "f")
        assertTrue(css.any { it.label == "flex" })

        val pluginLanguage = ProfessionalCompletion.localCandidates(
            languageId = "third-party-language",
            documentText = "customDocumentSymbol = 1",
            cursorIndex = 10,
            prefix = "custom",
        )
        assertTrue(pluginLanguage.any { it.label == "customDocumentSymbol" })
    }

    @Test fun languageSpecificPrefixAndTriggerRulesPreserveMobileSyntax() {
        assertEquals("font-s", ProfessionalCompletion.prefixFromLine("css", "font-s", 6))
        assertEquals("section", ProfessionalCompletion.prefixFromLine("latex", "\\section", 8))
        assertEquals("interface", ProfessionalCompletion.prefixFromLine("objectivec", "@interface", 10))
        assertEquals("<", ProfessionalCompletion.triggerCharacter("html", "<", 1))
        assertTrue(ProfessionalCompletion.shouldQueryLsp("toml", "", "="))
        assertFalse(ProfessionalCompletion.shouldQueryLsp("toml", "", " "))
    }

}
