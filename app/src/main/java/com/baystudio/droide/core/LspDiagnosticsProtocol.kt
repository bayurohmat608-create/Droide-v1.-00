package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

 
internal object LspDiagnosticsProtocol {
    fun parsePull(result: JsonElement?): List<LspDiagnostic>? {
        val obj = result as? JsonObject ?: return null
        val items = obj["items"] as? JsonArray ?: return null
        return items.mapNotNull(::parse)
    }

    fun parse(el: JsonElement): LspDiagnostic? {
        val obj = el as? JsonObject ?: return null
        val range = obj["range"] as? JsonObject ?: return null
        val start = range["start"] as? JsonObject ?: return null
        val end = range["end"] as? JsonObject ?: start
        return LspDiagnostic(
            line = (start["line"]?.jsonPrimitive?.intOrNull ?: 0) + 1,
            column = (start["character"]?.jsonPrimitive?.intOrNull ?: 0) + 1,
            endLine = (end["line"]?.jsonPrimitive?.intOrNull ?: 0) + 1,
            endColumn = (end["character"]?.jsonPrimitive?.intOrNull ?: 0) + 1,
            severity = obj["severity"]?.jsonPrimitive?.intOrNull,
            message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty().take(2_000),
            source = obj["source"]?.jsonPrimitive?.contentOrNull,
        )
    }

    fun format(path: String, items: List<LspDiagnostic>): String = buildString {
        appendLine("Diagnostics for $path:")
        items.take(100).forEach { d ->
            val sev = when (d.severity) {
                1 -> "error"
                2 -> "warning"
                3 -> "info"
                4 -> "hint"
                else -> "diagnostic"
            }
            appendLine("${d.line}:${d.column} [$sev${d.source?.let { "/$it" } ?: ""}] ${d.message}")
        }
    }.trimEnd()
}
