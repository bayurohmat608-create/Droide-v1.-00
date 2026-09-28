package com.baystudio.droide.core

import kotlinx.serialization.json.*

 
data class LspCompletionItem(
    val label: String,
    val insertText: String,
    val detail: String? = null,
    val documentation: String? = null,
    val kind: Int? = null,
    val filterText: String? = null,
    val sortText: String? = null,
    val preselect: Boolean = false,
    val editStartLine: Int? = null,
    val editStartColumn: Int? = null,
    val editEndLine: Int? = null,
    val editEndColumn: Int? = null,
    val additionalTextEdits: List<TextRangeEdit> = emptyList(),
    val isSnippet: Boolean = false,
)

 
internal object LspCompletionProtocol {
    fun parse(result: JsonElement): List<LspCompletionItem> {
        val items = when (result) {
            is JsonArray -> result
            is JsonObject -> result["items"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return items.take(240).mapNotNull(::parseItem)
            .distinctBy { Triple(it.label, it.insertText, it.editStartLine to it.editStartColumn) }
    }

    private fun parseItem(el: JsonElement): LspCompletionItem? {
        val o = el as? JsonObject ?: return null
        val label = when (val value = o["label"]) {
            is JsonPrimitive -> value.contentOrNull
            is JsonObject -> value["label"]?.jsonPrimitive?.contentOrNull
            else -> null
        }?.take(500) ?: return null
        val textEdit = o["textEdit"] as? JsonObject
        val insertText = o["insertText"]?.jsonPrimitive?.contentOrNull
            ?: textEdit?.get("newText")?.jsonPrimitive?.contentOrNull
            ?: label
        val insertTextFormat = o["insertTextFormat"]?.jsonPrimitive?.intOrNull ?: 1
        val requestedSnippet = insertTextFormat == 2
        val sanitizedSnippet = if (requestedSnippet) {
            runCatching { ProfessionalSnippets.sanitizeForSora(insertText.take(ProfessionalSnippets.MAX_SNIPPET_CHARS)) }.getOrNull()
        } else null
        val isSnippet = requestedSnippet && sanitizedSnippet != null
        val safeInsert = sanitizedSnippet
            ?: if (requestedSnippet) ProfessionalSnippets.plainTextFallback(insertText)
            else insertText.take(ProfessionalSnippets.MAX_SNIPPET_CHARS)
        val range = (textEdit?.get("range") ?: textEdit?.get("replace") ?: textEdit?.get("insert")) as? JsonObject
        val rangeStart = range?.get("start") as? JsonObject
        val rangeEnd = range?.get("end") as? JsonObject
        val parsedAdditional = (o["additionalTextEdits"] as? JsonArray)
            ?.take(32)
            ?.mapNotNull(::parseTextEdit)
            .orEmpty()
        val additional = parsedAdditional.takeIf { edits ->
            edits.sumOf { it.newText.length.toLong() } <= 200_000L
        }.orEmpty()
        return LspCompletionItem(
            label = label,
            insertText = safeInsert,
            detail = o["detail"]?.jsonPrimitive?.contentOrNull?.take(1_000),
            documentation = o["documentation"]?.let(::markupText)?.take(4_000),
            kind = o["kind"]?.jsonPrimitive?.intOrNull,
            filterText = o["filterText"]?.jsonPrimitive?.contentOrNull?.take(500),
            sortText = o["sortText"]?.jsonPrimitive?.contentOrNull?.take(500),
            preselect = o["preselect"]?.jsonPrimitive?.booleanOrNull == true,
            editStartLine = rangeStart?.get("line")?.jsonPrimitive?.intOrNull?.plus(1),
            editStartColumn = rangeStart?.get("character")?.jsonPrimitive?.intOrNull?.plus(1),
            editEndLine = rangeEnd?.get("line")?.jsonPrimitive?.intOrNull?.plus(1),
            editEndColumn = rangeEnd?.get("character")?.jsonPrimitive?.intOrNull?.plus(1),
            additionalTextEdits = additional,
            isSnippet = isSnippet,
        )
    }

    private fun parseTextEdit(el: JsonElement): TextRangeEdit? {
        val edit = el as? JsonObject ?: return null
        val range = edit["range"] as? JsonObject ?: return null
        val start = range["start"] as? JsonObject ?: return null
        val end = range["end"] as? JsonObject ?: return null
        val newText = edit["newText"]?.jsonPrimitive?.contentOrNull ?: ""
        if (newText.length > 50_000) return null
        return TextRangeEdit(
            (start["line"]?.jsonPrimitive?.intOrNull ?: 0) + 1,
            (start["character"]?.jsonPrimitive?.intOrNull ?: 0) + 1,
            (end["line"]?.jsonPrimitive?.intOrNull ?: 0) + 1,
            (end["character"]?.jsonPrimitive?.intOrNull ?: 0) + 1,
            newText,
        )
    }

    private fun markupText(el: JsonElement): String = when (el) {
        is JsonPrimitive -> el.contentOrNull.orEmpty()
        is JsonArray -> el.joinToString("\n") { markupText(it) }
        is JsonObject -> el["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
        else -> ""
    }
}
