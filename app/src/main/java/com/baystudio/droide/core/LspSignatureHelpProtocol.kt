package com.baystudio.droide.core

import kotlinx.serialization.json.*

data class LspSignatureParameter(
    val label: String,
    val documentation: String? = null,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
)

data class LspSignatureInformation(
    val label: String,
    val documentation: String? = null,
    val parameters: List<LspSignatureParameter> = emptyList(),
    val activeParameter: Int? = null,
)

data class LspSignatureHelp(
    val signatures: List<LspSignatureInformation>,
    val activeSignature: Int = 0,
    val activeParameter: Int = 0,
)

data class LspSignatureTriggers(
    val triggerCharacters: Set<String> = emptySet(),
    val retriggerCharacters: Set<String> = emptySet(),
)

enum class LspSignatureTriggerKind(val wireValue: Int) {
    INVOKED(1),
    TRIGGER_CHARACTER(2),
    CONTENT_CHANGE(3),
}

 
internal object LspSignatureHelpProtocol {
    const val MAX_SIGNATURES = 12
    const val MAX_PARAMETERS = 64
    const val MAX_LABEL_CHARS = 2_000
    const val MAX_DOCUMENTATION_CHARS = 1_500

    fun parse(result: JsonElement?): LspSignatureHelp? {
        val root = result as? JsonObject ?: return null
        val rawSignatures = root["signatures"] as? JsonArray ?: return null
        val signatures = rawSignatures.take(MAX_SIGNATURES).mapNotNull { raw ->
            val obj = raw as? JsonObject ?: return@mapNotNull null
            val label = obj["label"]?.jsonPrimitive?.contentOrNull
                ?.take(MAX_LABEL_CHARS)
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val parameters = (obj["parameters"] as? JsonArray)
                ?.take(MAX_PARAMETERS)
                ?.mapNotNull { parameter -> parseParameter(parameter, label) }
                .orEmpty()
            val active = obj["activeParameter"]?.jsonPrimitive?.intOrNull
                ?.takeIf { it >= 0 }
                ?.let { if (parameters.isEmpty()) null else it.coerceAtMost(parameters.lastIndex) }
            LspSignatureInformation(
                label = label,
                documentation = documentation(obj["documentation"]),
                parameters = parameters,
                activeParameter = active,
            )
        }
        if (signatures.isEmpty()) return null
        val activeSignature = (root["activeSignature"]?.jsonPrimitive?.intOrNull ?: 0)
            .coerceIn(0, signatures.lastIndex)
        val selected = signatures[activeSignature]
        val requestedParameter = selected.activeParameter
            ?: root["activeParameter"]?.jsonPrimitive?.intOrNull
            ?: 0
        val activeParameter = if (selected.parameters.isEmpty()) 0
        else requestedParameter.coerceIn(0, selected.parameters.lastIndex)
        return LspSignatureHelp(signatures, activeSignature, activeParameter)
    }

    fun context(
        kind: LspSignatureTriggerKind,
        triggerCharacter: String?,
        isRetrigger: Boolean,
        previous: LspSignatureHelp?,
    ): JsonObject = buildJsonObject {
        put("triggerKind", kind.wireValue)
        if (kind == LspSignatureTriggerKind.TRIGGER_CHARACTER && !triggerCharacter.isNullOrEmpty()) {
            put("triggerCharacter", triggerCharacter.take(8))
        }
        put("isRetrigger", isRetrigger)
        if (isRetrigger && previous != null) put("activeSignatureHelp", toJson(previous))
    }

    private fun parseParameter(raw: JsonElement, signatureLabel: String): LspSignatureParameter? {
        val obj = raw as? JsonObject ?: return null
        val labelElement = obj["label"] ?: return null
        val documentation = documentation(obj["documentation"])
        if (labelElement is JsonPrimitive && labelElement.isString) {
            val label = labelElement.content.take(500)
            if (label.isEmpty()) return null
            val start = signatureLabel.indexOf(label).takeIf { it >= 0 }
            return LspSignatureParameter(
                label = label,
                documentation = documentation,
                startOffset = start,
                endOffset = start?.plus(label.length),
            )
        }
        val offsets = labelElement as? JsonArray ?: return null
        if (offsets.size != 2) return null
        val start = offsets[0].jsonPrimitive.intOrNull ?: return null
        val end = offsets[1].jsonPrimitive.intOrNull ?: return null
        if (start < 0 || end < start || end > signatureLabel.length) return null
        return LspSignatureParameter(
            label = signatureLabel.substring(start, end).take(500),
            documentation = documentation,
            startOffset = start,
            endOffset = end,
        )
    }

    private fun documentation(raw: JsonElement?): String? {
        val text = when (raw) {
            is JsonPrimitive -> raw.contentOrNull
            is JsonObject -> raw["value"]?.jsonPrimitive?.contentOrNull
            else -> null
        } ?: return null
        return text.trim().take(MAX_DOCUMENTATION_CHARS).takeIf { it.isNotBlank() }
    }

    private fun toJson(help: LspSignatureHelp): JsonObject = buildJsonObject {
        put("signatures", buildJsonArray {
            help.signatures.take(MAX_SIGNATURES).forEach { signature ->
                add(buildJsonObject {
                    put("label", signature.label.take(MAX_LABEL_CHARS))
                    signature.documentation?.let { put("documentation", it.take(MAX_DOCUMENTATION_CHARS)) }
                    signature.activeParameter?.let { put("activeParameter", it) }
                    put("parameters", buildJsonArray {
                        signature.parameters.take(MAX_PARAMETERS).forEach { parameter ->
                            add(buildJsonObject {
                                if (parameter.startOffset != null && parameter.endOffset != null) {
                                    put("label", buildJsonArray {
                                        add(parameter.startOffset)
                                        add(parameter.endOffset)
                                    })
                                } else {
                                    put("label", parameter.label.take(500))
                                }
                                parameter.documentation?.let { put("documentation", it.take(MAX_DOCUMENTATION_CHARS)) }
                            })
                        }
                    })
                })
            }
        })
        put("activeSignature", help.activeSignature)
        put("activeParameter", help.activeParameter)
    }
}
