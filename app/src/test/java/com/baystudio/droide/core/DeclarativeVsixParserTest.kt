package com.baystudio.droide.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeclarativeVsixParserTest {
    @Test
    fun acceptsBoundedDataOnlyVsixAndCollectsReferencedResources() {
        val file = makeVsix(
            packageJson = """{
              "name":"demo-lang","publisher":"droide-test","version":"1.2.3",
              "contributes":{
                "languages":[{"id":"demo","extensions":[".demo"],"filenames":["DemoFile"],"configuration":"language.json"}],
                "grammars":[{"language":"demo","scopeName":"source.demo","path":"syntaxes/demo.tmLanguage.json"}],
                "snippets":[{"language":"demo","path":"snippets/demo.code-snippets"}],
                "themes":[{"id":"night","label":"Demo Night","uiTheme":"vs-dark","path":"themes/night.json"}]
              }
            }""".trimIndent(),
            entries = mapOf(
                "extension/language.json" to "{}",
                "extension/syntaxes/demo.tmLanguage.json" to "{\"scopeName\":\"source.demo\",\"patterns\":[]}",
                "extension/snippets/demo.code-snippets" to "{\"Print\":{\"prefix\":\"print\",\"body\":\"print(${'$'}1)\"}}",
                "extension/themes/night.json" to "{\"include\":\"./base.json\",\"colors\":{\"editor.background\":\"#101010\"}}",
                "extension/themes/base.json" to "{\"colors\":{\"editor.foreground\":\"#eeeeee\"}}",
            ),
        )
        try {
            val parsed = DeclarativeVsixParser.parse(file)
            assertEquals("droide-test.demo-lang", parsed.manifest.id)
            assertEquals(ExtensionRuntimeKind.DECLARATIVE, parsed.manifest.runtime)
            assertEquals(1, parsed.manifest.contributes.languages.size)
            assertTrue("themes/base.json" in parsed.resourcePaths)
            assertTrue("syntaxes/demo.tmLanguage.json" in parsed.resourcePaths)
        } finally { file.delete() }
    }

    @Test
    fun rejectsExecutableEntryPoint() {
        val file = makeVsix("""{"name":"bad","publisher":"test","version":"1.0.0","main":"./out/extension.js","contributes":{"languages":[{"id":"bad","extensions":[".bad"]}]}}""")
        try { assertThrows(IllegalArgumentException::class.java) { DeclarativeVsixParser.parse(file) } } finally { file.delete() }
    }

    @Test
    fun rejectsExecutableContributionSurface() {
        val file = makeVsix("""{"name":"badcmd","publisher":"test","version":"1.0.0","contributes":{"commands":[{"command":"bad.run","title":"Run"}]}}""")
        try { assertThrows(IllegalArgumentException::class.java) { DeclarativeVsixParser.parse(file) } } finally { file.delete() }
    }

    @Test
    fun rejectsUnsupportedDeclarativeContributionInsteadOfPartialInstall() {
        val file = makeVsix("""{"name":"icons","publisher":"test","version":"1.0.0","contributes":{"iconThemes":[{"id":"x","path":"icons.json"}]}}""")
        try { assertThrows(IllegalArgumentException::class.java) { DeclarativeVsixParser.parse(file) } } finally { file.delete() }
    }

    @Test
    fun rejectsArchiveTraversal() {
        val file = makeVsix(
            """{"name":"safe","publisher":"test","version":"1.0.0","contributes":{"languages":[{"id":"safe","extensions":[".safe"]}]}}""",
            mapOf("extension/../escape.txt" to "bad"),
        )
        try { assertThrows(IllegalArgumentException::class.java) { DeclarativeVsixParser.parse(file) } } finally { file.delete() }
    }

    @Test
    fun resolvesThemeParentIncludesWithoutEscapingPackage() {
        assertEquals("themes/base.json", DeclarativeVsixParser.resolveRelativeBundlePath("themes/nested/night.json", "../base.json"))
        assertThrows(IllegalArgumentException::class.java) {
            DeclarativeVsixParser.resolveRelativeBundlePath("theme.json", "../../escape.json")
        }
    }

    private fun makeVsix(packageJson: String, entries: Map<String, String> = emptyMap()): File {
        val file = File.createTempFile("droide-declarative-", ".vsix")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put("extension/package.json", packageJson)
            entries.forEach(::put)
        }
        return file
    }
}
