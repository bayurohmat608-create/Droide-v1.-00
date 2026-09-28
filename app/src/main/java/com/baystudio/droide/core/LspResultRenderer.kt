package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

 
internal object LspResultRenderer {
    fun render(op: String, result: JsonElement): String {
        if (result is JsonNull) return "(no result)"
        return when (op) {
            "hover" -> renderHover(result)
            "completion" -> renderCompletion(result)
            "goToDefinition", "findReferences", "goToImplementation" -> renderLocations(result)
            "documentSymbol", "workspaceSymbol" -> renderSymbols(result)
            else -> result.toString()
        }
    }

    private fun renderHover(result: JsonElement): String {
        val contents = (result as? JsonObject)?.get("contents") ?: return result.toString()
        return markupText(contents).ifBlank { "(no hover)" }
    }

    private fun markupText(el: JsonElement): String = when (el) {
        is JsonPrimitive -> el.contentOrNull.orEmpty()
        is JsonArray -> el.joinToString("\n") { markupText(it) }
        is JsonObject -> el["value"]?.jsonPrimitive?.contentOrNull
            ?: el["language"]?.jsonPrimitive?.contentOrNull?.let { lang ->
                "```$lang\n${el["value"]?.jsonPrimitive?.contentOrNull.orEmpty()}\n```"
            }
            ?: el.toString()
        else -> ""
    }

    private fun renderCompletion(result: JsonElement): String {
        val items = when (result) {
            is JsonArray -> result
            is JsonObject -> result["items"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return items.take(100).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val label = o["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val detail = o["detail"]?.jsonPrimitive?.contentOrNull
            if (detail.isNullOrBlank()) label else "$label — $detail"
        }.joinToString("\n").ifBlank { "(no completions)" }
    }

    private fun renderLocations(result: JsonElement): String {
        val arr = when (result) {
            is JsonArray -> result
            is JsonObject -> JsonArray(listOf(result))
            else -> JsonArray(emptyList())
        }
        return arr.take(100).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val uri = o["uri"]?.jsonPrimitive?.contentOrNull
                ?: o["targetUri"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val range = (o["range"] ?: o["targetSelectionRange"]) as? JsonObject
            val start = range?.get("start") as? JsonObject
            val line = (start?.get("line")?.jsonPrimitive?.intOrNull ?: 0) + 1
            val col = (start?.get("character")?.jsonPrimitive?.intOrNull ?: 0) + 1
            "$uri:$line:$col"
        }.joinToString("\n").ifBlank { "(no locations)" }
    }

    private fun renderSymbols(result: JsonElement): String {
        val arr = result as? JsonArray ?: return "(no symbols)"
        return arr.take(200).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val kind = o["kind"]?.jsonPrimitive?.intOrNull?.toString().orEmpty()
            if (kind.isBlank()) name else "$name (kind $kind)"
        }.joinToString("\n").ifBlank { "(no symbols)" }
    }
}
