package com.baystudio.droide.core

import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import java.io.File
import java.time.Duration
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.registry.IGrammarSource





object DeclarativeTextMateRegistry {
    private const val MAX_TOTAL_NANOS = 120_000_000L
    private val lineBudget = Duration.ofMillis(8)

    private data class LoadedGrammar(
        val extensionId: String,
        val languageId: String,
        val scopeName: String,
        val registry: GrammarRegistry,
        val grammar: IGrammar,
    )

    private val byExtension = linkedMapOf<String, List<LoadedGrammar>>()

    @Synchronized
    fun register(
        extensionId: String,
        root: File,
        languages: List<DroideLanguageContribution>,
        contributions: List<DroideGrammarContribution>,
    ) {
        unregister(extensionId)
        val configByLanguage = languages.associate { it.id to it.configuration }
        val parent = GrammarRegistry.getInstance()
        val loaded = mutableListOf<LoadedGrammar>()
        contributions.forEach { contribution ->
            runCatching {
                val grammarFile = resolve(root, contribution.path)
                require(grammarFile.isFile && grammarFile.length() <= DeclarativeVsixParser.MAX_ENTRY_BYTES) { "Grammar resource is invalid" }
                val source = IGrammarSource.fromFile(grammarFile)
                val config = configByLanguage[contribution.language]?.let { resolve(root, it) }
                val definition = if (config != null && config.isFile) {
                    DefaultGrammarDefinition.withLanguageConfiguration(
                        source,
                        config.absolutePath,
                        contribution.language,
                        contribution.scopeName,
                    )
                } else {
                    DefaultGrammarDefinition.withGrammarSource(source, contribution.language, contribution.scopeName)
                }.withEmbeddedLanguages(contribution.embeddedLanguages)
                val registry = GrammarRegistry(parent)
                val grammar = registry.loadGrammar(definition)
                loaded += LoadedGrammar(extensionId, contribution.language, contribution.scopeName, registry, grammar)
            }
        }
        if (loaded.isNotEmpty()) byExtension[extensionId] = loaded
    }

    @Synchronized
    fun unregister(extensionId: String) {
        byExtension.remove(extensionId)?.forEach { runCatching { it.registry.dispose() } }
    }

    @Synchronized
    fun hasGrammar(languageId: String): Boolean = byExtension.values.any { list -> list.any { it.languageId == languageId } }

    fun highlight(text: String, languageId: String, maxSpans: Int): List<DroideHighlightSpan>? {
        val loaded = synchronized(this) {
            byExtension.values.asSequence().flatMap { it.asSequence() }.firstOrNull { it.languageId == languageId }
        } ?: return null
        val deadline = System.nanoTime() + MAX_TOTAL_NANOS
        val spans = ArrayList<DroideHighlightSpan>(minOf(maxSpans, text.length / 5 + 8))
        var state: IStateStack? = null
        return runCatching {
            text.split('\n').forEachIndexed { lineIndex, line ->
                if (System.nanoTime() > deadline || spans.size >= maxSpans) error("TextMate tokenization budget exceeded")
                val result = loaded.grammar.tokenizeLine(line, state, lineBudget)
                if (result.isStoppedEarly) error("TextMate line tokenization budget exceeded")
                state = result.ruleStack
                result.tokens.forEach { token ->
                    if (spans.size >= maxSpans) return@forEach
                    val start = token.startIndex.coerceIn(0, line.length)
                    val end = token.endIndex.coerceIn(start, line.length)
                    if (end > start) {
                        val style = styleForScopes(token.scopes)
                        if (style.kind != DroideHighlightKind.NORMAL || style.bold || style.italic) {
                            spans += DroideHighlightSpan(
                                line = lineIndex,
                                start = start,
                                end = end,
                                kind = style.kind,
                                bold = style.bold,
                                italic = style.italic,
                            )
                        }
                    }
                }
            }
            spans
        }.getOrNull()
    }

    private data class ScopeStyle(
        val kind: DroideHighlightKind,
        val bold: Boolean = false,
        val italic: Boolean = false,
    )

    private fun styleForScopes(scopes: List<String>): ScopeStyle {
        val joined = scopes.joinToString(" ").lowercase()
        return when {
            "comment" in joined -> ScopeStyle(DroideHighlightKind.COMMENT, italic = true)
            "string.regexp" in joined || "regexp" in joined -> ScopeStyle(DroideHighlightKind.REGEXP)
            "string" in joined -> ScopeStyle(DroideHighlightKind.STRING)
            "constant.numeric" in joined || "number" in joined -> ScopeStyle(DroideHighlightKind.NUMBER)
            "keyword" in joined || "storage.modifier" in joined -> ScopeStyle(DroideHighlightKind.KEYWORD, bold = true)
            "storage.type" in joined || "entity.name.type" in joined || "entity.name.class" in joined ||
                "support.type" in joined -> ScopeStyle(DroideHighlightKind.TYPE)
            "entity.name.function" in joined || "support.function" in joined || "meta.function-call" in joined ->
                ScopeStyle(DroideHighlightKind.FUNCTION)
            "entity.name.tag" in joined || "meta.tag" in joined -> ScopeStyle(DroideHighlightKind.TAG)
            "entity.other.attribute-name" in joined -> ScopeStyle(DroideHighlightKind.ATTRIBUTE)
            "variable.other.property" in joined || "support.type.property-name" in joined -> ScopeStyle(DroideHighlightKind.PROPERTY)
            "variable" in joined -> ScopeStyle(DroideHighlightKind.VARIABLE)
            "entity.name.decorator" in joined || "meta.annotation" in joined -> ScopeStyle(DroideHighlightKind.ANNOTATION)
            "keyword.operator" in joined || "punctuation" in joined -> ScopeStyle(DroideHighlightKind.OPERATOR)
            else -> ScopeStyle(DroideHighlightKind.NORMAL)
        }
    }

    private fun resolve(root: File, relative: String): File {
        val base = root.canonicalFile
        val target = File(base, DeclarativeVsixParser.normalizeBundlePath(relative)).canonicalFile
        require(target.path.startsWith(base.path + File.separator)) { "Grammar path escaped extension root" }
        return target
    }
}
