package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

 
object DeclarativeSnippetRegistry {
    private const val MAX_SNIPPET_FILE_BYTES = 2 * 1024 * 1024
    private const val MAX_SNIPPETS_PER_FILE = 512
    private val json = Json { ignoreUnknownKeys = true }
    private val byExtension = linkedMapOf<String, Map<String, List<CompletionCandidate>>>()

    @Synchronized
    fun register(extensionId: String, root: File, contributions: List<DroideSnippetContribution>) {
        val languageMap = linkedMapOf<String, MutableList<CompletionCandidate>>()
        contributions.forEach { contribution ->
            val file = resolve(root, contribution.path)
            val candidates = parse(file).take(MAX_SNIPPETS_PER_FILE)
            languageMap.getOrPut(contribution.language) { mutableListOf() }.addAll(candidates)
        }
        byExtension[extensionId] = languageMap.mapValues { (_, items) -> items.take(MAX_SNIPPETS_PER_FILE) }
    }

    @Synchronized
    fun unregister(extensionId: String) { byExtension.remove(extensionId) }

    @Synchronized
    fun candidates(languageId: String, prefix: String): List<CompletionCandidate> {
        if (prefix.isBlank()) return emptyList()
        return byExtension.values.asSequence()
            .flatMap { it[languageId].orEmpty().asSequence() }
            .filter { (it.filterText ?: it.label).startsWith(prefix, ignoreCase = true) }
            .distinctBy { it.label to it.insertText }
            .take(48)
            .toList()
    }

    private fun parse(file: File): List<CompletionCandidate> {
        if (!file.isFile || file.length() !in 1..MAX_SNIPPET_FILE_BYTES.toLong()) return emptyList()
        val root = runCatching { json.parseToJsonElement(BoundedJsonc.stripComments(file.readText())) as? JsonObject }.getOrNull() ?: return emptyList()
        return root.entries.asSequence().take(MAX_SNIPPETS_PER_FILE).flatMap { (label, raw) ->
            val obj = raw as? JsonObject ?: return@flatMap emptySequence()
            val prefixes = when (val value = obj["prefix"]) {
                is JsonPrimitive -> listOfNotNull(value.contentOrNull)
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                else -> emptyList()
            }.filter { it.isNotBlank() && it.length <= 80 }.take(16)
            val body = when (val value = obj["body"]) {
                is JsonPrimitive -> value.contentOrNull
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("\n")
                else -> null
            }?.take(ProfessionalSnippets.MAX_SNIPPET_CHARS) ?: return@flatMap emptySequence()
            val detail = (obj["description"] as? JsonPrimitive)?.contentOrNull?.take(240) ?: "extension snippet"
            prefixes.asSequence().map { prefix ->
                CompletionCandidate(
                    label = label.take(120),
                    insertText = body,
                    detail = detail,
                    kind = 15,
                    filterText = prefix,
                    origin = CompletionOrigin.SNIPPET,
                    isSnippet = true,
                )
            }
        }.toList()
    }

    private fun resolve(root: File, relative: String): File {
        val base = root.canonicalFile
        val target = File(base, DeclarativeVsixParser.normalizeBundlePath(relative)).canonicalFile
        require(target.path.startsWith(base.path + File.separator)) { "Snippet path escaped extension root" }
        return target
    }
}
